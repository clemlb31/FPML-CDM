package io.fpmlcdm.schema;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CLI for {@link XsdValidator}. Validates one XML file or a whole directory against an XSD.
 *
 * <pre>
 *   java -cp out io.fpmlcdm.schema.XsdValidateCli --xsd &lt;schema.xsd&gt; --input &lt;file|dir&gt; [--ext xml]
 * </pre>
 *
 * Exit code 1 if any file is invalid. Standalone (pure JDK) — see {@link XsdValidator}.
 */
public final class XsdValidateCli {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        if (!opt.containsKey("xsd") || !opt.containsKey("input")) {
            System.err.println("usage: XsdValidateCli --xsd <schema.xsd> --input <file|dir> [--ext xml]");
            System.exit(2);
        }
        File xsd = new File(opt.get("xsd"));
        if (!xsd.isFile()) { System.err.println("XSD not found: " + xsd); System.exit(2); }

        String ext = "." + opt.getOrDefault("ext", "xml");
        Path in = Paths.get(opt.get("input"));
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(in)) {
            try (Stream<Path> s = Files.walk(in)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(ext))
                 .sorted()
                 .forEach(files::add);
            }
        } else {
            files.add(in);
        }

        int bad = 0;
        for (Path f : files) {
            XsdValidator.Result r = XsdValidator.validate(f.toFile(), xsd);
            System.out.println((r.valid() ? "OK   " : "FAIL ") + f);
            r.issues().forEach(i -> System.out.println("        " + i));
            if (!r.valid()) bad++;
        }
        System.out.printf("%n%d/%d valid against %s%n", files.size() - bad, files.size(), xsd.getName());
        if (bad > 0) System.exit(1);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }

    private XsdValidateCli() {}
}
