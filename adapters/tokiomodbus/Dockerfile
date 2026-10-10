# SPDX-License-Identifier: MIT
#
# modbus-interop adapter for tokio-modbus (Rust).
#
# Build from the repository root:
#   docker build -f adapters/tokiomodbus/Dockerfile -t modbus-interop-tokiomodbus:dev .
#
# tokio-modbus is MIT OR Apache-2.0 and comes from crates.io at an exact
# version; Cargo.lock pins it and every other crate with a checksum that cargo
# verifies. The pins are mirrored in versions.yaml.

# Where the base images come from. They are pinned by digest (the multi-arch
# index, so linux/amd64 and linux/arm64 both resolve), so a mirror of Docker
# Hub serves exactly the same images.
ARG BASE_REGISTRY=docker.io/library
ARG RUST_DIGEST=sha256:2752b332db73fdbb7dc576f06c82ed1f312005784ef913d7e04a28f5f55dc581
ARG DEBIAN_DIGEST=sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f

# ---------------------------------------------------------------------------
# Stage 1: the adapter, with tokio-modbus and its dependencies linked in
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/rust:1.99.0-slim-trixie@${RUST_DIGEST} AS build

# The tokio-modbus release on crates.io. The default is the pin of Cargo.toml
# and Cargo.lock; any other value is a candidate build (see below).
ARG UPSTREAM_VERSION=0.17.0
ARG ADAPTER_VERSION=dev

WORKDIR /src/adapter
COPY adapters/tokiomodbus/Cargo.toml adapters/tokiomodbus/Cargo.lock ./
COPY adapters/tokiomodbus/src ./src
COPY adapters/tokiomodbus/collect-licenses.sh ./

# At the pin, Cargo.lock decides everything and --locked refuses any drift.
# A candidate build asks for another tokio-modbus release, to which the lock
# file cannot apply: the requirement in Cargo.toml is rewritten and only that
# crate is moved in the lock file ("cargo update --precise"), which keeps the
# other crates where they are unless the candidate needs something else. From
# there on the build is locked again.
RUN set -eu; \
    pinned=$(sed -n 's/^tokio-modbus = { version = "=\([^"]*\)".*/\1/p' Cargo.toml); \
    test -n "${pinned}" || { echo "no tokio-modbus pin in Cargo.toml" >&2; exit 1; }; \
    if [ "${pinned}" != "${UPSTREAM_VERSION}" ]; then \
        echo "candidate build: tokio-modbus ${UPSTREAM_VERSION} instead of the pin ${pinned}"; \
        sed -i "s/^tokio-modbus = { version = \"=${pinned}\"/tokio-modbus = { version = \"=${UPSTREAM_VERSION}\"/" Cargo.toml; \
        cargo update --package tokio-modbus --precise "${UPSTREAM_VERSION}"; \
    fi; \
    cargo fetch --locked; \
    locked=$(awk '$0 == "name = \"tokio-modbus\"" { getline; gsub(/version = |"/, ""); print }' Cargo.lock); \
    test "${locked}" = "${UPSTREAM_VERSION}" || { echo "Cargo.lock has tokio-modbus ${locked}, want ${UPSTREAM_VERSION}" >&2; exit 1; }

# The revision reported by print-capabilities is the SHA-256 of the .crate
# file, which is what Cargo.lock pins and cargo verified on download.
RUN set -eu; \
    checksum=$(awk '$0 == "name = \"tokio-modbus\"" { found = 1 } found && /^checksum = / { gsub(/checksum = |"/, ""); print; exit }' Cargo.lock); \
    test -n "${checksum}"; \
    ADAPTER_VERSION="${ADAPTER_VERSION}" UPSTREAM_VERSION="${UPSTREAM_VERSION}" UPSTREAM_REVISION="sha256:${checksum}" \
        cargo build --locked --offline --release; \
    mkdir -p /out; \
    cp target/release/modbus-interop /out/modbus-interop

# Licence texts: every crate in the binary, and the Rust standard library
# that is linked into it.
RUN set -eu; \
    sh ./collect-licenses.sh /out/licenses/crates; \
    doc=$(rustc --print sysroot)/share/doc/rust; \
    mkdir -p /out/licenses/rust-std; \
    cp -R "${doc}/COPYRIGHT-library.html" "${doc}/licenses" /out/licenses/rust-std/

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/debian:trixie-slim@${DEBIAN_DIGEST} AS runtime

ARG ADAPTER_VERSION=dev

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin interop \
    && mkdir -p /run/modbus-interop \
    && chown interop /run/modbus-interop

COPY --from=build /out/modbus-interop /usr/local/bin/modbus-interop
COPY --from=build /out/licenses /licenses
COPY LICENSE /licenses/modbus-interop/LICENSE
COPY fixtures /fixtures

LABEL org.opencontainers.image.title="modbus-interop-tokiomodbus" \
      org.opencontainers.image.description="Modbus TCP reference client and server built on tokio-modbus" \
      org.opencontainers.image.source="https://github.com/otfabric/modbus-interop" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${ADAPTER_VERSION}"

USER interop
EXPOSE 1502

HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=15 \
  CMD test -f /run/modbus-interop/ready

ENTRYPOINT ["/usr/local/bin/modbus-interop"]
CMD ["server"]
