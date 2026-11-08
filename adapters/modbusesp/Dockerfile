# modbus-interop adapter for modbus-esp8266 (emelianov/modbus-esp8266).
#
# Build from the repository root:
#   make image-modbusesp
#   docker build -f adapters/modbusesp/Dockerfile -t ghcr.io/otfabric/modbus-interop-modbusesp:dev .
#
# modbus-esp8266 is an Arduino library (BSD-3-Clause). Its Modbus code
# (src/Modbus.cpp, ModbusRTU.cpp and the header-only ModbusTCPTemplate.h and
# ModbusAPI.h) is compiled unmodified for Linux, against a small Arduino core
# layer (adapters/modbusesp/src/arduino: millis, Stream, Client, Server,
# IPAddress over BSD sockets) that contains no Modbus code. See README.md.

# Where the base images come from. They are pinned by digest, so a mirror of
# Docker Hub serves exactly the same images (see the Makefile).
ARG BASE_REGISTRY=docker.io/library
# debian:trixie-slim, the multi-arch index (linux/amd64 and linux/arm64).
ARG DEBIAN_DIGEST=sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4

# The upstream release, as its git tag, and the commit that tag names.
ARG UPSTREAM_VERSION=4.1.1
ARG UPSTREAM_SHA=299666307a052df52f757f3131e292c2513bb57f
ARG UPSTREAM_REPO=https://github.com/emelianov/modbus-esp8266.git

# The compiler and cJSON (the JSON documents), from Debian.
ARG GXX_VERSION=14.2.0-19
ARG CJSON_VERSION=1.7.18-3.1+deb13u1

ARG ADAPTER_VERSION=dev

# ---------------------------------------------------------------------------
# Stage 1: the toolchain, shared by the two build stages. Not in the image.
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/debian:trixie-slim@${DEBIAN_DIGEST} AS toolchain

ARG GXX_VERSION
ARG CJSON_VERSION

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      "g++-14=${GXX_VERSION}" \
      "gcc-14=${GXX_VERSION}" \
      git \
      "libcjson-dev=${CJSON_VERSION}" \
      "libcjson1=${CJSON_VERSION}" \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# Stage 2: modbus-esp8266 from the pinned tag, unpatched
# ---------------------------------------------------------------------------
FROM toolchain AS upstream

ARG UPSTREAM_VERSION
ARG UPSTREAM_SHA
ARG UPSTREAM_REPO

# The pins of this file, to tell a release build from a candidate build below.
COPY adapters/modbusesp/Dockerfile /tmp/pins

# The tag is fetched and the commit it names is compared with UPSTREAM_SHA, so
# a tag that moved fails the build. A candidate build overrides
# UPSTREAM_VERSION; when it does not give the commit as well, UPSTREAM_SHA is
# still the pin of another version and there is nothing to compare with. The
# commit that was built is recorded either way and is what print-capabilities
# reports. The sources are not changed after the checkout: SOURCES.sha256
# records them, and the adapter build compiles exactly these files.
WORKDIR /src/modbus-esp8266
RUN set -eu; \
    pinned_version=$(sed -nE 's/^ARG UPSTREAM_VERSION=(.+)$/\1/p' /tmp/pins | head -1); \
    pinned_sha=$(sed -nE 's/^ARG UPSTREAM_SHA=(.+)$/\1/p' /tmp/pins | head -1); \
    git init -q .; \
    git remote add origin "${UPSTREAM_REPO}"; \
    git fetch -q --depth 1 origin "refs/tags/${UPSTREAM_VERSION}" \
      || git fetch -q --depth 1 origin "${UPSTREAM_VERSION}"; \
    git checkout -q --detach FETCH_HEAD; \
    actual=$(git rev-parse 'HEAD^{commit}'); \
    if [ "${UPSTREAM_VERSION}" != "${pinned_version}" ] && [ "${UPSTREAM_SHA}" = "${pinned_sha}" ]; then \
      echo "candidate ${UPSTREAM_VERSION} without UPSTREAM_SHA: building ${actual} unverified"; \
    elif [ "${actual}" != "${UPSTREAM_SHA}" ]; then \
      echo "${UPSTREAM_VERSION} is ${actual}, not the pinned ${UPSTREAM_SHA}" >&2; exit 1; \
    fi; \
    git diff --quiet HEAD; \
    mkdir -p /opt/upstream; \
    echo "${actual}" > /opt/upstream/REVISION; \
    (cd src && sha256sum *) > /opt/upstream/SOURCES.sha256; \
    printf '%s\n' \
      "modbus-esp8266 ${UPSTREAM_VERSION}, built unmodified from" \
      "${UPSTREAM_REPO}" \
      "commit ${actual}" \
      "" \
      "Licence: BSD-3-Clause, see LICENSE.txt in this directory." \
      "SOURCES.sha256: the library files that were compiled." \
      > /opt/upstream/SOURCE

