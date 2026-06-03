# CDM 6.19.0 — API quirks et conventions

Notes accumulées sur le modèle Java de CDM 6.19.0 pendant le projet FpML→CDM.
Tout ce qui suit a été vérifié via `javap` sur le JAR `org.finos.cdm:cdm-java:6.19.0`.

---

## 1. Naming d'enums

CDM utilise des conventions inhabituelles qui ne correspondent pas toujours à ce qu'on attendrait depuis FpML.

### DayCountFractionEnum
- `_30E_360` (pas `_30_E_360`)
- `_30E_360_ISDA` (pas `_30_E_360_ISDA`)
- `ACT_365L` (pas `ACT_365_L`)
- `CAL_252` (pas `BUS_252` — c'était l'ancien nom)
- `ACT_365_FIXED` — OK
- `ACT_ACT_ISDA` / `ACT_ACT_ICMA` / `ACT_ACT_AFB` — OK

### RollConventionEnum
Les valeurs numériques sont **préfixées par un underscore** :
- `_14`, `_15`, `_31`, etc.
- `EOM`, `IMM`, `NONE` — sans préfixe

### CounterpartyRoleEnum
- Valeurs Java : `PARTY_1`, `PARTY_2`
- Sérialisé en JSON : `"Party1"`, `"Party2"` (capitale, sans underscore)

### PeriodEnum vs PeriodExtendedEnum
- `PeriodEnum` : D / W / M / Y — utilisé pour `indexTenor`, `paymentFrequency`
- `PeriodExtendedEnum` : ajoute T (Term) — utilisé pour `calculationPeriodFrequency`

### BusinessDayConventionEnum
- `MODFOLLOWING` (pas `MOD_FOLLOWING`)
- `NotApplicable` / `NotEnumerated` en FpML → `NOT_APPLICABLE`

### Énums de jour / type
- `DayTypeEnum` : `CurrencyBusiness` → `CURRENCY_BUSINESS` (regex camelCase → UPPER_SNAKE)
- Utiliser `fromDisplayName(String)` quand disponible avant `valueOf()`

---

## 2. Champs présents en JSON de référence mais absents du modèle Java

Vérifié via `javap` — ces classes/setters **n'existent pas** en CDM 6.19.0 :

| Champ | Classe attendue | Statut |
|---|---|---|
| `assetType` | `AssetBase`, `IndexBase` | pas de setter |
| `securityType` | `Security` | pas de setter |
| `priceSubType` | `PriceSchedule` | pas de setter |
| `averagingFeature` | `cdm.product.template.AveragingFeature` | **classe inexistante** |

Ces champs sont dans le dataset de référence FINOS mais n'ont **pas** d'équivalent dans le modèle Java 6.19. Notre `SemanticDiff.DROPPED_ANYWHERE` les normalise — c'est pas un masquage abusif, c'est documenter la divergence de versions.

L'ingestion CDM officielle (`MapDataDocumentToTradeState`) omet aussi ces champs : c'est confirmé que la référence vient d'un modèle plus ancien.

---

## 3. Class names à chercher quand ce qu'on attend n'existe pas

| On cherche | Où c'est en CDM 6.19.0 |
|---|---|
| `cdm.observable.asset.FloatingRateIndexEnum` | **`cdm.base.staticdata.asset.rates.FloatingRateIndexEnum`** |
| `cdm.observable.asset.FloatingRateIndex` | **`cdm.observable.asset.FloatingRateIndex`** (OK) |
| `FieldWithMetaFloatingRateIndexEnum` | `cdm.base.staticdata.asset.rates.metafields.FieldWithMetaFloatingRateIndexEnum` |
| `ReferenceWithMetaBusinessCenters` | `cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters` (pas `cdm.base.datetime`) |
| `Address` (pour cross-ref) | `com.rosetta.model.lib.meta.Reference` (clé `address` en JSON) |
| `Key` (pour location) | `com.rosetta.model.lib.meta.Key` (clé `location` en JSON via mixin) |
| `Identifier` (trade-level) | `cdm.event.common.TradeIdentifier` étend `cdm.base.staticdata.identifier.Identifier` |
| `PartyName` | n'existe pas — utiliser `FieldWithMetaString` pour `Party.name` |
| `AveragingFeature` | **n'existe pas en 6.19.0** |

---

