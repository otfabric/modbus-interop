# SPDX-License-Identifier: MIT
# modbus-interop adapter for PyModbus.
#
# Build from the repository root:
#   docker build -f adapters/pymodbus/Dockerfile -t modbus-interop-pymodbus:dev .
#
# PyModbus is BSD-3-Clause and pure Python with no mandatory dependency; the
# adapter is MIT like the rest of the repository. Pins are mirrored in
# versions.yaml.

# Where the base image comes from. It is pinned by digest, so a mirror of
# Docker Hub serves exactly the same image; CI uses one to stay clear of
# Docker Hub's pull rate limit (see the Makefile).
ARG BASE_REGISTRY=docker.io/library
# python:3.14.8-slim-trixie, multi-arch index (linux/amd64 and linux/arm64).
ARG PYTHON_DIGEST=sha256:a2b82f3c48559aa0a8446d9af49826b6e2b2016f4cd2afabfe6013ec53729170

# ---------------------------------------------------------------------------
# Stage 1: PyModbus from PyPI, the adapter, byte-compiled
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/python:3.14.8-slim-trixie@${PYTHON_DIGEST} AS build

# The upstream release. requirements.txt pins the same version with the hash
# of its wheel. A candidate build overrides UPSTREAM_VERSION: the hash of
# another release cannot be known here, so it is installed without hash
# checking, and the hash of what was downloaded is what print-capabilities
# reports as the revision.
ARG UPSTREAM_VERSION=3.15.0
ARG ADAPTER_VERSION=dev

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_ROOT_USER_ACTION=ignore

COPY adapters/pymodbus/requirements.txt /src/requirements.txt

WORKDIR /wheels
RUN set -eu; \
    pinned="$(sed -nE 's/^pymodbus==([^ \\]+).*/\1/p' /src/requirements.txt)"; \
    test -n "${pinned}"; \
    if [ "${UPSTREAM_VERSION}" = "${pinned}" ]; then \
        pip download --no-deps --only-binary :all: --require-hashes -r /src/requirements.txt -d /wheels; \
    else \
        echo "candidate build: pymodbus ${UPSTREAM_VERSION} instead of the pin ${pinned}, no hash to check" >&2; \
        pip download --no-deps --only-binary :all: "pymodbus==${UPSTREAM_VERSION}" -d /wheels; \
    fi; \
    test "$(ls /wheels | wc -l)" = 1; \
    wheel="$(ls /wheels/pymodbus-*.whl)"; \
    sha="$(sha256sum "${wheel}" | cut -d' ' -f1)"; \
    pip install --no-index --no-deps --no-compile --target /opt/modbus-interop/site-packages "${wheel}"; \
    installed="$(PYTHONPATH=/opt/modbus-interop/site-packages python3 -c 'import pymodbus; print(pymodbus.__version__)')"; \
    test "${installed}" = "${UPSTREAM_VERSION}"; \
    mkdir -p /licenses/pymodbus /licenses/python /licenses/modbus-interop; \
    cp /opt/modbus-interop/site-packages/pymodbus-*.dist-info/licenses/* /licenses/pymodbus/ 2>/dev/null \
        || cp /opt/modbus-interop/site-packages/pymodbus-*.dist-info/LICENSE* /licenses/pymodbus/; \
    test -n "$(ls /licenses/pymodbus)"; \
    cp /usr/local/lib/python3.14/LICENSE.txt /licenses/python/LICENSE.txt; \
    printf '{"adapterVersion":"%s","upstreamVersion":"%s","upstreamRevision":"sha256:%s","upstreamArtifact":"%s"}\n' \
        "${ADAPTER_VERSION}" "${installed}" "${sha}" "$(basename "${wheel}")" > /opt/modbus-interop/build-info.json

COPY adapters/pymodbus/modbus_interop /opt/modbus-interop/modbus_interop
COPY adapters/pymodbus/modbus-interop /opt/modbus-interop/bin/modbus-interop
COPY LICENSE /licenses/modbus-interop/LICENSE

# The runtime user cannot write byte code next to the sources: compile now.
RUN python3 -m compileall -q /opt/modbus-interop \
    && chmod 0755 /opt/modbus-interop/bin/modbus-interop

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/python:3.14.8-slim-trixie@${PYTHON_DIGEST} AS runtime

ARG ADAPTER_VERSION=dev

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin interop \
    && mkdir -p /run/modbus-interop \
    && chown interop /run/modbus-interop

COPY --from=build /opt/modbus-interop /opt/modbus-interop
COPY --from=build /opt/modbus-interop/bin/modbus-interop /usr/local/bin/modbus-interop
COPY --from=build /licenses /licenses
COPY fixtures /fixtures

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

LABEL org.opencontainers.image.title="modbus-interop-pymodbus" \
      org.opencontainers.image.description="Modbus TCP reference client and server built on PyModbus" \
      org.opencontainers.image.source="https://github.com/otfabric/modbus-interop" \
      org.opencontainers.image.licenses="MIT AND BSD-3-Clause" \
      org.opencontainers.image.version="${ADAPTER_VERSION}"

USER interop
EXPOSE 1502

HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=15 \
  CMD test -f /run/modbus-interop/ready

ENTRYPOINT ["/usr/local/bin/modbus-interop"]
CMD ["server"]
