package io.fpmlcdm.fpml.cdm;

import cdm.event.common.TradeState;
import io.fpmlcdm.fpml.cdm.common.XmlUtils;
import io.fpmlcdm.fpml.cdm.detect.ProductDetector;
import io.fpmlcdm.fpml.cdm.products.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level entry point. Parses an FpML XML document and produces one
 * {@link TradeState} per {@code <trade>} element found.
 *
 * The dataset's {@code -incomplete} folders contain multi-trade documents,
 * hence the {@code List<TradeState>} return type.
 */
public final class FpmlToCdmConverter {

    private static final Logger log = LoggerFactory.getLogger(FpmlToCdmConverter.class);

    private final ProductDetector detector = new ProductDetector();

    public List<TradeState> convert(InputStream xml) throws Exception {
        Document doc = XmlUtils.parse(xml);
        List<TradeState> out = new ArrayList<>();
        NodeList trades = doc.getElementsByTagNameNS("*", "trade");
        for (int i = 0; i < trades.getLength(); i++) {
            Element trade = (Element) trades.item(i);
            ProductMapper mapper = detector.dispatch(trade);
            if (mapper == null) {
                log.warn("No mapper for trade #{} (no known product element found)", i);
                continue;
            }
            out.add(mapper.map(doc, trade));
        }
        return out;
    }
}
