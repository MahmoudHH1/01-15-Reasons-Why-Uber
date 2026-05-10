#!/usr/bin/env bash
# Re-fetch the M3 spec from the docsify site, diff against the committed
# canonical copy, and (if changed) save a dated immutable snapshot.
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
DATE="$(date -u +%Y-%m-%d)"
SNAP="${DOCS}/archive/${DATE}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

mkdir -p "${SNAP}"
changed=0
fetched=0

for f in "${FILES[@]}"; do
  if ! curl -fsSL --max-time 30 "${BASE}/${f}" -o "${TMP}/${f}"; then
    echo "FAIL: could not fetch ${BASE}/${f}" >&2
    exit 1
  fi
  fetched=$((fetched + 1))

  if [[ ! -f "${DOCS}/${f}" ]] || ! cmp -s "${TMP}/${f}" "${DOCS}/${f}"; then
    echo "CHANGED: ${f}"
    if [[ -f "${DOCS}/${f}" ]]; then
      diff -u "${DOCS}/${f}" "${TMP}/${f}" | sed -n '1,40p' || true
      echo "  (showing first 40 diff lines; run \`git diff docs/m3/${f}\` for full)"
    else
      echo "  (new file)"
    fi
    cp "${TMP}/${f}" "${DOCS}/${f}"
    cp "${TMP}/${f}" "${SNAP}/${f}"
    changed=$((changed + 1))
  else
    echo "unchanged: ${f}"
  fi
done

if [[ ${changed} -gt 0 ]]; then
  ( cd "${SNAP}" && shasum -a 256 *.md > SHA256SUMS )
  echo
  echo "${changed}/${fetched} file(s) changed. Snapshot saved to: docs/m3/archive/${DATE}/"
  echo "Review full diff with:  git diff docs/m3/"
  exit 2
fi

# Nothing changed — clean up the empty dated dir we created.
rmdir "${SNAP}" 2>/dev/null || true
echo
echo "All ${fetched} files unchanged. No new snapshot written."
exit 0
