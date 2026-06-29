package io.fpmlcdm.fpml.cdm.common;

import cdm.base.datetime.AdjustableDate;
import cdm.base.datetime.AdjustableDates;
import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.AdjustableOrRelativeDates;
import cdm.base.datetime.BusinessCenterEnum;
import cdm.base.datetime.BusinessCenterTime;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.DayTypeEnum;
import cdm.base.datetime.RelativeDateOffset;
import cdm.base.datetime.RelativeDates;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters;
import cdm.base.staticdata.party.BuyerSeller;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.observable.asset.CashCollateralValuationMethod;
import cdm.observable.asset.CsaTypeEnum;
import cdm.observable.asset.FxSpotRateSource;
import cdm.observable.asset.InformationProviderEnum;
import cdm.observable.asset.InformationSource;
import cdm.observable.asset.PartyDeterminationEnum;
import cdm.observable.asset.QuotationRateTypeEnum;
import cdm.observable.asset.ValuationMethod;
import cdm.observable.asset.ValuationSource;
import cdm.observable.asset.metafields.FieldWithMetaInformationProviderEnum;
import cdm.product.common.settlement.CashSettlementMethodEnum;
import cdm.product.common.settlement.CashSettlementTerms;
import cdm.product.common.settlement.SettlementTerms;
import cdm.product.common.settlement.SettlementTypeEnum;
import cdm.product.common.settlement.ValuationDate;
import cdm.product.template.CancelableProvision;
import cdm.product.template.EarlyTerminationProvision;
import cdm.product.template.ExerciseNotice;
import cdm.product.template.ExerciseTerms;
import cdm.product.template.ExpirationTimeTypeEnum;
import cdm.product.template.ExtendibleProvision;
import cdm.product.template.MandatoryEarlyTermination;
import cdm.product.template.OptionExerciseStyleEnum;
import cdm.product.template.OptionalEarlyTermination;
import cdm.product.template.TerminationProvision;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import com.rosetta.model.metafields.ReferenceWithMetaDate;
import org.w3c.dom.Element;

import java.time.LocalTime;
import java.util.List;

/**
 * Maps FpML {@code <earlyTerminationProvision>}, {@code <cancelableProvision>},
 * and {@code <extendibleProvision>} (children of {@code <swap>}) into
 * CDM {@link TerminationProvision}.
 */
public final class TerminationProvisionMapper {

    private TerminationProvisionMapper() {}

    /**
     * Builds a {@link TerminationProvision} from the given {@code <swap>} element.
     * Returns null if no termination-related provisions are present.
     */
    public static TerminationProvision map(Element swap, MappingContext ctx) {
        if (swap == null) return null;

        Element earlyTermEl = XmlUtils.child(swap, "earlyTerminationProvision");
        Element cancelableEl = XmlUtils.child(swap, "cancelableProvision");
        Element extendibleEl = XmlUtils.child(swap, "extendibleProvision");

        if (earlyTermEl == null && cancelableEl == null && extendibleEl == null) return null;

        TerminationProvision.TerminationProvisionBuilder tp = TerminationProvision.builder();
        if (earlyTermEl != null) tp.setEarlyTerminationProvision(mapEarlyTerminationProvision(earlyTermEl));
        if (cancelableEl != null) tp.setCancelableProvision(mapCancelableProvision(cancelableEl, ctx));
        if (extendibleEl != null) tp.setExtendibleProvision(mapExtendibleProvision(extendibleEl, ctx));
        return tp.build();
    }

    /* â”€â”€â”€â”€â”€ EarlyTerminationProvision â”€â”€â”€â”€â”€ */

    private static EarlyTerminationProvision mapEarlyTerminationProvision(Element earlyTermEl) {
        EarlyTerminationProvision.EarlyTerminationProvisionBuilder b = EarlyTerminationProvision.builder();

        Element mandatoryEl = XmlUtils.child(earlyTermEl, "mandatoryEarlyTermination");
        if (mandatoryEl != null) {
            b.setMandatoryEarlyTermination(mapMandatoryEarlyTermination(mandatoryEl));
        }

        Element optionalEl = XmlUtils.child(earlyTermEl, "optionalEarlyTermination");
        if (optionalEl != null) {
            b.setOptionalEarlyTermination(mapOptionalEarlyTermination(optionalEl));
        }
        return b.build();
    }

