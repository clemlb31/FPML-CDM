# Patterns récurrents FpML → CDM

Patterns trouvés en mappant 530 paires couvrant 10 familles de produits.
Ces patterns sont **transposables** à d'autres formats source (MXML→CDM, etc.).

---

## 1. PARTY_1 vs PARTY_2 — règles découvertes

| Produit | PARTY_1 = ? |
|---|---|
| IRS vanilla | Payer du **premier swapStream** en ordre document |
| Swaption | **Buyer** de l'option (pas payer de l'IRS sous-jacent) |
| CDS | **Seller** of protection |
| CDS Option | Buyer de l'option (l'underlying CDS hérite du party_order de l'outer) |
| FX spot/forward | Payer de **exchangedCurrency1** |
| FX swap | Payer de exchangedCurrency1 sur la **near leg** |
| Equity return swap | Payer de la **première leg** en ordre document |
| Commodity swaption | Buyer de l'option (l'underlying hérite) |

L'invariant : **PARTY_1 est PARTY_1 dans tous les payouts du document**. Quand un mapper outer (swaption, swaption-on-cds) appelle un mapper inner, il pose `partyOrder` puis met `partyOrderLocked = true` dans le `MappingContext` pour empêcher le mapper inner de réassigner.

`counterparty[]` est toujours émis dans l'ordre `PARTY_1, PARTY_2` (pas dans l'ordre document FpML).

---

## 2. Cross-référence — DOCUMENT-scope addresses

Pattern utilisé partout pour les `priceQuantity` :

**Émetteur** (dans le payout) :
```json
"priceQuantity": {
  "quantitySchedule": {
    "address": {"scope": "DOCUMENT", "value": "quantity-1"}
  }
}
```

**Cible** (dans `tradeLot[0].priceQuantity[N]`) :
```json
"quantity": [{
  "value": {...},
  "meta": {"location": [{"scope": "DOCUMENT", "value": "quantity-1"}]}
}]
```

Notre `StreamLabels` alloue séquentiellement : `quantity-1`, `quantity-2`, ..., `price-1`, `InterestRateIndex-1`, `observable-1`, etc.

