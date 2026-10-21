#!/usr/bin/env bash
# Checks that MongoDB can start on this machine, and if it can't, says why in a few seconds.
#
# Without this, a MongoDB that refuses to start (e.g. MongoDB 8.0.34 on Linux kernel 6.19+, which GitHub's
# runners moved to) shows up as every integration test class timing out after 60 s, with the real reason
# buried in the log. This prints the kernel and MongoDB's own fatal message as a GitHub error annotation.
#
# Usage: check-mongo-starts.sh [image] [extra docker run options...]
# The image defaults to the one the integration tests use (TestContainers.MONGO_IMAGE).
set -euo pipefail

image="${1:-$(grep -oE 'MONGO_IMAGE = "[^"]+"' src/test/java/com/asthaberi/forkcast/support/TestContainers.java | cut -d'"' -f2)}"
shift || true
name="mongo-start-check-$$"
trap 'docker rm -f "$name" > /dev/null 2>&1 || true' EXIT

echo "Kernel: $(uname -r)  Image: $image"
docker run -d --name "$name" "$@" "$image" mongod --replSet rs0 > /dev/null

for _ in $(seq 1 60); do
  logs="$(docker logs "$name" 2>&1 || true)"
  if grep -q 'Waiting for connections' <<< "$logs"; then
    echo "MongoDB starts on this runner."
    exit 0
  fi
  fatal="$(grep -m1 '"s":"F"' <<< "$logs" || true)"
  if [ -n "$fatal" ]; then
    message="$(sed -E 's/.*"msg":"([^"]*)".*/\1/' <<< "$fatal")"
    echo "::error title=MongoDB cannot start on this runner (kernel $(uname -r))::$message"
    exit 1
  fi
  sleep 1
done
echo "::error title=MongoDB did not start within 60 s::$(tail -n 5 <<< "$logs")"
exit 1
