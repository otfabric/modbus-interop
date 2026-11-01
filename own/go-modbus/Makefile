# The otfabric-go-modbus image, from the repository root:
#   make -f own/go-modbus/Makefile image smoke interop
#
# Apart from the top-level Makefile on purpose: this image is not in ADAPTERS
# and not in a release of the harness.

IMAGE ?= ghcr.io/otfabric/otfabric-go-modbus:dev
# The harness release of the reference images to test against.
VERSION ?= $(shell git tag -l 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -1)
REFERENCES ?= libmodbus pymodbus digitalpetri nmodbus tokiomodbus
BASE_REGISTRY ?= docker.io/library

export VERSION
export ADAPTER_IMAGE_otfabric = $(IMAGE)

.PHONY: image smoke interop

image:
	docker build --build-arg BASE_REGISTRY=$(BASE_REGISTRY) --tag $(IMAGE) --file own/go-modbus/Dockerfile .

smoke:
	scripts/smoke.sh otfabric

interop:
	scripts/interop.sh otfabric $(REFERENCES)
