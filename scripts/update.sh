#!/usr/bin/env bash

set -eou pipefail

CN_REPO_ROOT=$1

pkgs=$(ls -I "dars*" -I "splitwell*" $CN_REPO_ROOT/daml)

for pkg in $pkgs; do
    src="$CN_REPO_ROOT/daml/$pkg"
    rsync -av --delete "$src"  --exclude '.daml' --include '*/' --include 'daml/Splice/*' --include '*.yaml' --include '*.md' --exclude '*' daml/
done

COPYRIGHT_ESCAPED='-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and\/or its affiliates\. All rights reserved\.\
-- SPDX-License-Identifier: Apache-2\.0\
\
'

find daml -type f -name \*.daml -exec bash -c 'grep -q -e "-- Copyright" "$0" || sed -i "1s/^/$1/" $0' '{}' "$COPYRIGHT_ESCAPED" \;
