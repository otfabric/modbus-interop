# Requirements

What this repository must do and must not do. [PLAN.md](PLAN.md) says in
which order; [docs/CONTAINER_CONTRACT.md](docs/CONTAINER_CONTRACT.md) and
[docs/FIXTURES.md](docs/FIXTURES.md) are the specifications that follow from
it.

## Purpose

Independent, reproducible interoperability validation for Modbus libraries:
established third-party implementations, packaged as containers that all
behave the same way, so that a library can be tested against real
implementation diversity without owning any of it.

It is not another Modbus implementation and not a general protocol-testing
framework.

## R1 - Repository isolation

- R1.1 This repository does not import, build, link against or depend on any
  library under test, and contains no test logic or expectation specific to
  one.
- R1.2 A consumer depends on published images and the documented contract
  only. It does not need a checkout of this repository to run its tests.
- R1.3 No third-party code from here is copied into a consumer, and the
  contract does not shape a consumer's public API.

| Responsibility | Here | Consumer |
|----------------|:----:|:--------:|
| Third-party implementations, adapters, Dockerfiles | yes | no |
| Fixtures, fixture schema and validation | yes | no |
| Container contract, capability declarations | yes | no |
| Image builds and publishing, licence compliance | yes | no |
| Self-tests of the reference stacks | yes | no |
| Scenarios and assertions about a library | no | yes |
| Compatibility claims and release qualification of a library | no | yes |

## R2 - Reference implementations

- R2.1 Exactly these five are in scope, in this order of priority:

  | Priority | Implementation | Language | Independent engine |
  |----------|----------------|----------|:------------------:|
  | P0 | libmodbus | C | yes |
  | P0 | PyModbus | Python | yes |
  | P0 | digitalpetri/modbus | Java | yes |
  | P1 | NModbus | C# | yes |
  | P1 | tokio-modbus | Rust | yes |

- R2.2 Each is its own image. No image contains two implementations.
- R2.3 Each image provides both roles where the implementation has them: a
  server (a device serving a fixture) and a client (bounded operations).
- R2.4 An adapter uses the implementation's public API as an application
  would. It does not patch the implementation and does not re-implement
  protocol behaviour the implementation lacks: what a stack cannot do is
  declared in its capabilities.

## R3 - One contract

- R3.1 Every image has the same commands, flags, JSON documents and exit
  codes ([docs/CONTAINER_CONTRACT.md](docs/CONTAINER_CONTRACT.md)).
- R3.2 Every server serves the same fixture the same way
  ([docs/FIXTURES.md](docs/FIXTURES.md)). The fixture is baked into the image
  and can be printed from it.
- R3.3 Readiness is a file, never a delay. Every wait is bounded.
- R3.4 stdout carries JSON only; diagnostics go to stderr.

## R4 - Honest capabilities

- R4.1 `print-capabilities` declares a feature `true` only when the
  self-tests of this repository exercise it for that adapter.
- R4.2 A consumer can skip, not fail, what a stack declares unsupported.

## R5 - Self-tests

- R5.1 A smoke test checks the contract of each image on its own.
- R5.2 A cross-stack test runs every client against every server on every
  case, requires the expected outcome and requires all pairings to return the
  same result document.
- R5.3 Disagreements between stacks are resolved against the Modbus
  specification, not by majority, and recorded in
  [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## R6 - Reproducibility

- R6.1 Upstreams are pinned by exact version or commit, base images by
  digest; [versions.yaml](versions.yaml) mirrors every pin and a check fails
  when it disagrees with the build files.
- R6.2 Images are built natively for linux/amd64 and linux/arm64 and
  self-tested on both before they are published.
- R6.3 The latest upstream release is the pin unless it fails the
  self-tests; then it is built as a candidate with its bug listed in
  [tests/known-bugs.tsv](tests/known-bugs.tsv).

## R7 - Safety

- R7.1 The images are test fixtures: unauthenticated, loopback or private
  network only ([SECURITY.md](SECURITY.md)).
- R7.2 Processes run as a non-root user.

## R8 - Licensing

- R8.1 The repository's own code is MIT-licensed.
- R8.2 Every image carries the licence texts of what it contains in
  `/licenses`, and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists them.
