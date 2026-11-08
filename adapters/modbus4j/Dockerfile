# SPDX-License-Identifier: GPL-3.0-or-later
#
# modbus-interop adapter for modbus4j (MangoAutomation/modbus4j, Java).
#
# Build from the repository root:
#   make image-modbus4j
#   docker build -f adapters/modbus4j/Dockerfile -t ghcr.io/otfabric/modbus-interop-modbus4j:dev .
#
# modbus4j is GPL-3.0-or-later, and so is this adapter, which links it. The
# image is built locally by each user and NOT published (versions.yaml,
# "published: false"). modbus4j is not on Maven Central: it is built here from
# the source archive of its release tag on GitHub, pinned by SHA-256, with
# adapters/modbus4j/upstream/pom.xml. Its dependencies come from Maven
# Central. The pins are UPSTREAM_VERSION, UPSTREAM_SHA and
# UPSTREAM_SOURCE_SHA256 below, mirrored in pom.xml and versions.yaml.

# Where the base images come from. They are pinned by digest, so a mirror of
# Docker Hub serves exactly the same images.
ARG BASE_REGISTRY=docker.io/library
ARG MAVEN_DIGEST=sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976
ARG JRE_DIGEST=sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f

# The release pin: the tag v3.1.0, the commit it names, and the SHA-256 of
# GitHub's archive of the tag.
ARG UPSTREAM_VERSION=3.1.0
ARG UPSTREAM_SHA=3325a2cbce786989156ba7999d6adc2755f7ea5b
ARG UPSTREAM_SOURCE_SHA256=eb20d1fce2d25e3de8e1b67025ca06cf43babd9a249dad82c4a49ebf2fd93344
ARG UPSTREAM_REPO=https://github.com/MangoAutomation/modbus4j

ARG ADAPTER_VERSION=dev

# ---------------------------------------------------------------------------
# Stage 1: modbus4j from the pinned source archive, unpatched
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/maven:3.9.16-eclipse-temurin-25-noble@${MAVEN_DIGEST} AS upstream

ARG UPSTREAM_VERSION
ARG UPSTREAM_SHA
ARG UPSTREAM_SOURCE_SHA256
ARG UPSTREAM_REPO

# The pins of this file, to tell a release build from a candidate build below.
COPY adapters/modbus4j/Dockerfile /tmp/pins
COPY adapters/modbus4j/upstream/pom.xml /tmp/build-pom.xml

# The archive must have the pinned SHA-256, and the commit GitHub records in
# its pax header must be UPSTREAM_SHA. A candidate build ("make
# candidate-modbus4j CANDIDATE_VERSION=...") overrides UPSTREAM_VERSION; when
# it does not give the checksum as well, the archive is built unverified and
# the commit it names is what print-capabilities reports.
WORKDIR /src
RUN set -eu; \
    pinned_version=$(sed -nE 's/^ARG UPSTREAM_VERSION=(.+)$/\1/p' /tmp/pins | head -1); \
    pinned_sum=$(sed -nE 's/^ARG UPSTREAM_SOURCE_SHA256=(.+)$/\1/p' /tmp/pins | head -1); \
    archive="${UPSTREAM_REPO}/archive/refs/tags/v${UPSTREAM_VERSION}.tar.gz"; \
    curl -fsSL --retry 3 -o modbus4j.tar.gz "${archive}"; \
    actual_sum=$(sha256sum modbus4j.tar.gz | cut -d' ' -f1); \
    commit=$(gzip -dc modbus4j.tar.gz | head -c 1024 | tr -c '[:print:]' '.' | grep -o 'comment=[0-9a-f]\{40\}' | cut -d= -f2); \
    if [ "${UPSTREAM_VERSION}" != "${pinned_version}" ] && [ "${UPSTREAM_SOURCE_SHA256}" = "${pinned_sum}" ]; then \
      echo "candidate ${UPSTREAM_VERSION} without UPSTREAM_SOURCE_SHA256: building ${commit} (${actual_sum}) unverified"; \
    else \
      [ "${actual_sum}" = "${UPSTREAM_SOURCE_SHA256}" ] \
        || { echo "${archive} has SHA-256 ${actual_sum}, not the pinned ${UPSTREAM_SOURCE_SHA256}" >&2; exit 1; }; \
      [ "${commit}" = "${UPSTREAM_SHA}" ] \
        || { echo "${archive} is commit ${commit}, not the pinned ${UPSTREAM_SHA}" >&2; exit 1; }; \
    fi; \
    mkdir modbus4j; \
    tar -xzf modbus4j.tar.gz -C modbus4j --strip-components 1; \
    printf '%s\n' "${commit}" > /src/commit; \
    printf 'modbus4j %s\nsource:   %s\nsha256:   %s\ncommit:   %s\nbuilt with build-pom.xml (this directory), unmodified sources\n' \
      "${UPSTREAM_VERSION}" "${archive}" "${actual_sum}" "${commit}" > /src/SOURCE

