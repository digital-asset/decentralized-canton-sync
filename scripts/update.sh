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

pkgs=$(ls "$CN_REPO_ROOT/daml" | grep -v '^\(dars\|splitwell\)')

copy_daml $CN_REPO_ROOT/daml daml

prepend_copyright() {
    read -r -d '' COPYRIGHT_ESCAPED << 'EOF'
-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0
EOF
    file="$1"
    first_line=$(head -n 1 "$file")
    if [[ ! "$first_line" =~ ^--\ Copyright ]]; then
        echo -e "$COPYRIGHT_ESCAPED\n" | cat - "$file" > "$file.tmp" && mv "$file.tmp" "$file"
    fi    
}
export -f prepend_copyright

find daml -type f -name \*.daml -exec bash -c 'prepend_copyright "$0"' {} \;

copy_daml daml $SPLICE_REPO_ROOT/daml
