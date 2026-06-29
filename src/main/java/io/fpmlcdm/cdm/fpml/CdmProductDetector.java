package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import cdm.product.asset.CreditDefaultPayout;
import cdm.product.asset.InterestRatePayout;
import cdm.product.template.EconomicTerms;
import cdm.product.template.Payout;
import io.fpmlcdm.cdm.fpml.products.BondOptionMapper;
import io.fpmlcdm.cdm.fpml.products.CapFloorMapper;
import io.fpmlcdm.cdm.fpml.products.CommodityOptionMapper;
import io.fpmlcdm.cdm.fpml.products.CommoditySwapMapper;
import io.fpmlcdm.cdm.fpml.products.CreditDefaultSwapMapper;
import io.fpmlcdm.cdm.fpml.products.DividendSwapMapper;
import io.fpmlcdm.cdm.fpml.products.DividendSwapOptionMapper;
import io.fpmlcdm.cdm.fpml.products.EquityOptionMapper;
import io.fpmlcdm.cdm.fpml.products.EquitySwapMapper;
import io.fpmlcdm.cdm.fpml.products.FraMapper;
import io.fpmlcdm.cdm.fpml.products.FxOptionMapper;
import io.fpmlcdm.cdm.fpml.products.FxSingleLegMapper;
import io.fpmlcdm.cdm.fpml.products.FxSwapMapper;
import io.fpmlcdm.cdm.fpml.products.InterestRateSwapMapper;
import io.fpmlcdm.cdm.fpml.products.SwaptionMapper;
import io.fpmlcdm.cdm.fpml.products.BulletPaymentMapper;

import java.util.List;
import java.util.Optional;

/**
 * Detects the product type from a CDM {@link TradeState} to select the appropriate mapper.
 */
public class CdmProductDetector {

