package eu.rossotto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.List;
import java.util.Set;

/**
 * Shrinks raw Jira field values to what a reader cares about.
 *
 * <ul>
 *   <li>users, statuses, priorities, versions, components, options → their display text
 *       ({@code {"name":"Done","self":...,"statusCategory":{...}}} → {@code "Done"})</li>
 *   <li>rich text (ADF documents) → plain text</li>
 *   <li>arrays → arrays of simplified values</li>
 *   <li>other objects → the same object, recursively simplified, without API plumbing (self, avatars, icons...)</li>
 * </ul>
 */
final class FieldValues {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** The first of these present on an object is its human-readable form. */
    private static final List<String> LABEL_KEYS = List.of("displayName", "name", "value", "key");

    private static final Set<String> NOISE = Set.of(
            "self", "avatarUrls", "iconUrl", "expand", "accountType", "timeZone", "active",
            "statusCategory", "avatarId", "subtask", "hierarchyLevel", "entityId");

    private FieldValues() {
    }

    static JsonNode simplify(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return JSON.nullNode();
        }
        if (value.isArray()) {
            ArrayNode out = JSON.arrayNode();
            value.forEach(v -> out.add(simplify(v)));
            return out;
        }
        if (!value.isObject()) {
            return value;
        }
        if ("doc".equals(value.path("type").asText()) && value.has("content")) {
            return TextNode.valueOf(PlainText.of(value));
        }
        // Cascading select: "Parent / Child"
        if (value.has("value") && value.has("child")) {
            return TextNode.valueOf(value.path("value").asText() + " / " + value.path("child").path("value").asText());
        }
        for (String key : LABEL_KEYS) {
            JsonNode label = value.get(key);
            if (label != null && label.isValueNode() && !label.asText().isBlank()) {
                return label;
            }
        }
        ObjectNode out = JSON.objectNode();
        value.properties().forEach(e -> {
            if (!NOISE.contains(e.getKey())) {
                out.set(e.getKey(), simplify(e.getValue()));
            }
        });
        return out;
    }
}
