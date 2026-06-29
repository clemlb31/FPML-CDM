package io.fpmlcdm.cdm.fpml;

import cdm.event.common.TradeState;
import org.w3c.dom.Element;

/**
 * A demonstration of the CDM $\to$ FpML conversion.
 */
public class Demo {
    public static void main(String[] args) {
        try {
            CdmToFpmlConverter converter = new CdmToFpmlConverter();
            
            // In a real application, we would load a real TradeState from a JSON file using RosettaObjectMapper.
            // For this demo, we assume a non-null TradeState is provided by an external loader.
            TradeState tradeState = null; 
            
            System.out.println("Starting conversion...");
            
            if (tradeState != null) {
                CdmToFpmlConverter.ConversionResult result = converter.convert(tradeState);
                
                if (result.getTradeElement() != null) {
                    System.out.println("Conversion successful! Output XML:");
                    
                    org.w3c.dom.Document doc = result.getTradeElement().getOwnerDocument();
                    for (Element party : result.getPartyElements()) {
                        doc.appendChild(doc.importNode(party, true));
                    }
                    
                    printElement(result.getTradeElement());
                } else {
                    System.out.println("No elements were produced.");
                }
            } else {
                System.out.println("TradeState is null. Please provide a valid CDM JSON file to convert.");
            }
            
        } catch (Exception e) {
            System.err.println("Error during conversion demo: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printElement(Element element) throws Exception {
        org.w3c.dom.Document doc = element.getOwnerDocument();
        javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
        
        java.io.StringWriter writer = new java.io.StringWriter();
        transformer.transform(new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(writer));
        System.out.println(writer.getBuffer().toString());
    }
}