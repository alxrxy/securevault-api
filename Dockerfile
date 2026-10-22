# syntax=docker/dockerfile:1

# ---- Build stage: compile and package the Spring Boot jar ----------------
# Base images are pinned by digest; Dependabot (docker ecosystem) bumps them.
FROM maven:3.10.0-eclipse-temurin-21-noble@sha256:1bc7bb8342cb0cad1727af35ab5a36ce0abf13ec4e1393a001c5f0a559fcc1c7 AS build
WORKDIR /build

# Resolve dependencies in their own layer so source edits don't re-download them.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests need Docker (Testcontainers) and run in CI (mvn verify); the image
# build only packages.
RUN mvn -B -ntp -DskipTests package \
 && java -Djarmode=tools -jar target/securevault-*.jar extract --layers --launcher --destination /extracted

# ---- Runtime stage: JRE only, non-root ------------------------------------
FROM eclipse-temurin:24.0.2_12-jre-alpine@sha256:4044b6c87cb088885bcd0220f7dc7a8a4aab76577605fa471945d2e98270741f

RUN addgroup -S -g 10001 app \
 && adduser -S -D -H -u 10001 -G app app \
 && mkdir -p /data/storage \
 && chown app:app /data/storage

WORKDIR /app
# Least-changing layers first for better caching.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

ENV STORAGE_ROOT=/data/storage
VOLUME /data/storage
EXPOSE 8080

USER 10001:10001

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health > /dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
