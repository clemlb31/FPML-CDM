package io.fpmlcdm.payouts;

import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.BusinessCenterEnum;
import cdm.base.datetime.BusinessCenters;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.BusinessDayConventionEnum;
import cdm.base.datetime.CalculationPeriodFrequency;
import cdm.base.datetime.Frequency;
import cdm.base.datetime.PeriodEnum;
import cdm.base.datetime.PeriodExtendedEnum;
import cdm.base.datetime.RelativeDateOffset;
import cdm.base.datetime.RollConventionEnum;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.PayerReceiver;
import cdm.product.asset.FixedRateSpecification;
import cdm.product.asset.FloatingRateSpecification;
import cdm.product.asset.InterestRatePayout;
import cdm.product.asset.RateSpecification;
import cdm.product.common.schedule.CalculationPeriodDates;
import cdm.product.common.schedule.PayRelativeToEnum;
import cdm.product.common.schedule.PaymentDates;
import cdm.product.common.schedule.RateSchedule;
import cdm.product.common.schedule.ResetDates;
import cdm.product.common.schedule.ResetFrequency;
import cdm.product.common.schedule.ResetRelativeToEnum;
import cdm.product.common.schedule.metafields.ReferenceWithMetaCalculationPeriodDates;
import cdm.product.common.settlement.ResolvablePriceQuantity;
import cdm.product.template.Payout;
import cdm.observable.asset.metafields.ReferenceWithMetaInterestRateIndex;
import cdm.observable.asset.metafields.ReferenceWithMetaPriceSchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.MetaFields;
import com.rosetta.model.metafields.ReferenceWithMetaDate;
import io.fpmlcdm.common.DateMapper;
import io.fpmlcdm.common.EnumMappers;
import io.fpmlcdm.common.MappingContext;
import io.fpmlcdm.common.XmlUtils;
import org.w3c.dom.Element;

import java.util.List;

/**
 * Converts a single FpML {@code <swapStream>} (fixed or floating leg) into a CDM {@link Payout}
 * holding an {@link InterestRatePayout}.
 *
 * The leg uses cross-references (address) into {@code tradeLot[0].priceQuantity[*]} for the
 * actual notional/rate/index values — those are produced by QuantityMapper.
 */
public final class InterestRatePayoutMapper {

    public static final String QUANTITY_FLOAT = "quantity-1";
    public static final String QUANTITY_FIXED = "quantity-2";
    public static final String PRICE_FIXED = "price-1";
    public static final String INDEX_REF = "InterestRateIndex-1";

    private InterestRatePayoutMapper() {}

