# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 qgents \
    && useradd --uid 10001 --gid qgents --create-home qgents
WORKDIR /app
COPY --from=build /build/target/qgents-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/logs /app/data /var/lib/qgents/workspaces /var/lib/qgents/workspace-metadata \
    && chown -R qgents:qgents /app /var/lib/qgents
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
