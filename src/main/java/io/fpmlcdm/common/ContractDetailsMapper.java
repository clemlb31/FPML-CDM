package io.fpmlcdm.common;

import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.ContractDetails;
import cdm.legaldocumentation.common.AgreementName;
import cdm.legaldocumentation.common.ContractualDefinitionsEnum;
import cdm.legaldocumentation.common.LegalAgreement;
import cdm.legaldocumentation.common.LegalAgreementIdentification;
import cdm.legaldocumentation.common.LegalAgreementTypeEnum;
import cdm.legaldocumentation.common.metafields.FieldWithMetaContractualDefinitionsEnum;
import cdm.legaldocumentation.master.MasterAgreementTypeEnum;
import cdm.legaldocumentation.master.MasterConfirmationTypeEnum;
import cdm.legaldocumentation.master.metafields.FieldWithMetaMasterAgreementTypeEnum;
import cdm.legaldocumentation.master.metafields.FieldWithMetaMasterConfirmationTypeEnum;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps FpML {@code <documentation>} into CDM {@link ContractDetails}.
 *
 * FpML structure:
 * <pre>{@code
 * <documentation>
 *   <masterAgreement>
 *     <masterAgreementType>ISDA</masterAgreementType>
 *   </masterAgreement>
 *   <contractualDefinitions>ISDA2006</contractualDefinitions>
 * </documentation>
 * }</pre>
 *
 * Each FpML child element typically becomes a separate {@link LegalAgreement} entry under
 * {@code contractDetails.documentation[]}, each carrying both parties as {@code contractualParty}.
 */
public final class ContractDetailsMapper {

    private ContractDetailsMapper() {}

    public static ContractDetails map(Element documentation, List<Party> parties, MappingContext ctx) {
        if (documentation == null) return null;

        List<LegalAgreement> entries = new ArrayList<>();

        // Master agreements
        for (Element ma : XmlUtils.children(documentation, "masterAgreement")) {
            LegalAgreement la = buildMasterAgreement(ma, ctx);
            if (la != null) entries.add(la);
        }

        // Master confirmations (CDS)
        for (Element mc : XmlUtils.children(documentation, "masterConfirmation")) {
            LegalAgreement la = buildMasterConfirmation(mc, ctx);
            if (la != null) entries.add(la);
        }

        // Contractual definitions → one Confirmation entry with all contractualDefinitionsType[]
        List<Element> cds = XmlUtils.children(documentation, "contractualDefinitions");
        if (!cds.isEmpty()) {
            LegalAgreement la = buildConfirmation(cds, ctx);
            if (la != null) entries.add(la);
        }

        if (entries.isEmpty()) return null;
        ContractDetails.ContractDetailsBuilder b = ContractDetails.builder();
        entries.forEach(b::addDocumentation);
        return b.build();
    }

    private static LegalAgreement buildMasterConfirmation(Element mc, MappingContext ctx) {
        String typeText = XmlUtils.childText(mc, "masterConfirmationType");
        String dateText = XmlUtils.childText(mc, "masterConfirmationDate");

        AgreementName.AgreementNameBuilder name = AgreementName.builder()
                .setAgreementType(LegalAgreementTypeEnum.MASTER_CONFIRMATION);

        if (typeText != null) {
            MasterConfirmationTypeEnum mce = mapMasterConfirmationType(typeText);
            FieldWithMetaMasterConfirmationTypeEnum.FieldWithMetaMasterConfirmationTypeEnumBuilder fb =
                    FieldWithMetaMasterConfirmationTypeEnum.builder();
            if (mce != null) fb.setValue(mce);
            else fb.setValue(null).setMeta(MetaFields.builder().build());
            name.setMasterConfirmationType(FieldWithMetaMasterConfirmationTypeEnum.builder()
                    .setValue(mce).build());
        }

        LegalAgreement.LegalAgreementBuilder la = LegalAgreement.builder()
                .setLegalAgreementIdentification(LegalAgreementIdentification.builder()
                        .setAgreementName(name.build()).build());
        if (dateText != null) la.setAgreementDate(DateMapper.parse(dateText));
        for (ReferenceWithMetaParty p : partyRefs(ctx)) la.addContractualParty(p);
        return la.build();
    }

    private static MasterConfirmationTypeEnum mapMasterConfirmationType(String text) {
        if (text == null) return null;
        try { return MasterConfirmationTypeEnum.fromDisplayName(text); }
        catch (Exception ignored) {}
        String normalized = text.replace(".", "_").replace(" ", "_").toUpperCase();
        try { return MasterConfirmationTypeEnum.valueOf(normalized); }
        catch (Exception ignored) {}
        return null;
    }