    public Optional<CdmToFpmlProductMapper> detect(TradeState tradeState) {
        if (tradeState == null) return Optional.empty();

        try {
            // Get economic terms from: TradeState -> getTrade() -> getProduct() -> getEconomicTerms()
            Object trade = invokeField(tradeState, "getTrade");
            if (trade == null) return Optional.empty();

            Object product = invokeField(trade, "getProduct");
            if (product == null) return Optional.empty();

            EconomicTerms econTerms = (EconomicTerms) invokeField(product, "getEconomicTerms");
            if (econTerms == null) return Optional.empty();

            List<? extends Payout> payouts = econTerms.getPayout();
            if (payouts == null || payouts.isEmpty()) return Optional.empty();

            // Inspect each payout to determine product type
            for (Payout p : payouts) {
                InterestRatePayout irPayout = p.getInterestRatePayout();
                CreditDefaultPayout cdPayout = p.getCreditDefaultPayout();

                if (irPayout != null) {
                    // Check if it's a swaption by looking for exercise-related fields in the payout context
                    if (isSwaption(irPayout, econTerms)) {
                        return Optional.of(new SwaptionMapper());
                    }

                    // Check if it's a cap/floor/collar by looking for capRateSchedule or floorRateSchedule
                    if (isCapFloor(irPayout)) {
                        return Optional.of(new CapFloorMapper());
                    }

                    // Check if it's an FRA: two payouts, one with fixed rate spec and one with floating rate spec
                    if (isFRA(payouts)) {
                        return Optional.of(new FraMapper());
                    }

                    return Optional.of(new InterestRateSwapMapper());
                }

                if (cdPayout != null) {
                    return Optional.of(new CreditDefaultSwapMapper());
                }

                // Check for SettlementPayout with currency pair underlier -> FX Single Leg
                Object settlementPayout = p.getSettlementPayout();
                if (settlementPayout != null) {
                    if (isFxSingleLeg(settlementPayout)) {
                        return Optional.of(new FxSingleLegMapper());
                    }
                }

                // Check for OptionPayout with various underliers -> FX Option, Equity Option, etc.
                Object optionPayout = p.getOptionPayout();
                if (optionPayout != null) {
                    // Priority order: more specific types first
                    if (isCommodityOption(optionPayout)) {
                        return Optional.of(new CommodityOptionMapper());
                    }
                    if (isBondOption(optionPayout)) {
                        return Optional.of(new BondOptionMapper());
                    }
                    if (isDividendSwapOption(optionPayout)) {
                        return Optional.of(new DividendSwapOptionMapper());
                    }
                    if (isEquityOption(optionPayout)) {
                        return Optional.of(new EquityOptionMapper());
                    }
                    if (isFxOption(optionPayout)) {
                        return Optional.of(new FxOptionMapper());
                    }
                }

                // Check for PerformancePayout + FixedPricePayout -> Dividend Swap
                Object performancePayout = p.getPerformancePayout();
                Object fixedPricePayout = p.getFixedPricePayout();
                if (performancePayout != null && fixedPricePayout != null) {
                    return Optional.of(new DividendSwapMapper());
                }

                // Check for CommodityPayout + FixedPricePayout -> Commodity Swap
                Object commodityPayout = p.getCommodityPayout();
                if (commodityPayout != null && fixedPricePayout != null) {
                    return Optional.of(new CommoditySwapMapper());
                }

                // Check for PerformancePayout alone or CommodityPayout alone -> Dividend/Commodity Swap
                if (performancePayout != null) {
                    return Optional.of(new DividendSwapMapper());
                }
                if (commodityPayout != null) {
                    return Optional.of(new CommoditySwapMapper());
                }

                // Check for FxSingleLeg on the payout itself via reflection
                try {
                    Object fxSingleLeg = invokeField(p, "getFxSingleLeg");
                    if (fxSingleLeg != null) {
                        return Optional.of(new FxSingleLegMapper());
                    }
                } catch (Exception ignored) {}

                // Check for Cashflow with currency pair -> FX Single Leg via reflection
                try {
                    Object cashflow = invokeField(p, "getCashflow");
                    if (cashflow != null) {
                        Object currencyPair = invokeField(cashflow, "getCurrencyPair");
                        if (currencyPair != null) {
                            return Optional.of(new FxSingleLegMapper());
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Secondary detection: check TradeState directly for product types
            Object fxSwapLeg = invokeField(tradeState, "getFxSwapLeg");
            if (fxSwapLeg instanceof List && !((List<?>) fxSwapLeg).isEmpty()) {
                return Optional.of(new FxSwapMapper());
            }

            Object fxSingleLegDirect = invokeField(tradeState, "getFxSingleLeg");
            if (fxSingleLegDirect != null) {
                // Distinguish between FX Swap and FX Single Leg by checking for second leg
                Object hasSecondLeg = invokeField(tradeState, "getFxSwapLeg");
                if (hasSecondLeg instanceof List && !((List<?>) hasSecondLeg).isEmpty()) {
                    return Optional.of(new FxSwapMapper());
                } else {
                    return Optional.of(new FxSingleLegMapper());
                }
            }

            Object equitySwap = invokeField(tradeState, "getEquitySwap");
            if (equitySwap != null) {
                return Optional.of(new EquitySwapMapper());
            }

            // Check for BulletPayment/termDeposit/future: single payout with no swap structure
            if (payouts != null && payouts.size() == 1) {
                Payout singlePayout = payouts.get(0);
                try {
                    Object cashflowPayout = invokeField(singlePayout, "getCashflowPayout");
                    if (cashflowPayout != null) {
                        return Optional.of(new BulletPaymentMapper());
                    }
                } catch (Exception ignored) {}
                // Single InterestRatePayout without swap-like structure
                Object irPayout = singlePayout.getInterestRatePayout();
                if (irPayout != null && irPayout instanceof InterestRatePayout) {
                    InterestRatePayout irP = (InterestRatePayout) irPayout;
                    if (!isSwaption(irP, econTerms) && !isCapFloor(irP)) {
                        // Check if it has termDeposit
                        Object termDeposit = invokeField(econTerms, "getTermDeposit");
                        if (termDeposit != null) {
                            return Optional.of(new BulletPaymentMapper());
                        }
                        // Check for future-specific fields
                        Object future = invokeField(econTerms, "getFuture");
                        if (future != null) {
                            return Optional.of(new BulletPaymentMapper());
                        }
                    }
                }
            }

        } catch (Exception e) {
            // Detection failed - fall through to empty
        }

        return Optional.empty();
    }

    private boolean isSwaption(InterestRatePayout irPayout, EconomicTerms econTerms) {
        try {
            Object obj = invokeField(irPayout, "getExerciseDatePeriod");
            if (obj != null) return true;

            Object obj2 = invokeField(econTerms, "getExerciseDatePeriod");
            if (obj2 != null) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isCapFloor(InterestRatePayout irPayout) {
        try {
            Object rateSpec = invokeField(irPayout, "getRateSpecification");
            if (rateSpec != null) {
                Object floatSpec = invokeField(rateSpec, "getFloatingRateSpecification");
                if (floatSpec != null) {
                    try {
                        Object capRateSched = invokeField(floatSpec, "getCapRateSchedule");
                        if (capRateSched != null) return true;
                    } catch (Exception ignored) {}

                    try {
                        Object floorRateSched = invokeField(floatSpec, "getFloorRateSchedule");
                        if (floorRateSched != null) return true;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isFRA(List<? extends Payout> payouts) {
        if (payouts == null || payouts.size() != 2) return false;

        boolean hasFixed = false;
        boolean hasFloating = false;

        for (Payout p : payouts) {
            InterestRatePayout irPayout = p.getInterestRatePayout();
            if (irPayout == null) continue;

            try {
                Object rateSpec = invokeField(irPayout, "getRateSpecification");
                if (rateSpec != null) {
                    Object fixedSpec = invokeField(rateSpec, "getFixedRateSpecification");
                    Object floatSpec = invokeField(rateSpec, "getFloatingRateSpecification");

                    if (fixedSpec != null) hasFixed = true;
                    if (floatSpec != null) hasFloating = true;
                }
            } catch (Exception ignored) {}
        }

        return hasFixed && hasFloating;
    }

    private boolean isFxSingleLeg(Object settlementPayout) {
        try {
            // FX single leg: SettlementPayout with currency pair underlier or cash/currency payment
            Object underlier = invokeField(settlementPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        String str = observable.toString();
                        // Cash or currency underlier indicates FX
                        if (str.contains("Cash") || str.contains("cash")) return true;
                        if (str.contains("Currency") || str.contains("currency")) return true;

                        // Check for identifier that looks like a currency code
                        try {
                            Object idList = invokeField(observable, "getIdentifier");
                            if (idList instanceof List) {
                                for (Object id : (List<?>) idList) {
                                    try {
                                        Object identObj = invokeField(id, "getIdentifier");
                                        if (identObj != null) {
                                            String identStr = extractStringValue(identObj);
                                            if (isCurrencyCode(identStr)) return true;
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }

            // Check for currency pair directly on settlement payout
            try {
                Object baseCurrency = invokeField(settlementPayout, "getBaseCurrency");
                if (baseCurrency != null) return true;
            } catch (Exception ignored) {}

            try {
                Object quoteCurrency = invokeField(settlementPayout, "getQuoteCurrency");
                if (quoteCurrency != null) return true;
            } catch (Exception ignored) {}

            // Check for exchange rate (NDF indicator)
            try {
                Object exchangeRate = invokeField(settlementPayout, "getExchangeRate");
                if (exchangeRate != null) return true;
            } catch (Exception ignored) {}

        } catch (Exception ignored) {}
        return false;
    }

    private boolean isFxOption(Object optionPayout) {
        try {
            // FX Option: OptionPayout with cash/currency underlier or currency pair fields
            Object baseCurrency = invokeField(optionPayout, "getBaseCurrency");
            if (baseCurrency != null) return true;

            Object quoteCurrency = invokeField(optionPayout, "getQuoteCurrency");
            if (quoteCurrency != null) return true;

            // Check underlier for cash/currency type
            Object underlier = invokeField(optionPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        String str = observable.toString();
                        if (str.contains("Cash") || str.contains("cash")) return true;
                        if (str.contains("Currency") || str.contains("currency")) return true;

                        try {
                            Object idList = invokeField(observable, "getIdentifier");
                            if (idList instanceof List) {
                                for (Object id : (List<?>) idList) {
                                    try {
                                        Object identObj = invokeField(id, "getIdentifier");
                                        if (identObj != null) {
                                            String identStr = extractStringValue(identObj);
                                            if (isCurrencyCode(identStr)) return true;
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {}
        return false;
    }

    private boolean isEquityOption(Object optionPayout) {
        try {
            // Equity Option: OptionPayout with equity/asset/Security underlier
            Object shareQuantity = invokeField(optionPayout, "getShareQuantity");
            if (shareQuantity != null) return true;

            Object underlyingEquity = invokeField(optionPayout, "getUnderlyingEquity");
            if (underlyingEquity != null) return true;

            Object underlier = invokeField(optionPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        String str = observable.toString();
                        if (str.contains("Asset") || str.contains("asset")) return true;
                        if (str.contains("Security") || str.contains("security")) return true;
                        if (str.contains("Instrument") || str.contains("instrument")) return true;
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {}
        return false;
    }

    private boolean isBondOption(Object optionPayout) {
        try {
            // Bond Option: OptionPayout with debt/bond Security underlier
            Object underlyingBond = invokeField(optionPayout, "getUnderlyingBond");
            if (underlyingBond != null) return true;

            Object couponSchedule = invokeField(optionPayout, "getCouponSchedule");
            if (couponSchedule != null) return true;

            Object underlier = invokeField(optionPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        String str = observable.toString();
                        if (str.contains("Debt") || str.contains("debt")) return true;
                        if (str.contains("Bond") || str.contains("bond")) return true;
                        if (str.contains("FixedIncome") || str.contains("fixedIncome")) return true;
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {}
        return false;
    }

    private boolean isCommodityOption(Object optionPayout) {
        try {
            // Commodity Option: OptionPayout with commodity underlier
            Object underlyingCommodity = invokeField(optionPayout, "getUnderlyingCommodity");
            if (underlyingCommodity != null) return true;

            Object underlier = invokeField(optionPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        String str = observable.toString();
                        if (str.contains("Commodity") || str.contains("commodity")) return true;

                        // Check for commodity-specific identifiers
                        try {
                            Object idList = invokeField(observable, "getIdentifier");
                            if (idList instanceof List) {
                                for (Object id : (List<?>) idList) {
                                    try {
                                        Object identObj = invokeField(id, "getIdentifier");
                                        if (identObj != null) {
                                            String identStr = extractStringValue(identObj);
                                            if (identStr != null && isCommodityCode(identStr)) return true;
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {}
        return false;
    }

    private boolean isDividendSwapOption(Object optionPayout) {
        try {
            // Dividend Swap Option: OptionPayout with dividend index underlier
            Object divIndex = invokeField(optionPayout, "getDividendIndex");
            if (divIndex != null) return true;

            Object underlier = invokeField(optionPayout, "getUnderlier");
            if (underlier != null) {
                try {
                    Object observable = invokeField(underlier, "getObservable");
                    if (observable != null) {
                        String str = observable.toString();
                        if (str.contains("Dividend") || str.contains("dividend")) return true;
                        if (str.contains("PerformancePayout") || str.contains("performancePayout")) return true;
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {}
        return false;
    }

    private boolean isCurrencyCode(String str) {
        if (str == null || str.length() != 3) return false;
        return str.matches("[A-Z]{3}");
    }

    private boolean isCommodityCode(String str) {
        if (str == null) return false;
        String upper = str.toUpperCase();
        // Common commodity codes/names
        for (String code : new String[]{"WTI", "BRENT", "NATGAS", "HEATING", "GOLD", "SILVER", "COPPER", 
                "OIL", "CRUDE", "WHEAT", "CORN", "SOYBEAN", "COFFEE", "SUGAR", "COTTON"}) {
            if (upper.contains(code)) return true;
        }
        // Capacity units used in commodities
        for (String unit : new String[]{"BBL", "MWH", "MMBTU", "TROY_OZ", "MTONNE", "TONNE"}) {
            if (upper.contains(unit)) return true;
        }
        return false;
    }

    private static Object invokeField(Object obj, String fieldName) throws Exception {
        if (obj == null || fieldName == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(fieldName);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Unwraps a CDM string-like value into a plain String.
     * Handles raw {@code String}, Rosetta {@code FieldWithMeta*} wrappers
     * (via {@code getValue()}), and falls back to {@code toString()}.
     */
    private static String extractStringValue(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        // FieldWithMetaString and similar metadata wrappers expose getValue()
        try {
            Object value = invokeField(obj, "getValue");
            if (value instanceof String) return (String) value;
            if (value != null && value != obj) {
                String nested = extractStringValue(value);
                if (nested != null) return nested;
            }
        } catch (Exception ignored) {}
        String s = obj.toString();
        return (s == null || s.isEmpty()) ? null : s;
    }
}
