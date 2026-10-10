# SPDX-License-Identifier: MIT
#
# modbus-interop adapter for digitalpetri/modbus (Java).
#
# Build from the repository root:
#   docker build -f adapters/digitalpetri/Dockerfile -t modbus-interop-digitalpetri:dev .
#
# digitalpetri/modbus is EPL-2.0 and is used as published on Maven Central,
# unmodified: the image carries its jar next to the adapter's (MIT). The pin is
# UPSTREAM_VERSION below, mirrored in pom.xml (modbus.version) and versions.yaml.

# Where the base images come from. They are pinned by digest, so a mirror of
# Docker Hub serves exactly the same images.
ARG BASE_REGISTRY=docker.io/library
ARG MAVEN_DIGEST=sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976
ARG JRE_DIGEST=sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab

# ---------------------------------------------------------------------------
# Stage 1: build the adapter jar and collect its runtime class path
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/maven:3.9.16-eclipse-temurin-25-noble@${MAVEN_DIGEST} AS build

# The release pin: com.digitalpetri.modbus:modbus-tcp on Maven Central.
# "make candidate-digitalpetri CANDIDATE_VERSION=..." overrides it.
ARG UPSTREAM_VERSION=2.1.6

WORKDIR /build
COPY adapters/digitalpetri/pom.xml .
COPY adapters/digitalpetri/src ./src
# -C: fail when a downloaded artifact does not match its checksum. One online
# step on purpose: "dependency:go-offline" followed by an offline build does
# not resolve the same versions when UPSTREAM_VERSION differs from the pin.
RUN mvn -B -q -C -Dmodbus.version="${UPSTREAM_VERSION}" package -DskipTests \
    && test -f "target/lib/modbus-tcp-${UPSTREAM_VERSION}.jar" \
    && test -f "target/lib/modbus-${UPSTREAM_VERSION}.jar"

# The licence and notice files every bundled jar carries, per jar, and the
# list of what is bundled.
RUN set -eu; mkdir -p /licenses; \
    ls target/lib > /licenses/BUNDLED-JARS.txt; \
    for jar in target/lib/*.jar; do \
        name="$(basename "${jar}" .jar)"; \
        files="$(jar tf "${jar}" | grep -E '^META-INF/(LICENSE|NOTICE|license/)[^/]*$' | grep -v '/$' || true)"; \
        [ -n "${files}" ] || continue; \
        mkdir -p "/licenses/${name}"; \
        (cd "/licenses/${name}" && echo "${files}" | xargs jar xf "/build/${jar}" \
            && mv META-INF/* . && rmdir META-INF); \
    done

# ---------------------------------------------------------------------------
# Stage 2: runtime (JRE only)
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/eclipse-temurin:25.0.4.1_1-jre-noble@${JRE_DIGEST} AS runtime

ARG ADAPTER_VERSION=dev

# The JVM flags trade peak throughput for a short start: a client is one
# short-lived process per operation. stdout is for JSON only: the JVM's own
# warnings (-Xlog) and the library's logging (slf4j-simple) go to stderr.
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin interop \
    && mkdir -p /run/modbus-interop /app \
    && chown interop /run/modbus-interop \
    && printf '%s\n' '#!/bin/sh' \
       'exec java -Xlog:disable -Xlog:all=warning:stderr -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -Dio.netty.noUnsafe=true -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "/app/modbus-interop-digitalpetri.jar:/app/lib/*" org.otfabric.modbusinterop.digitalpetri.Main "$@"' \
       > /usr/local/bin/modbus-interop \
    && chmod +x /usr/local/bin/modbus-interop

COPY --from=build /build/target/modbus-interop-digitalpetri.jar /app/modbus-interop-digitalpetri.jar
COPY --from=build /build/target/lib /app/lib
COPY --from=build /licenses /licenses
COPY adapters/digitalpetri/licenses /licenses
COPY LICENSE /licenses/modbus-interop/LICENSE
COPY fixtures /fixtures

ENV ADAPTER_VERSION=${ADAPTER_VERSION}

LABEL org.opencontainers.image.title="modbus-interop-digitalpetri" \
      org.opencontainers.image.description="Modbus TCP reference server and client built on digitalpetri/modbus" \
      org.opencontainers.image.source="https://github.com/otfabric/modbus-interop" \
      org.opencontainers.image.licenses="MIT AND EPL-2.0 AND Apache-2.0" \
      org.opencontainers.image.version="${ADAPTER_VERSION}"

USER interop
EXPOSE 1502

HEALTHCHECK --interval=2s --timeout=2s --start-period=20s --retries=15 \
  CMD test -f /run/modbus-interop/ready

ENTRYPOINT ["/usr/local/bin/modbus-interop"]
CMD ["server"]
