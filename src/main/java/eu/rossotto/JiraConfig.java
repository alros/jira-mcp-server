package eu.rossotto;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Jira connection settings, fed by the JIRA_URL / JIRA_USERNAME / JIRA_TOKEN environment variables.
 * Everything is optional at startup so the MCP server can always boot and report a clear error
 * through the tool instead of crashing the client handshake.
 */
@ConfigMapping(prefix = "jira")
public interface JiraConfig {

    /** Base URL, e.g. https://acme.atlassian.net */
    Optional<String> url();

    /** Account e-mail (Cloud) or username (Server/DC). If absent, the token is sent as a Bearer PAT. */
    Optional<String> username();

    /** API token (Cloud) or personal access token / password (Server/DC). */
    Optional<String> token();

    /**
     * Additional fields to fetch and add to every ticket, from the comma-separated JIRA_FIELDS.
     * Entries may be field ids ({@code status}, {@code customfield_10016}) or display names ({@code Story Points}).
     */
    Optional<List<String>> fields();

    Duration timeout();
}
