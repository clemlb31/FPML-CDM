package io.fpmlcdm.fpml.cdm.products;

import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.common.TaxonomySourceEnum;
import cdm.base.staticdata.asset.common.TaxonomyValue;
import cdm.base.staticdata.party.Party;
import cdm.event.common.TradeState;
import cdm.observable.asset.PriceQuantity;
import cdm.product.template.Payout;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.fpml.cdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps FpML {@code <fxSwap>} (near leg + far leg) into CDM TradeState.
 * Each leg produces one SettlementPayout and one PriceQuantity entry.
 */
public class FxSwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        Element fxSwap = XmlUtils.child(trade, "fxSwap");
        Element nearLeg = XmlUtils.child(fxSwap, "nearLeg");
        Element farLeg = XmlUtils.child(fxSwap, "farLeg");

        // Assign roles: PARTY_1 = payer of exchangedCurrency1 on the near leg
        Element ec1 = XmlUtils.child(nearLeg, "exchangedCurrency1");
        Element ec1PayerRef = ec1 != null ? XmlUtils.child(ec1, "payerPartyReference") : null;
        String ec1PayerHref = ec1PayerRef != null ? ec1PayerRef.getAttribute("href") : null;
        FxSingleLegMapper.assignRoles(ec1PayerHref, ctx);

        // Build near leg (index=1) and far leg (index=2)
        FxSingleLegMapper.LegResult nearResult = FxSingleLegMapper.buildLegResult(nearLeg, ctx, 1, 2);
        FxSingleLegMapper.LegResult farResult = FxSingleLegMapper.buildLegResult(farLeg, ctx, 2, 2);

        List<Payout> payouts = new ArrayList<>();
        payouts.add(nearResult.payout);
        payouts.add(farResult.payout);

        List<PriceQuantity> pqs = new ArrayList<>();
        pqs.add(nearResult.priceQuantity);
        pqs.add(farResult.priceQuantity);

        // Build taxonomy from productType on fxSwap element
        List<ProductTaxonomy> extraTaxonomy = buildFxSwapTaxonomy(fxSwap);

        return FxSingleLegMapper.buildTradeState(doc, trade, tradeHeader, parties, ctx,
                payouts, pqs, fxSwap, "ForeignExchange_Swap", extraTaxonomy);
    }

    private List<ProductTaxonomy> buildFxSwapTaxonomy(Element fxSwap) {
        List<ProductTaxonomy> out = new ArrayList<>();
        Element productType = XmlUtils.child(fxSwap, "productType");
        if (productType != null) {
            String ptValue = productType.getTextContent().trim();
            String ptScheme = productType.getAttribute("productTypeScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder name = FieldWithMetaString.builder().setValue(ptValue);
            if (ptScheme != null && !ptScheme.isEmpty()) {
                name.setMeta(MetaFields.builder().setScheme(ptScheme).build());
            }
            TaxonomyValue tv = TaxonomyValue.builder().setName(name.build()).build();
            TaxonomySourceEnum src = (ptScheme != null && !ptScheme.isEmpty())
                    ? TaxonomySourceEnum.OTHER : TaxonomySourceEnum.OTHER;
            out.add(ProductTaxonomy.builder().setSource(src).setValue(tv).build());
        }
        return out;
    }
}