    /**
     * @param swapStream  one of the two FpML swapStream elements
     * @param isFloating  true if this is the floating leg, false if fixed
     * @param ctx         party order context
     */
    public static Payout map(Element swapStream, boolean isFloating, MappingContext ctx) {
        InterestRatePayout.InterestRatePayoutBuilder irp = InterestRatePayout.builder();

        // Payer / Receiver via party order
        String payer = href(XmlUtils.child(swapStream, "payerPartyReference"));
        String receiver = href(XmlUtils.child(swapStream, "receiverPartyReference"));
        irp.setPayerReceiver(PayerReceiver.builder()
                .setPayer(roleFor(payer, ctx))
                .setReceiver(roleFor(receiver, ctx))
                .build());

        // priceQuantity (address ref into tradeLot)
        String quantityLoc = isFloating ? QUANTITY_FLOAT : QUANTITY_FIXED;
        irp.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(addressRef(quantityLoc))
                        .build())
                .build());

        // rateSpecification (address ref)
        if (isFloating) {
            FloatingRateSpecification floating = FloatingRateSpecification.builder()
                    .setRateOption(ReferenceWithMetaInterestRateIndex.builder()
                            .setReference(addressRef(INDEX_REF))
                            .build())
                    .build();
            irp.setRateSpecification(RateSpecification.builder()
                    .setFloatingRateSpecification(floating)
                    .build());
        } else {
            FixedRateSpecification fixed = FixedRateSpecification.builder()
                    .setRateSchedule(RateSchedule.builder()
                            .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                    .setReference(addressRef(PRICE_FIXED))
                                    .build())
                            .build())
                    .build();
            irp.setRateSpecification(RateSpecification.builder()
                    .setFixedRateSpecification(fixed)
                    .build());
        }

        // dayCountFraction
        Element calc = XmlUtils.path(swapStream, "calculationPeriodAmount", "calculation");
        String dcf = XmlUtils.childText(calc, "dayCountFraction");
        if (dcf != null) irp.setDayCountFraction(EnumMappers.dayCount(dcf));

        // calculationPeriodDates
        irp.setCalculationPeriodDates(buildCalcDates(XmlUtils.child(swapStream, "calculationPeriodDates")));

        // paymentDates
        irp.setPaymentDates(buildPaymentDates(XmlUtils.child(swapStream, "paymentDates")));

        // resetDates (floating leg only, when present)
        if (isFloating) {
            Element resetEl = XmlUtils.child(swapStream, "resetDates");
            if (resetEl != null) {
                irp.setResetDates(buildResetDates(resetEl));
            }
        }

        return Payout.builder().setInterestRatePayout(irp.build()).build();
    }

    private static CalculationPeriodDates buildCalcDates(Element fpml) {
        if (fpml == null) return null;
        CalculationPeriodDates.CalculationPeriodDatesBuilder b = CalculationPeriodDates.builder();
        AdjustableOrRelativeDate eff = DateMapper.adjustableOrRelative(XmlUtils.child(fpml, "effectiveDate"));
        if (eff != null) b.setEffectiveDate(eff);
        AdjustableOrRelativeDate term = DateMapper.adjustableOrRelative(XmlUtils.child(fpml, "terminationDate"));
        if (term != null) b.setTerminationDate(term);
        Element cpda = XmlUtils.child(fpml, "calculationPeriodDatesAdjustments");
        if (cpda != null) {
            b.setCalculationPeriodDatesAdjustments(DateMapper.businessDayAdjustments(cpda));
        }
        Element cpf = XmlUtils.child(fpml, "calculationPeriodFrequency");
        if (cpf != null) b.setCalculationPeriodFrequency(buildCalcPeriodFreq(cpf));
        String id = fpml.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return b.build();
    }

    private static CalculationPeriodFrequency buildCalcPeriodFreq(Element fpml) {
        CalculationPeriodFrequency.CalculationPeriodFrequencyBuilder b = CalculationPeriodFrequency.builder();
        String pm = XmlUtils.childText(fpml, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String period = XmlUtils.childText(fpml, "period");
        if (period != null) b.setPeriod(EnumMappers.periodExtended(period));
        String roll = XmlUtils.childText(fpml, "rollConvention");
        if (roll != null) b.setRollConvention(parseRollConvention(roll));
        return b.build();
    }

    private static RollConventionEnum parseRollConvention(String text) {
        try {
            // Numeric values use _N convention
            int n = Integer.parseInt(text);
            return RollConventionEnum.valueOf("_" + n);
        } catch (NumberFormatException ignored) {}
        try { return RollConventionEnum.valueOf(text); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static PaymentDates buildPaymentDates(Element fpml) {
        if (fpml == null) return null;
        PaymentDates.PaymentDatesBuilder b = PaymentDates.builder();
        Element pf = XmlUtils.child(fpml, "paymentFrequency");
        if (pf != null) {
            String pm = XmlUtils.childText(pf, "periodMultiplier");
            String p = XmlUtils.childText(pf, "period");
            Frequency.FrequencyBuilder fb = Frequency.builder();
            if (pm != null) fb.setPeriodMultiplier(Integer.parseInt(pm));
            if (p != null) fb.setPeriod(EnumMappers.periodExtended(p));
            b.setPaymentFrequency(fb.build());
        }
        String pr = XmlUtils.childText(fpml, "payRelativeTo");
        if (pr != null) b.setPayRelativeTo(parsePayRelativeTo(pr));
        Element pda = XmlUtils.child(fpml, "paymentDatesAdjustments");
        if (pda != null) {
            b.setPaymentDatesAdjustments(DateMapper.businessDayAdjustments(pda));
        }
        return b.build();
    }

    private static PayRelativeToEnum parsePayRelativeTo(String text) {
        return switch (text) {
            case "CalculationPeriodStartDate" -> PayRelativeToEnum.CALCULATION_PERIOD_START_DATE;
            case "CalculationPeriodEndDate"   -> PayRelativeToEnum.CALCULATION_PERIOD_END_DATE;
            case "ResetDate"                  -> PayRelativeToEnum.RESET_DATE;
            case "ValuationDate"              -> PayRelativeToEnum.VALUATION_DATE;
            case "LastPricingDate"            -> PayRelativeToEnum.LAST_PRICING_DATE;
            default -> null;
        };
    }

    private static ResetDates buildResetDates(Element fpml) {
        ResetDates.ResetDatesBuilder b = ResetDates.builder();
        Element calcRef = XmlUtils.child(fpml, "calculationPeriodDatesReference");
        if (calcRef != null) {
            String href = calcRef.getAttribute("href");
            b.setCalculationPeriodDatesReference(ReferenceWithMetaCalculationPeriodDates.builder()
                    .setExternalReference(href)
                    .build());
        }
        String resetRelTo = XmlUtils.childText(fpml, "resetRelativeTo");
        if (resetRelTo != null) {
            if ("CalculationPeriodStartDate".equals(resetRelTo)) {
                b.setResetRelativeTo(ResetRelativeToEnum.CALCULATION_PERIOD_START_DATE);
            } else if ("CalculationPeriodEndDate".equals(resetRelTo)) {
                b.setResetRelativeTo(ResetRelativeToEnum.CALCULATION_PERIOD_END_DATE);
            }
        }
        Element fixing = XmlUtils.child(fpml, "fixingDates");
        if (fixing != null) {
            b.setFixingDates(buildFixingDates(fixing));
        }
        Element rf = XmlUtils.child(fpml, "resetFrequency");
        if (rf != null) {
            ResetFrequency.ResetFrequencyBuilder rfb = ResetFrequency.builder();
            String pm = XmlUtils.childText(rf, "periodMultiplier");
            String p = XmlUtils.childText(rf, "period");
            if (pm != null) rfb.setPeriodMultiplier(Integer.parseInt(pm));
            if (p != null) rfb.setPeriod(EnumMappers.periodExtended(p));
            b.setResetFrequency(rfb.build());
        }
        Element rda = XmlUtils.child(fpml, "resetDatesAdjustments");
        if (rda != null) {
            b.setResetDatesAdjustments(DateMapper.businessDayAdjustments(rda));
        }
        String id = fpml.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return b.build();
    }

    private static RelativeDateOffset buildFixingDates(Element fpml) {
        RelativeDateOffset.RelativeDateOffsetBuilder b = RelativeDateOffset.builder();
        String pm = XmlUtils.childText(fpml, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(fpml, "period");
        if (p != null) b.setPeriod(EnumMappers.period(p));
        String dt = XmlUtils.childText(fpml, "dayType");
        if (dt != null) {
            try { b.setDayType(cdm.base.datetime.DayTypeEnum.valueOf(dt.toUpperCase())); }
            catch (IllegalArgumentException ignored) {}
        }
        String bdc = XmlUtils.childText(fpml, "businessDayConvention");
        if (bdc != null) b.setBusinessDayConvention(EnumMappers.bdc(bdc));

        Element centers = XmlUtils.child(fpml, "businessCenters");
        if (centers != null) {
            b.setBusinessCenters(DateMapper.buildBusinessCenters(centers));
        }
        Element centersRef = XmlUtils.child(fpml, "businessCentersReference");
        if (centersRef != null) {
            String href = centersRef.getAttribute("href");
            b.setBusinessCentersReference(ReferenceWithMetaBusinessCenters.builder()
                    .setExternalReference(href).build());
        }
        Element drt = XmlUtils.child(fpml, "dateRelativeTo");
        if (drt != null) {
            String href = drt.getAttribute("href");
            b.setDateRelativeTo(ReferenceWithMetaDate.builder()
                    .setExternalReference(href)
                    .build());
        }
        return b.build();
    }

    /* ───── helpers ───── */

    private static String href(Element el) {
        return el == null ? null : el.getAttribute("href");
    }

    private static CounterpartyRoleEnum roleFor(String partyHref, MappingContext ctx) {
        if (partyHref == null) return null;
        Integer order = ctx.partyOrder.get(partyHref);
        if (order == null) return null;
        return order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
    }

    private static Reference addressRef(String value) {
        return Reference.builder()
                .setScope("DOCUMENT")
                .setReference(value)
                .build();
    }
}
