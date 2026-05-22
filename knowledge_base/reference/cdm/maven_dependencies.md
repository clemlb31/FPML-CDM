# Maven Dependencies (Java 17)

Repository — add to pom.xml (not on Maven Central):
  <repository>
    <id>regnosys-releases</id>
    <url>https://regnosys.jfrog.io/artifactory/libs-release</url>
  </repository>

Core:
  <dependency>
      <groupId>org.finos.cdm</groupId>
      <artifactId>cdm-java</artifactId>
      <version>6.19.0</version>
  </dependency>
  <dependency>
      <groupId>com.regnosys.rosetta</groupId>
      <artifactId>rosetta-common</artifactId>
      <version>9.27.0</version>
  </dependency>

JSON:
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
