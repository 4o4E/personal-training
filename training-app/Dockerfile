# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data && chown 10001:10001 /data
# 构建目录来自已发布的后端 ZIP；分层保存框架、动作素材及业务代码。
COPY --chown=10001:10001 dependencies/ lib/
COPY --chown=10001:10001 catalog/ lib/
COPY --chown=10001:10001 application/ lib/
USER 10001:10001
ENV TRAINING_DATA=/data PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/lib/*", "top.e404.training.server.MainKt"]
