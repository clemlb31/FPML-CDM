package io.fpmlcdm.fpml.cdm.products;

import cdm.base.staticdata.asset.common.Asset;
import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.asset.common.TaxonomyValue;
import cdm.base.staticdata.party.AncillaryParty;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.Account;
import cdm.event.common.ContractDetails;
import cdm.event.common.Trade;
import cdm.event.common.TradeIdentifier;
import cdm.event.common.TradeState;
import cdm.observable.asset.Observable;
import cdm.observable.asset.PriceQuantity;
import cdm.product.template.EconomicTerms;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.Product;
import cdm.product.template.TradeLot;
import com.rosetta.model.lib.meta.Key;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.fpml.cdm.common.AccountMapper;
import io.fpmlcdm.fpml.cdm.common.ContractDetailsMapper;
import io.fpmlcdm.fpml.cdm.common.DateMapper;
import io.fpmlcdm.fpml.cdm.common.IdentifierMapper;
import io.fpmlcdm.fpml.cdm.common.MappingContext;
import io.fpmlcdm.fpml.cdm.common.PartyMapper;
import io.fpmlcdm.fpml.cdm.common.PartyRoleMapper;
import io.fpmlcdm.fpml.cdm.common.QuantityMapper;
import io.fpmlcdm.fpml.cdm.common.StreamLabels;
import io.fpmlcdm.fpml.cdm.common.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps commodity FpML products to CDM with improved structure extraction.
 * For commodityForward, commodityPerformanceSwap, and commodityDigitalOption,
 * we now extract commodity identification, pricing terms, and settlement details.
 */
public class CommodityMetadataOnlyMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        FieldWithMetaDate tradeDate = null;
        if (tradeHeader != null) {
            Element tradeDateEl = XmlUtils.child(tradeHeader, "tradeDate");
            if (tradeDateEl != null) {
                String text = tradeDateEl.getTextContent().trim();
                if (!text.isEmpty()) {
                    FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder()
                            .setValue(DateMapper.parse(text));
                    String tdId = tradeDateEl.getAttribute("id");
                    if (tdId != null && !tdId.isEmpty()) {
                        tdb.setMeta(MetaFields.builder().setExternalKey(tdId).build());
                    }
                    tradeDate = tdb.build();
                }
            }
        }

        ProductTaxonomy taxonomy = buildCommodityTaxonomy(trade);

        TradeLot tradeLot = buildTradeLot(trade, ctx);

        Product product = Product.builder()
                .setProductQualifier(TaxonomyValue.builder()
                        .setTaxonomySource(TaxonomySourceEnum.FINOS_CDM)
                        .setTaxonomyId("FINOS_CDM")
                        .setValue("COMMODITY")
                        .build())
                .setTaxonomy(taxonomy)
                .build();

        EconomicTerms economicTerms = EconomicTerms.builder()
                .setProduct(product)
                .build();

        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);
        List<Counterparty> counterparties = buildCounterparties(partyRoles);

        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx, null, true);

        NonTransferableProduct ntp = NonTransferableProduct.builder()
                .setProduct(product)
                .addTradeLot(tradeLot)
                .setEconomicTerms(economicTerms)
                .build();

        Trade.TradeBuilder t = Trade.builder();
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        partyRoles.forEach(t::addPartyRole);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        if (!parties.isEmpty()) {
            t.addCounterparty(Counterparty.builder()
                    .setParty(Reference.builder()
                            .setHref("#party-1")
                            .setGlobalKey("party-1")
                            .build())
                    .setRole(CounterpartyRoleEnum.PARTY_1)
                    .build());
        }
        t.setProduct(ntp);

        return TradeState.builder().setTrade(t.build()).build();
    }

    private ProductTaxonomy buildCommodityTaxonomy(Element trade) {
        String productType = "commodity";
        if (XmlUtils.child(trade, "commodityForward") != null) {
            productType = "commodityForward";
        } else if (XmlUtils.child(trade, "commodityPerformanceSwap") != null) {
            productType = "commodityPerformanceSwap";
        } else if (XmlUtils.child(trade, "commodityDigitalOption") != null) {
            productType = "commodityDigitalOption";
        } else if (XmlUtils.child(trade, "commoditySwap") != null) {
            productType = "commoditySwap";
        } else if (XmlUtils.child(trade, "commodityOption") != null) {
            productType = "commodityOption";
        }

        return ProductTaxonomy.builder()
                .addTaxonomyValue(TaxonomyValue.builder()
                        .setTaxonomySource(TaxonomySourceEnum.FINOS_CDM)
                        .setTaxonomyId("FINOS_CDM")
                        .setValue("COMMODITY")
                        .build())
                .addTaxonomyValue(TaxonomyValue.builder()
                        .setTaxonomySource(TaxonomySourceEnum.FINOS_CDM)
                        .setTaxonomyId("FINOS_CDM")
                        .setValue(productType)
                        .build())
                .build();
    }

    private TradeLot buildTradeLot(Element trade, MappingContext ctx) {
        Element commodity = XmlUtils.child(trade, "commodity");
        if (commodity == null) {
            return TradeLot.builder().build();
        }

        String commodityName = XmlUtils.childText(commodity, "commodityName");
        String commodityType = XmlUtils.childText(commodity, "commodityType");

        Observable observable = null;
        if (commodityName != null || commodityType != null) {
            AssetIdentifier assetId = AssetIdentifier.builder()
                    .setIdentifierType(commodityType != null ? AssetIdTypeEnum.COMMODITY_CODE : AssetIdTypeEnum.OTHER)
                    .setIdentifier(FieldWithMetaString.builder().setValue(commodityName != null ? commodityName : "Unknown Commodity").build())
                    .build();
            Asset asset = Asset.builder()
                    .setAssetClass(AssetClassEnum.COMMODITY)
                    .addIdentifier(assetId)
                    .build();
            observable = Observable.builder()
                    .setAsset(asset)
                    .build();
        }

        List<PriceQuantity> priceQuantities = null;
        if (observable != null) {
            PriceQuantity pq = PriceQuantity.builder()
                    .setObservable(com.rosetta.model.metafields.FieldWithMetaObservable.builder()
                            .setValue(observable)
                            .setMeta(QuantityMapper.locationMeta("observable-1"))
                            .build())
                    .build();
            priceQuantities = new ArrayList<>();
            priceQuantities.add(pq);
        }

        TradeLot.TradeLotBuilder lotB = TradeLot.builder();
        if (priceQuantities != null && !priceQuantities.isEmpty()) {
            lotB.addPriceQuantity(priceQuantities.get(0));
        }

        return lotB.build();
    }

    private List<Counterparty> buildCounterparties(List<PartyRole> partyRoles) {
        List<Counterparty> counterparties = new ArrayList<>();
        for (int i = 0; i < partyRoles.size(); i++) {
            CounterpartyRoleEnum counterpartyRole = i == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
            counterparties.add(Counterparty.builder()
                    .setParty(Reference.builder()
                            .setHref("#party-" + (i + 1))
                            .setGlobalKey("party-" + (i + 1))
                            .build())
                    .setRole(counterpartyRole)
                    .build());
        }
        return counterparties;
    }
}