# -C: fail when a downloaded artifact does not match its checksum.
WORKDIR /src/modbus4j
RUN cp /tmp/build-pom.xml build-pom.xml \
    && mvn -B -q -C -f build-pom.xml -Drevision="${UPSTREAM_VERSION}" package \
    && mvn -B -q -C install:install-file -Dfile="target/modbus4j-${UPSTREAM_VERSION}.jar" \
         -DgroupId=com.infiniteautomation -DartifactId=modbus4j -Dversion="${UPSTREAM_VERSION}" -Dpackaging=jar

# ---------------------------------------------------------------------------
# Stage 2: the adapter jar and its runtime class path
# ---------------------------------------------------------------------------
FROM upstream AS build

ARG UPSTREAM_VERSION

WORKDIR /build
COPY adapters/modbus4j/pom.xml .
COPY adapters/modbus4j/src ./src
RUN mvn -B -q -C -Dmodbus4j.version="${UPSTREAM_VERSION}" -Dmodbus4j.revision="$(cat /src/commit)" package -DskipTests \
    && test -f "target/lib/modbus4j-${UPSTREAM_VERSION}.jar"

# The licence and notice files every bundled jar carries, per jar, and the
# list of what is bundled. modbus4j's jar has none: its licence is the
# license.txt of the source archive.
RUN set -eu; mkdir -p /licenses/modbus4j; \
    ls target/lib > /licenses/BUNDLED-JARS.txt; \
    for jar in target/lib/*.jar; do \
        name="$(basename "${jar}" .jar)"; \
        files="$(jar tf "${jar}" | grep -E '^META-INF/(LICENSE|NOTICE|license/)[^/]*$' | grep -v '/$' || true)"; \
        [ -n "${files}" ] || continue; \
        mkdir -p "/licenses/${name}"; \
        (cd "/licenses/${name}" && echo "${files}" | xargs jar xf "/build/${jar}" \
            && mv META-INF/* . && rmdir META-INF); \
    done; \
    cp /src/modbus4j/license.txt /src/SOURCE /src/modbus4j/build-pom.xml /licenses/modbus4j/

# ---------------------------------------------------------------------------
# Stage 3: runtime (JRE only)
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/eclipse-temurin:25.0.4.1_1-jre-noble@${JRE_DIGEST} AS runtime

ARG ADAPTER_VERSION=dev

# The JVM flags trade peak throughput for a short start: a client is one
# short-lived process per operation. stdout is for JSON only: the JVM's own
# warnings (-Xlog) and the library's logging (commons-logging, which falls back
# to java.util.logging and its console handler on stderr) go to stderr.
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin interop \
    && mkdir -p /run/modbus-interop /app \
    && chown interop /run/modbus-interop \
    && printf '%s\n' '#!/bin/sh' \
       'exec java -Xlog:disable -Xlog:all=warning:stderr -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -cp "/app/modbus-interop-modbus4j.jar:/app/lib/*" org.otfabric.modbusinterop.modbus4j.Main "$@"' \
       > /usr/local/bin/modbus-interop \
    && chmod +x /usr/local/bin/modbus-interop

COPY --from=build /build/target/modbus-interop-modbus4j.jar /app/modbus-interop-modbus4j.jar
COPY --from=build /build/target/lib /app/lib
COPY --from=build /licenses /licenses
COPY adapters/modbus4j/licenses /licenses
# The adapter is GPL-3.0-or-later: its complete source is in the image.
COPY adapters/modbus4j/LICENSE /licenses/modbus-interop-modbus4j/LICENSE
COPY adapters/modbus4j /licenses/modbus-interop-modbus4j/source
COPY LICENSE /licenses/modbus-interop/LICENSE
COPY fixtures /fixtures

ENV ADAPTER_VERSION=${ADAPTER_VERSION}

LABEL org.opencontainers.image.title="modbus-interop-modbus4j" \
      org.opencontainers.image.description="Modbus TCP reference server and client built on modbus4j (local image, not published)" \
      org.opencontainers.image.source="https://github.com/otfabric/modbus-interop" \
      org.opencontainers.image.licenses="GPL-3.0-or-later AND MIT AND Apache-2.0" \
      org.opencontainers.image.version="${ADAPTER_VERSION}"

USER interop
EXPOSE 1502 1502/udp

HEALTHCHECK --interval=2s --timeout=2s --start-period=20s --retries=15 \
  CMD test -f /run/modbus-interop/ready

ENTRYPOINT ["/usr/local/bin/modbus-interop"]
CMD ["server"]
