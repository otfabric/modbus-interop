# Third-party notices

The images published from this repository contain third-party software.
Every image carries the licence texts of what it contains under `/licenses`.

## What is licensed how

| Part | Licence |
|------|---------|
| Repository tooling, fixtures, schemas, scripts, documentation | MIT ([LICENSE](LICENSE)) |
| Adapter programs under `adapters/` | MIT, except `adapters/modbus4j/` |
| `adapters/modbus4j/` (links modbus4j) | GPL-3.0-or-later ([adapters/modbus4j/LICENSE](adapters/modbus4j/LICENSE)) |
| The published images | the adapter (MIT) together with the components listed below, each under its own licence |
| Image `modbus-interop-modbus4j` (not published; each user builds it locally) | GPL-3.0-or-later, with the licences of the components listed below |

Nothing in this repository is linked into a consumer: a library that tests
against the images talks to them over TCP and reads their output, and is not
affected by their licences.

## Image `modbus-interop-otfabric`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| go-modbus | latest release when built | MIT | https://github.com/otfabric/go-modbus |
| go-serial | v0.2.1 | MIT | https://github.com/otfabric/go-serial |
| Go standard library | 1.27.2 | BSD-3-Clause | https://go.dev |

## Image `modbus-interop-libmodbus`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| libmodbus | v3.2.0, commit `a9b025d12289855490b10d77461c99e001abfc0f` | LGPL-2.1-or-later | https://github.com/stephane/libmodbus |
| cJSON (Debian `libcjson1`) | 1.7.18-3.1+deb13u1 | MIT | https://github.com/DaveGamble/cJSON |
| Adapter | this repository, `adapters/libmodbus/` | MIT | https://github.com/otfabric/modbus-interop |
| Base system | Debian trixie-slim | various; see `/usr/share/doc/*/copyright` in the image | https://www.debian.org |

libmodbus is built unmodified from the commit above and shipped as a shared
library, `/usr/local/lib/libmodbus.so.5`, which the adapter links
dynamically: it can be replaced by another build of libmodbus.
Licence texts in the image: `/licenses/libmodbus/COPYING.LESSER` with
`/licenses/libmodbus/SOURCE` (where the source is), `/licenses/cjson/copyright`.

## Image `modbus-interop-pymodbus`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| PyModbus | 3.16.1 (wheel `sha256:bb433f7417280bdea1190d95495aa6fb8e1fa8e0d720c436922b270bc378024c`) | BSD-3-Clause | https://github.com/pymodbus-dev/pymodbus |
| CPython | 3.14.8 | PSF-2.0 | https://www.python.org |
| Adapter | this repository, `adapters/pymodbus/` | MIT | https://github.com/otfabric/modbus-interop |
| Base system | Debian trixie-slim (python image) | various; see `/usr/share/doc/*/copyright` in the image | https://www.debian.org |

Licence texts in the image: `/licenses/pymodbus/`, `/licenses/python/LICENSE.txt`.

## Image `modbus-interop-digitalpetri`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| digitalpetri/modbus (`modbus`, `modbus-tcp`) | 2.1.6 | EPL-2.0 | https://github.com/digitalpetri/modbus |
| netty-channel-fsm | 1.0.2 | EPL-2.0 | https://github.com/digitalpetri/netty-channel-fsm |
| strict-machine | 1.0.0 | EPL-2.0 | https://github.com/digitalpetri/strict-machine |
| Netty (buffer, codec, common, handler, resolver, transport, transport-native-unix-common) | 4.1.136.Final | Apache-2.0 | https://netty.io |
| slf4j-api, slf4j-simple | 2.0.18 | MIT | https://www.slf4j.org |
| Gson | 2.14.0 | Apache-2.0 | https://github.com/google/gson |
| error_prone_annotations | 2.48.0 | Apache-2.0 | https://github.com/google/error-prone |
| Eclipse Temurin JRE | 25.0.4.1+1 | GPL-2.0 with Classpath Exception | https://adoptium.net |
| Adapter | this repository, `adapters/digitalpetri/` | MIT | https://github.com/otfabric/modbus-interop |
| Base system | Ubuntu noble (temurin image) | various; see `/usr/share/doc/*/copyright` in the image | https://ubuntu.com |

