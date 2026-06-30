# CDM schema (JSON)

CDM is **JSON**, not XML — structural validity is not done via XSD.

Two levels, already covered by the project:
1. **Model**: deserialize the JSON into `cdm.event.common.TradeState` via `RosettaObjectMapper`
   (if it parses, it is structurally compliant with the CDM 6.19.0 model). This is what the pipeline does.
2. **Data-rules**: [`io.fpmlcdm.validate.CdmValidator`](../../src/main/java/io/fpmlcdm/validate/CdmValidator.java)
   (CDM business rules via the Guice `CdmRuntimeModule`).

➡️ Optional: drop the **CDM JSON Schema** here (rosetta-dsl can generate it) for a JSON-schema
validation independent of the Java model — this would require a JSON-schema lib (a dependency to add).

See [../../docs/schemas-and-validation.md](../../docs/schemas-and-validation.md).
