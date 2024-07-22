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

copy_daml daml $SPLICE_REPO_ROOT/daml

cp -r $CN_REPO_ROOT/scripts/scan-txlog scripts/
cp -r scripts/scan-txlog $SPLICE_REPO_ROOT/scripts/
