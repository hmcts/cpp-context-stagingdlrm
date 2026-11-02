#!/usr/bin/env bash
# Version 0.2.0 (2026-07-14)
#
# pcfdlrm-report.sh — for every case_id in a Script 2 (stagingdlrm-report.sh)
# output CSV, report its latest processing status in pcfdlrm (RECEIVED /
# PROCESSED / REJECTED / NOT_FOUND_IN_AUTOMATION / NOT_RECEIVED_BY_PCFDLRM /
# UNKNOWN), derived from pcfdlrm's event_log table (see pcfdlrm-report.sql for
# the query and its design notes).
#
# Writes one CSV file to an `output/` directory relative to the current
# directory (created if it doesn't exist):
#   output/pcfdlrm_report.csv
# If that file already exists from a previous run, it's renamed with a
# timestamp suffix before this run writes its own.
#
# USAGE:
#   ./pcfdlrm-report.sh [path-to-script2-csv]
# Defaults to ./output/stagingdlrm_report.csv (Script 2's own default output
# location) if no path is given.
#
# Required env vars:
#   PCFDLRM_DB_USER
# Optional env vars:
#   PCFDLRM_DB_HOST      (default: localhost)
#   PCFDLRM_DB_PORT      (default: 5432)
#   PCFDLRM_DB_NAME      (default: pcfdlrmeventstore)
#
# psql prompts for PCFDLRM_DB_USER's password interactively — it is never
# read from an env var here.
#
# Requires: psql
#
# Usage Example:
#   export PCFDLRM_DB_PORT=5437
#   export PCFDLRM_DB_USER=pgreadonly
#   ./pcfdlrm-report.sh   # reads ./output/stagingdlrm_report.csv by default

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$(pwd)/output"
INPUT_CSV="${1:-$OUTPUT_DIR/stagingdlrm_report.csv}"

SQL_FILE="$SCRIPT_DIR/pcfdlrm-report.sql"

OUT_CSV="$OUTPUT_DIR/pcfdlrm_report.csv"

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

if [[ ! -f "$INPUT_CSV" ]]; then
  console "Error: input CSV not found at $INPUT_CSV (pass a path, or run stagingdlrm-report.sh first to create the default)"
  exit 1
fi

if [[ -z "${PCFDLRM_DB_USER:-}" ]]; then
  console "Error: PCFDLRM_DB_USER must be set"
  exit 1
fi

PCFDLRM_DB_HOST="${PCFDLRM_DB_HOST:-localhost}"
PCFDLRM_DB_PORT="${PCFDLRM_DB_PORT:-5432}"
PCFDLRM_DB_NAME="${PCFDLRM_DB_NAME:-pcfdlrmeventstore}"

if ! command -v psql >/dev/null 2>&1; then
  console "Error: required tool 'psql' not found on PATH"
  exit 1
fi

if [[ ! -f "$SQL_FILE" ]]; then
  console "Error: expected SQL file not found at $SQL_FILE"
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

# case_id is column 4 in Script 2's CSV (batch_id,submission_id,case_urn,
# case_id,status,description,...). Plain comma-splitting is safe here even
# though later columns (description) can contain quoted/embedded commas,
# because columns 1-3 are always simple unquoted identifiers — the only
# risk of mis-splitting lives in columns that come after the one we read.
CASE_IDS=$(awk -F',' 'NR>1 && $4 != "" {print $4}' "$INPUT_CSV" | paste -sd, -)

if [[ -z "$CASE_IDS" ]]; then
  console "Warning: no case_id values found in $INPUT_CSV"
  archive_if_exists "$OUT_CSV"
  echo "case_urn,case_id,pcfdlrm_status,description,validation_warning_count,validation_warning_details,defendant_validation_failed_count,defendant_validation_failed_details,hearing_validation_failed_count,material_uploaded_count,latest_event,last_updated" > "$OUT_CSV"
  console "==> Wrote header-only $OUT_CSV."
  exit 0
fi

console "==> Querying pcfdlrm event_log at $PCFDLRM_DB_HOST:$PCFDLRM_DB_PORT/$PCFDLRM_DB_NAME for case_ids from $INPUT_CSV..."

archive_if_exists "$OUT_CSV"

if ! psql \
    -h "$PCFDLRM_DB_HOST" -p "$PCFDLRM_DB_PORT" \
    -U "$PCFDLRM_DB_USER" -d "$PCFDLRM_DB_NAME" \
    --csv -q -v "ON_ERROR_STOP=1" \
    -v case_ids="$CASE_IDS" \
    -f "$SQL_FILE" > "$OUT_CSV"; then
  console "Error: query against pcfdlrm event_log failed — see psql output above"
  rm -f "$OUT_CSV"
  exit 1
fi

ROW_COUNT=$(($(wc -l < "$OUT_CSV" | tr -d ' ') - 1))

if [[ "$ROW_COUNT" -le 0 ]]; then
  console "Warning: query returned no rows — $OUT_CSV contains only the header row."
  exit 0
fi

console "==> Done. Wrote $ROW_COUNT case row(s) to $OUT_CSV."
