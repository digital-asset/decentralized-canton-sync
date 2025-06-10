
  set -euo pipefail

  dir=${PWD}
  cd $DA_REPO_ROOT/splice
  . .envrc.vars
  cd $DA_REPO_ROOT
  . .envrc.vars

  cd $dir

  if [[ "$TARGET_CLUSTER" != none ]]; then
    . $DA_REPO_ROOT/cluster/deployment/$TARGET_CLUSTER/.envrc.vars
    . $SPLICE_ROOT/build-tools/lib/cluster.envrc.vars
    echo "Using cluster hostname suffix: ${GCP_CLUSTER_HOSTNAME}"
  fi

  if [[ false == true ]]; then
    if [[ "$TARGET_CLUSTER" == none ]]; then
      echo "TARGET_CLUSTER must be set"
      exit 1
    fi
    # Authenticate to the correct GCP project for the cluster
    echo "Authenticating to cluster \"$TARGET_CLUSTER\" in project \"${CLOUDSDK_CORE_PROJECT}\""
    case ${CLOUDSDK_CORE_PROJECT} in
      da-cn-scratchnet)
        export CN_ACTIVE_ENVIRONMENT_KEY="${GCP_DA_CN_SCRATCHNET_KEY}"
        ;;

      da-cn-scratchnet2)
        export CN_ACTIVE_ENVIRONMENT_KEY="${GCP_DA_CN_SCRATCHNET2_KEY}"
        ;;

      da-cn-ci)
        export CN_ACTIVE_ENVIRONMENT_KEY="${GCP_DA_CN_CI_KEY}"
        ;;

      da-cn-ci-2)
        export CN_ACTIVE_ENVIRONMENT_KEY="${GCP_DA_CN_CI_2_KEY}"
        ;;

      da-cn-devnet)
        export CN_ACTIVE_ENVIRONMENT_KEY="${GCP_DA_CN_DEVNET_KEY}"
        ;;

      da-cn-mainnet)
        export CN_ACTIVE_ENVIRONMENT_KEY="${GCP_DA_CN_MAINNET_KEY}"
        ;;

      *)
        echo "Cannot authenticate to GCP project without key: ${CLOUDSDK_CORE_PROJECT}"
        exit 1
        ;;
    esac

    # GOOGLE_CREDENTIALS variable is used by pulumi
    export GOOGLE_CREDENTIALS=${CN_ACTIVE_ENVIRONMENT_KEY}

    # GOOGLE_APPLICATION_CREDENTIALS variable is used by gcloud typescript client, it should include the filename of the key
    export GOOGLE_APPLICATION_CREDENTIALS=$(mktemp gcp-creds-XXXXXX.json)
    echo ${CN_ACTIVE_ENVIRONMENT_KEY} > ${GOOGLE_APPLICATION_CREDENTIALS}

    # TODO(DACH-NY/canton-network-node#7702) Extract retry logic for CI tasks into command/script
    # gcloud auth with a limited number of retries
    MAX_AUTH_RETRIES=5
    AUTH_TRY_COUNT=0
    AUTH_RETRY_WAIT=30
    until [ $AUTH_TRY_COUNT -gt $MAX_AUTH_RETRIES ]
    do
        echo "Trying gcloud auth. Attempt #$[AUTH_TRY_COUNT+1]"

        # Try to authenticate and break retry loop in case of success
        echo ${CN_ACTIVE_ENVIRONMENT_KEY} | gcloud auth activate-service-account --key-file=- && break

        AUTH_TRY_COUNT=$[$AUTH_TRY_COUNT+1]
        sleep $AUTH_RETRY_WAIT
    done

    if [ $AUTH_TRY_COUNT -gt $MAX_AUTH_RETRIES ]; then
        echo "Exceeded maximum retries ($AUTH_TRY_COUNT / $MAX_AUTH_RETRIES), no more attempts: gcloud auth activate-service-account" >&2
        exit 1
    fi

  fi

  MAX_RETRY=0
  n=0
  until [ $n -gt $MAX_RETRY ]
  do
      if [ $n -gt 0 ]; then
        sleep 5
        
      fi
      cmd_wrapper() {
        # We write the cmd to a separate file. That way `set -e` statements in the cmd will take effect
        # despite us using this in a tested context below where they would usually be disabled.
        # See https://gist.github.com/cocreature/9c345b75b7dad05ed32865b21c1b0460 for a minimal example
        # of what happens if we just call parameters.cmd directly.
        # We enforce set -euo pipefail by including it in all the commands. Even if the command has already set it, then it will have no effect
        # but this way we ensure all the commands will fail on error and no lead to confusing behaviour
        # append random string to the cmd.sh file to avoid parallel steps writing and executing the same cmd.sh files
        CMD_FILE_2=$(mktemp cmd-XXXXXX.sh)
        echo "temporary CMD_FILE_2: $CMD_FILE_2"
        cat <<'EOF2' > $CMD_FILE_2
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

EOF2
        RESULT=$?

        if [ $RESULT -ne 0 ]; then
          echo "Failed to write command to CMD_FILE_2 $CMD_FILE_2"
          exit $RESULT
        fi
        echo "Wrote command to CMD_FILE $CMD_FILE_2"
        bash $CMD_FILE_2
      }
      cmd_wrapper && break
      if [ true != true ]; then
        echo "Retry condition not met, not retrying" >&2
        exit 1
      fi
      n=$[$n+1]
  done

  if [ $n -gt $MAX_RETRY ]; then
    echo "Exceeded maximum retries ($n / $MAX_RETRY), no more attempts Compute base branch" >&2
    exit 1
  fi
