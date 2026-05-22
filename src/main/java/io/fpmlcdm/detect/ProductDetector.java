package io.fpmlcdm.detect;

import io.fpmlcdm.common.XmlUtils;
import io.fpmlcdm.products.ProductMapper;
import io.fpmlcdm.products.SwapMapper;
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
        if (XmlUtils.child(trade, "swap") != null) {
            return new SwapMapper();
        }
        return null;
    }
}
