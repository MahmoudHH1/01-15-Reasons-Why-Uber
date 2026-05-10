#!/usr/bin/env bash
# Re-fetch the M3 spec from the docsify site. If anything changed:
#   1. Replace the canonical copies under docs/m3/ with the live versions.
#   2. Write a fresh, timestamped, immutable archive directory under
#      docs/m3/archive/<UTC-timestamp>/ containing ALL spec files
#      (full snapshot, not just the changed ones) plus a SHA256 manifest.
# If nothing changed, exit silently and write nothing.
#
# Usage:   ./scripts/snapshot-m3.sh
# Exit:    0 = no change, 1 = fetch failure, 2 = change detected
#
# Override the source with M3_BASE if the site moves.

set -euo pipefail

BASE="${M3_BASE:-https://scalable-docs.netlify.app}"
FILES=(uber-m3.md Uber_Tests_Description.md Grader_Run_Guide.md)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOCS="${ROOT}/docs/m3"
# Format: YYYY-MM-DD-HHMM (UTC). Honors SNAPSHOT_TIMESTAMP if set so the
# workflow can share one timestamp between the archive directory and the
# bot's branch name. Falls back to a fresh stamp for local invocations.
TIMESTAMP="${SNAPSHOT_TIMESTAMP:-$(date -u +%Y-%m-%d-%H%M)}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# Phase 1 — fetch every file into TMP and detect what changed. No writes
# to docs/m3/ yet; we want full atomicity per run.
declare -a changed_files=()
for f in "${FILES[@]}"; do
  if ! curl -fsSL --max-time 30 "${BASE}/${f}" -o "${TMP}/${f}"; then
    echo "FAIL: could not fetch ${BASE}/${f}" >&2
    exit 1
  fi
  if [[ ! -f "${DOCS}/${f}" ]] || ! cmp -s "${TMP}/${f}" "${DOCS}/${f}"; then
    changed_files+=("${f}")
  fi
done

# Phase 2 — no change → no archive, no canonical update, exit 0.
if [[ ${#changed_files[@]} -eq 0 ]]; then
  echo "All ${#FILES[@]} files unchanged. No new snapshot written."
  exit 0
fi

# Phase 3 — change detected. Surface the diff(s), then atomically:
#   - replace every canonical file with the live version
#   - write a complete timestamped archive containing ALL files
SNAP="${DOCS}/archive/${TIMESTAMP}"
mkdir -p "${SNAP}"

echo "Change detected in ${#changed_files[@]}/${#FILES[@]} file(s):"
for f in "${changed_files[@]}"; do
  echo "  CHANGED: ${f}"
  if [[ -f "${DOCS}/${f}" ]]; then
    diff -u "${DOCS}/${f}" "${TMP}/${f}" | sed -n '1,40p' || true
    echo "    (showing first 40 diff lines; run \`git diff docs/m3/${f}\` for full)"
  else
    echo "    (new file)"
  fi
done

for f in "${FILES[@]}"; do
  cp "${TMP}/${f}" "${DOCS}/${f}"
  cp "${TMP}/${f}" "${SNAP}/${f}"
done
( cd "${SNAP}" && shasum -a 256 *.md > SHA256SUMS )

echo
echo "Canonical copies under docs/m3/ updated to live."
echo "Full snapshot of all ${#FILES[@]} files saved to: docs/m3/archive/${TIMESTAMP}/"
echo "Review full diff with:  git diff docs/m3/"
exit 2
