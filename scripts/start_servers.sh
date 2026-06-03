#!/usr/bin/env bash
# Démarre les 5 MCP servers nécessaires au LangGraph agent (Mac/Linux).
#
# Servers:
#   filesystem-8080   — supergateway → workspaces + knowledge_base + data/train
#   examples-8081     — supergateway → data/train (read-only)
#   triage-8002       — Python MCP   → triage_compile_error / triage_test_diff
#   validator-8003    — Python MCP   → compile_project / run_test / score (Docker)
#   mapping-8004      — Python MCP   → get_maven_dependencies / ask_human
#
# Usage:
#   bash scripts/start_servers.sh         # foreground, Ctrl+C arrête tout
#   bash scripts/start_servers.sh --stop  # arrête les serveurs lancés précédemment
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

if [[ -f "$ROOT/.env" ]]; then
  # shellcheck disable=SC2046
  export $(grep -v '^\s*#' "$ROOT/.env" | grep -v '^\s*$' | xargs -0 2>/dev/null || true)
  set -a; source "$ROOT/.env"; set +a
fi

stop_all() {
  echo ""
  echo "Stopping all MCP servers..."
  for pidfile in "$PID_DIR"/*.pid; do
    [[ -e "$pidfile" ]] || continue
    pid="$(cat "$pidfile")"
    name="$(basename "$pidfile" .pid)"
    if kill -0 "$pid" 2>/dev/null; then
      echo "  stopping $name (pid $pid)"
      kill "$pid" 2>/dev/null || true
      # give it 2s then SIGKILL
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

# Trap Ctrl+C
trap stop_all INT TERM

start() {
  local name="$1"; shift
  local log="$LOG_DIR/$name.log"
  local pidfile="$PID_DIR/$name.pid"
  echo "[start] $name → log: $log"
  ( "$@" ) >"$log" 2>&1 &
  echo $! >"$pidfile"
}

echo "=== Starting MCP servers (Mac) ==="

# 1. Filesystem MCP — workspaces + knowledge_base + data/train (absolute paths)
start filesystem-8080 \
  npx -y supergateway --port 8080 --outputTransport streamableHttp \
    --stdio "npx -y @modelcontextprotocol/server-filesystem $ROOT/workspaces $ROOT/knowledge_base $ROOT/data/train $ROOT/data/test"

# 2. Examples MCP — data/train read-only (absolute path)
start examples-8081 \
  npx -y supergateway --port 8081 --outputTransport streamableHttp \
    --stdio "npx -y @modelcontextprotocol/server-filesystem $ROOT/data/train"

# 3. Triage server
start triage-8002 \
  "$PYTHON" -m mcp_servers.triage_server.server --transport streamable-http --port 8002

# 4. Validator server (requires Docker daemon)
start validator-8003 \
  "$PYTHON" -m mcp_servers.validator_server.server --transport streamable-http --port 8003

# 5. Mapping server
start mapping-8004 \
  "$PYTHON" -m mcp_servers.mapping_server.server --transport streamable-http --port 8004

# 6. Grep server (ripgrep wrapper — content search)
start grep-8005 \
  "$PYTHON" -m mcp_servers.grep_server.server --transport streamable-http --port 8005

sleep 2

echo ""
echo "=== Servers launched ==="
for pidfile in "$PID_DIR"/*.pid; do
  name="$(basename "$pidfile" .pid)"
  pid="$(cat "$pidfile")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "  [up]   $name  (pid $pid)  log: $LOG_DIR/$name.log"
  else
    echo "  [DOWN] $name  log: $LOG_DIR/$name.log"
  fi
done
echo ""
echo "URLs:"
echo "  filesystem    http://localhost:8080/mcp"
echo "  examples      http://localhost:8081/mcp"
echo "  triage        http://localhost:8002/mcp"
echo "  validator     http://localhost:8003/mcp  (requires Docker daemon)"
echo "  mapping       http://localhost:8004/mcp"
echo "  grep          http://localhost:8005/mcp"
echo ""
echo "Tail logs:  tail -f $LOG_DIR/<name>.log"
echo "Stop all:   bash scripts/start_servers.sh --stop"
echo ""
echo "Press Ctrl+C to stop all servers..."

# Block until interrupted
while true; do sleep 60; done
