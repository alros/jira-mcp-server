package eu.rossotto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private JiraClient start(String username, Handler handler) throws IOException {
        return start(username, List.of(), handler);
    }

    private JiraClient start(String username, List<String> fields, Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String uri = URLDecoder.decode(ex.getRequestURI().toString(), StandardCharsets.UTF_8);
            requests.add(uri);
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            handler.handle(ex, uri);
        });
        server.start();
        return new JiraClient("http://127.0.0.1:" + server.getAddress().getPort() + "/",
                username, "secret", fields, Duration.ofSeconds(5), MAPPER);
    }

    interface Handler {
        void handle(HttpExchange ex, String uri) throws IOException;
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String issue(String key, String description) {
        return """
                {"key":"%s","fields":{"summary":"Summary %s","description":%s}}""".formatted(key, key, description);
    }

    @Test
    void cloudPagesWithNextPageToken() throws IOException {
        String adf = """
                {"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"hello"}]}]}""";
        JiraClient client = start("me@acme.io", (ex, uri) -> {
            if (!uri.contains("nextPageToken")) {
                reply(ex, 200, "{\"issues\":[" + issue("A-1", adf) + "],\"nextPageToken\":\"p2\",\"isLast\":false}");
            } else {
                reply(ex, 200, "{\"issues\":[" + issue("A-2", "null") + "],\"isLast\":true}");
            }
        });

        JiraClient.SearchResult r = client.search("project = A", 10);

        assertEquals(2, r.tickets().size());
        assertFalse(r.hasMore());
        Ticket first = r.tickets().getFirst();
        assertEquals("A-1", first.key());
        assertEquals("Summary A-1", first.summary());
        assertEquals("hello", first.description());
        assertTrue(first.link().endsWith("/browse/A-1"), first.link());
        assertEquals("", r.tickets().get(1).description());
        assertTrue(requests.getFirst().startsWith("/rest/api/3/search/jql?jql=project = A&fields=summary,description"));
        assertTrue(authHeaders.getFirst().startsWith("Basic "));
    }

    @Test
    void limitStopsPagingAndReportsMore() throws IOException {
        JiraClient client = start("me", (ex, uri) ->
                reply(ex, 200, "{\"issues\":[" + issue("A-1", "null") + "," + issue("A-2", "null")
                        + "],\"nextPageToken\":\"p2\",\"isLast\":false}"));

        JiraClient.SearchResult r = client.search("x", 1);

        assertEquals(1, r.tickets().size());
        assertTrue(r.hasMore());
        assertEquals(1, requests.size());
    }

    @Test
    void fallsBackToServerApiAndBearerToken() throws IOException {
        JiraClient client = start(null, (ex, uri) -> {
            if (uri.startsWith("/rest/api/3/")) {
                reply(ex, 404, "{}");
            } else if (uri.contains("startAt=0")) {
                reply(ex, 200, "{\"total\":2,\"issues\":[" + issue("S-1", "\"*bold* [x|http://y]\"") + "]}");
            } else {
                reply(ex, 200, "{\"total\":2,\"issues\":[" + issue("S-2", "\"two\"") + "]}");
            }
        });

        JiraClient.SearchResult r = client.search("project = S", 50);

        assertEquals(List.of("bold x", "two"), r.tickets().stream().map(Ticket::description).toList());
        assertFalse(r.hasMore());
        assertEquals("Bearer secret", authHeaders.getFirst());
        // The flavour is remembered: the next search goes straight to v2.
        requests.clear();
        client.search("project = S", 1);
        assertTrue(requests.getFirst().startsWith("/rest/api/2/search"), requests.getFirst());
    }

    @Test
    void jqlErrorsAreSurfaced() throws IOException {
        JiraClient client = start("me", (ex, uri) ->
                reply(ex, 400, "{\"errorMessages\":[\"Field 'foo' does not exist.\"],\"errors\":{}}"));

        JiraException e = assertThrows(JiraException.class, () -> client.search("foo = 1", 5));

        assertEquals("Invalid request (check the JQL syntax): Field 'foo' does not exist.", e.getMessage());
    }

    @Test
    void extraFieldsAreResolvedFetchedAndSimplified() throws IOException {
        String catalogue = """
                [{"id":"status","name":"Status"},{"id":"assignee","name":"Assignee"},
                 {"id":"labels","name":"Labels"},{"id":"customfield_10016","name":"Story Points"},
                 {"id":"customfield_10020","name":"Sprint"}]""";
        String fields = """
                {"summary":"S","description":null,
                 "status":{"self":"https://x/status/3","name":"In Progress","statusCategory":{"key":"indeterminate"}},
                 "assignee":{"accountId":"5b10","displayName":"Ada Lovelace","avatarUrls":{"48x48":"https://a"}},
                 "labels":["backend","urgent"],
                 "customfield_10016":5.0,
                 "customfield_10020":[{"id":7,"name":"Sprint 42","state":"active"}]}""";
        JiraClient client = start("me", List.of("status", "Assignee", "labels", "story points", "Sprint", "nope", "summary"),
                (ex, uri) -> {
                    if (uri.startsWith("/rest/api/2/field")) {
                        reply(ex, 200, catalogue);
                    } else {
                        reply(ex, 200, "{\"issues\":[{\"key\":\"A-1\",\"fields\":" + fields + "}],\"isLast\":true}");
                    }
                });

        Ticket t = client.search("x", 5).tickets().getFirst();

        assertEquals("""
                {"status":"In Progress","Assignee":"Ada Lovelace","labels":["backend","urgent"],\
                "story points":5.0,"Sprint":["Sprint 42"],"nope":null}""", MAPPER.writeValueAsString(t.extra()));
        String search = requests.get(1);
        assertTrue(search.contains("fields=summary,description,status,assignee,labels,customfield_10016,customfield_10020,nope"),
                search);
        // The catalogue is read once.
        client.search("y", 5);
        assertEquals(1, requests.stream().filter(r -> r.startsWith("/rest/api/2/field")).count());
    }

    @Test
    void unreadableCatalogueFallsBackToIds() throws IOException {
        JiraClient client = start("me", List.of("customfield_1"), (ex, uri) -> {
            if (uri.startsWith("/rest/api/2/field")) {
                reply(ex, 500, "{}");
            } else {
                reply(ex, 200, "{\"issues\":[" + issue("A-1", "null") + "],\"isLast\":true}");
            }
        });

        Ticket t = client.search("x", 5).tickets().getFirst();

        assertTrue(t.extra().containsKey("customfield_1"));
        assertTrue(requests.get(1).contains("fields=summary,description,customfield_1"), requests.get(1));
    }
}
