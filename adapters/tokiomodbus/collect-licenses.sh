#!/bin/sh
# SPDX-License-Identifier: MIT
#
# Collects the licence texts of every crate that goes into the adapter binary.
#
# Usage: collect-licenses.sh <output directory>     (run in the crate directory,
#        after "cargo fetch"; uses cargo only, no further tool)
#
# The crates are those cargo resolves for this target with normal and build
# dependencies, proc macros included: they are not linked, but the code they
# generate is. The texts are copied from the sources cargo downloaded and
# verified against Cargo.lock. A crate without a licence text fails the build
# rather than being shipped without one.

set -eu

out="${1:?usage: collect-licenses.sh <output directory>}"
host=$(rustc -vV | sed -n 's/^host: //p')
registry="${CARGO_HOME:-$HOME/.cargo}/registry/src"
mkdir -p "${out}"
: > "${out}/CRATES.txt"

cargo tree --locked --offline --edges normal,build --target "${host}" --prefix none \
    --format '{p}|{l}' | sed 's/ (\*)$//' | sort -u > /tmp/crates.list

missing=0
while IFS='|' read -r package license; do
    name=$(echo "${package}" | cut -d' ' -f1)
    version=$(echo "${package}" | cut -d' ' -f2 | sed 's/^v//')
    # The adapter itself: its licence is the repository's LICENSE.
    case "${package}" in *"($(pwd))"*) continue ;; esac
    source=$(find "${registry}" -mindepth 2 -maxdepth 2 -type d -name "${name}-${version}" | head -1)
    if [ -z "${source}" ]; then
        echo "collect-licenses: no source directory for ${name} ${version}" >&2
        missing=1
        continue
    fi
    target="${out}/${name}-${version}"
    mkdir -p "${target}"
    find "${source}" -maxdepth 1 \( -type f -o -type d \) \
        \( -iname 'LICEN[CS]E*' -o -iname 'COPYING*' -o -iname 'NOTICE*' -o -iname 'UNLICENSE*' -o -iname 'COPYRIGHT*' \) \
        -exec cp -R {} "${target}/" \;
    if [ -z "$(find "${target}" -type f | head -1)" ]; then
        echo "collect-licenses: ${name} ${version} (${license}) ships no licence text" >&2
        missing=1
    fi
    printf '%s %s\t%s\n' "${name}" "${version}" "${license}" >> "${out}/CRATES.txt"
done < /tmp/crates.list

[ "${missing}" -eq 0 ] || exit 1
echo "collect-licenses: $(wc -l < "${out}/CRATES.txt") crates"
