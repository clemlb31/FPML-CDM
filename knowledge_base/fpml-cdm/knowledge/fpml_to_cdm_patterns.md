# Recurring FpML → CDM patterns

Patterns found while mapping 530 pairs covering 10 product families.
These patterns are **transposable** to other source formats (MXML→CDM, etc.).

---

## 1. PARTY_1 vs PARTY_2 — discovered rules

| Product | PARTY_1 = ? |
|---|---|
| IRS vanilla | Payer of the **first swapStream** in document order |
| Swaption | **Buyer** of the option (not payer of the underlying IRS) |
| CDS | **Seller** of protection |
| CDS Option | Buyer of the option (the underlying CDS inherits the party_order of the outer) |
| FX spot/forward | Payer of **exchangedCurrency1** |
| FX swap | Payer of exchangedCurrency1 on the **near leg** |
| Equity return swap | Payer of the **first leg** in document order |
| Commodity swaption | Buyer of the option (the underlying inherits) |

The invariant: **PARTY_1 is PARTY_1 in all payouts of the document**. When an outer mapper (swaption, swaption-on-cds) calls an inner mapper, it sets `partyOrder` then sets `partyOrderLocked = true` in the `MappingContext` to prevent the inner mapper from reassigning.

`counterparty[]` is always emitted in the order `PARTY_1, PARTY_2` (not in FpML document order).

---

## 2. Cross-reference — DOCUMENT-scope addresses

Pattern used everywhere for `priceQuantity`:

**Emitter** (in the payout):
```json
"priceQuantity": {
  "quantitySchedule": {
    "address": {"scope": "DOCUMENT", "value": "quantity-1"}
  }
}
```

**Target** (in `tradeLot[0].priceQuantity[N]`):
```json
"quantity": [{
  "value": {...},
  "meta": {"location": [{"scope": "DOCUMENT", "value": "quantity-1"}]}
}]
```

Our `StreamLabels` allocates sequentially: `quantity-1`, `quantity-2`, ..., `price-1`, `InterestRateIndex-1`, `observable-1`, etc.

Observed convention:
- Floating leg: `quantity-N` + `observable-N` (+ `InterestRateIndex-N` inside)
- Fixed leg: `price-K` + `quantity-N+1`
- When the float has a spread, the spread `price-1` comes before the fixed `price-2`

---

## 3. tradeIdentifier — split into two entries

Pattern for FpML `partyTradeIdentifier` that contain **both** an `<issuer>` and a `<tradeId tradeIdScheme=".../uti">` (or `usi`):

The reference emits **TWO** `tradeIdentifier`:
1. One **without issuer** (bare UTI)
2. One **with** the issuer (or issuerReference)

This is a quirk of the Regnosys post-processing. Our `IdentifierMapper.mapWithSplit` reproduces it.

Order of the two entries:
- If `<issuer>`: without-issuer first, with-issuer next
- If `<partyReference>`: with-issuer first, without-issuer next

**The without-issuer tradeIdentifier violates `IdentifierIssuerChoice` (CDM data rule)** — this is documented in `validation_findings.md`. The reference itself is only partially CDM-valid.

`identifierType` is set **only** when the scheme is canonically UTI/USI (`/unique-transaction-identifier`, `/uti`, `/unique-swap-identifier`, `/usi`). Versioned identifiers (with `<versionedTradeId>`) collapse into a single entry without `identifierType`.

---

## 4. Taxonomy — three canonical entries

When the FpML contains `<primaryAssetClass>`, `<productType>` AND the heuristics allow deriving an ISDA qualifier:

```json
"taxonomy": [
  {"primaryAssetClass": {"value": "InterestRate", "meta": {"scheme": "..."}}},
  {"source": "ISDA", "value": {"name": {"value": "InterestRate:IRSwap:OIS", "meta": {"scheme": "..."}}}},
  {"source": "ISDA", "productQualifier": "InterestRate_IRSwap_FixedFloat_OIS"}
]
```

