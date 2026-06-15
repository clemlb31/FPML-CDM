# CDM 6.x object model — the top-level graph

What wraps what, where each piece of data lives, and the order to build things in.
This describes the **shape** of the model in words. It does **not** give code — get the
exact builder method names from `cdm_lookup name=<Type>`, and confirm container attribute
names against the `.rosetta` files in `cdm/rosetta/` or `cdm/hierarchy.txt`.

> Source: FINOS CDM product model — https://cdm.finos.org/docs/product-model/

## The serialized root is `TradeState`

The JSON you must produce has `TradeState` at its root (it is the `[rootType]`). A
`TradeState` carries a `Trade`. Everything else hangs under that `Trade`.

## The spine, from the root down

```
TradeState
└─ trade : Trade
   ├─ product : NonTransferableProduct        the economic product (party-agnostic)
   │  └─ economicTerms : EconomicTerms
   │     └─ payout : Payout[]                  ONE per leg (a swap has 2)
   │        └─ interestRatePayout : InterestRatePayout   (rates); other payout kinds
   │                                                       exist for other asset classes
   ├─ tradeLot : TradeLot[]                    holds the ACTUAL notional + price values
   │  └─ priceQuantity : PriceQuantity[]
   ├─ counterparty : Counterparty[]            role → party reference (PARTY_1 / PARTY_2)
   ├─ party : Party[]                          the real Party objects (ids, names)
   ├─ tradeIdentifier : TradeIdentifier[]      one per partyTradeIdentifier in FpML
   └─ tradeDate                                the trade date
```

The indentation is the **containment**, not Java. Confirm the exact attribute name on each
container with `cdm_lookup` (e.g. is it `Trade.product` or nested under a tradable-product
layer in your jar version — the model has evolved; trust the jar over any prose here).

## Key design point: the product is *party-agnostic*

`NonTransferableProduct` / `EconomicTerms` describe **what** is traded, not **who** trades it
or **how much**. Two consequences you must respect:

- **The actual amounts (notional, fixed rate, spread) do NOT live inside the payout.** They
  live once in `Trade.tradeLot[].priceQuantity[]`. The payout side only holds a **reference**
  (an address) pointing back at those values. See [meta-and-references.md](meta-and-references.md).
- **Who pays/receives is a role, not a party.** Each payout carries a `payerReceiver` expressed
  with `CounterpartyRoleEnum` (`PARTY_1` / `PARTY_2`), never an inline Party. The mapping from
  role to the real Party is done once via `Trade.counterparty`.

## What each spine type holds (in words)

- **NonTransferableProduct** — `identifier`, `taxonomy`, and `economicTerms`. For our task the
  load-bearing attribute is `economicTerms`.
- **EconomicTerms** — one or more `payout`, plus product-wide provisions (effective/termination
  dates and their adjustments *may* sit here at the product level; calculation agent; early
  termination / option provisions). For plain rate swaps the proven approach sets the per-leg
  schedule dates inside each `InterestRatePayout.calculationPeriodDates` rather than at this
  level — verify what the expected JSON for your trade actually contains before choosing.
- **Payout** — a *choice* wrapper: exactly one payout-kind is set per entry
  (`interestRatePayout` for a rate leg). One `Payout` entry per economic leg.
- **InterestRatePayout** — the mechanics of one rate leg: `payerReceiver`, `rateSpecification`
  (fixed or floating — see below), `dayCountFraction`, `calculationPeriodDates`, `paymentDates`,
  and (floating only) `resetDates`. Also discounting/compounding and stub handling when present.
- **rateSpecification** — a choice between `fixedRateSpecification` (fixed leg) and
  `floatingRateSpecification` (floating leg). Each holds a **reference** into the tradeLot
  price/observable, not the raw value.
- **TradeLot → PriceQuantity** — the home of real values: a `quantity` (the notional, a
  `NonNegativeQuantitySchedule` with a currency `UnitType`), a `price` (the fixed rate), and an
  `observable` (the floating index). Each value is tagged with a DOCUMENT-scope location label
  so the payouts can address it.
- **Counterparty** — `role` (`CounterpartyRoleEnum`) + `partyReference` (a reference to a Party).
- **Party** — the concrete legal entity: `partyId` (with an id scheme), `name`, and meta.
- **TradeIdentifier** — one per FpML `partyTradeIdentifier`; carries the assigned identifier(s).

## Build order (bottom-up)

Build leaves first, assemble upward: parties → the priceQuantity values (with their location
labels) → each payout (referencing those labels and the party roles) → economicTerms → product
→ tradeLot → assemble the Trade → wrap in TradeState. Building top-down forces you to hold
half-built parents and is where small models lose track.

## The single most useful navigation aid: `cdm/structure-skeleton.json`

`cdm/structure-skeleton.json` is a **full `TradeState` instance with every value set to `null`** —
a complete map of the exact attribute names and nesting for rate swaps and swaptions
(InterestRatePayout, OptionPayout, terminationProvision, exerciseTerms, and the whole
`tradeLot.priceQuantity` with `quantity` / `observable` / `price`). When you need the exact
JSON path of a field — *"where does the floating index go?"*, *"what's the child of
rateSpecification?"* — **grep this file**, don't guess. It is the shape the expected JSON
follows. (It is a shape map, not data: all leaves are `null`.)

## How to verify any claim on this page

1. `grep` the field/type in `cdm/structure-skeleton.json` → its exact path + attribute names.
2. `cdm_lookup name=EconomicTerms` (or any type) → real builder `setX`/`addX` signatures.
3. `grep` the type name in `cdm/rosetta/*.rosetta` → its authoritative attribute list + cardinality.
4. `grep` it in `cdm/hierarchy.txt` → where it sits in the type tree.
Never invent an attribute or method name. If it is not in the jar/skeleton, it does not exist.
