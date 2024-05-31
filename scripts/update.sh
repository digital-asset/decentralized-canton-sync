#!/usr/bin/env bash

set -eou pipefail

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <CN_REPO_ROOT> <SPLICE_REPO_ROOT>"
    exit 1
fi

CN_REPO_ROOT=$1
SPLICE_REPO_ROOT=$2

function copy_daml {
    target="$2"
    for pkg in $pkgs; do
        src="$1/$pkg"
        rsync -av --delete "$src"  --exclude '.daml' --include '*/' --include 'daml/Splice/*' --include '*.yaml' --include '*.md' --exclude '*' "$target"
        find "$target" -empty -type d -delete
    done
}

pkgs=$(ls -I "dars*" -I "splitwell*" $CN_REPO_ROOT/daml)

copy_daml $CN_REPO_ROOT/daml daml

COPYRIGHT_ESCAPED='-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and\/or its affiliates\. All rights reserved\.\
-- SPDX-License-Identifier: Apache-2\.0\
\
'

find daml -type f -name \*.daml -exec bash -c 'grep -q -e "-- Copyright" "$0" || sed -i "1s/^/$1/" $0' '{}' "$COPYRIGHT_ESCAPED" \;

copy_daml daml $SPLICE_REPO_ROOT/daml
