package io.fpmlcdm.products;

import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Stub IRS mapper — real mapping is filled in incrementally to match the reference dataset. */
public class SwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        // TODO: assemble the Trade exactly as the reference CDM JSON expects.
        Trade.TradeBuilder t = Trade.builder();
        return TradeState.builder().setTrade(t).build();
    }
}
