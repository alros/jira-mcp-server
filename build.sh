#!/usr/bin/env bash
# Builds the native macOS binary (default) or the JVM jar (./build.sh jvm).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ "${1:-native}" == "jvm" ]]; then
  exec ./mvnw -B package
fi

# Native image needs a GraalVM 25. Prefer an explicit GRAALVM_HOME only if it is a 25.x install,
# otherwise use the JDK on the PATH / JAVA_HOME (which must be GraalVM 25).
graal_ok() { [[ -x "$1/bin/native-image" ]] && "$1/bin/java" -version 2>&1 | grep -q 'version "25'; }
if [[ -z "${GRAALVM_HOME:-}" ]] || ! graal_ok "$GRAALVM_HOME"; then
  JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
  graal_ok "$JAVA_HOME" || { echo "GraalVM 25 with native-image not found (set GRAALVM_HOME)" >&2; exit 1; }
  export GRAALVM_HOME="$JAVA_HOME"
fi
echo "Using GraalVM at $GRAALVM_HOME" >&2
exec ./mvnw -B package -Dnative
