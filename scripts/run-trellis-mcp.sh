#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# Cursor MCP launches with a minimal PATH. Locate JDK and sbt explicitly.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "${HOME}/.sdkman/candidates/java/current" \
    "${HOME}/.jdks/openjdk-23.0.1" \
    /usr/lib/jvm/java-21-openjdk \
    /usr/lib/jvm/java-17-openjdk
  do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "Trellis MCP: JAVA_HOME not found; install a JDK 17+ or set JAVA_HOME" >&2
  exit 1
fi

export PATH="${JAVA_HOME}/bin:${HOME}/.local/share/coursier/bin:${HOME}/.sdkman/candidates/sbt/current/bin:${PATH}"

if ! command -v sbt >/dev/null 2>&1; then
  echo "Trellis MCP: sbt not found on PATH" >&2
  exit 1
fi

sbt --error compile >/dev/null
classpath="$(sbt --error 'export runtime:fullClasspath' 2>/dev/null | tail -1)"
if [[ -z "$classpath" ]]; then
  echo "Trellis MCP: failed to resolve runtime classpath" >&2
  exit 1
fi

exec "${JAVA_HOME}/bin/java" -cp "$classpath" trellis.agent.AgentMcp "$@"
