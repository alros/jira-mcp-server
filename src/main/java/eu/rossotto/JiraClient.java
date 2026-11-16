package eu.rossotto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Minimal Jira REST client built on the JDK HTTP client (no reflection, native-friendly).
 *
 * <p>Works with both deployments:
 * <ul>
 *   <li><b>Cloud</b>: {@code GET /rest/api/3/search/jql} (token-based paging, ADF descriptions)</li>
 *   <li><b>Server / Data Center</b>: {@code GET /rest/api/2/search} (offset paging, wiki-markup descriptions)</li>
 * </ul>
 * The flavour is detected on first use (a 404 on the Cloud endpoint means Server/DC) and remembered.
 */
public class JiraClient {

    private static final Logger LOG = Logger.getLogger(JiraClient.class);
    private static final List<String> BASE_FIELDS = List.of("summary", "description");
    private static final Set<String> RESERVED = Set.of("key", "summary", "description", "link");
    private static final int PAGE_SIZE = 100;

    enum Flavour { CLOUD, SERVER }

    private final String baseUrl;
    private final String authHeader;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final List<String> extraFields;
    private volatile Flavour flavour;
    /** Output name (as configured) → Jira field id; resolved lazily via /rest/api/2/field. */
    private volatile Map<String, String> extraFieldIds;