    /* â”€â”€â”€â”€â”€ MandatoryEarlyTermination â”€â”€â”€â”€â”€ */

    private static MandatoryEarlyTermination mapMandatoryEarlyTermination(Element el) {
        MandatoryEarlyTermination.MandatoryEarlyTerminationBuilder b = MandatoryEarlyTermination.builder();

        Element dateEl = XmlUtils.child(el, "mandatoryEarlyTerminationDate");
        if (dateEl != null) {
            AdjustableDate ad = DateMapper.adjustable(dateEl);
            if (ad != null) {
                b.setMandatoryEarlyTerminationDate(ad);
            }
        }

        // cashSettlement â†’ SettlementTerms
        Element cashSettlementEl = XmlUtils.child(el, "cashSettlement");
        if (cashSettlementEl != null) {
            b.setCashSettlement(mapCashSettlementToSettlementTerms(cashSettlementEl));
        }
        return b.build();
    }

    /* â”€â”€â”€â”€â”€ OptionalEarlyTermination â”€â”€â”€â”€â”€ */

    private static OptionalEarlyTermination mapOptionalEarlyTermination(Element el) {
        OptionalEarlyTermination.OptionalEarlyTerminationBuilder b = OptionalEarlyTermination.builder();

        // exerciseNotice (may be repeated)
        for (Element noticeEl : XmlUtils.children(el, "exerciseNotice")) {
            ExerciseNotice en = mapExerciseNotice(noticeEl);
            if (en != null) b.addExerciseNotice(en);
        }

        // followUpConfirmation
        String fuc = XmlUtils.childText(el, "followUpConfirmation");
        if (fuc != null) b.setFollowUpConfirmation(Boolean.parseBoolean(fuc));

        // cashSettlement â†’ SettlementTerms
        Element cashSettlementEl = XmlUtils.child(el, "cashSettlement");
        if (cashSettlementEl != null) {
            b.setCashSettlement(mapCashSettlementToSettlementTerms(cashSettlementEl));
        }

        // exerciseTerms from europeanExercise / bermudaExercise / americanExercise
        ExerciseTerms et = mapExerciseTerms(el);
        if (et != null) b.setExerciseTerms(et);

        return b.build();
    }

    /* â”€â”€â”€â”€â”€ CancelableProvision â”€â”€â”€â”€â”€ */

    private static CancelableProvision mapCancelableProvision(Element el, MappingContext ctx) {
        CancelableProvision.CancelableProvisionBuilder b = CancelableProvision.builder();

        // buyerSeller
        mapBuyerSeller(el, b, ctx);

        // exerciseNotice (singular for cancelable/extendible)
        Element noticeEl = XmlUtils.child(el, "exerciseNotice");
        if (noticeEl != null) {
            b.setExerciseNotice(mapExerciseNotice(noticeEl));
        }

        // followUpConfirmation
        String fuc = XmlUtils.childText(el, "followUpConfirmation");
        if (fuc != null) b.setFollowUpConfirmation(Boolean.parseBoolean(fuc));

        // exerciseTerms
        ExerciseTerms et = mapExerciseTerms(el);
        if (et != null) b.setExerciseTerms(et);

        return b.build();
    }

    /* â”€â”€â”€â”€â”€ ExtendibleProvision â”€â”€â”€â”€â”€ */

    private static ExtendibleProvision mapExtendibleProvision(Element el, MappingContext ctx) {
        ExtendibleProvision.ExtendibleProvisionBuilder b = ExtendibleProvision.builder();

        // buyerSeller
        mapBuyerSellerExtendible(el, b, ctx);

        // exerciseNotice (singular)
        Element noticeEl = XmlUtils.child(el, "exerciseNotice");
        if (noticeEl != null) {
            b.setExerciseNotice(mapExerciseNotice(noticeEl));
        }

        // followUpConfirmation
        String fuc = XmlUtils.childText(el, "followUpConfirmation");
        if (fuc != null) b.setFollowUpConfirmation(Boolean.parseBoolean(fuc));

        // exerciseTerms
        ExerciseTerms et = mapExerciseTerms(el);
        if (et != null) b.setExerciseTerms(et);

        return b.build();
    }

