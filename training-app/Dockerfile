# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /source
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY shared/build.gradle.kts shared/build.gradle.kts
COPY server/build.gradle.kts server/build.gradle.kts
RUN chmod +x gradlew
# 依赖保存在独立构建层，Actions 层缓存可跨执行复用。
RUN ./gradlew -PserverOnly :shared:dependencies :server:dependencies --no-daemon
COPY shared/src/ shared/src/
COPY server/src/ server/src/
COPY catalog/ catalog/
RUN ./gradlew -PserverOnly :server:test :server:installDist --no-daemon \
    && mkdir -p /out/dependencies /out/catalog /out/application \
    && cp server/build/install/server/lib/*.jar /out/dependencies/ \
    && mv /out/dependencies/server-*.jar /out/dependencies/shared-*.jar /out/application/ \
    && mv /out/dependencies/training-catalog-*.jar /out/catalog/

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data && chown 10001:10001 /data
# 框架依赖、教学素材与业务代码分层；修改业务代码不重新下载全部图解。
COPY --from=build --chown=10001:10001 /out/dependencies/ lib/
COPY --from=build --chown=10001:10001 /out/catalog/ lib/
COPY --from=build --chown=10001:10001 /out/application/ lib/
USER 10001:10001
ENV TRAINING_DATA=/data PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/lib/*", "top.e404.training.server.MainKt"]
