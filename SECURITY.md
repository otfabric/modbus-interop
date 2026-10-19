# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in `modbus-interop`, please report it responsibly.

**Do not open a public GitHub issue for security vulnerabilities.**

Instead, please email **security@otfabric.io**, or open a private advisory on GitHub (the [Security](https://github.com/otfabric/modbus-interop/security) tab → **Advisories** → **Report a vulnerability**), with a description, steps to reproduce and the affected image tag or digest.

Vulnerabilities in the bundled implementations themselves (libmodbus, PyModbus, digitalpetri/modbus, NModbus, tokio-modbus) belong upstream; report them there, and tell us so that the pin can be moved.

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Older releases | No; pull the latest images |

## Security Considerations

### These are test fixtures, not products

The images run **unauthenticated, unencrypted Modbus TCP devices** that accept reads and writes from anyone who can reach them, and clients that write to whatever address they are given. That is their purpose.

- **Never attach a server to a plant, control or other production network**
- **Never point a client at a real device**: the write operations and `raw` act on what they are sent to
- `compose.yaml` and the `run-*` Makefile targets publish ports on `127.0.0.1` only. Keep it that way; the self-tests use private Docker networks and publish nothing
- There is no TLS and no access control

### Image hardening

- Processes run as a non-root user (uid 10001), which is why the servers listen on 1502 and not on 502
- Base images are pinned by digest; upstream sources by commit or exact version ([versions.yaml](versions.yaml))
- The runtime images contain the adapter, its runtime libraries and the fixtures; build tools stay in the build stages
- The images are rebuilt, not patched: a base image or upstream fix arrives with a new release

### Supply chain

- Upstream sources and packages are fetched during the image build at pinned versions, from GitHub, PyPI, Maven Central, NuGet and crates.io; where the ecosystem has lock files or hashes (`requirements.txt` hashes, `Cargo.lock`, NuGet lock files) the build verifies them
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists what each image contains and under which licence; the licence texts are in `/licenses` of every image
