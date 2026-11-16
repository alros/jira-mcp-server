#!/usr/bin/env bash
# Launches the Jira MCP server over stdio.
#
# Credentials come from JIRA_URL / JIRA_USERNAME / JIRA_TOKEN. If a secrets.sh file sits next to
# this script it is sourced first, so MCP clients only need to know the path of this script.
#
# stdout is the MCP channel: nothing in here may print to it.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$DIR/secrets.sh" ]]; then
  # shellcheck source=/dev/null
  source "$DIR/secrets.sh" >&2
fi
export JIRA_URL JIRA_USERNAME JIRA_TOKEN JIRA_FIELDS 2>/dev/null || true

NATIVE="$DIR/target/jira-mcp-server-1.0.0-SNAPSHOT-runner"
JAR="$DIR/target/quarkus-app/quarkus-run.jar"

if [[ -x "$NATIVE" ]]; then
  exec "$NATIVE" "$@"
elif [[ -f "$JAR" ]]; then
  exec java -jar "$JAR" "$@"
else
  echo "jira-mcp-server: not built yet, run $DIR/build.sh first" >&2
  exit 1
fi
