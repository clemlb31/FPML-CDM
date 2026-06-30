# FpML schemas (XSD)

➡️ **Drop the FpML 5.x confirmation XSDs here** (from [fpml.org](https://www.fpml.org/), the version
matching the data — the samples are in FpML-5/confirmation, 5-3 / 5-10 / 5-13 depending on the folders).

Typical root: `fpml-main-5-x.xsd` (or `Confirmation`), which imports the other `.xsd` files of the package.

Validating an FpML (e.g. an `_expected.xml` or an MXML→FpML output):
```bash
java -cp tmp/schema-out io.fpmlcdm.schema.XsdValidateCli \
    --xsd schemas/fpml/fpml-main-5-3.xsd \
    --input data/mxml/OUT_IRD_ASWP_5-3_INS_01/OUT_IRD_ASWP_5-3_INS_01_expected.xml
```

See [../../docs/schemas-and-validation.md](../../docs/schemas-and-validation.md).