    /* â”€â”€â”€â”€â”€ ExerciseNotice â”€â”€â”€â”€â”€ */

    private static ExerciseNotice mapExerciseNotice(Element el) {
        if (el == null) return null;
        ExerciseNotice.ExerciseNoticeBuilder b = ExerciseNotice.builder();
        String bc = XmlUtils.childText(el, "businessCenter");
        if (bc != null) {
            FieldWithMetaBusinessCenterEnum.FieldWithMetaBusinessCenterEnumBuilder fb =
                    FieldWithMetaBusinessCenterEnum.builder();
            try { fb.setValue(BusinessCenterEnum.valueOf(bc)); } catch (Exception ignored) {}
            b.setBusinessCenter(fb.build());
        }
        return b.build();
    }

    /* â”€â”€â”€â”€â”€ ExerciseTerms (from europeanExercise / bermudaExercise / americanExercise) â”€â”€â”€â”€â”€ */

    private static ExerciseTerms mapExerciseTerms(Element parent) {
        Element euro = XmlUtils.child(parent, "europeanExercise");
        Element berm = XmlUtils.child(parent, "bermudaExercise");
        Element amer = XmlUtils.child(parent, "americanExercise");

        Element exercise = euro != null ? euro : (berm != null ? berm : amer);
        if (exercise == null) return null;

        ExerciseTerms.ExerciseTermsBuilder b = ExerciseTerms.builder();

        if (euro != null) b.setStyle(OptionExerciseStyleEnum.EUROPEAN);
        else if (berm != null) b.setStyle(OptionExerciseStyleEnum.BERMUDA);
        else b.setStyle(OptionExerciseStyleEnum.AMERICAN);

        // commencementDate (American: <commencementDate><relativeDate>)
        if (amer != null) {
            Element commencementEl = XmlUtils.child(amer, "commencementDate");
            if (commencementEl != null) {
                Element relDate = XmlUtils.child(commencementEl, "relativeDate");
                if (relDate != null) {
                    RelativeDateOffset rdo = buildRelativeDateOffset(relDate);
                    b.setCommencementDate(AdjustableOrRelativeDate.builder()
                            .setRelativeDate(cdm.base.datetime.AdjustedRelativeDateOffset.builder()
                                    .setPeriodMultiplier(rdo.getPeriodMultiplier())
                                    .setPeriod(rdo.getPeriod())
                                    .setDayType(rdo.getDayType())
                                    .setBusinessDayConvention(rdo.getBusinessDayConvention())
                                    .setBusinessCenters(rdo.getBusinessCenters())
                                    .setBusinessCentersReference(rdo.getBusinessCentersReference())
                                    .setDateRelativeTo(rdo.getDateRelativeTo())
                                    .build())
                            .build());
                } else {
                    Element adjustable = XmlUtils.child(commencementEl, "adjustableDate");
                    if (adjustable != null) {
                        AdjustableDate ad = DateMapper.adjustable(adjustable);
                        if (ad != null) b.setCommencementDate(
                                AdjustableOrRelativeDate.builder().setAdjustableDate(ad).build());
                    }
                }
            }
        }

        // exerciseDates (Bermuda: <bermudaExerciseDates><relativeDates>)
        if (berm != null) {
            Element bermDates = XmlUtils.child(berm, "bermudaExerciseDates");
            if (bermDates != null) {
                Element relDates = XmlUtils.child(bermDates, "relativeDates");
                if (relDates != null) {
                    RelativeDates rd = buildRelativeDates(relDates);
                    if (rd != null) {
                        b.setExerciseDates(AdjustableOrRelativeDates.builder()
                                .setRelativeDates(rd).build());
                    }
                }
                Element adjDates = XmlUtils.child(bermDates, "adjustableDates");
                if (adjDates != null && relDates == null) {
                    AdjustableDates ads = buildAdjustableDates(adjDates);
                    if (ads != null) {
                        b.setExerciseDates(AdjustableOrRelativeDates.builder()
                                .setAdjustableDates(ads).build());
                    }
                }
            }
        }

        // expirationDate
        Element expDateEl = XmlUtils.child(exercise, "expirationDate");
        if (expDateEl != null) {
            Element relDate = XmlUtils.child(expDateEl, "relativeDate");
            if (relDate != null) {
                RelativeDateOffset rdo = buildRelativeDateOffset(relDate);
                b.addExpirationDate(AdjustableOrRelativeDate.builder()
                        .setRelativeDate(cdm.base.datetime.AdjustedRelativeDateOffset.builder()
                                .setPeriodMultiplier(rdo.getPeriodMultiplier())
                                .setPeriod(rdo.getPeriod())
                                .setDayType(rdo.getDayType())
                                .setBusinessDayConvention(rdo.getBusinessDayConvention())
                                .setBusinessCenters(rdo.getBusinessCenters())
                                .setBusinessCentersReference(rdo.getBusinessCentersReference())
                                .setDateRelativeTo(rdo.getDateRelativeTo())
                                .build())
                        .build());
            } else {
                Element adjustable = XmlUtils.child(expDateEl, "adjustableDate");
                if (adjustable != null) {
                    AdjustableDate ad = DateMapper.adjustable(adjustable);
                    if (ad != null) {
                        b.addExpirationDate(AdjustableOrRelativeDate.builder()
                                .setAdjustableDate(ad).build());
                    }
                }
            }
        }

        // relevantUnderlyingDate
        Element ruDate = XmlUtils.child(exercise, "relevantUnderlyingDate");
        if (ruDate != null) {
            Element relDates = XmlUtils.child(ruDate, "relativeDates");
            if (relDates != null) {
                RelativeDates rd = buildRelativeDates(relDates);
                if (rd != null) {
                    b.setRelevantUnderlyingDate(AdjustableOrRelativeDates.builder()
                            .setRelativeDates(rd).build());
                }
            }
            Element adjDates = XmlUtils.child(ruDate, "adjustableDates");
            if (adjDates != null && relDates == null) {
                AdjustableDates ads = buildAdjustableDates(adjDates);
                if (ads != null) {
                    b.setRelevantUnderlyingDate(AdjustableOrRelativeDates.builder()
                            .setAdjustableDates(ads).build());
                }
            }
        }

        // earliestExerciseTime, latestExerciseTime, expirationTime
        Element earliest = XmlUtils.child(exercise, "earliestExerciseTime");
        if (earliest != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(earliest);
            if (bct != null) b.setEarliestExerciseTime(bct);
        }
        Element latest = XmlUtils.child(exercise, "latestExerciseTime");
        if (latest != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(latest);
            if (bct != null) b.setLatestExerciseTime(bct);
        }
        Element expTime = XmlUtils.child(exercise, "expirationTime");
        if (expTime != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(expTime);
            if (bct != null) b.setExpirationTime(bct);
        }
        if (expTime != null || earliest != null) {
            b.setExpirationTimeType(ExpirationTimeTypeEnum.SPECIFIC_TIME);
        }

        // externalKey from exercise element's id
        String id = exercise.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }

