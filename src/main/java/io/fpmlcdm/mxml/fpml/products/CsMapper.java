package io.fpmlcdm.mxml.fpml.products;

import io.fpmlcdm.core.xml.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.List;

/**
 * MXML -> FpML mapper for cross-currency swaps (typology "Xccy Swap").
 *
 * <p>A vanilla (non-MTM) cross-currency swap is structurally an IRS with two
 * differences, both principal-exchange related; per-leg currency is already
 * handled by the reused stream builder. So this extends {@link SwapMapper} and
 * only overrides the two principal-exchange hooks:
 * <ul>
 *   <li>{@code <principalExchanges>} flags (initial/final/intermediate) after
 *       calculationPeriodAmount, from {@code streamTemplate/capitalExchanges};</li>
 *   <li>{@code <principalExchange>} cashflow entries at the head of cashflows,
 *       from {@code cashFlows/capitalFlows/flow}, signed relative to the stream's
 *       receiver party.</li>
 * </ul>
 *
 * <p>Scope: vanilla fixed/float or float/float CS on a {@code dataDocument}.
 * Out of scope: FX-linked (MTM) notional, lifecycle envelopes.
 */
public final class CsMapper extends SwapMapper {

    private static final String FPML_NS = "http://www.fpml.org/FpML-5/confirmation";

    @Override
    public String mxmlProductType() {
        return "Xccy Swap"; // detector upper-cases -> "XCCY SWAP"
    }

    @Override
    protected void emitPrincipalExchangesFlags(Document doc, Element swapStream, Element stream) {
        Element st = XmlUtils.getFirstChildElement(stream, "streamTemplate");
        Element ce = st != null ? XmlUtils.getFirstChildElement(st, "capitalExchanges") : null;
        if (ce == null) return;
        String initial = XmlUtils.getTextContent(ce, "initialCapitalExchange");
        String finalEx = XmlUtils.getTextContent(ce, "finalCapitalExchange");
        String interEx = XmlUtils.getTextContent(ce, "intermediateCapitalExchange");
        // No exchanges at all -> omit the block (e.g. the BusinessDayConvention case).
        if (!"true".equals(initial) && !"true".equals(finalEx) && !"true".equals(interEx)) return;

        Element pe = el(doc, swapStream, "principalExchanges");
        // Reproduce the reference's MXML order: initial, final, intermediate.
        elText(doc, pe, "initialExchange", boolText(initial));
        elText(doc, pe, "finalExchange", boolText(finalEx));
        elText(doc, pe, "intermediateExchange", boolText(interEx));
    }

    @Override
    protected void emitPrincipalExchanges(Document doc, Element cashflows, Element stream) {
        Element cashFlows = XmlUtils.getFirstChildElement(stream, "cashFlows");
        Element capitalFlows = cashFlows != null
                ? XmlUtils.getFirstChildElement(cashFlows, "capitalFlows") : null;
        if (capitalFlows == null) return;

        String streamReceiver = stripHash(attrOfChild(stream, "receiverPartyReference", "href"));

        for (Element flow : XmlUtils.getChildElements(capitalFlows, "flow")) {
            String date = isoDate(XmlUtils.getTextContent(flow, "date"));
            String ccy = XmlUtils.getTextContent(flow, "currency");
            String amount = XmlUtils.getTextContent(flow, "amount");
            String flowReceiver = stripHash(attrOfChild(flow, "receiverPartyReference", "href"));

            // Sign relative to the stream's receiver: receiver gets capital -> +, pays -> -.
            String signed = amount;
            if (streamReceiver != null && !streamReceiver.equals(flowReceiver)) {
                signed = negate(amount);
            }

            Element pe = el(doc, cashflows, "principalExchange");
            elText(doc, pe, "adjustedPrincipalExchangeDate", date);
            elText(doc, pe, "principalExchangeAmount", signed);
            Element pv = el(doc, pe, "presentValuePrincipalExchangeAmount");
            elText(doc, pv, "currency", ccy);
            elText(doc, pv, "amount", signed);
        }
    }

    private static String boolText(String v) {
        return "true".equals(v) ? "true" : "false";
    }

    private static String negate(String amount) {
        if (amount == null || amount.isEmpty()) return amount;
        return amount.startsWith("-") ? amount.substring(1) : "-" + amount;
    }
}
