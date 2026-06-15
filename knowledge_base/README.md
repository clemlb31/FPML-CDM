# Knowledge base — index (read this first)

Reference **documentation** for converting FpML 5.x → CDM 6.19. It describes the model, the
source format, and the field mappings **in prose**. It contains **no ready-made transformer code**
by design: exact builder/enum signatures come from the `cdm_lookup` tool (live `javap` on the jar).
Open the precise file you need (titles below), and `grep` rather than reading whole files.

## The three golden rules

1. **Never guess an API name.** For any CDM type/method/enum constant, run `cdm_lookup name=<Type>`.
   The jar is the only authority. A guessed name is a "cannot find symbol" compile error.
2. **Map to what the target shows.** The expected CDM JSON is the contract. To find a field's exact
   path/shape, grep [cdm/structure-skeleton.json](cdm/structure-skeleton.json) — a full `TradeState`
   with every value `null`.
3. **Documentation, not code.** These files explain patterns; you write the Java. If you ever find a
   copyable `.java` snippet here, treat it as a bug to report, not a template.

## Where to look — "to do X, read Y"

| You need to… | Read |
|---|---|
| understand the CDM object graph (TradeState→Trade→…→InterestRatePayout) | [cdm/object-model.md](cdm/object-model.md) |
| know the exact JSON path / attribute name of a CDM field | grep [cdm/structure-skeleton.json](cdm/structure-skeleton.json) |
| understand how CDM builders work (set vs add, build, wrappers) | [cdm/builder-conventions.md](cdm/builder-conventions.md) |
| reference parties / link price+quantity (the meta & reference model) | [cdm/meta-and-references.md](cdm/meta-and-references.md) |
| handle dates (Date type, AdjustableDate, schedules) | [cdm/dates.md](cdm/dates.md) |
| convert an FpML token to a CDM enum constant (+ the PeriodEnum trap) | [cdm/enums.md](cdm/enums.md) |
| fix a compile/runtime error (the "when it won't compile" protocol) | [cdm/pitfalls.md](cdm/pitfalls.md) |
| get a type's authoritative attributes + cardinality | grep [cdm/rosetta/](cdm/rosetta/) ; or [cdm/hierarchy.txt](cdm/hierarchy.txt) |
| read the FpML document layout (root, parties, namespaces, hrefs) | [fpml/document-structure.md](fpml/document-structure.md) |
| read the FpML source shape for a product family | `fpml/<family>.md` (e.g. [fpml/rates.md](fpml/rates.md)) |
| map FpML→CDM fields | [mapping/principles.md](mapping/principles.md) then `mapping/<family>.md` (e.g. [mapping/rates.md](mapping/rates.md)) |
| write the pom / know the Maven coordinates | [build/dependencies.md](build/dependencies.md) |

## Layout

- **cdm/** — the target model (CDM 6.x), product-agnostic concepts. Plus the ground-truth dumps:
  `structure-skeleton.json` (JSON shape), `rosetta/` (model DSL), `hierarchy.txt` (type tree).
- **fpml/** — the source model (FpML 5.x): `document-structure.md` + one file per product family.
- **mapping/** — the bridge: `principles.md` + one field-map per family.
- **build/** — Maven coordinates.
- **policies/** — `cdm_structure.rego` (a structural validation policy).
- **notes/** — the agent's working memory (decisions log, observations, trace) — append findings here.

## Coverage status

**Rates (IRS / FRA / OIS) is filled in depth** — [fpml/rates.md](fpml/rates.md),
[mapping/rates.md](mapping/rates.md), and all `cdm/` cross-cutting files. The other families
(credit, equity, fx, commodity, bond-options, correlation/dividend/variance/volatility swaps,
inflation swaps, repo, loan, securities) are **precise scaffolds** — they name the FpML element and
the CDM target payout, and tell you to fill the field map by working a `data/train/<family>-*/` pair
against `cdm/structure-skeleton.json` + `cdm_lookup`. Expand a scaffold as you learn the family;
record what you learn in `notes/`.
