#!/usr/bin/env bash
set -euo pipefail

studio_port="${STUDIO_PORT:-8421}"
runtime_port="${TRELLIS_RUNTIME_PORT:-8422}"

sbt --error "runMain trellis.studio.StudioExecutionServer ${runtime_port}" &
runtime_pid=$!
trap 'kill "$runtime_pid" 2>/dev/null || true' EXIT INT TERM

cargo run --manifest-path rust/delta-web/Cargo.toml -- \
  --resources src/main/resources/trellis \
  --port "$studio_port"
