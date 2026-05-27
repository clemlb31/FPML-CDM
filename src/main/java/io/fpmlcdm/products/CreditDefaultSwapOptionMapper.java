package io.fpmlcdm.products;

import cdm.base.datetime.*;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.FeeTypeEnum;
import cdm.product.common.settlement.*;
import cdm.product.template.*;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <creditDefaultSwapOption>} into CDM TradeState with an OptionPayout
 * wrapping the underlying CDS product.
 */
public class CreditDefaultSwapOptionMapper implements ProductMapper {

    private final CreditDefaultSwapMapper cdsMapper = new CreditDefaultSwapMapper();

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element cdso = XmlUtils.child(trade, "creditDefaultSwapOption");
        if (cdso == null) return null;

        // Buyer/seller
        String buyerHref = href(XmlUtils.child(cdso, "buyerPartyReference"));
        String sellerHref = href(XmlUtils.child(cdso, "sellerPartyReference"));

        // Assign PARTY_1 = buyer
        if (buyerHref != null) {
            Map<String, Integer> newOrder = new LinkedHashMap<>();
            newOrder.put(buyerHref, 0);
            int idx = 1;
            for (String pid : ctx.partyOrder.keySet()) {
                if (!pid.equals(buyerHref)) newOrder.put(pid, idx++);
            }
            ctx.partyOrder.clear();
            ctx.partyOrder.putAll(newOrder);
        }

        CounterpartyRoleEnum buyerRole = roleFor(buyerHref, ctx);
        CounterpartyRoleEnum sellerRole = roleFor(sellerHref, ctx);

        // Build the underlying CDS as a full TradeState, then extract the product.
        // The CDS mapper looks for <creditDefaultSwap> directly under <trade>,
        // so it will find the one nested inside <creditDefaultSwapOption>.
        NonTransferableProduct underlierProduct = null;
        List<cdm.observable.asset.PriceQuantity> pqs = new ArrayList<>();
        try {
            TradeState cdsTs = cdsMapper.map(doc, trade);
            if (cdsTs != null && cdsTs.getTrade() != null) {
                underlierProduct = cdsTs.getTrade().getProduct();
                if (cdsTs.getTrade().getTradeLot() != null && !cdsTs.getTrade().getTradeLot().isEmpty()) {
                    pqs = new ArrayList<>(cdsTs.getTrade().getTradeLot().get(0).getPriceQuantity());
                }
            }
        } catch (Exception e) {
            // CDS mapper may fail for some option structures; continue with null underlier
        }

        // Build OptionPayout
        OptionPayout.OptionPayoutBuilder op = OptionPayout.builder();
        op.setBuyerSeller(BuyerSeller.builder().setBuyer(buyerRole).setSeller(sellerRole).build());
        op.setPayerReceiver(PayerReceiver.builder().setPayer(sellerRole).setReceiver(buyerRole).build());

        // optionType
        String optionTypeStr = XmlUtils.childText(cdso, "optionType");
        if ("Put".equalsIgnoreCase(optionTypeStr)) op.setOptionType(OptionTypeEnum.PUT);
        else if ("Call".equalsIgnoreCase(optionTypeStr)) op.setOptionType(OptionTypeEnum.CALL);
        else if ("Payer".equalsIgnoreCase(optionTypeStr)) op.setOptionType(OptionTypeEnum.PAYER);
        else if ("Receiver".equalsIgnoreCase(optionTypeStr)) op.setOptionType(OptionTypeEnum.RECEIVER);

        // Underlier
        if (underlierProduct != null) {
            op.setUnderlier(Underlier.builder()
                    .setProduct(Product.builder().setNonTransferableProduct(underlierProduct).build())
                    .build());
        }

        // Exercise terms
        op.setExerciseTerms(buildExerciseTerms(cdso, buyerHref, sellerHref));

        // Settlement terms
        SettlementTerms st = buildSettlementTerms(cdso);
        if (st != null) op.setSettlementTerms(st);

        // Strike
        Element strikeEl = XmlUtils.child(cdso, "strike");
        if (strikeEl != null) {
            Element strikeRef = XmlUtils.child(strikeEl, "strikeReference");
            Element strikeSpread = XmlUtils.child(strikeEl, "spread");
            if (strikeSpread != null) {
                String spreadVal = strikeSpread.getTextContent().trim();
                op.setStrike(OptionStrike.builder()
                        .setStrikePrice(cdm.observable.asset.Price.builder()
                                .setValue(new BigDecimal(spreadVal))
                                .setPriceType(cdm.observable.asset.PriceTypeEnum.INTEREST_RATE)
                                .build())
                        .build());
            }
        }

