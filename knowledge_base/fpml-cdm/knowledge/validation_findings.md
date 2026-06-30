# Validation — two independent signals

The project has two **complementary and divergent** validation methods:

## 1. SemanticDiff (against oracle)
`io.fpmlcdm.report.SemanticDiff` — compares the produced CDM to the reference CDM JSON
of the dataset, after normalization (globalKey, globalReference, assetType… ignored).
- **Metric**: 313/360 (curated), 396/565 (complete).
- **Goal**: faithfully reproduce the output of the official FINOS ingestion.
- **Limitation**: requires an oracle (does not exist for MXML→CDM).

## 2. CdmValidator (intrinsic, oracle-free)
`io.fpmlcdm.validate.CdmValidator` — runs the **CDM data rules** (`RosettaTypeValidator`
wired via Guice `CdmRuntimeModule`) on the produced TradeState. No reference required.
- **Goal**: verify that the output respects the CDM model invariants (cardinalities,
  choices, conditions).
- **Directly transposable to MXML→CDM.**

## Key discovery (2026-05): the reference is not CDM-valid

When running `CdmValidator` on the **reference CDM JSONs themselves** (not our output):

```
=== 8/59 REFERENCE CDM JSONs are CDM-valid (rates-5-10) ===
```

**Only 8 reference files out of 59 pass the CDM data rules.** The dominant
failure is:

```
[DATA_RULE] Identifier.IdentifierIssuerChoice
  -> One and only one field must be set of 'issuerReference', 'issuer'. No fields are set.
```

Cause: the dataset was post-processed to add "UTI-only" `tradeIdentifier`
(without issuer or issuerReference), which violates the `IdentifierIssuerChoice` rule.
Our `IdentifierMapper.mapWithSplit()` faithfully reproduces this behavior — so
it matches the reference (SemanticDiff PASS) while producing CDM that is data-rule-invalid.

## Consequence for MXML→CDM

The two objectives **diverge**:
- Reproduce the reference → keep the quirks (identifiers without issuer).
- Produce valid CDM → *clean* output, which would not match this reference.

For MXML→CDM (no oracle), **CdmValidator** is the right criterion. The
mapper will then have to aim for conformance to the data rules, not reproduction of an
imperfect reference dataset.

## Usage

```bash
# Validate the output of our mapper (reference-free)
mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.validate.ValidateCli -Dexec.args="data/train/rates-5-10/fpml"

# Validate the reference CDM JSONs themselves
mvn -q exec:java -Dexec.mainClass=io.fpmlcdm.validate.ValidateRefCli -Dexec.args="data/train/rates-5-10/cdm"
```
