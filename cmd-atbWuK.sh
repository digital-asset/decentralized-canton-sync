        set -euo pipefail
        echo "Running command"
        release=$(${SPLICE_ROOT}/build-tools/get-release-if-on-release-branch.sh)
if [ -n "${release:-}" ]; then
  echo "Splice is on release branch ${release}, using release-line-${release} as base branch"
  echo "release-line-${release}" > /tmp/base_branch
else
  echo "Splice is not on a release branch, using main as base branch"
  echo "main" > /tmp/base_branch
fi

