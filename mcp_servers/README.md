# MCP servers

The autonomous agent ([`agent/autonomous.py`](../agent/autonomous.py)) does not touch
the disk, the compiler or the web directly. Every capability is a **separate MCP server**
living in its own folder here, so each is small, independent and replaceable — that is
the whole design goal (robust & understandable, parts as independent as possible).

The agent connects to all of them through one `MultiServerMCPClient`
([`agent/helpers.py::get_servers`](../agent/helpers.py)), configured in
[`configs/mcp.yaml`](../configs/mcp.yaml).

```
agent ──┬─▶ filesystem       :8080   read refs / read-write the build sandbox
        ├─▶ validator        :8003   compile + run_test (Docker Maven)
        ├─▶ grep             :8005   ripgrep content search
        ├─▶ cdm_lookup       :8006   CDM 6.19 API introspection (javap)
        └─▶ internet_search  :8007   web search (Tavily wrapper)
```

## Run them

```bash
bash mcp_servers/start_all.sh          # start the 4 local servers (foreground)
bash mcp_servers/start_all.sh --stop   # stop them
```

Each server is a standalone module you can also run alone, e.g.:

```bash
.venv/bin/python -m mcp_servers.cdm_lookup_server.server --transport streamable-http --port 8006
```

All are built on **FastMCP** (`mcp.server.fastmcp`), speak **streamable-http**, expose
their tools via `@mcp.tool()`, and return strings (JSON or plain text). PIDs go to
`.mcp_pids/`, logs to `.mcp_logs/`.

---

## The servers in detail

### `filesystem_server/` — port 8080
Sandboxed file access. **Replaces** the off-the-shelf `@modelcontextprotocol/server-filesystem`
(previously run via `npx supergateway`) with a server we own — one fewer external
dependency, and native recursive `mkdir`.

Two-tier sandbox (every path is resolved and checked):
- **READ** allowed under: `workspaces/`, `knowledge_base/`, `data/`
- **WRITE** allowed under: `workspaces/` **only**

So the agent reads the references and FpML inputs but can only ever create/modify files
inside its build sandbox. Tools:

| Tool | Args | Effect |
|---|---|---|
| `read_file` | `path` | return UTF-8 contents (read roots) |
| `read_multiple_files` | `paths` | read several at once → JSON path→content map |
| `write_file` | `path, content` | create/overwrite under `workspaces/`; parent dirs auto-created |
| `edit_file` | `path, edits:[{oldText,newText}], dryRun?` | apply replacements; each `oldText` must match **exactly once** or the whole edit is rejected |
| `mkdir_p` | `path` | recursive, idempotent mkdir under `workspaces/` |
| `list_directory` | `path` | list entry names (dirs suffixed `/`) |

> Note: the agent also wraps `write_file`/`edit_file` on its side
> ([`agent/tool_wrappers.py`](../agent/tool_wrappers.py)) for extra safety; the server
> enforces the same invariants independently.

### `validator_server/` — port 8003 — **requires Docker**
The compile/test feedback loop. Runs Maven **inside a persistent Docker container** so the
build is hermetic and the host stays clean. Exposes 8 tools; the agent uses only the first
two — the rest are a batch-evaluation / diagnostic harness for measuring the pipeline across
the whole test suite (not wired into the agent prompt).

**Used by the agent**

| Tool | Args | Returns |
|---|---|---|
| `compile_project` | `project_dir` | `{ok, errors:[{file,line,message}]}` from `mvn clean compile` |
| `run_test` | `fpml_file, expected_json_file, project_dir` | packages, runs the jar on the FpML, diffs output vs the expected CDM JSON → `{ok, match, score, score_detail, crash, differences}` |

`run_test` is where the score comes from: `score_detail` gives matched/total + wrong_values,
`differences` is the per-field MISSING/EXTRA list, and `crash` (parsed from the stack trace)
gives `{exception, message, method, file, line}` when the program throws.
See [`validator_server/_internals.py`](validator_server/_internals.py).

**Batch / diagnostic harness** (loaded, NOT exposed to the model — useful for measuring
reproducibility & generalisation across products)

