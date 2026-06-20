FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-ubi9-minimal
WORKDIR /app
RUN mkdir -p /app/tmp && chown -R 1000:1000 /app
COPY --from=build /workspace/target/ai-support-service-1.0.0-SNAPSHOT.jar app.jar
USER 1000:1000
EXPOSE 8094
ENTRYPOINT ["java","-Djava.io.tmpdir=/app/tmp","-jar","/app/app.jar"]
