package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileCopy {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: FileCopy <input-file> <output-file>");
            System.exit(1);
        }

        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        if (!Files.exists(input)) {
            System.err.println("Erreur : le fichier source n'existe pas : " + input);
            System.exit(1);
        }

        try {
            byte[] content = Files.readAllBytes(input);
            // Crée les répertoires parents si nécessaire
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.write(output, content);
            System.out.println("Fichier copié avec succès : " + input + " -> " + output);
        } catch (IOException e) {
            System.err.println("Erreur lors de la copie : " + e.getMessage());
            System.exit(1);
        }
    }
}
