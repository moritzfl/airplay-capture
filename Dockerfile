# syntax=docker/dockerfile:1

# The jar is the same for every platform: build it once, natively, instead of again under arm64
# emulation (which took 16 minutes per release).
# The JDK must be the toolchain version in build.gradle.kts (Java 21): Gradle does not download one,
# so another major fails the build. renovate.json keeps these images on their major for that reason;
# move build.gradle.kts, this file and packaging/Dockerfile.runtime together.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon --version > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*.jar /src/app.jar

FROM eclipse-temurin:21-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6

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

# -XX:-UseBASE64Intrinsics keeps Java4Shell (CVE-2026-114938) unreachable until the Temurin
# image carries the fixed JDK; the RAOP receiver decodes Base64 from the network.
ENTRYPOINT ["java", "-XX:-UseBASE64Intrinsics", "-jar", "/app/app.jar"]
