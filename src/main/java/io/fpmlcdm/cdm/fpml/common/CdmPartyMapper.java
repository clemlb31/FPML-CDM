package io.fpmlcdm.cdm.fpml.common;

import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.Counterparty;
import io.fpmlcdm.cdm.fpml.CdmToFpmlMappingContext;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Helper to map CDM Parties to FpML {@code <party>} elements.
 */
public class CdmPartyMapper {

    public static List<Element> mapParties(Document doc, Collection<Party> parties, CdmToFpmlMappingContext context) {
        List<Element> partyElements = new ArrayList<>();
        if (parties == null || parties.isEmpty()) return partyElements;

        Map<String, Party> originalPartiesMap = context.getOriginalParties();
        
        for (Party party : parties) {
            String fpmlId = resolvePartyId(party, originalPartiesMap);
            
            Element partyElement = doc.createElementNS(FpmlConstants.FPML_NS, "party");
            partyElement.setAttribute("id", fpmlId);
            
            try {
                Object partyIds = invoke(party, "getPartyId");
                if (partyIds instanceof List) {
                    for (Object pidObj : (List<?>) partyIds) {
                        Element pidElem = doc.createElementNS(FpmlConstants.FPML_NS, "partyId");
                        try {
                            Object identifier = invoke(pidObj, "getIdentifier");
                            if (identifier != null) {
                                // Extract value from FieldWithMetaString or similar wrapper
                                String idValue = extractStringValue(identifier);
                                if (idValue != null && !idValue.isEmpty()) {
                                    pidElem.setTextContent(idValue);
                                }
                            }
                            
                            Object idType = invoke(pidObj, "getIdentifierType");
                            if (idType instanceof Enum) {
                                String scheme = mapPartyIdScheme((Enum<?>) idType);
                                if (scheme != null) {
                                    pidElem.setAttribute("partyIdScheme", scheme);
                                }
                            }
                        } catch (Exception ignored) {}
                        partyElement.appendChild(pidElem);
                    }
                }

                // Extract name from FieldWithMetaString or similar wrapper
                Object nameField = invoke(party, "getName");
                if (nameField != null) {
                    String name = extractStringValue(nameField);
                    if (name != null && !name.isEmpty()) {
                        Element nameElem = doc.createElementNS(FpmlConstants.FPML_NS, "partyName");
                        nameElem.setTextContent(name);
                        partyElement.appendChild(nameElem);
                    }
                }
            } catch (Exception e) {
                context.addWarning("Could not map party details: " + e.getMessage());
            }

            partyElements.add(partyElement);
        }
        return partyElements;
    }

    public static Element createCounterpartyReference(Counterparty counterparty, CdmToFpmlMappingContext context) {
        Document doc = context.getDocument();
        
        try {
            String extRef = extractExternalReferenceFromRef(counterparty);
            String fpmlId = extRef != null && !extRef.isEmpty() ? extRef : "counterparty";
            
            Element partyRef = doc.createElementNS(FpmlConstants.FPML_NS, "partyReference");
            partyRef.setAttribute("href", "#" + fpmlId);
            
            try {
                Object roleObj = invoke(counterparty, "getRole");
                if (roleObj instanceof Enum) {
                    Element roleElem = doc.createElementNS(FpmlConstants.FPML_NS, "role");
                    roleElem.setTextContent(((Enum<?>) roleObj).name());
                    partyRef.appendChild(roleElem);
                }
            } catch (Exception ignored) {}
            
            return partyRef;
        } catch (Exception e) {
            context.addWarning("Could not map counterparty reference: " + e.getMessage());
            Element fallback = doc.createElementNS(FpmlConstants.FPML_NS, "partyReference");
            fallback.setAttribute("href", "#counterparty");
            return fallback;
        }
    }

    private static String resolvePartyId(Party party, Map<String, Party> originalParties) {
        try {
            String extRef = extractExternalReference(party);
            if (extRef != null && !extRef.isEmpty()) {
                return extRef;
            }
            
            String globalKey = extractGlobalKey(party);
            if (globalKey != null && !globalKey.isEmpty()) {
                return "party_" + globalKey;
            }
        } catch (Exception ignored) {}
        
        try {
            Object nameField = invoke(party, "getName");
            String name = extractStringValue(nameField);
            if (name != null && !name.isEmpty()) {
                return "party_" + name.hashCode();
            }
        } catch (Exception ignored) {}
        
        return "party_unknown";
    }

