package eu.rossotto;

import com.fasterxml.jackson.databind.JsonNode;

/** Entry point: turns whatever Jira put in a description field into plain text. */
public final class PlainText {

    private PlainText() {
    }

    /**
     * @param description an ADF document (Cloud, API v3), a wiki-markup string (Server/DC, API v2) or null
     */
    public static String of(JsonNode description) {
        if (description == null || description.isNull() || description.isMissingNode()) {
            return "";
        }
        String text = description.isObject()
                ? AdfToText.convert(description)
                : WikiToText.convert(description.asText());
        return tidy(text);
    }

    /** Normalises whitespace: trims lines, collapses runs of blank lines, drops invisible characters. */
    static String tidy(String text) {
        String s = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("(?<=\\S) {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }
}