When only one of the elements is present, we emit just the corresponding entry.

productQualifier — derivation heuristics:
- Standard fixed/float IRS → `InterestRate_IRSwap_FixedFloat`
- IRS with OIS/SOFR/ESTR/SONIA/TONAR/EONIA index in the name → `..._OIS`
- IRS with two floating legs → `InterestRate_IRSwap_BasisSwap` (or `..._BasisSwap_OIS`)
- Cross-currency IRS → `InterestRate_CrossCurrency_FixedFloat` or `..._Basis`
- Inflation swap → `InterestRate_InflationSwap_*`
- CDS index → `CreditDefaultSwap_Index`, single name → `CreditDefaultSwap_SingleName`
- CDS basket → `CreditDefaultSwap_*Tranche`
- Equity option with single-name `<feature><passThrough>` → **suppress** ISDA qualifier
- FX spot/forward → `ForeignExchange_Spot_Forward`
- FX volatility swap → `ForeignExchange_ParameterReturnVolatility`
- FX variance swap → `ForeignExchange_ParameterReturnVariance`

---

## 5. contractDetails.documentation[] — patterns

Mapping FpML `<documentation>` → CDM `ContractDetails.documentation[]`:

| FpML | CDM |
|---|---|
| `<masterAgreement>` | `LegalAgreement` with `agreementType=MasterAgreement`, masterAgreementType enum |
| `<masterAgreementType>ISDA</masterAgreementType>` | enum mapped to `MasterAgreementTypeEnum` (alias "ISDA" → "ISDAMaster") |
| `<masterAgreementVersion>` | `LegalAgreementIdentification.vintage` (int) |
| `<masterConfirmation>` | `LegalAgreement` with `agreementType=MasterConfirmation` (CDS) |
| `<contractualDefinitions>` | `LegalAgreement` with `agreementType=Confirmation`, contractualDefinitionsType[] |
| `<contractualMatrix>` | in the same Confirmation entry, contractualMatrix[] |
| `<contractualTermsSupplement>` | in the same Confirmation entry, contractualTermsSupplement[] |
| `<governingLaw>` | `ContractDetails.governingLaw` (at the parent level) |

All carry `contractualParty[]` = the two party globalReferences in **PARTY_1, PARTY_2 order**.

---

## 6. transferHistory[] — possible sources

CDM element at root `TradeState.transferHistory[]` populated from:

- `<additionalPayment>` (FpML 5.x inside `<swap>`, `<creditDefaultSwap>`)
- `<feeLeg><initialPayment>` or `<feeLeg><singlePayment>` (CDS — `<fixedAmount>` instead of `<paymentAmount>`)
- `<otherPartyPayment>` (at trade level)
- `<additionalPayment>/<additionalPaymentAmount>/<paymentAmount>` (returnSwap)
- `<premium>` (options — uses a `Transfer` with `transferExpression.unscheduledTransfer.priceTransfer = "Upfront"`)

Date:
- `<paymentDate>` may have `<unadjustedDate>+<dateAdjustments>`, `<adjustedDate>`, or be a leaf with the adjusted date as text
- `<adjustablePaymentDate>`/`<adjustedPaymentDate>` (siblings — CDS feeLeg/initialPayment)
- `<paymentDate><relativeDate>` (commodity option premium)
- `<additionalPaymentDate><adjustableDate>` (returnSwap — the reference omits the unadjustedDate in this case)
- `<additionalPaymentDate><relativeDate>` (returnSwap — relative version)

---

## 7. Address-ref labels — numbering convention

When several swapStreams share labels:

- `quantity-N`: one per stream (notional)
- `price-K`: only for the streams that emit one (fixed leg; floating with spread, cap, floor)
- `observable-N` / `InterestRateIndex-N`: one per floating stream

Numbering: we walk the streams in document order. When a stream emits both `quantity` and `price`, the `price` comes before if it is a spread float, otherwise after for fixed legs.