    public JiraClient(String baseUrl, String username, String token, List<String> extraFields, Duration timeout,
                      ObjectMapper mapper) {
        this.extraFields = extraFields == null ? List.of() : List.copyOf(extraFields);
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.authHeader = (username == null || username.isBlank())
                ? "Bearer " + token
                : "Basic " + Base64.getEncoder()
                        .encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8));
        this.timeout = timeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String browseUrl(String key) {
        return baseUrl + "/browse/" + key;
    }

    /** Runs a JQL query and returns at most {@code limit} tickets; {@code hasMore} tells if more exist. */
    public SearchResult search(String jql, int limit) {
        Map<String, String> ids = fieldIds();
        if (flavour == null) {
            try {
                SearchResult r = searchCloud(jql, limit, ids);
                flavour = Flavour.CLOUD;
                return r;
            } catch (JiraException e) {
                if (e.status() != 404) {
                    throw e;
                }
                LOG.info("Cloud search endpoint not found, switching to Jira Server/Data Center API");
                flavour = Flavour.SERVER;
            }
        }
        return flavour == Flavour.CLOUD ? searchCloud(jql, limit, ids) : searchServer(jql, limit, ids);
    }

    public record SearchResult(List<Ticket> tickets, boolean hasMore) {
    }

    // ---------------------------------------------------------------- Cloud

    private SearchResult searchCloud(String jql, int limit, Map<String, String> ids) {
        List<Ticket> tickets = new ArrayList<>();
        String nextPageToken = null;
        boolean hasMore;
        do {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("jql", jql);
            params.put("fields", fieldsParam(ids));
            params.put("maxResults", String.valueOf(Math.min(PAGE_SIZE, limit - tickets.size())));
            if (nextPageToken != null) {
                params.put("nextPageToken", nextPageToken);
            }
            JsonNode page = get("/rest/api/3/search/jql", params);
            collect(page, tickets, limit, ids);
            nextPageToken = page.path("nextPageToken").asText(null);
            hasMore = nextPageToken != null && !page.path("isLast").asBoolean(false);
        } while (hasMore && tickets.size() < limit);
        return new SearchResult(tickets, hasMore);
    }

    // ---------------------------------------------------------- Server / DC

    private SearchResult searchServer(String jql, int limit, Map<String, String> ids) {
        List<Ticket> tickets = new ArrayList<>();
        int total;
        do {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("jql", jql);
            params.put("fields", fieldsParam(ids));
            params.put("startAt", String.valueOf(tickets.size()));
            params.put("maxResults", String.valueOf(Math.min(PAGE_SIZE, limit - tickets.size())));
            JsonNode page = get("/rest/api/2/search", params);
            int before = tickets.size();
            collect(page, tickets, limit, ids);
            total = page.path("total").asInt(tickets.size());
            if (tickets.size() == before) {
                break; // defensive: never loop on an empty page
            }
        } while (tickets.size() < Math.min(limit, total));
        return new SearchResult(tickets, total > tickets.size());
    }

    // ------------------------------------------------------- extra fields

    private static String fieldsParam(Map<String, String> ids) {
        return Stream.concat(BASE_FIELDS.stream(), ids.values().stream())
                .distinct().collect(Collectors.joining(","));
    }

    private static Map<String, JsonNode> extras(JsonNode fields, Map<String, String> ids) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        ids.forEach((name, id) -> out.put(name, FieldValues.simplify(fields.get(id))));
        return out;
    }

    /**
     * Maps each configured entry to a field id. Entries that already are ids are kept; others are looked up
     * by display name (case-insensitive) in the field catalogue. Unknown entries are passed through as-is,
     * so a typo shows up as a null value rather than a failure. The mapping is cached once the catalogue
     * has been read; if it cannot be read, entries are used as ids for this call and resolution is retried.
     */
    private Map<String, String> fieldIds() {
        Map<String, String> cached = extraFieldIds;
        if (cached != null) {
            return cached;
        }
        List<String> wanted = extraFields.stream().filter(f -> !RESERVED.contains(f)).toList();
        Map<String, String> resolved = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return extraFieldIds = resolved;
        }
        Map<String, String> catalogue = new HashMap<>(); // lower-cased id or name -> id
        try {
            JsonNode fields = get("/rest/api/2/field", Map.of());
            for (JsonNode f : fields) {
                String id = f.path("id").asText();
                catalogue.putIfAbsent(f.path("name").asText().toLowerCase(Locale.ROOT), id);
            }
            for (JsonNode f : fields) { // ids win over names
                String id = f.path("id").asText();
                catalogue.put(id.toLowerCase(Locale.ROOT), id);
            }
        } catch (JiraException e) {
            if (e.status() == 401) {
                throw e;
            }
            LOG.warnf("Cannot list Jira fields, using JIRA_FIELDS entries as ids: %s", e.getMessage());
            wanted.forEach(f -> resolved.put(f, f));
            return resolved;
        }
        for (String f : wanted) {
            String id = catalogue.get(f.toLowerCase(Locale.ROOT));
            if (id == null) {
                LOG.warnf("JIRA_FIELDS: unknown field '%s'", f);
                id = f;
            }
            resolved.put(f, id);
        }
        return extraFieldIds = resolved;
    }

    // ------------------------------------------------------------- helpers

    private void collect(JsonNode page, List<Ticket> into, int limit, Map<String, String> ids) {
        for (JsonNode issue : page.path("issues")) {
            if (into.size() >= limit) {
                return;
            }
            String key = issue.path("key").asText();
            JsonNode fields = issue.path("fields");
            into.add(new Ticket(
                    key,
                    fields.path("summary").asText(""),
                    PlainText.of(fields.get("description")),
                    browseUrl(key),
                    extras(fields, ids)));
        }
    }

    private JsonNode get(String path, Map<String, String> params) {
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path + "?" + query))
                .timeout(timeout)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new JiraException("Cannot reach Jira at " + baseUrl + ": " + reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JiraException("Interrupted while calling Jira");
        }
        int status = response.statusCode();
        if (status / 100 != 2) {
            throw new JiraException(status, describeError(status, response.body()));
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new JiraException(status, "Jira returned a non-JSON response (is JIRA_URL correct?)");
        }
    }

    private String describeError(int status, String body) {
        String details = jiraErrorMessages(body);
        String hint = switch (status) {
            case 400 -> "Invalid request (check the JQL syntax)";
            case 401 -> "Authentication failed (check JIRA_USERNAME / JIRA_TOKEN)";
            case 403 -> "Access denied (the account lacks permission, or CAPTCHA is required after failed logins)";
            case 404 -> "Not found";
            case 429 -> "Rate limited by Jira, retry later";
            default -> "Jira returned HTTP " + status;
        };
        return details.isEmpty() ? hint : hint + ": " + details;
    }

    /** Extracts Jira's {"errorMessages":[...],"errors":{...}} payload, if any. */
    private String jiraErrorMessages(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode json = mapper.readTree(body);
            List<String> messages = new ArrayList<>();
            json.path("errorMessages").forEach(m -> messages.add(m.asText()));
            json.path("errors").properties().forEach(e -> messages.add(e.getKey() + ": " + e.getValue().asText()));
            return String.join("; ", messages);
        } catch (IOException e) {
            return "";
        }
    }

    private static String stripTrailingSlash(String s) {
        String r = s.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
