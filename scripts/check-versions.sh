#!/usr/bin/env bash
# Fails when versions.yaml disagrees with what the builds actually pin.
#
# Every adapter Dockerfile declares its pins as build arguments with a default:
#   ARG UPSTREAM_VERSION=...        the upstream release
#   ARG UPSTREAM_SHA=...            its commit, where the build fetches one
#   ARG UPSTREAM_SOURCE_SHA256=...  the source archive, where the build fetches one
#   ARG <NAME>_VERSION=...          a bundled package (cJSON, g++)
#   ARG <NAME>_DIGEST=sha256:...    every base image
# The upstream and bundled pins must be in the adapter's own entry under
# implementations: in versions.yaml, the digests in versions.yaml. A hash the
# entry records for the upstream artifact (wheel_sha256, crate_sha256,
# tarball_integrity) must be in the adapter's lock file. The own
# Dockerfiles (own/*/Dockerfile) resolve their upstream when they are built;
# their digests are checked like the adapters'. Every FROM names a base image
# by digest or an earlier stage of the same Dockerfile.
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

# entry <name>: the lines of implementations.<name> in versions.yaml.
entry() {
    awk -v key="  $1:" '
        /^[^ #]/ { section = $0 }
        inside && (/^[^ ]/ || /^  [^ ]/) { exit }
        inside { print }
        section == "implementations:" && $0 == key { inside = 1 }
    ' versions.yaml
}

need_entry() {  # need_entry <adapter> <description> <value that must appear in its entry>
    if grep -qF -- "$3" <<<"${block}"; then
        echo "ok   $1 $2: $3"
    else
        echo "FAIL $1 $2: $3 is not in implementations.$1 of versions.yaml"
        status=1
    fi
}

found=0
for dockerfile in adapters/*/Dockerfile own/*/Dockerfile; do
    [[ -f "${dockerfile}" ]] || continue
    adapter=$(basename "$(dirname "${dockerfile}")")
    if [[ "${dockerfile}" == adapters/* ]]; then
        found=$((found + 1))
        block=$(entry "${adapter}")
        if [[ -z "${block}" ]]; then
            echo "FAIL ${adapter}: versions.yaml has no implementations.${adapter}"
            status=1
        fi
        version=$(sed -nE 's/^ARG UPSTREAM_VERSION=(.+)$/\1/p' "${dockerfile}" | head -1)
        if [[ -z "${version}" ]]; then
            echo "FAIL ${adapter}: the Dockerfile has no ARG UPSTREAM_VERSION=<pin>"
            status=1
        else
            need_entry "${adapter}" version "version: \"${version}\""
        fi
        sha=$(sed -nE 's/^ARG UPSTREAM_SHA=(.+)$/\1/p' "${dockerfile}" | head -1)
        if [[ -n "${sha}" ]]; then
            need_entry "${adapter}" revision "revision: \"${sha}\""
        fi
        source=$(sed -nE 's/^ARG UPSTREAM_SOURCE_SHA256=(.+)$/\1/p' "${dockerfile}" | head -1)
        if [[ -n "${source}" ]]; then
            need_entry "${adapter}" "source hash" "source_sha256: \"${source}\""
        fi
        while read -r name value; do
            need_entry "${adapter}" "${name}" "\"${value}\""
        done < <(sed -nE 's/^ARG ([A-Z0-9_]+_VERSION)=(.+)$/\1 \2/p' "${dockerfile}" | grep -vE '^(UPSTREAM|ADAPTER)_VERSION ' || true)
        # A hash the entry records for the artifact must be the one the
        # adapter's lock file pins (jar_sha256 is information: Maven checks
        # its own checksum files).
        for pair in wheel_sha256:requirements.txt crate_sha256:Cargo.lock tarball_integrity:package-lock.json; do
            field=${pair%%:*} lock="adapters/${adapter}/${pair#*:}"
            hash=$(sed -nE "s/^    ${field}: \"([^\"]+)\".*$/\\1/p" <<<"${block}")
            [[ -n "${hash}" ]] || continue
            if [[ -f "${lock}" ]] && grep -qF -- "${hash}" "${lock}"; then
                echo "ok   ${adapter} ${field}: ${hash} in ${lock}"
            else
                echo "FAIL ${adapter} ${field}: ${hash} of versions.yaml is not in ${lock}"
                status=1
            fi
        done
    fi
    digests=" "
    while read -r name digest; do
        need "${dockerfile} ${name}" "${digest}"
        digests="${digests}${name} "
    done < <(sed -nE 's/^ARG ([A-Z0-9_]+_DIGEST)=(sha256:[0-9a-f]{64})$/\1 \2/p' "${dockerfile}")
    stages=" "
    while read -r image stage; do
        arg=$(sed -nE 's/^.*@\$\{([A-Z0-9_]+_DIGEST)\}$/\1/p' <<<"${image}")
        if [[ "${image}" =~ @sha256:[0-9a-f]{64}$ ]]; then
            need "${dockerfile} FROM" "${image##*@}"
        elif [[ -n "${arg}" && "${digests}" == *" ${arg} "* ]]; then
            :
        elif [[ "${stages}" == *" ${image} "* ]]; then
            :
        else
            echo "FAIL ${dockerfile}: FROM ${image} is neither pinned by a digest declared in the Dockerfile nor an earlier stage"
            status=1
        fi
        if [[ -n "${stage}" ]]; then
            stages="${stages}${stage} "
        fi
    done < <(sed -nE 's/^FROM +(--platform=[^ ]+ +)?([^ ]+)( +[Aa][Ss] +([^ ]+))? *$/\2 \4/p' "${dockerfile}")
    if grep -E '^FROM ' "${dockerfile}" | grep -vE '^FROM +(--platform=[^ ]+ +)?[^ ]+( +[Aa][Ss] +[^ ]+)? *$' >/dev/null; then
        echo "FAIL ${dockerfile}: a FROM line is not FROM [--platform=...] <image> [AS <stage>]"
        status=1
    fi
done
if [[ "${found}" -eq 0 ]]; then
    echo "FAIL no adapter Dockerfile found"
    status=1
fi
exit "${status}"
