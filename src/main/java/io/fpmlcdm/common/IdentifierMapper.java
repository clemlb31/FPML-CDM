package io.fpmlcdm.common;

import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.TradeIdentifierTypeEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.TradeIdentifier;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Element;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps {@code <partyTradeIdentifier>} into CDM {@link Identifier} (a.k.a. tradeIdentifier).
 *
 * FpML variants we observe in the rates test pack:
 *   1. {@code <partyTradeIdentifier>} with {@code <issuer>} + {@code <tradeId>}
 *      → identifierType = UniqueTransactionIdentifier when scheme contains "uti"
 *   2. {@code <partyTradeIdentifier>} with {@code <partyReference>} + {@code <tradeId>}
 *      → issuerReference set
 *   3. {@code <partyTradeIdentifier>} with {@code <partyReference>} + {@code <versionedTradeId>}
 *      → assignedIdentifier carries version
 */
public final class IdentifierMapper {

    private IdentifierMapper() {}

    public static TradeIdentifier map(Element fpml) {
        if (fpml == null) return null;
        TradeIdentifier.TradeIdentifierBuilder b = TradeIdentifier.builder();

        // Issuer (text or referenced party)
        Element issuer = XmlUtils.child(fpml, "issuer");
        if (issuer != null) {
            FieldWithMetaString iss = FieldWithMetaString.builder()
                    .setValue(issuer.getTextContent().trim())
                    .setMeta(metaScheme(issuer.getAttribute("issuerIdScheme")))
                    .build();
            b.setIssuer(iss);
        } else {
            Element partyRef = XmlUtils.child(fpml, "partyReference");
            if (partyRef != null) {
                String href = partyRef.getAttribute("href");
                b.setIssuerReference(ReferenceWithMetaParty.builder()
                        .setExternalReference(href)
                        .build());
            }
        }

        // tradeId (plain) OR versionedTradeId
        // When multiple <tradeId> children are present we keep only the LAST per the reference.
        java.util.List<Element> tradeIds = XmlUtils.children(fpml, "tradeId");
        Element tradeId = tradeIds.isEmpty() ? null : tradeIds.get(tradeIds.size() - 1);
        Element versioned = XmlUtils.child(fpml, "versionedTradeId");
        AssignedIdentifier.AssignedIdentifierBuilder ai = AssignedIdentifier.builder();
        String scheme = null;
        String value = null;

        if (tradeId != null) {
            value = tradeId.getTextContent().trim();
            scheme = tradeId.getAttribute("tradeIdScheme");
        } else if (versioned != null) {
            Element vId = XmlUtils.child(versioned, "tradeId");
            value = vId == null ? null : vId.getTextContent().trim();
            scheme = vId == null ? null : vId.getAttribute("tradeIdScheme");
            String version = XmlUtils.childText(versioned, "version");
            if (version != null) {
                try { ai.setVersion(BigInteger.valueOf(Long.parseLong(version)).intValue()); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (value != null) {
            ai.setIdentifier(FieldWithMetaString.builder()
                    .setValue(value)
                    .setMeta(metaScheme(scheme))
                    .build());
            b.addAssignedIdentifier(ai.build());
        }

        // Identifier type — derive from the scheme URI (not set for versionedTradeId per dataset)
        if (versioned == null) {
            TradeIdentifierTypeEnum type = identifierType(scheme);
            if (type != null) b.setIdentifierType(type);
        }

        return b.build();
    }

    /** Map scheme URI → TradeIdentifierTypeEnum (only the canonical FpML schemes count). */
    private static TradeIdentifierTypeEnum identifierType(String scheme) {
        if (scheme == null) return null;
        String low = scheme.toLowerCase();
        if (low.contains("unique-transaction-identifier") || low.endsWith("/uti")) {
            return TradeIdentifierTypeEnum.UNIQUE_TRANSACTION_IDENTIFIER;
        }
        if (low.contains("unique-swap-identifier") || low.endsWith("/usi")) {
            return TradeIdentifierTypeEnum.UNIQUE_SWAP_IDENTIFIER;
        }
        return null;
    }

    public static List<TradeIdentifier> mapAll(List<Element> fpmlEls) {
        return fpmlEls.stream()
                .map(IdentifierMapper::map)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Maps one FpML {@code <partyTradeIdentifier>} into a list of {@link TradeIdentifier}.
     *
     * Reference dataset pattern: any non-versioned PTI (i.e. carries a plain {@code <tradeId>},
     * not a {@code <versionedTradeId>}) yields TWO entries:
     *   - first WITHOUT issuer / issuerReference
     *   - second WITH the issuer or issuerReference
     * Versioned PTIs collapse to a single entry.
     */
    public static List<TradeIdentifier> mapWithSplit(Element fpml) {
        List<TradeIdentifier> out = new ArrayList<>();
        if (fpml == null) return out;

        java.util.List<Element> tradeIds = XmlUtils.children(fpml, "tradeId");
        Element tradeId = tradeIds.isEmpty() ? null : tradeIds.get(tradeIds.size() - 1);
        Element versioned = XmlUtils.child(fpml, "versionedTradeId");
        Element issuer = XmlUtils.child(fpml, "issuer");
        Element partyRef = XmlUtils.child(fpml, "partyReference");
        boolean hasIssuerInfo = issuer != null || partyRef != null;

        if (tradeId != null && versioned == null && hasIssuerInfo) {
            TradeIdentifier withoutIssuer = buildIdentifier(fpml, false);
            TradeIdentifier withIssuer = buildIdentifier(fpml, true);
            // Order varies by source: <issuer> → unattributed first, then attributed;
            // <partyReference> → attributed first, then unattributed.
            boolean issuerFirst = issuer == null && partyRef != null;
            if (issuerFirst) {
                if (withIssuer != null) out.add(withIssuer);
                if (withoutIssuer != null) out.add(withoutIssuer);
            } else {
                if (withoutIssuer != null) out.add(withoutIssuer);
                if (withIssuer != null) out.add(withIssuer);
            }
            return out;
        }
        TradeIdentifier single = map(fpml);
        if (single != null) out.add(single);
        return out;
    }

    private static TradeIdentifier buildIdentifier(Element fpml, boolean includeIssuer) {
        TradeIdentifier.TradeIdentifierBuilder b = TradeIdentifier.builder();
        Element issuer = XmlUtils.child(fpml, "issuer");
        Element partyRef = XmlUtils.child(fpml, "partyReference");
        if (includeIssuer) {
            if (issuer != null) {
                b.setIssuer(FieldWithMetaString.builder()
                        .setValue(issuer.getTextContent().trim())
                        .setMeta(metaScheme(issuer.getAttribute("issuerIdScheme")))
                        .build());
            } else if (partyRef != null) {
                String href = partyRef.getAttribute("href");
                b.setIssuerReference(ReferenceWithMetaParty.builder()
                        .setExternalReference(href).build());
            }
        }
        java.util.List<Element> tradeIds = XmlUtils.children(fpml, "tradeId");
        Element tradeId = tradeIds.isEmpty() ? null : tradeIds.get(tradeIds.size() - 1);
        if (tradeId != null) {
            String value = tradeId.getTextContent().trim();
            String scheme = tradeId.getAttribute("tradeIdScheme");
            AssignedIdentifier ai = AssignedIdentifier.builder()
                    .setIdentifier(FieldWithMetaString.builder()
                            .setValue(value)
                            .setMeta(metaScheme(scheme))
                            .build())
                    .build();
            b.addAssignedIdentifier(ai);
            TradeIdentifierTypeEnum type = identifierType(scheme);
            if (type != null) b.setIdentifierType(type);
        }
        return b.build();
    }

    private static MetaFields metaScheme(String scheme) {
        if (scheme == null || scheme.isEmpty()) return null;
        return MetaFields.builder().setScheme(scheme).build();
    }
}
