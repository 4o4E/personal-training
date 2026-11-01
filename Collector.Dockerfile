# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data && chown 10001:10001 /data
# The build context is the collector-<version> Release ZIP directory.
COPY --chown=10001:10001 lib/ ./lib/
USER 10001:10001
ENV COLLECTOR_STATE_DIR=/data/collector COLLECTOR_SEED_DB=/data/misports.db
ENTRYPOINT ["java", "-cp", "/app/lib/*", "top.e404.training.collector.MainKt"]
