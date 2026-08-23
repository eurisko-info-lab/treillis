#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "$0")/.." && pwd)
source_dir="$repo_dir/src/main/resources/trellis/products/source"
compiled_dir="${TRELLIS_GENERATED_DIR:-$repo_dir/target/generated-deltas}"
check_arg="${1:-}"

cd "$repo_dir"
sbt "runMain trellis.Main compile-product-sources $source_dir $compiled_dir $check_arg"
