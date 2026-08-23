#!/usr/bin/env bash
set -euo pipefail

squeak_port="${SQUEAK_PORT:-8421}"
runtime_port="${TRELLIS_RUNTIME_PORT:-8422}"

sbt --error "runMain trellis.squeak.SqueakServer ${runtime_port}" &
runtime_pid=$!
trap 'kill "$runtime_pid" 2>/dev/null || true' EXIT INT TERM

cargo run --manifest-path rust/delta-web/Cargo.toml -- \
  --runtime-port "$runtime_port" \
  --port "$squeak_port"
