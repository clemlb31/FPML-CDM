# Maven dependencies & build (Java 17)

The coordinates needed to compile **and run** a CDM 6.19 transformer. These are **facts**
(group:artifact:version), not copyable build code — assemble your own `pom.xml` from them.
This set is the one a **converging** FRA build actually used; prefer it over guessing.

Java level: **17**. Packaging: a runnable jar (the main reads the FpML, runs the transformer,
serializes to CDM JSON, and compares) — use the assembly (fat-jar) plugin so the run has all
deps on the classpath.

## Repository

**None needed.** `org.finos.cdm:cdm-java:6.19.0` is on **Maven Central** — do NOT add a custom
repository. (Old notes mentioned a Regnosys repo for pre-6.x artifacts; it is not needed here and
adding an unreachable repo only causes "could not resolve" failures.)

## Dependencies (the proven set)

- `org.finos.cdm:cdm-java:6.19.0` — the CDM model + serializers.
- `com.fasterxml.jackson.core:jackson-databind:2.17.2` — JSON.
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2` — JSON date/time module.
- `com.google.inject:guice:6.0.0` — **required at runtime**: CDM's object mapper / serializer is
  wired with Guice. Without it the program compiles but throws at runtime when serializing.

That is the whole set. **Do NOT add `com.regnosys.rosetta:rosetta-common`** as a direct
dependency — `cdm-java` brings the rosetta runtime (`com.rosetta.model.lib.*`, `records.Date`,
meta types) transitively. Pinning a standalone `rosetta-common` version (e.g. 9.27.0) fails to
resolve and wastes iterations. You also do not need slf4j/logback/junit to compile or run.

## Build plugins

- `maven-compiler-plugin` → source/target **17**.
- `maven-assembly-plugin:3.6.0` → a single runnable jar with `mainClass` =
  `com.example.FpmlToCdmApp` (or your main class), descriptorRef `jar-with-dependencies`.

## Notes

- The CDM version you depend on MUST match the jar `cdm_lookup` introspects (6.19.0). If a
  builder/enum from `cdm_lookup` won't resolve at compile time, the dependency version is off.
- Get the exact CDM JSON serializer/ObjectMapper class name from `cdm_lookup` (or by grepping the
  jar) — don't invent it; it is the Guice-wired one above.
