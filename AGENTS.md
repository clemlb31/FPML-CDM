# Operating notes for autonomous agents

## Environment
- **OS:** Windows. Shell is PowerShell; a Bash tool is also available (POSIX). `mvn` is **not** on PATH —
  use `C:\Maven\maven-3.9.12-takari\bin\mvn`. JDK is 21 (`JAVA_HOME` set); source level is Java 17.
- **Maven repo.** Dependencies (incl. `cdm-java:6.19.0`) are on **Maven Central**. But the default
  `-takari` Maven points at the **Murex Nexus mirror** (`pandora.fr.murex.com` / `repo-dev/nexus`),
  unreachable off-VPN → `mvn` fails with `No versions available …`. Fix: run with a Maven Central
  `settings.xml` (`mvn -s …`) or reconnect to the Nexus. The project targets `cdm-java:6.19.0` — **don't
  try random CDM versions.** Verify any CDM symbol with the `cdm-api` tool instead of guessing.

## Workflow
- Read [README.md](README.md) (hub) and the relevant compartment doc under [docs/](docs/)
  (`docs/fpml-cdm.md`, `docs/mxml-fpml.md`, `docs/mxml-cdm.md`), plus [TODO.md](TODO.md), before starting.
  After context compaction, re-read them before deciding anything.
- Pick the next unchecked item from `TODO.md`. If blocked, note the blocker in the relevant TODO item and
  move to the next independent task.
- **Always run the tests on the full train dataset when validating a feature:**
  ```
  mvn test -Dtest=DataDrivenValidationTest -Dincludeincomplete=true
  ```
  ("Done" = build green, all 3 validation signals pass, TODO updated.)
- Capture durable domain insights in [knowledge_base/](knowledge_base/) — `fpml-cdm/` (CDM quirks,
  FpML→CDM patterns) or `mxml-fpml/` (XSLT spec) — not in throwaway notes.

## Current truth (keep in sync)
- **FpML → CDM:** 530/530 full · 360/360 curated · 3 signals (1590/1590, 1080/1080).
- **CDM → FpML:** prototype, ~51 %, 0 clean round-trips, has open compile defects (see docs/cdm-to-fpml.md).
- **MXML ↔ FpML / MXML ↔ CDM:** see docs/mxml-fpml.md / docs/mxml-cdm.md (port from XSLT spec; chaining).
