#!/usr/bin/env bash
# Deprecated location — the MCP launcher now lives next to the servers it starts.
# This thin wrapper delegates to mcp_servers/start_all.sh so there is ONE source of
# truth (and it now also starts cdm_lookup-8006 + our own filesystem server).
# Use that path directly:  bash mcp_servers/start_all.sh [--stop]
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec bash "$ROOT/mcp_servers/start_all.sh" "$@"
