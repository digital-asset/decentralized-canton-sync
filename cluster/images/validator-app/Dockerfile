# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5003
EXPOSE 10013

COPY app.conf bootstrap.sc pre-bootstrap.sh /app/
