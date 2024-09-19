#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

rename_script="scripts/check-repo-names.sh"

function check_patterns_locally() {
  # removing NEVERMATCHES alternative causes these to never match
  local disallowed_patterns=(
    '(\b|[`_])(cn|NEVERMATCHES)(\b|[A-Z`_])'
    '(\b|[a-z`_])(CN|NEVERMATCHES)(\b|[A-Z`_])'
    '(\b|[`_])(cns|NEVERMATCHES)(\b|[A-Z`_])'
    '(\b|[a-z`_])(CNS|NEVERMATCHES)(\b|[A-Z`_])'
    '(?i)canton network'
    '(?i)canton coin'
  )
  # exceptions are searched against grep lines, which follow the format
  # path/to/file:linenumber:line-contents
  # so that metadata may be incorporated into any of exceptions
  local exceptions=(
    '(\b|[`_])cn-docs'
    '@cn-load-tester\.com'
  )

  local exception exceptions_args=()
  for exception in "${exceptions[@]}"; do
    exceptions_args+=("--regexp=$exception")
  done

  local pattern matches fail=0
  for pattern in "${disallowed_patterns[@]}"; do
    echo "Checking for occurrences of '$pattern' (case sensitive)"
    set +e
    matches="$(rg --line-number --engine=pcre2 --regexp="$pattern" --glob='!'"$rename_script" --glob='!/canton/**/*' \
                | rg --invert-match --engine=pcre2 "${exceptions_args[@]}")"
    set -e
    if [[ -n $matches ]]; then
      echo "$pattern occurs in code, please remedy"
      echo "$matches"
      fail=1
    else
      echo "no name clashes detected with $pattern"
    fi
  done

  if [[ $fail -ne 0 ]]; then
    exit $fail
  fi
}

function setup_temp_splice() {
  local src="$1" tempsplice="$(mktemp -d)"
  cd "$src"
  direnv exec . scripts/copy-to-splice.sh "$tempsplice"
  cd "$tempsplice"
}

function check_patterns() {
  while getopts 'hs:' arg; do
    case "$arg" in
      h)
        echo '  Options: [-s SPLICE_REPO]
    -s: Run copy-to-splice from SPLICE_REPO first, and scan the result' 1>&2
        exit 0;;
      s)
        if [[ ! -d $OPTARG ]]; then
          echo "-s requires a splice repo directory" 1>&2
          exit 1
        fi
        setup_temp_splice "$OPTARG";;
      :|?) exit 1;;
    esac
  done
  check_patterns_locally
}

check_patterns "$@"
