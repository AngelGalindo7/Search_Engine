# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy
RUN adduser --disabled-password --no-create-home appuser
WORKDIR /app
COPY --from=build /app/target/SearchEngine-1.0-SNAPSHOT.jar app.jar
# Corpus baked into image so IndexBootstrap skips the download on every start.
# Docker layer cache means re-deploys only re-upload the JAR layer, not the corpus.
COPY blog_index.bin blog_token_meta.txt blog_embeddings.bin blog_doc_meta.txt ./
# DJL pytorch native (~300MB) persists on a Fly volume at /data so it
# downloads once and survives container restarts and redeploys.
ENV DJL_CACHE_DIR=/data/.djl
USER appuser
# HEALTHCHECK omitted: eclipse-temurin:17-jre-jammy does not include curl or wget.
# Liveness is handled by fly.toml [checks.health] via HTTP GET /health.
CMD ["sh", "-c", "java -Xmx400m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC -jar app.jar"]
