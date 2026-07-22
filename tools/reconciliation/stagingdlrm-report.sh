#!/usr/bin/env bash
# Version 0.1.0 (2026-07-13)
#
# stagingdlrm-report.sh — for every case submitted under a batch, report its
# latest processing status in stagingdlrm (RECEIVED / PROCESSED / DUPLICATE /
# CASE_ALREADY_EXISTS / PROCESSED_FAILED / ERROR), derived from stagingdlrm's
# event_log table (see stagingdlrm-report.sql for the query and its design
# notes).
#
# Writes one CSV file to an `output/` directory relative to the current
# directory (created if it doesn't exist):
#   output/stagingdlrm_report.csv
# If that file already exists from a previous run, it's renamed with a
# timestamp suffix before this run writes its own.
#
# USAGE:
#   ./stagingdlrm-report.sh <batch_id>
#
# source_system is hardcoded to XHIBIT — this reconciliation pipeline is not
# currently used for any other migration source system.
#
# Required env vars:
#   STAGINGDLRM_DB_USER
# Optional env vars:
#   STAGINGDLRM_DB_HOST      (default: localhost)
#   STAGINGDLRM_DB_PORT      (default: 5432)
#   STAGINGDLRM_DB_NAME      (default: stagingdlrmeventstore)
#
# psql prompts for STAGINGDLRM_DB_USER's password interactively — it is never
# read from an env var here.
#
# Requires: psql
#
# Usage Example:
#   export STAGINGDLRM_DB_PORT=5436
#   export STAGINGDLRM_DB_USER=pgreadonly
#   ./stagingdlrm-report.sh test_7_cases_2705

set -euo pipefail

SOURCE="XHIBIT"
BATCH_ID="${1:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/stagingdlrm-report.sql"

OUTPUT_DIR="$(pwd)/output"
OUT_CSV="$OUTPUT_DIR/stagingdlrm_report.csv"

console() {
  echo "$1" >&2
}

# If $1 already exists, renames it in place with a timestamp suffix so this
# run's output never silently clobbers a previous run's.
archive_if_exists() {
  local file="$1"
  if [[ -f "$file" ]]; then
    local backup="${file%.csv}_$(date +%Y%m%d_%H%M%S).csv"
    mv "$file" "$backup"
    console "==> Archived existing $file to $backup"
  fi
}

if [[ -z "$BATCH_ID" ]]; then
  console "Usage: $0 <batch_id>"
  exit 1
fi

if [[ -z "${STAGINGDLRM_DB_USER:-}" ]]; then
  console "Error: STAGINGDLRM_DB_USER must be set"
  exit 1
fi

STAGINGDLRM_DB_HOST="${STAGINGDLRM_DB_HOST:-localhost}"
STAGINGDLRM_DB_PORT="${STAGINGDLRM_DB_PORT:-5432}"
STAGINGDLRM_DB_NAME="${STAGINGDLRM_DB_NAME:-stagingdlrmeventstore}"

if ! command -v psql >/dev/null 2>&1; then
  console "Error: required tool 'psql' not found on PATH"
  exit 1
fi

if [[ ! -f "$SQL_FILE" ]]; then
  console "Error: expected SQL file not found at $SQL_FILE"
  exit 1
fi

console "==> Querying stagingdlrm event_log at $STAGINGDLRM_DB_HOST:$STAGINGDLRM_DB_PORT/$STAGINGDLRM_DB_NAME for $SOURCE/$BATCH_ID/..."

mkdir -p "$OUTPUT_DIR"
archive_if_exists "$OUT_CSV"

if ! psql \
    -h "$STAGINGDLRM_DB_HOST" -p "$STAGINGDLRM_DB_PORT" \
    -U "$STAGINGDLRM_DB_USER" -d "$STAGINGDLRM_DB_NAME" \
    --csv -v "ON_ERROR_STOP=1" \
    -v source_system="$SOURCE" -v batch_id="$BATCH_ID" \
    -f "$SQL_FILE" > "$OUT_CSV"; then
  console "Error: query against stagingdlrm event_log failed — see psql output above"
  rm -f "$OUT_CSV"
  exit 1
fi

ROW_COUNT=$(($(wc -l < "$OUT_CSV" | tr -d ' ') - 1))

if [[ "$ROW_COUNT" -le 0 ]]; then
  console "Warning: no matching submissions found for $SOURCE/$BATCH_ID/ — $OUT_CSV contains only the header row."
  exit 0
fi

console "==> Done. Wrote $ROW_COUNT case row(s) to $OUT_CSV."
