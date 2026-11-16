package eu.rossotto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class JiraTools {

    private static final Logger LOG = Logger.getLogger(JiraTools.class);
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;

    @Inject
    JiraConfig config;

    @Inject
    ObjectMapper mapper;

    private volatile JiraClient client;

    @Tool(name = "fetch_tickets", description = """
            Search Jira issues with a JQL query and return them as JSON: an array of tickets, each with \
            "key" (ticket number, e.g. PROJ-123), "summary", "description" (plain text: formatting, images \
            and link targets stripped) and "link" (browser URL). Use it to read, triage or summarise tickets. \
            Examples: 'key = PROJ-123', 'project = PROJ AND status = "In Progress" ORDER BY updated DESC', \
            'assignee = currentUser() AND resolution = Unresolved', 'text ~ "login error" AND created >= -7d'.""")
    public String fetchTickets(
            @ToolArg(description = "JQL query, e.g. 'project = PROJ AND sprint in openSprints()'") String jql,
            @ToolArg(description = "Maximum number of tickets to return (1-" + MAX_LIMIT + ", default "
                    + DEFAULT_LIMIT + ")", required = false) Integer maxResults) {

        if (jql == null || jql.isBlank()) {
            throw new ToolCallException("The 'jql' argument is required");
        }
        int limit = maxResults == null ? DEFAULT_LIMIT : Math.clamp(maxResults, 1, MAX_LIMIT);
        try {
            JiraClient.SearchResult result = client().search(jql.strip(), limit);
            return toJson(result);
        } catch (JiraException e) {
            LOG.warnf("fetch_tickets failed: %s", e.getMessage());
            throw new ToolCallException(e.getMessage());
        }
    }

    private String toJson(JiraClient.SearchResult result) {
        ObjectNode root = mapper.createObjectNode();
        root.put("count", result.tickets().size());
        root.put("hasMore", result.hasMore());
        ArrayNode tickets = root.putArray("tickets");
        for (Ticket t : result.tickets()) {
            tickets.addObject()
                    .put("key", t.key())
                    .put("summary", t.summary())
                    .put("description", t.description())
                    .put("link", t.link())
                    .setAll(t.extra());
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new ToolCallException("Cannot serialise result: " + e.getMessage());
        }
    }

    private JiraClient client() {
        JiraClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = createClient();
                }
                c = client;
            }
        }
        return c;
    }

    private JiraClient createClient() {
        String url = config.url().map(String::strip).filter(s -> !s.isEmpty())
                .orElseThrow(() -> new JiraException("JIRA_URL is not set (e.g. https://your-company.atlassian.net)"));
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new JiraException("JIRA_URL must start with https:// (got '" + url + "')");
        }
        String token = config.token().filter(s -> !s.isBlank())
                .orElseThrow(() -> new JiraException("JIRA_TOKEN is not set"));
        List<String> fields = config.fields().orElse(List.of()).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        return new JiraClient(url, config.username().orElse(null), token, fields, config.timeout(), mapper);
    }
}
