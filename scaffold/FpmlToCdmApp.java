package com.example;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

public class FpmlToCdmApp {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: FpmlToCdmApp <fpml.xml> [--expected <expected.json>]");
            System.exit(1);
        }
        String fpmlPath = args[0];
        String expectedPath = null;
        for (int i = 1; i < args.length - 1; i++) {
            if ("--expected".equals(args[i])) {
                expectedPath = args[i + 1];
                break;
            }
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new File(fpmlPath));

        TradeState tradeState = new IrsTransformer().transform(doc);

        ObjectMapper mapper = RosettaObjectMapper.getNewRosettaObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
        String actualJson = mapper.writeValueAsString(tradeState);
        System.out.println(actualJson);

        if (expectedPath != null) {
            String expectedJson = new String(Files.readAllBytes(Paths.get(expectedPath)));
            JsonNode actualTree = mapper.readTree(actualJson);
            JsonNode expectedTree = mapper.readTree(expectedJson);
            SemanticDiff.Result diff = SemanticDiff.compare(expectedTree, actualTree);
            if (diff.isEqual()) {
                System.out.println("===EQUAL===");
                System.exit(0);
            } else {
                System.out.println("===DIFFS===");
                System.out.println(diff);
                System.exit(2);
            }
        }
    }
}