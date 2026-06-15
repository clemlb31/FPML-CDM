#!/usr/bin/env bash
# Start all LOCAL MCP servers of the autonomous agent (Mac/Linux).
# (Tavily is a remote hosted MCP — no local process. See README.md.)
#
# Servers (one folder per MCP under mcp_servers/):
#   filesystem-8080      — read knowledge_base/+data/, read-write workspaces/ (sandboxed)
#   validator-8003       — compile_project / run_test                        (Docker Maven)
#   grep-8005            — ripgrep content search
#   cdm_lookup-8006      — CDM 6.19 API introspection (javap)
#   internet_search-8007 — web search (Tavily wrapper)
#
# Usage:
#   bash mcp_servers/start_all.sh         # foreground, Ctrl+C stops everything
#   bash mcp_servers/start_all.sh --stop  # stop servers started earlier
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PID_DIR="$ROOT/.mcp_pids"
LOG_DIR="$ROOT/.mcp_logs"
mkdir -p "$PID_DIR" "$LOG_DIR"

PYTHON="$ROOT/.venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then
  echo "ERROR: $PYTHON not found. Run: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"
  exit 1
fi

# Load .env (TAVILY_KEY, etc.) so child servers inherit it.
if [[ -f "$ROOT/.env" ]]; then
  set -a; source "$ROOT/.env"; set +a
fi

stop_all() {
  echo ""
  echo "Stopping all MCP servers..."
  for pidfile in "$PID_DIR"/*.pid; do
    [[ -e "$pidfile" ]] || continue
    pid="$(cat "$pidfile")"; name="$(basename "$pidfile" .pid)"
    if kill -0 "$pid" 2>/dev/null; then
      echo "  stopping $name (pid $pid)"
      kill "$pid" 2>/dev/null || true
      sleep 1
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
  done
  echo "Stopped."
}

if [[ "${1:-}" == "--stop" ]]; then
  stop_all
  exit 0
fi

trap stop_all INT TERM

start() {
  local name="$1"; shift
  local log="$LOG_DIR/$name.log"
  echo "[start] $name → log: $log"
  # No subshell: capture the real server PID so --stop works.
  "$@" >"$log" 2>&1 &
  echo $! >"$PID_DIR/$name.pid"
}

# Poll an MCP endpoint until it answers (cold-start safe).
ready_check() {
  local name="$1" port="$2"
  for _ in $(seq 1 45); do
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://localhost:$port/mcp" 2>/dev/null || echo 000)"
    if [[ "$code" != "000" ]]; then
      echo "  [ready] $name (http $code) → http://localhost:$port/mcp"
      return 0
    fi
    sleep 2
  done
  echo "  [DOWN]  $name — no response on :$port (see $LOG_DIR/$name.log)"
  return 1
}

echo "=== Starting MCP servers ==="
start filesystem-8080      "$PYTHON" -m mcp_servers.filesystem_server.server       --transport streamable-http --port 8080
start validator-8003       "$PYTHON" -m mcp_servers.validator_server.server        --transport streamable-http --port 8003
start grep-8005            "$PYTHON" -m mcp_servers.grep_server.server             --transport streamable-http --port 8005
start cdm_lookup-8006      "$PYTHON" -m mcp_servers.cdm_lookup_server.server       --transport streamable-http --port 8006
start internet_search-8007 "$PYTHON" -m mcp_servers.internet_search_server.server --transport streamable-http --port 8007

echo ""
echo "=== Waiting for servers to be ready ==="
ready_check filesystem-8080      8080 || true
ready_check validator-8003       8003 || true
ready_check grep-8005            8005 || true
ready_check cdm_lookup-8006      8006 || true
ready_check internet_search-8007 8007 || true

if ! docker info >/dev/null 2>&1; then
  echo ""
  echo "  ⚠ Docker is DOWN — validator's compile_project/run_test will fail until you start it."
fi

cat <<EOF

URLs:
  filesystem        http://localhost:8080/mcp
  validator         http://localhost:8003/mcp  (requires Docker daemon)
  grep              http://localhost:8005/mcp
  cdm_lookup        http://localhost:8006/mcp
  internet_search   http://localhost:8007/mcp  (Tavily; needs TAVILY_KEY in .env)

Tail logs:  tail -f $LOG_DIR/<name>.log
Stop all:   bash mcp_servers/start_all.sh --stop

Press Ctrl+C to stop all servers...
EOF

while true; do sleep 60; done
