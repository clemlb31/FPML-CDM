package io.fpmlcdm.detect;

import io.fpmlcdm.common.XmlUtils;
import io.fpmlcdm.products.CapFloorMapper;
import io.fpmlcdm.products.CreditDefaultSwapMapper;
import io.fpmlcdm.products.EquityOptionMapper;
import io.fpmlcdm.products.FraMapper;
import io.fpmlcdm.products.FxOptionMapper;
import io.fpmlcdm.products.FxSingleLegMapper;
import io.fpmlcdm.products.FxSwapMapper;
import io.fpmlcdm.products.ProductMapper;
import io.fpmlcdm.products.SwapMapper;
import io.fpmlcdm.products.SwaptionMapper;
import org.w3c.dom.Element;

/**
 * Inspects the {@code <trade>} element and picks the appropriate {@link ProductMapper}.
 *
 * FpML 5.x trade product elements seen across the dataset:
 *   swap, fra, swaption, capFloor,
 *   creditDefaultSwap, creditDefaultSwapOption,
 *   fxSingleLeg, fxOption, fxSwap, fxDigitalOption,
 *   equityForward, equityOption, equityOptionTransactionSupplement,
 *   equitySwapTransactionSupplement, dividendSwapTransactionSupplement,
 *   bondOption, brokerEquityOption,
 *   commodityForward, commodityOption, commoditySwap, commoditySwaption,
 *   correlationSwap, varianceSwap, volatilitySwap,
 *   returnSwap, genericProduct
 */
public class ProductDetector {

    public ProductMapper dispatch(Element trade) {
        if (XmlUtils.child(trade, "swaption") != null) {
            return new SwaptionMapper();
        }
        if (XmlUtils.child(trade, "swap") != null) {
            return new SwapMapper();
        }
        if (XmlUtils.child(trade, "capFloor") != null) {
            return new CapFloorMapper();
        }
        if (XmlUtils.child(trade, "fra") != null) {
            return new FraMapper();
        }
        if (XmlUtils.child(trade, "fxSingleLeg") != null) {
            return new FxSingleLegMapper();
        }
        if (XmlUtils.child(trade, "fxSwap") != null) {
            return new FxSwapMapper();
        }
        if (XmlUtils.child(trade, "fxOption") != null) {
            return new FxOptionMapper();
        }
        if (XmlUtils.child(trade, "creditDefaultSwap") != null) {
            return new CreditDefaultSwapMapper();
        }
        // Equity options
        if (XmlUtils.child(trade, "equityOption") != null) {
            return new EquityOptionMapper();
        }
        if (XmlUtils.child(trade, "brokerEquityOption") != null) {
            return new EquityOptionMapper();
        }
        if (XmlUtils.child(trade, "equityOptionTransactionSupplement") != null) {
            return new EquityOptionMapper();
        }
        return null;
    }
}
