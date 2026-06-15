# FpML → CDM mapping principles (the bridge)

General principles that apply to every product family before you touch a specific field map.
Source side: [../fpml/](../fpml/). Target side: [../cdm/](../cdm/). These are concepts in words —
the transform "code" is described, never given.

> Context: CDM ships its own FpML mapping (FpML_5_10 synonyms; FpML→CDM ingest functions in
> CDM 7-dev) — https://cdm.finos.org/docs/mapping/ . We hand-roll the transformer instead, but the
> *same* target shapes apply, so the expected JSON and `../cdm/structure-skeleton.json` are ground
> truth for "where does this go".

## 1. The expected JSON is the contract

The success criterion is `SemanticDiff` match against the expected CDM JSON (globalKey ignored,
numbers BigDecimal-compared). So: when a mapping is unclear, **grep the expected JSON** for the
trade and **grep `../cdm/structure-skeleton.json`** for the field's path. Map *to what the target
shows*, not to what seems reasonable.

## 2. Values live once; everything else points at them

The actual notional / rate / spread / index live once in `trade.tradeLot.priceQuantity`, each
tagged with a `meta.location` (`{scope: DOCUMENT, value: <label>}`). The payouts hold **addresses**
with the matching `{scope, value}`. Get the labels identical on both sides, or the reference breaks
silently (MISSING/EXTRA in the diff, not a compile error). Full rules:
[../cdm/meta-and-references.md](../cdm/meta-and-references.md).

## 3. Parties: define once, reference by id

Each FpML `<party id="X">` becomes one CDM `Party` under `trade.party`. References use the FpML
href string as the CDM `externalReference`. A leg's payer/receiver becomes a
`CounterpartyRoleEnum` (`PARTY_1`/`PARTY_2`), resolved by document order, and `Trade.counterparty`
maps each role to its party. Never inline a party twice.

## 4. Dates are per-leg

Set effective/termination/frequency/payment/reset dates inside each
`InterestRatePayout.calculationPeriodDates` (and `paymentDates`/`resetDates`), per leg — even when
both legs share the dates. FpML gives unadjusted dates as text; the adjusted date is usually
computed (set it only if the expected JSON has it). See [../cdm/dates.md](../cdm/dates.md).

## 5. Set a field only if the source exists

An absent FpML element means leave the CDM field unset — do **not** pass null into a builder (it
compiles, then NullPointer-crashes at runtime → score 0). Guard on presence. See
[../cdm/pitfalls.md](../cdm/pitfalls.md).

## 6. Transform vocabulary (described, not coded)

When a field map says "transform: …", it means one of these well-known operations — implement them
in the transformer; this KB does not hand you the code:
- **parse date** — FpML `yyyy-MM-dd` text → CDM `Date` (see [../cdm/dates.md](../cdm/dates.md)).
- **parse decimal / integer** — numeric text → `BigDecimal` / `int`.
- **enum map** — FpML token → CDM enum constant, with mangling (see [../cdm/enums.md](../cdm/enums.md)).
- **wrap** — scalar → `FieldWithMetaXxx` (see [../cdm/meta-and-references.md](../cdm/meta-and-references.md)).
- **address ref** — emit a `{scope: DOCUMENT, value: <label>}` pointing at a tradeLot location.
- **role resolve** — FpML `@href` → `CounterpartyRoleEnum` by document order.

## 7. Per-family files

Each family has a mapping file ([rates.md](rates.md) is deep; the rest are scaffolds). The field
tables there read: **FpML xpath → CDM path → transform (in words)**. Fill a scaffold by working a
train pair (`data/train/<family>-*/`) against `../cdm/structure-skeleton.json` and `cdm_lookup`.
