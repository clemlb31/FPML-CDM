# Stage 1 : compilation
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn package -q

# Stage 2 : image légère pour l'exécution
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/file-copy-1.0.0.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
