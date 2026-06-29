package io.fpmlcdm.report;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares two FpML XML documents semantically (element tree + attributes + leaf
 * text), tolerant of the differences that are not economically meaningful.
 *
 * <p>Design rationale (see the MXML↔FpML investigation): the Murex {@code _ignored}
 * masks only ever ignore <b>non-deterministic timestamps</b>. Rather than parse those
 * mask files, this comparator applies the equivalent rule generically: leaf elements
 * whose local name is a known volatile timestamp ({@code creationTimestamp},
 * {@code ReleaseTime}, {@code ReceiveTime}) are not text-compared.
 *
 * <p>Normalisation applied before/along the diff:
 * <ul>
 *   <li>comparison is by <b>local name</b> (namespace-prefix agnostic; FpML uses a
 *       default namespace);</li>
 *   <li>namespace-declaration attributes ({@code xmlns}, {@code xmlns:*}) are ignored;</li>
 *   <li>whitespace-only text nodes and comments are ignored;</li>
 *   <li>leaf numeric text is compared via {@link BigDecimal#compareTo} (0.025 == 0.0250);</li>
 *   <li>child elements are compared positionally (FpML content is sequence-defined);</li>
 *   <li>volatile timestamp leaves are present-checked but their text is not compared.</li>
 * </ul>
 */
public final class XmlSemanticDiff {

    /** Leaf element local-names whose text is volatile and must not be compared. */
    private static final Set<String> VOLATILE_TIMESTAMP_LEAVES =
            Set.of("creationTimestamp", "ReleaseTime", "ReceiveTime");

    /**
     * Leaf element local-names whose text is an anonymized display value in the
     * reference dataset and therefore not reproducible from the source.
     *
     * <p>{@code partyId} holds the party's display name. The public mxml-fpml
     * dataset scrubs the real Murex names (e.g. {@code MXpress}/{@code MUREX})
     * to anonymized ones ({@code BARCLAYS}/{@code party1}) <em>after</em>
     * generation — the analog of the CDM {@code globalKey}. The party's
     * {@code id} attribute (the reference target of every {@code href}) is still
     * compared, so a counterparty collapse (payer==receiver) is still caught.
     */
    private static final Set<String> ANONYMIZED_LEAVES = Set.of("partyId");

    /** Attribute local-names dropped everywhere (namespace plumbing, not content). */
    private static final Set<String> IGNORED_ATTRS = Set.of("schemaLocation");

    private static final DocumentBuilderFactory DBF = newFactory();

    private static DocumentBuilderFactory newFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setIgnoringComments(true);
        f.setCoalescing(true);
        try {
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // best-effort hardening
        }
        return f;
    }

    private XmlSemanticDiff() {}

    /* ──────────────── entry points ──────────────── */

    public static Result compare(String expectedXml, String actualXml) throws Exception {
        Document e = parse(expectedXml);
        Document a = parse(actualXml);
        return compare(e, a);
    }

    public static Result compare(Document expected, Document actual) {
        List<String> diffs = new ArrayList<>();
        Element eRoot = expected.getDocumentElement();
        Element aRoot = actual.getDocumentElement();
        if (eRoot == null || aRoot == null) {
            if (eRoot != aRoot) {
                diffs.add("~ / : root presence mismatch (expected="
                        + (eRoot == null ? "null" : eRoot.getLocalName())
                        + ", actual=" + (aRoot == null ? "null" : aRoot.getLocalName()) + ")");
            }
            return new Result(diffs);
        }
        walkElement("/" + local(eRoot), eRoot, aRoot, diffs);
        return new Result(diffs);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilder builder = DBF.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /* ──────────────── walk ──────────────── */

    private static void walkElement(String path, Element expected, Element actual, List<String> diffs) {
        // 1. local-name match
        if (!local(expected).equals(local(actual))) {
            diffs.add("~ " + path + " : element <" + local(expected) + "> → <" + local(actual) + ">");
            return;
        }

        // 2. attributes
        compareAttributes(path, expected, actual, diffs);

        // 3. children
        List<Element> eKids = childElements(expected);
        List<Element> aKids = childElements(actual);

        boolean eLeaf = eKids.isEmpty();
        boolean aLeaf = aKids.isEmpty();

        if (eLeaf && aLeaf) {
            // leaf-vs-leaf: compare text unless volatile timestamp or anonymized value
            if (VOLATILE_TIMESTAMP_LEAVES.contains(local(expected))
                    || ANONYMIZED_LEAVES.contains(local(expected))) {
                return; // present on both sides; text intentionally ignored
            }
            compareText(path, text(expected), text(actual), diffs);
            return;
        }

        if (eLeaf != aLeaf) {
            diffs.add("~ " + path + " : structure mismatch (expected "
                    + (eLeaf ? "leaf" : eKids.size() + " children")
                    + ", actual " + (aLeaf ? "leaf" : aKids.size() + " children") + ")");
            return;
        }

        // 4. child comparison.
        //    <party> children are matched by @id (order-independent — the reference
        //    dataset may list parties in a different order); every other child is
        //    compared positionally (FpML content is sequence-defined).
        List<Element> eOrdered = new ArrayList<>();
        List<Element> aOrdered = new ArrayList<>();
        List<Element> eParties = new ArrayList<>();
        List<Element> aParties = new ArrayList<>();
        for (Element k : eKids) (isParty(k) ? eParties : eOrdered).add(k);
        for (Element k : aKids) (isParty(k) ? aParties : aOrdered).add(k);

        comparePositional(path, eOrdered, aOrdered, diffs);
        comparePartiesById(path, eParties, aParties, diffs);
    }

    private static boolean isParty(Element el) {
        return "party".equals(local(el));
    }

    private static void comparePositional(String path, List<Element> eKids,
                                          List<Element> aKids, List<String> diffs) {
        int n = Math.max(eKids.size(), aKids.size());
        java.util.Map<String, Integer> nameSeen = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            if (i >= eKids.size()) {
                Element extra = aKids.get(i);
                diffs.add("+ " + path + "/" + local(extra) + " : unexpected element (actual has extra child #" + i + ")");
                continue;
            }
            if (i >= aKids.size()) {
                Element missing = eKids.get(i);
                diffs.add("- " + path + "/" + local(missing) + " : missing element (expected child #" + i + ")");
                continue;
            }
            Element ec = eKids.get(i);
            Element ac = aKids.get(i);
            String name = local(ec);
            int idx = nameSeen.merge(name, 1, Integer::sum) - 1;
            long sameName = eKids.stream().filter(k -> local(k).equals(name)).count();
            String childPath = path + "/" + name + (sameName > 1 ? "[" + idx + "]" : "");
            walkElement(childPath, ec, ac, diffs);
        }
    }

    private static void comparePartiesById(String path, List<Element> eParties,
                                           List<Element> aParties, List<String> diffs) {
        java.util.Map<String, Element> aById = new java.util.LinkedHashMap<>();
        for (Element a : aParties) {
            aById.put(a.getAttribute("id"), a);
        }
        for (Element e : eParties) {
            String id = e.getAttribute("id");
            Element a = aById.remove(id);
            String childPath = path + "/party[@id=" + id + "]";
            if (a == null) {
                diffs.add("- " + childPath + " : missing party (expected id '" + id + "')");
            } else {
                walkElement(childPath, e, a, diffs);
            }
        }
        for (String leftoverId : aById.keySet()) {
            diffs.add("+ " + path + "/party[@id=" + leftoverId + "] : unexpected party");
        }
    }

    private static void compareAttributes(String path, Element expected, Element actual, List<String> diffs) {
        TreeMap<String, String> e = attrs(expected);
        TreeMap<String, String> a = attrs(actual);
        Set<String> all = new java.util.TreeSet<>();
        all.addAll(e.keySet());
        all.addAll(a.keySet());
        for (String k : all) {
            String ev = e.get(k);
            String av = a.get(k);
            if (ev == null) {
                diffs.add("+ " + path + "@" + k + " : " + av);
            } else if (av == null) {
                diffs.add("- " + path + "@" + k + " : " + ev);
            } else if (!ev.equals(av)) {
                diffs.add("~ " + path + "@" + k + " : " + ev + " → " + av);
            }
        }
    }

    private static void compareText(String path, String expected, String actual, List<String> diffs) {
        String e = expected == null ? "" : expected.trim();
        String a = actual == null ? "" : actual.trim();
        if (e.equals(a)) return;
        // numeric tolerance
        BigDecimal eb = tryDecimal(e);
        BigDecimal ab = tryDecimal(a);
        if (eb != null && ab != null) {
            if (eb.compareTo(ab) != 0) {
                diffs.add("~ " + path + " : " + e + " → " + a);
            }
            return;
        }
        diffs.add("~ " + path + " : " + render(e) + " → " + render(a));
    }

    /* ──────────────── helpers ──────────────── */

    private static String local(Element el) {
        String ln = el.getLocalName();
        return ln != null ? ln : el.getNodeName();
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String text(Element el) {
        // leaf text only (coalesced by parser); ignore element children by contract
        return el.getTextContent();
    }

    private static TreeMap<String, String> attrs(Element el) {
        TreeMap<String, String> map = new TreeMap<>();
        NamedNodeMap nnm = el.getAttributes();
        for (int i = 0; i < nnm.getLength(); i++) {
            Attr at = (Attr) nnm.item(i);
            String name = at.getNodeName();
            String localName = at.getLocalName() != null ? at.getLocalName() : name;
            // drop namespace declarations
            if ("xmlns".equals(name) || name.startsWith("xmlns:")) continue;
            if (IGNORED_ATTRS.contains(localName)) continue;
            map.put(localName, at.getValue());
        }
        return map;
    }

    private static BigDecimal tryDecimal(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String render(String s) {
        if (s == null) return "<null>";
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    /* ──────────────── result ──────────────── */

    public record Result(List<String> diffs) {
        public boolean isEqual() { return diffs.isEmpty(); }
        public int size() { return diffs.size(); }
        @Override public String toString() {
            return diffs.isEmpty() ? "<equal>" : String.join("\n", diffs);
        }
    }
}
