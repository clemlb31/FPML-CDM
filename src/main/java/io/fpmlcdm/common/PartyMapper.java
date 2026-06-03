package io.fpmlcdm.common;

import cdm.base.staticdata.party.NaturalPerson;
import cdm.base.staticdata.party.NaturalPersonRole;
import cdm.base.staticdata.party.NaturalPersonRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyIdentifier;
import cdm.base.staticdata.party.PartyIdentifierTypeEnum;
import cdm.base.staticdata.party.metafields.FieldWithMetaNaturalPersonRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaNaturalPerson;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads top-level {@code <party id="…">} elements into CDM {@link Party} objects and registers
 * them in the {@link MappingContext} so other mappers can reference them by FpML id.
 */
public final class PartyMapper {

    private PartyMapper() {}

    public static List<Party> map(Document doc, MappingContext ctx) {
        List<Party> parties = new ArrayList<>();
        NodeList partyNodes = doc.getDocumentElement().getElementsByTagNameNS("*", "party");
        int order = 0;
        for (int i = 0; i < partyNodes.getLength(); i++) {
            Element p = (Element) partyNodes.item(i);
            if (p.getParentNode() != doc.getDocumentElement()) continue; // only document-level parties

            String id = p.getAttribute("id");
            Party party = buildParty(p, id);
            parties.add(party);
            if (!id.isEmpty()) {
                ctx.parties.put(id, party);
                ctx.partyOrder.put(id, order++);
            }
        }
        return parties;
    }

    private static Party buildParty(Element p, String externalKey) {
        Party.PartyBuilder b = Party.builder();

        for (Element pid : XmlUtils.children(p, "partyId")) {
            String value = pid.getTextContent().trim();
            String scheme = pid.getAttribute("partyIdScheme");
            FieldWithMetaString id = FieldWithMetaString.builder()
                    .setValue(value)
                    .setMeta(scheme.isEmpty() ? null : MetaFields.builder().setScheme(scheme).build())
                    .build();

            PartyIdentifier.PartyIdentifierBuilder pib = PartyIdentifier.builder()
                    .setIdentifier(id);
            PartyIdentifierTypeEnum type = EnumMappers.partyIdentifierType(scheme);
            if (type != null) pib.setIdentifierType(type);
            b.addPartyId(pib.build());
        }

        String name = XmlUtils.childText(p, "partyName");
        if (name != null && !name.isEmpty()) {
            b.setName(FieldWithMetaString.builder().setValue(name).build());
        }

        // Embedded persons → Party.person[]
        for (Element personEl : XmlUtils.children(p, "person")) {
            b.addPerson(buildPerson(personEl));
        }

        // contactInfo → contactInformation
        Element contactInfo = XmlUtils.child(p, "contactInfo");
        if (contactInfo != null) {
            cdm.base.staticdata.party.ContactInformation ci = buildContactInformation(contactInfo);
            if (ci != null) b.setContactInformation(ci);
        }

        if (externalKey != null && !externalKey.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(externalKey).build());
        }
        return b.build();
    }

    private static cdm.base.staticdata.party.ContactInformation buildContactInformation(Element contactInfo) {
        cdm.base.staticdata.party.ContactInformation.ContactInformationBuilder b =
                cdm.base.staticdata.party.ContactInformation.builder();
        boolean any = false;
        for (Element addr : XmlUtils.children(contactInfo, "address")) {
            cdm.base.staticdata.party.Address.AddressBuilder ab = cdm.base.staticdata.party.Address.builder();
            String country = XmlUtils.childText(addr, "country");
            if (country != null) {
                ab.setCountry(FieldWithMetaString.builder().setValue(country).build());
                any = true;
            }
            String city = XmlUtils.childText(addr, "city");
            if (city != null) { ab.setCity(city); any = true; }
            for (Element street : XmlUtils.children(addr, "street")) {
                String s = street.getTextContent().trim();
                if (!s.isEmpty()) { ab.addStreet(s); any = true; }
            }
            b.addAddress(ab.build());
        }
        return any ? b.build() : null;
    }

    private static NaturalPerson buildPerson(Element personEl) {
        NaturalPerson.NaturalPersonBuilder pb = NaturalPerson.builder();
        String firstName = XmlUtils.childText(personEl, "firstName");
        if (firstName != null) pb.setFirstName(firstName);
        String surname = XmlUtils.childText(personEl, "surname");
        if (surname != null) pb.setSurname(surname);

        // personId children → personId[]
        for (Element pidEl : XmlUtils.children(personEl, "personId")) {
            String value = pidEl.getTextContent().trim();
            String scheme = pidEl.getAttribute("personIdScheme");
            com.rosetta.model.metafields.FieldWithMetaString.FieldWithMetaStringBuilder fms =
                    com.rosetta.model.metafields.FieldWithMetaString.builder().setValue(value);
            if (scheme != null && !scheme.isEmpty()) {
                fms.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            cdm.base.staticdata.party.PersonIdentifier piEl = cdm.base.staticdata.party.PersonIdentifier.builder()
                    .setIdentifier(fms.build())
                    .build();
            pb.addPersonId(cdm.base.staticdata.party.metafields.FieldWithMetaPersonIdentifier.builder()
                    .setValue(piEl)
                    .build());
        }

        String pid = personEl.getAttribute("id");
        if (pid != null && !pid.isEmpty()) {
            pb.setMeta(MetaFields.builder().setExternalKey(pid).build());
        }
        return pb.build();
    }

    /**
     * Attach person roles to a built party in-place. Returns the same Party with the addition.
     * Each {@code <relatedPerson>} (under {@code partyTradeInformation}) becomes a NaturalPersonRole.
     */
    public static void attachPersonRoles(Party.PartyBuilder pb, Element relatedPerson) {
        if (relatedPerson == null) return;
        Element ref = XmlUtils.child(relatedPerson, "personReference");
        String roleText = XmlUtils.childText(relatedPerson, "role");
        if (ref == null && roleText == null) return;

        NaturalPersonRole.NaturalPersonRoleBuilder npr = NaturalPersonRole.builder();
        if (ref != null) {
            npr.setPersonReference(ReferenceWithMetaNaturalPerson.builder()
                    .setExternalReference(ref.getAttribute("href")).build());
        }
        if (roleText != null) {
            NaturalPersonRoleEnum e = mapPersonRole(roleText);
            FieldWithMetaNaturalPersonRoleEnum.FieldWithMetaNaturalPersonRoleEnumBuilder fb =
                    FieldWithMetaNaturalPersonRoleEnum.builder();
            if (e != null) fb.setValue(e);
            npr.addRole(fb.build());
        }
        pb.addPersonRole(npr.build());
    }

    private static NaturalPersonRoleEnum mapPersonRole(String text) {
        if (text == null) return null;
        try { return NaturalPersonRoleEnum.fromDisplayName(text); }
        catch (Exception ignored) {}
        try { return NaturalPersonRoleEnum.valueOf(text.toUpperCase()); }
        catch (Exception ignored) {}
        return null;
    }
}
