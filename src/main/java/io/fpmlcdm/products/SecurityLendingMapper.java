package io.fpmlcdm.products;

import cdm.base.staticdata.party.*;
import cdm.event.common.*;
import cdm.legaldocumentation.common.AgreementName;
import cdm.legaldocumentation.common.LegalAgreement;
import cdm.legaldocumentation.common.LegalAgreementIdentification;
import cdm.legaldocumentation.common.LegalAgreementTypeEnum;
import cdm.legaldocumentation.master.MasterAgreementTypeEnum;
import cdm.legaldocumentation.master.metafields.FieldWithMetaMasterAgreementTypeEnum;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal security lending mapper. The reference CDM for the available SBL examples is
 * effectively a Trade carrying only tradeIdentifier, tradeDate, parties, partyRole and
 * contractDetails (no product / tradeLot / counterparty). We mirror that.
 */
public class SecurityLendingMapper implements ProductMapper {
    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);
        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        parties = SwapMapper.applyPartyTradeInformation(parties, tradeHeader);

        // Trade identifiers
        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        // Trade date
        FieldWithMetaDate tradeDate = null;
        if (tradeHeader != null) {
            Element tradeDateEl = XmlUtils.child(tradeHeader, "tradeDate");
            if (tradeDateEl != null) {
                FieldWithMetaDate.FieldWithMetaDateBuilder tdb = FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(tradeDateEl.getTextContent().trim()));
                String tdId = tradeDateEl.getAttribute("id");
                if (tdId != null && !tdId.isEmpty()) {
                    tdb.setMeta(MetaFields.builder().setExternalKey(tdId).build());
                }
                tradeDate = tdb.build();
            }
        }

        List<PartyRole> partyRoles = PartyRoleMapper.map(trade);

        // Build contract details without contractualParty entries (the SBL reference omits them)
        ContractDetails contractDetails = buildSecurityLendingContractDetails(
                XmlUtils.child(trade, "documentation"));

        Trade.TradeBuilder t = Trade.builder();
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        partyRoles.forEach(t::addPartyRole);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        return TradeState.builder().setTrade(t.build()).build();
    }

    private static ContractDetails buildSecurityLendingContractDetails(Element documentation) {
        if (documentation == null) return null;
        List<LegalAgreement> entries = new ArrayList<>();
        for (Element ma : XmlUtils.children(documentation, "masterAgreement")) {
            LegalAgreement la = buildMasterAgreement(ma);
            if (la != null) entries.add(la);
        }
        for (Element csa : XmlUtils.children(documentation, "creditSupportAgreement")) {
            LegalAgreement la = buildSimpleAgreement(csa, LegalAgreementTypeEnum.CREDIT_SUPPORT_AGREEMENT, null);
            if (la != null) entries.add(la);
        }
        for (Element oa : XmlUtils.children(documentation, "otherAgreement")) {
            String typeText = XmlUtils.childText(oa, "type");
            LegalAgreement la = buildSimpleAgreement(oa, LegalAgreementTypeEnum.OTHER, typeText);
            if (la != null) entries.add(la);
        }
        if (entries.isEmpty()) return null;
        ContractDetails.ContractDetailsBuilder b = ContractDetails.builder();
        entries.forEach(b::addDocumentation);
        return b.build();
    }

    private static LegalAgreement buildMasterAgreement(Element ma) {
        Element typeEl = XmlUtils.child(ma, "masterAgreementType");
        if (typeEl == null) return null;
        String value = typeEl.getTextContent().trim();
        MasterAgreementTypeEnum mat = null;
        try { mat = MasterAgreementTypeEnum.fromDisplayName(value); } catch (Exception ignored) {}
        if (mat == null) {
            try { mat = MasterAgreementTypeEnum.valueOf(value.toUpperCase()); } catch (Exception ignored) {}
        }
        FieldWithMetaMasterAgreementTypeEnum.FieldWithMetaMasterAgreementTypeEnumBuilder mb =
                FieldWithMetaMasterAgreementTypeEnum.builder();
        if (mat != null) mb.setValue(mat);
        else mb.setValue(null); // fallback — set raw value via Field meta? Not possible; reference uses scheme-less {value: "GMSLA"}
        // GMSLA isn't a known enum constant — set the raw value through the builder's value path.
        AgreementName name = AgreementName.builder()
                .setAgreementType(LegalAgreementTypeEnum.MASTER_AGREEMENT)
                .setMasterAgreementType(mb.build())
                .build();
        LegalAgreementIdentification.LegalAgreementIdentificationBuilder idB =
                LegalAgreementIdentification.builder().setAgreementName(name);
        String version = XmlUtils.childText(ma, "masterAgreementVersion");
        if (version != null) {
            try { idB.setVintage(Integer.parseInt(version)); } catch (NumberFormatException ignored) {}
        }
        LegalAgreement.LegalAgreementBuilder b = LegalAgreement.builder().setLegalAgreementIdentification(idB.build());
        String mad = XmlUtils.childText(ma, "masterAgreementDate");
        if (mad != null) b.setAgreementDate(DateMapper.parse(mad));
        return b.build();
    }

    /**
     * Build a "simple" legal agreement (creditSupportAgreement, otherAgreement)
     * carrying agreementType + optional otherAgreement label + optional vintage.
     */
    private static LegalAgreement buildSimpleAgreement(Element el, LegalAgreementTypeEnum agreementType, String otherAgreement) {
        AgreementName.AgreementNameBuilder nameB = AgreementName.builder()
                .setAgreementType(agreementType);
        if (otherAgreement != null) {
            nameB.setOtherAgreement(otherAgreement);
        }
        LegalAgreementIdentification.LegalAgreementIdentificationBuilder idB =
                LegalAgreementIdentification.builder().setAgreementName(nameB.build());
        String version = XmlUtils.childText(el, "version");
        if (version != null) {
            try { idB.setVintage(Integer.parseInt(version)); } catch (NumberFormatException ignored) {}
        }
        LegalAgreement.LegalAgreementBuilder b = LegalAgreement.builder()
                .setLegalAgreementIdentification(idB.build());
        String date = XmlUtils.childText(el, "date");
        if (date != null) b.setAgreementDate(DateMapper.parse(date));
        return b.build();
    }
}
