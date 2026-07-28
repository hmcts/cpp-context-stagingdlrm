#!/usr/bin/env bash
# Version 0.1.0 (2026-07-16)
#
# run-all.sh — runs the full DLRM reconciliation pipeline for a batch in
# sequence: function-app-report.sh -> stagingdlrm-report.sh ->
# pcfdlrm-report.sh -> listing-report.sh -> summary-report.sh. Stops
# immediately (failfast) if any step exits non-zero — a step that legitimately
# finds zero rows still exits 0 (see that script's own header comment), so a
# non-zero exit here always means a real, unexpected failure.
#
# All five scripts write their CSVs to an `output/` directory relative to the
# current directory (created automatically) — see each script's own header
# comment for its exact output filename.
#
# USAGE:
#   ./run-all.sh <batch_id> [--archive=<tag>] [--report-type=technical|business|dlrm]
#
# --archive=<tag> (optional): before running the pipeline, renames every
# pre-existing output CSV (dlrm_storage.csv, dlrm_logqueue.csv,
# stagingdlrm_report.csv, pcfdlrm_report.csv, listing_report.csv,
# summary_report.csv, summary_report_business.csv, summary_report_dlrm.csv —
# the individual scripts' own fixed output filenames) from output/<name>.csv
# to output/<tag>_<name>.csv, if present. This clears the way for the fresh
# run's own output rather than letting each individual script's own
# timestamp-suffix archive-on-exists logic kick in, giving the previous run's
# files a name you chose instead. A file that doesn't exist yet (e.g. first
# run, or a report type that wasn't generated last time) is silently
# skipped. batch_id, --archive=<tag>, and --report-type= can be given in any
# order.
#
# --report-type=technical|business|dlrm (optional, default: technical):
# forwarded as-is to summary-report.sh for step 5/5 — see summary-report.py's
# own header comment for exactly what each type produces. Omitting it
# preserves today's behaviour exactly (technical report, output/summary_report.csv).
#
# source_system is hardcoded to XHIBIT throughout the pipeline (same
# convention as every individual script) — this pipeline is not currently
# used for any other migration source system.
#
# Required env vars (must already be set before running this script):
#   One of:
#     DLRM_STORAGE_ACCOUNT            — use your `az login` identity (needs
#                                        Storage Blob Data Reader role or
#                                        higher). Preferred for interactive use.
#     AZURE_STORAGE_CONNECTION_STRING — key-based auth; takes precedence if
#                                        both are set. For non-interactive
#                                        (CI) contexts.
#   STAGINGDLRM_DB_USER   — stagingdlrm Postgres user (read-only)
#   PCFDLRM_DB_USER        — pcfdlrm Postgres user (read-only)
#   LISTING_DB_USER        — Listing Postgres user (read-only)
#
# Optional env vars (sensible defaults if unset):
#   DLRM_CONTAINER          (default: dlrmcontainer)
#   DLRM_LOG_QUEUE          (default: dlrmlogqueue)
#   STAGINGDLRM_DB_HOST     (default: localhost)
#   STAGINGDLRM_DB_PORT     (default: 5432)
#   STAGINGDLRM_DB_NAME     (default: stagingdlrmeventstore)
#   PCFDLRM_DB_HOST         (default: localhost)
#   PCFDLRM_DB_PORT         (default: 5432)
#   PCFDLRM_DB_NAME         (default: pcfdlrmeventstore)
#   LISTING_DB_HOST         (default: localhost)
#   LISTING_DB_PORT         (default: 5432)
#   LISTING_DB_NAME         (default: listingviewstore)
#
# Note: stagingdlrm-report.sh, pcfdlrm-report.sh, and listing-report.sh each
# invoke `psql`, which prompts interactively for its DB user's password — this
# script will pause at each of those three steps waiting for a password to be
# typed. It is not hung.
#
# Requires: az (authenticated), jq, psql, python3 — see the individual
# scripts' own header comments for details.
#
# Usage Example:
#   export DLRM_STORAGE_ACCOUNT=sadevccm01stagingdlrm
#   export STAGINGDLRM_DB_PORT=5436
#   export STAGINGDLRM_DB_USER=pgreadonly
#   export PCFDLRM_DB_PORT=5437
#   export PCFDLRM_DB_USER=pgreadonly
#   export LISTING_DB_PORT=5438
#   export LISTING_DB_USER=pgreadonly
#   ./run-all.sh test_7_cases_2705
#   ./run-all.sh test_7_cases_2705 --archive=testrun
#   ./run-all.sh test_7_cases_2705 --report-type=business
#   ./run-all.sh test_7_cases_2705 --archive=testrun --report-type=dlrm

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$(pwd)/output"

