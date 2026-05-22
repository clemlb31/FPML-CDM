package io.fpmlcdm.common;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for reading FpML 5.x XML through plain DOM (namespace-agnostic).
 *
 * FpML 5.x uses xmlns="http://www.fpml.org/FpML-5/confirmation" on the root.
 * All lookups here use getLocalName / getTagName *without* the namespace
 * prefix to keep mappers terse.
 */
public final class XmlUtils {

    private XmlUtils() {}

    public static Document parse(InputStream xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(xml);
    }

    /** First *direct* child element with the given local name, or null. */
    public static Element child(Element parent, String localName) {
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

    /** All direct child elements with the given local name. */
    public static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /** Text content of a direct child, trimmed; null if the child is absent. */
    public static String childText(Element parent, String localName) {
        Element c = child(parent, localName);
        return c == null ? null : c.getTextContent().trim();
    }

    /** Walks a path of nested element names ("a/b/c"); returns the leaf or null. */
    public static Element path(Element start, String... names) {
        Element cur = start;
        for (String name : names) {
            cur = child(cur, name);
            if (cur == null) return null;
        }
        return cur;
    }

    public static String pathText(Element start, String... names) {
        Element e = path(start, names);
        return e == null ? null : e.getTextContent().trim();
    }

    /** Find a descendant by walking down — first match in document order. */
    public static Element firstDescendant(Element start, String localName) {
        if (start == null) return null;
        NodeList all = start.getElementsByTagNameNS("*", localName);
        return all.getLength() == 0 ? null : (Element) all.item(0);
    }
}
