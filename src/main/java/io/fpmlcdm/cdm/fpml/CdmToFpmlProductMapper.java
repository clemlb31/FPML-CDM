package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import org.w3c.dom.Element;

/**
 * Strategy for converting a CDM {@link TradeState} into an FpML {@code <trade>} subtree.
 *
 * <p>Implementations are stateless per call.
 * A new instance is allocated by {@link io.fpmlcdm.cdm.fpml.CdmProductDetector} for every trade.
 *
 * <p>{@code map()} may return {@code null} to signal "no salvageable output"
 * for this trade; the caller ({@link io.fpmlcdm.cdm.fpml.CdmToFpmlConverter}) currently
 * adds whatever is returned to its list, so prefer building a minimal-but-valid
 * {@code <trade>} element over returning null when possible.
 *
 * <p>Implementations should use {@link CdmToFpmlMappingContext} to manage XML building
 * and ID mapping.
 */
public interface CdmToFpmlProductMapper {
    Element map(TradeState tradeState, CdmToFpmlMappingContext context);
}