Licence texts in the image: `/licenses/NOTICE.txt` (which component is under
which text), `EPL-2.0.txt`, `Apache-2.0.txt`, `netty-NOTICE.txt`, the slf4j
licence files, and `BUNDLED-JARS.txt` (the jars actually in the image).

## Image `modbus-interop-nmodbus`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| NModbus | 3.0.83, commit `d96e7707a08cebe7f436087e7d575c930963d91a` | MIT | https://github.com/NModbus/NModbus |
| .NET runtime | 10.0.12 | MIT | https://github.com/dotnet/runtime |
| Adapter | this repository, `adapters/nmodbus/` | MIT | https://github.com/otfabric/modbus-interop |
| Base system | Alpine 3.24 (dotnet runtime image) | various | https://alpinelinux.org |

Licence texts in the image: `/licenses/NModbus/LICENSE.txt`, `/licenses/dotnet/`.

## Image `modbus-interop-tokiomodbus`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| tokio-modbus | 0.17.0 (crate `sha256:95d06136e737e962c71d922a09148f5a264f6609a021fdf031ad16ffd172c818`) | MIT OR Apache-2.0 | https://github.com/slowtec/tokio-modbus |
| tokio | 1.53.2 | MIT | https://github.com/tokio-rs/tokio |
| serde_json | 1.0.151 | MIT OR Apache-2.0 | https://github.com/serde-rs/json |
| 28 further crates, statically linked | see `adapters/tokiomodbus/Cargo.lock` | MIT, Apache-2.0, Unlicense or Unicode-3.0 terms | https://crates.io |
| Rust standard library | 1.99.0 | MIT OR Apache-2.0 | https://www.rust-lang.org |
| Adapter | this repository, `adapters/tokiomodbus/` | MIT | https://github.com/otfabric/modbus-interop |
| Base system | Debian trixie-slim | various; see `/usr/share/doc/*/copyright` in the image | https://www.debian.org |

Licence texts in the image: `/licenses/crates/<crate>-<version>/` for every
linked crate, with `/licenses/crates/CRATES.txt` listing them and their SPDX
expressions, and `/licenses/rust-std/`.

## Image `modbus-interop-j2mod`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| j2mod | 3.4.0, commit `d7861c90f40a7ac219a2dc6987ffa54caa5d47a7` (jar `sha256:0d4254d260c81bc4b5e1e5be1f77ebc5156f42437d5e8c88907af41f7f11ed9a`) | Apache-2.0 | https://github.com/steveohara/j2mod |
| jSerialComm | 2.11.4 | Apache-2.0 OR LGPL-3.0-or-later, used under Apache-2.0 | https://github.com/Fazecast/jSerialComm |
| slf4j-api | 2.0.9 | MIT | https://www.slf4j.org |
| slf4j-simple | 2.0.18 | MIT | https://www.slf4j.org |
| Gson | 2.14.0 | Apache-2.0 | https://github.com/google/gson |
| error_prone_annotations | 2.48.0 | Apache-2.0 | https://github.com/google/error-prone |
| Eclipse Temurin JRE | 25.0.4.1+1 | GPL-2.0 with Classpath Exception | https://adoptium.net |
| Adapter | this repository, `adapters/j2mod/` | MIT | https://github.com/otfabric/modbus-interop |
| Base system | Ubuntu noble (temurin image) | various; see `/usr/share/doc/*/copyright` in the image | https://ubuntu.com |

Licence texts in the image: `/licenses/NOTICE.txt`, `Apache-2.0.txt` and the
slf4j licence files.