# Fixed output filenames each individual script writes, relative to
# $OUTPUT_DIR — kept in sync with those scripts' own OUT_CSV/STORAGE_CSV/
# LOGQUEUE_CSV variables.
OUTPUT_FILENAMES=(
  dlrm_storage.csv
  dlrm_logqueue.csv
  stagingdlrm_report.csv
  pcfdlrm_report.csv
  listing_report.csv
  summary_report.csv
  summary_report_business.csv
  summary_report_dlrm.csv
)

BATCH_ID=""
ARCHIVE_TAG=""
REPORT_TYPE=""

for arg in "$@"; do
  case "$arg" in
    --archive=*)
      ARCHIVE_TAG="${arg#--archive=}"
      ;;
    --report-type=*)
      REPORT_TYPE="${arg#--report-type=}"
      ;;
    *)
      if [[ -z "$BATCH_ID" ]]; then
        BATCH_ID="$arg"
      fi
      ;;
  esac
done

console() {
  echo "$1" >&2
}

if [[ -z "$BATCH_ID" ]]; then
  console "Usage: $0 <batch_id> [--archive=<tag>] [--report-type=technical|business|dlrm]"
  exit 1
fi

if [[ -n "$ARCHIVE_TAG" ]]; then
  console "==> Archiving existing output/ CSVs with tag '$ARCHIVE_TAG'..."
  for filename in "${OUTPUT_FILENAMES[@]}"; do
    existing="$OUTPUT_DIR/$filename"
    if [[ -f "$existing" ]]; then
      archived="$OUTPUT_DIR/${ARCHIVE_TAG}_${filename}"
      mv "$existing" "$archived"
      console "    $existing -> $archived"
    fi
  done
fi

# Runs one pipeline step, tagging a failure with the step's own name so a
# failure deep in the sequence is unambiguous about which stage broke.
run_step() {
  local name="$1"
  shift
  console ""
  console "==> [$name] Starting..."
  if ! "$@"; then
    console "==> [$name] FAILED — stopping pipeline (failfast). See output above for details."
    exit 1
  fi
  console "==> [$name] Done."
}

run_step "1/5 function-app-report" "$SCRIPT_DIR/function-app-report.sh" "$BATCH_ID"
run_step "2/5 stagingdlrm-report" "$SCRIPT_DIR/stagingdlrm-report.sh" "$BATCH_ID"
run_step "3/5 pcfdlrm-report" "$SCRIPT_DIR/pcfdlrm-report.sh"
run_step "4/5 listing-report" "$SCRIPT_DIR/listing-report.sh"
run_step "5/5 summary-report" "$SCRIPT_DIR/summary-report.sh" ${REPORT_TYPE:+--report-type="$REPORT_TYPE"}

case "$REPORT_TYPE" in
  business) SUMMARY_FILE="summary_report_business.csv" ;;
  dlrm) SUMMARY_FILE="summary_report_dlrm.csv" ;;
  *) SUMMARY_FILE="summary_report.csv" ;;
esac

console ""
console "==> Pipeline complete for batch $BATCH_ID. See ./output/$SUMMARY_FILE."
