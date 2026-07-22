#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ZIP_NAME="dlrm-reconciliation-scripts.zip"

FILES=(
  env.sh
  function-app-report.sh
  listing-report.sh
  listing-report.sql
  pcfdlrm-report.sh
  pcfdlrm-report.sql
  stagingdlrm-report.sh
  stagingdlrm-report.sql
  summary-report.sh
  summary-report.py
  run-all.sh
)

MISSING=()
for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    MISSING+=("$f")
  fi
done

if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "Error: missing files:" >&2
  printf '  %s\n' "${MISSING[@]}" >&2
  exit 1
fi

rm -f "$ZIP_NAME"
zip -j "$ZIP_NAME" "${FILES[@]}"

echo "Created $SCRIPT_DIR/$ZIP_NAME"