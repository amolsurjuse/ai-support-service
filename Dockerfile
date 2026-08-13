FROM maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .
RUN ./mvnw -B -ntp dependency:go-offline
COPY src ./src
RUN ./mvnw -B -ntp -DskipTests clean package

FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal@sha256:35f47084a4c1e34636fc8842780d5ca1e85b1b74de139723d1a541137932ddf2
WORKDIR /app
RUN mkdir -p /app/tmp && chown -R 1000:1000 /app
COPY --from=build /workspace/target/ai-support-service-1.0.0-SNAPSHOT.jar app.jar
USER 1000:1000
EXPOSE 8094
ENTRYPOINT ["java","-Djava.io.tmpdir=/app/tmp","-jar","/app/app.jar"]
