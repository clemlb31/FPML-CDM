# CDM 6.19.0 — runtime wiring (Guice, validation, hashing)

How to wire up the CDM runtime components on the Java side.

---

## 1. Maven / dependencies

- `org.finos.cdm:cdm-java:6.19.0` is **on Maven Central** — no need for the Regnosys repo (which returns 403 and is private).
- `rosetta-common` has moved: groupId = **`com.regnosys`** (not `com.regnosys.rosetta`), version = **11.118.1** (transitive via CDM 6.19.0). The old docs that mention `com.regnosys.rosetta:rosetta-common:9.27.0` are obsolete.
- Guice 6.0.0 is required explicitly to wire up `org.finos.cdm.CdmRuntimeModule`.

Minimum POM:
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

The rest (rosetta-common, serialization, rune-runtime, rune-fpml) is pulled in transitively.

---

## 2. JSON serialization

The `ObjectMapper` to use is **`RosettaObjectMapper`**, not a fresh Jackson one:

```java
ObjectMapper json = com.regnosys.rosetta.common.serialisation.RosettaObjectMapper
    .getNewRosettaObjectMapper()
    .enable(SerializationFeature.INDENT_OUTPUT);
```

It wires up the MixIns that:
- alias `Reference.getReference()` → `value`
- alias `ReferenceWithMeta.getReference()` → `address`
- alias `MetaFields.getKey()` → `location`
- handle polymorphism via wrappers (Index/InterestRateIndex/FloatingRateIndex)

A standard Jackson `ObjectMapper` will **not** produce the correct CDM JSON.

---

## 3. XML serialization (if you need to deserialize FpML POJOs)

`com.regnosys.rosetta.common.serialisation.xml.RosettaXmlMapper` — preconfigured for the Rosetta/FpML types. The FpML 5.x POJOs are in the CDM JAR under `fpml.consolidated.*`.

(Not used in this project: we parse to DOM and build the CDM objects directly.)

---

## 4. Guice — wiring the CDM module

```java
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.finos.cdm.CdmRuntimeModule;

Injector injector = Guice.createInjector(new CdmRuntimeModule());
```

⚠️ `CdmRuntimeModule` depends transitively on `com.regnosys.runefpml.RuneFpmlRuntimeModule` — `com.regnosys.rune-fpml` needs to be on the classpath (transitive via CDM, present automatically with Maven).

Once the injector is created, you can obtain any `*ProcessStep`, `*Validator`, `*Qualifier`:

```java
RosettaTypeValidator validator = injector.getInstance(RosettaTypeValidator.class);
ReferenceConfig refConfig = injector.getInstance(ReferenceConfig.class);
```

---

## 5. Validation by data rules

The validator runs all the CDM **conditions** (cardinalities, choice constraints, data rules) on an object:

```java
RosettaTypeValidator validator = ...;  // via Guice
ValidationReport report = validator.runProcessStep(TradeState.class, tradeState);
boolean ok = report.success();
List<ValidationResult<?>> failures = report.validationFailures();
```

Each `ValidationResult` exposes:
- `getValidationType()` — `CARDINALITY`, `DATA_RULE`, `TYPE_FORMAT`, `CHOICE`
- `getModelObjectName()` — e.g. `Identifier`
- `getName()` — e.g. `IdentifierIssuerChoice`
- `getFailureReason()` — human-readable message
- `getPath()` — Rosetta path of the offending object

Applicable wrapper: `io.fpmlcdm.validate.CdmValidator`.

---

## 6. Reproducing globalKey / globalReference

The Regnosys hashing algorithm is in `rosetta-common`:

```java
import com.regnosys.rosetta.common.hashing.GlobalKeyProcessStep;
import com.regnosys.rosetta.common.hashing.NonNullHashCollector;
import com.regnosys.rosetta.common.hashing.ReKeyProcessStep;

GlobalKeyProcessStep keyStep = new GlobalKeyProcessStep(NonNullHashCollector::new);
ReKeyProcessStep reKeyStep = new ReKeyProcessStep(keyStep);

TradeState.TradeStateBuilder b = tradeState.toBuilder();
keyStep.runProcessStep(TradeState.class, (TradeState) b);   // sets the globalKey
reKeyStep.runProcessStep(TradeState.class, (TradeState) b); // fills in the globalReference
TradeState withHashes = b.build();
```

The algorithm:
1. Computes a content hash for each `GlobalKey` object (ignores internal keys/refs, takes everything else)
2. Writes it into `meta.globalKey`
3. Re-walks to fill in the `globalReference` that point to those objects

**Observed limitations**:
- The hashes produced do **not** match byte-for-byte those of the reference dataset (our content differs slightly). But they are **deterministic** and **internally consistent**.
- Main usefulness: checking integrity (`globalReference` must point to an existing `globalKey` — no orphan reference).

Applicable wrapper: `io.fpmlcdm.validate.GlobalKeyReproducer`.

---

## 7. Qualification (not used in this project)

To determine the `productQualifier` from a `TradeState`:

```java
QualifyFunctionFactory factory = injector.getInstance(QualifyFunctionFactory.class);
// ... call the relevant Qualify_* functions
```

Would allow checking that CDM derives the same qualifier as the reference, independently of the SemanticDiff. A lead for a 4th validation signal.

---

## 8. Built-in FpML ingestion functions (not used)

The CDM JAR contains the official FpML→CDM ingestion functions generated by the Rosetta DSL, under `cdm.ingest.fpml.confirmation.*`. Notably:

- `cdm.ingest.fpml.confirmation.message.functions.MapDataDocumentToTradeState`
- `cdm.ingest.fpml.confirmation.message.functions.Ingest_FpmlConfirmationToTradeState`

And 31 product-specific functions: `MapSwap*`, `MapCreditDefaultSwap*`, `MapFxSwap*`, `MapEquityOption*`, etc.

These are the functions that **generated** the reference CDM JSON in the dataset (verified: the FpML inputs are byte-identical to the official test pack `finos/common-domain-model/rosetta-source/.../ingest/input/`, and the outputs are ≈ ours after a slight Regnosys post-processing).

This project **does not use** these functions by choice (custom mapper, with a view to an MXML→CDM transposition). But they exist and can be wired up via Guice if needed.

---

## 9. Transitive Guice modules

`CdmRuntimeModule` installs, in cascade:
- `RuneFpmlRuntimeModule` (FpML POJOs and their validators)
- bindings for: `ValidatorFactory`, `QualifyFunctionFactory`, `QualificationHandlerProvider`
- bindings for the CDM functions: `CalculationPeriod`, `ResolveAdjustableDate`, `RoundToNearest`, etc.

Exhaustive list accessible via `javap -p org.finos.cdm.CdmRuntimeModule`.
