# CDM 6.19.0 — wiring runtime (Guice, validation, hashing)

Comment câbler les composants runtime de CDM côté Java.

---

## 1. Maven / dépendances

- `org.finos.cdm:cdm-java:6.19.0` est **sur Maven Central** — pas besoin du repo Regnosys (qui renvoie 403 et est privé).
- `rosetta-common` a déménagé : groupId = **`com.regnosys`** (pas `com.regnosys.rosetta`), version = **11.118.1** (transitif via CDM 6.19.0). Les anciens docs qui parlent de `com.regnosys.rosetta:rosetta-common:9.27.0` sont obsolètes.
- Guice 6.0.0 est requis explicitement pour câbler `org.finos.cdm.CdmRuntimeModule`.

POM minimum :
```xml
<dependency>
    <groupId>org.finos.cdm</groupId>
    <artifactId>cdm-java</artifactId>
    <version>6.19.0</version>
</dependency>
<dependency>
    <groupId>com.google.inject</groupId>
    <artifactId>guice</artifactId>
    <version>6.0.0</version>
</dependency>
```

Le reste (rosetta-common, serialization, rune-runtime, rune-fpml) est tiré transitivement.

---

## 2. Sérialisation JSON

L'`ObjectMapper` à utiliser est **`RosettaObjectMapper`**, pas un Jackson neuf :

```java
ObjectMapper json = com.regnosys.rosetta.common.serialisation.RosettaObjectMapper
    .getNewRosettaObjectMapper()
    .enable(SerializationFeature.INDENT_OUTPUT);
```

Il câble les MixIn qui :
- aliasent `Reference.getReference()` → `value`
- aliasent `ReferenceWithMeta.getReference()` → `address`
- aliasent `MetaFields.getKey()` → `location`
- gèrent le polymorphisme via wrappers (Index/InterestRateIndex/FloatingRateIndex)

Un `ObjectMapper` Jackson standard ne produira **pas** le bon JSON CDM.

---

## 3. Sérialisation XML (si besoin de désérialiser du FpML POJO)

`com.regnosys.rosetta.common.serialisation.xml.RosettaXmlMapper` — préconfiguré pour les types Rosetta/FpML. Les POJOs FpML 5.x sont dans le JAR CDM sous `fpml.consolidated.*`.

(Non utilisé dans ce projet : on parse en DOM et on construit directement les objets CDM.)

---

## 4. Guice — wiring du module CDM

```java
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.finos.cdm.CdmRuntimeModule;

Injector injector = Guice.createInjector(new CdmRuntimeModule());
```

⚠️ `CdmRuntimeModule` dépend transitivement de `com.regnosys.runefpml.RuneFpmlRuntimeModule` — il faut que `com.regnosys.rune-fpml` soit sur le classpath (transitive via CDM, présent automatiquement en Maven).

Une fois l'injector créé, on peut obtenir n'importe quel `*ProcessStep`, `*Validator`, `*Qualifier` :

```java
RosettaTypeValidator validator = injector.getInstance(RosettaTypeValidator.class);
ReferenceConfig refConfig = injector.getInstance(ReferenceConfig.class);
```

---

## 5. Validation par data rules

Le validateur exécute toutes les **conditions** CDM (cardinalités, choice constraints, data rules) sur un objet :

```java
RosettaTypeValidator validator = ...;  // via Guice
ValidationReport report = validator.runProcessStep(TradeState.class, tradeState);
boolean ok = report.success();
List<ValidationResult<?>> failures = report.validationFailures();
```

Chaque `ValidationResult` expose :
- `getValidationType()` — `CARDINALITY`, `DATA_RULE`, `TYPE_FORMAT`, `CHOICE`
- `getModelObjectName()` — ex: `Identifier`
- `getName()` — ex: `IdentifierIssuerChoice`
- `getFailureReason()` — message human-readable
- `getPath()` — chemin Rosetta de l'objet en faute

Wrapper applicable : `io.fpmlcdm.validate.CdmValidator`.

---

## 6. Reproduction des globalKey / globalReference

L'algorithme de hash Regnosys est dans `rosetta-common` :

```java
import com.regnosys.rosetta.common.hashing.GlobalKeyProcessStep;
import com.regnosys.rosetta.common.hashing.NonNullHashCollector;
import com.regnosys.rosetta.common.hashing.ReKeyProcessStep;

GlobalKeyProcessStep keyStep = new GlobalKeyProcessStep(NonNullHashCollector::new);
ReKeyProcessStep reKeyStep = new ReKeyProcessStep(keyStep);

TradeState.TradeStateBuilder b = tradeState.toBuilder();
keyStep.runProcessStep(TradeState.class, (TradeState) b);   // pose les globalKey
reKeyStep.runProcessStep(TradeState.class, (TradeState) b); // remplit les globalReference
TradeState withHashes = b.build();
```

L'algo :
1. Calcule un hash de contenu pour chaque objet `GlobalKey` (ignore les keys/refs internes, prend tout le reste)
2. L'écrit dans `meta.globalKey`
3. Re-walk pour remplir les `globalReference` qui pointent vers ces objets

**Limites observées** :
- Les hashes produits ne matchent **pas** byte-à-byte ceux de la référence dataset (notre contenu diffère légèrement). Mais ils sont **déterministes** et **internally consistent**.
- Utilité principale : vérifier l'intégrité (`globalReference` doit pointer vers un `globalKey` existant — pas de référence orpheline).

Wrapper applicable : `io.fpmlcdm.validate.GlobalKeyReproducer`.

---

## 7. Qualification (pas utilisé dans ce projet)

Pour déterminer le `productQualifier` à partir d'un `TradeState` :

```java
QualifyFunctionFactory factory = injector.getInstance(QualifyFunctionFactory.class);
// ... appeler les fonctions Qualify_* pertinentes
```

Permettrait de vérifier que CDM dérive le même qualifier que la référence, indépendamment du SemanticDiff. Piste pour un 4ème signal de validation.

---

## 8. Fonctions d'ingestion FpML intégrées (non utilisées)

Le JAR CDM contient les fonctions officielles d'ingestion FpML→CDM générées par Rosetta DSL, sous `cdm.ingest.fpml.confirmation.*`. Notamment :

- `cdm.ingest.fpml.confirmation.message.functions.MapDataDocumentToTradeState`
- `cdm.ingest.fpml.confirmation.message.functions.Ingest_FpmlConfirmationToTradeState`

Et 31 fonctions produit-spécifique : `MapSwap*`, `MapCreditDefaultSwap*`, `MapFxSwap*`, `MapEquityOption*`, etc.

Ce sont les fonctions qui ont **généré** les CDM JSON de référence du dataset (vérifié : les FpML inputs sont byte-identiques au test pack officiel `finos/common-domain-model/rosetta-source/.../ingest/input/`, et les outputs sont ≈ les nôtres après un léger post-traitement Regnosys).

Ce projet **n'utilise pas** ces fonctions par choix (mapper custom, en vue d'une transposition MXML→CDM). Mais elles existent et peuvent être câblées via Guice si besoin.

---

## 9. Modules Guice transitifs

`CdmRuntimeModule` installe en cascade :
- `RuneFpmlRuntimeModule` (FpML POJOs et leurs validateurs)
- bindings pour : `ValidatorFactory`, `QualifyFunctionFactory`, `QualificationHandlerProvider`
- bindings pour les fonctions CDM : `CalculationPeriod`, `ResolveAdjustableDate`, `RoundToNearest`, etc.

Liste exhaustive accessible via `javap -p org.finos.cdm.CdmRuntimeModule`.
