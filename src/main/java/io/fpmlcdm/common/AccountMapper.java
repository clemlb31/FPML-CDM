package io.fpmlcdm.common;

import cdm.base.staticdata.party.Account;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads top-level FpML {@code <account>} elements and produces CDM {@link Account} objects.
 *
 * Mapping rules (observed from rates-5-10 reference):
 *   - {@code <accountId>}                 → accountNumber.value
 *   - {@code <accountName>}               → accountName.value (when present)
 *   - {@code <accountBeneficiary href>}   → accountBeneficiary.externalReference
 *   - {@code <servicingParty href>}       → servicingParty.externalReference
 *   - partyReference defaults to accountBeneficiary when no explicit partyReference present
 *   - the account's FpML {@code id} attribute is copied to {@code meta.externalKey}
 */
public final class AccountMapper {

    private AccountMapper() {}

    public static List<Account> map(Document doc) {
        // Collect the set of account ids that are referenced from inside any swap stream
        // via <payerAccountReference> / <receiverAccountReference>. Only those accounts
        // emit a partyReference (mirroring the accountBeneficiary).
        java.util.Set<String> referencedAccountIds = new java.util.HashSet<>();
        for (String tag : new String[]{"payerAccountReference", "receiverAccountReference"}) {
            NodeList refs = doc.getDocumentElement().getElementsByTagNameNS("*", tag);
            for (int i = 0; i < refs.getLength(); i++) {
                Element r = (Element) refs.item(i);
                String href = r.getAttribute("href");
                if (href != null && !href.isEmpty()) referencedAccountIds.add(href);
            }
        }

        List<Account> out = new ArrayList<>();
        NodeList nodes = doc.getDocumentElement().getElementsByTagNameNS("*", "account");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (el.getParentNode() != doc.getDocumentElement()) continue;
            out.add(build(el, referencedAccountIds.contains(el.getAttribute("id"))));
        }
        return out;
    }

    private static Account build(Element fpml, boolean mirrorPartyReference) {
        Account.AccountBuilder b = Account.builder();
        String id = fpml.getAttribute("id");

        Element accountIdEl = XmlUtils.child(fpml, "accountId");
        if (accountIdEl != null) {
            String accountId = accountIdEl.getTextContent().trim();
            FieldWithMetaString.FieldWithMetaStringBuilder anB = FieldWithMetaString.builder().setValue(accountId);
            String accountIdScheme = accountIdEl.getAttribute("accountIdScheme");
            if (accountIdScheme != null && !accountIdScheme.isEmpty()) {
                anB.setMeta(MetaFields.builder().setScheme(accountIdScheme).build());
            }
            b.setAccountNumber(anB.build());
        }
        String accountName = XmlUtils.childText(fpml, "accountName");
        if (accountName != null) {
            b.setAccountName(FieldWithMetaString.builder().setValue(accountName).build());
        }

        Element ab = XmlUtils.child(fpml, "accountBeneficiary");
        Element sp = XmlUtils.child(fpml, "servicingParty");
        Element pr = XmlUtils.child(fpml, "partyReference");

        ReferenceWithMetaParty beneficiaryRef = null;
        if (ab != null) {
            String href = ab.getAttribute("href");
            beneficiaryRef = ReferenceWithMetaParty.builder().setExternalReference(href).build();
            b.setAccountBeneficiary(beneficiaryRef);
        }
        if (sp != null) {
            b.setServicingParty(ReferenceWithMetaParty.builder()
                    .setExternalReference(sp.getAttribute("href")).build());
        }
        // partyReference: explicit, else mirror accountBeneficiary IFF the account is referenced
        // from a swap stream (otherwise the reference dataset leaves it absent).
        if (pr != null) {
            b.setPartyReference(ReferenceWithMetaParty.builder()
                    .setExternalReference(pr.getAttribute("href")).build());
        } else if (beneficiaryRef != null && mirrorPartyReference) {
            b.setPartyReference(beneficiaryRef);
        }

        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return b.build();
    }
}
