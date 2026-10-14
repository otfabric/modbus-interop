#!/usr/bin/env bash
# Reference-stack self-test: every client adapter against every server adapter.
#
# Usage: interop.sh <adapter> [<adapter> ...]      e.g. interop.sh libmodbus pymodbus
#
# For each case in tests/cases.tsv and each client/server pairing:
#   - the outcome must match the expectation of the case, and
#   - all pairings must return the same result document.
# Then checks on values and on connection handling rather than outcomes:
#   - every table reads back exactly as the fixture defines it,
#   - every write function changes what a later read on another connection
#     returns, at the largest quantity the protocol allows,
#   - FC22 and FC23 compute what the specification says,
#   - FC08, FC17, FC20, FC21 and FC24 return the fixture's data, and FC21
#     changes what a later FC20 reads,
#   - the server reports the requests it answered,
#   - many requests on one connection and concurrent connections work.
#
# tests/known-bugs.tsv lists bugs of specific upstream versions; a case they
# explain is counted as a known bug instead of a failure.
#
# This tests the reference adapters against each other. It says nothing about
# any library that consumes the images.
#
# Environment: REGISTRY, VERSION (image tag, default dev), VERBOSE=1,
#              REPORT_DIR (keeps every result document when set).

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/lib.sh
source "${SCRIPT_DIR}/lib.sh"

