package io.fpmlcdm.fpml.cdm.common;

import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.PartyRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import org.w3c.dom.Element;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps {@code <trade>} top-level role elements (determiningParty, hedgingParty,
 * barrierDeterminationAgent, brokerPartyReference, …) into CDM {@link PartyRole} entries.
 */
public final class PartyRoleMapper {

    /** FpML element name → CDM PartyRoleEnum. */
    private static final Map<String, PartyRoleEnum> SINGLE_REF_ROLES = new LinkedHashMap<>();
    static {
        SINGLE_REF_ROLES.put("determiningParty", PartyRoleEnum.DETERMINING_PARTY);
        SINGLE_REF_ROLES.put("barrierDeterminationAgent", PartyRoleEnum.BARRIER_DETERMINATION_AGENT);
        SINGLE_REF_ROLES.put("hedgingParty", PartyRoleEnum.HEDGING_PARTY);
        SINGLE_REF_ROLES.put("brokerPartyReference", PartyRoleEnum.ARRANGING_BROKER);
    }

    private PartyRoleMapper() {}

    public static java.util.List<PartyRole> map(Element trade) {
        java.util.List<PartyRole> out = new java.util.ArrayList<>();
        if (trade == null) return out;
        for (Map.Entry<String, PartyRoleEnum> e : SINGLE_REF_ROLES.entrySet()) {
            for (Element el : XmlUtils.children(trade, e.getKey())) {
                String href = el.getAttribute("href");
                if (href == null || href.isEmpty()) continue;
                out.add(PartyRole.builder()
                        .setPartyReference(ReferenceWithMetaParty.builder()
                                .setExternalReference(href).build())
                        .setRole(e.getValue())
                        .build());
            }
        }
        // tradeHeader/partyTradeInformation/relatedParty entries
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeInformation")) {
                Element owner = XmlUtils.child(pti, "partyReference");
                String ownerHref = owner == null ? null : owner.getAttribute("href");
                for (Element rp : XmlUtils.children(pti, "relatedParty")) {
                    Element rpRef = XmlUtils.child(rp, "partyReference");
                    String rpHref = rpRef == null ? null : rpRef.getAttribute("href");
                    String roleText = XmlUtils.childText(rp, "role");
                    if (rpHref == null) continue;
                    PartyRoleEnum role = mapRoleText(roleText);
                    PartyRole.PartyRoleBuilder b = PartyRole.builder()
                            .setPartyReference(ReferenceWithMetaParty.builder()
                                    .setExternalReference(rpHref).build());
                    if (role != null) b.setRole(role);
                    if (ownerHref != null && !ownerHref.isEmpty()) {
                        b.setOwnershipPartyReference(ReferenceWithMetaParty.builder()
                                .setExternalReference(ownerHref).build());
                    }
                    out.add(b.build());
                }
            }
        }
        return out;
    }

    private static PartyRoleEnum mapRoleText(String text) {
        if (text == null) return null;
        try { return PartyRoleEnum.fromDisplayName(text); }
        catch (Exception ignored) {}
        try { return PartyRoleEnum.valueOf(text.toUpperCase()); }
        catch (Exception ignored) {}
        return null;
    }
}
