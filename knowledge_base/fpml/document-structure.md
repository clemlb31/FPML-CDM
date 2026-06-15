# FpML 5.x document structure (the source side)

The shape every FpML 5.x confirmation/recordkeeping document shares, independent of product.
Read this first, then the per-family file (e.g. [rates.md](rates.md)) for the product element.
This is prose — the agent reads/greps the actual XML; no parsing code is given here.

> Source: FpML 5.x specification, confirmation view — https://www.fpml.org/spec/ .

## Root and top-level layout

- The document root is `<dataDocument>` (sometimes `<FpML>` or a message wrapper). The trade lives
  at `dataDocument/trade`.
- **Parties live at the document root**, as siblings of `trade` (`dataDocument/party`), **not**
  inside `<trade>`. Each `<party>` has an `@id` attribute (e.g. `id="party1"`) — that id is the
  anchor that everything else points at via `@href`.
- A `<trade>` contains a `<tradeHeader>` (ids, dates) and exactly one **product element** that
  names the asset class (`<swap>`, `<fra>`, `<swaption>`, `<creditDefaultSwap>`, `<fxSingleLeg>`,
  `<equityOption>`, …). The product element is what the per-family file describes.

## Namespaces — match by local name

FpML elements are namespace-qualified (e.g. `http://www.fpml.org/FpML-5/confirmation`). Do **not**
hard-code namespace prefixes. **Match elements by local name only** (ignore the namespace). This
keeps the converter robust across FpML minor versions and message wrappers. (In the generated
Java this is the "elements by local name, namespace-wildcard" approach — implement it once; this
KB does not provide the helper, by design.)

## References inside the document (`@href` → `@id`)

FpML cross-references by attribute: a `…PartyReference/@href="party1"` points at the
`<party id="party1">`. The same href string is what becomes the CDM `externalReference` (see
[../cdm/meta-and-references.md](../cdm/meta-and-references.md)). Resolve party roles by document
order: the first party id → `PARTY_1`, the other → `PARTY_2`.

## What's reliably present vs conditional

- **tradeHeader**: `tradeDate`, and one `partyTradeIdentifier` per party (each with a
  `partyReference/@href` and a `tradeId`).
- **parties**: `partyId` (often with an LEI scheme like `iso17442`) and `partyName`.
- Product-specific blocks (rates, dates, notionals, rate calculations) are **conditional** on the
  product and the leg type. The per-family file lists which elements appear when.

## Practical reading tips for the agent

- `read_file` the FpML **once**; if it's large, `grep` for the elements you need by local name.
- The expected CDM JSON is the contract — when an FpML element's destination is unclear, grep the
  same concept in `../cdm/structure-skeleton.json` to see where CDM expects it.
- Only set a CDM field when its FpML source element is actually present (absent element → leave the
  field unset, don't pass null — see [../cdm/pitfalls.md](../cdm/pitfalls.md)).
