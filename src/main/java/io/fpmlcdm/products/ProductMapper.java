package io.fpmlcdm.products;

import cdm.event.common.TradeState;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Strategy for converting one FpML {@code <trade>} subtree into a {@link TradeState}. */
public interface ProductMapper {
    TradeState map(Document doc, Element trade);
}
