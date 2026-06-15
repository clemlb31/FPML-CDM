# CDM 6.x metadata & cross-reference model

How CDM avoids duplicating data: values live in one place and are pointed at from elsewhere.
This is the part models get wrong most. Everything below is grounded in
`cdm/structure-skeleton.json` (grep it to see the exact shapes) and the CDM reference-data model.
Exact builder method names come from `cdm_lookup` — this page gives you the *concepts and the
right field names*, not code.

> Sources: CDM reference-data model — https://cdm.finos.org/docs/reference-data-model/ ;
> the Rosetta/Rune metadata annotations (`[metadata key]`, `[metadata reference]`,
> `[metadata scheme]`, `[metadata location]`).

## The big idea

A scalar in CDM is rarely bare. It is wrapped so it can carry metadata. Two wrapper families:

- **`FieldWithMetaXxx`** — a value plus optional metadata (a `scheme` URI, a `key`/`location`).
  Use it where the JSON shows `{ "value": …, "meta": { … } }`.
- **`ReferenceWithMetaXxx`** — a *pointer*: instead of an inline value it carries a reference to
  another node (by key) or an `address` (by location label).

## Four distinct mechanisms (don't conflate them)

### (A) The value side — real values live in `tradeLot`, tagged with a `location`
The actual notional, fixed rate, spread and floating index live **once**, under
`trade.tradeLot.priceQuantity` (grep `structure-skeleton.json`):
- `priceQuantity.quantity.value.{value, unit.currency}` — the notional amount + its currency,
  with `quantity.meta.location.{scope, value}` — a label such as scope `DOCUMENT`, value `quantity-1`.
- `priceQuantity.price.value.{value, unit.currency, priceType}` — the fixed rate, with a
  `meta.location` (e.g. `price-1`).
- `priceQuantity.observable.value.Index.InterestRateIndex.value.FloatingRateIndex{…}` — the
  floating index, with a `meta.location` (e.g. `InterestRateIndex-1`).

Each value is **anchored** by its `meta.location` (a `{scope, value}` pair). That label is what
the payouts point at.

### (B) The payout side — `address`es that point at those locations
The `InterestRatePayout` does **not** repeat the values. It holds **addresses** that must match a
location from (A):
- `priceQuantity.quantitySchedule.address.{scope, value}` → must match the quantity's location.
- `rateSpecification.FixedRateSpecification.rateSchedule.price.address.{scope, value}` → the price location.
- `rateSpecification.FloatingRateSpecification.rateOption.address.{scope, value}` → the observable location.
- `spreadSchedule.price.address`, `capRateSchedule`/`floorRateSchedule.price.address` likewise.

**Rule:** an address resolves only if its `scope` AND `value` equal some location's `scope` and
`value`. A typo in either is a silent broken reference → it shows up as MISSING/EXTRA in the diff,
not as a compile error. Keep the labels consistent (e.g. always `DOCUMENT` + `quantity-1`).

### (C) Object references — `globalReference` vs `externalReference`
To point at a whole *object* (a party, a set of business centers, a calculation-period-dates
block), a reference holder carries **one of two** keys (both appear in the skeleton as
`{ "globalReference": null, "externalReference": null }`):
- **`externalReference`** — the **source-document id**: the FpML `@id` / `href` string, e.g.
  `"party1"`. This is what you use when ingesting FpML — you already have the id.
- **`globalReference`** — the **computed global key** (a content hash) of the target's
  `meta.globalKey`. The framework derives it; you normally don't author it by hand.

Seen in the skeleton: `dateRelativeTo`, `calculationPeriodDatesReference`,
`businessCentersReference`, `quantityReference`, and `Counterparty.partyReference`.

### (D) The `meta` block — `globalKey`, `externalKey`, `scheme`
Most objects carry an optional `meta`:
- **`globalKey`** — a content-hash identity, computed by the model when the object can be a
  reference target. **You do not invent it.** (SemanticDiff ignores globalKey, so don't chase it.)
- **`externalKey`** — the source id carried over from FpML (e.g. the `@id` on a
  `calculationPeriodDates`). The skeleton shows `externalKey` on calculationPeriodDates,
  paymentDates and resetDates `meta`. This is the *target* side of an `externalReference`.
- **`scheme`** — a URI classifying a value (currency ISO-4217 scheme, party-id scheme, product
  taxonomy scheme). Skeleton: `unit.currency.meta.scheme`, `taxonomy.value.name.meta.scheme`.

## Referencing parties without duplication

Define each `Party` once under `trade.party` (id + name). Then **point** at it everywhere:
- `Trade.counterparty[]` maps a role to a party: `Counterparty.role` = `CounterpartyRoleEnum`
  (`PARTY_1` / `PARTY_2`) and `Counterparty.partyReference` = a `ReferenceWithMetaParty` whose
  `externalReference` is the FpML party id (`"party1"`).
- A payout's `payerReceiver` uses the **role enum** (`PARTY_1` / `PARTY_2`), never an inline
  party. Resolve an FpML `payerPartyReference/@href` to a role by document order (first party id
  → `PARTY_1`, else `PARTY_2`).

Never inline a second copy of a Party inside a counterparty or payout — that breaks the reference
model and shows up as duplicated objects in the diff.

## ⚠️ Correction of older guidance (do NOT do this)

Earlier notes said to *fabricate* a `globalKey` from a random UUID substring and set it manually,
and to always set both `globalReference` and an inline `value` on every reference. **That is wrong
for CDM 6.x.** The proven FpML→CDM approach is:
- price / quantity / observable links: **`address` ↔ `location`** pairs with `scope = DOCUMENT`
  and stable labels (`quantity-1`, `price-1`, `InterestRateIndex-1`).
- object references (parties, etc.): **`externalReference`** = the FpML id.
- leave `globalKey`/`globalReference` to the framework; SemanticDiff ignores them anyway.

Get the exact setter names (for `address`, `location`, `externalReference`, `scheme`,
`externalKey`, the wrapper builders) from `cdm_lookup` — never guess them.
