# modbus-interop adapter for libmodbus.
#
# Build from the repository root:
#   make image-libmodbus
#   docker build -f adapters/libmodbus/Dockerfile -t modbus-interop-libmodbus:dev .
#
# libmodbus is LGPL-2.1-or-later. The adapter (MIT) links it dynamically and
# the image ships it as a shared library, /usr/local/lib/libmodbus.so.5, which
# can be replaced by another build of the library. Its source is the commit
# named in /licenses/libmodbus/SOURCE.

# Where the base images come from. They are pinned by digest, so a mirror of
# Docker Hub serves exactly the same images (see the Makefile).
ARG BASE_REGISTRY=docker.io/library
# debian:trixie-slim, the multi-arch index (linux/amd64 and linux/arm64).
ARG DEBIAN_DIGEST=sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f

# The upstream release, as its git tag, and the commit that tag names.
ARG UPSTREAM_VERSION=v3.2.0
ARG UPSTREAM_SHA=a9b025d12289855490b10d77461c99e001abfc0f
ARG UPSTREAM_REPO=https://github.com/stephane/libmodbus.git

# cJSON, from Debian: the only library in the image besides libmodbus and the
# C library of the base image.
ARG CJSON_VERSION=1.7.18-3.1+deb13u1

ARG ADAPTER_VERSION=dev

# ---------------------------------------------------------------------------
# Stage 1: the toolchain, shared by the two build stages. Not in the image.
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/debian:trixie-slim@${DEBIAN_DIGEST} AS toolchain

ARG CJSON_VERSION

RUN apt-get update && apt-get install -y --no-install-recommends \
      autoconf \
      automake \
      ca-certificates \
      gcc \
      git \
      libc6-dev \
      "libcjson-dev=${CJSON_VERSION}" \
      "libcjson1=${CJSON_VERSION}" \
      libtool \
      make \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# Stage 2: libmodbus from the pinned tag, unpatched
# ---------------------------------------------------------------------------
FROM toolchain AS upstream

ARG UPSTREAM_VERSION
ARG UPSTREAM_SHA
ARG UPSTREAM_REPO

# The pins of this file, to tell a release build from a candidate build below.
COPY adapters/libmodbus/Dockerfile /tmp/pins

# The tag is fetched and the commit it names is compared with UPSTREAM_SHA, so
# a tag that moved fails the build. A candidate build ("make candidate-libmodbus
# CANDIDATE_VERSION=...") overrides UPSTREAM_VERSION; when it does not give
# the commit as well (CANDIDATE_SHA), UPSTREAM_SHA is still the pin of another
# version and there is nothing to compare with. The commit that was built is
# recorded either way and is what print-capabilities reports.
WORKDIR /src/libmodbus
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
    mkdir -p /opt/libmodbus; \
    echo "${actual}" > /opt/libmodbus/REVISION

RUN ./autogen.sh >/dev/null \
    && ./configure --prefix=/opt/libmodbus --disable-static --disable-tests >/dev/null \
    && make -j"$(nproc)" \
    && make install \
    && mkdir /opt/libmodbus/shared \
    && cp -P /opt/libmodbus/lib/libmodbus.so.5* /opt/libmodbus/shared/ \
    && printf '%s\n' \
         "libmodbus ${UPSTREAM_VERSION}, built unmodified from" \
         "${UPSTREAM_REPO}" \
         "commit $(cat /opt/libmodbus/REVISION)" \
         "" \
         "Licence: LGPL-2.1-or-later, see COPYING.LESSER in this directory." \
         "The adapter links the library dynamically: replace" \
         "/usr/local/lib/libmodbus.so.5 to run it with another build of libmodbus." \
         > /opt/libmodbus/SOURCE

# ---------------------------------------------------------------------------
# Stage 3: the adapter
# ---------------------------------------------------------------------------
FROM toolchain AS adapter

ARG UPSTREAM_VERSION
ARG ADAPTER_VERSION

COPY --from=upstream /opt/libmodbus /opt/libmodbus
COPY adapters/libmodbus/src /src/adapter

WORKDIR /src/adapter
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror \
      -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE \
      -DADAPTER_VERSION="\"${ADAPTER_VERSION}\"" \
      -DUPSTREAM_VERSION="\"${UPSTREAM_VERSION}\"" \
      -DUPSTREAM_REVISION="\"$(cat /opt/libmodbus/REVISION)\"" \
      -I/opt/libmodbus/include/modbus \
      main.c server.c client.c fixture.c util.c \
      -L/opt/libmodbus/lib -lmodbus -lcjson -lm \
      -o /opt/modbus-interop

# ---------------------------------------------------------------------------
# Stage 4: runtime
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/debian:trixie-slim@${DEBIAN_DIGEST} AS runtime

ARG CJSON_VERSION
ARG ADAPTER_VERSION

# The directory is copied, not its files: that keeps the soname a symlink.
COPY --from=upstream /opt/libmodbus/shared/ /usr/local/lib/

RUN apt-get update && apt-get install -y --no-install-recommends \
      "libcjson1=${CJSON_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && ldconfig \
    && useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin interop \
    && mkdir -p /run/modbus-interop /licenses/libmodbus /licenses/cjson /licenses/modbus-interop \
    && cp /usr/share/doc/libcjson1/copyright /licenses/cjson/copyright \
    && chown interop /run/modbus-interop

COPY --from=adapter /opt/modbus-interop /usr/local/bin/modbus-interop
COPY --from=upstream /src/libmodbus/COPYING.LESSER /opt/libmodbus/SOURCE /licenses/libmodbus/
COPY LICENSE /licenses/modbus-interop/LICENSE
COPY fixtures /fixtures

LABEL org.opencontainers.image.title="modbus-interop-libmodbus" \
      org.opencontainers.image.description="Modbus TCP reference server and client built on libmodbus" \
      org.opencontainers.image.source="https://github.com/otfabric/modbus-interop" \
      org.opencontainers.image.licenses="MIT AND LGPL-2.1-or-later" \
      org.opencontainers.image.version="${ADAPTER_VERSION}"

USER interop
EXPOSE 1502

HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=15 \
  CMD test -f /run/modbus-interop/ready

ENTRYPOINT ["/usr/local/bin/modbus-interop"]
CMD ["server"]