# ---------------------------------------------------------------------------
# Stage 3: the adapter
# ---------------------------------------------------------------------------
FROM upstream AS adapter

ARG UPSTREAM_VERSION
ARG ADAPTER_VERSION

COPY adapters/modbusesp/src /src/adapter

# The library's .cpp files and the adapter's are compiled with the same
# settings: settings_overlay.h (the adapter's ModbusSettings.h values, see
# there) is included first in every C++ translation unit. The library's
# files are compiled from the checkout, and checked against SOURCES.sha256
# right before.
WORKDIR /src/adapter
RUN set -eu; \
    (cd /src/modbus-esp8266/src && sha256sum -c --quiet /opt/upstream/SOURCES.sha256); \
    CXXFLAGS="-std=c++17 -O2 -Wall -Werror -include settings_overlay.h -I. -Iarduino -I/src/modbus-esp8266/src"; \
    for f in Modbus ModbusRTU; do \
      g++-14 ${CXXFLAGS} -w -c /src/modbus-esp8266/src/$f.cpp -o /tmp/$f.o; \
    done; \
    g++-14 ${CXXFLAGS} -Wextra \
      -DADAPTER_VERSION="\"${ADAPTER_VERSION}\"" \
      -DUPSTREAM_VERSION="\"${UPSTREAM_VERSION}\"" \
      -DUPSTREAM_REVISION="\"$(cat /opt/upstream/REVISION)\"" \
      -c main.cpp -o /tmp/main.o; \
    for f in server client arduino/arduino; do \
      g++-14 ${CXXFLAGS} -Wextra -Wno-missing-field-initializers -c $f.cpp -o /tmp/$(basename $f).o; \
    done; \
    for f in fixture util; do \
      gcc-14 -std=c11 -O2 -Wall -Wextra -Werror -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE -I. \
        -c $f.c -o /tmp/$f.o; \
    done; \
    g++-14 /tmp/*.o -lcjson -lm -o /opt/modbus-interop

# ---------------------------------------------------------------------------
# Stage 4: runtime
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/debian:trixie-slim@${DEBIAN_DIGEST} AS runtime

ARG CJSON_VERSION
ARG ADAPTER_VERSION

RUN apt-get update && apt-get install -y --no-install-recommends \
      "libcjson1=${CJSON_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin interop \
    && mkdir -p /run/modbus-interop /licenses/modbus-esp8266 /licenses/cjson /licenses/modbus-interop \
    && cp /usr/share/doc/libcjson1/copyright /licenses/cjson/copyright \
    && chown interop /run/modbus-interop

COPY --from=adapter /opt/modbus-interop /usr/local/bin/modbus-interop
COPY --from=upstream /src/modbus-esp8266/LICENSE.txt /opt/upstream/SOURCE /opt/upstream/SOURCES.sha256 /licenses/modbus-esp8266/
COPY LICENSE /licenses/modbus-interop/LICENSE
COPY fixtures /fixtures

LABEL org.opencontainers.image.title="modbus-interop-modbusesp" \
      org.opencontainers.image.description="Modbus TCP and RTU-over-TCP reference server and client built on modbus-esp8266" \
      org.opencontainers.image.source="https://github.com/otfabric/modbus-interop" \
      org.opencontainers.image.licenses="MIT AND BSD-3-Clause" \
      org.opencontainers.image.version="${ADAPTER_VERSION}"

USER interop
EXPOSE 1502

HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=15 \
  CMD test -f /run/modbus-interop/ready

ENTRYPOINT ["/usr/local/bin/modbus-interop"]
CMD ["server"]
