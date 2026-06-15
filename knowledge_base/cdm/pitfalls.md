# CDM 6.x compile/runtime pitfalls + the "when it won't compile" protocol

The recurring traps that burn iterations, in words, plus the exact loop to follow when the
compiler complains. The meta-rule: **verify against the jar (`cdm_lookup`), the model
(`cdm/rosetta/`) and the shape (`cdm/structure-skeleton.json`) — never guess twice.**

## The recurring traps

1. **Wrong period enum.** `PeriodEnum` vs `PeriodExtendedEnum` are not interchangeable — a
   frequency needs the *extended* one. See [enums.md](enums.md). Symptom: "incompatible types"
   or "cannot find symbol" on a `setPeriod`.
2. **Forgot the meta wrapper.** Passing a raw `String`/enum/`Date` where a `FieldWithMetaXxx` is
   expected. Symptom: *"incompatible types: String cannot be converted to FieldWithMetaString"*.
   Wrap it — see [meta-and-references.md](meta-and-references.md).
3. **Leading-digit enum constant.** `30/360` is `_30_360`, not `30_360`; roll `15` is `_15`.
4. **CDM 5.x types that no longer exist in 6.x.** The biggest: `ContractualProduct` →
   **`NonTransferableProduct`**. Any `setX` you remember from a 5.x example may be gone. Do not
   port 5.x snippets; check 6.x with `cdm_lookup`.
5. **Invented method/enum name.** "cannot find symbol – method setFoo" means the type has no
   `setFoo`. Look up the real one; do not substitute a plausible synonym.
6. **`set` vs `add` confusion.** Single-valued attribute → `set…`; list attribute → `add…` once
   per element. Grep `cdm/rosetta/` for the cardinality.
7. **address ↔ location mismatch.** A payout `address` whose `{scope, value}` doesn't match a
   `tradeLot` `location` is a *silent* broken reference — no compile error, but MISSING/EXTRA in
   the run_test diff. Keep labels identical (`DOCUMENT` + `quantity-1`, etc.).
8. **Per-leg vs product-level dates.** Put calculation/payment/reset dates inside each
   `InterestRatePayout.calculationPeriodDates`, not on `EconomicTerms` (for rate swaps).
9. **Runtime NullPointerException (compiles, then crashes → score 0).** You passed `null` into a
   builder where the FpML element was absent. Guard the null or only set the field when the source
   element exists. The crash feedback names the exact `method (file:line)` — fix THAT line first,
   before chasing field diffs.
10. **Whole-file rewrite to fix one error.** Don't. Use `edit_file` on the exact line. Rewriting
    regresses a compiling state (observed: 10→49 errors editing blind).

## When it won't compile — do this, in order

1. **Read the exact `file:line` and message** from `compile_project`. Fix one error at a time.
2. **"cannot find symbol – method"** → `cdm_lookup name=<OwningType>` to get the real
   `set*`/`add*` signatures; pick the right one.
3. **"cannot find symbol – variable" on an enum** → `cdm_lookup name=<Enum>` to list the real
   constants; the spelling is mangled (see [enums.md](enums.md)).
4. **"incompatible types: String → FieldWithMetaX"** → wrap the scalar (meta wrapper).
5. **"incompatible types" on a period** → wrong period enum; switch `PeriodEnum` ↔
   `PeriodExtendedEnum`.
6. Still stuck on a *type's* shape → grep it in `cdm/rosetta/*.rosetta` (attributes + cardinality)
   and in `cdm/structure-skeleton.json` (exact JSON path). Only use `internet_search` as a last
   resort, after the jar and the model files.

Re-compile after each fix. A clean compile is progress — lock it in with `run_test` before
editing further (don't keep "improving" and break the build).
