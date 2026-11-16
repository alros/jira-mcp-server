package eu.rossotto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Converts an Atlassian Document Format tree to plain text.
 *
 * <p>The goal is content, not looks: links keep only their text, media is dropped, mentions and
 * emoji become words, tables become tab-separated rows, lists get simple bullets/numbers.
 * Unknown nodes fall back to rendering their children, so new ADF node types degrade gracefully.
 *
 * @see <a href="https://developer.atlassian.com/cloud/jira/platform/apis/document/structure/">ADF structure</a>
 */
final class AdfToText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final StringBuilder out = new StringBuilder();

    private AdfToText() {
    }

    static String convert(JsonNode doc) {
        AdfToText c = new AdfToText();
        c.node(doc, "");
        return c.out.toString();
    }

    private void node(JsonNode n, String indent) {
        String type = n.path("type").asText("");
        JsonNode attrs = n.path("attrs");
        switch (type) {
            case "text" -> out.append(n.path("text").asText(""));
            case "hardBreak" -> out.append('\n').append(indent);
            case "paragraph", "heading" -> {
                children(n, indent);
                block();
            }
            case "bulletList" -> list(n, indent, false);
            case "orderedList" -> list(n, indent, true);
            case "taskList" -> {
                for (JsonNode item : n.path("content")) {
                    out.append(indent).append("DONE".equals(item.path("attrs").path("state").asText()) ? "[x] " : "[ ] ");
                    children(item, indent + "    ");
                    line();
                }
                block();
            }
            case "decisionList" -> {
                for (JsonNode item : n.path("content")) {
                    out.append(indent).append("Decision: ");
                    children(item, indent);
                    line();
                }
                block();
            }
            case "codeBlock", "blockquote", "panel" -> {
                children(n, indent);
                block();
            }
            case "expand", "nestedExpand" -> {
                String title = attrs.path("title").asText("");
                if (!title.isBlank()) {
                    out.append(title).append('\n');
                }
                children(n, indent);
                block();
            }
            case "rule" -> block();
            case "table" -> {
                for (JsonNode row : n.path("content")) {
                    boolean first = true;
                    for (JsonNode cell : row.path("content")) {
                        if (!first) {
                            out.append('\t');
                        }
                        first = false;
                        out.append(inline(cell));
                    }
                    line();
                }
                block();
            }
            case "mention" -> out.append(stripAt(attrs.path("text").asText("someone")));
            case "emoji" -> {
                String text = attrs.path("text").asText("");
                out.append(text.isBlank() ? attrs.path("shortName").asText("") : text);
            }
            case "status" -> out.append('[').append(attrs.path("text").asText("")).append(']');
            case "date" -> out.append(formatDate(attrs.path("timestamp").asText("")));
            case "inlineCard", "blockCard", "embedCard" -> {
                // A smart link has no text of its own: the URL is its plain-text form.
                String url = attrs.path("url").asText("");
                out.append(url);
                if (!"inlineCard".equals(type)) {
                    block();
                }
            }
            case "placeholder" -> out.append(attrs.path("text").asText(""));
            // Images, attachments, videos and extensions carry no readable text.
            case "media", "mediaSingle", "mediaGroup", "mediaInline", "extension", "bodiedExtension",
                 "inlineExtension" -> {
                if ("bodiedExtension".equals(type)) {
                    children(n, indent);
                }
            }
            default -> children(n, indent);
        }
    }

    private void list(JsonNode n, String indent, boolean ordered) {
        int i = n.path("attrs").path("order").asInt(1);
        for (JsonNode item : n.path("content")) {
            String marker = ordered ? (i++) + ". " : "- ";
            out.append(indent).append(marker);
            listItem(item, indent + " ".repeat(marker.length()));
        }
        if (indent.isEmpty()) {
            block();
        }
    }

    /** List items hold paragraphs and nested lists; keep them compact (no blank lines between items). */
    private void listItem(JsonNode item, String indent) {
        boolean first = true;
        for (JsonNode child : item.path("content")) {
            String type = child.path("type").asText();
            if (type.endsWith("List")) {
                line();
                node(child, indent);
                first = true;
                continue;
            }
            if (!first) {
                out.append('\n').append(indent);
            }
            if ("paragraph".equals(type) || "heading".equals(type)) {
                children(child, indent);
            } else {
                node(child, indent);
            }
            first = false;
        }
        line();
    }

    private String inline(JsonNode n) {
        AdfToText nested = new AdfToText();
        nested.children(n, "");
        return nested.out.toString().strip().replaceAll("\\s*\\n+\\s*", " ");
    }

    private void children(JsonNode n, String indent) {
        for (JsonNode child : n.path("content")) {
            node(child, indent);
        }
    }

    /** Ends the current line, if any. */
    private void line() {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    /** Ends the current block with a blank line. */
    private void block() {
        line();
        int len = out.length();
        if (len > 0 && !(len >= 2 && out.charAt(len - 2) == '\n')) {
            out.append('\n');
        }
    }

    private static String stripAt(String s) {
        return s.startsWith("@") ? s.substring(1) : s;
    }

    private static String formatDate(String timestamp) {
        try {
            return DATE.format(Instant.ofEpochMilli(Long.parseLong(timestamp)));
        } catch (NumberFormatException e) {
            return timestamp;
        }
    }
}
