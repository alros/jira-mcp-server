package eu.rossotto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldValuesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String simplify(String json) throws Exception {
        return MAPPER.writeValueAsString(FieldValues.simplify(MAPPER.readTree(json)));
    }

    @Test
    void namedObjectsBecomeTheirLabel() throws Exception {
        assertEquals("\"High\"", simplify("{\"self\":\"x\",\"iconUrl\":\"y\",\"name\":\"High\",\"id\":\"2\"}"));
        assertEquals("\"Option A\"", simplify("{\"self\":\"x\",\"value\":\"Option A\",\"id\":\"10\"}"));
        assertEquals("\"EU / Italy\"", simplify("{\"value\":\"EU\",\"child\":{\"value\":\"Italy\"}}"));
        assertEquals("[\"Web\",\"API\"]", simplify("[{\"name\":\"Web\"},{\"name\":\"API\"}]"));
    }

    @Test
    void richTextBecomesPlainText() throws Exception {
        assertEquals("\"Steps: click it\"", simplify("""
                {"type":"doc","version":1,"content":[{"type":"paragraph","content":[
                 {"type":"text","text":"Steps: "},{"type":"text","text":"click it","marks":[{"type":"link","attrs":{"href":"h"}}]}]}]}"""));
    }

    @Test
    void scalarsAndOtherObjectsPassThroughWithoutNoise() throws Exception {
        assertEquals("3.5", simplify("3.5"));
        assertEquals("null", simplify("null"));
        assertEquals("{\"originalEstimate\":\"2d\",\"remainingEstimate\":\"1d\"}",
                simplify("{\"self\":\"x\",\"originalEstimate\":\"2d\",\"remainingEstimate\":\"1d\"}"));
    }
}
