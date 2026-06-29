package io.fpmlcdm.core.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public final class XmlUtils {
    
    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = createDocumentBuilderFactory();
    private static final TransformerFactory TRANSFORMER_FACTORY = TransformerFactory.newInstance();
    
    private static DocumentBuilderFactory createDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        // Allow DOCTYPE (some Murex MXML inputs carry one) but block the actual XXE
        // vectors: external general/parameter entities and external DTD loading.
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (ParserConfigurationException e) {
            // Ignore if a given feature is not supported by the active parser
        }
        return factory;
    }
    
    private XmlUtils() { }
    
    public static Document parse(String xml) throws IOException, SAXException {
        try (InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return parse(is);
        }
    }
    
    public static Document parse(File file) throws IOException, SAXException {
        try (InputStream is = Files.newInputStream(file.toPath())) {
            return parse(is);
        }
    }
    
    public static Document parse(Path path) throws IOException, SAXException {
        try (InputStream is = Files.newInputStream(path)) {
            return parse(is);
        }
    }
    
    public static Document parse(InputStream inputStream) throws IOException, SAXException {
        try {
            DocumentBuilder builder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
            return builder.parse(new InputSource(inputStream));
        } catch (ParserConfigurationException e) {
            throw new IOException("Failed to create XML parser", e);
        }
    }
    
    public static Document newDocument() {
        try {
            DocumentBuilder builder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
            return builder.newDocument();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Failed to create new XML document", e);
        }
    }
    
    public static String serialize(Document doc) {
        return serialize(doc, false);
    }
    
    public static String serialize(Document doc, boolean prettyPrint) {
        try {
            Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            if (prettyPrint) {
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            }
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException e) {
            throw new RuntimeException("Failed to serialize XML document", e);
        }
    }
    
    public static void serialize(Document doc, Path outputPath) throws IOException {
        serialize(doc, outputPath, false);
    }
    
    public static void serialize(Document doc, Path outputPath, boolean prettyPrint) throws IOException {
        String xml = serialize(doc, prettyPrint);
        Files.writeString(outputPath, xml, StandardCharsets.UTF_8);
    }
    
    public static Element getFirstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && 
                (tagName == null || tagName.equals(node.getLocalName()) || tagName.equals(node.getNodeName()))) {
                return (Element) node;
            }
        }
        return null;
    }
    
    public static List<Element> getChildElements(Element parent, String tagName) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                if (tagName == null || tagName.equals(node.getLocalName()) || tagName.equals(node.getNodeName())) {
                    elements.add((Element) node);
                }
            }
        }
        return elements;
    }
    
    public static List<Element> getChildElements(Element parent) {
        return getChildElements(parent, null);
    }
    
    public static Element getFirstChildElementNS(Element parent, String namespaceUri, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && 
                namespaceUri.equals(node.getNamespaceURI()) && 
                localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }
    
    public static List<Element> getChildElementsNS(Element parent, String namespaceUri, String localName) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && 
                namespaceUri.equals(node.getNamespaceURI()) && 
                localName.equals(node.getLocalName())) {
                elements.add((Element) node);
            }
        }
        return elements;
    }
    
    public static String getTextContent(Element element) {
        if (element == null) return null;
        return element.getTextContent().trim();
    }
    
    public static String getTextContent(Element parent, String childTagName) {
        Element child = getFirstChildElement(parent, childTagName);
        return child != null ? getTextContent(child) : null;
    }
    
    public static String getAttribute(Element element, String attrName) {
        if (element == null) return null;
        String value = element.getAttribute(attrName);
        return value != null && !value.isEmpty() ? value : null;
    }
    
    public static String getAttributeNS(Element element, String namespaceUri, String attrName) {
        if (element == null) return null;
        String value = element.getAttributeNS(namespaceUri, attrName);
        return value != null && !value.isEmpty() ? value : null;
    }
    
    public static Element findElementByPath(Element root, String... path) {
        Element current = root;
        for (String tagName : path) {
            if (current == null) return null;
            current = getFirstChildElement(current, tagName);
        }
        return current;
    }
    
    public static String findTextByPath(Element root, String... path) {
        Element element = findElementByPath(root, path);
        return element != null ? getTextContent(element) : null;
    }
    
    public static Element createElement(Document doc, String tagName) {
        return doc.createElement(tagName);
    }
    
    public static Element createElementNS(Document doc, String namespaceUri, String qualifiedName) {
        return doc.createElementNS(namespaceUri, qualifiedName);
    }
    
    public static Element appendElement(Document doc, Element parent, String tagName) {
        Element child = createElement(doc, tagName);
        parent.appendChild(child);
        return child;
    }
    
    public static Element appendElementNS(Document doc, Element parent, String namespaceUri, String qualifiedName) {
        Element child = createElementNS(doc, namespaceUri, qualifiedName);
        parent.appendChild(child);
        return child;
    }
    
    public static void setTextContent(Element element, String text) {
        element.setTextContent(text != null ? text : "");
    }
    
    public static Element createTextElement(Document doc, String tagName, String text) {
        Element element = createElement(doc, tagName);
        setTextContent(element, text);
        return element;
    }
    
    public static Element createTextElementNS(Document doc, String namespaceUri, String qualifiedName, String text) {
        Element element = createElementNS(doc, namespaceUri, qualifiedName);
        setTextContent(element, text);
        return element;
    }
    
    public static void appendTextElement(Document doc, Element parent, String tagName, String text) {
        Element child = createTextElement(doc, tagName, text);
        parent.appendChild(child);
    }
    
    public static void appendTextElementNS(Document doc, Element parent, String namespaceUri, String qualifiedName, String text) {
        Element child = createTextElementNS(doc, namespaceUri, qualifiedName, text);
        parent.appendChild(child);
    }
    
    public static void setAttribute(Element element, String attrName, String value) {
        if (value != null) {
            element.setAttribute(attrName, value);
        }
    }
    
    public static void setAttributeNS(Element element, String namespaceUri, String attrName, String value) {
        if (value != null) {
            element.setAttributeNS(namespaceUri, attrName, value);
        }
    }
}
