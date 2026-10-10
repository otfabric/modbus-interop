#!/usr/bin/env bash
# Container-contract smoke test for one adapter image.
#
# Usage: smoke.sh <adapter>          e.g. smoke.sh libmodbus
#
# Checks the parts of docs/CONTAINER_CONTRACT.md that do not need a second
# adapter: the command surface, capabilities, the baked fixture, readiness,
# the server event stream, exit codes and a clean stop on SIGTERM.
#
# Environment: REGISTRY, VERSION (image tag, default dev), VERBOSE=1,
#              REPORT_DIR (keeps the capability document when set).

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/lib.sh
source "${SCRIPT_DIR}/lib.sh"

ADAPTER="${1:?usage: smoke.sh <adapter>}"
IMAGE="$(image_of "${ADAPTER}")"
require_tools
trap cleanup EXIT
network_up
log "${IMAGE}"

expect_exit() {  # expect_exit <code> <label> <docker run args...>
    local want="$1" label="$2"; shift 2
    docker run --rm --network "${NETWORK}" --label "modbus-interop.run=${RUN_ID}" "$@" >/dev/null 2>&1
    local got=$?
    if [[ "${got}" == "${want}" ]]; then ok "${label}: exit ${got}"; else fail "${label}: exit ${got}, want ${want}"; fi
}

# ---- capabilities ----
caps=$(docker run --rm "${IMAGE}" print-capabilities 2>/dev/null)
if [[ -n "${REPORT_DIR:-}" ]]; then
    mkdir -p "${REPORT_DIR}"
    printf '%s\n' "${caps}" > "${REPORT_DIR}/capabilities-${ADAPTER}.json"
fi
if jq -e --arg a "${ADAPTER}" '
        .schemaVersion == "1.0" and .adapter == $a and .protocol == "modbus-tcp"
        and (.adapterVersion | type == "string") and (.upstream.name | type == "string")
        and (.upstream.version | type == "string") and (.upstream.license | type == "string")
        and (.upstream.independentEngine | type == "boolean")
        and (.roles.server | type == "boolean") and (.roles.client | type == "boolean")
        and (.features | type == "object") and ([.features[] | type == "boolean"] | all)
        and (.serverFunctions | type == "array") and (.clientOperations | type == "array")' <<<"${caps}" >/dev/null 2>&1
then ok "print-capabilities"; else fail "print-capabilities is not a valid capability document: ${caps}"; fi
if [[ $(wc -l <<<"${caps}") -eq 1 ]]; then ok "print-capabilities is one line"; else fail "print-capabilities printed more than one line"; fi
has() { jq -e --arg f "$1" '.features[$f] == true' <<<"${caps}" >/dev/null 2>&1; }
has_role() { jq -e --arg r "$1" '.roles[$r] == true' <<<"${caps}" >/dev/null 2>&1; }

# ---- the baked fixture is the repository's ----
if diff -q <(docker run --rm "${IMAGE}" print-fixture baseline 2>/dev/null) "${ROOT}/fixtures/baseline/fixture.json" >/dev/null
then ok "print-fixture baseline"; else fail "print-fixture baseline differs from fixtures/baseline/fixture.json"; fi
if diff -q <(docker run --rm "${IMAGE}" print-fixture 2>/dev/null) "${ROOT}/fixtures/baseline/fixture.json" >/dev/null
then ok "print-fixture defaults to baseline"; else fail "print-fixture without a name is not the baseline fixture"; fi
expect_exit 1 "print-fixture of an unknown name" "${IMAGE}" print-fixture no-such-fixture
expect_exit 0 "version" "${IMAGE}" version

# ---- usage errors never touch the network ----
# (Without a command the image runs "server": that is its default.)
expect_exit 2 "unknown command" "${IMAGE}" frobnicate
expect_exit 2 "server with an unknown flag" "${IMAGE}" server --frobnicate
if has_role client; then
    expect_exit 2 "client without --host" "${IMAGE}" client read-coils --address 0 --quantity 1
    expect_exit 2 "client with an unknown operation" "${IMAGE}" client frobnicate --host nowhere
    expect_exit 2 "client with an unknown flag" "${IMAGE}" client read-coils --host nowhere --address 0 --quantity 1 --frobnicate 1
    expect_exit 2 "read without --quantity" "${IMAGE}" client read-holding-registers --host nowhere --address 0
    expect_exit 2 "read of 126 registers" "${IMAGE}" client read-holding-registers --host nowhere --address 0 --quantity 126
    expect_exit 2 "read of 0 coils" "${IMAGE}" client read-coils --host nowhere --address 0 --quantity 0
    expect_exit 2 "address above 65535" "${IMAGE}" client read-coils --host nowhere --address 65536 --quantity 1
    expect_exit 2 "register value above 65535" "${IMAGE}" client write-single-register --host nowhere --address 0 --value 65536
    expect_exit 2 "coil value that is not a boolean" "${IMAGE}" client write-single-coil --host nowhere --address 0 --value maybe
    expect_exit 2 "write without --values" "${IMAGE}" client write-multiple-registers --host nowhere --address 0
    expect_exit 2 "unit identifier above 255" "${IMAGE}" client read-coils --host nowhere --address 0 --quantity 1 --unit-id 256
