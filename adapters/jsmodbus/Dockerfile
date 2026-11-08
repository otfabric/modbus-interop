# SPDX-License-Identifier: MIT
#
# modbus-interop adapter for jsmodbus (Cloud-Automation/node-modbus).
#
# Build from the repository root:
#   docker build -f adapters/jsmodbus/Dockerfile -t modbus-interop-jsmodbus:dev .
#
# jsmodbus is MIT and is installed from the npm registry as published,
# unmodified; package-lock.json pins it and every package it depends on by
# version and integrity (sha512). The adapter is plain JavaScript (MIT) with
# no dependency besides jsmodbus. The pin is UPSTREAM_VERSION below, mirrored
# in package.json, package-lock.json and versions.yaml.

# Where the base image comes from. It is pinned by digest, so a mirror of
# Docker Hub serves exactly the same image.
ARG BASE_REGISTRY=docker.io/library
# node:26.13.0-trixie-slim (Node.js 26, LTS), multi-arch index (linux/amd64 and linux/arm64).
ARG NODE_DIGEST=sha256:5adad64f9a1fca2d51972caa65983634c8a774eb300d72b20f7f2a0f60368072

# ---------------------------------------------------------------------------
# Stage 1: jsmodbus from npm, the adapter
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/node:26.13.0-trixie-slim@${NODE_DIGEST} AS build

# The release pin: jsmodbus on npm. "make candidate-jsmodbus
# CANDIDATE_VERSION=..." overrides it; the lock file cannot know the integrity
# of another release, so a candidate is installed without it, and the
# integrity of what was downloaded is what print-capabilities reports.
ARG UPSTREAM_VERSION=5.0.0
ARG ADAPTER_VERSION=dev

ENV NPM_CONFIG_UPDATE_NOTIFIER=false \
    NPM_CONFIG_FUND=false \
    NPM_CONFIG_AUDIT=false

WORKDIR /opt/modbus-interop
COPY adapters/jsmodbus/package.json adapters/jsmodbus/package-lock.json ./
# --ignore-scripts: no install script of any package runs.
RUN set -eu; \
    pinned="$(node -p 'require("./package.json").dependencies.jsmodbus')"; \
    if [ "${UPSTREAM_VERSION}" != "${pinned}" ]; then \
        echo "candidate build: jsmodbus ${UPSTREAM_VERSION} instead of the pin ${pinned}, lock file resolved now" >&2; \
        rm package-lock.json; \
        npm pkg set "dependencies.jsmodbus=${UPSTREAM_VERSION}"; \
        npm install --package-lock-only --ignore-scripts; \
    fi; \
    npm ci --omit=dev --ignore-scripts; \
    installed="$(node -p 'require("jsmodbus/package.json").version')"; \
    test "${installed}" = "${UPSTREAM_VERSION}"; \
    integrity="$(node -p 'require("./node_modules/.package-lock.json").packages["node_modules/jsmodbus"].integrity')"; \
    case "${integrity}" in sha512-*) ;; *) echo "no integrity for jsmodbus: ${integrity}" >&2; exit 1 ;; esac; \
    printf '{"adapterVersion":"%s","upstreamVersion":"%s","upstreamRevision":"%s"}\n' \
        "${ADAPTER_VERSION}" "${installed}" "${integrity}" > build-info.json; \
    ls node_modules > /tmp/BUNDLED-PACKAGES.txt

# The licence texts. jsmodbus has no licence file: its README ends with the
# MIT licence, which is copied from there.
RUN set -eu; \
    mkdir -p /licenses/jsmodbus /licenses/modbus-interop; \
    sed -n '/^## License/,$p' node_modules/jsmodbus/README.md > /licenses/jsmodbus/LICENSE; \
    grep -q 'Permission is hereby granted' /licenses/jsmodbus/LICENSE; \
    for dir in node_modules/*/; do \
        name="$(basename "${dir}")"; \
        [ "${name}" = jsmodbus ] && continue; \
        files="$(find "${dir}" -maxdepth 1 -type f -iname 'licen[cs]e*')"; \
        test -n "${files}" || { echo "no licence file in ${name}" >&2; exit 1; }; \
        mkdir -p "/licenses/${name}"; \
        cp ${files} "/licenses/${name}/"; \
    done; \
    { echo "npm packages in /opt/modbus-interop/node_modules, at the versions of package-lock.json:"; \
      for name in $(cat /tmp/BUNDLED-PACKAGES.txt); do \
        node -p "const p=require('./node_modules/${name}/package.json'); p.name+' '+p.version+' '+p.license"; \
      done; } > /licenses/BUNDLED-PACKAGES.txt

COPY adapters/jsmodbus/src ./src

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/node:26.13.0-trixie-slim@${NODE_DIGEST} AS runtime

ARG ADAPTER_VERSION=dev

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin interop \
    && mkdir -p /run/modbus-interop \
    && chown interop /run/modbus-interop \
    && printf '%s\n' '#!/bin/sh' 'exec node /opt/modbus-interop/src/main.js "$@"' > /usr/local/bin/modbus-interop \
    && chmod 0755 /usr/local/bin/modbus-interop

COPY --from=build /opt/modbus-interop /opt/modbus-interop
COPY --from=build /licenses /licenses
COPY LICENSE /licenses/modbus-interop/LICENSE
COPY fixtures /fixtures

# The library logs with "debug": nothing unless DEBUG is set, and then to stderr.
ENV NODE_ENV=production

LABEL org.opencontainers.image.title="modbus-interop-jsmodbus" \
      org.opencontainers.image.description="Modbus TCP reference server and client built on jsmodbus" \
      org.opencontainers.image.source="https://github.com/otfabric/modbus-interop" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${ADAPTER_VERSION}"

USER interop
EXPOSE 1502

HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=15 \
  CMD test -f /run/modbus-interop/ready

ENTRYPOINT ["/usr/local/bin/modbus-interop"]
CMD ["server"]
