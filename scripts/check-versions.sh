#!/usr/bin/env bash
# Fails when versions.yaml disagrees with what the builds actually pin.
#
# Every adapter Dockerfile declares its pins as build arguments with a default:
#   ARG UPSTREAM_VERSION=...     the upstream release
#   ARG UPSTREAM_SHA=...         its commit, where the build fetches one
#   ARG <NAME>_DIGEST=sha256:... every base image
# Each of those values must appear in versions.yaml.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"
status=0

need() {  # need <description> <value that must appear in versions.yaml>
    if grep -qF -- "$2" versions.yaml; then
        echo "ok   $1: $2"
    else
        echo "FAIL $1: $2 is not in versions.yaml"
        status=1
    fi
}

found=0
for dockerfile in adapters/*/Dockerfile; do
    [[ -f "${dockerfile}" ]] || continue
    found=$((found + 1))
    adapter=$(basename "$(dirname "${dockerfile}")")
    version=$(sed -nE 's/^ARG UPSTREAM_VERSION=(.+)$/\1/p' "${dockerfile}" | head -1)
    if [[ -z "${version}" ]]; then
        echo "FAIL ${adapter}: the Dockerfile has no ARG UPSTREAM_VERSION=<pin>"
        status=1
    else
        need "${adapter} version" "version: \"${version}\""
    fi
    sha=$(sed -nE 's/^ARG UPSTREAM_SHA=(.+)$/\1/p' "${dockerfile}" | head -1)
    [[ -n "${sha}" ]] && need "${adapter} revision" "${sha}"
    while read -r name digest; do
        need "${adapter} ${name}" "${digest}"
    done < <(sed -nE 's/^ARG ([A-Z0-9_]+_DIGEST)=(sha256:[0-9a-f]{64})$/\1 \2/p' "${dockerfile}")
    if grep -nE '^FROM ' "${dockerfile}" | grep -vE '@(\$\{[A-Z0-9_]+_DIGEST\}|sha256:)| AS [a-z]+$|^[0-9]+:FROM [a-z]+( AS [a-z]+)?$' >/dev/null; then
        echo "FAIL ${adapter}: a FROM line is not pinned by digest"
        status=1
    fi
done
if [[ "${found}" -eq 0 ]]; then
    echo "FAIL no adapter Dockerfile found"
    status=1
fi
exit "${status}"
