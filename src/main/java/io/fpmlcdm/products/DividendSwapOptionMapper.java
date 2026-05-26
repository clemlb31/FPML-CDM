package io.fpmlcdm.products;

import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.math.UnitType;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.party.AncillaryParty;
import cdm.base.staticdata.party.Account;
import cdm.base.staticdata.party.BuyerSeller;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.PayerReceiver;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.ContractDetails;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.event.common.Transfer;
import cdm.event.common.TransferState;
import cdm.observable.asset.Cash;
import cdm.observable.asset.PriceQuantity;
import cdm.product.common.settlement.SettlementTerms;
import cdm.product.common.settlement.SettlementTypeEnum;
import cdm.product.common.settlement.TransferExpression;
import cdm.product.template.EconomicTerms;
import cdm.product.template.ExerciseTerms;
import cdm.product.template.ExpirationTimeTypeEnum;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.OptionExerciseStyleEnum;
import cdm.product.template.OptionPayout;
import cdm.product.template.OptionTypeEnum;
import cdm.product.template.Payout;
import cdm.product.template.Product;
import cdm.product.template.TradeLot;
import cdm.product.template.Underlier;
import cdm.base.staticdata.asset.common.Asset;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.AccountMapper;
import io.fpmlcdm.common.CalculationAgentMapper;
import io.fpmlcdm.common.ContractDetailsMapper;
import io.fpmlcdm.common.DateMapper;
import io.fpmlcdm.common.IdentifierMapper;
import io.fpmlcdm.common.MappingContext;
import io.fpmlcdm.common.PartyMapper;
import io.fpmlcdm.common.PartyRoleMapper;
import io.fpmlcdm.common.TransferMapper;
import io.fpmlcdm.common.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps FpML {@code <dividendSwapOptionTransactionSupplement>} into CDM TradeState.
 * Produces an OptionPayout whose underlier is the inner dividend swap (built as a nested
 * NonTransferableProduct via {@link DividendSwapMapper}).
 */
