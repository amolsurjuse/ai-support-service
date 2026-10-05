FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data /app/tmp && chown -R 1000:1000 /app
COPY --from=build /workspace/target/support-mcp-service-1.0.0-SNAPSHOT.jar /app/app.jar
USER 1000:1000
ENV MCP_BIND_ADDRESS=0.0.0.0 SUPPORT_MCP_MEMORY_DIRECTORY=/app/data
EXPOSE 8095
ENTRYPOINT ["java", "-Djava.io.tmpdir=/app/tmp", "-jar", "/app/app.jar"]
