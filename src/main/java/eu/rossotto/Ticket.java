package eu.rossotto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * A Jira issue reduced to what an LLM needs to summarise it.
 *
 * @param extra the JIRA_FIELDS values, keyed as configured, already simplified
 */
public record Ticket(String key, String summary, String description, String link, Map<String, JsonNode> extra) {
}
