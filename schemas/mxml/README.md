# MXML schemas (XSD)

➡️ **Drop the MXML XSDs here** (provided by Murex). Expected entry point: a root `.xsd`
(the `xs:include`/`xs:import` are resolved relative to this file).

Validating an MXML:
```bash
javac -d tmp/schema-out src/main/java/io/fpmlcdm/schema/*.java
java -cp tmp/schema-out io.fpmlcdm.schema.XsdValidateCli \
    --xsd schemas/mxml/<root>.xsd \
    --input data/mxml/OUT_IRD_ASWP_5-3_INS_01/OUT_IRD_ASWP_5-3_INS_01.xml
```

See [../../docs/schemas-and-validation.md](../../docs/schemas-and-validation.md).
