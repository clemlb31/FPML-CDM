package io.fpmlcdm.fpml.cdm.payouts;

import cdm.product.asset.CashflowRepresentation;
import cdm.observable.asset.RateObservation;
import cdm.product.asset.FloatingRateDefinition;
import cdm.product.common.schedule.CalculationPeriod;
import cdm.product.common.schedule.PaymentCalculationPeriod;
import io.fpmlcdm.fpml.cdm.common.DateMapper;
import io.fpmlcdm.fpml.cdm.common.XmlUtils;
import org.w3c.dom.Element;

import java.math.BigDecimal;

/**
 * Maps FpML {@code <cashflows>} (under {@code <swapStream>}) into CDM
 * {@link CashflowRepresentation}.
 *
 * Only fields actually observed in the rates-5-10 / rates-5-12 / interest-rate-derivatives-5-13
 * datasets are handled. Anything else is left unset, which keeps the diff narrower than the
 * dataset's reference for unhandled flavours.
 */
public final class CashflowMapper {

    private CashflowMapper() {}

    public static CashflowRepresentation map(Element fpml) {
        if (fpml == null) return null;
        CashflowRepresentation.CashflowRepresentationBuilder b = CashflowRepresentation.builder();
        String match = XmlUtils.childText(fpml, "cashflowsMatchParameters");
        if (match != null) b.setCashflowsMatchParameters(Boolean.parseBoolean(match));
        for (Element pcp : XmlUtils.children(fpml, "paymentCalculationPeriod")) {
            b.addPaymentCalculationPeriod(buildPcp(pcp));
        }
        return b.build();
    }

    private static PaymentCalculationPeriod buildPcp(Element fpml) {
        PaymentCalculationPeriod.PaymentCalculationPeriodBuilder b = PaymentCalculationPeriod.builder();
        String unadj = XmlUtils.childText(fpml, "unadjustedPaymentDate");
        if (unadj != null) b.setUnadjustedPaymentDate(DateMapper.parse(unadj));
        String adj = XmlUtils.childText(fpml, "adjustedPaymentDate");
        if (adj != null) b.setAdjustedPaymentDate(DateMapper.parse(adj));
        for (Element cp : XmlUtils.children(fpml, "calculationPeriod")) {
            b.addCalculationPeriod(buildCp(cp));
        }
        return b.build();
    }

    private static CalculationPeriod buildCp(Element fpml) {
        CalculationPeriod.CalculationPeriodBuilder b = CalculationPeriod.builder();
        String us = XmlUtils.childText(fpml, "unadjustedStartDate");
        if (us != null) b.setUnadjustedStartDate(DateMapper.parse(us));
        String ue = XmlUtils.childText(fpml, "unadjustedEndDate");
        if (ue != null) b.setUnadjustedEndDate(DateMapper.parse(ue));
        String as = XmlUtils.childText(fpml, "adjustedStartDate");
        if (as != null) b.setAdjustedStartDate(DateMapper.parse(as));
        String ae = XmlUtils.childText(fpml, "adjustedEndDate");
        if (ae != null) b.setAdjustedEndDate(DateMapper.parse(ae));
        String nDays = XmlUtils.childText(fpml, "calculationPeriodNumberOfDays");
        if (nDays != null) b.setCalculationPeriodNumberOfDays(Integer.parseInt(nDays));
        String notional = XmlUtils.childText(fpml, "notionalAmount");
        if (notional != null) b.setNotionalAmount(new BigDecimal(notional));
        String fixedRate = XmlUtils.childText(fpml, "fixedRate");
        if (fixedRate != null) b.setFixedRate(new BigDecimal(fixedRate));
        String dcyf = XmlUtils.childText(fpml, "dayCountYearFraction");
        if (dcyf != null) b.setDayCountYearFraction(new BigDecimal(dcyf));
        Element frd = XmlUtils.child(fpml, "floatingRateDefinition");
        if (frd != null) b.setFloatingRateDefinition(buildFloatingRateDefinition(frd));
        return b.build();
    }

    private static FloatingRateDefinition buildFloatingRateDefinition(Element fpml) {
        FloatingRateDefinition.FloatingRateDefinitionBuilder b = FloatingRateDefinition.builder();
        for (Element ro : XmlUtils.children(fpml, "rateObservation")) {
            RateObservation.RateObservationBuilder rb = RateObservation.builder();
            String afd = XmlUtils.childText(ro, "adjustedFixingDate");
            if (afd != null) rb.setAdjustedFixingDate(DateMapper.parse(afd));
            String ow = XmlUtils.childText(ro, "observationWeight");
            if (ow != null) rb.setObservationWeight(Integer.parseInt(ow));
            String or = XmlUtils.childText(ro, "observedRate");
            if (or != null) rb.setObservedRate(new BigDecimal(or));
            String fr = XmlUtils.childText(ro, "forecastRate");
            if (fr != null) rb.setForecastRate(new BigDecimal(fr));
            b.addRateObservation(rb.build());
        }
        String calcRate = XmlUtils.childText(fpml, "calculatedRate");
        if (calcRate != null) b.setCalculatedRate(new BigDecimal(calcRate));
        return b.build();
    }
}
