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
        return map(documentation, parties, ctx, null);
    }

    /**
     * Extended version that also accepts the trade element to extract {@code governingLaw}.
     */
    public static ContractDetails map(Element documentation, List<Party> parties, MappingContext ctx, Element trade) {
        if (documentation == null && trade == null) return null;

        List<LegalAgreement> entries = new ArrayList<>();

        if (documentation != null) {
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
        }

        // Check for governingLaw
        cdm.legaldocumentation.common.metafields.FieldWithMetaGoverningLawEnum govLawField = null;
        if (trade != null) {
            Element govLaw = XmlUtils.child(trade, "governingLaw");
            if (govLaw != null) {
                String value = govLaw.getTextContent().trim();
                cdm.legaldocumentation.common.GoverningLawEnum gle = null;
                try { gle = cdm.legaldocumentation.common.GoverningLawEnum.valueOf(value); }
                catch (IllegalArgumentException ignored) {}
                if (gle == null) {
                    try { gle = cdm.legaldocumentation.common.GoverningLawEnum.fromDisplayName(value); }
                    catch (Exception ignored) {}
                }
                if (gle != null) {
                    govLawField = cdm.legaldocumentation.common.metafields.FieldWithMetaGoverningLawEnum.builder()
                            .setValue(gle).build();
                }
            }
        }

        if (entries.isEmpty() && govLawField == null) return null;
        ContractDetails.ContractDetailsBuilder b = ContractDetails.builder();
        entries.forEach(b::addDocumentation);
        if (govLawField != null) b.setGoverningLaw(govLawField);

        return b.build();
    }

    private static LegalAgreement buildMasterConfirmation(Element mc, MappingContext ctx) {
        Element typeEl = XmlUtils.child(mc, "masterConfirmationType");
        String typeText = typeEl != null ? typeEl.getTextContent().trim() : null;
        String typeScheme = typeEl != null ? typeEl.getAttribute("masterConfirmationTypeScheme") : null;
        String dateText = XmlUtils.childText(mc, "masterConfirmationDate");

        AgreementName.AgreementNameBuilder name = AgreementName.builder()
                .setAgreementType(LegalAgreementTypeEnum.MASTER_CONFIRMATION);

        if (typeText != null) {
            MasterConfirmationTypeEnum mce = mapMasterConfirmationType(typeText);
            FieldWithMetaMasterConfirmationTypeEnum.FieldWithMetaMasterConfirmationTypeEnumBuilder fb =
                    FieldWithMetaMasterConfirmationTypeEnum.builder();
            if (mce != null) fb.setValue(mce);
            if (typeScheme != null && !typeScheme.isEmpty()) {
                fb.setMeta(MetaFields.builder().setScheme(typeScheme).build());
            }
            name.setMasterConfirmationType(fb.build());
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

        LegalAgreementIdentification.LegalAgreementIdentificationBuilder idB =
                LegalAgreementIdentification.builder()
                        .setAgreementName(name);

        // masterAgreementVersion → vintage (integer year)
        String version = XmlUtils.childText(ma, "masterAgreementVersion");
        if (version != null) {
            try {
                idB.setVintage(Integer.parseInt(version));
            } catch (NumberFormatException ignored) {}
        }

        LegalAgreement.LegalAgreementBuilder b = LegalAgreement.builder()
                .setLegalAgreementIdentification(idB.build());

        // masterAgreementDate -> agreementDate (carried at LegalAgreement level)
        String dateText = XmlUtils.childText(ma, "masterAgreementDate");
        if (dateText != null) b.setAgreementDate(DateMapper.parse(dateText));

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
        // FpML uses shorthand codes that don't always match display names
        ContractualDefinitionsEnum mapped = switch (value) {
            case "ISDA2002Equity" -> ContractualDefinitionsEnum.ISDA_2002_EQUITY_DERIVATIVES;
            case "ISDA1996Equity" -> ContractualDefinitionsEnum.ISDA_1996_EQUITY_DERIVATIVES;
            case "ISDA2011Equity" -> ContractualDefinitionsEnum.ISDA_2011_EQUITY_DERIVATIVES;
            default -> null;
        };
        if (mapped != null) return mapped;
        try { return ContractualDefinitionsEnum.fromDisplayName(value); }
        catch (Exception ignored) {}
        try { return ContractualDefinitionsEnum.valueOf(value); }
        catch (Exception ignored) {}
        return null;
    }
}
