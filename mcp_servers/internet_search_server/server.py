"""internet_search MCP server — one clean web-search tool, backed by Tavily.

Unifies web access behind a single `internet_search(query)` tool in the same
FastMCP/Python style as the other local servers, instead of wiring the remote
hosted Tavily MCP (which exposed five oddly-named tools). It calls the Tavily REST
API directly with the key from `.env` (TAVILY_KEY) and returns a compact, ranked
list of {title, url, snippet} — plus Tavily's one-line answer when available.

Disabled gracefully if TAVILY_KEY is unset (the tool returns an error string; the
server still starts). Depends only on httpx (already a project dependency).
"""
from __future__ import annotations

import argparse
import os

import httpx
from mcp.server.fastmcp import FastMCP


mcp = FastMCP("internet_search")

_TAVILY_URL = "https://api.tavily.com/search"


@mcp.tool()
def internet_search(query: str, max_results: int = 5, deep: bool = False) -> str:
    """Search the web for documentation / answers (CDM, FpML, rosetta-model, Java…).

    Use as a LAST resort — the cheat-sheet and `cdm_lookup` come first. Good for a
    product or symbol the local references don't cover.

    Args:
        query:       What to search for.
        max_results: How many results to return (default 5).
        deep:        True for Tavily 'advanced' depth (slower, broader); else 'basic'.

    Returns plain text: an optional one-line answer, then ranked
    `[n] title — url\\n    snippet` entries.
    """
    key = os.environ.get("TAVILY_KEY", "").strip()
    if not key:
        return "<error>internet_search: TAVILY_KEY not set in environment — web search is unavailable.</error>"
    if not (query or "").strip():
        return "<error>internet_search: empty query</error>"

    payload = {
        "query": query,
        "search_depth": "advanced" if deep else "basic",
        "max_results": max(1, min(int(max_results or 5), 10)),
        "include_answer": True,
    }
    try:
        resp = httpx.post(
            _TAVILY_URL,
            json=payload,
            headers={"Authorization": f"Bearer {key}"},
            timeout=30,
        )
    except httpx.HTTPError as exc:
        return f"<error>internet_search: request failed ({type(exc).__name__}: {exc})</error>"

    if resp.status_code != 200:
        return f"<error>internet_search: Tavily returned HTTP {resp.status_code}: {resp.text[:200]}</error>"

    data = resp.json()
    lines: list[str] = []
    answer = (data.get("answer") or "").strip()
    if answer:
        lines.append(f"answer: {answer}\n")
    results = data.get("results") or []
    if not results:
        return (answer and "\n".join(lines)) or "(no results)"
    for i, r in enumerate(results, 1):
        title = (r.get("title") or "").strip()
        url = (r.get("url") or "").strip()
        snippet = " ".join((r.get("content") or "").split())[:300]
        lines.append(f"[{i}] {title} — {url}\n    {snippet}")
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="internet_search MCP server (Tavily wrapper)")
    parser.add_argument("--transport", default="stdio",
                        choices=["stdio", "streamable-http", "streamablehttp"])
    parser.add_argument("--port", type=int, default=8007)
    args = parser.parse_args()

    transport = args.transport.replace("streamablehttp", "streamable-http")
    if transport == "streamable-http":
        mcp.settings.port = args.port
        mcp.run(transport="streamable-http")
    else:
        mcp.run(transport="stdio")
