# CDM to FpML Mapping Concepts

This document outlines the conceptual mapping between the FINOS Common Domain Model (CDM) 6.x and FpML 5.x to guide the development of a CDM $\to$ FpML converter.

## 1. High-Level Mapping Architecture

The transformation follows a "Normalization $\to$ Specificity" pattern. 
- **CDM** is a highly normalized, abstract model designed for lifecycle automation. It uses generic components (e.g., `TradeLot`, `PayerReceiver`) to represent many different product types.
- **FpML** is a product-specific, hierarchical XML standard. It uses explicit, often nested, structures for different asset classes (e.g., `<swap>` vs `<fxSwap>`).

| CDM Concept | FpML 5.x Concept | Mapping Strategy |
| :--- | :--- | :--- |
| `TradeState` | `<trade>` | The root of the product-specific subtree. |
| `TradeHeader` / `Party` | `<tradeHeader>` / `<party>` | Map CDM `Party` details and `TradeHeader` (dates, IDs) to FpML. |
| `PayerReceiver` | `<payerPartyReference>` / `<receiverPartyReference>` | Map `payer` $\to$ `payer` and `receiver` $\to$ `receiver`. |
| `Quantity` / `Price` | `<notional>` / `<amount>` | Map CDM `amount` and `currency` to FpML `<notional>` or `<amount>`. |
| `InterestRatePayout` | `<interestLeg>` | Map CDM interest/float legs to FpML interest legs. |
| `Observable` / `Index` | `<floatingRateIndex>` / `<underlyer>` | Map CDM index/instrument identifiers to FpML underlyers/indices. |
| `TradeLot` | (Multiple elements) | A single CDM `TradeLot` might map to multiple FpML elements if it represents multiple legs or complex components. |

## ---------------------------------------------------------

## 2. Key Mapping Challenges

### 2.1 Party Role Assignment
CDM uses abstract roles (e.g., `Payer`, `Receiver`, `HedgingParty`). FpML uses specific role-based references (e.g., `payerPartyReference`, `receiverPartyReference`, `clearinghouse`). 
- **Challenge**: Ensuring the correct CDM `Party` is assigned to the correct FpML reference based on the product's specific business rules.

### 2.2 Normalization vs. Product Specificity
CDM is designed to be "one size fits all" through abstraction. 
- **Challenge**: When converting to FpML, you must "re-specialize" the generic CDM components. A generic `Quantity` in CDM must be transformed into the specific XML structure expected by a `Swap` (e.g., part of an `interestLeg`) or a `FXSwap` (e.g., part of a `notional`).

### 2.3 Identity and Referencing
- **CDM** uses `globalKey`, `externalKey`, and `globalReference` for internal object linking.
- **FpML** uses `id` (for defining elements) and `href` (for referencing them via URI/ID).
- **Strategy**: Maintain a mapping table of `CDM ID` $\to$ `FpML ID` during the transformation process to resolve `href` attributes correctly.

### 2.4 Date and Business Day Conventions
- **CDM** uses `adjustableDate` and `dateAdjustments`.
- **FpML** often uses different nested structures for `businessDayConvention`, `businessCenters`, and `dateRelativeTo`.

## 3. Recommended Transformation Workflow

1.  **Parse CDM JSON** $\to$ Load into a `TradeState` object (already handled by the current project's reverse direction).
2.  **Identify Product Type** $\to$ Inspect `productQualifier` or `taxonomy` in the CDM JSON.
3.  **Initialize FpML DOM** $\to$ Create a new XML `Document`.
4.  **Map Metadata** $\to$ Build `<tradeHeader>` from CDM `tradeDate` and `party` info.
5.  **Map Parties** $\to$ Create `<party>` elements for every unique party in the CDM.
6.  **Dispatch to Product Mapper** $\to$ Use a `CdmToFpmlProductMapper` to build the specific product subtree (e.g., `<swap>`, `<fxSingleLeg>`).
7.  **Resolve References** $\to$ Traverse the DOM to replace CDM `globalReference` with FpML `href="id"`.
8.  **Serialize to XML**.