fi

# ---- an invalid fixture stops the server before it listens ----
bad=$(mktemp -d)
trap 'rm -rf "${bad}"; cleanup' EXIT
chmod 755 "${bad}"
# Each invalid fixture sits in a directory named after it, so that it breaks
# exactly one rule and not also "name is the directory name".
mkdir -p "${bad}"/{good,block,unit,broken,name}/baseline "${bad}/name/other"
cp "${ROOT}/fixtures/baseline/fixture.json" "${bad}/good/baseline/fixture.json"
jq '.holdingRegisters.address = 65400' "${ROOT}/fixtures/baseline/fixture.json" > "${bad}/block/baseline/fixture.json"
jq '.unitId = 0' "${ROOT}/fixtures/baseline/fixture.json" > "${bad}/unit/baseline/fixture.json"
echo '{ not json' > "${bad}/broken/baseline/fixture.json"
cp "${ROOT}/fixtures/baseline/fixture.json" "${bad}/name/other/fixture.json"
chmod -R a+rX "${bad}"
expect_exit 4 "server with a block past address 65535" -v "${bad}:/bad:ro" "${IMAGE}" server --fixture /bad/block/baseline/fixture.json
expect_exit 4 "server with unit identifier 0" -v "${bad}:/bad:ro" "${IMAGE}" server --fixture /bad/unit/baseline/fixture.json
expect_exit 4 "server with a fixture that is not JSON" -v "${bad}:/bad:ro" "${IMAGE}" server --fixture /bad/broken/baseline/fixture.json
expect_exit 4 "server with a fixture whose name is not its directory" -v "${bad}:/bad:ro" "${IMAGE}" server --fixture /bad/name/other/fixture.json
# The control: the same mount with a valid fixture is served.
control=$(docker run -d --network "${NETWORK}" --label "modbus-interop.run=${RUN_ID}" -v "${bad}:/bad:ro" "${IMAGE}" server --fixture /bad/good/baseline/fixture.json)
served=no
for _ in $(seq 1 100); do
    if docker logs "${control}" 2>/dev/null | grep -q '"event":"ready"'; then served=yes; break; fi
    [[ "$(docker inspect -f '{{.State.Running}}' "${control}" 2>/dev/null)" == "true" ]] || break
    sleep 0.2
done
docker rm -f "${control}" >/dev/null 2>&1
if [[ "${served}" == "yes" ]]; then ok "server with a mounted valid fixture: ready"; else fail "server with a mounted valid fixture did not become ready"; fi
expect_exit 4 "server with a missing fixture" "${IMAGE}" server --fixture /no/such/fixture.json

# ---- a connection that cannot be made ----
if has_role client; then
    doc=$(run_client "${ADAPTER}" read-coils --address 0 --quantity 1 --host "no-such-host-${RUN_ID}" --connect-timeout-ms 1500); CLIENT_EXIT=$?
    if [[ "${CLIENT_EXIT}" == 3 ]] && jq -e '.ok == false and .error.code == "connect-failed" and .connected == false
            and .requests == 0 and .responses == 0 and .response == null and .exception == null' <<<"${doc}" >/dev/null 2>&1
    then ok "connect failure: exit 3 and a result document"; else fail "connect failure: exit ${CLIENT_EXIT}, ${doc}"; fi
fi

has_role server || { finish; exit $?; }

# ---- server: readiness, events, operations, clean stop ----
name=$(start_server "${ADAPTER}")
if wait_ready "${name}"; then ok "server becomes ready"; else fail "server did not become ready"; finish; exit 1; fi
first=$(docker logs "${name}" 2>/dev/null | head -1)
if jq -e --arg a "${ADAPTER}" '.event == "ready" and .adapter == $a and .fixture == "baseline"
        and .unitId == 1 and (.address | type == "string")' <<<"${first}" >/dev/null 2>&1
then ok "ready event"; else fail "first stdout line is not the ready event: ${first}"; fi
health=$(docker inspect --format '{{if .State.Health}}yes{{else}}no{{end}}' "${name}")
if [[ "${health}" == yes ]]; then ok "image has a health check"; else fail "image has no health check"; fi
user=$(docker exec "${name}" id -u 2>/dev/null)
if [[ "${user}" == 10001 ]]; then ok "runs as uid 10001"; else fail "runs as uid ${user}, want 10001"; fi

