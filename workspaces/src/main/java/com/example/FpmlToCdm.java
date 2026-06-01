package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FpML 5.x → CDM JSON converter.
 *
 * Interface contract (DO NOT CHANGE — the validator calls this):
 *   args[0]  = absolute path to the FpML XML file
 *   stdout   = CDM JSON object
 *   stderr   = errors / stack traces only
 *   exit 0   = success
 *   exit 1   = error
 *
 * This is a PLACEHOLDER. The agent replaces the body of convert() with the
 * real FpML→CDM mapping logic. Do not change main() or parseXml().
 */
public class FpmlToCdm {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: FpmlToCdm <fpml-file>");
            System.exit(1);
        }
        try {
            Document doc = parseXml(new File(args[0]));
            Map<String, Object> cdm = convert(doc);
            System.out.println(MAPPER.writeValueAsString(cdm));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    // ── XML parsing (do not modify) ───────────────────────────────────────────

    private static Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Disable external entity processing (XXE prevention)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    /** Return the text content of the first element with the given local name, or null. */
    protected static String text(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    // ── Conversion — REPLACE THIS METHOD ─────────────────────────────────────

    /**
     * Convert the parsed FpML document to a CDM trade object.
     * <p>
     * TODO: implement full FpML → CDM mapping.
     * The agent will replace this placeholder with the real implementation.
     * </p>
     */
    private static Map<String, Object> convert(Document doc) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> trade  = new LinkedHashMap<>();
        result.put("trade", trade);
        // Placeholder — returns empty trade until agent implements the mapping.
        return result;
    }
}
