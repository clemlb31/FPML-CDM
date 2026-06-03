package io.fpmlcdm.products;

import cdm.base.datetime.*;
import cdm.base.math.NonNegativeQuantity;
import cdm.base.math.UnitType;
import cdm.base.staticdata.asset.common.*;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <dividendSwapOptionTransactionSupplement>} into CDM TradeState with an
 * OptionPayout wrapping the underlying dividend-swap product.
 */
public class DividendSwapOptionMapper implements ProductMapper {

    private final DividendSwapMapper innerMapper = new DividendSwapMapper();

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element optTx = XmlUtils.child(trade, "dividendSwapOptionTransactionSupplement");
        if (optTx == null) return null;

        // Buyer/seller
        String buyerHref = href(XmlUtils.child(optTx, "buyerPartyReference"));
        String sellerHref = href(XmlUtils.child(optTx, "sellerPartyReference"));

        // Reassign PARTY_1 = buyer FIRST, then build the inner product using the same ordering
        // so the inner payouts use the same Party1/Party2 labels as the outer trade.
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

        NonTransferableProduct underlierProduct = null;
        List<cdm.observable.asset.PriceQuantity> pqs = new ArrayList<>();
        try {
            DividendSwapMapper.ProductResult pr = innerMapper.buildProduct(trade, ctx);
            if (pr != null) {
                underlierProduct = pr.product;
                pqs = new ArrayList<>(pr.priceQuantities);
            }
        } catch (Exception ignored) {}

        CounterpartyRoleEnum buyerRole = roleFor(buyerHref, ctx);
        CounterpartyRoleEnum sellerRole = roleFor(sellerHref, ctx);

        // Build OptionPayout
        OptionPayout.OptionPayoutBuilder op = OptionPayout.builder();
        op.setBuyerSeller(BuyerSeller.builder().setBuyer(buyerRole).setSeller(sellerRole).build());
        op.setPayerReceiver(PayerReceiver.builder().setPayer(sellerRole).setReceiver(buyerRole).build());

        // Note: div option reference does not include optionType in the OptionPayout — omit it.

        // Underlier
        if (underlierProduct != null) {
            op.setUnderlier(Underlier.builder()
                    .setProduct(Product.builder().setNonTransferableProduct(underlierProduct).build())
                    .build());
        }

        // Exercise terms from equityExercise (European)
        Element equityExercise = XmlUtils.child(optTx, "equityExercise");
        ExerciseTerms et = buildExerciseTerms(equityExercise);
        if (et != null) op.setExerciseTerms(et);

        // Settlement terms (from equityExercise)
        SettlementTerms st = buildSettlementTerms(equityExercise);

        // Note: dividend-swap-option reference CDM omits clearingInstructions mapping entirely
        // (no physicalSettlementTerms, no ancillaryParty for the clearing org).
        if (st != null) op.setSettlementTerms(st);

        Payout optionPayout = Payout.builder().setOptionPayout(op.build()).build();

        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(optionPayout);

