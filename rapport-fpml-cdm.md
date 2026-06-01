# Rapport FpML to CDM


## Page 1

Rapport de validation — Convertisseur
FpML → CDM
Généré le 25 mai 2026 — Projet fpml-cdm-converter v0.1.0-SNAPSHOT
313 / 360
86.9% des paires du sous-ensemble curaté passent en égalité sémantique
stricte
396PAIRES VALIDÉES
(DATASET COMPLET
565)
17MAPPERS PRODUIT
10FAMILLES
COUVERTES
~10
000LIGNES DE CODE
JAVA
1. Objectif
Valider la faisabilité d'un mapper custom FpML 5.x → FINOS CDM 6.x en Java, sans utiliser
les fonctions d'ingestion intégrées au JAR CDM (cdm.ingest.fpml.*). Ce proof-of-concept
prépare la transposition vers un convertisseur MXML → CDM.
Le dataset de validation est le test pack officiel FINOS CDM : 565 paires FpML (XML) / CDM
(JSON) couvrant 46 catégories de produits financiers dérivés.
2. Résultats par famille de produits
Famille Produits FpML Mapper(s) Curaté Complet
IRS swap, swaption, capFloor, fra,
bulletPayment
SwapMapper, SwaptionMapper,
CapFloorMapper, FraMapper,
BulletPaymentMapper
115/133 176/199
FX fxSingleLeg, fxSwap, fxOption 34/37 61/73


## Page 2

FxSingleLegMapper,
FxSwapMapper,
FxOptionMapper
Credit creditDefaultSwap,
creditDefaultSwapOption
CreditDefaultSwapMapper,
CreditDefaultSwapOptionMapper57/71 85/105
Equity
equityOption, returnSwap,
equitySwapTransactionSupplement,
dividendSwapTransactionSupplement
EquityOptionMapper,
ReturnSwapMapper,
DividendSwapMapper
36/55 38/72
CommoditycommoditySwap, commodityOptionCommoditySwapMapper,
CommodityOptionMapper 23/29 44/79
Variance/
Corr/Vol
varianceSwap, correlationSwap,
volatilitySwap VarianceSwapMapper 16/17 17/19
Inflation swap (inflationRateCalculation)SwapMapper 12/14 13/15
Bond
options bondOption BondOptionMapper 2/3 4/5
Repo securityLending SecurityLendingMapper (stub)0/1 0/2
TOTAL 313/360
(86.9%)
396/565
(70.1%)
3. Méthodologie de validation
3.1 Égalité sémantique
Chaque paire FpML/CDM est validée par comparaison JSON après normalisation :
Ordre des clés JSON ignoré — comparaison par champ, pas par position
Comparaison numérique — BigDecimal.compareTo (0.025 == 0.0250)
Champs non reproductibles exclus (vérifiés via javap) :
Champ Raison d'exclusion
globalKey Hash de contenu calculé par l'algorithme Regnosys (non documenté
publiquement)
globalReferenceHash du contenu de l'objet référencé
assetType Pas de setter sur AssetBase/ IndexBase dans CDM 6.19.0
securityType Pas de setter sur Security dans CDM 6.19.0
priceSubType Pas de setter sur PriceSchedule dans CDM 6.19.0
•
•
•


## Page 3

3.2 Provenance du dataset
Les fichiers FpML du dataset sont identiques octet-à-octet au test pack officiel du repo finos/
common-domain-model (rosetta-source/src/main/resources/ingest/input/). Les CDM
JSON de référence proviennent de la fonction MapDataDocumentToTradeState intégrée au JAR
CDM 6.19.0, avec un léger post-traitement (ajout d'identifiants UTI supplémentaires sur certains
fichiers).
4. Architecture du convertisseur
FpML XML → DOM (XmlUtils.parse)
→ ProductDetector.dispatch()    // détecte <swap>, <fxSingleLeg>, <creditDefaultSwap>, etc.
→ ProductMapper.map()           // mapper spécifique par type de produit
→ TradeState                    // objet CDM 6.19.0 construit via les builders
→ RosettaObjectMapper           // sérialisation JSON CDM
→ SemanticDiff.compare()        // validation vs. JSON de référence
4.1 Infrastructure partagée (15 utilitaires)
Composant Rôle
PartyMapper Parties (BIC/LEI), personRoles, businessUnits
IdentifierMapper TradeIdentifier avec split UTI/USI
DateMapper AdjustableDate, RelativeDateOffset, BusinessDayAdjustments
EnumMappers DayCount, Period, RollConvention, BDC, FloatingRateIndex
QuantityMapper TradeLot.priceQuantity[] avec address-refs DOCUMENT-scope
TaxonomyMapper primaryAssetClass + productType + productQualifier
ContractDetailsMapper masterAgreement, masterConfirmation, contractualDefinitions,
governingLaw
TerminationProvisionMapperearlyTerminationProvision (mandatory/optional)
TransferMapper additionalPayment, initialPayment → transferHistory
AccountMapper Comptes de trading
CalculationAgentMappercalculationAgent + ancillaryParty
PartyRoleMapper relatedParty, determiningParty, hedgingParty
ProductIdentifierMapperproductId, productType → product.identifier[]
StreamLabels Allocation séquentielle des labels quantity-N, price-N,
observable-N