    private static LegalAgreement buildMasterAgreement(Element ma, MappingContext ctx) {
        Element typeEl = XmlUtils.child(ma, "masterAgreementType");
        if (typeEl == null) return null;
        String value = typeEl.getTextContent().trim();
        String scheme = typeEl.getAttribute("masterAgreementTypeScheme");

        MasterAgreementTypeEnum mat = mapMasterAgreementType(value);
        FieldWithMetaMasterAgreementTypeEnum.FieldWithMetaMasterAgreementTypeEnumBuilder mb =
                FieldWithMetaMasterAgreementTypeEnum.builder();
        if (mat != null) mb.setValue(mat);
        if (scheme != null && !scheme.isEmpty()) {
            mb.setMeta(MetaFields.builder().setScheme(scheme).build());
        }

        AgreementName name = AgreementName.builder()
                .setAgreementType(LegalAgreementTypeEnum.MASTER_AGREEMENT)
                .setMasterAgreementType(mb.build())
                .build();

        LegalAgreementIdentification id = LegalAgreementIdentification.builder()
                .setAgreementName(name)
                .build();

        LegalAgreement.LegalAgreementBuilder b = LegalAgreement.builder()
                .setLegalAgreementIdentification(id);
        for (ReferenceWithMetaParty p : partyRefs(ctx)) {
            b.addContractualParty(p);
        }
        return b.build();
    }

    private static LegalAgreement buildConfirmation(List<Element> cds, MappingContext ctx) {
        AgreementName.AgreementNameBuilder nameB = AgreementName.builder()
                .setAgreementType(LegalAgreementTypeEnum.CONFIRMATION);
        for (Element cd : cds) {
            String value = cd.getTextContent().trim();
            String scheme = cd.getAttribute("contractualDefinitionsScheme");
            ContractualDefinitionsEnum cde = mapContractualDefinitions(value);
            // Skip entries where neither enum value nor scheme can be set (reference drops them).
            if (cde == null && (scheme == null || scheme.isEmpty())) continue;
            FieldWithMetaContractualDefinitionsEnum.FieldWithMetaContractualDefinitionsEnumBuilder b =
                    FieldWithMetaContractualDefinitionsEnum.builder();
            if (cde != null) b.setValue(cde);
            if (scheme != null && !scheme.isEmpty()) {
                b.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            nameB.addContractualDefinitionsType(b.build());
        }

        LegalAgreementIdentification id = LegalAgreementIdentification.builder()
                .setAgreementName(nameB.build())
                .build();
        LegalAgreement.LegalAgreementBuilder b = LegalAgreement.builder()
                .setLegalAgreementIdentification(id);
        for (ReferenceWithMetaParty p : partyRefs(ctx)) {
            b.addContractualParty(p);
        }
        return b.build();
    }

    /** PARTY_1 and PARTY_2 (in role order) — additional parties are excluded. */
    private static List<ReferenceWithMetaParty> partyRefs(MappingContext ctx) {
        return ctx.partyOrder.entrySet().stream()
                .filter(e -> e.getValue() < 2)
                .sorted(java.util.Map.Entry.comparingByValue())
                .map(e -> ReferenceWithMetaParty.builder().setExternalReference(e.getKey()).build())
                .collect(java.util.stream.Collectors.toList());
    }

    /** Map FpML masterAgreementType code to CDM enum. */
    private static MasterAgreementTypeEnum mapMasterAgreementType(String value) {
        if (value == null) return null;
        switch (value) {
            case "ISDA": return MasterAgreementTypeEnum.ISDA_MASTER;
            default:
        }
        // Try fromDisplayName as fallback (handles many variants)
        try { return MasterAgreementTypeEnum.fromDisplayName(value); }
        catch (Exception ignored) {}
        try { return MasterAgreementTypeEnum.valueOf(value.toUpperCase()); }
        catch (Exception ignored) {}
        return null;
    }

    /** Map FpML contractualDefinitions code to CDM enum. */
    private static ContractualDefinitionsEnum mapContractualDefinitions(String value) {
        if (value == null) return null;
        try { return ContractualDefinitionsEnum.fromDisplayName(value); }
        catch (Exception ignored) {}
        try { return ContractualDefinitionsEnum.valueOf(value); }
        catch (Exception ignored) {}
        return null;
    }
}
