package io.fpmlcdm.products;

import cdm.base.datetime.*;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.*;
import cdm.base.staticdata.asset.common.metafields.FieldWithMetaAssetClassEnum;
import cdm.base.staticdata.party.*;
import cdm.event.common.*;
import cdm.observable.asset.*;
import cdm.observable.asset.metafields.FieldWithMetaObservable;
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
 * Maps FpML {@code <genericProduct>} into a CDM TradeState with a SettlementPayout.
 * Used primarily for bond forward representations.
 */
public class GenericProductMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element gp = XmlUtils.child(trade, "genericProduct");
        if (gp == null) return null;

        // Buyer/seller
        String buyerHref = href(XmlUtils.child(gp, "buyerPartyReference"));
        String sellerHref = href(XmlUtils.child(gp, "sellerPartyReference"));

        // PARTY_1 = buyer
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

        // EconomicTerms
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();

        // effectiveDate
        Element effDate = XmlUtils.child(gp, "effectiveDate");
        if (effDate != null) {
            AdjustableDate ad = buildAdjustableDate(effDate);
            if (ad != null) econ.setEffectiveDate(AdjustableOrRelativeDate.builder().setAdjustableDate(ad).build());
        }
        // terminationDate
        Element termDate = XmlUtils.child(gp, "terminationDate");
        if (termDate != null) {
            AdjustableDate ad = buildAdjustableDate(termDate);
            if (ad != null) econ.setTerminationDate(AdjustableOrRelativeDate.builder().setAdjustableDate(ad).build());
        }

        // settlementType → SettlementPayout
        String settlementType = XmlUtils.childText(gp, "settlementType");
        SettlementPayout.SettlementPayoutBuilder spb = SettlementPayout.builder();
        spb.setPayerReceiver(PayerReceiver.builder().setPayer(sellerRole).setReceiver(buyerRole).build());
        SettlementTerms.SettlementTermsBuilder stb = SettlementTerms.builder();
        if ("Physical".equalsIgnoreCase(settlementType)) stb.setSettlementType(SettlementTypeEnum.PHYSICAL);
        else if ("Cash".equalsIgnoreCase(settlementType)) stb.setSettlementType(SettlementTypeEnum.CASH);
        spb.setSettlementTerms(stb.build());
        econ.addPayout(Payout.builder().setSettlementPayout(spb.build()).build());

        // nonStandardisedTerms from partyTradeInformation
        Boolean nonStandard = readNonStandardFlag(tradeHeader);
        if (nonStandard != null) econ.setNonStandardisedTerms(nonStandard);

        // Build taxonomies
        List<ProductTaxonomy> taxonomies = new ArrayList<>();
        Element primaryAssetClassEl = XmlUtils.child(gp, "primaryAssetClass");
        if (primaryAssetClassEl != null) {
            String value = primaryAssetClassEl.getTextContent().trim();
            FieldWithMetaAssetClassEnum.FieldWithMetaAssetClassEnumBuilder ab =
                    FieldWithMetaAssetClassEnum.builder();
            try { ab.setValue(AssetClassEnum.fromDisplayName(value)); } catch (Exception ignored) {
                try { ab.setValue(AssetClassEnum.valueOf(value.toUpperCase())); } catch (Exception ignored2) {}
            }
            taxonomies.add(ProductTaxonomy.builder().setPrimaryAssetClass(ab.build()).build());
        }
        for (Element pt : XmlUtils.children(gp, "productType")) {
            String value = pt.getTextContent().trim();
            String scheme = pt.getAttribute("productTypeScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(value);
            if (scheme != null && !scheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            TaxonomySourceEnum src = mapTaxonomySource(scheme);
            taxonomies.add(ProductTaxonomy.builder().setSource(src).setValue(tv).build());
        }

        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
        taxonomies.forEach(ntp::addTaxonomy);
        ProductIdentifierMapper.map(gp).forEach(ntp::addIdentifier);

        // TradeLot with bond underlier
        TradeLot tradeLot = buildTradeLot(gp);

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

        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx, trade);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        partyRoles.forEach(t::addPartyRole);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        return TradeState.builder().setTrade(t.build()).build();
    }

    private static Boolean readNonStandardFlag(Element tradeHeader) {
        if (tradeHeader == null) return null;
        for (Element pti : XmlUtils.children(tradeHeader, "partyTradeInformation")) {
            String v = XmlUtils.childText(pti, "nonStandardTerms");
            if (v != null) return Boolean.parseBoolean(v);
        }
        return null;
    }

    private static TaxonomySourceEnum mapTaxonomySource(String scheme) {
        if (scheme == null) return TaxonomySourceEnum.OTHER;
        String s = scheme.toLowerCase();
        if (s.contains("iso10962")) return TaxonomySourceEnum.CFI;
        if (s.contains("emir-contract-type")) return TaxonomySourceEnum.EMIR;
        if (s.contains("product-taxonomy")) return TaxonomySourceEnum.ISDA;
        return TaxonomySourceEnum.OTHER;
    }

    private static AdjustableDate buildAdjustableDate(Element dateEl) {
        AdjustableDate.AdjustableDateBuilder b = AdjustableDate.builder();
        String unadj = XmlUtils.childText(dateEl, "unadjustedDate");
        if (unadj == null) return null;
        b.setUnadjustedDate(DateMapper.parse(unadj));
        BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(XmlUtils.child(dateEl, "dateAdjustments"));
        if (bda != null) b.setDateAdjustments(bda);
        return b.build();
    }

    private TradeLot buildTradeLot(Element gp) {
        PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder();

        // notional → quantity[0]
        Element notional = XmlUtils.child(gp, "notional");
        if (notional != null) {
            String ccy = XmlUtils.childText(notional, "currency");
            String amount = XmlUtils.childText(notional, "amount");
            if (amount != null) {
                NonNegativeQuantitySchedule.NonNegativeQuantityScheduleBuilder qsb =
                        NonNegativeQuantitySchedule.builder().setValue(new BigDecimal(amount));
                if (ccy != null) {
                    qsb.setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build())
                            .build());
                }
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(qsb.build())
                        .setMeta(QuantityMapper.locationMeta("quantity-1"))
                        .build());

                // Add a price entry with same currency (priceType=AssetPrice)
                cdm.observable.asset.PriceSchedule.PriceScheduleBuilder psb =
                        cdm.observable.asset.PriceSchedule.builder()
                                .setPriceType(cdm.observable.asset.PriceTypeEnum.ASSET_PRICE);
                if (ccy != null) {
                    psb.setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build())
                            .build());
                }
                pqb.addPrice(cdm.observable.asset.metafields.FieldWithMetaPriceSchedule.builder()
                        .setValue(psb.build())
                        .build());
            }
        }

        // underlyer/bond → observable
        for (Element u : XmlUtils.children(gp, "underlyer")) {
            Element bond = XmlUtils.child(u, "bond");
            if (bond != null) {
                Observable obs = EquityOptionMapper.buildBondObservable(bond);
                if (obs != null) {
                    pqb.setObservable(FieldWithMetaObservable.builder()
                            .setValue(obs)
                            .setMeta(QuantityMapper.locationMeta("observable-1"))
                            .build());
                }
                break;
            }
        }

        return TradeLot.builder().addPriceQuantity(pqb.build()).build();
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