        return b.build();
    }

    /* â”€â”€â”€â”€â”€ Cash settlement â†’ SettlementTerms â”€â”€â”€â”€â”€ */

    /**
     * Maps FpML {@code <cashSettlement>} (inside earlyTermination / optionalEarlyTermination)
     * into CDM {@link SettlementTerms} with settlementType=Cash.
     */
    static SettlementTerms mapCashSettlementToSettlementTerms(Element cashEl) {
        if (cashEl == null) return null;

        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        stb.setSettlementType(SettlementTypeEnum.CASH);

        CashSettlementTerms.CashSettlementTermsBuilder cstb = CashSettlementTerms.builder();

        // Determine method and build ValuationMethod
        CashSettlementMethodEnum methodEnum = null;
        ValuationMethod valuationMethod = null;

        // FpML 5.10 style: <cashPriceMethod>
        Element cashPriceMethod = XmlUtils.child(cashEl, "cashPriceMethod");
        if (cashPriceMethod != null) {
            methodEnum = CashSettlementMethodEnum.CASH_PRICE_METHOD;
            String quotRateType = XmlUtils.childText(cashPriceMethod, "quotationRateType");
            if (quotRateType != null) {
                QuotationRateTypeEnum q = mapQuotationRateType(quotRateType);
                if (q != null) {
                    valuationMethod = ValuationMethod.builder().setQuotationMethod(q).build();
                }
            }
            String ccy = XmlUtils.childText(cashPriceMethod, "cashSettlementCurrency");
            if (ccy != null) {
                stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(ccy).build());
            }
        }

        // FpML 5.13 style: <collateralizedCashPriceMethod>
        Element collateralizedMethod = XmlUtils.child(cashEl, "collateralizedCashPriceMethod");
        if (collateralizedMethod != null) {
            methodEnum = CashSettlementMethodEnum.COLLATERALIZED_CASH_PRICE_METHOD;
            valuationMethod = buildValuationMethodFromCollateralized(collateralizedMethod);
            String ccy = XmlUtils.childText(collateralizedMethod, "cashSettlementCurrency");
            if (ccy != null) {
                stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(ccy).build());
            }
        }

        // FpML 5.13 style: <midMarketValuation>
        Element midMarketValuation = XmlUtils.child(cashEl, "midMarketValuation");
        if (midMarketValuation != null) {
            Element indicativeQuotations = XmlUtils.child(midMarketValuation, "indicativeQuotations");
            Element iqAlt = XmlUtils.child(midMarketValuation, "indicativeQuotationsAlternate");
            Element calcAgentDetermination = XmlUtils.child(midMarketValuation, "calculationAgentDetermination");
            Element firmQuotations = null;

            if (indicativeQuotations != null) {
                methodEnum = CashSettlementMethodEnum.MID_MARKET_INDICATIVE_QUOTATIONS;
                valuationMethod = buildValuationMethodFromIndicativeQuotations(indicativeQuotations);
                String ccy = XmlUtils.childText(indicativeQuotations, "cashSettlementCurrency");
                if (ccy != null) stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(ccy).build());
            } else if (iqAlt != null) {
                methodEnum = CashSettlementMethodEnum.MID_MARKET_INDICATIVE_QUOTATIONS_ALTERNATE;
                valuationMethod = buildValuationMethodFromIndicativeQuotations(iqAlt);
                String ccy = XmlUtils.childText(iqAlt, "cashSettlementCurrency");
                if (ccy != null) stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(ccy).build());
            } else if (calcAgentDetermination != null) {
                methodEnum = CashSettlementMethodEnum.MID_MARKET_CALCULATION_AGENT_DETERMINATION;
                valuationMethod = buildValuationMethodFromIndicativeQuotations(calcAgentDetermination);
                String ccy = XmlUtils.childText(calcAgentDetermination, "cashSettlementCurrency");
                if (ccy != null) stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(ccy).build());
            }
        }

        // FpML 5.13 style: <replacementValue>
        Element replacementValue = XmlUtils.child(cashEl, "replacementValue");
        if (replacementValue != null) {
            Element firmQuotations = XmlUtils.child(replacementValue, "firmQuotations");
            Element calcAgentDetermination = XmlUtils.child(replacementValue, "calculationAgentDetermination");

            if (firmQuotations != null) {
                methodEnum = CashSettlementMethodEnum.REPLACEMENT_VALUE_FIRM_QUOTATIONS;
                valuationMethod = buildValuationMethodFromFirmQuotations(firmQuotations);
                String ccy = XmlUtils.childText(firmQuotations, "cashSettlementCurrency");
                if (ccy != null) stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(ccy).build());
            } else if (calcAgentDetermination != null) {
                methodEnum = CashSettlementMethodEnum.REPLACEMENT_VALUE_CALCULATION_AGENT_DETERMINATION;
                valuationMethod = buildValuationMethodFromFirmQuotations(calcAgentDetermination);
                String ccy = XmlUtils.childText(calcAgentDetermination, "cashSettlementCurrency");
                if (ccy != null) stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(ccy).build());
            }
        }

        if (methodEnum != null) cstb.setCashSettlementMethod(methodEnum);
        if (valuationMethod != null) cstb.setValuationMethod(valuationMethod);

        // ValuationDate
        Element valDateEl = XmlUtils.child(cashEl, "cashSettlementValuationDate");
        if (valDateEl != null) {
            RelativeDateOffset rdo = buildRelativeDateOffset(valDateEl);
            if (rdo != null) {
                cstb.setValuationDate(ValuationDate.builder().setValuationDate(rdo).build());
            }
        }

        // ValuationTime
        Element valTime = XmlUtils.child(cashEl, "cashSettlementValuationTime");
        if (valTime != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(valTime);
            if (bct != null) cstb.setValuationTime(bct);
        }

        // externalKey from id
        String id = cashEl.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            cstb.setMeta(MetaFields.builder().setExternalKey(id).build());
        }

        stb.addCashSettlementTerms(cstb.build());
        return stb.build();
    }

    /* â”€â”€â”€â”€â”€ ValuationMethod builders for different FpML method shapes â”€â”€â”€â”€â”€ */

    private static ValuationMethod buildValuationMethodFromCollateralized(Element method) {
        ValuationMethod.ValuationMethodBuilder b = ValuationMethod.builder();

        // settlementRateSource
        Element settlementRateSource = XmlUtils.child(method, "settlementRateSource");
        Element infoSource = settlementRateSource == null ? null
                : XmlUtils.child(settlementRateSource, "informationSource");
        if (infoSource != null) {
            InformationSource.InformationSourceBuilder isb = InformationSource.builder();
            String rateSource = XmlUtils.childText(infoSource, "rateSource");
            if (rateSource != null) {
                InformationProviderEnum provider = mapInfoProvider(rateSource);
                FieldWithMetaInformationProviderEnum.FieldWithMetaInformationProviderEnumBuilder pb =
                        FieldWithMetaInformationProviderEnum.builder();
                if (provider != null) pb.setValue(provider);
                isb.setSourceProvider(pb.build());
            }
            b.setValuationSource(ValuationSource.builder()
                    .setInformationSource(FxSpotRateSource.builder().setPrimarySource(isb.build()).build())
                    .build());
        }

        // quotationRateType
        String quotRateType = XmlUtils.childText(method, "quotationRateType");
        if (quotRateType != null) {
            QuotationRateTypeEnum q = mapQuotationRateType(quotRateType);
            if (q != null) b.setQuotationMethod(q);
        }

        // agreedDiscountRate â†’ cashCollateralValuationMethod
        String agreedDiscount = XmlUtils.childText(method, "agreedDiscountRate");
        if (agreedDiscount != null) {
            b.setCashCollateralValuationMethod(CashCollateralValuationMethod.builder()
                    .setAgreedDiscountRate(FieldWithMetaString.builder().setValue(agreedDiscount).build())
                    .build());
        }

        return b.build();
    }

    private static ValuationMethod buildValuationMethodFromIndicativeQuotations(Element method) {
        ValuationMethod.ValuationMethodBuilder b = ValuationMethod.builder();

        CashCollateralValuationMethod.CashCollateralValuationMethodBuilder ccvm =
                CashCollateralValuationMethod.builder();
        boolean hasCcvm = false;

        String applicableCsa = XmlUtils.childText(method, "applicableCsa");
        if (applicableCsa != null) {
            CsaTypeEnum csa = mapCsaType(applicableCsa);
            if (csa != null) { ccvm.setApplicableCsa(csa); hasCcvm = true; }
        }

        String cashCollCcy = XmlUtils.childText(method, "cashCollateralCurrency");
        if (cashCollCcy != null) { ccvm.setCashCollateralCurrency(cashCollCcy); hasCcvm = true; }

        String cashCollRate = XmlUtils.childText(method, "cashCollateralInterestRate");
        if (cashCollRate != null) {
            ccvm.setCashCollateralInterestRate(FieldWithMetaString.builder().setValue(cashCollRate).build());
            hasCcvm = true;
        }

        String agreedDiscount = XmlUtils.childText(method, "agreedDiscountRate");
        if (agreedDiscount != null) {
            ccvm.setAgreedDiscountRate(FieldWithMetaString.builder().setValue(agreedDiscount).build());
            hasCcvm = true;
        }

        if (hasCcvm) b.setCashCollateralValuationMethod(ccvm.build());
        return b.build();
    }

    private static ValuationMethod buildValuationMethodFromFirmQuotations(Element method) {
        ValuationMethod.ValuationMethodBuilder b = ValuationMethod.builder();

        CashCollateralValuationMethod.CashCollateralValuationMethodBuilder ccvm =
                CashCollateralValuationMethod.builder();
        boolean hasCcvm = false;

        String cashCollCcy = XmlUtils.childText(method, "cashCollateralCurrency");
        if (cashCollCcy != null) { ccvm.setCashCollateralCurrency(cashCollCcy); hasCcvm = true; }

        // protectedParty (may be repeated)
        for (Element pp : XmlUtils.children(method, "protectedParty")) {
            String det = XmlUtils.childText(pp, "partyDetermination");
            if (det != null) {
                PartyDeterminationEnum pde = mapPartyDetermination(det);
                if (pde != null) { ccvm.addProtectedParty(pde); hasCcvm = true; }
            }
        }

        String prescribedAdj = XmlUtils.childText(method, "prescribedDocumentationAdjustment");
        if (prescribedAdj != null) {
            ccvm.setPrescribedDocumentationAdjustment(Boolean.parseBoolean(prescribedAdj));
            hasCcvm = true;
        }

        if (hasCcvm) b.setCashCollateralValuationMethod(ccvm.build());
        return b.build();
    }

    /* â”€â”€â”€â”€â”€ buyer/seller mapping â”€â”€â”€â”€â”€ */

    private static void mapBuyerSeller(Element el, CancelableProvision.CancelableProvisionBuilder b, MappingContext ctx) {
        String buyerHref = hrefOf(XmlUtils.child(el, "buyerPartyReference"));
        String sellerHref = hrefOf(XmlUtils.child(el, "sellerPartyReference"));
        if (buyerHref != null || sellerHref != null) {
            CounterpartyRoleEnum buyerRole = roleFor(buyerHref, ctx);
            CounterpartyRoleEnum sellerRole = roleFor(sellerHref, ctx);
            b.setBuyer(buyerRole);
            b.setSeller(sellerRole);
        }
    }

    private static void mapBuyerSellerExtendible(Element el, ExtendibleProvision.ExtendibleProvisionBuilder b, MappingContext ctx) {
        String buyerHref = hrefOf(XmlUtils.child(el, "buyerPartyReference"));
        String sellerHref = hrefOf(XmlUtils.child(el, "sellerPartyReference"));
        if (buyerHref != null || sellerHref != null) {
            CounterpartyRoleEnum buyerRole = roleFor(buyerHref, ctx);
            CounterpartyRoleEnum sellerRole = roleFor(sellerHref, ctx);
            b.setBuyer(buyerRole);
            b.setSeller(sellerRole);
        }
    }

    /* â”€â”€â”€â”€â”€ Shared helper methods â”€â”€â”€â”€â”€ */

    static RelativeDateOffset buildRelativeDateOffset(Element el) {
        RelativeDateOffset.RelativeDateOffsetBuilder b = RelativeDateOffset.builder();
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
        Element centersRef = XmlUtils.child(el, "businessCentersReference");
        if (centers != null) {
            b.setBusinessCenters(DateMapper.buildBusinessCenters(centers));
        } else if (centersRef != null) {
            b.setBusinessCentersReference(ReferenceWithMetaBusinessCenters.builder()
                    .setExternalReference(centersRef.getAttribute("href")).build());
        }
        Element drt = XmlUtils.child(el, "dateRelativeTo");
        if (drt != null) {
            b.setDateRelativeTo(ReferenceWithMetaDate.builder()
                    .setExternalReference(drt.getAttribute("href")).build());
        }
        return b.build();
    }

    static RelativeDates buildRelativeDates(Element el) {
        RelativeDates.RelativeDatesBuilder b = RelativeDates.builder();
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
        Element centersRef = XmlUtils.child(el, "businessCentersReference");
        if (centers != null) {
            b.setBusinessCenters(DateMapper.buildBusinessCenters(centers));
        } else if (centersRef != null) {
            b.setBusinessCentersReference(ReferenceWithMetaBusinessCenters.builder()
                    .setExternalReference(centersRef.getAttribute("href")).build());
        }
        Element drt = XmlUtils.child(el, "dateRelativeTo");
        if (drt != null) {
            b.setDateRelativeTo(ReferenceWithMetaDate.builder()
                    .setExternalReference(drt.getAttribute("href")).build());
        }
        return b.build();
    }

    static BusinessCenterTime buildBusinessCenterTime(Element el) {
        if (el == null) return null;
        BusinessCenterTime.BusinessCenterTimeBuilder b = BusinessCenterTime.builder();
        String hmt = XmlUtils.childText(el, "hourMinuteTime");
        if (hmt != null) {
            try { b.setHourMinuteTime(LocalTime.parse(hmt)); }
            catch (Exception ignored) {}
        }
        String bc = XmlUtils.childText(el, "businessCenter");
        if (bc != null) {
            FieldWithMetaBusinessCenterEnum.FieldWithMetaBusinessCenterEnumBuilder bcb =
                    FieldWithMetaBusinessCenterEnum.builder();
            try { bcb.setValue(BusinessCenterEnum.valueOf(bc)); } catch (Exception ignored) {}
            b.setBusinessCenter(bcb.build());
        }
        return b.build();
    }

    static AdjustableDates buildAdjustableDates(Element fpml) {
        AdjustableDates.AdjustableDatesBuilder b = AdjustableDates.builder();
        for (Element d : XmlUtils.children(fpml, "unadjustedDate")) {
            b.addUnadjustedDate(DateMapper.parse(d.getTextContent().trim()));
        }
        BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(XmlUtils.child(fpml, "dateAdjustments"));
        if (bda != null) b.setDateAdjustments(bda);
        return b.build();
    }

    private static QuotationRateTypeEnum mapQuotationRateType(String s) {
        if (s == null) return null;
        try { return QuotationRateTypeEnum.fromDisplayName(s); } catch (Exception ignored) {}
        try { return QuotationRateTypeEnum.valueOf(s.toUpperCase()); } catch (Exception ignored) {}
        return null;
    }

    private static InformationProviderEnum mapInfoProvider(String s) {
        if (s == null) return null;
        try { return InformationProviderEnum.fromDisplayName(s); } catch (Exception ignored) {}
        try { return InformationProviderEnum.valueOf(s.toUpperCase()); } catch (Exception ignored) {}
        return null;
    }

    private static CsaTypeEnum mapCsaType(String s) {
        if (s == null) return null;
        try { return CsaTypeEnum.fromDisplayName(s); } catch (Exception ignored) {}
        try { return CsaTypeEnum.valueOf(s.toUpperCase().replace(" ", "_")); } catch (Exception ignored) {}
        return null;
    }

    private static PartyDeterminationEnum mapPartyDetermination(String s) {
        if (s == null) return null;
        try { return PartyDeterminationEnum.fromDisplayName(s); } catch (Exception ignored) {}
        try { return PartyDeterminationEnum.valueOf(s.toUpperCase().replace(" ", "_")); } catch (Exception ignored) {}
        return null;
    }

    private static String hrefOf(Element el) {
        return el == null ? null : el.getAttribute("href");
    }

    private static CounterpartyRoleEnum roleFor(String partyHref, MappingContext ctx) {
        if (partyHref == null || ctx == null) return null;
        Integer order = ctx.partyOrder.get(partyHref);
        if (order == null) return null;
        return order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
    }
}
