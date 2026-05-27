package io.fpmlcdm.payouts;

import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.BusinessCenterEnum;
import cdm.base.datetime.BusinessCenters;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.BusinessDayConventionEnum;
import cdm.base.datetime.CalculationPeriodFrequency;
import cdm.base.datetime.DayTypeEnum;
import cdm.base.datetime.Frequency;
import cdm.base.datetime.Offset;
import cdm.base.datetime.PeriodEnum;
import cdm.base.datetime.PeriodExtendedEnum;
import cdm.base.datetime.RelativeDateOffset;
import cdm.base.datetime.RollConventionEnum;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.PayerReceiver;
import cdm.product.asset.CompoundingMethodEnum;
import cdm.product.asset.FixedRateSpecification;
import cdm.product.asset.FloatingRateSpecification;
import cdm.product.asset.InterestRatePayout;
import cdm.product.asset.RateSpecification;
import cdm.product.common.schedule.CalculationPeriodDates;
import cdm.product.common.schedule.PayRelativeToEnum;
import cdm.product.asset.StubFloatingRate;
import cdm.product.asset.StubValue;
import cdm.product.common.schedule.PaymentDates;
import cdm.product.common.schedule.StubPeriod;
import cdm.product.common.schedule.StubPeriodTypeEnum;
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
import io.fpmlcdm.common.StreamLabels;
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

    private InterestRatePayoutMapper() {}

    /** Back-compat 4-arg overload (no inflation). */
    public static Payout map(Element swapStream, boolean isFloating, StreamLabels.Labels labels, MappingContext ctx) {
        return map(swapStream, isFloating, false, labels, ctx);
    }

    /**
     * @param swapStream  one of the FpML swapStream elements
     * @param isFloating  true if this is the floating leg, false if fixed
     * @param isInflation true if this is an inflation leg (overrides isFloating routing)
     * @param labels      pre-computed address-ref labels for this stream
     * @param ctx         party order context
     */
    public static Payout map(Element swapStream, boolean isFloating, boolean isInflation,
                              StreamLabels.Labels labels, MappingContext ctx) {
        InterestRatePayout.InterestRatePayoutBuilder irp = InterestRatePayout.builder();

        // Payer / Receiver via party order
        String payer = href(XmlUtils.child(swapStream, "payerPartyReference"));
        String receiver = href(XmlUtils.child(swapStream, "receiverPartyReference"));
        irp.setPayerReceiver(PayerReceiver.builder()
                .setPayer(roleFor(payer, ctx))
                .setReceiver(roleFor(receiver, ctx))
                .build());

        // priceQuantity (address ref into tradeLot) — quantity-N matches this stream's label
        irp.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(addressRef(labels.quantityLabel))
                        .build())
                .build());

        // rateSpecification (address ref)
        if (isInflation) {
            cdm.product.asset.InflationRateSpecification.InflationRateSpecificationBuilder ib =
                    cdm.product.asset.InflationRateSpecification.builder()
                            .setRateOption(ReferenceWithMetaInterestRateIndex.builder()
                                    .setReference(addressRef(labels.indexLabel))
                                    .build());
            irp.setRateSpecification(RateSpecification.builder()
                    .setInflationRateSpecification(ib.build())
                    .build());
        } else if (isFloating) {
            FloatingRateSpecification.FloatingRateSpecificationBuilder floating =
                    FloatingRateSpecification.builder()
                            .setRateOption(ReferenceWithMetaInterestRateIndex.builder()
                                    .setReference(addressRef(labels.indexLabel))
                                    .build());
            if (labels.spreadPriceLabel != null) {
                floating.setSpreadSchedule(cdm.product.asset.SpreadSchedule.builder()
                        .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                .setReference(addressRef(labels.spreadPriceLabel))
                                .build())
                        .build());
            }
            if (labels.capPriceLabel != null) {
                floating.setCapRateSchedule(cdm.product.template.StrikeSchedule.builder()
                        .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                .setReference(addressRef(labels.capPriceLabel))
                                .build())
                        .build());
            }
            if (labels.floorPriceLabel != null) {
                floating.setFloorRateSchedule(cdm.product.template.StrikeSchedule.builder()
                        .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                .setReference(addressRef(labels.floorPriceLabel))
                                .build())
                        .build());
            }
            // initialRate (FpML <initialRate>)
            Element frc = XmlUtils.path(swapStream, "calculationPeriodAmount", "calculation",
                    "floatingRateCalculation");
            String initialRate = XmlUtils.childText(frc, "initialRate");
            if (initialRate != null) {
                floating.setInitialRate(cdm.observable.asset.Price.builder()
                        .setValue(new java.math.BigDecimal(initialRate))
                        .setPriceType(cdm.observable.asset.PriceTypeEnum.INTEREST_RATE)
                        .build());
            }
            // rateTreatment (FpML <rateTreatment>)
            String rateTreatment = XmlUtils.childText(frc, "rateTreatment");
            if (rateTreatment != null) {
                try { floating.setRateTreatment(cdm.product.asset.RateTreatmentEnum
                        .fromDisplayName(rateTreatment)); }
                catch (Exception ignored) {
                    try { floating.setRateTreatment(cdm.product.asset.RateTreatmentEnum
                            .valueOf(rateTreatment.toUpperCase())); } catch (Exception ig2) {}
                }
            }
            // finalRateRounding (FpML <finalRateRounding>)
            Element frr = XmlUtils.child(frc, "finalRateRounding");
            if (frr != null) {
                cdm.base.math.Rounding.RoundingBuilder rb = cdm.base.math.Rounding.builder();
                String dir = XmlUtils.childText(frr, "roundingDirection");
                if (dir != null) {
                    try { rb.setRoundingDirection(cdm.base.math.RoundingDirectionEnum.fromDisplayName(dir)); }
                    catch (Exception ignored) {
                        try { rb.setRoundingDirection(cdm.base.math.RoundingDirectionEnum.valueOf(dir.toUpperCase())); }
                        catch (Exception ig2) {}
                    }
                }
                String prec = XmlUtils.childText(frr, "precision");
                if (prec != null) rb.setPrecision(Integer.parseInt(prec));
                floating.setFinalRateRounding(rb.build());
            }
            irp.setRateSpecification(RateSpecification.builder()
                    .setFloatingRateSpecification(floating.build())
                    .build());
        } else {
            FixedRateSpecification fixed = FixedRateSpecification.builder()
                    .setRateSchedule(RateSchedule.builder()
                            .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                    .setReference(addressRef(labels.fixedPriceLabel))
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

        // compoundingMethod (optional)
        String compMethod = XmlUtils.childText(calc, "compoundingMethod");
        if (compMethod != null) {
            CompoundingMethodEnum cm = compoundingMethod(compMethod);
            if (cm != null) irp.setCompoundingMethod(cm);
        }

        // calculationPeriodDates
        irp.setCalculationPeriodDates(buildCalcDates(XmlUtils.child(swapStream, "calculationPeriodDates")));

        // paymentDates
        irp.setPaymentDates(buildPaymentDates(XmlUtils.child(swapStream, "paymentDates")));

        // resetDates (present on floating legs; preserved as-is)
        Element resetEl = XmlUtils.child(swapStream, "resetDates");
        if (resetEl != null) {
            irp.setResetDates(buildResetDates(resetEl));
        }

        // stubPeriod (FpML <stubCalculationPeriodAmount> inside swapStream)
        Element stubEl = XmlUtils.child(swapStream, "stubCalculationPeriodAmount");
        if (stubEl != null) {
            StubPeriod sp = buildStubPeriod(stubEl);
            if (sp != null) irp.setStubPeriod(sp);
        }

        // cashflowRepresentation (FpML <cashflows> inside swapStream)
        Element cashflows = XmlUtils.child(swapStream, "cashflows");
        if (cashflows != null) {
            cdm.product.asset.CashflowRepresentation cr = CashflowMapper.map(cashflows);
            if (cr != null) irp.setCashflowRepresentation(cr);
        }

        // principalPayment (FpML <principalExchanges> inside swapStream)
        Element pe = XmlUtils.child(swapStream, "principalExchanges");
        if (pe != null) {
            cdm.product.common.settlement.PrincipalPayments.PrincipalPaymentsBuilder pp =
                    cdm.product.common.settlement.PrincipalPayments.builder();
            String init = XmlUtils.childText(pe, "initialExchange");
            String fin = XmlUtils.childText(pe, "finalExchange");
            String inter = XmlUtils.childText(pe, "intermediateExchange");
            boolean hasInitial = init != null && Boolean.parseBoolean(init);
            boolean hasFinal = fin != null && Boolean.parseBoolean(fin);
            if (init != null) pp.setInitialPayment(hasInitial);
            if (fin != null) pp.setFinalPayment(hasFinal);
            if (inter != null) pp.setIntermediatePayment(Boolean.parseBoolean(inter));

            // principalPaymentSchedule from <cashflows><principalExchange> entries
            if (cashflows != null) {
                cdm.product.common.settlement.PrincipalPaymentSchedule sched =
                        buildPrincipalPaymentSchedule(cashflows, payer, receiver, ctx, swapStream,
                                hasInitial, hasFinal);
                if (sched != null) pp.setPrincipalPaymentSchedule(sched);
            }

            irp.setPrincipalPayment(pp.build());
        }

        // settlementTerms (from <settlementProvision> inside swapStream)
        Element settlProv = XmlUtils.child(swapStream, "settlementProvision");
        if (settlProv != null) {
            cdm.product.common.settlement.SettlementTerms st = buildSettlementTerms(settlProv);
            if (st != null) irp.setSettlementTerms(st);
        }

        return Payout.builder().setInterestRatePayout(irp.build()).build();
    }

    /**
     * Builds the {@link cdm.product.common.settlement.PrincipalPaymentSchedule} from the FpML
     * {@code <cashflows>/<principalExchange>} entries. Convention:
     *   - amount &gt; 0 ⇒ stream's interest payer pays this principal
     *   - amount &lt; 0 ⇒ stream's interest payer receives this principal (roles swapped)
     */
    private static cdm.product.common.settlement.PrincipalPaymentSchedule buildPrincipalPaymentSchedule(
            Element cashflows, String interestPayer, String interestReceiver,
            MappingContext ctx, Element swapStream, boolean hasInitial, boolean hasFinal) {
        List<Element> exchanges = XmlUtils.children(cashflows, "principalExchange");
        if (exchanges.isEmpty()) return null;

        cdm.product.common.settlement.PrincipalPaymentSchedule.PrincipalPaymentScheduleBuilder b =
                cdm.product.common.settlement.PrincipalPaymentSchedule.builder();

        // Currency from the swapStream's notional
        Element notional = XmlUtils.path(swapStream, "calculationPeriodAmount", "calculation",
                "notionalSchedule", "notionalStepSchedule");
        String ccy = XmlUtils.childText(notional, "currency");

        // Assign each principalExchange to initial / final based on the principalExchanges
        // flags and exchange count. When only one of initial/final flags is true (e.g. NDS),
        // the single entry routes to that slot. With two entries and both flags true, the
        // earliest goes to initial and the latest to final (FpML doc order).
        Element initialEx = null;
        Element finalEx = null;
        if (exchanges.size() == 1) {
            // Decide slot from the boolean flags (NDS-style trades have only finalExchange=true).
            if (hasFinal && !hasInitial) {
                finalEx = exchanges.get(0);
            } else {
                initialEx = exchanges.get(0);
            }
        } else {
            initialEx = exchanges.get(0);
            finalEx = exchanges.get(exchanges.size() - 1);
        }

        if (initialEx != null) {
            cdm.product.common.settlement.PrincipalPayment initial =
                    buildOnePrincipalPayment(initialEx, interestPayer, interestReceiver, ctx, ccy);
            if (initial != null) b.setInitialPrincipalPayment(initial);
        }
        if (finalEx != null) {
            cdm.product.common.settlement.PrincipalPayment fin =
                    buildOnePrincipalPayment(finalEx, interestPayer, interestReceiver, ctx, ccy);
            if (fin != null) b.setFinalPrincipalPayment(fin);
        }
        return b.build();
    }

    private static cdm.product.common.settlement.PrincipalPayment buildOnePrincipalPayment(
            Element fpml, String interestPayer, String interestReceiver, MappingContext ctx, String ccy) {
        cdm.product.common.settlement.PrincipalPayment.PrincipalPaymentBuilder b =
                cdm.product.common.settlement.PrincipalPayment.builder();

        String adj = XmlUtils.childText(fpml, "adjustedPrincipalExchangeDate");
        String unadj = XmlUtils.childText(fpml, "unadjustedPrincipalExchangeDate");
        cdm.base.datetime.AdjustableDate.AdjustableDateBuilder dateB = cdm.base.datetime.AdjustableDate.builder();
        if (unadj != null) dateB.setUnadjustedDate(DateMapper.parse(unadj));
        if (adj != null) {
            // CDM PrincipalPayment uses AdjustableDate.adjustedDate (not unadjusted) when only adjusted.
            // But reference shows "adjustedDate" wrapper; the simple unadjusted form is more common
            // in the dataset. Fall back to adjusted as a FieldWithMetaDate via adjustedDate getter.
            dateB.setAdjustedDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                    .setValue(DateMapper.parse(adj)).build());
        }
        b.setPrincipalPaymentDate(dateB.build());

        String amtText = XmlUtils.childText(fpml, "principalExchangeAmount");
        java.math.BigDecimal amt = amtText == null ? null : new java.math.BigDecimal(amtText);

        // Roles: amt < 0 ⇒ swap payer/receiver of principal
        cdm.base.staticdata.party.PayerReceiver.PayerReceiverBuilder prB =
                cdm.base.staticdata.party.PayerReceiver.builder();
        if (amt != null && amt.signum() < 0) {
            prB.setPayer(roleFor(interestReceiver, ctx));
            prB.setReceiver(roleFor(interestPayer, ctx));
            amt = amt.abs();
        } else {
            prB.setPayer(roleFor(interestPayer, ctx));
            prB.setReceiver(roleFor(interestReceiver, ctx));
        }
        b.setPayerReceiver(prB.build());

        if (amt != null && ccy != null) {
            b.setPrincipalAmount(cdm.observable.asset.Money.builder()
                    .setValue(amt)
                    .setUnit(cdm.base.math.UnitType.builder()
                            .setCurrency(com.rosetta.model.metafields.FieldWithMetaString.builder()
                                    .setValue(ccy).build())
                            .build())
                    .build());
        }
        return b.build();
    }

    private static StubPeriod buildStubPeriod(Element fpml) {
        StubPeriod.StubPeriodBuilder b = StubPeriod.builder();
        Element calcRef = XmlUtils.child(fpml, "calculationPeriodDatesReference");
        if (calcRef != null) {
            b.setCalculationPeriodDatesReference(
                    cdm.product.common.schedule.metafields.ReferenceWithMetaCalculationPeriodDates.builder()
                            .setExternalReference(calcRef.getAttribute("href")).build());
        }
        StubValue initial = buildStubValue(XmlUtils.child(fpml, "initialStub"));
        if (initial != null) b.setInitialStub(initial);
        StubValue finalStub = buildStubValue(XmlUtils.child(fpml, "finalStub"));
        if (finalStub != null) b.setFinalStub(finalStub);
        return b.build();
    }

    private static StubValue buildStubValue(Element fpml) {
        if (fpml == null) return null;
        StubValue.StubValueBuilder b = StubValue.builder();
        String rate = XmlUtils.childText(fpml, "stubRate");
        if (rate != null) b.setStubRate(new java.math.BigDecimal(rate));
        for (Element fr : XmlUtils.children(fpml, "floatingRate")) {
            StubFloatingRate.StubFloatingRateBuilder fb = StubFloatingRate.builder();
            String idx = XmlUtils.childText(fr, "floatingRateIndex");
            if (idx != null) {
                try { fb.setFloatingRateIndex(
                        cdm.base.staticdata.asset.rates.FloatingRateIndexEnum.fromDisplayName(idx)); }
                catch (Exception ignored) {}
            }
            Element tenor = XmlUtils.child(fr, "indexTenor");
            if (tenor != null) {
                String pm = XmlUtils.childText(tenor, "periodMultiplier");
                String p = XmlUtils.childText(tenor, "period");
                cdm.base.datetime.Period.PeriodBuilder periodB = cdm.base.datetime.Period.builder();
                if (pm != null) periodB.setPeriodMultiplier(Integer.parseInt(pm));
                if (p != null) periodB.setPeriod(EnumMappers.period(p));
                fb.setIndexTenor(periodB.build());
            }
            b.addFloatingRate(fb.build());
        }
        return b.build();
    }

    private static CompoundingMethodEnum compoundingMethod(String text) {
        if (text == null) return null;
        try { return CompoundingMethodEnum.fromDisplayName(text); }
        catch (Exception ignored) {}
        try { return CompoundingMethodEnum.valueOf(text.toUpperCase()); }
        catch (Exception ignored) {}
        return null;
    }

    private static CalculationPeriodDates buildCalcDates(Element fpml) {
        if (fpml == null) return null;
        CalculationPeriodDates.CalculationPeriodDatesBuilder b = CalculationPeriodDates.builder();
        AdjustableOrRelativeDate eff = DateMapper.adjustableOrRelative(XmlUtils.child(fpml, "effectiveDate"));
        if (eff == null) {
            eff = DateMapper.relativeOnly(XmlUtils.child(fpml, "relativeEffectiveDate"));
        }
        if (eff != null) b.setEffectiveDate(eff);
        AdjustableOrRelativeDate term = DateMapper.adjustableOrRelative(XmlUtils.child(fpml, "terminationDate"));
        if (term == null) {
            term = DateMapper.relativeOnly(XmlUtils.child(fpml, "relativeTerminationDate"));
        }
        if (term != null) b.setTerminationDate(term);
        Element cpda = XmlUtils.child(fpml, "calculationPeriodDatesAdjustments");
        if (cpda != null) {
            b.setCalculationPeriodDatesAdjustments(DateMapper.businessDayAdjustments(cpda));
        }
        Element cpf = XmlUtils.child(fpml, "calculationPeriodFrequency");
        if (cpf != null) b.setCalculationPeriodFrequency(buildCalcPeriodFreq(cpf));

        // Stub / regular period markers
        String firstRegStart = XmlUtils.childText(fpml, "firstRegularPeriodStartDate");
        if (firstRegStart != null) b.setFirstRegularPeriodStartDate(DateMapper.parse(firstRegStart));
        String lastRegEnd = XmlUtils.childText(fpml, "lastRegularPeriodEndDate");
        if (lastRegEnd != null) b.setLastRegularPeriodEndDate(DateMapper.parse(lastRegEnd));
        String firstCompEnd = XmlUtils.childText(fpml, "firstCompoundingPeriodEndDate");
        if (firstCompEnd != null) b.setFirstCompoundingPeriodEndDate(DateMapper.parse(firstCompEnd));
        String stubType = XmlUtils.childText(fpml, "stubPeriodType");
        if (stubType != null) {
            StubPeriodTypeEnum spt = stubPeriodType(stubType);
            if (spt != null) b.setStubPeriodType(spt);
        }
        // firstPeriodStartDate (adjustable form, present in some stub trades)
        Element firstPeriodStart = XmlUtils.child(fpml, "firstPeriodStartDate");
        if (firstPeriodStart != null) {
            AdjustableOrRelativeDate aor = DateMapper.adjustableOrRelative(firstPeriodStart);
            if (aor != null) b.setFirstPeriodStartDate(aor);
        }

        String id = fpml.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return b.build();
    }

    private static StubPeriodTypeEnum stubPeriodType(String text) {
        if (text == null) return null;
        try { return StubPeriodTypeEnum.fromDisplayName(text); }
        catch (Exception ignored) {}
        try { return StubPeriodTypeEnum.valueOf(text.toUpperCase()); }
        catch (Exception ignored) {}
        return null;
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

        // paymentDaysOffset (Offset = Period + dayType)
        Element pdo = XmlUtils.child(fpml, "paymentDaysOffset");
        if (pdo != null) {
            Offset.OffsetBuilder ob = Offset.builder();
            String pm = XmlUtils.childText(pdo, "periodMultiplier");
            String p = XmlUtils.childText(pdo, "period");
            if (pm != null) ob.setPeriodMultiplier(Integer.parseInt(pm));
            if (p != null) ob.setPeriod(EnumMappers.period(p));
            String dt = XmlUtils.childText(pdo, "dayType");
            if (dt != null) {
                try { ob.setDayType(DayTypeEnum.valueOf(dt.toUpperCase())); }
                catch (IllegalArgumentException ignored) {}
            }
            b.setPaymentDaysOffset(ob.build());
        }

        String firstPaymentDate = XmlUtils.childText(fpml, "firstPaymentDate");
        if (firstPaymentDate != null) b.setFirstPaymentDate(DateMapper.parse(firstPaymentDate));
        String lastRegularPayment = XmlUtils.childText(fpml, "lastRegularPaymentDate");
        if (lastRegularPayment != null) b.setLastRegularPaymentDate(DateMapper.parse(lastRegularPayment));

        Element pda = XmlUtils.child(fpml, "paymentDatesAdjustments");
        if (pda != null) {
            b.setPaymentDatesAdjustments(DateMapper.businessDayAdjustments(pda));
        }
        String id = fpml.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
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

    /* ───── settlementTerms from <settlementProvision> ───── */

    private static cdm.product.common.settlement.SettlementTerms buildSettlementTerms(Element settlProv) {
        if (settlProv == null) return null;
        cdm.product.common.settlement.SettlementTerms.SettlementTermsBuilder stb =
                cdm.product.common.settlement.SettlementTerms.builder();
        String settlCcy = XmlUtils.childText(settlProv, "settlementCurrency");
        if (settlCcy != null) {
            stb.setSettlementCurrency(com.rosetta.model.metafields.FieldWithMetaString.builder()
                    .setValue(settlCcy).build());
        }
        Element nds = XmlUtils.child(settlProv, "nonDeliverableSettlement");
        if (nds != null) {
            stb.setSettlementType(cdm.product.common.settlement.SettlementTypeEnum.CASH);
            cdm.product.common.settlement.CashSettlementTerms.CashSettlementTermsBuilder cstb =
                    cdm.product.common.settlement.CashSettlementTerms.builder();
            String sro = XmlUtils.childText(nds, "settlementRateOption");
            if (sro != null) {
                cdm.observable.asset.SettlementRateOptionEnum sroEnum = mapSettlementRateOption(sro);
                cdm.observable.asset.metafields.FieldWithMetaSettlementRateOptionEnum fwm =
                        cdm.observable.asset.metafields.FieldWithMetaSettlementRateOptionEnum.builder()
                                .setValue(sroEnum).build();
                cstb.setValuationMethod(cdm.observable.asset.ValuationMethod.builder()
                        .setValuationSource(cdm.observable.asset.ValuationSource.builder()
                                .setSettlementRateOption(cdm.observable.asset.SettlementRateOption.builder()
                                        .setSettlementRateOption(fwm).build())
                                .build())
                        .build());
            }
            Element fxFixingEl = XmlUtils.child(nds, "fxFixingDate");
            if (fxFixingEl != null) {
                cdm.product.common.settlement.FxFixingDate fxFix = buildFxFixingDate(fxFixingEl);
                if (fxFix != null) {
                    cstb.setValuationDate(cdm.product.common.settlement.ValuationDate.builder()
                            .setFxFixingDate(fxFix).build());
                }
            }
            stb.addCashSettlementTerms(cstb.build());
        }
        return stb.build();
    }

    private static cdm.product.common.settlement.FxFixingDate buildFxFixingDate(Element el) {
        cdm.product.common.settlement.FxFixingDate.FxFixingDateBuilder b =
                cdm.product.common.settlement.FxFixingDate.builder();
        String pm = XmlUtils.childText(el, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(el, "period");
        if (p != null) b.setPeriod(EnumMappers.period(p));
        String dt = XmlUtils.childText(el, "dayType");
        if (dt != null) {
            try { b.setDayType(DayTypeEnum.valueOf(dt.toUpperCase())); }
            catch (Exception ignored) {}
        }
        String bdc = XmlUtils.childText(el, "businessDayConvention");
        if (bdc != null) b.setBusinessDayConvention(EnumMappers.bdc(bdc));
        Element centers = XmlUtils.child(el, "businessCenters");
        if (centers != null) b.setBusinessCenters(DateMapper.buildBusinessCenters(centers));
        Element centersRef = XmlUtils.child(el, "businessCentersReference");
        if (centersRef != null) {
            b.setBusinessCentersReference(ReferenceWithMetaBusinessCenters.builder()
                    .setExternalReference(centersRef.getAttribute("href")).build());
        }
        Element drtpd = XmlUtils.child(el, "dateRelativeToPaymentDates");
        if (drtpd != null) {
            cdm.product.common.schedule.DateRelativeToPaymentDates.DateRelativeToPaymentDatesBuilder drtb =
                    cdm.product.common.schedule.DateRelativeToPaymentDates.builder();
            Element pdRef = XmlUtils.child(drtpd, "paymentDatesReference");
            if (pdRef != null) {
                drtb.addPaymentDatesReference(
                        cdm.product.common.schedule.metafields.ReferenceWithMetaPaymentDates.builder()
                                .setExternalReference(pdRef.getAttribute("href")).build());
            }
            b.setDateRelativeToPaymentDates(drtb.build());
        }
        return b.build();
    }

    private static cdm.observable.asset.SettlementRateOptionEnum mapSettlementRateOption(String text) {
        if (text == null) return null;
        try { return cdm.observable.asset.SettlementRateOptionEnum.fromDisplayName(text); }
        catch (Exception ignored) {}
        String normalized = text.replace('.', '_').replace('/', '_');
        try { return cdm.observable.asset.SettlementRateOptionEnum.valueOf(normalized); }
        catch (Exception ignored) {}
        return null;
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
