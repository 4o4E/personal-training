# syntax=docker/dockerfile:1
FROM python:3.12-slim
WORKDIR /app
RUN mkdir -p /data && chown 10001:10001 /data
# The build context is the collector Release ZIP, never a second checkout of misports.
COPY --chown=10001:10001 collector/ ./collector/
COPY --chown=10001:10001 misports/ ./misports/
RUN pip install --no-cache-dir --break-system-packages -r collector/requirements.txt -r misports/requirements.txt \
    && chown -R 10001:10001 /app
USER 10001:10001
ENV MISPORTS_SOURCE_DIR=/app/misports COLLECTOR_STATE_DIR=/data/collector COLLECTOR_SEED_DB=/data/misports.db
ENTRYPOINT ["python", "-m", "collector"]