## Page 4

MappingContext Registre parties + rôles counterparty par document
4.2 Mappers produit (17)
Mapper Produit(s) FpML Payout CDM
SwapMapper swap (vanilla, basis, OIS, xccy,
inflation) InterestRatePayout
SwaptionMapper swaption OptionPayout +
underlier swap
CapFloorMapper capFloor InterestRatePayout
FraMapper fra InterestRatePayout
BulletPaymentMapper bulletPayment Cashflow
transferHistory
FxSingleLegMapper fxSingleLeg (spot, fwd, NDF) SettlementPayout
FxSwapMapper fxSwap (near + far) 2× SettlementPayout
FxOptionMapper fxOption OptionPayout
CreditDefaultSwapMapper creditDefaultSwap (single, index,
basket) CreditDefaultPayout
CreditDefaultSwapOptionMappercreditDefaultSwapOption OptionPayout + CDS
underlier
EquityOptionMapper equityOption, brokerEquityOptionOptionPayout
ReturnSwapMapper returnSwap,
equitySwapTransactionSupplement
PerformancePayout +
InterestRatePayout
DividendSwapMapper dividendSwapTransactionSupplementPerformancePayout +
FixedPricePayout
CommoditySwapMapper commoditySwap CommodityPayout +
FixedPricePayout
CommodityOptionMapper commodityOption, commoditySwaptionOptionPayout
VarianceSwapMapper varianceSwap, correlationSwap,
volatilitySwap PerformancePayout
BondOptionMapper bondOption OptionPayout


## Page 5

5. Gap restant (47 fichiers sur 360)
Cause Fichiers Complexité
Equity short-form interdealer (master confirmation templates)~4 Haute (23-34 diffs)
CDS option structural diffs ~12 Moyenne (8-17 diffs)
Return swap / performance payout ordering ~10 Moyenne
Cross-currency fxNotional / known-amount-schedule~8 Moyenne
Swaptions 5.13 (cashSettlement + cleared) ~5 Moyenne
Commodity exotiques (barrier strip, schedule) ~4 Moyenne
Divers (NDS, CNREPOFIX, repo) ~4 Variable
6. Stack technique
Java 17, Maven 3.9
CDM 6.19.0 (org.finos.cdm:cdm-java) — Maven Central, pas de repo privé
rosetta-common 11.118.1 (transitif) — RosettaObjectMapper pour la sérialisation JSON
Jackson 2.17 — parsing JSON pour le diff sémantique
JUnit 5 — 565 tests paramétrés
picocli 4.7 — CLI avec --input, --output, --validate, --report-html/md
7. Conclusion — Faisabilité MXML → CDM
L'approche mapper custom est validée :
313/360 paires (87%) passent en égalité sémantique stricte, couvrant les 10 familles de
produits du dataset FINOS CDM.
L'architecture est extensible : ajouter un nouveau produit = 1 mapper (~200-400 lignes)
+ 1 entrée dans ProductDetector.
L'infrastructure commune est réutilisable : PartyMapper, DateMapper,
QuantityMapper, etc. sont indépendants du type de produit et du format source.
Le pattern est directement transposable à MXML : remplacer les XPath FpML par des
XPath MXML dans chaque mapper, garder les builders CDM identiques.
Convertisseur FpML→CDM v0.1.0-SNAPSHOT — Java 17 / CDM 6.19.0 / Maven — 25 mai 2026
•
•
•
•
•
•
1.
2.
3.
4.
