package io.fpmlcdm.detect;
import io.fpmlcdm.common.XmlUtils;
import io.fpmlcdm.products.*;
import org.w3c.dom.Element;
public class ProductDetector {
    public ProductMapper dispatch(Element trade) {
        if (XmlUtils.child(trade, "swaption") != null) return new SwaptionMapper();
        if (XmlUtils.child(trade, "swap") != null) return new SwapMapper();
        if (XmlUtils.child(trade, "capFloor") != null) return new CapFloorMapper();
        if (XmlUtils.child(trade, "fra") != null) return new FraMapper();
        if (XmlUtils.child(trade, "fxSingleLeg") != null) return new FxSingleLegMapper();
        if (XmlUtils.child(trade, "fxSwap") != null) return new FxSwapMapper();
        if (XmlUtils.child(trade, "fxOption") != null) return new FxOptionMapper();
        if (XmlUtils.child(trade, "creditDefaultSwapOption") != null) return new CreditDefaultSwapOptionMapper();
        if (XmlUtils.child(trade, "creditDefaultSwap") != null) return new CreditDefaultSwapMapper();
        if (XmlUtils.child(trade, "equityOption") != null) return new EquityOptionMapper();
        if (XmlUtils.child(trade, "brokerEquityOption") != null) return new EquityOptionMapper();
        if (XmlUtils.child(trade, "equityOptionTransactionSupplement") != null) return new EquityOptionMapper();
        if (XmlUtils.child(trade, "returnSwap") != null) return new ReturnSwapMapper();
        if (XmlUtils.child(trade, "equitySwapTransactionSupplement") != null) return new ReturnSwapMapper();
        if (XmlUtils.child(trade, "dividendSwapOptionTransactionSupplement") != null) return new DividendSwapOptionMapper();
        if (XmlUtils.child(trade, "dividendSwapTransactionSupplement") != null) return new DividendSwapMapper();
        if (XmlUtils.child(trade, "commoditySwap") != null) return new CommoditySwapMapper();
        if (XmlUtils.child(trade, "commodityOption") != null) return new CommodityOptionMapper();
        if (XmlUtils.child(trade, "commoditySwaption") != null) return new CommoditySwaptionMapper();
        if (XmlUtils.child(trade, "commodityBasketOption") != null) return new CommodityBasketOptionMapper();
        if (XmlUtils.child(trade, "varianceSwap") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "varianceSwapTransactionSupplement") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "varianceOptionTransactionSupplement") != null) return new VarianceOptionMapper();
        if (XmlUtils.child(trade, "correlationSwap") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "volatilitySwap") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "volatilitySwapTransactionSupplement") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "securityLending") != null) return new SecurityLendingMapper();
        if (XmlUtils.child(trade, "bulletPayment") != null) return new BulletPaymentMapper();
        if (XmlUtils.child(trade, "bondOption") != null) return new BondOptionMapper();
        if (XmlUtils.child(trade, "genericProduct") != null) return new GenericProductMapper();
        if (XmlUtils.child(trade, "fxVarianceSwap") != null) return new FxVarianceSwapMapper();
        if (XmlUtils.child(trade, "fxVolatilitySwap") != null) return new FxVolatilitySwapMapper();
        if (XmlUtils.child(trade, "fxDigitalOption") != null) return new FxDigitalOptionMapper();
        // Product types whose reference CDM JSON carries only trade-level metadata
        // (tradeIdentifier/tradeDate/party). BulletPaymentMapper already produces
        // exactly that shape, so reuse it here.
        if (XmlUtils.child(trade, "strategy") != null) return new BulletPaymentMapper();
        if (XmlUtils.child(trade, "fxFlexibleForward") != null) return new BulletPaymentMapper();
        if (XmlUtils.child(trade, "fxForwardVolatilityAgreement") != null) return new FxForwardVolatilityAgreementMapper();
        if (XmlUtils.child(trade, "termDeposit") != null) return new BulletPaymentMapper();
        if (XmlUtils.child(trade, "future") != null) return new BulletPaymentMapper();
        if (XmlUtils.child(trade, "instrumentTradeDetails") != null) return new BulletPaymentMapper();
        // Commodity exotic products whose reference is metadata-only. We add
        // contractDetails via the standard Trade-builder path below.
        if (XmlUtils.child(trade, "commodityPerformanceSwap") != null) return new CommodityMetadataOnlyMapper();
        if (XmlUtils.child(trade, "commodityDigitalOption") != null) return new CommodityMetadataOnlyMapper();
        if (XmlUtils.child(trade, "commodityForward") != null) return new CommodityMetadataOnlyMapper();
        return null;
    }
}