    private static String extractExternalReference(Party party) throws Exception {
        try {
            java.lang.reflect.Method m = party.getClass().getMethod("getMeta");
            Object meta = m.invoke(party);
            if (meta == null) return null;
            try {
                java.lang.reflect.Method getExtKey = meta.getClass().getMethod("getExternalKey");
                Object val = getExtKey.invoke(meta);
                return val != null ? String.valueOf(val) : null;
            } catch (NoSuchMethodException ignored) {}
        } catch (NoSuchMethodException ignored) {}
        return null;
    }

    private static String extractGlobalKey(Party party) throws Exception {
        try {
            java.lang.reflect.Method m = party.getClass().getMethod("getMeta");
            Object meta = m.invoke(party);
            if (meta == null) return null;
            try {
                java.lang.reflect.Method getGk = meta.getClass().getMethod("getGlobalKey");
                Object val = getGk.invoke(meta);
                return val != null ? String.valueOf(val) : null;
            } catch (NoSuchMethodException ignored) {}
        } catch (NoSuchMethodException ignored) {}
        return null;
    }

    private static String extractExternalReferenceFromRef(Counterparty cp) throws Exception {
        try {
            java.lang.reflect.Method m = cp.getClass().getMethod("getPartyReference");
            Object ref = m.invoke(cp);
            if (ref == null) return null;
            try {
                java.lang.reflect.Method getExtRef = ref.getClass().getMethod("getExternalReference");
                Object val = getExtRef.invoke(ref);
                return val != null ? String.valueOf(val) : null;
            } catch (NoSuchMethodException ignored) {}
        } catch (NoSuchMethodException ignored) {}
        return null;
    }

    private static String extractStringValue(Object obj) throws Exception {
        if (obj == null) return null;
        
        // Check if it's already a string
        if (obj instanceof String) return (String) obj;
        
        // Try to get value from wrapper objects (FieldWithMetaString, etc.)
        try {
            java.lang.reflect.Method getM = obj.getClass().getMethod("get");
            Object val = getM.invoke(obj);
            if (val != null && !val.toString().contains("{")) {
                return String.valueOf(val);
            }
        } catch (NoSuchMethodException ignored) {}
        
        // Try getValue method
        try {
            java.lang.reflect.Method getVal = obj.getClass().getMethod("getValue");
            Object val = getVal.invoke(obj);
            if (val != null && !val.toString().contains("{")) {
                return String.valueOf(val);
            }
        } catch (NoSuchMethodException ignored) {}
        
        // Fallback: parse from toString() output like "FieldWithMetaString {value=Party A, meta=null}"
        String str = obj.toString();
        if (str.contains("value=")) {
            int start = str.indexOf("value=") + 6;
            int end = str.indexOf(",", start);
            if (end == -1) end = str.indexOf("}", start);
            if (end > start) return str.substring(start, end).trim();
        }
        
        return null;
    }

    private static String mapPartyIdScheme(Enum<?> type) {
        if (type == null) return null;
        try {
            cdm.base.staticdata.party.PartyIdentifierTypeEnum pit = 
                (cdm.base.staticdata.party.PartyIdentifierTypeEnum) Enum.valueOf(
                    cdm.base.staticdata.party.PartyIdentifierTypeEnum.class, type.name());
            switch (pit) {
                case BIC: return "http://www.fpml.org/coding-scheme/external/iso9362";
                case LEI: return "http://www.fpml.org/coding-scheme/external/lei";
                default: return null;
            }
        } catch (Exception ignored) {}
        
        String name = type.name().toLowerCase();
        if (name.contains("bic") || name.contains("bank")) {
            return "http://www.fpml.org/coding-scheme/external/iso9362";
        } else if (name.contains("lei")) {
            return "http://www.fpml.org/coding-scheme/external/lei";
        }
        return null;
    }

    private static Object invoke(Object obj, String method) throws Exception {
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(method);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
