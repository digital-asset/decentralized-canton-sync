# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

images := \
	canton \
	canton-participant \
	canton-domain \
	canton-sequencer \
	canton-mediator \
	canton-cometbft-sequencer \
	cometbft \
	\
	cn-app \
	cn-debug \
	sv-app \
	sv-web-ui \
	scan-app \
	scan-web-ui \
	wallet-web-ui \
	validator-app \
	splitwell-app \
	\
	cns-web-ui \
	splitwell-web-ui \
	\
	docs \
	gcs-proxy \
	load-tester \
	multi-validator \
	multi-participant \
	pulumi-kubernetes-operator \

canton-image := cluster/images/canton
cn-image := cluster/images/cn-app

ifdef CI
    # never use the cache in CI on the master branch
    cache_opt := --no-cache
else
    # Local builds (which may be on an M1) are explicitly constrained
    # to x86.
    platform_opt := --platform=linux/amd64
    docker_opt := --force
endif

docker-build := target/docker.id
docker-push := target/docker.push
docker-promote := target/docker.promote
docker-local-image-tag := target/local-image-tag
docker-image-tag := target/image-tag

#########
# Per-image targets
#########

# You cannot define implicit phony targets
# so instead we define the phony targets in here.
define DEFINE_PHONY_RULES =
prefix := cluster/images/$(1)

include cluster/images/$(1)/local.mk

# Stop make from deleting version files to get working caching.
.NOTINTERMEDIATE: $$(prefix)/$(docker-image-tag) $$(prefix)/$(docker-local-image-tag)

.PHONY: $$(prefix)/docker-build
$$(prefix)/docker-build: $$(prefix)/$(docker-build)

.PHONY: $$(prefix)/docker-push
$$(prefix)/docker-push: $$(prefix)/$(docker-push)

.PHONY: $$(prefix)/docker-promote
$$(prefix)/docker-promote: $$(prefix)/$(docker-promote)

.PHONY: $$(prefix)/docker-check
$$(prefix)/docker-check: $$(prefix)/$(docker-image-tag)
	docker-check $$$$(cat $$(abspath $$<))

.PHONY: $$(prefix)/clean
$$(prefix)/clean:
	-rm -vfr $$(@D)/target
endef # end DEFINE_PHONY_RULES

$(foreach image,$(images),$(eval $(call DEFINE_PHONY_RULES,$(image))))

#########
# docker pattern rules
#########

%/$(docker-local-image-tag): force-update-version
	mkdir -p $(@D)
	overwrite-if-changed $$(basename $$(dirname $(@D))):$(shell get-snapshot-version) $@

%/$(docker-image-tag): force-update-version
	mkdir -p $(@D)
	get-docker-image-name $$(basename $$(dirname $(@D))) > $@

%/$(docker-build): %/$(docker-local-image-tag) %/Dockerfile
	mkdir -pv $(@D)
	@echo docker build triggered because these files changed: $?
	docker build $(platform_opt) --iidfile $@ $(cache_opt) $(build_arg) -t $$(cat $<) $(@D)/..

%/$(docker-push):  %/$(docker-image-tag) %/$(docker-build)
	cd $(@D)/.. && docker-push $$(cat $(abspath $<)) $(docker_opt)

%/$(docker-promote):  %/$(docker-image-tag)
	cd $(@D)/.. && docker-promote $$(cat $(abspath $<)) $(shell get-docker-image-name $$(basename $$(dirname $(@D))) --artifactory)
