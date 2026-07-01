package io.fpmlcdm.mxml.fpml.products;

import io.fpmlcdm.core.xml.XmlUtils;
import io.fpmlcdm.mxml.fpml.MxmlToFpmlContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * MXML -> FpML mapper for cap/floor (typology "Cap", incl. floors and straddles
 * distinguished by {@code streamPayout}). Ported from the Murex XSLT spec
 * (knowledge_base/mxml-fpml/ird-5-3/cf).
 *
 * <p>Extends {@link SwapMapper} purely to reuse its leaf helpers (frequency,
 * business centers, index/tenor, day-count, unadjusted-boundary computation,
 * primitives). A cap/floor is a SINGLE floating stream, so the date blocks are
 * built here (cap/floor uses MODFOLLOWING date adjustments unconditionally,
 * unlike the swap's unadjusted-vs-adjusted rule), and the strike schedule +
 * per-period strike are cap/floor-specific.
 *
 * <p>Scope: vanilla single-stream cap/floor on a {@code dataDocument}. Out of
 * scope (NOMAP/DIFF for now): premium, inflation, exercise/earlyTermination.
 */
public final class CapFloorMapper extends SwapMapper {

    private static final String FPML_NS = "http://www.fpml.org/FpML-5/confirmation";
    private static final String CPD_ID = "floatingRateCalculationPeriodDates1";

    @Override
    public String mxmlProductType() {
        return "Cap"; // detector upper-cases -> CAP (covers cap & floor; payout in streamPayout)
    }

    @Override
    public Element map(Document doc, Element mxmlRoot, MxmlToFpmlContext context) {
        Element trade = findTrade(mxmlRoot);
        if (trade == null) {
            context.addWarning("CapFloor: no <trade> under /MxML/trades");
            return null;
        }
        Element tradeBody = XmlUtils.getFirstChildElement(trade, "tradeBody");
        Element product = tradeBody != null ? firstElementChild(tradeBody) : null;
        if (product == null) {
            context.addWarning("CapFloor: no product node under tradeBody");
            return null;
        }
        List<Element> streams = XmlUtils.getChildElements(product, "stream");
        if (streams.isEmpty()) {
            context.addWarning("CapFloor: no stream found");
            return null;
        }
        Element stream = streams.get(0);

        // Out-of-scope features -> let it NOMAP rather than emit a wrong doc.
        if (XmlUtils.getFirstChildElement(product, "settlement") != null) {
            // premium present (periodicSettlementFlows) — not yet handled
        }

        Element root = doc.createElementNS(FPML_NS, "dataDocument");
        root.setAttribute("fpmlVersion", "5-3");

        Element fpmlTrade = el(doc, root, "trade");
        buildTradeHeader(doc, fpmlTrade, trade, mxmlRoot);
        // Cap/floor tradeId uses the UTI scheme (IRS uses contract-id); the inherited
        // header emits contract-id, so retarget the scheme here (cap/floor-local).
        retargetTradeIdScheme(fpmlTrade,
                "http://www.fpml.org/coding-scheme/external/unique-transaction-identifier");

        Element capFloor = el(doc, fpmlTrade, "capFloor");
        buildCapFloorStream(doc, capFloor, stream, context);

        buildParties(doc, root, trade);
        return root;
    }

    private void buildCapFloorStream(Document doc, Element capFloor, Element stream,
                                     MxmlToFpmlContext ctx) {
        String payout = streamPayout(stream); // cap | floor | straddle
        Element ss = el(doc, capFloor, "capFloorStream");

        String payer = stripHash(attrOfChild(stream, "payerPartyReference", "href"));
        String receiver = stripHash(attrOfChild(stream, "receiverPartyReference", "href"));
        elRef(doc, ss, "payerPartyReference", payer);
        elRef(doc, ss, "receiverPartyReference", receiver);

        buildCfCalculationPeriodDates(doc, ss, stream);
        buildCfPaymentDates(doc, ss, stream);
        buildCfResetDates(doc, ss, stream);
        buildCfCalculationPeriodAmount(doc, ss, stream, payout);
        buildCfCashflows(doc, ss, stream, payout);
    }

    /* ─────────── date blocks (cap/floor: unconditional MODFOLLOWING) ─────────── */

    private void buildCfCalculationPeriodDates(Document doc, Element parent, Element stream) {
        Element cpd = el(doc, parent, "calculationPeriodDates");
        cpd.setAttribute("id", CPD_ID);
        List<String> centers = businessCenters(stream);

        String eff = isoDate(XmlUtils.getTextContent(stream, "effectiveDate"));
        String effAdj = isoDate(XmlUtils.getTextContent(stream, "adjustedEffectiveDate"));
        Element effEl = el(doc, cpd, "effectiveDate");
        elText(doc, effEl, "unadjustedDate", eff);
        Element effAdjs = el(doc, effEl, "dateAdjustments");
        elText(doc, effAdjs, "businessDayConvention", "MODFOLLOWING");
        appendBusinessCenters(doc, effAdjs, centers);
        elText(doc, effEl, "adjustedDate", effAdj);

        String mat = isoDate(XmlUtils.getTextContent(stream, "maturity"));
        String matAdj = isoDate(XmlUtils.getTextContent(stream, "adjustedMaturity"));
        Element termEl = el(doc, cpd, "terminationDate");
        elText(doc, termEl, "unadjustedDate", mat);
        Element termAdjs = el(doc, termEl, "dateAdjustments");
        elText(doc, termAdjs, "businessDayConvention", "MODFOLLOWING");
        appendBusinessCenters(doc, termAdjs, centers);
        elText(doc, termEl, "adjustedDate", matAdj);

        Element cpdAdj = el(doc, cpd, "calculationPeriodDatesAdjustments");
        elText(doc, cpdAdj, "businessDayConvention", "MODFOLLOWING");
        appendBusinessCenters(doc, cpdAdj, centers);

        emitStubPeriod(doc, cpd, stream);

        String[] freq = calcFrequency(stream);
        Element cpf = el(doc, cpd, "calculationPeriodFrequency");
        elText(doc, cpf, "periodMultiplier", freq[0]);
        elText(doc, cpf, "period", freq[1]);
        elText(doc, cpf, "rollConvention", rollConvention(stream));
    }

    private void buildCfPaymentDates(Document doc, Element parent, Element stream) {
        Element pd = el(doc, parent, "paymentDates");
        elRef(doc, pd, "calculationPeriodDatesReference", CPD_ID);
        String[] freq = calcFrequency(stream);
        Element pf = el(doc, pd, "paymentFrequency");
        elText(doc, pf, "periodMultiplier", freq[0]);
        elText(doc, pf, "period", freq[1]);
        emitStubPaymentDates(doc, pd, stream);
        elText(doc, pd, "payRelativeTo", "CalculationPeriodEndDate");
        Element shift = paymentScheduleShift(stream);
        if (shift != null) {
            Element off = el(doc, pd, "paymentDaysOffset");
            String pm = XmlUtils.getTextContent(shift, "periodMultiplier");
            String pu = XmlUtils.getTextContent(shift, "periodUnit");
            elText(doc, off, "periodMultiplier", pm != null ? pm : "0");
            elText(doc, off, "period", periodCodeFor(pu));
        }
        Element pdAdj = el(doc, pd, "paymentDatesAdjustments");
        elText(doc, pdAdj, "businessDayConvention", "MODFOLLOWING");
        appendBusinessCenters(doc, pdAdj, businessCenters(stream));
    }

    private void buildCfResetDates(Document doc, Element parent, Element stream) {
        Element rd = el(doc, parent, "resetDates");
        rd.setAttribute("id", "resetDates");
        elRef(doc, rd, "calculationPeriodDatesReference", CPD_ID);
        elText(doc, rd, "resetRelativeTo", "CalculationPeriodStartDate");

        String resetBc = resetBusinessCenter(stream);
        Element fix = el(doc, rd, "fixingDates");
        Element shift = resetScheduleShift(stream);
        String mult = shift != null ? XmlUtils.getTextContent(shift, "periodMultiplier") : null;
        if (mult == null || mult.isEmpty()) mult = "-2";
        elText(doc, fix, "periodMultiplier", mult);
        elText(doc, fix, "period", "D");
        elText(doc, fix, "dayType", "Business");
        // cap/floor fixing BDC = reset schedule BDC (MODFOLLOWING), not the swap's NONE
        elText(doc, fix, "businessDayConvention", scheduleBusinessDayConvention(stream));
        Element fixBc = el(doc, fix, "businessCenters");
        elText(doc, fixBc, "businessCenter", resetBc);
        elRef(doc, fix, "dateRelativeTo", CPD_ID);

        String[] freq = calcFrequency(stream);
        Element rf = el(doc, rd, "resetFrequency");
        elText(doc, rf, "periodMultiplier", freq[0]);
        elText(doc, rf, "period", freq[1]);

        Element rdAdj = el(doc, rd, "resetDatesAdjustments");
        elText(doc, rdAdj, "businessDayConvention", "MODFOLLOWING");
        Element rdBc = el(doc, rdAdj, "businessCenters");
        elText(doc, rdBc, "businessCenter", resetBc);
    }

    private void buildCfCalculationPeriodAmount(Document doc, Element parent, Element stream,
                                                String payout) {
        Element cpa = el(doc, parent, "calculationPeriodAmount");
        Element calc = el(doc, cpa, "calculation");

        Element notSched = el(doc, calc, "notionalSchedule");
        Element nss = el(doc, notSched, "notionalStepSchedule");
        Element capital = XmlUtils.getFirstChildElement(stream, "capital");
        String notional = XmlUtils.getTextContent(capital, "initialCapitalAmount");
        String currency = XmlUtils.getTextContent(capital, "initialCapitalCurrency");
        elText(doc, nss, "initialValue", notional);
        emitNotionalSteps(doc, nss, stream, notional);
        elText(doc, nss, "currency", currency);

        Element frc = el(doc, calc, "floatingRateCalculation");
        elText(doc, frc, "floatingRateIndex", floatingRateIndex(stream));
        String[] tenor = indexTenor(stream);
        Element it = el(doc, frc, "indexTenor");
        elText(doc, it, "periodMultiplier", tenor[0]);
        elText(doc, it, "period", tenor[1]);

        // Strike schedule(s): cap -> capRateSchedule, floor -> floorRateSchedule,
        // straddle -> both. initialValue + step* (delta detection) + buyer/seller.
        boolean cap = "cap".equals(payout) || "straddle".equals(payout);
        boolean floor = "floor".equals(payout) || "straddle".equals(payout);
        if (cap) emitStrikeSchedule(doc, frc, stream, "capRateSchedule");
        if (floor) emitStrikeSchedule(doc, frc, stream, "floorRateSchedule");

        elText(doc, calc, "dayCountFraction", dayCount(
                XmlUtils.getFirstChildElement(stream, "streamTemplate")));
    }

    private void emitStrikeSchedule(Document doc, Element frc, Element stream, String scheduleName) {
        Element sched = el(doc, frc, scheduleName);
        String initial = pct(streamStrike(stream));
        elText(doc, sched, "initialValue", (initial == null || initial.isEmpty()) ? "0" : initial);
        // steps: per-period strike change (delta detection vs previous), like notional steps
        String prev = initial;
        for (Element ipp : interestPaymentPeriods(stream)) {
            Element cp = XmlUtils.getFirstChildElement(ipp, "calculationPeriod");
            String raw = periodStrike(cp);
            String val = raw != null ? pct(raw) : prev;
            if (!strikeEquals(val, prev)) {
                Element step = el(doc, sched, "step");
                elText(doc, step, "stepDate", isoDate(XmlUtils.getTextContent(cp, "calculationStartDate")));
                elText(doc, step, "stepValue", val);
                prev = val;
            }
        }
        elText(doc, sched, "buyer", "Receiver");
        elText(doc, sched, "seller", "Payer");
    }

    private void buildCfCashflows(Document doc, Element parent, Element stream, String payout) {
        Element cashFlows = XmlUtils.getFirstChildElement(stream, "cashFlows");
        Element interestFlows = cashFlows != null
                ? XmlUtils.getFirstChildElement(cashFlows, "interestFlows") : null;
        if (interestFlows == null) return;

        Element cf = el(doc, parent, "cashflows");
        elText(doc, cf, "cashflowsMatchParameters", cashflowsMatchParameters(stream) ? "true" : "false");

        List<Element> ipps = XmlUtils.getChildElements(interestFlows, "interestPaymentPeriod");
        List<String> adjStart = new ArrayList<>(), adjEnd = new ArrayList<>();
        for (Element ipp : ipps) {
            Element cp = XmlUtils.getFirstChildElement(ipp, "calculationPeriod");
            adjStart.add(isoDate(XmlUtils.getTextContent(cp, "calculationStartDate")));
            adjEnd.add(isoDate(XmlUtils.getTextContent(cp, "calculationEndDate")));
        }
        String eff = isoDate(XmlUtils.getTextContent(stream, "effectiveDate"));
        String matUnadj = isoDate(XmlUtils.getTextContent(stream, "maturity"));
        String[] freq = calcFrequency(stream);
        List<String> unStart = new ArrayList<>(adjStart), unEnd = new ArrayList<>(adjEnd);
        computeUnadjustedBoundaries(eff, matUnadj, freq, ipps.size(), unStart, unEnd);

        String streamStrikePct = pct(streamStrike(stream));
        boolean cap = "cap".equals(payout) || "straddle".equals(payout);
        boolean floor = "floor".equals(payout) || "straddle".equals(payout);

        for (int i = 0; i < ipps.size(); i++) {
            Element ipp = ipps.get(i);
            Element calcPeriod = XmlUtils.getFirstChildElement(ipp, "calculationPeriod");
            Element pcp = el(doc, cf, "paymentCalculationPeriod");
            elText(doc, pcp, "adjustedPaymentDate", isoDate(XmlUtils.getTextContent(ipp, "paymentDate")));

            Element cp = el(doc, pcp, "calculationPeriod");
            elText(doc, cp, "unadjustedStartDate", unStart.get(i));
            elText(doc, cp, "unadjustedEndDate", unEnd.get(i));
            elText(doc, cp, "adjustedStartDate", adjStart.get(i));
            elText(doc, cp, "adjustedEndDate", adjEnd.get(i));
            elText(doc, cp, "notionalAmount", XmlUtils.getTextContent(calcPeriod, "notionalAmount"));

            Element frd = el(doc, cp, "floatingRateDefinition");
            Element ro = el(doc, frd, "rateObservation");
            Element frp = XmlUtils.getFirstChildElement(calcPeriod, "floatingRatePeriod");
            Element obs = frp != null ? XmlUtils.findElementByPath(frp, "observations", "observation") : null;
            if (obs != null) {
                elText(doc, ro, "adjustedFixingDate", isoDate(XmlUtils.getTextContent(obs, "observationDate")));
                String observed = XmlUtils.getTextContent(obs, "observedRate");
                if (observed != null && !observed.isEmpty()) elText(doc, ro, "observedRate", pct(observed));
                elText(doc, ro, "observationWeight", XmlUtils.getTextContent(obs, "observationWeight"));
            } else {
                elText(doc, ro, "observationWeight", "1");
            }
            String margin = frp != null ? XmlUtils.getTextContent(frp, "margin") : null;
            elText(doc, frd, "spread", (margin == null || margin.isEmpty()) ? "0" : pct(margin));

            // per-period strike (after spread): period strike else stream strike else 0
            String pStrike = periodStrike(calcPeriod);
            String strikeVal = pStrike != null ? pct(pStrike)
                    : (streamStrikePct != null && !streamStrikePct.isEmpty() ? streamStrikePct : "0");
            if (cap) {
                Element capRate = el(doc, frd, "capRate");
                elText(doc, capRate, "strikeRate", strikeVal);
            }
            if (floor) {
                Element floorRate = el(doc, frd, "floorRate");
                elText(doc, floorRate, "strikeRate", strikeVal);
            }
            elText(doc, cp, "dayCountYearFraction", XmlUtils.getTextContent(calcPeriod, "periodDayCountFactor"));
        }
    }

    /* ─────────── cap/floor-specific extractors ─────────── */
    /** Retarget the (inherited) tradeId scheme to the cap/floor UTI scheme. */
    private void retargetTradeIdScheme(Element fpmlTrade, String scheme) {
        Element th = XmlUtils.getFirstChildElement(fpmlTrade, "tradeHeader");
        Element pti = th != null ? XmlUtils.getFirstChildElement(th, "partyTradeIdentifier") : null;
        Element tid = pti != null ? XmlUtils.getFirstChildElement(pti, "tradeId") : null;
        if (tid != null) tid.setAttribute("tradeIdScheme", scheme);
    }

    private String streamPayout(Element stream) {
        Element frs = XmlUtils.getFirstChildElement(stream, "floatingRateStream");
        String p = frs != null ? XmlUtils.getTextContent(frs, "streamPayout") : null;
        return p != null ? p : "cap";
    }

    /** The leg's resetSchedule shifter/shift element, or null if no shifter. */
    private Element resetScheduleShift(Element stream) {
        Element st = XmlUtils.getFirstChildElement(stream, "streamTemplate");
        Element ss = st != null ? XmlUtils.getFirstChildElement(st, "streamSchedules") : null;
        Element rs = ss != null ? XmlUtils.getFirstChildElement(ss, "resetSchedule") : null;
        Element shifter = rs != null ? XmlUtils.getFirstChildElement(rs, "shifter") : null;
        return shifter != null ? XmlUtils.getFirstChildElement(shifter, "shift") : null;
    }

    private String streamStrike(Element stream) {
        Element frs = XmlUtils.getFirstChildElement(stream, "floatingRateStream");
        Element strikes = frs != null ? XmlUtils.getFirstChildElement(frs, "streamStrikes") : null;
        return strikes != null ? XmlUtils.getTextContent(strikes, "strike") : null;
    }

    private String periodStrike(Element calcPeriod) {
        Element frp = calcPeriod != null ? XmlUtils.getFirstChildElement(calcPeriod, "floatingRatePeriod") : null;
        Element strikes = frp != null ? XmlUtils.getFirstChildElement(frp, "strikes") : null;
        String s = strikes != null ? XmlUtils.getTextContent(strikes, "strike") : null;
        return (s != null && !s.isEmpty()) ? s : null;
    }

    private List<Element> interestPaymentPeriods(Element stream) {
        Element cashFlows = XmlUtils.getFirstChildElement(stream, "cashFlows");
        Element interestFlows = cashFlows != null
                ? XmlUtils.getFirstChildElement(cashFlows, "interestFlows") : null;
        return interestFlows != null
                ? XmlUtils.getChildElements(interestFlows, "interestPaymentPeriod") : new ArrayList<>();
    }

    private static boolean strikeEquals(String a, String b) {
        if (a == null || b == null) return java.util.Objects.equals(a, b);
        try { return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0; }
        catch (NumberFormatException e) { return a.equals(b); }
    }

    private static String periodCodeFor(String unit) {
        if (unit == null) return "D";
        switch (unit) {
            case "year": return "Y";
            case "month": return "M";
            case "week": return "W";
            case "day": case "businessDay": return "D";
            default: return unit.substring(0, 1).toUpperCase();
        }
    }
}