## 4. Address-refs DOCUMENT-scope (pattern complet)

Les `address` et `meta.location` sont des aliases Jackson posés par des MixIn dans rosetta-common :

```java
// Pour produire {"address": {"scope": "DOCUMENT", "value": "quantity-1"}}
ReferenceWithMetaXxx.builder()
    .setReference(Reference.builder()
        .setScope("DOCUMENT")
        .setReference("quantity-1")
        .build())
    .build()
```

```java
// Pour produire {"meta": {"location": [{"scope": "DOCUMENT", "value": "quantity-1"}]}}
MetaFields.builder()
    .addKey(Key.builder()
        .setScope("DOCUMENT")
        .setKeyValue("quantity-1")
        .build())
    .build()
```

Les MixIn responsables :
- `LegacyReferenceMixIn` — `Reference.getReference()` → JSON `value`
- `ReferenceWithMetaMixIn` — `getReference()` → JSON `address`
- `LegacyGlobalKeyFieldsMixIn` — `MetaFields.getKey()` → JSON `location`

---

## 5. Polymorphisme JSON via wrapper field

CDM utilise des wrappers JSON pour discriminer les choices (pas `@type` Jackson) :

```json
"observable": {
  "value": {
    "Index": {
      "InterestRateIndex": { ... }
    }
  }
}
```

Côté Java, le builder est plat : `setObservable(Observable.builder().setIndex(...))` — le wrapper apparaît à la sérialisation seulement.

**Exception** : `unscheduledTransfer` dans `transferExpression`. Le wrapper n'a pas de pendant Java (le modèle a été aplati). Notre `SemanticDiff.HOIST_WRAPPER` hoist ses enfants pour le diff.

---

## 6. Payout types et leurs cas d'usage

| Payout | Pour |
|---|---|
| `InterestRatePayout` | IRS, FRA, capFloor, swaption underlier |
| `SettlementPayout` | FX spot/forward/NDF, FX swap legs, bullet payment, repo |
| `OptionPayout` | swaption, fxOption, equityOption, bondOption, commodityOption, swap option, variance/dividend option |
| `CreditDefaultPayout` | creditDefaultSwap (single, index, basket), CDS option underlier |
| `PerformancePayout` | returnSwap, variance/correlation/volatility swap, dividend swap, fx variance/volatility |
| `FixedPricePayout` | dividend swap fixed leg, commodity swap fixed leg |
| `CashflowPayout` (non rencontré ici) | — |

---

## 7. Builder API : pièges fréquents

### Toujours utiliser `.builder()` puis `.build()`
Les `XxxBuilder` sont mutables, les `Xxx` (interface) sont immuables.

### Choice types
Pour `Asset.setCash(...)` ou `Observable.setAsset(...)`, **pas** de wrapper intermédiaire — direct.

### MetaFields.addKey vs setMeta(Key)
- `addKey(Key)` ajoute un élément à la liste `location[]`
- `setMeta` n'existe pas sur la plupart des classes — c'est typiquement sur le wrapper `FieldWithMeta*`

### `toBuilder().build()` ≠ identité
Re-construire un objet via toBuilder() **réinjecte** les valeurs, ce qui réordonne parfois les arrays. À utiliser uniquement quand on veut explicitement réinitialiser des sous-objets.

---

## 8. Typos / oddities du modèle

- `notionalReference` dans la référence — **`notionaReference`** dans le modèle Java 6.19.0 (typo, 'l' manquant). Aliasé dans `SemanticDiff.FIELD_ALIASES`.
- `barrier` en JSON de référence vs `knock` dans le modèle (pour knock-in/knock-out). Aliasé.

---

## 9. Champs `meta.externalKey` vs `id` FpML

Le `meta.externalKey` reproduit l'attribut FpML `id="..."` sur les éléments. À propager systématiquement sur :
- `Party.meta.externalKey` (depuis `<party id="party1">`)
- `BusinessCenters.meta.externalKey` (depuis `<businessCenters id="primaryBusinessCenters">`)
- `CalculationPeriodDates.meta.externalKey` (depuis `<calculationPeriodDates id="fixedCalcDates1">`)
- `PaymentDates`, `ResetDates`, `tradeDate` — même pattern

C'est ce qui permet aux `externalReference` (dans `ReferenceWithMetaParty`, `businessCentersReference`, etc.) de résoudre.
