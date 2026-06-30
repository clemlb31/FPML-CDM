---
name: add-product-mapper
description: Step-by-step recipe to add or extend an FpML→CDM product mapper in this repo. Use when adding support for a new FpML product element, or extending an existing mapper to cover more cases.
---

# Add / extend an FpML→CDM product mapper

Architecture recap: `FpmlToCdmConverter` parses the XML, `detect/ProductDetector` dispatches on the
product element, a `products/XxxMapper` builds the `TradeState`. Reuse everything in `common/`.

## Steps
1. **Identify the FpML element.** Open a sample in `data/train/<category>/fpml/*.xml` and the paired
   reference in `data/train/<category>/cdm/*.json`. The JSON is the target you must reproduce.
2. **Pick/confirm the payout type** for the product (see the `cdm-builder-recipe` skill, §Payout selection).
3. **Create `products/XxxMapper.java`** implementing `ProductMapper` (`TradeState map(Document, Element)`).
   - Reuse `common/`: `PartyMapper`, `DateMapper`, `EnumMappers`, `IdentifierMapper`, `QuantityMapper`,
     `TaxonomyMapper`, `ContractDetailsMapper`, etc. Do **not** re-implement these.
   - Reuse `payouts/InterestRatePayoutMapper` for any rate leg.
   - Build CDM objects with the `cdm-api` tool open — never guess a setter/enum.
4. **Register dispatch** in `detect/ProductDetector.java` (`if (XmlUtils.child(trade,"xxx")!=null) return new XxxMapper();`).
   Order matters: more specific elements first (e.g. `swaption` before `swap`).
5. **Iterate with `diff-pair`** on one failing file until it prints `EQUAL`.
6. **Sweep the family with `category-report`** to catch regressions in neighbouring categories.
7. **Validate on the full dataset with `run-dataset-tests`** (all 3 signals) before declaring done.

## Conventions
- Don't mask diffs by editing `SemanticDiff` unless it's a genuine model/version divergence already
  documented in `cdm_api_quirks.md`.
- Metadata-only products (reference JSON carries only tradeHeader/parties) can reuse
  `BulletPaymentMapper` / `CommodityMetadataOnlyMapper` patterns.
- Keep new code in the same style as the surrounding mapper (comment density, naming).
