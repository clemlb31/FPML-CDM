package io.fpmlcdm.cdm.fpml.payouts;

import cdm.base.datetime.AdjustableDate;
import cdm.event.common.TradeState;
import cdm.product.asset.InterestRatePayout;
import cdm.product.template.Payout;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.CdmToFpmlProductMapper;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Mapper for InterestRatePayout to FpML <interestLeg>.
 */
public class FpmlInterestRatePayoutMapper implements CdmToFpmlProductMapper {

    @Override
    public Element map(TradeState tradeState, CdmToFpmlMappingContext context) {
        try {
            java.util.List<? extends Payout> payouts = getPayouts(tradeState);
            if (payouts != null && !payouts.isEmpty()) {
                for (Payout p : payouts) {
                    if (p instanceof InterestRatePayout) {
                        Element leg = mapPayout((InterestRatePayout) p, context);
                        if (leg != null) return leg;
                    }
                }
            }
        } catch (Exception e) {
            context.addWarning("Could not get payouts from TradeState: " + e.getMessage());
        }
        return null; 
    }

    /**
     * Maps a specific {@link InterestRatePayout} to an FpML <interestLeg>.
     */
    public Element mapPayout(InterestRatePayout payout, CdmToFpmlMappingContext context) {
        Document doc = context.getDocument();
        Element legElement = doc.createElementNS(FpmlConstants.FPML_NS, "interestLeg");
        
        // 1. Map ID and Direction (Payer/Receiver)
        String legId = mapLegId(payout);
        legElement.setAttribute("id", legId);
        String direction = mapDirection(payout, context);
        if (direction != null) {
            legElement.setAttribute("direction", direction);
        }

        // 2. Map Dates via CalculationPeriodDates
        try {
            Object calcDates = invokeField(payout, "getCalculationPeriodDates");
            if (calcDates != null) {
                mapEffectiveDate(doc, legElement, calcDates);
                mapTerminationDate(doc, legElement, calcDates);
                
                // Map Frequency and PayRelativeTo if present in calcDates
                mapFrequency(doc, legElement, calcDates);
            }
        } catch (Exception e) {
            context.addWarning("Could not map calculation period dates for payout: " + e.getMessage());
        }

        // 3. Map Day Count Fraction
        try {
            Object dcf = invokeField(payout, "getDayCountFraction");
            if (dcf != null) {
                Element dcfElem = doc.createElementNS(FpmlConstants.FPML_NS, "dayCountFraction");
                dcfElem.setTextContent(String.valueOf(dcf));
                legElement.appendChild(dcfElem);
            }
        } catch (Exception e) {
            context.addWarning("Could not map day count fraction for payout: " + e.getMessage());
        }

        return legElement;
    }

    private String mapLegId(InterestRatePayout payout) {
        try {
            Object id = invokeField(payout, "getId");
            return id != null ? String.valueOf(id) : "leg_" + Math.abs(payout.hashCode());
        } catch (Exception e) {
            return "leg_" + Math.abs(payout.hashCode());
        }
    }

    private String mapDirection(InterestRatePayout payout, CdmToFpmlMappingContext context) {
        try {
            Object dir = invokeField(payout, "getPayerReceiver");
            if (dir != null && dir instanceof Enum) {
                return ((Enum<?>) dir).name().toLowerCase();
            }
            return "payer"; // Default fallback
        } catch (Exception e) {
            context.addWarning("Could not map direction for payout: " + e.getMessage());
            return "payer";
        }
    }

    private void mapEffectiveDate(Document doc, Element legElement, Object calcDates) throws Exception {
        try {
            Object effField = invokeField(calcDates, "getEffectiveDate");
            if (effField != null && effField instanceof AdjustableDate) {
                Element effElem = doc.createElementNS(FpmlConstants.FPML_NS, "effectiveDate");
                effElem.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (AdjustableDate) effField));
                legElement.appendChild(effElem);
            }
        } catch (Exception e) {
            // Fallback or warning handled at caller level
        }
    }

    private void mapTerminationDate(Document doc, Element legElement, Object calcDates) throws Exception {
        try {
            Object termField = invokeField(calcDates, "getTerminationDate");
            if (termField != null && termField instanceof AdjustableDate) {
                Element termElem = doc.createElementNS(FpmlConstants.FPML_NS, "terminationDate");
                termElem.appendChild(io.fpmlcdm.cdm.fpml.common.CdmDateMapper.mapAdjustableDate(doc, (AdjustableDate) termField));
                legElement.appendChild(termElem);
            }
        } catch (Exception e) {
            // Fallback or warning handled at caller level
        }
    }

    private void mapFrequency(Document doc, Element legElement, Object calcDates) throws Exception {
        try {
            Object freq = invokeField(calcDates, "getPaymentFrequency");
            if (freq != null) {
                Element freqElem = doc.createElementNS(FpmlConstants.FPML_NS, "paymentFrequency");
                Element resetFreq = doc.createElementNS(FpmlConstants.FPML_NS, "resetFrequency");
                resetFreq.setTextContent(String.valueOf(freq));
                freqElem.appendChild(resetFreq);
                legElement.appendChild(freqElem);
            }
        } catch (Exception e) {
            // Fallback or warning handled at caller level
        }
    }

    private java.util.List<? extends Payout> getPayouts(TradeState tradeState) throws Exception {
        Object trade = invokeField(tradeState, "getTrade");
        if (trade == null) return null;
        
        Object product = invokeField(trade, "getProduct");
        if (product == null) return null;

        Object econTerms = invokeField(product, "getEconomicTerms");
        if (econTerms == null) return null;

        return (java.util.List<? extends Payout>) invokeField(econTerms, "getPayout");
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
}
