#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

SUBCOMMAND_NAME="$1"
shift

set -euo pipefail

SCRIPTNAME=${0##*/}

rename_script="scripts/check-repo-names.sh"

function assert_no_usage() {
  local pattern=$1
  local args=('-P' "$pattern" '--' ":!$rename_script" ":!canton/")

  # Check if the renaming would cause any name clashes
  if git grep "${args[@]}"; then
    _error "Error: Renaming would cause name clashes with '$pattern'."
  else
    _info "No name clashes detected with '$pattern'."
  fi
}

function check_patterns() {
  local disallowed_patterns=(
    '(\b|[`_-])cn|cn(\b|[A-Z`_-])'
    '(\b|[a-z`_])CN|CN(\b|[A-Z`_])'
    '(\b|[`_-])cns|cns(\b|[A-Z`_-])'
    '(\b|[a-z`_])CNS|CNS(\b|[A-Z`_])'
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
    exceptions_args+=('--regexp' "$exception")
  done

  local pattern matches fail=0
  for pattern in "${disallowed_patterns[@]}"; do
    echo "Checking for occurrences of '$pattern' (case sensitive)"
    set +e
    matches="$(rg --line-number --engine=pcre2 --regexp "$pattern" --glob "!$rename_script" --glob "!/canton/**/*" \
                | rg --invert-match --engine=pcre2 "${exceptions_args[@]}")"
    set -e
    if [[ -n $matches ]]; then
      echo "$pattern occurs in code, please remedy"
      echo "$matches"
      fail=1
    fi
  done

  if [[ $fail -ne 0 ]]; then
    exit $fail
  fi
}

check_patterns
