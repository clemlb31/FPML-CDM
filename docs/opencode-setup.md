# Setup OpenCode — MCP, tools & skills

Tooling added to develop this repo with OpenCode + a local model (Qwen3.5 32B A3B).

**Principle.** A small MoE model hallucinates and forgets more than a large one. Everything here aims to **ground the
model in the truth** (the real CDM API) and **automate verification** (tests/diff), rather than
adding capabilities. This is exactly what was making the project fail: inventing the CDM API and
wrong build commands.

> Discipline rule: **few tools, but sharp ones.** Beyond ~15-20 active tools, a 32B
> chooses poorly. Don't pile on other MCPs without a reason.

---

## 1. Custom tools — `.opencode/tools/`

TypeScript tools (the `tool()` helper from `@opencode-ai/plugin`). The file name = the tool name.
They run in OpenCode's Bun runtime and shell out to `javap` / `jar` / `mvn`.

| Tool | Role | Args |
|---|---|---|
| **`cdm-api`** | ★ Anti-hallucination. Real signatures of a CDM class/enum/builder via `javap`, on the pom's `cdm-java` jar (6.19.0). Accepts a simple name or FQN; if not found, lists the candidates. | `symbol` |
| **`cdm-source`** | Greps the CDM **source code** (the `.m2` has the `…-sources.jar`). Shows the real implementation of builders/enums. Extracts the sources once into `tmp/cdm-sources/`. | `pattern`, `max?` |
| **`run-dataset-tests`** | Runs `DataDrivenValidationTest` with the right flags; returns the Surefire summary. Detects the "Murex mirror unreachable" blockage. | `includeIncomplete?`, `method?` |
| **`diff-pair`** | Semantic diff of **one** pair (`io.fpmlcdm.DiffOne`) → `EQUAL` or the list of diffs. Fast iteration loop. | `category`, `base` |
| **`category-report`** | Pass/fail score per category (`io.fpmlcdm.CategoryReport`) + failing pairs sorted. | `categories[]` |

**The most valuable one: `cdm-api`.** 90% of the repo's bugs come from non-existent CDM classes/methods
(this is what was breaking the build). The model must call it **before** writing any code touching CDM.

> The 3 mvn wrappers use `C:\Maven\maven-3.9.12-takari\bin\mvn.cmd` by default.
> Override it via the environment variable **`OPENCODE_MVN`** if your path differs.

---

## 2. MCP servers — `opencode.json`

| Server | Role | Prerequisites |
|---|---|---|
| **serena** (`oraios/serena`) | ★ **Semantic** navigation of the Java code (find symbol / references / go-to-def) over the 40k lines, via LSP (eclipse-jdt). Much better than grep for a small model. | `uv`/`uvx` installed; the 1st run downloads the Java LSP |
| **context7** (`@upstash/context7-mcp`) | Up-to-date docs of **public** libs (Jackson, Guice, picocli, JUnit). Useless for CDM/rosetta (niche) → hence `cdm-api`. | `node`/`npx` |

**Deliberately absent** (already native in OpenCode, duplicating them = noise): filesystem, git, fetch/web.

---

## 3. Skills — `.opencode/skills/`

Reusable instructions, **lazy-loaded** (loaded on demand) → minimal context for the 32B.
Fed from the repo's real content (README, `knowledge_base/`).

| Skill | Triggers when… |
|---|---|
| **`cdm-builder-recipe`** | you build CDM objects (builders, enums, choice/wrapper, address-refs, externalKey). Distills `cdm_api_quirks.md` + enforces "verify with `cdm-api`". |
| **`add-product-mapper`** | you add/extend an FpML→CDM product mapper (7-step recipe). |
| **`diff-failing-pair`** | a pair fails and you have to find the root cause. |
| **`validate-on-dataset`** | before declaring a feature finished (the 3-signal discipline, reference figures). |

Tip: you can add a mini-skill per product family (one `SKILL.md` per folder in
`knowledge_base/`) so that the model only loads the relevant note.

---

## 4. Subagent — `explore` (in `opencode.json`)

A **read-only** agent (write/edit/patch and the mvn tools disabled) for navigation/search.
Keeps the main context small: it returns conclusions (`file:line`), not dumps.

---

## 5. Installation & verification

```bash
# MCP prerequisites
#   serena   -> uv (https://docs.astral.sh/uv/)
#   context7 -> node/npm

# Launch opencode at the repo root: it automatically loads
#   opencode.json, .opencode/tools/*, .opencode/skills/*
opencode
```

- **Tools**: type `/` or ask the model "which tools do you have?" — `cdm-api`, `cdm-source`,
  `run-dataset-tests`, `diff-pair`, `category-report` should appear.
- **Skills**: should be listed as available (loaded on demand).
- **MCP**: check the serena/context7 status at startup (OpenCode logs).
- `@opencode-ai/plugin` is provided by OpenCode at runtime; for autocompletion/typing locally:
  `bun add -d @opencode-ai/plugin` (optional).

## Caveats
- **Windows** paths: tools in `process.env.USERPROFILE`, `JAVA_HOME` for `javap`/`jar`.
- `cdm-source` extracts ~a few thousand files into `tmp/cdm-sources/` on the 1st call (slow once).
- If `run-dataset-tests` returns "No versions available…": that's the Murex mirror being unreachable → use
  Maven Central settings (`mvn -s`) or VPN. See [../TODO.md](../TODO.md) → *Build / environment*.
