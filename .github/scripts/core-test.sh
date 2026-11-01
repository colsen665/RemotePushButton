#!/usr/bin/env bash
# Runs :core:test. RelayIntegrationTest is known to be flaky (TODO.md), so it alone gets retried:
# a failure in any other test class fails the build straight away.
set -uo pipefail

cd "$(dirname "$0")/../../android"
results=core/build/test-results/test
retries=2

./gradlew :core:test --console=plain && exit 0

other=$(grep -l -E '<(failure|error)[ >]' "$results"/*.xml 2>/dev/null | grep -v 'RelayIntegrationTest' || true)
relay=$(grep -l -E '<(failure|error)[ >]' "$results"/TEST-*RelayIntegrationTest.xml 2>/dev/null || true)
if [ -n "$other" ] || [ -z "$relay" ]; then
    echo "Not a RelayIntegrationTest flake, not retrying." >&2
    exit 1
fi

for attempt in $(seq 1 "$retries"); do
    echo "::warning::RelayIntegrationTest failed, retry $attempt of $retries"
    ./gradlew :core:test --tests '*RelayIntegrationTest' --rerun --console=plain && exit 0
done
exit 1