For FX swap: near leg = quantity-1+3, price-1, observable-1; far leg = quantity-2+4, price-2, observable-2.

---

## 8. `meta.externalKey` fields — systematic propagation

The FpML `id="..."` attribute must be propagated to `meta.externalKey` on the CDM side for:

- `Party.meta.externalKey` ← `<party id="party1">`
- `BusinessCenters.meta.externalKey` ← `<businessCenters id="primaryBusinessCenters">`
- `CalculationPeriodDates.meta.externalKey` ← `<calculationPeriodDates id="fixedCalcDates1">`
- `PaymentDates.meta.externalKey` ← `<paymentDates id="paymentDates1">`
- `ResetDates.meta.externalKey` ← `<resetDates id="resetDates2">`
- `TradeDate.meta.externalKey` ← `<tradeDate id="...">` (rare)
- `CashSettlementTerms.meta.externalKey` ← `<cashSettlement id="...">`
- `MandatoryEarlyTerminationDate.externalKey` ← `<mandatoryEarlyTerminationDate id="...">`

This is what allows `externalReference` (on `ReferenceWithMetaParty`, `dateRelativeTo`, `businessCentersReference`, etc.) to resolve during the re-hash.

---

## 9. Quirks observed in the reference

- **The unadjustedDate is omitted** when wrapping an `<additionalPaymentDate><adjustableDate>` in returnSwap — only `dateAdjustments` is kept.
- **stubPeriodType** is serialized as an array `["ShortInitial"]` but the Java model exposes it as a scalar — we unwrap it in pre-processing.
- **assetType/securityType/priceSubType/averagingFeature** in the reference but absent from the 6.19 model — we mask them (see above).
- **Currency scheme** on `<currency currencyScheme="..iso4217">`:
  - **propagated** to `quantity.unit.currency.meta.scheme`
  - **dropped** on `Cash.identifier` (the reference ingester removes it)
- **`<feature><passThrough>`** suppresses the ISDA productQualifier **only** for single-name underliers, **not** for basket (eqd-ex14 vs eqd-ex15).
- **Bond options (`<bondOption>`)**: ISIN scheme → `ProductIdentifier.source = ISIN` (not Other).

---

## 10. General strategy of the custom mapper

Architecture that worked for the 10 families:

```
FpML DOM
  ├─ PartyMapper        → ctx.parties, ctx.partyOrder
  ├─ ProductDetector    → chooses the ProductMapper
  └─ ProductMapper
      ├─ ctx-aware (fix partyOrder if outer trade imposes it)
      ├─ StreamLabels   → DOCUMENT-scope labels
      ├─ Build payouts via PayoutMapper(s) (InterestRatePayout, OptionPayout, ...)
      ├─ Build tradeLot.priceQuantity[]
      ├─ Build counterparty (sort PARTY_1, PARTY_2)
      ├─ TradeIdentifier (split UTI/USI via IdentifierMapper.mapWithSplit)
      ├─ ContractDetailsMapper
      ├─ AccountMapper, CalculationAgentMapper, PartyRoleMapper
      ├─ TaxonomyMapper, ProductIdentifierMapper
      └─ TransferMapper (transferHistory)
```

Effort per new family: ~200-500 lines for the product mapper, with full reuse of `common/*`.

---

## 11. Transposition to a format other than FpML

For MXML → CDM (or other), the architecture is fully reusable by replacing:
- `XmlUtils` (FpML DOM helpers) → MXML DOM helpers
- The XPath in each FpML mapper → equivalent MXML XPath
- The FpML enums (`payerPartyReference/@href`, `currencyScheme`, etc.) → their MXML equivalents

Everything else stays as is:
- `common/PartyMapper`, `DateMapper`, `EnumMappers`, `QuantityMapper`, `TaxonomyMapper`, etc.
- The `Payout*Mapper`s
- `SemanticDiff`, `CdmValidator`, `GlobalKeyReproducer`

This is the architectural promise of the project, validated at 100% on the FpML scope.
