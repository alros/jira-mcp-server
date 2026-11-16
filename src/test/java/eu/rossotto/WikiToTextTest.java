package eu.rossotto;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikiToTextTest {

    private static String text(String wiki) {
        return PlainText.of(TextNode.valueOf(wiki));
    }

    @Test
    void linksImagesAndFormatting() {
        String wiki = """
                h2. Problem
                The *login* page _fails_ for [~jdoe], see [runbook|https://wiki/x] and [https://status.io].
                !screenshot.png|thumbnail!
                !image-2024-01-01-10-00-00-000.png!
                {color:red}Urgent{color} -old- +new+ {{config.yml}} (/) done
                ----
                * first
                ** nested [^logs.zip]
                # numbered
                bq. quoted
                """;
        assertEquals("""
                Problem
                The login page fails for jdoe, see runbook and https://status.io.

                Urgent old new config.yml ✓ done

                - first
                  - nested logs.zip
                - numbered
                quoted""", text(wiki));
    }

    @Test
    void codeIsKeptVerbatim() {
        String wiki = "Run:\n{code:java}\nint *x* = a_b_c[0];\n{code}\nthen {noformat}[not|a link]{noformat}";
        assertEquals("Run:\n\nint *x* = a_b_c[0];\n\nthen\n[not|a link]", text(wiki));
    }

    @Test
    void tablesAndPlainBracketsSurvive() {
        String wiki = "||Env||Result||\n|prod|KO|\n[WIP] snake_case_name stays, so does 3 - 2 - 1. Wow! Nice!";
        assertEquals("Env\tResult\nprod\tKO\n[WIP] snake_case_name stays, so does 3 - 2 - 1. Wow! Nice!", text(wiki));
    }
}