public class DividendSwapOptionMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element dsOption = XmlUtils.child(trade, "dividendSwapOptionTransactionSupplement");
        Element supplement = XmlUtils.child(dsOption, "dividendSwapTransactionSupplement");

        // Determine PARTY_1 = payer of dividend leg (inside supplement), same as plain dividend swap
        Element dividendLeg = supplement != null ? XmlUtils.child(supplement, "dividendLeg") : null;
        if (dividendLeg != null) {
            Element dlPayer = XmlUtils.child(dividendLeg, "payerPartyReference");
            if (dlPayer != null) {
                assignRoles(dlPayer.getAttribute("href"), ctx);
            }
        }

        // Buyer/seller of the option
        Element buyerRef = XmlUtils.child(dsOption, "buyerPartyReference");
        Element sellerRef = XmlUtils.child(dsOption, "sellerPartyReference");
        String buyerHref = buyerRef != null ? buyerRef.getAttribute("href") : null;
        String sellerHref = sellerRef != null ? sellerRef.getAttribute("href") : null;
        CounterpartyRoleEnum buyerRole = roleFor(buyerHref, ctx);
        CounterpartyRoleEnum sellerRole = roleFor(sellerHref, ctx);

        // Option type
        String optionTypeStr = XmlUtils.childText(dsOption, "optionType");
        OptionTypeEnum optionType = "Put".equals(optionTypeStr) ? OptionTypeEnum.PUT : OptionTypeEnum.CALL;

        // Exercise terms
        Element equityExercise = XmlUtils.child(dsOption, "equityExercise");
        ExerciseTerms exerciseTerms = buildExerciseTerms(equityExercise);

        // Settlement terms
        SettlementTerms settlementTerms = buildSettlementTerms(equityExercise);

        // Build inner dividend swap as nested NonTransferableProduct
        NonTransferableProduct innerProduct = null;
        if (supplement != null) {
            EconomicTerms innerEcon = DividendSwapMapper.buildInnerEconomicTerms(supplement, ctx);
            NonTransferableProduct.NonTransferableProductBuilder innerB =
                    NonTransferableProduct.builder().setEconomicTerms(innerEcon);
            for (ProductTaxonomy tax : DividendSwapMapper.buildInnerTaxonomy(supplement)) {
                innerB.addTaxonomy(tax);
            }
            innerProduct = innerB.build();
        }

        // OptionPayout
        OptionPayout.OptionPayoutBuilder op = OptionPayout.builder()
                .setBuyerSeller(BuyerSeller.builder()
                        .setBuyer(buyerRole)
                        .setSeller(sellerRole)
                        .build())
                .setPayerReceiver(PayerReceiver.builder()
                        .setPayer(sellerRole)
                        .setReceiver(buyerRole)
                        .build())
                .setOptionType(optionType);

        if (settlementTerms != null) op.setSettlementTerms(settlementTerms);
        if (exerciseTerms != null) op.setExerciseTerms(exerciseTerms);
        if (innerProduct != null) {
            Product productWrapper = Product.builder().setNonTransferableProduct(innerProduct).build();
            op.setUnderlier(Underlier.builder().setProduct(productWrapper).build());
        }

        Payout optionPayout = Payout.builder().setOptionPayout(op.build()).build();

        // Outer economic terms
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder().addPayout(optionPayout);
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Outer product (option taxonomy)
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        for (ProductTaxonomy tax : buildOuterTaxonomy(supplement)) {
            ntp.addTaxonomy(tax);
        }

        // TradeLot from inner dividend swap's priceQuantities
        List<PriceQuantity> innerPQs = supplement != null
                ? DividendSwapMapper.buildInnerTradeLotPriceQuantities(supplement)
                : List.of();
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(innerPQs).build();

        // Counterparties
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

        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

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

        // Equity premium → transferHistory
        for (Element premium : XmlUtils.children(dsOption, "equityPremium")) {
            TransferState ts = buildPremiumTransfer(premium);
            if (ts != null) tsBuilder.addTransferHistory(ts);
        }
        // Other-party payments at trade level
        for (TransferState ts : TransferMapper.map(trade, null)) {
            tsBuilder.addTransferHistory(ts);
        }

        return tsBuilder.build();
    }

    private ExerciseTerms buildExerciseTerms(Element equityExercise) {
        if (equityExercise == null) return null;
        ExerciseTerms.ExerciseTermsBuilder etb = ExerciseTerms.builder();

        Element europeanEx = XmlUtils.child(equityExercise, "equityEuropeanExercise");
        Element americanEx = XmlUtils.child(equityExercise, "equityAmericanExercise");
        Element bermudanEx = XmlUtils.child(equityExercise, "equityBermudaExercise");

        Element exerciseEl = null;
        if (europeanEx != null) {
            etb.setStyle(OptionExerciseStyleEnum.EUROPEAN);
            exerciseEl = europeanEx;
        } else if (americanEx != null) {
            etb.setStyle(OptionExerciseStyleEnum.AMERICAN);
            exerciseEl = americanEx;
        } else if (bermudanEx != null) {
            etb.setStyle(OptionExerciseStyleEnum.BERMUDA);
            exerciseEl = bermudanEx;
        }

        if (exerciseEl != null) {
            Element expirationDate = XmlUtils.child(exerciseEl, "expirationDate");
            if (expirationDate != null) {
                Element adjDate = XmlUtils.child(expirationDate, "adjustableDate");
                if (adjDate != null) {
                    AdjustableOrRelativeDate aord = DateMapper.adjustableOrRelative(adjDate);
                    if (aord != null) {
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
            String equityExpTimeType = XmlUtils.childText(exerciseEl, "equityExpirationTimeType");
            if (equityExpTimeType != null) {
                etb.setExpirationTimeType(mapExpirationTimeType(equityExpTimeType));
            }
        }

        // Note: <automaticExercise>true</automaticExercise> is NOT emitted as an
        // exerciseProcedure for dividend swap options — the reference dataset omits it.

        // ExpirationTimeType from equityValuation/valuationTimeType
        Element valuation = XmlUtils.child(equityExercise, "equityValuation");
        if (valuation != null) {
            String vtt = XmlUtils.childText(valuation, "valuationTimeType");
            if (vtt != null) {
                etb.setExpirationTimeType(mapExpirationTimeType(vtt));
            }
        }

        return etb.build();
    }

    private ExpirationTimeTypeEnum mapExpirationTimeType(String text) {
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
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        String settlementType = XmlUtils.childText(equityExercise, "settlementType");
        if ("Cash".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.CASH);
        else if ("Physical".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.PHYSICAL);
        else if ("Election".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.ELECTION);
        else if ("CashOrPhysical".equals(settlementType)) stb.setSettlementType(SettlementTypeEnum.CASH_OR_PHYSICAL);
        String settlementCurrency = XmlUtils.childText(equityExercise, "settlementCurrency");
        if (settlementCurrency != null) {
            stb.setSettlementCurrency(FieldWithMetaString.builder().setValue(settlementCurrency).build());
        }
        return stb.build();
    }

    private List<ProductTaxonomy> buildOuterTaxonomy(Element supplement) {
        List<ProductTaxonomy> out = new ArrayList<>();
        boolean isIndex = false;
        if (supplement != null) {
            Element dl = XmlUtils.child(supplement, "dividendLeg");
            if (dl != null) {
                Element underlyer = XmlUtils.child(dl, "underlyer");
                if (underlyer != null) {
                    Element su = XmlUtils.child(underlyer, "singleUnderlyer");
                    if (su != null) {
                        isIndex = XmlUtils.child(su, "index") != null;
                    }
                }
            }
        }
        String qualifier = isIndex
                ? "EquityOption_ParameterReturnDividend_Index"
                : "EquityOption_ParameterReturnDividend_SingleName";
        out.add(ProductTaxonomy.builder()
                .setSource(TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build());
        return out;
    }

    private TransferState buildPremiumTransfer(Element premium) {
        Transfer.TransferBuilder tb = Transfer.builder();

        Element amtEl = XmlUtils.child(premium, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
        if (amount != null && ccy != null) {
            tb.setQuantity(cdm.base.math.NonNegativeQuantity.builder()
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
            cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate.builder();
            String unadj = XmlUtils.childText(payDate, "unadjustedDate");
            if (unadj != null) sdb.setUnadjustedDate(DateMapper.parse(unadj));
            String adj = XmlUtils.childText(payDate, "adjustedDate");
            if (adj != null) {
                sdb.setAdjustedDate(FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(adj)).build());
            }
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                    XmlUtils.child(payDate, "dateAdjustments"));
            if (bda != null) sdb.setDateAdjustments(bda);
            tb.setSettlementDate(sdb.build());
        }

        Element payerRef = XmlUtils.child(premium, "payerPartyReference");
        Element receiverRef = XmlUtils.child(premium, "receiverPartyReference");
        if (payerRef != null || receiverRef != null) {
            cdm.base.staticdata.party.PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr =
                    cdm.base.staticdata.party.PartyReferencePayerReceiver.builder();
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
                .setPriceTransfer(cdm.observable.asset.FeeTypeEnum.PREMIUM)
                .build());

        return TransferState.builder().setTransfer(tb.build()).build();
    }

    private CounterpartyRoleEnum roleFor(String href, MappingContext ctx) {
        if (href == null) return CounterpartyRoleEnum.PARTY_1;
        Integer order = ctx.partyOrder.get(href);
        return (order != null && order == 0) ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
    }

    private void assignRoles(String payerHref, MappingContext ctx) {
        if (payerHref == null) return;
        Map<String, Integer> newOrder = new LinkedHashMap<>();
        newOrder.put(payerHref, 0);
        int idx = 1;
        for (String id : ctx.partyOrder.keySet()) {
            if (!id.equals(payerHref)) {
                newOrder.put(id, idx++);
            }
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }
}