        Payout optionPayout = Payout.builder().setOptionPayout(op.build()).build();

        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(optionPayout);

        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Product
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        ntp.addTaxonomy(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier("CreditDefaultSwap_Option")
                .build());

        // TradeLot
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(pqs).build();

        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);
        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        // Trade identifiers
        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        // Trade date
        FieldWithMetaDate tradeDate = null;
        if (tradeHeader != null) {
            Element tradeDateEl = XmlUtils.child(tradeHeader, "tradeDate");
            if (tradeDateEl != null) {
                FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()));
                String tdId = tradeDateEl.getAttribute("id");
                if (tdId != null && !tdId.isEmpty()) {
                    tdb.setMeta(MetaFields.builder().setExternalKey(tdId).build());
                }
                tradeDate = tdb.build();
            }
        }

        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        partyRoles.forEach(t::addPartyRole);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());

        // Premium
        Element premium = XmlUtils.child(cdso, "premium");
        if (premium != null) {
            TransferState premTs = buildPremiumTransfer(premium);
            if (premTs != null) tsBuilder.addTransferHistory(premTs);
        }

        return tsBuilder.build();
    }

    private ExerciseTerms buildExerciseTerms(Element cdso, String buyerHref, String sellerHref) {
        ExerciseTerms.ExerciseTermsBuilder b = ExerciseTerms.builder();
        Element euro = XmlUtils.child(cdso, "europeanExercise");
        Element amer = XmlUtils.child(cdso, "americanExercise");
        Element exercise = euro != null ? euro : amer;
        if (exercise == null) return b.build();

        b.setStyle(euro != null ? OptionExerciseStyleEnum.EUROPEAN : OptionExerciseStyleEnum.AMERICAN);

        Element expDate = XmlUtils.child(exercise, "expirationDate");
        if (expDate != null) {
            Element adj = XmlUtils.child(expDate, "adjustableDate");
            if (adj != null) {
                AdjustableDate ad = DateMapper.adjustable(adj);
                if (ad != null) b.addExpirationDate(AdjustableOrRelativeDate.builder().setAdjustableDate(ad).build());
            }
        }

        Element expTime = XmlUtils.child(exercise, "expirationTime");
        if (expTime != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(expTime);
            if (bct != null) b.setExpirationTime(bct);
            b.setExpirationTimeType(ExpirationTimeTypeEnum.SPECIFIC_TIME);
        }

        // Exercise procedure
        Element procEl = XmlUtils.child(cdso, "exerciseProcedure");
        if (procEl != null) {
            ExerciseProcedure.ExerciseProcedureBuilder epb = ExerciseProcedure.builder();
            Element manual = XmlUtils.child(procEl, "manualExercise");
            if (manual != null && XmlUtils.child(manual, "exerciseNotice") != null) {
                ManualExercise.ManualExerciseBuilder mb = ManualExercise.builder();
                Element notice = XmlUtils.child(manual, "exerciseNotice");
                ExerciseNotice.ExerciseNoticeBuilder enb = ExerciseNotice.builder();
                Element pref = XmlUtils.child(notice, "partyReference");
                if (pref != null) {
                    String h = pref.getAttribute("href");
                    if (h.equals(buyerHref)) enb.setExerciseNoticeGiver(ExerciseNoticeGiverEnum.BUYER);
                    else if (h.equals(sellerHref)) enb.setExerciseNoticeGiver(ExerciseNoticeGiverEnum.SELLER);
                }
                String bc = XmlUtils.childText(notice, "businessCenter");
                if (bc != null) {
                    try { enb.setBusinessCenter(FieldWithMetaBusinessCenterEnum.builder()
                            .setValue(BusinessCenterEnum.valueOf(bc)).build()); }
                    catch (Exception ignored) {}
                }
                mb.setExerciseNotice(enb.build());
                epb.setManualExercise(mb.build());
            }
            String followUp = XmlUtils.childText(procEl, "followUpConfirmation");
            if (followUp != null) epb.setFollowUpConfirmation(Boolean.parseBoolean(followUp));
            b.setExerciseProcedure(epb.build());
        }

        String id = exercise.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return b.build();
    }

    private SettlementTerms buildSettlementTerms(Element cdso) {
        String settlementType = XmlUtils.childText(cdso, "settlementType");
        if (settlementType == null) return null;
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        if ("Physical".equalsIgnoreCase(settlementType)) stb.setSettlementType(SettlementTypeEnum.PHYSICAL);
        else if ("Cash".equalsIgnoreCase(settlementType)) stb.setSettlementType(SettlementTypeEnum.CASH);
        String ccy = XmlUtils.childText(cdso, "settlementCurrency");
        if (ccy == null) {
            Element innerCds = XmlUtils.child(cdso, "creditDefaultSwap");
            if (innerCds != null) ccy = XmlUtils.pathText(innerCds, "feeLeg", "periodicPayment", "fixedAmountCalculation",
                    "calculationAmount", "currency");
        }
        return stb.build();
    }

    private TransferState buildPremiumTransfer(Element premium) {
        Transfer.TransferBuilder tb = Transfer.builder();
        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
        if (amount != null && ccy != null) {
            tb.setQuantity(cdm.base.math.NonNegativeQuantity.builder()
                    .setValue(new BigDecimal(amount))
                    .setUnit(cdm.base.math.UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build()).build())
                    .build());
            tb.setAsset(cdm.base.staticdata.asset.common.Asset.builder()
                    .setCash(cdm.base.staticdata.asset.common.Cash.builder()
                            .addIdentifier(cdm.base.staticdata.asset.common.AssetIdentifier.builder()
                                    .setIdentifier(FieldWithMetaString.builder().setValue(ccy).build())
                                    .setIdentifierType(cdm.base.staticdata.asset.common.AssetIdTypeEnum.CURRENCY_CODE)
                                    .build())
                            .build())
                    .build());
        }
        Element payDate = XmlUtils.child(premium, "paymentDate");
        if (payDate != null) {
            AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    AdjustableOrAdjustedOrRelativeDate.builder();
            String unadj = XmlUtils.childText(payDate, "unadjustedDate");
            if (unadj != null) sdb.setUnadjustedDate(DateMapper.parse(unadj));
            String adj = XmlUtils.childText(payDate, "adjustedDate");
            if (adj != null) sdb.setAdjustedDate(FieldWithMetaDate.builder().setValue(DateMapper.parse(adj)).build());
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(XmlUtils.child(payDate, "dateAdjustments"));
            if (bda != null) sdb.setDateAdjustments(bda);
            tb.setSettlementDate(sdb.build());
        }
        Element payerRef = XmlUtils.child(premium, "payerPartyReference");
        Element receiverRef = XmlUtils.child(premium, "receiverPartyReference");
        if (payerRef != null || receiverRef != null) {
            PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr = PartyReferencePayerReceiver.builder();
            if (payerRef != null) pr.setPayerPartyReference(ReferenceWithMetaParty.builder()
                    .setExternalReference(payerRef.getAttribute("href")).build());
            if (receiverRef != null) pr.setReceiverPartyReference(ReferenceWithMetaParty.builder()
                    .setExternalReference(receiverRef.getAttribute("href")).build());
            tb.setPayerReceiver(pr.build());
        }
        String premType = XmlUtils.childText(premium, "premiumType");
        tb.setTransferExpression(TransferExpression.builder()
                .setPriceTransfer(FeeTypeEnum.PREMIUM).build());
        return TransferState.builder().setTransfer(tb.build()).build();
    }

    private static BusinessCenterTime buildBusinessCenterTime(Element el) {
        BusinessCenterTime.BusinessCenterTimeBuilder b = BusinessCenterTime.builder();
        String hmt = XmlUtils.childText(el, "hourMinuteTime");
        if (hmt != null) {
            try { b.setHourMinuteTime(LocalTime.parse(hmt)); } catch (Exception ignored) {}
        }
        String bc = XmlUtils.childText(el, "businessCenter");
        if (bc != null) {
            try { b.setBusinessCenter(FieldWithMetaBusinessCenterEnum.builder()
                    .setValue(BusinessCenterEnum.valueOf(bc)).build()); }
            catch (Exception ignored) {}
        }
        return b.build();
    }

    private static String href(Element el) {
        return el == null ? null : el.getAttribute("href");
    }

    private static CounterpartyRoleEnum roleFor(String partyHref, MappingContext ctx) {
        if (partyHref == null) return null;
        Integer order = ctx.partyOrder.get(partyHref);
        if (order == null) return null;
        return order == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
    }
}
