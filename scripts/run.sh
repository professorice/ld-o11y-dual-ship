#!/usr/bin/env bash
# ld-o11y-demo runner: ./scripts/run.sh java | go | check
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  java    Build and run the Java demo (port 8080; starts Datadog Agent if needed)
  go      Run the Go demo (port 8081; starts Datadog Agent if needed)
  check   Verify prerequisites and .env

Setup: cp .env.example .env  (then set LAUNCHDARKLY_SDK_KEY, LAUNCHDARKLY_CLIENT_SIDE_ID, DD_API_KEY)

The demo apps use Datadog tracing libraries; they send spans to the Datadog Agent on
localhost:8126. The Agent (not the app) dual-ships to LaunchDarkly per LD docs.
EOF
}

agent_port_open() {
  (echo >/dev/tcp/127.0.0.1/8126) &>/dev/null
}

export_tracer_env() {
  : "${LAUNCHDARKLY_CLIENT_SIDE_ID:?Set LAUNCHDARKLY_CLIENT_SIDE_ID in .env}"
  export DD_AGENT_HOST="${DD_AGENT_HOST:-localhost}"
  export DD_TRACE_AGENT_PORT="${DD_TRACE_AGENT_PORT:-8126}"
  export DD_TRACE_OTEL_ENABLED="${DD_TRACE_OTEL_ENABLED:-true}"
  export OTEL_RESOURCE_ATTRIBUTES="launchdarkly.project_id=${LAUNCHDARKLY_CLIENT_SIDE_ID}"
  # DD_TAGS is for the Datadog Agent container only; unset so the tracer does not warn.
  unset DD_TAGS
}

require_port() {
  local port="$1"
  local listeners
  listeners="$(lsof -nP -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "${listeners}" ]]; then
    echo "Port ${port} is already in use. Stop the existing process and retry:" >&2
    echo "${listeners}" >&2
    return 1
  fi
}

ensure_datadog_agent() {
  if agent_port_open; then
    return 0
  fi
  if ! command -v docker &>/dev/null; then
    echo "Datadog Agent is not running on localhost:8126 and Docker is not installed." >&2
    echo "Install Docker, then run ./scripts/run.sh java or go" >&2
    echo "Or run a Datadog Agent yourself with dual-ship configured (see README)." >&2
    return 1
  fi
  echo "Datadog Agent not detected on localhost:8126 — starting via Docker Compose..."
  cmd_agent
  local i
  for i in $(seq 1 30); do
    if agent_port_open; then
      echo "Datadog Agent is listening on localhost:8126"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for Datadog Agent on localhost:8126" >&2
  return 1
}

cmd_agent() {
  load_env
  : "${DD_API_KEY:?Set DD_API_KEY in .env}"
  : "${DD_APM_ADDITIONAL_ENDPOINTS:?LAUNCHDARKLY_CLIENT_SIDE_ID must be set in .env}"
  require_cmd docker "https://docs.docker.com/get-docker/"
  docker compose -f "${ROOT}/docker-compose.yml" up -d
  echo "Datadog Agent started. Traces: apps -> localhost:8126 -> Datadog + LaunchDarkly"
}

load_env() {
  local env_file="${ROOT}/.env"
  if [[ ! -f "${env_file}" ]]; then
    echo "Missing ${env_file}. Copy .env.example to .env and fill in values." >&2
    return 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "${env_file}"
  set +a
  # Used by docker-compose Datadog Agent (dual-ship), not by the app process directly.
  if [[ -n "${LAUNCHDARKLY_CLIENT_SIDE_ID:-}" ]]; then
    export DD_APM_ADDITIONAL_ENDPOINTS="{\"https://datadog.observability.app.launchdarkly.com:8126\": [\"${LAUNCHDARKLY_CLIENT_SIDE_ID}\"]}"
    export DD_TAGS="launchdarkly.project_id:${LAUNCHDARKLY_CLIENT_SIDE_ID}"
  fi
}

