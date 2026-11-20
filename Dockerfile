# syntax=docker/dockerfile:1

# The jar is the same for every platform: build it once, natively, instead of again under arm64
# emulation (which took 16 minutes per release).
# The JDK must be the toolchain version in build.gradle.kts (Java 21): Gradle does not download one,
# so another major fails the build. renovate.json keeps these images on their major for that reason;
# move build.gradle.kts, this file and packaging/Dockerfile.runtime together.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon --version > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*.jar /src/app.jar

# The amd64 manifest has the Java4Shell fix (21.0.13.1+2); the older arm64 build is unaffected.
FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d

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
