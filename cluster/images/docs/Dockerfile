# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM splice-app:${base_version}

FROM nginx:stable
ARG version

COPY --from=0 app/splice-node/docs/html /usr/share/nginx/html/
