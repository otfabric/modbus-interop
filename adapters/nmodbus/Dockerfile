# SPDX-License-Identifier: MIT
#
# modbus-interop adapter for NModbus (C#/.NET).
#
# Build from the repository root:
#   docker build -f adapters/nmodbus/Dockerfile -t ghcr.io/otfabric/modbus-interop-nmodbus:dev .
#
# Upstream: https://github.com/NModbus/NModbus (NuGet package "NModbus", MIT).
# Nothing of it is vendored or patched: the package is restored from nuget.org
# at the exact version below, and adapters/nmodbus/packages.lock.json holds
# its content hash. The pin is mirrored in ModbusInterop.csproj,
# packages.lock.json and versions.yaml.

# The .NET images come from the Microsoft Container Registry, not from Docker
# Hub, so BASE_REGISTRY (a Docker Hub mirror in CI) does not apply to them. The
# argument is declared because every adapter build receives it.
ARG BASE_REGISTRY=docker.io/library

# mcr.microsoft.com/dotnet/sdk:10.0.401-noble and
# mcr.microsoft.com/dotnet/runtime:10.0.12-alpine3.24, multi-arch index digests
# (linux/amd64 and linux/arm64). .NET 10 is the current LTS release.
ARG DOTNET_SDK_DIGEST=sha256:e70cdb7f80b0348f5cb85f19a8f670fca061f033d57eed12fa003d58b0e06317
ARG DOTNET_RUNTIME_DIGEST=sha256:c13f5945240d31ae6cdc99e2c7d48c355d3e78bc98ef1415e9482ce193eef32e

# ---------------------------------------------------------------------------
# Stage 1: build. The adapter is portable IL: it is compiled once, on the
# build platform, and runs on the runtime image of the target platform.
# ---------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM mcr.microsoft.com/dotnet/sdk:10.0.401-noble@${DOTNET_SDK_DIGEST} AS build

# The release pin. "make candidate-nmodbus" overrides it to try another
# upstream version without touching the pin.
ARG UPSTREAM_VERSION=3.0.83

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_NOLOGO=1 \
    NUGET_XMLDOC_MODE=skip

WORKDIR /src
COPY adapters/nmodbus/nuget.config adapters/nmodbus/ModbusInterop.csproj adapters/nmodbus/packages.lock.json ./

# At the pin the restore is locked: NuGet fails if the graph or the content
# hash of the package differs from packages.lock.json. A candidate version is
# by definition not the locked one, so it is restored without the lock file,
# still at exactly the version asked for ("[x]" in the project file).
RUN if grep -q "\"resolved\": \"${UPSTREAM_VERSION}\"" packages.lock.json; then \
        dotnet restore ModbusInterop.csproj -p:NModbusVersion="${UPSTREAM_VERSION}" --locked-mode; \
    else \
        echo "candidate build: NModbus ${UPSTREAM_VERSION} is not the locked version; restoring without the lock file" >&2; \
        rm packages.lock.json; \
        dotnet restore ModbusInterop.csproj -p:NModbusVersion="${UPSTREAM_VERSION}" -p:RestorePackagesWithLockFile=false; \
    fi

COPY adapters/nmodbus/src ./src
RUN dotnet publish ModbusInterop.csproj -c Release --no-restore \
        -p:NModbusVersion="${UPSTREAM_VERSION}" -o /out \
    && test -f /out/NModbus.dll && test -f /out/modbus-interop.dll \
    && mkdir -p /licenses/NModbus \
    && license="$(find "${HOME}/.nuget/packages/nmodbus/${UPSTREAM_VERSION}" -maxdepth 1 -iname 'LICENSE*' | head -1)" \
    && test -n "${license}" \
    && cp "${license}" /licenses/NModbus/LICENSE.txt

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM mcr.microsoft.com/dotnet/runtime:10.0.12-alpine3.24@${DOTNET_RUNTIME_DIGEST} AS runtime

ARG ADAPTER_VERSION=dev

RUN adduser -D -H -u 10001 -s /sbin/nologin interop \
    && mkdir -p /run/modbus-interop /opt/modbus-interop /licenses/dotnet /licenses/modbus-interop \
    && chown interop /run/modbus-interop \
    && cp /usr/share/dotnet/LICENSE.txt /usr/share/dotnet/ThirdPartyNotices.txt /licenses/dotnet/ \
    && printf '%s\n' '#!/bin/sh' \
       'exec dotnet /opt/modbus-interop/modbus-interop.dll "$@"' \
       > /usr/local/bin/modbus-interop \
    && chmod +x /usr/local/bin/modbus-interop

COPY --from=build /out /opt/modbus-interop
COPY --from=build /licenses/NModbus /licenses/NModbus
COPY LICENSE /licenses/modbus-interop/LICENSE
COPY fixtures /fixtures

# No diagnostic pipes in /tmp, and the JIT settings that suit a process that
# lives for one request (the client) as well as one that serves for hours.
ENV ADAPTER_VERSION=${ADAPTER_VERSION} \
    DOTNET_EnableDiagnostics=0 \
    DOTNET_NOLOGO=1 \
    DOTNET_TieredPGO=0

LABEL org.opencontainers.image.title="modbus-interop-nmodbus" \
      org.opencontainers.image.description="Modbus TCP reference server and client built on NModbus" \
      org.opencontainers.image.source="https://github.com/otfabric/modbus-interop" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${ADAPTER_VERSION}"

USER 10001:10001
EXPOSE 1502

HEALTHCHECK --interval=2s --timeout=2s --start-period=20s --retries=15 \
  CMD test -f /run/modbus-interop/ready

ENTRYPOINT ["/usr/local/bin/modbus-interop"]
CMD ["server"]
