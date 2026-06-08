package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.finos.cdm.model.Cdm;

public class FpmlToCdmApp {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: FpmlToCdmApp <fpml.xml> <output.json>");
            System.exit(1);
        }
        String fpmlPath = args[0];
        String outputPath = args[1];

        String xml = Files.readString(Path.of(fpmlPath));
        Cdm cdm = IrsTransformer.transform(xml);

        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(new File(outputPath), cdm);
    }
}
