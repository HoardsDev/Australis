#!/usr/bin/env bash
#
# Australis self-attack regression lab.
# Runs a matrix of floodtest scenarios against a target you OWN and summarizes
# the results into a timestamped report, so you can re-run it after changes and
# compare (regression testing). Uses only floodtest — no external deps.
#
#   ./lab.sh <host:port> --i-own-this-target [out-dir]
#
# Override the scenario matrix (mode c rate duration, one per line):
#   LAB_SCENARIOS=$'ping 50 0 5s\njoin 100 0 5s' ./lab.sh play.example.net:25565 --i-own-this-target
#
# ⚠️ Only ever run this against infrastructure you own or are authorized to test.
set -euo pipefail

TARGET="${1:-}"
OWN="${2:-}"
OUT="${3:-lab-$(date +%Y%m%d-%H%M%S)}"

if [ -z "$TARGET" ] || [ "$OWN" != "--i-own-this-target" ]; then
    echo "usage: ./lab.sh <host:port> --i-own-this-target [out-dir]" >&2
    echo "  (the --i-own-this-target gate is deliberate: only test infra you own)" >&2
    exit 2
fi

# Locate the floodtest binary (built by: go build ./cmd/floodtest).
FT="${FLOODTEST:-}"
if [ -z "$FT" ]; then
    for c in ./floodtest ./floodtest.exe ./cmd/floodtest/floodtest; do
        [ -x "$c" ] && FT="$c" && break
    done
fi
[ -n "$FT" ] && [ -x "$FT" ] || { echo "floodtest binary not found — run: go build -o floodtest ./cmd/floodtest" >&2; exit 2; }

# Default scenario matrix: "mode concurrency rate duration".
DEFAULT_SCENARIOS=$'ping 100 0 10s\njoin 200 0 10s\nconn 200 0 10s'
SCENARIOS="${LAB_SCENARIOS:-$DEFAULT_SCENARIOS}"

mkdir -p "$OUT"
SUMMARY="$OUT/summary.md"
{
    echo "# Australis lab — $TARGET — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    echo "| scenario | attempts/s | rejected | engaged | reset | p99 latency |"
    echo "|---|---|---|---|---|---|"
} > "$SUMMARY"

echo "Australis self-attack lab -> $TARGET  (reports: $OUT/)"
while IFS= read -r line; do
    [ -z "$line" ] && continue
    # shellcheck disable=SC2086
    set -- $line
    mode="$1"; c="$2"; rate="$3"; dur="$4"
    echo ">> $mode  c=$c rate=$rate duration=$dur"
    rpt="$OUT/$mode.txt"
    "$FT" -mode "$mode" -target "$TARGET" -c "$c" -rate "$rate" -duration "$dur" -i-own-this-target > "$rpt" 2>&1 || true

    aps=$(grep -oE 'attempts:[[:space:]]+[0-9]+[[:space:]]+\([0-9]+/s\)' "$rpt" | grep -oE '[0-9]+/s' | head -1)
    rej=$(grep -oE 'rejected:[[:space:]]+[0-9]+' "$rpt" | grep -oE '[0-9]+' | tail -1)
    eng=$(grep -oE 'engaged:[[:space:]]+[0-9]+' "$rpt" | grep -oE '[0-9]+' | tail -1)
    rst=$(grep -oE 'reset:[[:space:]]+[0-9]+' "$rpt" | grep -oE '[0-9]+' | tail -1)
    p99=$(grep -oE 'p99=[^ ]+' "$rpt" | head -1 | sed 's/p99=//')
    printf '| %s | %s | %s | %s | %s | %s |\n' \
        "$mode" "${aps:-?}" "${rej:-?}" "${eng:-?}" "${rst:-?}" "${p99:-?}" >> "$SUMMARY"
done <<< "$SCENARIOS"

echo
echo "==== summary ===="
cat "$SUMMARY"
echo
echo "Full per-scenario reports in: $OUT/"
echo "Tip: run once with Australis off (baseline) and once on, then diff the summaries."
