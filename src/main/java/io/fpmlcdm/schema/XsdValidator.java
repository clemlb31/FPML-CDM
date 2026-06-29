package io.fpmlcdm.schema;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural validation of an XML document (MXML or FpML) against a W3C XSD.
 *
 * Pure JDK (javax.xml.validation) — no CDM/Maven dependency, so it compiles and runs standalone
 * even while the CDM build is unavailable:
 * <pre>
 *   javac -d out src/main/java/io/fpmlcdm/schema/*.java
 *   java  -cp out io.fpmlcdm.schema.XsdValidateCli --xsd schemas/fpml/fpml-main-5-3.xsd --input data/ground_truth/mxml-fpml/ird-aswp/fpml/OUT_IRD_ASWP_5-3_INS_01.xml
 * </pre>
 *
 * Includes/imports inside the XSD are resolved relative to the XSD file's location.
 * For CDM (JSON) structural validity, see docs/schemas-and-validation.md.
 */
public final class XsdValidator {

    public record Issue(String severity, int line, int col, String message) {
        @Override public String toString() {
            return severity + " [" + line + ":" + col + "] " + message;
        }
    }

    public record Result(String file, boolean valid, List<Issue> issues) {}

    private XsdValidator() {}

    public static Result validate(File xml, File xsd) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = factory.newSchema(xsd);
        Validator validator = schema.newValidator();

        List<Issue> issues = new ArrayList<>();
        validator.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException e) {
                issues.add(new Issue("WARN", e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
            }
            @Override public void error(SAXParseException e) {
                issues.add(new Issue("ERROR", e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
            }
            @Override public void fatalError(SAXParseException e) {
                issues.add(new Issue("FATAL", e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
            }
        });

        try {
            validator.validate(new StreamSource(xml));
        } catch (SAXException e) {
            // A fatal error already recorded by the handler; nothing more to do.
        }

        boolean valid = issues.stream().allMatch(i -> "WARN".equals(i.severity()));
        return new Result(xml.getName(), valid, issues);
    }
}
