package io.fpmlcdm.cdm.fpml;

import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.Party;
import cdm.event.common.TradeState;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.CdmToFpmlProductMapper;
import io.fpmlcdm.cdm.fpml.CdmProductDetector;
import io.fpmlcdm.cdm.fpml.common.CdmPartyMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Main entry point for the CDM $\to$ FpML conversion.
 */
public class CdmToFpmlConverter {

    private final CdmProductDetector detector = new CdmProductDetector();

    /**
     * Result of a CDM $\to$ FpML conversion, containing both the trade element
     * and any document-level party elements needed for round-trip validation.
     */
    public static class ConversionResult {
        private final Element tradeElement;
        private final List<Element> partyElements;

        public ConversionResult(Element tradeElement, List<Element> partyElements) {
            this.tradeElement = tradeElement;
            this.partyElements = partyElements != null ? partyElements : new ArrayList<>();
        }

        public Element getTradeElement() { return tradeElement; }
        public List<Element> getPartyElements() { return partyElements; }
    }

    /**
     * Converts a CDM {@link TradeState} to FpML elements.
     */
    public ConversionResult convert(TradeState tradeState) throws Exception {
        CdmToFpmlMappingContext context = new CdmToFpmlMappingContext();
        
        // Extract and register original parties/counterparties from CDM
        extractOriginalParties(tradeState, context);

        Optional<CdmToFpmlProductMapper> mapper = detector.detect(tradeState);
        
        if (mapper.isEmpty()) {
            throw new IllegalArgumentException("No suitable mapper found for the given CDM product type.");
        }

        Element tradeElement = mapper.get().map(tradeState, context);
        
        // Build document-level party elements from registered original parties
        List<Element> partyElements = buildDocumentLevelParties(context);
        
        return new ConversionResult(tradeElement, partyElements);
    }

    private void extractOriginalParties(TradeState tradeState, CdmToFpmlMappingContext context) throws Exception {
        Object trade = invokeField(tradeState, "getTrade");
        if (trade == null) return;
        
        List<?> counterparties = (List<?>) invokeField(trade, "getCounterparty");
        if (counterparties != null && !counterparties.isEmpty()) {
            for (Object cp : counterparties) {
                if (cp instanceof Counterparty) {
                    context.registerOriginalCounterparties(java.util.Collections.singletonList((Counterparty) cp));
                }
            }
        }
        
        List<?> parties = (List<?>) invokeField(trade, "getParty");
        if (parties != null && !parties.isEmpty()) {
            for (Object p : parties) {
                if (p instanceof Party) {
                    context.registerOriginalParty((Party) p);
                }
            }
        }
    }

    private List<Element> buildDocumentLevelParties(CdmToFpmlMappingContext context) {
        Document doc = context.getDocument();
        
        // Use LinkedHashSet to preserve insertion order from LinkedHashMap
        java.util.Set<Party> originalParties = new java.util.LinkedHashSet<>(context.getOriginalParties().values());
        
        if (originalParties.isEmpty()) return new ArrayList<>();
        
        return CdmPartyMapper.mapParties(doc, originalParties, context);
    }

    private static Object invokeField(Object obj, String fieldName) throws Exception {
        if (obj == null || fieldName == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(fieldName);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
