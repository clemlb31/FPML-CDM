package io.fpmlcdm.common;

import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.TradeIdentifierTypeEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.TradeIdentifier;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Element;

import java.math.BigInteger;
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
        Element tradeId = XmlUtils.child(fpml, "tradeId");
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

        // Identifier type — derive from the scheme URI
        if (scheme != null && scheme.toLowerCase().contains("uti")) {
            b.setIdentifierType(TradeIdentifierTypeEnum.UNIQUE_TRANSACTION_IDENTIFIER);
        } else if (scheme != null && scheme.toLowerCase().contains("usi")) {
            b.setIdentifierType(TradeIdentifierTypeEnum.UNIQUE_SWAP_IDENTIFIER);
        }

        return b.build();
    }

    public static List<TradeIdentifier> mapAll(List<Element> fpmlEls) {
        return fpmlEls.stream()
                .map(IdentifierMapper::map)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static MetaFields metaScheme(String scheme) {
        if (scheme == null || scheme.isEmpty()) return null;
        return MetaFields.builder().setScheme(scheme).build();
    }
}