## Image `modbus-interop-jsmodbus`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| jsmodbus | 5.0.0, git `fdf02b2600b22519ae4e7465e9bec381a1653172` (tarball `sha512-YuJlEoWn06eeV4xgMNw8v+07QEaJcq3x7ROtapk3z1QWmbZJn94JkV2yrxULeWIYSrgbLDivifUL2QFq7m/low==`) | MIT | https://github.com/Cloud-Automation/node-modbus |
| commander | 11.1.0 | MIT | https://github.com/tj/commander.js |
| crc | 4.3.2 | MIT | https://github.com/alexgorbatchev/crc |
| debug | 4.4.3 | MIT | https://github.com/debug-js/debug |
| ms | 2.1.3 | MIT | https://github.com/vercel/ms |
| Node.js | 26.13.0 | MIT, with the licences of its bundled components | https://nodejs.org |
| Adapter | this repository, `adapters/jsmodbus/` | MIT | https://github.com/otfabric/modbus-interop |
| Base system | Debian trixie-slim (node image) | various; see `/usr/share/doc/*/copyright` in the image | https://www.debian.org |

Licence texts in the image: `/licenses/<package>/` for every npm package and
`/licenses/BUNDLED-PACKAGES.txt` listing them. The jsmodbus package has no
licence file; `/licenses/jsmodbus/LICENSE` is the licence section of its README.

## Image `modbus-interop-modbusesp`

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| modbus-esp8266 | 4.1.1, commit `299666307a052df52f757f3131e292c2513bb57f` | BSD-3-Clause | https://github.com/emelianov/modbus-esp8266 |
| cJSON (Debian `libcjson1`) | 1.7.18-3.1+deb13u1 | MIT | https://github.com/DaveGamble/cJSON |
| Adapter | this repository, `adapters/modbusesp/` | MIT | https://github.com/otfabric/modbus-interop |
| Base system | Debian trixie-slim | various; see `/usr/share/doc/*/copyright` in the image | https://www.debian.org |

modbus-esp8266 is compiled unmodified from the commit above, statically into
the adapter binary, with four of its documented compile-time settings changed
(`adapters/modbusesp/src/settings_overlay.h`). Licence texts in the image:
`/licenses/modbus-esp8266/LICENSE.txt` with `SOURCE` and `SOURCES.sha256`,
`/licenses/cjson/copyright`.

## Image `modbus-interop-modbus4j` (not published)

| Component | Version | Licence | Source |
|-----------|---------|---------|--------|
| modbus4j | 3.1.0, commit `3325a2cbce786989156ba7999d6adc2755f7ea5b` (archive `sha256:eb20d1fce2d25e3de8e1b67025ca06cf43babd9a249dad82c4a49ebf2fd93344`) | GPL-3.0-or-later | https://github.com/MangoAutomation/modbus4j |
| commons-lang3 | 3.7 | Apache-2.0 | https://commons.apache.org/proper/commons-lang/ |
| commons-logging | 1.1.1 | Apache-2.0 | https://commons.apache.org/proper/commons-logging/ |
| Gson | 2.14.0 | Apache-2.0 | https://github.com/google/gson |
| error_prone_annotations | 2.48.0 | Apache-2.0 | https://github.com/google/error-prone |
| Eclipse Temurin JRE | 25.0.4.1+1 | GPL-2.0 with Classpath Exception | https://adoptium.net |
| Adapter | this repository, `adapters/modbus4j/` | GPL-3.0-or-later | https://github.com/otfabric/modbus-interop |
| Base system | Ubuntu noble (temurin image) | various; see `/usr/share/doc/*/copyright` in the image | https://ubuntu.com |

modbus4j is built unmodified from the pinned source archive (see
`/licenses/modbus4j/SOURCE` in the image). The image carries the GPL text and
the adapter's own source. Because of the licence it is never published:
`make image-modbus4j` builds it from the pinned source on the user's machine.

## Corresponding source

Every upstream is built or installed at the exact version above from its
public source or registry; the pins are in the adapter Dockerfiles, their
lock files and [versions.yaml](versions.yaml). The adapter sources and the
build instructions for every image are this repository at the release tag.