        // Calculation agent
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Outer taxonomy from inner: EquitySwap_ParameterReturnDividend_X → EquityOption_ParameterReturnDividend_X
        String outerQualifier = "EquityOption_ParameterReturnDividend";
        if (underlierProduct != null && underlierProduct.getTaxonomy() != null) {
            for (ProductTaxonomy pt : underlierProduct.getTaxonomy()) {
                String q = pt.getProductQualifier();
                if (q != null && q.startsWith("EquitySwap_ParameterReturn")) {
                    outerQualifier = q.replaceFirst("^EquitySwap_", "EquityOption_");
                }
            }
        }

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build())
                .addTaxonomy(ProductTaxonomy.builder()
                        .setSource(TaxonomySourceEnum.ISDA)
                        .setProductQualifier(outerQualifier)
                        .build());

        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(pqs).build();

        List<Counterparty> counterparties = SwapMapper.buildCounterparties(ctx);

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
                XmlUtils.child(trade, "documentation"), parties, ctx, trade);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsBuilder = TradeState.builder().setTrade(t.build());

        // equityPremium → transferHistory
        for (Element premium : XmlUtils.children(optTx, "equityPremium")) {
            TransferState ts = buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }

        return tsBuilder.build();
    }

    private ExerciseTerms buildExerciseTerms(Element equityExercise) {
        if (equityExercise == null) return null;
        ExerciseTerms.ExerciseTermsBuilder etb = ExerciseTerms.builder();
        Element europeanEx = XmlUtils.child(equityExercise, "equityEuropeanExercise");
        Element americanEx = XmlUtils.child(equityExercise, "equityAmericanExercise");
        Element exerciseEl = europeanEx != null ? europeanEx : americanEx;
        if (exerciseEl == null) return null;
        etb.setStyle(europeanEx != null ? OptionExerciseStyleEnum.EUROPEAN : OptionExerciseStyleEnum.AMERICAN);

        Element expirationDate = XmlUtils.child(exerciseEl, "expirationDate");
        if (expirationDate != null) {
            Element adjDate = XmlUtils.child(expirationDate, "adjustableDate");
            if (adjDate != null) {
                AdjustableDate ad = DateMapper.adjustable(adjDate);
                if (ad != null) {
                    AdjustableOrRelativeDate aord = AdjustableOrRelativeDate.builder()
                            .setAdjustableDate(ad).build();
                    String expId = expirationDate.getAttribute("id");
                    if (expId != null && !expId.isEmpty()) {
                        aord = aord.toBuilder()
                                .setMeta(MetaFields.builder().setExternalKey(expId).build())
                                .build();
                    }
                    etb.addExpirationDate(aord);
                }
            }
        }

        String expTimeType = XmlUtils.childText(exerciseEl, "equityExpirationTimeType");
        if (expTimeType != null) {
            ExpirationTimeTypeEnum mapped = mapExpirationTimeType(expTimeType);
            if (mapped != null) etb.setExpirationTimeType(mapped);
        }

        Element expTime = XmlUtils.child(exerciseEl, "equityExpirationTime");
        if (expTime != null) {
            BusinessCenterTime bct = buildBusinessCenterTime(expTime);
            if (bct != null) etb.setExpirationTime(bct);
        }

        return etb.build();
    }

    private static BusinessCenterTime buildBusinessCenterTime(Element el) {
        if (el == null) return null;
        BusinessCenterTime.BusinessCenterTimeBuilder b = BusinessCenterTime.builder();
        String hmt = XmlUtils.childText(el, "hourMinuteTime");
        if (hmt != null) {
            try { b.setHourMinuteTime(java.time.LocalTime.parse(hmt)); }
            catch (Exception ignored) {}
        }
        String bc = XmlUtils.childText(el, "businessCenter");
        if (bc != null) {
            try {
                b.setBusinessCenter(cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum.builder()
                        .setValue(BusinessCenterEnum.valueOf(bc)).build());
            } catch (Exception ignored) {}
        }
        return b.build();
    }

    private static ExpirationTimeTypeEnum mapExpirationTimeType(String text) {
        if (text == null) return null;
        return switch (text) {
            case "Close" -> ExpirationTimeTypeEnum.CLOSE;
            case "OSP" -> ExpirationTimeTypeEnum.OSP;
            case "SpecificTime" -> ExpirationTimeTypeEnum.SPECIFIC_TIME;
            case "XETRA" -> ExpirationTimeTypeEnum.XETRA;
            case "DerivativesClose" -> ExpirationTimeTypeEnum.DERIVATIVES_CLOSE;
            case "AsSpecifiedInMasterConfirmation" -> ExpirationTimeTypeEnum.AS_SPECIFIED_IN_MASTER_CONFIRMATION;
            default -> {
                try { yield ExpirationTimeTypeEnum.valueOf(text.toUpperCase()); }
                catch (IllegalArgumentException ignored) { yield null; }
            }
        };
    }

    private SettlementTerms buildSettlementTerms(Element equityExercise) {
        if (equityExercise == null) return null;
        String settlementType = XmlUtils.childText(equityExercise, "settlementType");
        String settlementCurrency = XmlUtils.childText(equityExercise, "settlementCurrency");
        if (settlementType == null && settlementCurrency == null) return null;
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        if (settlementType != null) {
            if ("Cash".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.CASH);
            else if ("Physical".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.PHYSICAL);
        }
        if (settlementCurrency != null) {
            stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCurrency).build());
        }
        return stb.build();
    }

    private TransferState buildPremiumTransfer(Element premium) {
        Transfer.TransferBuilder tb = Transfer.builder();
        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
        if (amount != null && ccy != null) {
            tb.setQuantity(NonNegativeQuantity.builder()
                    .setValue(new BigDecimal(amount))
                    .setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build()).build())
                    .build());
            tb.setAsset(Asset.builder()
                    .setCash(Cash.builder()
                            .addIdentifier(AssetIdentifier.builder()
                                    .setIdentifier(FieldWithMetaString.builder().setValue(ccy).build())
                                    .setIdentifierType(AssetIdTypeEnum.CURRENCY_CODE)
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
            if (adj != null) sdb.setAdjustedDate(FieldWithMetaDate.builder()
                    .setValue(DateMapper.parse(adj)).build());
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                    XmlUtils.child(payDate, "dateAdjustments"));
            if (bda != null) sdb.setDateAdjustments(bda);
            tb.setSettlementDate(sdb.build());
        }

        Element payerRef = XmlUtils.child(premium, "payerPartyReference");
        Element receiverRef = XmlUtils.child(premium, "receiverPartyReference");
        if (payerRef != null || receiverRef != null) {
            PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr =
                    PartyReferencePayerReceiver.builder();
            if (payerRef != null) {
                pr.setPayerPartyReference(ReferenceWithMetaParty.builder()
                        .setExternalReference(payerRef.getAttribute("href")).build());
            }
            if (receiverRef != null) {
                pr.setReceiverPartyReference(ReferenceWithMetaParty.builder()
                        .setExternalReference(receiverRef.getAttribute("href")).build());
            }
            tb.setPayerReceiver(pr.build());
        }

        tb.setTransferExpression(TransferExpression.builder()
                .setPriceTransfer(FeeTypeEnum.PREMIUM).build());
        return TransferState.builder().setTransfer(tb.build()).build();
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
