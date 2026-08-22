#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_MANIFEST="$ROOT/rust/trellis-verify/Cargo.toml"
FOUNDATIONS="$ROOT/src/main/resources/trellis/foundations"

for cmd in sbt cargo; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "required tool is missing: $cmd" >&2
    exit 1
  fi
done

for n in $(seq 1 11); do
  test -f "$FOUNDATIONS/F$n.delta" || {
    echo "missing frozen foundation delta: $FOUNDATIONS/F$n.delta" >&2
    exit 1
  }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

printf '%s\n' '[parity] Rust unit and adversarial tests'
cargo test --quiet --manifest-path "$RUST_MANIFEST"

printf '%s\n' '[parity] Rust clean-room reconstruction'
cargo run --quiet --manifest-path "$RUST_MANIFEST" -- verify --resources "$FOUNDATIONS"

printf '%s\n' '[parity] canonical closure-report bytes'
sbt --error "run closure" \
  | grep '^14:closure-report' \
  | tail -n 1 \
  > "$TMP/scala-closure.txt"

cargo run --quiet --manifest-path "$RUST_MANIFEST" -- closure --resources "$FOUNDATIONS" \
  > "$TMP/rust-closure.txt"

if ! cmp -s "$TMP/scala-closure.txt" "$TMP/rust-closure.txt"; then
  echo 'Scala and Rust closure-report bytes differ' >&2
  diff -u "$TMP/scala-closure.txt" "$TMP/rust-closure.txt" || true
  exit 1
fi

printf '%s\n' '[parity] frozen delta ids and F0-F11 roots'
sbt --error run \
  | grep -E '^F([0-9]+) (delta|root):' \
  | sed -E 's/[[:space:]]+//g' \
  > "$TMP/scala-roots.txt"

cargo run --quiet --manifest-path "$RUST_MANIFEST" -- roots --resources "$FOUNDATIONS" \
  | grep -E '^F([0-9]+) (delta|root):' \
  | sed -E 's/[[:space:]]+//g' \
  > "$TMP/rust-roots.txt"

if ! cmp -s "$TMP/scala-roots.txt" "$TMP/rust-roots.txt"; then
  echo 'Scala and Rust frozen identities differ' >&2
  diff -u "$TMP/scala-roots.txt" "$TMP/rust-roots.txt" || true
  exit 1
fi

printf '%s\n' '[ok] Scala and Rust are byte-identical on bootstrap closure and frozen identities'
