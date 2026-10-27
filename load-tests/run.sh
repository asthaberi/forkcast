#!/usr/bin/env bash
# Runs the load-test series with k6 in Docker against the app on the host, then prints the results table.
# Start the app first without rate limits (all requests come from one IP):
#   RATE_LIMIT_ENABLED=false ./mvnw spring-boot:run
# Then: load-tests/run.sh               (or e.g. load-tests/run.sh browse to run one scenario)
set -euo pipefail
cd "$(dirname "$0")/.."

# Windows (Git Bash) needs a Windows path for the volume mount
dir="$(pwd -W 2>/dev/null || pwd)"
export MSYS_NO_PATHCONV=1

run() {
  local scenario=$1 rate=$2
  echo "== $scenario at $rate/s"
  # Thresholds that fail make k6 exit non-zero; the run's numbers are still wanted
  docker run --rm -v "$dir/load-tests:/scripts" -v "$dir/load-tests/results:/results" \
    -e BASE_URL="${BASE_URL:-http://host.docker.internal:8080}" -e RATE="$rate" -e DURATION="${DURATION:-30s}" \
    grafana/k6:1.3.0 run --quiet "/scripts/$scenario.js" > /dev/null || true
}

only="${1:-all}"
[[ $only == all || $only == browse ]] && for rate in 100 250 500 1000 1500 2000; do run browse $rate; done
[[ $only == all || $only == checkout ]] && for rate in 10 25 50 100 200 300; do run checkout $rate; done
[[ $only == all || $only == recommendations ]] && for rate in 50 100 200 400 800; do run recommendations $rate; done

python load-tests/summarize.py
