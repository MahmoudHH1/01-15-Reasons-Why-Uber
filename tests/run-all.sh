#!/usr/bin/env bash
# Master orchestrator. Runs every test script in numerical order, captures
# per-script PASS/FAIL totals, and prints a final summary table.
#
# Usage:
#   ./tests/run-all.sh                  # run everything
#   ./tests/run-all.sh user driver      # run only matching service scripts
#   ./tests/run-all.sh cc               # run only the cross-cutting (CC) scripts
#
# Failure exit code = sum of FAILs across all scripts.

set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
FILTER="${*:-}"

scripts=()
for f in \
  "$DIR/00-health.sh" \
  "$DIR/01-cc-jwt.sh" \
  "$DIR/02-cc-cache.sh" \
  "$DIR/03-cc-design-patterns.sh" \
  "$DIR/04-cc-docker-yaml.sh" \
  "$DIR/10-user-service.sh" \
  "$DIR/20-driver-service.sh" \
  "$DIR/30-ride-service.sh" \
  "$DIR/40-location-service.sh" \
  "$DIR/50-payment-service.sh"
do
  if [ -n "$FILTER" ]; then
    keep=0
    for kw in $FILTER; do
      [[ "$f" == *"$kw"* ]] && keep=1 && break
    done
    [ "$keep" = "1" ] && scripts+=("$f")
  else
    scripts+=("$f")
  fi
done

total_pass=0
total_fail=0
total_skip=0

declare -A SCRIPT_RESULT

for s in "${scripts[@]}"; do
  echo
  echo "##################################################################"
  echo "# RUN  $(basename "$s")"
  echo "##################################################################"

  out_file="$(mktemp)"
  bash "$s" 2>&1 | tee "$out_file"
  # Extract TOTALS line (last)
  totals_line="$(grep -E '^TOTALS:' "$out_file" | tail -1)"
  rm -f "$out_file"

  if [ -n "$totals_line" ]; then
    # Format: TOTALS: N PASS / N FAIL / N SKIP
    p="$(echo "$totals_line" | sed -nE 's/.*: ([0-9]+) PASS.*/\1/p')"
    f="$(echo "$totals_line" | sed -nE 's/.*\/ ([0-9]+) FAIL.*/\1/p')"
    k="$(echo "$totals_line" | sed -nE 's/.*\/ ([0-9]+) SKIP.*/\1/p')"
    SCRIPT_RESULT["$(basename "$s")"]="${p:-?} PASS / ${f:-?} FAIL / ${k:-?} SKIP"
    total_pass=$((total_pass + ${p:-0}))
    total_fail=$((total_fail + ${f:-0}))
    total_skip=$((total_skip + ${k:-0}))
  else
    SCRIPT_RESULT["$(basename "$s")"]="(no TOTALS line — script crashed?)"
  fi
done

echo
echo "=================================================================="
echo "# OVERALL SUMMARY"
echo "=================================================================="
for s in "${scripts[@]}"; do
  printf "  %-32s  %s\n" "$(basename "$s")" "${SCRIPT_RESULT[$(basename "$s")]:-?}"
done
echo
echo "  GRAND TOTAL:  $total_pass PASS / $total_fail FAIL / $total_skip SKIP"
echo "=================================================================="
exit $total_fail
