# FpML 5.x — Foreign exchange (source side) — STUB

> Status: scaffold. Lean on [../cdm/](../cdm/) + `cdm_lookup` + [../cdm/rosetta/](../cdm/rosetta/),
> and **grep a worked train pair** in `data/train/fx-*/`, then expand this file and
> [../mapping/fx.md](../mapping/fx.md).

## FpML product elements
- `trade/fxSingleLeg` — spot / forward outright.
- `trade/fxSwap` — near + far legs.
- `trade/fxOption` (`fxSimpleOption` / `fxDigitalOption`) — FX option.

## Key sub-structures (by local name)
- `exchangedCurrency1` / `exchangedCurrency2` — `paymentAmount/currency` + `/amount`, payer/receiver refs.
- `valueDate` (spot/forward), `dealtCurrency`, `exchangeRate/rate` + `quotedCurrencyPair`.
- Option: `putCurrencyAmount`, `callCurrencyAmount`, `strike`, `expiryDate`, `premium`.

## CDM target
FX outright/swap → CDM FX payouts (cashflow / `ForeignExchange`-style payouts) under
`economicTerms.payout`; an FX swap is two legs. FX option → `OptionPayout`. Confirm the exact
payout type names with `cdm_lookup` + `cdm/rosetta/` (FX payouts are not in the rates skeleton).

## Known gotchas
- Two currency amounts with opposing payer/receiver — get the direction right.
- The exchange rate carries a `quotedCurrencyPair` (base/quote) — preserve orientation.
