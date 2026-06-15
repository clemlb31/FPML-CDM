"""Central registry of tools exposed to the LLM.

Single source of truth for:
  • the prompt-time description (XML-mode system prompt) of each tool
  • the JSON Schema (native tool_use API) of each tool's arguments

Adding a new MCP tool to expose:
  1. Add an entry below: name → {"description": …, "input_schema": {…}}.
  2. Restart the agent. That's it.

The local-only `compact_context` and `spawn_subagent` tools are also declared
here so the model sees them in the same list as MCP tools.
"""
from __future__ import annotations

from agent.context import (
    COMPACT_TOOL_DESCRIPTION,
    COMPACT_TOOL_INPUT_SCHEMA,
    COMPACT_TOOL_NAME,
)


# ── Tool registry ──────────────────────────────────────────────────────────────

EXPOSED_TOOLS: dict[str, dict] = {

    # ── filesystem MCP (read) ─────────────────────────────────────────────────
    "read_file": {
        "description": "Read a UTF-8 file from disk.",
        "input_schema": {
            "type": "object",
            "required": ["path"],
            "properties": {"path": {"type": "string"}},
        },
    },
    "read_multiple_files": {
        "description": "Read several files in one call. Returns a path → content map.",
        "input_schema": {
            "type": "object",
            "required": ["paths"],
            "properties": {
                "paths": {"type": "array", "items": {"type": "string"}}
            },
        },
    },
    "list_directory": {
        "description": "List file and sub-directory names in a directory.",
        "input_schema": {
            "type": "object",
            "required": ["path"],
            "properties": {"path": {"type": "string"}},
        },
    },
    "directory_tree": {
        "description": "Return a recursive JSON tree of a directory.",
        "input_schema": {
            "type": "object",
            "required": ["path"],
            "properties": {"path": {"type": "string"}},
        },
    },
    "search_files": {
        "description": (
            "Recursively find files/directories whose name matches a glob pattern "
            "(e.g. '**/*.java'). Searches by filename only — not contents. "
            "Use `grep` to search inside files."
        ),
        "input_schema": {
            "type": "object",
            "required": ["path", "pattern"],
            "properties": {
                "path": {"type": "string"},
                "pattern": {"type": "string"},
                "excludePatterns": {
                    "type": "array",
                    "items": {"type": "string"},
                    "default": [],
                },
            },
        },
    },

    # ── filesystem MCP (write) — wrapped locally for safety ───────────────────
    "write_file": {
        "description": (
            "Create or overwrite a UTF-8 file. Parent directory is created "
            "automatically if missing."
        ),
        "input_schema": {
            "type": "object",
            "required": ["path", "content"],
            "properties": {
                "path":    {"type": "string"},
                "content": {"type": "string"},
            },
        },
    },
    "edit_file": {
        "description": (
            "Apply a list of find/replace edits to a file. Each `oldText` MUST "
            "appear exactly once in the file or the edit fails — add surrounding "
            "context to disambiguate when needed."
        ),
        "input_schema": {
            "type": "object",
            "required": ["path", "edits"],
            "properties": {
                "path":  {"type": "string"},
                "edits": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "required": ["oldText", "newText"],
                        "properties": {
                            "oldText": {"type": "string"},
                            "newText": {"type": "string"},
                        },
                    },
                },
                "dryRun": {"type": "boolean", "default": False},
            },
        },
    },
    "mkdir_p": {
        "description": "Create a directory tree recursively (parents OK, idempotent).",
        "input_schema": {
            "type": "object",
            "required": ["path"],
            "properties": {"path": {"type": "string"}},
        },
    },

    # ── grep MCP (content search) ─────────────────────────────────────────────
    "grep": {
        "description": (
            "Search file contents via ripgrep. Returns matches as file:line:text. "
            "Use `include` for a glob filter (e.g. '*.java'). Much faster than "
            "read_file + scan when looking up a symbol across many files."
        ),
        "input_schema": {
            "type": "object",
            "required": ["pattern"],
            "properties": {
                "pattern":     {"type": "string"},
                "path":        {"type": "string", "default": "."},
                "include":     {"type": "string", "description": "glob filter, e.g. '*.java'"},
                "max_results": {"type": "integer", "default": 50},
            },
        },
    },

    # ── validator MCP ─────────────────────────────────────────────────────────
    "compile_project": {
        "description": "Run `mvn clean compile` on a Maven project. Returns ok + errors[].",
        "input_schema": {
            "type": "object",
            "required": ["project_dir"],
            "properties": {"project_dir": {"type": "string"}},
        },
    },
    "run_test": {
        "description": (
            "Package the project, run the JAR on an FpML file, and diff its JSON "
            "output against an expected CDM JSON file. Returns score + diffs."
        ),
        "input_schema": {
            "type": "object",
            "required": ["project_dir", "fpml_file", "expected_json_file"],
            "properties": {
                "project_dir":        {"type": "string"},
                "fpml_file":          {"type": "string"},
                "expected_json_file": {"type": "string"},
            },
        },
    },

    # ── internet_search MCP (web search — Tavily wrapper) ─────────────────────
    "internet_search": {
        "description": (
            "Search the web. LAST resort during research — to look up CDM / FpML / "
            "rosetta-model docs or the real 6.19 API when the cheat-sheet and "
            "cdm_lookup don't cover a product or symbol. Returns an optional one-line "
            "answer + ranked results (title, url, snippet)."
        ),
        "input_schema": {
            "type": "object",
            "required": ["query"],
            "properties": {
                "query":       {"type": "string"},
                "max_results": {"type": "integer", "default": 5},
                "deep":        {"type": "boolean", "default": False,
                                "description": "advanced (slower, broader) search depth"},
            },
        },
    },

    # ── cdm_lookup MCP (CDM 6.19 API introspection — mcp_servers/cdm_lookup_server) ─
    "cdm_lookup": {
        "description": (
            "Look up the REAL CDM 6.19 API for a type by name. Returns the exact "
            "builder set*/add* method signatures (or, for an enum, its real "
            "constants) straight from the jar. Use this to VERIFY a method/enum name "
            "before writing it, or whenever the compiler says 'cannot find symbol' — "
            "do not guess. e.g. name='TradeLot' or name='DayCountFractionEnum'."
        ),
        "input_schema": {
            "type": "object",
            "required": ["name"],
            "properties": {"name": {"type": "string"}},
        },
    },

    # ── Local-only (not MCP — manipulate the agent loop's own state) ───────────
    COMPACT_TOOL_NAME: {
        "description":  COMPACT_TOOL_DESCRIPTION,
        "input_schema": COMPACT_TOOL_INPUT_SCHEMA,
        "local":        True,
    },
    "spawn_subagent": {
        "description": (
            "Delegate a self-contained task to a focused sub-agent that shares the "
            "same tools and project_dir but cannot recurse. Use when ≥2 independent "
            "method bodies can be filled in parallel."
        ),
        "input_schema": {
            "type": "object",
            "required": ["task"],
            "properties": {
                "task":  {"type": "string"},
                "label": {"type": "string", "default": "subagent"},
            },
        },
        "local": True,
    },
}


def render_tools_doc(include: list[str] | None = None) -> str:
    """Render a markdown bullet list of (name, description) for prompts."""
    names = include if include is not None else list(EXPOSED_TOOLS.keys())
    lines = []
    for name in names:
        spec = EXPOSED_TOOLS.get(name)
        if not spec:
            continue
        lines.append(f"- `{name}` — {spec['description']}")
    return "\n".join(lines)


def schema_registry(include: list[str] | None = None) -> dict[str, dict]:
    """Subset of EXPOSED_TOOLS keyed by name with description + input_schema only."""
    names = include if include is not None else list(EXPOSED_TOOLS.keys())
    return {
        n: {
            "description":  EXPOSED_TOOLS[n]["description"],
            "input_schema": EXPOSED_TOOLS[n].get("input_schema") or {"type": "object", "properties": {}},
        }
        for n in names if n in EXPOSED_TOOLS
    }
