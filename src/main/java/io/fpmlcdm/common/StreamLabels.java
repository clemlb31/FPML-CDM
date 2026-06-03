package io.fpmlcdm.common;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-computes the {@code priceQuantity}/{@code price}/{@code observable}/{@code InterestRateIndex}
 * address-reference labels for every FpML {@code <swapStream>} in document order.
 *
 * Reference convention observed in rates-5-10:
 *   - {@code quantity-N}             = N is the 1-based stream index
 *   - {@code price-K}                = K is a global counter incremented every time a price
 *                                      (spread or fixed-rate) is emitted across all streams,
 *                                      in stream order
 *   - {@code observable-N}           = N is a counter incremented per floating leg
 *   - {@code InterestRateIndex-N}    = N is a counter incremented per floating leg
 */
public final class StreamLabels {

    /** Per-stream labels. */
    public static final class Labels {
        public final String quantityLabel;
        /** Price label this stream's FixedRateSpecification points at, or null if floating. */
        public final String fixedPriceLabel;
        /** Price label for this stream's floating-leg spreadSchedule (if any), else null. */
        public final String spreadPriceLabel;
        /** Price label for capRateSchedule, else null. */
        public final String capPriceLabel;
        /** Price label for floorRateSchedule, else null. */
        public final String floorPriceLabel;
        public final String observableLabel;
        public final String indexLabel;

        Labels(String quantityLabel, String fixedPriceLabel, String spreadPriceLabel,
               String capPriceLabel, String floorPriceLabel,
               String observableLabel, String indexLabel) {
            this.quantityLabel = quantityLabel;
            this.fixedPriceLabel = fixedPriceLabel;
            this.spreadPriceLabel = spreadPriceLabel;
            this.capPriceLabel = capPriceLabel;
            this.floorPriceLabel = floorPriceLabel;
            this.observableLabel = observableLabel;
            this.indexLabel = indexLabel;
        }
    }

    private StreamLabels() {}

    public static Map<Element, Labels> compute(List<Element> swapStreams) {
        Map<Element, Labels> out = new HashMap<>();
        int priceCounter = 0;
        int observableCounter = 0;
        int quantityCounter = 0;
        for (int i = 0; i < swapStreams.size(); i++) {
            Element s = swapStreams.get(i);
            Element calc = XmlUtils.path(s, "calculationPeriodAmount", "calculation");
            Element frc = calc == null ? null : XmlUtils.child(calc, "floatingRateCalculation");
            Element irc = calc == null ? null : XmlUtils.child(calc, "inflationRateCalculation");
            // Treat inflation rate calculation as a flavour of floating for labelling purposes.
            Element rateCalc = frc != null ? frc : irc;
            boolean floating = rateCalc != null;
            boolean fixed = calc != null && XmlUtils.child(calc, "fixedRateSchedule") != null;
            // knownAmountSchedule = pre-computed payment amounts, no notional/rate to reference
            boolean knownAmount = calc == null && XmlUtils.path(s, "calculationPeriodAmount", "knownAmountSchedule") != null;

            String quantityLabel;
            if (knownAmount) {
                quantityLabel = null;  // no priceQuantity entry, no payout reference
            } else {
                quantityCounter++;
                quantityLabel = "quantity-" + quantityCounter;
            }
            String fixedPriceLabel = null;
            String spreadPriceLabel = null;
            String capPriceLabel = null;
            String floorPriceLabel = null;
            String observableLabel = null;
            String indexLabel = null;
            if (floating) {
                observableCounter++;
                observableLabel = "observable-" + observableCounter;
                indexLabel = "InterestRateIndex-" + observableCounter;
                if (XmlUtils.child(rateCalc, "spreadSchedule") != null) {
                    priceCounter++;
                    spreadPriceLabel = "price-" + priceCounter;
                }
                if (XmlUtils.child(rateCalc, "capRateSchedule") != null) {
                    priceCounter++;
                    capPriceLabel = "price-" + priceCounter;
                }
                if (XmlUtils.child(rateCalc, "floorRateSchedule") != null) {
                    priceCounter++;
                    floorPriceLabel = "price-" + priceCounter;
                }
            }
            if (fixed) {
                priceCounter++;
                fixedPriceLabel = "price-" + priceCounter;
            }
            out.put(s, new Labels(quantityLabel, fixedPriceLabel, spreadPriceLabel,
                    capPriceLabel, floorPriceLabel, observableLabel, indexLabel));
        }
        return out;
    }
}
