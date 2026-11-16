package eu.rossotto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdfToTextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String text(String adfJson) throws Exception {
        return PlainText.of(MAPPER.readTree(adfJson));
    }

    @Test
    void richDocumentBecomesPlainText() throws Exception {
        String adf = """
                {"type":"doc","version":1,"content":[
                  {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Context"}]},
                  {"type":"paragraph","content":[
                    {"type":"text","text":"Login fails, see "},
                    {"type":"text","text":"the runbook","marks":[{"type":"link","attrs":{"href":"https://wiki/x"}}]},
                    {"type":"text","text":" — reported by "},
                    {"type":"mention","attrs":{"id":"123","text":"@Ada Lovelace"}},
                    {"type":"text","text":" "},
                    {"type":"emoji","attrs":{"shortName":":fire:","text":"🔥"}},
                    {"type":"hardBreak"},
                    {"type":"text","text":"Bold bit","marks":[{"type":"strong"}]},
                    {"type":"text","text":" "},
                    {"type":"status","attrs":{"text":"BLOCKED"}}
                  ]},
                  {"type":"mediaSingle","content":[{"type":"media","attrs":{"id":"abc","type":"file"}}]},
                  {"type":"bulletList","content":[
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"one"}]},
                      {"type":"orderedList","content":[
                        {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"nested"}]}]}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"inlineCard","attrs":{"url":"https://x.io/1"}}]}]}
                  ]},
                  {"type":"codeBlock","attrs":{"language":"java"},"content":[{"type":"text","text":"int a = 1;"}]},
                  {"type":"table","content":[
                    {"type":"tableRow","content":[
                      {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Env"}]}]},
                      {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Result"}]}]}]},
                    {"type":"tableRow","content":[
                      {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"prod"}]}]},
                      {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"KO"}]}]}]}
                  ]},
                  {"type":"rule"},
                  {"type":"paragraph","content":[{"type":"text","text":"Due "},{"type":"date","attrs":{"timestamp":"1735689600000"}}]}
                ]}
                """;
        assertEquals("""
                Context

                Login fails, see the runbook — reported by Ada Lovelace 🔥
                Bold bit [BLOCKED]

                - one
                  1. nested
                - https://x.io/1

                int a = 1;

                Env\tResult
                prod\tKO

                Due 2025-01-01""", text(adf));
    }

    @Test
    void taskListAndExpand() throws Exception {
        String adf = """
                {"type":"doc","version":1,"content":[
                  {"type":"expand","attrs":{"title":"Steps"},"content":[
                    {"type":"taskList","content":[
                      {"type":"taskItem","attrs":{"state":"DONE"},"content":[{"type":"text","text":"reproduce"}]},
                      {"type":"taskItem","attrs":{"state":"TODO"},"content":[{"type":"text","text":"fix"}]}]}]}
                ]}
                """;
        assertEquals("Steps\n[x] reproduce\n[ ] fix", text(adf));
    }

    @Test
    void nullAndEmpty() throws Exception {
        assertEquals("", PlainText.of(null));
        assertEquals("", text("null"));
        assertEquals("", text("{\"type\":\"doc\",\"version\":1,\"content\":[]}"));
    }
}
