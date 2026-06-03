# Maven Dependencies (Java 17)

CDM 6.x is **published on Maven Central** — no custom repository needed.
(The legacy Regnosys artifactory at `regnosys.jfrog.io/artifactory/libs-release`
returns 403 and is no longer required.)

Core:
  <dependency>
      <groupId>org.finos.cdm</groupId>
      <artifactId>cdm-java</artifactId>
      <version>6.19.0</version>
  </dependency>
  <!-- pulled transitively by cdm-java 6.19.0:
       com.regnosys:rosetta-common:11.118.1
       com.regnosys:serialization:11.118.1
       com.regnosys.rosetta.lib:9.78.0
       com.google.inject:guice:7.x (used by org.finos.cdm.CdmRuntimeModule)
  -->

JSON / XML serialisation (already pulled by rosetta-common, but pin for clarity):
  <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.17.2</version>
  </dependency>
  <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jsr310</artifactId>
      <version>2.17.2</version>
  </dependency>

CLI:
  <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>4.7.6</version>
  </dependency>

Logging:
  <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
      <version>2.0.13</version>
  </dependency>
  <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <version>1.5.6</version>
  </dependency>

Test:
  <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.3</version>
      <scope>test</scope>
  </dependency>

## Notes
- `com.regnosys:rosetta-common` is on `com.regnosys` (not `com.regnosys.rosetta` as some
  older docs suggest). Version 9.27.0 doesn't exist on Maven Central; CDM 6.19.0
  uses 11.118.1 transitively.
- `org.finos.cdm.CdmRuntimeModule` is a Guice module that binds all abstract
  function classes in CDM (`MapDataDocumentToTradeState`, etc.) to their
  `Default` implementations. We don't use it directly in the custom mapper —
  but it can be wired in tests to run the official ingestion side-by-side as
  an oracle comparison.
- The FpML POJOs (`fpml.consolidated.*`) are bundled inside `cdm-java` itself,
  no separate FpML dependency needed.
- The XML object mapper `com.regnosys.rosetta.common.serialisation.xml.RosettaXmlMapper`
  comes from `rosetta-common` and pre-configures Jackson for FpML/Rosetta XML.
