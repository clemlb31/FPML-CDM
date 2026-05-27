# FpML → CDM Converter

Convertisseur Java/Maven qui transforme des messages FpML 5.x (XML) en objets FINOS CDM 6.x (JSON).

## Contexte

Ce projet valide la faisabilité d'un **mapper custom** FpML→CDM, sans utiliser les fonctions d'ingestion intégrées au JAR CDM (`cdm.ingest.fpml.*`). L'objectif à terme est de transposer cette approche à d'autres formats source (MXML→CDM).

Le dataset de validation provient du [test pack officiel FINOS CDM](https://github.com/finos/common-domain-model) : 565 paires FpML/CDM couvrant 46 catégories de produits financiers.

## Résultats

**349 / 565 paires** du dataset complet passent en égalité sémantique stricte (61.8%).
Sur le sous-ensemble curaté (catégories `complete` uniquement, hors `-incomplete` et `invalid-products-*`) : **274 / 360 paires (76.1%)**.

Couverture des **10 familles de produits** du dataset (chiffres sur le sous-ensemble curaté) :

| Famille | Produits FpML | Mapper(s) | Paires validées |
|---|---|---|---|
| IRS | swap, swaption, capFloor, fra, bulletPayment | SwapMapper, SwaptionMapper, CapFloorMapper, FraMapper, BulletPaymentMapper | 103 / 133 |
| FX | fxSingleLeg, fxSwap, fxOption | FxSingleLegMapper, FxSwapMapper, FxOptionMapper | 34 / 37 |
| Credit | creditDefaultSwap | CreditDefaultSwapMapper | 54 / 71 |
| Equity | equityOption, returnSwap, equitySwapTransactionSupplement, dividendSwapTransactionSupplement | EquityOptionMapper, ReturnSwapMapper, DividendSwapMapper | 36 / 55 |
| Commodity | commoditySwap, commodityOption, commoditySwaption | CommoditySwapMapper, CommodityOptionMapper | 20 / 29 |
| Variance/Corr/Vol | varianceSwap, correlationSwap, volatilitySwap, varianceOptionTransactionSupplement | VarianceSwapMapper | 14 / 17 |
| Inflation | swap avec inflationRateCalculation | SwapMapper (route inflation legs vers InflationRateSpecification) | 12 / 14 |
| Bond options | bondOption | BondOptionMapper | 1 / 3 |
| Repo | securityLending | SecurityLendingMapper (stub) | 0 / 1 |

## Stack technique

- **Java 17**, Maven
- **CDM 6.19.0** (`org.finos.cdm:cdm-java`) — builders CDM pour construire les objets `TradeState`
- **rosetta-common 11.118.1** (transitif) — `RosettaObjectMapper` pour la sérialisation JSON CDM
- **Jackson 2.17** — diff sémantique JSON
- **JUnit 5** — tests paramétrés sur les 565 paires du dataset
- **picocli** — CLI

Toutes les dépendances sont sur **Maven Central** (pas de repository privé).

## Usage

### Build

```bash
mvn clean package -DskipTests
```

### CLI

```bash
# Convertir un fichier
java -jar target/fpml-cdm.jar --input trade.xml --output /tmp/out

# Convertir un dossier
java -jar target/fpml-cdm.jar --input data/train/rates-5-10/fpml --output /tmp/out

# Valider contre les CDM JSON de référence
java -jar target/fpml-cdm.jar \
    --input data/train/rates-5-10/fpml \
    --validate data/train/rates-5-10 \
    --report-html report.html \
    --report-md report.md \
    --fail-on-mismatch
```

### Tests

```bash
# Catégories "complètes" uniquement (360 paires)
mvn test -Dtest=DataDrivenValidationTest

# Dataset complet incluant incomplete/invalid (565 paires)
mvn test -Dtest=DataDrivenValidationTest -Dincludeincomplete=true
```

## Architecture

```
FpML XML
  → DOM (XmlUtils.parse)
  → ProductDetector.dispatch()    # détecte <swap>, <fxSingleLeg>, <creditDefaultSwap>, etc.
  → ProductMapper.map()           # mapper spécifique par type de produit
  → TradeState                    # objet CDM 6.19.0 construit via les builders
  → RosettaObjectMapper           # sérialisation JSON CDM
  → SemanticDiff.compare()        # validation vs. JSON de référence
```

### Structure du code

```
src/main/java/io/fpmlcdm/
├── Cli.java                           # Point d'entrée CLI (picocli)
├── FpmlToCdmConverter.java            # Orchestre le parsing et le dispatch
├── common/                            # Infrastructure partagée
│   ├── XmlUtils.java                  #   DOM helpers (child, children, pathText, etc.)
│   ├── DateMapper.java                #   Dates, AdjustableDate, BusinessDayAdjustments
│   ├── EnumMappers.java               #   DayCount, Period, BDC, FloatingRateIndex
│   ├── PartyMapper.java               #   <party> → CDM Party (BIC/LEI, personRoles)
│   ├── IdentifierMapper.java          #   <partyTradeIdentifier> → TradeIdentifier (split UTI/USI)
│   ├── QuantityMapper.java            #   tradeLot.priceQuantity[] avec address-refs
│   ├── TaxonomyMapper.java            #   primaryAssetClass + productType + productQualifier
│   ├── ContractDetailsMapper.java     #   masterAgreement, masterConfirmation, contractualDefinitions
│   ├── AccountMapper.java             #   <account> → CDM Account
│   ├── CalculationAgentMapper.java    #   calculationAgent + ancillaryParty
│   ├── PartyRoleMapper.java           #   relatedParty, determiningParty, etc.
│   ├── ProductIdentifierMapper.java   #   <productId>/<productType> → product.identifier[]
│   ├── TransferMapper.java            #   additionalPayment, initialPayment → transferHistory
│   ├── StreamLabels.java              #   allocation des labels quantity-N, price-N, observable-N
│   └── MappingContext.java            #   registre parties + rôles par document
├── detect/
│   └── ProductDetector.java           # Dispatch par élément produit FpML
├── products/                          # Un mapper par type de produit
│   ├── SwapMapper.java                #   IRS vanilla, basis, OIS, cross-currency, inflation
│   ├── SwaptionMapper.java            #   Swaptions (réutilise SwapMapper pour l'underlier)
│   ├── CapFloorMapper.java            #   Cap/Floor/Collar
│   ├── FraMapper.java                 #   Forward Rate Agreement
│   ├── BulletPaymentMapper.java       #   Bullet payments (cashflow-only trades)
│   ├── FxSingleLegMapper.java         #   FX spot, forward, NDF
│   ├── FxSwapMapper.java              #   FX swap (near + far legs)
│   ├── FxOptionMapper.java            #   FX options vanille
│   ├── CreditDefaultSwapMapper.java   #   CDS single-name, index, basket
│   ├── EquityOptionMapper.java        #   Equity options
│   ├── ReturnSwapMapper.java          #   Equity/total return swaps
│   ├── DividendSwapMapper.java        #   Dividend swaps
│   ├── CommoditySwapMapper.java       #   Commodity swaps (fixe, float, basis)
│   ├── CommodityOptionMapper.java     #   Commodity options
│   ├── VarianceSwapMapper.java        #   Variance/correlation/volatility swaps + variance options
│   ├── BondOptionMapper.java          #   Bond options et convertible bond options
│   └── SecurityLendingMapper.java     #   Repo/securities lending (stub)
├── payouts/
│   ├── InterestRatePayoutMapper.java  #   InterestRatePayout (fixe + floating)
│   └── CashflowMapper.java           #   Cashflows additionnels
└── report/
    ├── SemanticDiff.java              #   Diff JSON sémantique (ignore globalKey, etc.)
    └── ReportWriter.java             #   Rapports HTML et Markdown
```

### Normalisation sémantique

Le `SemanticDiff` compare les JSON CDM après normalisation. Les champs suivants sont ignorés car non reproductibles via les builders CDM 6.19.0 (vérifié par `javap`) :

| Champ | Raison |
|---|---|
| `globalKey` | Hash de contenu calculé par l'algorithme Regnosys |
| `globalReference` | Hash du contenu de l'objet référencé |
| `assetType` | Pas de setter sur `AssetBase`/`IndexBase` dans CDM 6.19.0 |
| `securityType` | Pas de setter sur `Security` dans CDM 6.19.0 |
| `priceSubType` | Pas de setter sur `PriceSchedule` dans CDM 6.19.0 |

### Outils de debug

```bash
# Score par catégorie + liste des paires en échec (avec leur diff count)
mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.CategoryReport \
    -Dexec.args="rates-5-10 credit-derivatives-5-13"

# Diff sémantique sur une seule paire (utile pour itérer rapidement)
mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.DiffOne \
    -Dexec.args="rates-5-10 ird-ex01-vanilla-swap"
```

## Ajouter un nouveau type de produit

1. Créer `products/MonProduitMapper.java` implements `ProductMapper`
2. Ajouter le dispatch dans `detect/ProductDetector.java`
3. Réutiliser les utilitaires `common/*` (PartyMapper, DateMapper, IdentifierMapper, etc.)
4. Tester : `mvn exec:java -Dexec.mainClass="io.fpmlcdm.Cli" -Dexec.args="--input mon_fichier.xml --output /tmp/out --validate data/train/ma_categorie --report-md /tmp/r.md"`

## Transposition à MXML→CDM

L'architecture est conçue pour être transposable :
- Remplacer `XmlUtils` et les XPath FpML par des XPath MXML
- Garder les builders CDM identiques (même `TradeState`, `InterestRatePayout`, etc.)
- Garder l'infrastructure commune (PartyMapper, DateMapper, QuantityMapper)
- Adapter le `ProductDetector` aux éléments produit MXML
