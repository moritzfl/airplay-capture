# syntax=docker/dockerfile:1

# The jar is the same for every platform: build it once, natively, instead of again under arm64
# emulation (which took 16 minutes per release).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon --version > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*.jar /src/app.jar

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app recorder

WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar

ENV AIRPLAY_CAPTURE_DATA=/data \
    AIRPLAY_CAPTURE_WEB_PORT=8080

RUN mkdir -p /data && chown -R recorder:recorder /data /app
USER recorder
VOLUME ["/data"]
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
