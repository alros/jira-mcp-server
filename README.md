# Jira MCP server

A small [MCP](https://modelcontextprotocol.io) server that queries Jira over **stdio**. It's built
with Java 25 and Quarkus and compiled to a native macOS binary that starts in about 30 ms.
It works with Claude (Code / Desktop) and GitHub Copilot.

## Tool

### `fetch_tickets`

| Argument     | Type    | Required | Notes                                    |
|--------------|---------|----------|------------------------------------------|
| `jql`        | string  | yes      | Any JQL, e.g. `project = PROJ AND status = "In Progress"` |
| `maxResults` | integer | no       | 1–500, default 50. Paging is handled for you |

Returns:

```json
{
  "count": 1,
  "hasMore": false,
  "tickets": [
    {
      "key": "PROJ-123",
      "summary": "Login broken",
      "description": "Users can't log in, see logs.",
      "link": "https://acme.atlassian.net/browse/PROJ-123"
    }
  ]
}
```

The `description` is **plain text**, meant to be summarised:

- links keep only their label (bare smart links keep the URL, since that's their only text)
- images, attachments and media are removed
- mentions become names, emoji become characters, statuses become `[TEXT]`, dates become `YYYY-MM-DD`
- lists become `- ` / `1. `, tables become tab-separated rows, and code blocks keep their content verbatim

### Extra fields (`JIRA_FIELDS`)

Set `JIRA_FIELDS` to a comma-separated list of extra fields. Each one is fetched and added to every
ticket under the name you wrote:

```bash
export JIRA_FIELDS="status,assignee,priority,labels,Story Points,Sprint"
```

```json
{ "key": "PROJ-123", "summary": "...", "description": "...", "link": "...",
  "status": "In Progress", "assignee": "Ada Lovelace", "priority": "High",
  "labels": ["backend"], "Story Points": 5.0, "Sprint": ["Sprint 42"] }
```

- Entries can be field ids (`status`, `customfield_10016`) or display names (`Story Points`),
  matched case-insensitively. Names are looked up once in `/rest/api/2/field`.
- Values are simplified. Users, statuses, priorities, versions, components and select options
  become their display text. Rich-text fields become plain text, and API noise such as `self`,
  avatars and icons is dropped.
- An unknown field logs a warning to stderr and comes back as `null`. Entries named `key`,
  `summary`, `description` or `link` are ignored because those are always returned.

Both description formats are handled. Jira Cloud sends ADF and Server/Data Center sends wiki markup.

Errors come back as MCP tool errors with a readable message, so the model can fix them itself.
Examples: bad JQL, a 401, a missing `JIRA_URL`.

## Jira Cloud vs Server / Data Center

The server works this out on the first call:

| Deployment  | Endpoint                         | Auth                                              |
|-------------|----------------------------------|---------------------------------------------------|
| Cloud       | `GET /rest/api/3/search/jql`     | Basic: `JIRA_USERNAME` (e-mail) + `JIRA_TOKEN` (API token) |
| Server / DC | `GET /rest/api/2/search` (used when v3 returns 404) | Bearer PAT if `JIRA_USERNAME` is empty, otherwise Basic |

## Configuration

| Variable        | Example                              |
|-----------------|--------------------------------------|
| `JIRA_URL`      | `https://acme.atlassian.net`         |
| `JIRA_USERNAME` | `you@acme.com` (leave empty for a Server/DC PAT) |
| `JIRA_TOKEN`    | [API token](https://id.atlassian.com/manage-profile/security/api-tokens) or PAT |
| `JIRA_FIELDS`   | `status,assignee,Story Points` (optional, see above) |

`jira-mcp-server.sh` sources `secrets.sh` from its **own directory** when that file exists, so it works
whatever working directory the MCP client launches it from. `secrets.sh` is git-ignored.

```bash
cp secrets.sh.example secrets.sh && chmod 600 secrets.sh
```

## Build

```bash
./build.sh        # native binary -> target/jira-mcp-server-1.1.0-runner (needs GraalVM 25)
./build.sh jvm    # JVM build     -> target/quarkus-app/quarkus-run.jar
./mvnw test       # unit tests (converters + HTTP client against a local fake Jira)
```

`build.sh` uses `GRAALVM_HOME` only when it points to a GraalVM 25. Otherwise it falls back to the
`java` on the `PATH`. `jira-mcp-server.sh` runs the native binary when it exists and the jar otherwise.

## Use it

**Claude Code** — `.mcp.json` in this repo is already set up. From any other project:

```bash
claude mcp add --scope user jira -- /path/to/jira-mcp-server/jira-mcp-server.sh
```

**Claude Desktop** — `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{ "mcpServers": { "jira": { "command": "/path/to/jira-mcp-server/jira-mcp-server.sh" } } }
```

**VS Code / GitHub Copilot** (agent mode) — `.vscode/mcp.json` is included. For other workspaces:

```json
{ "servers": { "jira": { "type": "stdio", "command": "/path/to/jira-mcp-server/jira-mcp-server.sh" } } }
```

**IntelliJ Copilot** — `~/.config/github-copilot/intellij/mcp.json`, same shape as the VS Code one.

You can also try it by hand with the MCP Inspector:

```bash
npx @modelcontextprotocol/inspector ./jira-mcp-server.sh
```

## Notes

- stdout is the MCP channel. All logging goes to stderr, and `jira-mcp-server.sh` sends anything
  `secrets.sh` prints to stderr too.
- The server always starts, even with missing config. Configuration problems are reported
  on the first tool call, so the client handshake never fails silently.