if has_role client; then
    doc=$(run_client "${ADAPTER}" read-holding-registers --address 0 --quantity 8 --host "${name}"); CLIENT_EXIT=$?
    if [[ "${CLIENT_EXIT}" == 0 ]] && jq -e --arg a "${ADAPTER}" '
            .schemaVersion == "1.0" and .adapter == $a and .operation == "read-holding-registers" and .ok == true
            and .error == null and .exception == null and .connected and .unitId == 1
            and .requests == 1 and .responses == 1 and (.elapsedMs | type == "number")
            and .response == {"function":3,"registers":[0,1,32767,32768,65535,4660,43981,255]}' <<<"${doc}" >/dev/null 2>&1
    then ok "client result document"; else fail "read-holding-registers: exit ${CLIENT_EXIT}, ${doc}"; fi
    if [[ $(wc -l <<<"${doc}") -eq 1 ]]; then ok "client result is one line"; else fail "client printed more than one line on stdout"; fi

    doc=$(run_client "${ADAPTER}" read-holding-registers --address 300 --quantity 1 --host "${name}"); CLIENT_EXIT=$?
    if [[ "${CLIENT_EXIT}" == 1 ]] && jq -e '.ok == false and .error.code == "exception" and .connected
            and .exception == {"function":131,"code":2} and .response == null
            and .requests == 1 and .responses == 0' <<<"${doc}" >/dev/null 2>&1
    then ok "exception result document"; else fail "read outside the fixture: exit ${CLIENT_EXIT}, ${doc}"; fi

    run_client "${ADAPTER}" write-multiple-registers --address 20 --values 7,8,9 --host "${name}" >/dev/null
    run_client "${ADAPTER}" write-single-coil --address 3 --value true --host "${name}" >/dev/null
    sleep 0.3
fi
events=$(docker logs "${name}" 2>/dev/null)
if grep -v '^{' <<<"${events}" | grep -q .; then fail "server stdout has lines that are not JSON"; else ok "server stdout is JSON Lines only"; fi
if has_role client; then
    wants=()
    if has connectionEvents; then
        wants+=('.event == "connection-opened" and (.peer | type == "string")' '.event == "connection-closed" and (.peer | type == "string")')
    fi
    if has requestEvents; then
        wants+=(
            '.event == "request" and .unitId == 1 and .function == 3 and .address == 0 and .quantity == 8 and .exception == null'
            '.event == "request" and .function == 3 and .address == 300 and .quantity == 1 and .exception == 2'
            '.event == "request" and .function == 16 and .address == 20 and .quantity == 3 and .registers == [7,8,9] and .exception == null'
            '.event == "request" and .function == 5 and .address == 3 and .quantity == 1 and .bits == [true] and .exception == null'
        )
    fi
    for want in ${wants[@]+"${wants[@]}"}; do
        if jq -e -s "map(select(${want})) | length > 0" <<<"${events}" >/dev/null 2>&1; then ok "event: ${want}"; else fail "no server event with ${want}"; fi
    done
fi

docker stop -t 10 "${name}" >/dev/null
code=$(docker inspect --format '{{.State.ExitCode}}' "${name}")
last=$(docker logs "${name}" 2>/dev/null | tail -1)
if [[ "${code}" == 0 ]]; then ok "SIGTERM: exit 0"; else fail "SIGTERM: exit ${code}"; fi
if jq -e '.event == "stopped"' <<<"${last}" >/dev/null 2>&1; then ok "stopped event"; else fail "last stdout line is not the stopped event: ${last}"; fi
stop_server "${name}"

# ---- a client that waits for a device that never answers ----
if has_role client; then
    # A listener that accepts and stays silent: the server image's own shell
    # is not part of the contract, so the silent peer is a paused server.
    name=$(start_server "${ADAPTER}"); wait_ready "${name}"
    docker pause "${name}" >/dev/null
    doc=$(run_client "${ADAPTER}" read-coils --address 0 --quantity 1 --host "${name}" --timeout-ms 800 --connect-timeout-ms 800); CLIENT_EXIT=$?
    docker unpause "${name}" >/dev/null
    if [[ "${CLIENT_EXIT}" == 1 || "${CLIENT_EXIT}" == 3 ]] && jq -e '.ok == false and (.error.code == "timeout" or .error.code == "connect-failed")' <<<"${doc}" >/dev/null 2>&1
    then ok "silent device: $(jq -r '.error.code' <<<"${doc}"), exit ${CLIENT_EXIT}"; else fail "silent device: exit ${CLIENT_EXIT}, ${doc}"; fi
    stop_server "${name}"
fi

# ---- a port that is already taken ----
name=$(start_server "${ADAPTER}"); wait_ready "${name}"
docker exec "${name}" /usr/local/bin/modbus-interop server --ready-file /tmp/second >/dev/null 2>&1
code=$?
if [[ "${code}" != 0 ]]; then ok "second server on a busy port fails (exit ${code})"; else fail "second server on a busy port exited 0"; fi
stop_server "${name}"

finish
