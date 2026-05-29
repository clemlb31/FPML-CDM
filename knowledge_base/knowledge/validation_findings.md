# Validation — deux signaux indépendants

Le projet dispose de deux méthodes de validation **complémentaires et divergentes** :

## 1. SemanticDiff (contre oracle)
`io.fpmlcdm.report.SemanticDiff` — compare le CDM produit au CDM JSON de référence
du dataset, après normalisation (globalKey, globalReference, assetType… ignorés).
- **Métrique** : 313/360 (curaté), 396/565 (complet).
- **But** : reproduire fidèlement la sortie de l'ingestion officielle FINOS.
- **Limite** : nécessite un oracle (n'existe pas pour MXML→CDM).

## 2. CdmValidator (intrinsèque, sans oracle)
`io.fpmlcdm.validate.CdmValidator` — exécute les **data rules CDM** (`RosettaTypeValidator`
câblé via Guice `CdmRuntimeModule`) sur le TradeState produit. Aucune référence requise.
- **But** : vérifier que la sortie respecte les invariants du modèle CDM (cardinalités,
  choix, conditions).
- **Transposable directement à MXML→CDM.**

## Découverte clé (2026-05) : la référence n'est pas CDM-valide

En exécutant `CdmValidator` sur les **CDM JSON de référence eux-mêmes** (pas notre sortie) :

```
=== 8/59 REFERENCE CDM JSONs are CDM-valid (rates-5-10) ===
```

**Seulement 8 fichiers de référence sur 59 passent les data rules CDM.** L'échec
dominant est :

```
[DATA_RULE] Identifier.IdentifierIssuerChoice
  -> One and only one field must be set of 'issuerReference', 'issuer'. No fields are set.
```

Cause : le dataset a été post-traité pour ajouter des `tradeIdentifier` "UTI-only"
(sans issuer ni issuerReference), ce qui viole la règle `IdentifierIssuerChoice`.
Notre `IdentifierMapper.mapWithSplit()` reproduit fidèlement ce comportement — donc
il matche la référence (SemanticDiff PASS) tout en produisant un CDM data-rule-invalide.

## Conséquence pour MXML→CDM

Les deux objectifs **divergent** :
- Reproduire la référence → garder les quirks (identifiants sans issuer).
- Produire du CDM valide → sortie *propre*, qui ne matcherait pas cette référence.

Pour MXML→CDM (pas d'oracle), c'est **CdmValidator** qui est le bon critère. Le
mapper devra alors viser la conformité aux data rules, pas la reproduction d'un
dataset de référence imparfait.

## Usage

```bash
# Valider la sortie de notre mapper (reference-free)
mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.validate.ValidateCli -Dexec.args="data/train/rates-5-10/fpml"

# Valider les CDM JSON de référence eux-mêmes
mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.validate.ValidateRefCli -Dexec.args="data/train/rates-5-10/cdm"
```
