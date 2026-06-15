# scaffold/ — pre-staged boilerplate (infra, NOT mapping)

The harness ([agent/autonomous.py](../agent/autonomous.py) `_prestage_boilerplate`) copies these
proven, **product-agnostic** files into every run's `project_dir` before the agent starts, so the
model spends its whole budget on the FpML→CDM **mapping** (`IrsTransformer.java`) rather than
re-deriving the plumbing each time.

| File | Pre-staged to | What it is |
|---|---|---|
| `pom.xml` | `project_dir/pom.xml` | Maven build: cdm-java 6.19.0, jackson, guice, assembly plugin (Java 17) |
| `FpmlToCdmApp.java` | `project_dir/src/main/java/com/example/FpmlToCdmApp.java` | the main: parse XML → `new IrsTransformer().transform(doc)` → serialize (RosettaObjectMapper) → diff |
| `SemanticDiff.java` | `project_dir/src/main/java/com/example/SemanticDiff.java` | CDM JSON comparator (drops globalKey, BigDecimal-compare, order-insensitive) |

**The model writes exactly one file:** `src/main/java/com/example/IrsTransformer.java` — a
`package com.example;` class exposing `public cdm.event.common.TradeState transform(org.w3c.dom.Document doc)`,
which is the contract `FpmlToCdmApp.java` calls. This is **infra**, not the mapping — consistent
with the constraint "never hand the model pre-written *mapping* code". Sourced verbatim from a
converging run. Disable with `--no-boilerplate` (ablation: model writes everything).