| Tool | What it does |
|---|---|
| `run_test_all` | package once, run against **every** case in the active suite |
| `run_arbitrary_test` | run the jar on an FpML file, return raw stdout/stderr (no packaging) |
| `list_test_suites` | list suites from `workspaces/test_config.yaml` + case counts |
| `get_test_cases` | list the `(fpml, cdm)` pairs of a suite |
| `extract_method_source` | pull one method's source out of a Java file (diagnostic) |
| `score_with_llm` | LLM-as-judge semantic scoring of a mapping vs expected |

> ⚠️ If Docker is down/restarted while the server holds a dead container id, every call
> returns "container … is not running" — which the agent mistakes for a code error. Fix:
> ensure Docker is up, then restart this server so it spins a fresh container.

### `grep_server/` — port 8005
Content search via **ripgrep** (falls back to a pure-Python walker if `rg` is absent).
The filesystem server only matches by *name*; this one searches *inside* files — much
faster than `read_file` + scan for locating a symbol across many files.

| Tool | Args | Returns |
|---|---|---|
| `grep` | `pattern, path?, include?, max_results?` | `{ok, tool, matches:[{file,line,text}], truncated}` |

Read sandbox mirrors the filesystem roots (+ `agent/`, `mcp_servers/`).

### `cdm_lookup_server/` — port 8006
**CDM 6.19 API introspection.** The model's biggest failure mode is *inventing* CDM
builder/enum names. This server lets it **query the real API** instead of guessing: it
locates the `cdm-java` jar under `~/.m2`, resolves a type name → fully-qualified name
(`unzip -l`), and `javap`s the `$Builder` inner interface (or the enum).

| Tool | Args | Returns (plain text) |
|---|---|---|
| `cdm_lookup` | `name` | a type → its builder `set*/add*` signatures; an enum → its constants; or a "did you mean…" list |

Examples: `cdm_lookup("TradeLot")` → `addPriceQuantity(PriceQuantity)…`;
`cdm_lookup("DayCountFractionEnum")` → `ACT_360, _30_360, ACT_ACT_ISDA…`. This is
facts-on-demand — like the compiler — so **no CDM source code is handed to the model**.
Depends on a JDK (`javap`) and `unzip` on PATH.

### `internet_search_server/` — port 8007
Web search, unified behind a **single** `internet_search` tool, backed by **Tavily**. We
wrap Tavily's REST API ourselves (httpx, key from `TAVILY_KEY` in `.env`) instead of wiring
the remote hosted Tavily MCP — so it is the same FastMCP/Python style as the rest, with one
clean tool instead of five oddly-named ones.

| Tool | Args | Returns |
|---|---|---|
| `internet_search` | `query, max_results?, deep?` | optional one-line answer + ranked `[n] title — url + snippet` |

Last resort during research (the cheat-sheet + `cdm_lookup` come first). If `TAVILY_KEY` is
unset the server still starts but the tool returns an error string.

---

## What is NOT an MCP server (and why)

`compact_context` and `spawn_subagent` are **local tools inside the agent loop**, not MCP
servers — they manipulate the agent's own conversation state (compacting the message
history; launching a child run that shares the loop's context). They cannot live behind an
MCP boundary because they need in-process access to the loop. See
[`agent/autonomous.py`](../agent/autonomous.py).

## Design notes (not servers)

`dev_server/`, `exemple_server/`, `knowledge_server/` are **not** running servers — each
holds a one-line note recording the ORIGINAL design, where every directory was exposed by
its own `npx @modelcontextprotocol/server-filesystem` instance on its own port
(workspaces:8080, data/train:8081, knowledge_base:8082). That approach was **consolidated**
into the single sandboxed `filesystem_server/` above (one server covering all roots — two
filesystem servers would shadow each other's `write_file` under MultiServerMCPClient). Kept
as a record of intent; not in `configs/mcp.yaml`.

(`triage_server/` and `mapping_server/`, leftovers from the earlier graph pipeline, were
removed — they were unused real servers, unlike the notes above.)
