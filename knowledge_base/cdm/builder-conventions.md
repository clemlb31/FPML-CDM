# CDM 6.x generated-Java builder conventions

How the generated CDM Java API behaves, **in words**. This explains the *shape* of the API so
you stop hallucinating method names. It deliberately contains **no code**: the exact method
signatures come from `cdm_lookup name=<Type>` (real `javap` output from the jar). Never copy a
builder call from memory — look it up.

## 1. Everything is built, nothing is constructed

Every CDM type is immutable. You never call a constructor and you never set a field on a built
object. You obtain a **builder** for the type, set/add attributes on it, then **build** it to get
the immutable instance. To change an already-built object, derive a fresh builder from it
(a "to-builder" operation), change it, and build again. If you reach for `new SomeType(...)`,
stop — it does not exist.

## 2. `set` for one value, `add` for a list element

- A **single-valued** attribute has a *set*-style method named after the attribute. Calling it
  again replaces the value.
- A **multi-valued** attribute (a list, cardinality `0..*` / `1..*`) has an *add*-style method
  that appends **one** element. To add three elements, call it three times. Some builders also
  expose a bulk setter that takes a whole list — but do **not** assume one exists; check the
  signature with `cdm_lookup`. The default mental model is "loop and add one at a time".
- To know whether an attribute is single or multi, grep it in `cdm/rosetta/*.rosetta` (the
  cardinality is written there, e.g. `payout Payout (1..*)`).

## 3. Builder types are nested inside their owning type

The builder for a type is a nested type of it (e.g. the builder of `EconomicTerms` is its nested
`Builder`). When you accumulate a child across a loop (e.g. adding business centers one by one),
hold a reference of that nested builder type rather than the built type. Many builders also offer
a *get-or-create* accessor that lazily returns the child's builder so you can populate it in
place — handy for deep nesting. Confirm exact names with `cdm_lookup`.

## 4. Scalars are almost always wrapped — this is the #1 surprise

A plain `String`, enum, or date is rarely accepted directly by a CDM builder. It is wrapped so it
can also carry metadata (a scheme, a key, a reference). The two wrapper families are described in
[meta-and-references.md](meta-and-references.md): `FieldWithMetaXxx` (a value + optional
scheme/key) and `ReferenceWithMetaXxx` (a pointer to another object or an address). If the
compiler says *"incompatible types: String cannot be converted to FieldWithMetaString"*, you
passed a raw scalar where a wrapper was expected — wrap it. The skeleton
`cdm/structure-skeleton.json` shows exactly which fields are wrapped (look for a nested
`{ "value": null, "meta": {...} }` shape).

## 5. Enums are obtained by name, sometimes mangled

You get an enum constant by its name. Some FpML strings do not map to the constant verbatim —
there are mangling rules (leading-digit underscore, separator-to-underscore, case-to-snake). See
[enums.md](enums.md). When unsure of a constant, `cdm_lookup name=<SomeEnum>` lists the real ones.

## 6. The discipline that keeps you compiling

- Before writing a builder call for a type you are unsure about: `cdm_lookup name=<Type>`.
- To find what a container attribute is called or whether it is a list: grep `cdm/rosetta/`.
- To find the exact JSON path / nesting of a field: grep `cdm/structure-skeleton.json`.
- On a "cannot find symbol" for a method: the type does not have that method — look it up, do not
  invent a near-synonym. On one for an enum constant: the constant is spelled differently — list
  the enum. See [pitfalls.md](pitfalls.md) for the full "when it doesn't compile" protocol.
