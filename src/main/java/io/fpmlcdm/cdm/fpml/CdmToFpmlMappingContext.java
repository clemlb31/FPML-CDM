package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.Counterparty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Context for a single CDM $\to$ FpML conversion process.
 * Manages the XML builder, ID mappings, and error collection.
 */
public class CdmToFpmlMappingContext {

    private final FpmlXmlBuilder xmlBuilder;
    private final Map<String, String> cdmIdToFpmlIdMap;
    private final Map<String, String> fpmlIdToCdmIdMap;
    private final Set<String> generatedIds;
    private int idCounter = 0;
    private final Map<String, Party> originalParties;
    private final List<Counterparty> originalCounterparties;
    private final List<String> errors;
    private final List<String> warnings;

    public CdmToFpmlMappingContext() {
        try {
            this.xmlBuilder = new FpmlXmlBuilder();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create XML builder", e);
        }
        this.cdmIdToFpmlIdMap = new HashMap<>();
        this.fpmlIdToCdmIdMap = new HashMap<>();
        this.generatedIds = new java.util.HashSet<>();
        this.originalParties = new LinkedHashMap<>();
        this.originalCounterparties = new ArrayList<>();
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }

    public FpmlXmlBuilder getXmlBuilder() {
        return xmlBuilder;
    }

    public void registerIdMapping(String cdmId, String fpmlId) {
        if (cdmId != null && fpmlId != null) {
            cdmIdToFpmlIdMap.put(cdmId, fpmlId);
            fpmlIdToCdmIdMap.put(fpmlId, cdmId);
        }
    }

    public void registerGeneratedId(String id) {
        if (id != null && !id.isEmpty()) {
            generatedIds.add(id);
        }
    }

    public boolean hasGeneratedId(String id) {
        return id != null && generatedIds.contains(id);
    }

    public String resolveHref(String cdmId) {
        if (cdmId == null) return "unknown_ref";
        if (fpmlIdToCdmIdMap.containsKey(cdmId)) {
            return fpmlIdToCdmIdMap.get(cdmId);
        }
        if (cdmIdToFpmlIdMap.containsKey(cdmId)) {
            return cdmIdToFpmlIdMap.get(cdmId);
        }
        String fallback = "ref_" + Math.abs(cdmId.hashCode());
        warnings.add("Unresolved href for CDM id: " + cdmId + ", using fallback: " + fallback);
        return fallback;
    }

    public synchronized String createFpmlId(String prefix) {
        idCounter++;
        String id = prefix + "_" + idCounter;
        generatedIds.add(id);
        return id;
    }

    public Set<String> getGeneratedIds() {
        return new java.util.HashSet<>(generatedIds);
    }

    public void addError(String message) {
        errors.add(message);
    }

    public void addWarning(String message) {
        warnings.add(message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public Document getDocument() {
        return xmlBuilder.getDocument();
    }

    public void registerOriginalParty(Party party) {
        if (party == null) return;
        try {
            String extRef = extractExternalReference(party);
            String globalKey = extractGlobalKey(party);
            if (extRef != null && !extRef.isEmpty()) {
                originalParties.put(extRef, party);
                registerIdMapping(globalKey, extRef);
            } else if (globalKey != null) {
                String fpmlId = "party_" + globalKey;
                originalParties.put(fpmlId, party);
                registerIdMapping(globalKey, fpmlId);
            }
        } catch (Exception e) {
            addWarning("Could not register original party: " + e.getMessage());
        }
    }

    public void registerOriginalCounterparties(List<Counterparty> counterparties) {
        if (counterparties == null) return;
        for (Counterparty cp : counterparties) {
            try {
                String extRef = extractExternalReferenceFromRef(cp);
                String globalRef = extractGlobalReferenceFromRef(cp);
                String fpmlId = extRef != null && !extRef.isEmpty() ? extRef : 
                                (globalRef != null && !globalRef.isEmpty() ? "party_" + globalRef : "counterparty_ref");
                originalCounterparties.add(cp);
                registerIdMapping(globalRef, fpmlId);
            } catch (Exception e) {
                addWarning("Could not register counterparty: " + e.getMessage());
            }
        }
    }

    public Map<String, Party> getOriginalParties() {
        return originalParties;
    }

    public List<Counterparty> getOriginalCounterparties() {
        return originalCounterparties;
    }

    private String extractExternalReference(Party party) throws Exception {
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

    private String extractGlobalKey(Party party) throws Exception {
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

    private String extractExternalReferenceFromRef(Counterparty cp) throws Exception {
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

    private String extractGlobalReferenceFromRef(Counterparty cp) throws Exception {
        try {
            java.lang.reflect.Method m = cp.getClass().getMethod("getPartyReference");
            Object ref = m.invoke(cp);
            if (ref == null) return null;
            try {
                java.lang.reflect.Method getGRef = ref.getClass().getMethod("getGlobalReference");
                Object val = getGRef.invoke(ref);
                return val != null ? String.valueOf(val) : null;
            } catch (NoSuchMethodException ignored) {}
        } catch (NoSuchMethodException ignored) {}
        return null;
    }
}