#!/usr/bin/env bash
# Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0


# Fix links to external google protobuf documentation
re='s,:ref:`(\.?google\.protobuf\.[^<>`\n]*) <google\.protobuf\.([^<>`\n]*)>`, `\1 <https://developers.google.com/protocol-buffers/docs/reference/google.protobuf#\L\2>`__,g'
sed -r "$re" -i docs.rst
# Fix links to external google rpc documentation
re='s,:ref:`(\.?google\.rpc\.[^<>`\n]*) <google\.rpc\.([^<>`\n]*)>`, `\1 <https://cloud.google.com/tasks/docs/reference/rpc/google.rpc#\L\2>`__,g'
sed -r "$re" -i docs.rst
