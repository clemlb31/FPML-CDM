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
        if (XmlUtils.child(trade, "commoditySwaption") != null) return new CommodityOptionMapper();
        if (XmlUtils.child(trade, "varianceSwap") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "correlationSwap") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "volatilitySwap") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "volatilitySwapTransactionSupplement") != null) return new VarianceSwapMapper();
        if (XmlUtils.child(trade, "securityLending") != null) return new SecurityLendingMapper();
        return null;
    }
}
