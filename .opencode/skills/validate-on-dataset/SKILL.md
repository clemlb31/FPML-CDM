---
name: validate-on-dataset
description: The validation discipline for this repo — how and when to run the full dataset tests and read the 3 signals. Use before declaring any FpML→CDM feature or fix complete.
---

# Validate on the full dataset

The repo's rule (see AGENTS.md): **a feature is not done until it's validated on the full train
dataset across all 3 signals.** No exceptions for "small" changes.

## How
- Full run (530 pairs × 3 signals): `run-dataset-tests` with `includeIncomplete: true` (default).
- Curated run (360 pairs): `run-dataset-tests` with `includeIncomplete: false`.
- Single signal while iterating: `run-dataset-tests` with `method: "semanticallyEqual"` (or
  `noNewCdmViolations` / `globalKeyIntegrity`).

## The 3 signals (all must stay green)
1. **`semanticallyEqual`** — output JSON matches the FINOS reference (`SemanticDiff`).
2. **`noNewCdmViolations`** — introduces no CDM data-rule violation absent from the reference.
3. **`globalKeyIntegrity`** — every `globalReference` resolves to a `globalKey` (no dangling refs).

## Reference numbers (must not regress)
- Curated: **360/360** (1080/1080 over 3 signals).
- Full: **530/530** (1590/1590 over 3 signals).
- Authoritative breakdown by category: [reports/fpml-cdm-train-530.md](../../../reports/fpml-cdm-train-530.md).

## If the build won't run
If dependency resolution fails with "No versions available …", the default `mvn` (`-takari`) is pointing
at the Murex Nexus mirror, unreachable off-VPN. Use a Maven Central `settings.xml` (`-s`) or reconnect to
the Nexus. Do **not** spend cycles trying random CDM versions — the project targets `cdm-java:6.19.0`.

Always finish by updating the relevant TODO.md item and, if you learned something durable about CDM or
FpML, by appending to the matching `knowledge_base/` note.