Convention observée :
- Floating leg : `quantity-N` + `observable-N` (+ `InterestRateIndex-N` à l'intérieur)
- Fixed leg : `price-K` + `quantity-N+1`
- Quand le float a un spread, le spread `price-1` vient avant le fixed `price-2`

---

## 3. tradeIdentifier — split en deux entrées

Pattern pour les `partyTradeIdentifier` FpML qui contiennent **à la fois** un `<issuer>` et un `<tradeId tradeIdScheme=".../uti">` (ou `usi`) :

La référence émet **DEUX** `tradeIdentifier` :
1. Une **sans issuer** (UTI nu)
2. Une **avec** l'issuer (ou issuerReference)

C'est un quirk du post-traitement Regnosys. Notre `IdentifierMapper.mapWithSplit` reproduit.

Ordre des deux entrées :
- Si `<issuer>` : sans-issuer d'abord, avec-issuer ensuite
- Si `<partyReference>` : avec-issuer d'abord, sans-issuer ensuite

**Le tradeIdentifier sans-issuer viole `IdentifierIssuerChoice` (data rule CDM)** — c'est documenté dans `validation_findings.md`. La référence elle-même n'est que partiellement CDM-valide.

`identifierType` n'est posé **que** quand le scheme est canoniquement UTI/USI (`/unique-transaction-identifier`, `/uti`, `/unique-swap-identifier`, `/usi`). Versioned identifiers (avec `<versionedTradeId>`) collapse en une seule entrée sans `identifierType`.

---

## 4. Taxonomy — trois entrées canoniques

Quand le FpML contient `<primaryAssetClass>`, `<productType>` ET les heuristiques permettent de dériver un qualifier ISDA :

```json
"taxonomy": [
  {"primaryAssetClass": {"value": "InterestRate", "meta": {"scheme": "..."}}},
  {"source": "ISDA", "value": {"name": {"value": "InterestRate:IRSwap:OIS", "meta": {"scheme": "..."}}}},
  {"source": "ISDA", "productQualifier": "InterestRate_IRSwap_FixedFloat_OIS"}
]
```

Quand un seul des éléments est présent, on émet juste l'entrée correspondante.

Productuqalifier — heuristiques de dérivation :
- IRS fixed/float standard → `InterestRate_IRSwap_FixedFloat`
- IRS avec index OIS/SOFR/ESTR/SONIA/TONAR/EONIA dans le nom → `..._OIS`
- IRS deux floating legs → `InterestRate_IRSwap_BasisSwap` (ou `..._BasisSwap_OIS`)
- IRS cross-currency → `InterestRate_CrossCurrency_FixedFloat` ou `..._Basis`
- Inflation swap → `InterestRate_InflationSwap_*`
- CDS index → `CreditDefaultSwap_Index`, single name → `CreditDefaultSwap_SingleName`
- CDS basket → `CreditDefaultSwap_*Tranche`
- Equity option avec `<feature><passThrough>` single-name → **suppress** ISDA qualifier
- FX spot/forward → `ForeignExchange_Spot_Forward`
- FX volatility swap → `ForeignExchange_ParameterReturnVolatility`
- FX variance swap → `ForeignExchange_ParameterReturnVariance`

---

## 5. contractDetails.documentation[] — patterns

Mapping `<documentation>` FpML → `ContractDetails.documentation[]` CDM :

| FpML | CDM |
|---|---|
| `<masterAgreement>` | `LegalAgreement` avec `agreementType=MasterAgreement`, masterAgreementType enum |
| `<masterAgreementType>ISDA</masterAgreementType>` | enum mappé à `MasterAgreementTypeEnum` (alias "ISDA" → "ISDAMaster") |
| `<masterAgreementVersion>` | `LegalAgreementIdentification.vintage` (int) |
| `<masterConfirmation>` | `LegalAgreement` avec `agreementType=MasterConfirmation` (CDS) |
| `<contractualDefinitions>` | `LegalAgreement` avec `agreementType=Confirmation`, contractualDefinitionsType[] |
| `<contractualMatrix>` | dans la même entrée Confirmation, contractualMatrix[] |
| `<contractualTermsSupplement>` | dans la même entrée Confirmation, contractualTermsSupplement[] |
| `<governingLaw>` | `ContractDetails.governingLaw` (au niveau parent) |

Tous portent `contractualParty[]` = les deux party globalReferences en **ordre PARTY_1, PARTY_2**.

---

## 6. transferHistory[] — sources possibles

Élément CDM en racine `TradeState.transferHistory[]` peuplé depuis :

- `<additionalPayment>` (FpML 5.x inside `<swap>`, `<creditDefaultSwap>`)
- `<feeLeg><initialPayment>` ou `<feeLeg><singlePayment>` (CDS — `<fixedAmount>` au lieu de `<paymentAmount>`)
- `<otherPartyPayment>` (au niveau trade)
- `<additionalPayment>/<additionalPaymentAmount>/<paymentAmount>` (returnSwap)
- `<premium>` (options — utilise un `Transfer` avec `transferExpression.unscheduledTransfer.priceTransfer = "Upfront"`)

Date :
- `<paymentDate>` peut avoir `<unadjustedDate>+<dateAdjustments>`, `<adjustedDate>`, ou être un leaf avec la date adjustée en texte
- `<adjustablePaymentDate>`/`<adjustedPaymentDate>` (siblings — CDS feeLeg/initialPayment)
- `<paymentDate><relativeDate>` (commodity option premium)
- `<additionalPaymentDate><adjustableDate>` (returnSwap — la référence omet l'unadjustedDate dans ce cas)
- `<additionalPaymentDate><relativeDate>` (returnSwap — version relative)

---

## 7. Address-ref labels — convention de numérotation

Quand plusieurs swapStreams partagent les labels :

- `quantity-N` : un par stream (notionel)
- `price-K` : seulement pour les streams qui en émettent un (fixed leg ; floating avec spread, cap, floor)
- `observable-N` / `InterestRateIndex-N` : un par stream floating

Numérotation : on parcourt les streams en ordre document. Quand un stream émet `quantity` et `price` les deux, le `price` vient avant si c'est un spread float, sinon après pour les fixed legs.

Pour FX swap : near leg = quantity-1+3, price-1, observable-1 ; far leg = quantity-2+4, price-2, observable-2.

---

## 8. Champs `meta.externalKey` — propagation systématique

L'attribut `id="..."` en FpML doit être propagé vers `meta.externalKey` côté CDM pour :

- `Party.meta.externalKey` ← `<party id="party1">`
- `BusinessCenters.meta.externalKey` ← `<businessCenters id="primaryBusinessCenters">`
- `CalculationPeriodDates.meta.externalKey` ← `<calculationPeriodDates id="fixedCalcDates1">`
- `PaymentDates.meta.externalKey` ← `<paymentDates id="paymentDates1">`
- `ResetDates.meta.externalKey` ← `<resetDates id="resetDates2">`
- `TradeDate.meta.externalKey` ← `<tradeDate id="...">` (rare)
- `CashSettlementTerms.meta.externalKey` ← `<cashSettlement id="...">`
- `MandatoryEarlyTerminationDate.externalKey` ← `<mandatoryEarlyTerminationDate id="...">`

C'est ce qui permet aux `externalReference` (sur `ReferenceWithMetaParty`, `dateRelativeTo`, `businessCentersReference`, etc.) de résoudre lors du re-hash.

---

## 9. Quirks observés dans la référence

- **L'unadjustedDate est omis** quand on emballe un `<additionalPaymentDate><adjustableDate>` dans returnSwap — seul `dateAdjustments` est gardé.
- **stubPeriodType** est sérialisé en array `["ShortInitial"]` mais le modèle Java l'expose en scalaire — on unwrap en pré-traitement.
- **assetType/securityType/priceSubType/averagingFeature** dans la référence mais absent du modèle 6.19 — on masque (vu plus haut).
- **Currency scheme** sur `<currency currencyScheme="..iso4217">` :
  - **propagé** sur `quantity.unit.currency.meta.scheme`
  - **droppé** sur `Cash.identifier` (l'ingester de référence l'enlève)
- **`<feature><passThrough>`** suppress l'ISDA productQualifier **seulement** pour single-name underliers, **pas** pour basket (eqd-ex14 vs eqd-ex15).
- **Bond options (`<bondOption>`)** : ISIN scheme → `ProductIdentifier.source = ISIN` (pas Other).

---

## 10. Stratégie générale du mapper custom

Architecture qui a marché pour les 10 familles :

```
FpML DOM
  ├─ PartyMapper        → ctx.parties, ctx.partyOrder
  ├─ ProductDetector    → choisit le ProductMapper
  └─ ProductMapper
      ├─ ctx-aware (fix partyOrder si outer trade impose)
      ├─ StreamLabels   → labels DOCUMENT-scope
      ├─ Build payouts via PayoutMapper(s) (InterestRatePayout, OptionPayout, ...)
      ├─ Build tradeLot.priceQuantity[]
      ├─ Build counterparty (sort PARTY_1, PARTY_2)
      ├─ TradeIdentifier (split UTI/USI via IdentifierMapper.mapWithSplit)
      ├─ ContractDetailsMapper
      ├─ AccountMapper, CalculationAgentMapper, PartyRoleMapper
      ├─ TaxonomyMapper, ProductIdentifierMapper
      └─ TransferMapper (transferHistory)
```

Effort par nouvelle famille : ~200-500 lignes pour le mapper produit, réutilisation totale du `common/*`.

---

## 11. Transposition à un format autre que FpML

Pour MXML → CDM (ou autre), l'architecture est intégralement réutilisable en remplaçant :
- `XmlUtils` (DOM helpers FpML) → DOM helpers MXML
- Les XPath dans chaque mapper FpML → XPath MXML équivalents
- Les enums FpML (`payerPartyReference/@href`, `currencyScheme`, etc.) → leurs équivalents MXML

Tout le reste reste tel quel :
- `common/PartyMapper`, `DateMapper`, `EnumMappers`, `QuantityMapper`, `TaxonomyMapper`, etc.
- Les `Payout*Mapper`s
- `SemanticDiff`, `CdmValidator`, `GlobalKeyReproducer`

C'est la promesse architecturale du projet, validée à 100% sur le périmètre FpML.
