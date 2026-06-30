# FpML · CDM · MXML — Conversion Engine

Conversion engine between three representation formats for financial products:
**FpML 5.x** (XML), **FINOS CDM 6.19.0** (JSON) and **MXML** (Murex XML).

The project is organized into **3 bidirectional compartments**:

| Compartment | Directions | Status | Doc |
|---|---|---|---|
| **FpML ↔ CDM** | FpML→CDM ✅ · CDM→FpML 🧪 | FpML→CDM **530/530** · CDM→FpML prototype | [docs/fpml-cdm.md](docs/fpml-cdm.md) · [docs/cdm-to-fpml.md](docs/cdm-to-fpml.md) |
| **MXML ↔ FpML** | MXML→FpML 🔨 · FpML→MXML ⏳ | Java port from the Murex XSLT spec | [docs/mxml-fpml.md](docs/mxml-fpml.md) |
| **MXML ↔ CDM** | MXML→CDM 🔗 · CDM→MXML ⏳ | By chaining MXML→FpML→CDM | [docs/mxml-cdm.md](docs/mxml-cdm.md) |

Cross-cutting: [schemas & structural validity](docs/schemas-and-validation.md) · [OpenCode tooling](docs/opencode-setup.md) · roadmap [TODO.md](TODO.md).

## Repository map

```
.
├── README.md                  # this hub
├── AGENTS.md                  # agent rules (OpenCode + Claude Code)
├── TODO.md                    # cross-cutting roadmap (by compartment)
├── pom.xml
│
├── docs/                      # 1 doc per compartment + cross-cutting
│   ├── fpml-cdm.md  cdm-to-fpml.md
│   ├── mxml-fpml.md  mxml-cdm.md
│   ├── schemas-and-validation.md
│   └── opencode-setup.md
│
├── schemas/                   # XSD / JSON-schema for structural validity
│   ├── mxml/  fpml/  cdm/      (drop the schemas here — see each README)
│
├── reports/                   # generated validation reports
│   └── fpml-cdm-train-530.md
│
├── src/main/java/io/fpmlcdm/
│   ├── core/                  # shared abstraction layer (conversion, party, date, xml, error, validation)
│   ├── fpml/cdm/              # FpML→CDM (common/ detect/ products/ payouts/ validate/)
│   ├── cdm/fpml/              # CDM→FpML
│   ├── mxml/fpml/             # MXML→FpML (skeleton)
│   ├── mxml/cdm/              # MXML→CDM (chains MXML→FpML→CDM)
│   ├── report/                # SemanticDiff, ReportWriter (shared)
│   └── schema/                # XsdValidator (pure JDK, structural)
│
├── data/
│   ├── train/                 # FpML/CDM pairs (FpML↔CDM compartment)
│   └── mxml/                  # MXML samples + expected FpML + expected CDM
│
├── knowledge_base/            # grouped by compartment
│   ├── fpml-cdm/              # CDM notes, references, rules, mapping concepts
│   └── mxml-fpml/             # Murex XSLT modules (spec) + manifest
│
└── .opencode/ + opencode.json # OpenCode tooling compartment (tools + skills)
```

## Quick start

```bash
# FpML → CDM : build + tests (full dataset, 3 signals)
mvn clean package -DskipTests
mvn test -Dtest=DataDrivenValidationTest -Dincludeincomplete=true

# XML structural validity (pure JDK, no Maven/CDM)
javac -d tmp/schema-out src/main/java/io/fpmlcdm/schema/*.java
java -cp tmp/schema-out io.fpmlcdm.schema.XsdValidateCli --xsd schemas/fpml/<root>.xsd --input <doc.xml>
```

> ⚠️ **Build.** The deps (incl. `cdm-java:6.19.0`) are on Maven Central, but the default `-takari` `mvn`
> points at the Murex Nexus (unreachable off-VPN) → use `mvn -s <settings-central.xml>`. Detail:
> [docs/schemas-and-validation.md](docs/schemas-and-validation.md) → *Build / environment*.