require_cmd() {
  command -v "$1" &>/dev/null || {
    echo "Required command not found: $1" >&2
    [[ -n "${2:-}" ]] && echo "$2" >&2
    return 1
  }
}

_java_major() {
  local v
  v="$("$1" -version 2>&1 | head -1)"
  [[ "${v}" =~ \"([0-9]+) ]] && echo "${BASH_REMATCH[1]}" && return 0
  [[ "${v}" =~ version\ ([0-9]+) ]] && echo "${BASH_REMATCH[1]}" && return 0
  return 1
}

_java_home_from_bin() {
  local resolved="$1" parent
  if command -v readlink &>/dev/null; then
    while [[ -L "${resolved}" ]]; do
      parent="$(cd "$(dirname "${resolved}")" && pwd)"
      resolved="$(readlink "${resolved}")"
      [[ "${resolved}" != /* ]] && resolved="${parent}/${resolved}"
    done
  fi
  resolved="$(cd "$(dirname "${resolved}")" && pwd)/$(basename "${resolved}")"
  dirname "$(dirname "${resolved}")"
}

setup_java() {
  local min="${1:-21}" major home dir java_bin
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    major="$(_java_major "${JAVA_HOME}/bin/java")" || true
    [[ -n "${major}" && "${major}" -ge "${min}" ]] && export PATH="${JAVA_HOME}/bin:${PATH}" && return 0
  fi
  if [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
    home="$(/usr/libexec/java_home -v "${min}" 2>/dev/null)" || true
    if [[ -n "${home:-}" ]]; then
      export JAVA_HOME="${home}" PATH="${JAVA_HOME}/bin:${PATH}"
      return 0
    fi
  fi
  if command -v java &>/dev/null; then
    java_bin="$(command -v java)"
    major="$(_java_major "${java_bin}")" || true
    if [[ -n "${major}" && "${major}" -ge "${min}" ]]; then
      export JAVA_HOME="$(_java_home_from_bin "${java_bin}")" PATH="${JAVA_HOME}/bin:${PATH}"
      return 0
    fi
  fi
  local candidates=()
  if [[ "$(uname -s)" == "Darwin" ]]; then
    for dir in /Library/Java/JavaVirtualMachines/*.jdk/Contents/Home \
               "${HOME}/Library/Java/JavaVirtualMachines"/*.jdk/Contents/Home; do
      [[ -d "${dir}" ]] && candidates+=("${dir}")
    done
  else
    for dir in /usr/lib/jvm/java-"${min}"-* /usr/lib/jvm/java-"${min}"*; do
      [[ -d "${dir}" ]] && candidates+=("${dir}")
    done
  fi
  for dir in "${candidates[@]}"; do
    if [[ -x "${dir}/bin/java" ]]; then
      major="$(_java_major "${dir}/bin/java")" || true
      if [[ -n "${major}" && "${major}" -ge "${min}" ]]; then
        export JAVA_HOME="${dir}" PATH="${JAVA_HOME}/bin:${PATH}"
        return 0
      fi
    fi
  done
  echo "JDK ${min}+ not found. Install JDK ${min} or set JAVA_HOME." >&2
  return 1
}

fetch_dd_agent() {
  local out="${ROOT}/java/lib/dd-java-agent.jar"
  mkdir -p "${ROOT}/java/lib"
  echo "Downloading Datadog Java tracer to ${out}..."
  curl -fsSL -o "${out}" "https://dtdg.co/latest-java-tracer"
}

resolve_dd_agent() {
  local default="${ROOT}/java/lib/dd-java-agent.jar"
  if [[ -z "${DD_JAVA_AGENT:-}" || "${DD_JAVA_AGENT}" == "/path/to/dd-java-agent.jar" ]]; then
    export DD_JAVA_AGENT="${default}"
  elif [[ "${DD_JAVA_AGENT}" != /* ]]; then
    export DD_JAVA_AGENT="${ROOT}/${DD_JAVA_AGENT}"
  fi
  [[ -f "${DD_JAVA_AGENT}" ]]
}

cmd_java() {
  load_env
  ensure_datadog_agent
  export_tracer_env
  require_port 8080
  setup_java 21
  require_cmd mvn "https://maven.apache.org/download.cgi"
  resolve_dd_agent || { fetch_dd_agent; resolve_dd_agent || exit 1; }
  : "${LAUNCHDARKLY_SDK_KEY:?Set LAUNCHDARKLY_SDK_KEY in .env}"
  export DD_SERVICE="${DD_SERVICE_JAVA:-ld-o11y-demo-java}"
  cd "${ROOT}/java"
  mvn -q clean package -DskipTests
  exec java -javaagent:"${DD_JAVA_AGENT}" \
    -Ddd.service="${DD_SERVICE}" \
    -Ddd.trace.otel.enabled=true \
    -jar target/ld-o11y-demo-java-1.0.0.jar
}

cmd_go() {
  load_env
  ensure_datadog_agent
  export_tracer_env
  require_port 8081
  require_cmd go "https://go.dev/dl/"
  : "${LAUNCHDARKLY_SDK_KEY:?Set LAUNCHDARKLY_SDK_KEY in .env}"
  export DD_SERVICE="${DD_SERVICE_GO:-ld-o11y-demo-go}"
  cd "${ROOT}/go"
  exec go run .
}

cmd_check() {
  local ok=0 fail=0
  try() { if "$@"; then echo "  ok   $*"; ok=$((ok + 1)); else echo "  FAIL $*"; fail=$((fail + 1)); fi; }

  echo "Checking prerequisites..."
  echo
  echo "Tools:"
  try require_cmd curl
  try require_cmd mvn
  try require_cmd go
  echo
  echo "Java (21+):"
  if setup_java 21; then
    echo "  ok   JAVA_HOME=${JAVA_HOME}"
    java -version 2>&1 | sed 's/^/       /'
    ok=$((ok + 1))
  else
    echo "  FAIL JDK 21+"; fail=$((fail + 1))
  fi
  echo
  echo "Go:"
  if (cd "${ROOT}/go" && go version); then ok=$((ok + 1)); else echo "  FAIL go"; fail=$((fail + 1)); fi
  echo
  echo "Config:"
  if [[ -f "${ROOT}/.env" ]]; then
    echo "  ok   .env exists"; ok=$((ok + 1))
    load_env
    if [[ -n "${LAUNCHDARKLY_SDK_KEY:-}" && "${LAUNCHDARKLY_SDK_KEY}" != sdk-xxxxxxxx-* ]]; then
      echo "  ok   LAUNCHDARKLY_SDK_KEY set"; ok=$((ok + 1))
    else
      echo "  FAIL LAUNCHDARKLY_SDK_KEY"; fail=$((fail + 1))
    fi
  else
    echo "  FAIL .env (cp .env.example .env)"; fail=$((fail + 1))
  fi
  echo
  if [[ -f "${ROOT}/java/lib/dd-java-agent.jar" ]]; then
    echo "  ok   java/lib/dd-java-agent.jar"
  else
    echo "  note java tracer auto-downloads on first: ./scripts/run.sh java"
  fi
  echo
  echo "Datadog Agent (localhost:8126):"
  if agent_port_open; then
    echo "  ok   Agent reachable (dual-ships to Datadog + LaunchDarkly)"
    ok=$((ok + 1))
  elif command -v docker &>/dev/null; then
    echo "  note not running — java/go will auto-start via Docker"
  else
    echo "  FAIL not running; install Docker, then run java or go"
    fail=$((fail + 1))
  fi
  echo
  [[ "${fail}" -eq 0 ]] && echo "All required checks passed." && return 0
  echo "${fail} failed, ${ok} passed."; return 1
}

case "${1:-}" in
  java)  cmd_java ;;
  go)    cmd_go ;;
  check) cmd_check ;;
  -h|--help|help) usage ;;
  *)
    [[ -n "${1:-}" ]] && echo "Unknown command: $1" >&2
    usage
    exit 1
    ;;
esac
