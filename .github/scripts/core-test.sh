#!/usr/bin/env bash
# Runs :core:test. Any failing test fails the build: there are no retries.
set -euo pipefail

cd "$(dirname "$0")/../../android"
./gradlew :core:test --console=plain
