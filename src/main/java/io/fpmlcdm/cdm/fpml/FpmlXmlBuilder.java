package io.fpmlcdm.cdm.fpml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Utility for building FpML XML documents.
 */
public class FpmlXmlBuilder {

    private final Document document;

    public FpmlXmlBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        this.document = builder.newDocument();
    }

    public Element createElement(String localName) {
        return document.createElementNS(FpmlConstants.FPML_NS, localName);
    }

    public Element createElement(String namespaceUri, String localName) {
        return document.createElementNS(namespaceUri, localName);
    }

    public void addAttribute(Element element, String attributeName, String value) {
        element.setAttribute(attributeName, value);
    }

    public void addAttribute(Element element, String namespaceUri, String localName, String value) {
        element.setAttributeNS(namespaceUri, localName, value);
    }

    public Document getDocument() {
        return document;
    }
}