[[ $# -ge 1 ]] || { echo "usage: interop.sh <adapter> [<adapter> ...]" >&2; exit 2; }
ADAPTERS=("$@")
FIXTURE="${ROOT}/fixtures/baseline/fixture.json"
CASES="${ROOT}/tests/cases.tsv"
KNOWN_BUGS="${ROOT}/tests/known-bugs.tsv"
require_tools
WORK=$(mktemp -d)
trap 'rm -rf "${WORK}"; cleanup' EXIT
network_up

# ---- known upstream bugs that apply to the versions under test ----
# ${WORK}/active: "<adapter>\t<operation regex>\t<description>" per line.
: > "${WORK}/active"
: > "${WORK}/reproduced"
for adapter in "${ADAPTERS[@]}"; do
    docker run --rm "$(image_of "${adapter}")" print-capabilities > "${WORK}/caps-${adapter}.json" 2>/dev/null
    version=$(jq -r '.upstream.version' "${WORK}/caps-${adapter}.json")
    log "${adapter}: upstream ${version}"
    while IFS=$'\t' read -r bug_adapter bug_version bug_regex bug_text; do
        [[ -z "${bug_adapter}" || "${bug_adapter}" == \#* ]] && continue
        if [[ "${bug_adapter}" == "${adapter}" && "${bug_version}" == "${version}" ]]; then
            printf '%s\t%s\t%s\n' "${bug_adapter}" "${bug_regex}" "${bug_text}" >> "${WORK}/active"
            log "known bug in ${adapter} ${version}: ${bug_text}"
        fi
    done < "${KNOWN_BUGS}"
done

# has <adapter> <feature>: the adapter declares the feature.
has() { jq -e --arg f "$2" '.features[$f] == true' "${WORK}/caps-$1.json" >/dev/null 2>&1; }

# has_role <adapter> <server|client>
has_role() { jq -e --arg r "$2" '.roles[$r] == true' "${WORK}/caps-$1.json" >/dev/null 2>&1; }

# supported <client> <server> <needs>: the pairing declares every feature in
# the comma-separated list of "server:<feature>" and "client:<feature>".
supported() {
    local need side feature
    has_role "$1" client && has_role "$2" server || return 1
    [[ "$3" == "-" ]] && return 0
    for need in ${3//,/ }; do
        side="${need%%:*}"; feature="${need#*:}"
        if [[ "${side}" == "server" ]]; then has "$2" "${feature}" || return 1
        else has "$1" "${feature}" || return 1; fi
    done
    return 0
}

SKIPPED=0
skip() { SKIPPED=$((SKIPPED + 1)); [[ -n "${VERBOSE:-}" ]] && log "skip: $*"; return 0; }

# known_bug <client> <server> <operation>: prints the description of a known
# bug that may affect this pairing and operation, if there is one.
known_bug() {
    local bug_adapter bug_regex bug_text
    while IFS=$'\t' read -r bug_adapter bug_regex bug_text; do
        if [[ "${bug_adapter}" == "$1" || "${bug_adapter}" == "$2" ]] && [[ "$3" =~ ${bug_regex} ]]; then
            echo "${bug_text}"
            return 0
        fi
    done < "${WORK}/active"
    return 1
}

# hit_known_bug <pair> <operation> <description>
hit_known_bug() {
    KNOWN=$((KNOWN + 1))
    echo "$3" >> "${WORK}/reproduced"
    [[ -n "${VERBOSE:-}" ]] && log "known bug: $1: $2"
    return 0
}

# One server per adapter is shared by the read-only cases; cases that change
# the device get a server of their own.
shared_server() { SERVER_TAG=shared server_name "$1"; }
for adapter in "${ADAPTERS[@]}"; do
    has_role "${adapter}" server || continue
    name=$(SERVER_TAG=shared start_server "${adapter}")
    wait_ready "${name}" || exit 1
done

keep() {  # keep <label> <document>
    [[ -n "${REPORT_DIR:-}" ]] || return 0
    mkdir -p "${REPORT_DIR}"
    printf '%s\n' "$2" > "${REPORT_DIR}/$(echo "$1" | tr -c 'A-Za-z0-9._\n-' '_').json"
}

log "adapters: ${ADAPTERS[*]} (${VERSION})"
n=0
while IFS=$'\t' read -r state want_exit want_summary needs operation; do
    [[ -z "${state}" || "${state}" == \#* ]] && continue
    n=$((n + 1))
    reference="" reference_pair=""
    for server in "${ADAPTERS[@]}"; do
        for client in "${ADAPTERS[@]}"; do
            pair="${client}->${server}"
            if ! supported "${client}" "${server}" "${needs}"; then
                skip "${pair}: ${operation} (needs ${needs})"
                continue
            fi
            if [[ "${state}" == "rw" ]]; then
                name=$(start_server "${server}"); wait_ready "${name}" || exit 1
            else
                name=$(shared_server "${server}")
            fi
            # shellcheck disable=SC2086
            doc=$(run_client "${client}" ${operation} --host "${name}"); CLIENT_EXIT=$?
            [[ "${state}" == "rw" ]] && stop_server "${name}"
            keep "case${n}-${pair}" "${doc}"

            bug=$(known_bug "${client}" "${server}" "${operation}")
            summary=$(jq -r "${SUMMARY}" <<<"${doc}" 2>/dev/null)
            if [[ "${summary}" != "${want_summary}" || "${CLIENT_EXIT}" != "${want_exit}" ]]; then
                if [[ -n "${bug}" ]]; then
                    hit_known_bug "${pair}" "${operation}" "${bug}"
                    continue
                fi
                fail "${pair}: ${operation}"
                log "    want exit=${want_exit} ${want_summary}"
                log "    got  exit=${CLIENT_EXIT} ${summary:-<no result document>}"
                continue
            fi
            ok "${pair}: ${operation}"
            # A pairing a known bug may affect is not held to agreement.
            [[ -n "${bug}" ]] && continue

            normalized=$(jq -cS "${NORMALIZE}" <<<"${doc}")
            if [[ -z "${reference}" ]]; then
                reference="${normalized}"; reference_pair="${pair}"
            elif [[ "${normalized}" != "${reference}" ]]; then
                fail "${pair} and ${reference_pair} disagree on: ${operation}"
                diff <(jq -S . <<<"${reference}") <(jq -S . <<<"${normalized}") | head -20 >&2
            else
                ok "${pair} agrees with ${reference_pair}: ${operation}"
            fi
        done
    done
done < "${CASES}"

# ---- values: every table reads back as the fixture defines it ----
# slice <table> <first index> <count>: the fixture's values as compact JSON.
slice() { jq -c --argjson i "$2" --argjson n "$3" ".$1.values[\$i:\$i+\$n]" "${FIXTURE}"; }
csv() { jq -r 'map(if . == true then 1 elif . == false then 0 else . end) | join(",")' <<<"$1"; }

# read_values <client> <server container> <operation> <address> <quantity>
read_values() { run_client "$1" "$3" --address "$4" --quantity "$5" --host "$2" | jq -c '.response.bits // .response.registers'; }

for server in "${ADAPTERS[@]}"; do
    has_role "${server}" server || continue
    name=$(shared_server "${server}")
    for client in "${ADAPTERS[@]}"; do
        has_role "${client}" client || continue
        pair="${client}->${server}"
        while read -r operation table base first count; do
            want=$(slice "${table}" "${first}" "${count}")
            got=$(read_values "${client}" "${name}" "${operation}" $((base + first)) "${count}")
            if [[ "${got}" == "${want}" ]]; then ok "${pair}: ${operation} ${count} at $((base + first)) matches the fixture"
            else fail "${pair}: ${operation} ${count} at $((base + first)) does not match the fixture"; log "    want ${want:0:160}"; log "    got  ${got:0:160}"; fi
        done <<TABLES
read-coils coils 0 0 2000
read-coils coils 0 3 13
read-discrete-inputs discreteInputs 1000 0 2000
read-discrete-inputs discreteInputs 1000 1991 9
read-holding-registers holdingRegisters 0 0 125
read-holding-registers holdingRegisters 0 125 125
read-holding-registers holdingRegisters 0 250 6
read-input-registers inputRegisters 65280 0 125
read-input-registers inputRegisters 65280 131 125
read-input-registers inputRegisters 65280 255 1
TABLES
    done
done

# ---- values: a write changes what a later read returns ----
# 123 registers, 1968 coils: the largest write of each function.
regs123=$(jq -cn '[range(0; 123) | (. * 521 + 17) % 65536]')
bits1968=$(jq -cn '[range(0; 1968) | (. * 5 + 1) % 7 < 3]')
for server in "${ADAPTERS[@]}"; do
    has_role "${server}" server || continue
    for client in "${ADAPTERS[@]}"; do
        has_role "${client}" client || continue
        pair="${client}->${server}"
        name=$(start_server "${server}"); wait_ready "${name}" || exit 1

        run_client "${client}" write-single-register --address 200 --value 51966 --host "${name}" >/dev/null
        got=$(read_values "${client}" "${name}" read-holding-registers 199 3)
        want=$(jq -c '.holdingRegisters.values[199:202] | .[1] = 51966' "${FIXTURE}")
        if [[ "${got}" == "${want}" ]]; then ok "${pair}: FC06 is visible to a later read, neighbours untouched"
        else fail "${pair}: read after FC06 returns ${got}, want ${want}"; fi

        run_client "${client}" write-multiple-registers --address 100 --values "$(csv "${regs123}")" --host "${name}" >/dev/null
        got=$(read_values "${client}" "${name}" read-holding-registers 100 123)
        if [[ "${got}" == "${regs123}" ]]; then ok "${pair}: FC16 with 123 registers is visible to a later read"
        else fail "${pair}: read after FC16 with 123 registers returns ${got:0:160}"; fi
        got=$(read_values "${client}" "${name}" read-holding-registers 98 2)
        want=$(slice holdingRegisters 98 2)
        if [[ "${got}" == "${want}" ]]; then ok "${pair}: FC16 leaves the registers before it untouched"
        else fail "${pair}: registers before the FC16 range are ${got}, want ${want}"; fi

        run_client "${client}" write-single-coil --address 0 --value true --host "${name}" >/dev/null
        run_client "${client}" write-single-coil --address 1 --value false --host "${name}" >/dev/null
        got=$(read_values "${client}" "${name}" read-coils 0 3)
        want=$(jq -c '.coils.values[0:3] | .[0] = true | .[1] = false' "${FIXTURE}")
        if [[ "${got}" == "${want}" ]]; then ok "${pair}: FC05 on and off are visible to a later read"
        else fail "${pair}: read after FC05 returns ${got}, want ${want}"; fi

        run_client "${client}" write-multiple-coils --address 16 --values "$(csv "${bits1968}")" --host "${name}" >/dev/null
        got=$(read_values "${client}" "${name}" read-coils 16 1968)
        if [[ "${got}" == "${bits1968}" ]]; then ok "${pair}: FC15 with 1968 coils is visible to a later read"
        else fail "${pair}: read after FC15 with 1968 coils differs from what was written"; fi
        got=$(read_values "${client}" "${name}" read-coils 1984 16)
        want=$(slice coils 1984 16)
        if [[ "${got}" == "${want}" ]]; then ok "${pair}: FC15 leaves the coils after it untouched"
        else fail "${pair}: coils after the FC15 range are ${got}, want ${want}"; fi

        # 13 coils: a count that is not a multiple of 8, at an address that is not one either.
        run_client "${client}" write-multiple-coils --address 3 --values 1,1,0,1,0,0,1,1,1,0,1,0,1 --host "${name}" >/dev/null
        got=$(read_values "${client}" "${name}" read-coils 3 13)
        if [[ "${got}" == "[true,true,false,true,false,false,true,true,true,false,true,false,true]" ]]; then ok "${pair}: FC15 with 13 coils at an odd address"
        else fail "${pair}: read after FC15 with 13 coils returns ${got}"; fi

        if supported "${client}" "${server}" "server:serverMaskWrite,client:clientMaskWrite"; then
            # Register 5 holds 4660 (0x1234): (0x1234 AND 0x00F2) OR (0x0025 AND NOT 0x00F2) = 0x0035.
            run_client "${client}" mask-write-register --address 5 --and-mask 0x00F2 --or-mask 0x0025 --host "${name}" >/dev/null
            got=$(read_values "${client}" "${name}" read-holding-registers 5 1)
            if [[ "${got}" == "[53]" ]]; then ok "${pair}: FC22 computes (current AND and) OR (or AND NOT and)"
            else fail "${pair}: register after FC22 is ${got}, want [53]"; fi
        else skip "${pair}: FC22 value"; fi

        if supported "${client}" "${server}" "server:serverReadWriteMultiple,client:clientReadWriteMultiple"; then
            # Write 31..32, read 30..33: the write comes first.
            got=$(run_client "${client}" read-write-multiple-registers --read-address 30 --read-quantity 4 \
                --write-address 31 --values 7,8 --host "${name}" | jq -c '.response.registers')
            want=$(jq -c '.holdingRegisters.values[30:34] | .[1] = 7 | .[2] = 8' "${FIXTURE}")
            if [[ "${got}" == "${want}" ]]; then ok "${pair}: FC23 writes before it reads"
            else fail "${pair}: FC23 returns ${got}, want ${want}"; fi
            # The largest FC23: 121 registers written, 125 read.
            got=$(run_client "${client}" read-write-multiple-registers --read-address 100 --read-quantity 125 \
                --write-address 102 --values "$(csv "$(jq -c '.[0:121]' <<<"${regs123}")")" --host "${name}" | jq -c '.response.registers | length')
            if [[ "${got}" == 125 ]]; then ok "${pair}: FC23 with 121 written and 125 read"
            else fail "${pair}: FC23 with 121 written and 125 read returned ${got} registers"; fi
        else skip "${pair}: FC23 values"; fi

        if has "${server}" requestEvents; then
            sleep 0.3
            events=$(docker logs "${name}" 2>/dev/null)
            for want in \
                '.event == "request" and .function == 6 and .address == 200 and .quantity == 1 and .registers == [51966] and .exception == null' \
                '.event == "request" and .function == 16 and .address == 100 and .quantity == 123 and (.registers | length) == 123 and .exception == null' \
                '.event == "request" and .function == 15 and .address == 16 and .quantity == 1968 and (.bits | length) == 1968 and .exception == null' \
                '.event == "request" and .function == 5 and .address == 1 and .bits == [false] and .exception == null' \
                '.event == "request" and .function == 3 and .address == 100 and .quantity == 123 and .exception == null'
            do
                if jq -e -s "map(select(${want})) | length > 0" <<<"${events}" >/dev/null 2>&1; then ok "${pair}: server reports ${want:0:70}"
                else fail "${pair}: no server event with ${want}"; fi
            done
        else skip "${pair}: request events"; fi
        stop_server "${name}"
    done
done

# ---- values: diagnostics, server ID, file records, FIFO queues ----
# What the cases above only count, compared with the fixture.
server_id_hex=$(printf '%02x%s' "$(jq -r '.reportServerId.serverId' "${FIXTURE}")" "$(jq -r 'if .reportServerId.runIndicator then "ff" else "00" end' "${FIXTURE}")")
for server in "${ADAPTERS[@]}"; do
    has_role "${server}" server || continue
    for client in "${ADAPTERS[@]}"; do
        has_role "${client}" client || continue
        pair="${client}->${server}"
        name=$(shared_server "${server}")
        if supported "${client}" "${server}" "server:serverDiagnostics,client:clientDiagnostics"; then
            got=$(run_client "${client}" diagnostics --sub-function 0 --data c0de --host "${name}" | jq -c '.response')
            if [[ "${got}" == '{"function":8,"subFunction":0,"data":"c0de"}' ]]; then ok "${pair}: FC08 echoes Return Query Data"
            else fail "${pair}: FC08 Return Query Data returned ${got}"; fi
        else skip "${pair}: FC08 value"; fi
        if supported "${client}" "${server}" "server:serverReportServerId,client:clientReportServerId"; then
            got=$(run_client "${client}" report-server-id --host "${name}" | jq -r '.response.data')
            if [[ "${got}" == "${server_id_hex}" ]]; then ok "${pair}: FC17 returns the fixture's server ID and run indicator"
            else fail "${pair}: FC17 returns ${got}, want ${server_id_hex}"; fi
        else skip "${pair}: FC17 value"; fi
        if supported "${client}" "${server}" "server:serverFileRecord,client:clientReadFileRecord"; then
            while read -r file first count index; do
                got=$(run_client "${client}" read-file-record --file "${file}" --record "${first}" --length "${count}" --host "${name}" | jq -c '.response.registers')
                want=$(jq -c --argjson f "${index}" --argjson i "${first}" --argjson n "${count}" '.fileRecords[$f].records[$i:$i+$n]' "${FIXTURE}")
                if [[ "${got}" == "${want}" ]]; then ok "${pair}: FC20 ${count} records of file ${file} from ${first} match the fixture"
                else fail "${pair}: FC20 file ${file} from ${first}: ${got:0:160}, want ${want:0:160}"; fi
            done <<FILES
1 0 120 0
1 120 80 0
2 0 4 1
FILES
        else skip "${pair}: FC20 values"; fi
        if supported "${client}" "${server}" "server:serverFifoQueue,client:clientFifoQueue"; then
            for index in 0 1 2; do
                address=$(jq -r --argjson q "${index}" '.fifoQueues[$q].address' "${FIXTURE}")
                got=$(run_client "${client}" read-fifo-queue --address "${address}" --host "${name}" | jq -c '.response.registers')
                want=$(jq -c --argjson q "${index}" '.fifoQueues[$q].values' "${FIXTURE}")
                if [[ "${got}" == "${want}" ]]; then ok "${pair}: FC24 queue at ${address} matches the fixture"
                else fail "${pair}: FC24 queue at ${address}: ${got:0:160}, want ${want:0:160}"; fi
            done
        else skip "${pair}: FC24 values"; fi
        if supported "${client}" "${server}" "server:serverFileRecord,client:clientWriteFileRecord,client:clientReadFileRecord"; then
            name=$(start_server "${server}"); wait_ready "${name}" || exit 1
            run_client "${client}" write-file-record --file 1 --record 10 --values 7,8,9 --host "${name}" >/dev/null
            got=$(run_client "${client}" read-file-record --file 1 --record 8 --length 6 --host "${name}" | jq -c '.response.registers')
            want=$(jq -c '.fileRecords[0].records[8:14] | .[2] = 7 | .[3] = 8 | .[4] = 9' "${FIXTURE}")
            if [[ "${got}" == "${want}" ]]; then ok "${pair}: FC21 is visible to a later FC20, neighbours untouched"
            else fail "${pair}: FC20 after FC21 returns ${got}, want ${want}"; fi
            if has "${server}" requestEvents; then
                sleep 0.3
                if docker logs "${name}" 2>/dev/null | jq -e -s 'map(select(.event == "request" and .function == 21 and .exception == null)) | length > 0' >/dev/null 2>&1
                then ok "${pair}: server reports the FC21 request"
                else fail "${pair}: no server event for the FC21 request"; fi
            fi
            stop_server "${name}"
        else skip "${pair}: FC21 value"; fi
    done
done

# ---- sessions: many requests on one connection, concurrent connections ----
for server in "${ADAPTERS[@]}"; do
    has_role "${server}" server || continue
    name=$(shared_server "${server}")
    want=$(slice holdingRegisters 0 125)
    for client in "${ADAPTERS[@]}"; do
        has_role "${client}" client || continue
        pair="${client}->${server}"
        repeat=()
        if has "${client}" clientRepeat; then
            repeat=(--repeat 200)
            doc=$(run_client "${client}" read-holding-registers --address 0 --quantity 125 --repeat 200 --host "${name}")
            if jq -e --argjson want "${want}" '.ok and .requests == 200 and .responses == 200 and .response.registers == $want' <<<"${doc}" >/dev/null 2>&1
            then ok "${pair}: 200 requests on one connection"
            else fail "${pair}: 200 requests on one connection: $(jq -c '{ok, error, requests, responses}' <<<"${doc}" 2>/dev/null)"; fi
        else skip "${pair}: requests on one connection"; fi

        tmp=$(mktemp -d)
        for i in 1 2 3 4 5 6 7 8; do
            run_client "${client}" read-holding-registers --address 0 --quantity 125 ${repeat[@]+"${repeat[@]}"} --host "${name}" > "${tmp}/${i}.json" &
        done
        wait
        good=0
        for i in 1 2 3 4 5 6 7 8; do
            jq -e --argjson want "${want}" '.ok and .response.registers == $want' "${tmp}/${i}.json" >/dev/null 2>&1 && good=$((good + 1))
        done
        rm -rf "${tmp}"
        if [[ "${good}" == 8 ]]; then ok "${pair}: eight concurrent connections"
        else fail "${pair}: only ${good} of 8 concurrent connections returned the fixture"; fi
    done
done

# ---- a known bug that no longer shows is a stale entry ----
while IFS=$'\t' read -r bug_adapter bug_regex bug_text; do
    if ! grep -qxF "${bug_text}" "${WORK}/reproduced"; then
        fail "known bug of ${bug_adapter} did not reproduce: ${bug_text}"
        log "    remove it from tests/known-bugs.tsv; the pin can move to this version"
    fi
done < "${WORK}/active"

finish
