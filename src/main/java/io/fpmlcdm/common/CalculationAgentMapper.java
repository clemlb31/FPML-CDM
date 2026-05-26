package io.fpmlcdm.common;

import cdm.base.datetime.BusinessCenterEnum;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.staticdata.party.AncillaryParty;
import cdm.base.staticdata.party.AncillaryRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.observable.asset.CalculationAgent;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps FpML {@code <calculationAgent>} into:
 *   - {@code EconomicTerms.calculationAgent.calculationAgentParty} (an {@link AncillaryRoleEnum})
 *   - one {@link AncillaryParty} entry per role with the referenced party.
 *
 * The standard mapping for {@code <calculationAgentPartyReference>} is
 * {@link AncillaryRoleEnum#CALCULATION_AGENT_INDEPENDENT}.
 */
public final class CalculationAgentMapper {

    private CalculationAgentMapper() {}

    public record Result(CalculationAgent calculationAgent, List<AncillaryParty> ancillaryParties) {}

    public static Result map(Element trade) {
        if (trade == null) return new Result(null, List.of());
        Element calculationAgent = XmlUtils.child(trade, "calculationAgent");
        Element businessCenter = XmlUtils.child(trade, "calculationAgentBusinessCenter");
        if (calculationAgent == null && businessCenter == null) return new Result(null, List.of());

        AncillaryRoleEnum role = AncillaryRoleEnum.CALCULATION_AGENT_INDEPENDENT;
        CalculationAgent.CalculationAgentBuilder ca = CalculationAgent.builder();
        List<AncillaryParty> aps = new ArrayList<>();
        boolean caHasData = false;

        if (calculationAgent != null) {
            List<Element> refs = XmlUtils.children(calculationAgent, "calculationAgentPartyReference");
            if (!refs.isEmpty()) {
                ca.setCalculationAgentParty(role);
                caHasData = true;
                AncillaryParty.AncillaryPartyBuilder b = AncillaryParty.builder().setRole(role);
                for (Element r : refs) {
                    String href = r.getAttribute("href");
                    if (href != null && !href.isEmpty()) {
                        b.addPartyReference(ReferenceWithMetaParty.builder()
                                .setExternalReference(href).build());
                    }
                }
                aps.add(b.build());
            }
        }
        if (businessCenter != null) {
            String value = businessCenter.getTextContent().trim();
            String scheme = businessCenter.getAttribute("businessCenterScheme");
            FieldWithMetaBusinessCenterEnum.FieldWithMetaBusinessCenterEnumBuilder fb =
                    FieldWithMetaBusinessCenterEnum.builder();
            try { fb.setValue(BusinessCenterEnum.valueOf(value)); }
            catch (IllegalArgumentException ignored) {}
            if (scheme != null && !scheme.isEmpty()) {
                fb.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            ca.setCalculationAgentBusinessCenter(fb.build());
            caHasData = true;
        }
        // Avoid emitting an empty CalculationAgent {} when only <calculationAgentParty> text is given.
        return new Result(caHasData ? ca.build() : null, aps);
    }
}
