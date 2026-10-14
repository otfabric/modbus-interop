# Third-party notices

The images published from this repository contain third-party software.
Every image carries the licence texts of what it contains under `/licenses`.

## What is licensed how

| Part | Licence |
|------|---------|
| Repository tooling, fixtures, schemas, scripts, documentation | MIT ([LICENSE](LICENSE)) |
| Adapter programs under `adapters/` | MIT |
| The published images | the adapter (MIT) together with the components listed below, each under its own licence |

Nothing in this repository is linked into a consumer: a library that tests
against the images talks to them over TCP and reads their output, and is not
affected by their licences.

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

## Corresponding source

Every upstream is built or installed at the exact version above from its
public source or registry; the pins are in the adapter Dockerfiles, their
lock files and [versions.yaml](versions.yaml). The adapter sources and the
build instructions for every image are this repository at the release tag.
