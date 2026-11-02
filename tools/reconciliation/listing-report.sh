#!/usr/bin/env bash
# Version 0.1.0 (2026-07-15)
#
# listing-report.sh — for every case_id in a Script 2 (stagingdlrm-report.sh)
# output CSV, report the hearings recorded for that case in Listing's
# hearing/listed_cases tables (see listing-report.sql for the query and its
# design notes — query logic supplied by the Listing team, adapted here to
# this pipeline's input/output conventions).
#
# Writes one CSV file to an `output/` directory relative to the current
# directory (created if it doesn't exist):
#   output/listing_report.csv
# If that file already exists from a previous run, it's renamed with a
# timestamp suffix before this run writes its own.
#
# USAGE:
#   ./listing-report.sh [path-to-script2-csv]
# Defaults to ./output/stagingdlrm_report.csv (Script 2's own default output
# location) if no path is given.
#
# Required env vars:
#   LISTING_DB_USER
# Optional env vars:
#   LISTING_DB_HOST      (default: localhost)
#   LISTING_DB_PORT      (default: 5432)
#   LISTING_DB_NAME      (default: listingviewstore)
#
# psql prompts for LISTING_DB_USER's password interactively — it is never
# read from an env var here.
#
# Requires: psql
#
# Usage Example:
#   export LISTING_DB_PORT=5438
#   export LISTING_DB_USER=pgreadonly
#   ./listing-report.sh   # reads ./output/stagingdlrm_report.csv by default

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$(pwd)/output"
INPUT_CSV="${1:-$OUTPUT_DIR/stagingdlrm_report.csv}"

SQL_FILE="$SCRIPT_DIR/listing-report.sql"

OUT_CSV="$OUTPUT_DIR/listing_report.csv"

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

if [[ -z "${LISTING_DB_USER:-}" ]]; then
  console "Error: LISTING_DB_USER must be set"
  exit 1
fi

LISTING_DB_HOST="${LISTING_DB_HOST:-localhost}"
LISTING_DB_PORT="${LISTING_DB_PORT:-5432}"
LISTING_DB_NAME="${LISTING_DB_NAME:-listingviewstore}"

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
  echo "case_id,case_reference,hearing_count,hearings" > "$OUT_CSV"
  console "==> Wrote header-only $OUT_CSV."
  exit 0
fi

console "==> Querying Listing hearing/listed_cases at $LISTING_DB_HOST:$LISTING_DB_PORT/$LISTING_DB_NAME for case_ids from $INPUT_CSV..."

archive_if_exists "$OUT_CSV"

if ! psql \
    -h "$LISTING_DB_HOST" -p "$LISTING_DB_PORT" \
    -U "$LISTING_DB_USER" -d "$LISTING_DB_NAME" \
    --csv -q -v "ON_ERROR_STOP=1" \
    -v case_ids="$CASE_IDS" \
    -f "$SQL_FILE" > "$OUT_CSV"; then
  console "Error: query against Listing failed — see psql output above"
  rm -f "$OUT_CSV"
  exit 1
fi

ROW_COUNT=$(($(wc -l < "$OUT_CSV" | tr -d ' ') - 1))

if [[ "$ROW_COUNT" -le 0 ]]; then
  console "Warning: query returned no rows — $OUT_CSV contains only the header row."
  exit 0
fi

console "==> Done. Wrote $ROW_COUNT case row(s) to $OUT_CSV."
