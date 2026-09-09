#!/usr/bin/env bash
# Version 0.1.0 (2026-07-10)
#
# function-app-report.sh — list every case submitted to Azure Blob Storage for a
# batch, report its outcome.json status (blank if not yet written), and cross-reference
# each submission against the dlrmlogqueue dead-letter queue.
#
# Writes two CSV files to an `output/` directory relative to the current
# directory (created if it doesn't exist):
#   output/dlrm_storage.csv — the main per-submission report, plus a
#                                 `log_queue` true/false column.
#   output/dlrm_logqueue.csv           — a raw dump of every message peeked
#                                 (non-destructively) from dlrmlogqueue.
# If either file already exists from a previous run, it's renamed with a
# timestamp suffix before this run writes its own.
#
# USAGE:
#   ./function-app-report.sh <batch_id>
#
# source_system is hardcoded to XHIBIT — this reconciliation pipeline is not
# currently used for any other migration source system.
#
# Auth (pick one):
#   DLRM_STORAGE_ACCOUNT           — use your `az login` identity (auth-mode login);
#                                     needs the Storage Blob Data Reader role (or higher)
#                                     on the storage account. Preferred for interactive use.
#   AZURE_STORAGE_CONNECTION_STRING — key-based auth; takes precedence if both are set.
#                                     Used for non-interactive contexts (CI) where nobody
#                                     is logged in via `az login`.
# Required env vars:
#   one of: DLRM_STORAGE_ACCOUNT | AZURE_STORAGE_CONNECTION_STRING
# Optional env vars:
#   DLRM_CONTAINER  (default: dlrmcontainer)
#   DLRM_LOG_QUEUE  (default: dlrmlogqueue)
#
# Requires: az (authenticated), jq, bash 4+ (associative arrays)
#
# Performance: makes 4 `az` CLI calls total regardless of batch size (blob list, queue
# peek, and one `download-batch` each for case.json/outcome.json) — manifest.json/
# case.json/outcome.json existence is resolved in-memory from the initial blob list
# instead of a per-submission `az storage blob exists` call.
#
# Usage Example (non-live dev):
#   export DLRM_STORAGE_ACCOUNT=sadevccm01stagingdlrm
#   export DLRM_CONTAINER=dlrmcontainer
#   export DLRM_LOG_QUEUE=dlrmlogqueue
#   ./function-app-report.sh test_7_cases_2805
#
# Output files (written to ./output/, one run each):

set -euo pipefail

SOURCE="XHIBIT"
BATCH_ID="${1:-}"

DLRM_CONTAINER="${DLRM_CONTAINER:-dlrmcontainer}"
DLRM_LOG_QUEUE="${DLRM_LOG_QUEUE:-dlrmlogqueue}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$(pwd)/output"
STORAGE_CSV="$OUTPUT_DIR/dlrm_storage.csv"
LOGQUEUE_CSV="$OUTPUT_DIR/dlrm_logqueue.csv"

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

# Truncates/creates $STORAGE_CSV and writes its header.
storage_init() {
  : > "$STORAGE_CSV"
  echo "case_urn,batch_id,submission_id,outcome_success,outcome_description,anomaly,log_queue" >> "$STORAGE_CSV"
}

# Appends one quoted row to $STORAGE_CSV.
csv_row() {
  local case_urn="$1" submission_id="$2" outcome_success="$3" outcome_description="$4" anomaly="$5" log_queue="$6"
  echo "$(csv_quote "$case_urn"),$(csv_quote "$BATCH_ID"),$(csv_quote "$submission_id"),$(csv_quote "$outcome_success"),$(csv_quote "$outcome_description"),$(csv_quote "$anomaly"),$(csv_quote "$log_queue")" >> "$STORAGE_CSV"
}

# Truncates/creates $LOGQUEUE_CSV, ready for logqueue_row to append to.
logqueue_init() {
  : > "$LOGQUEUE_CSV"
}

# Appends one quoted row to $LOGQUEUE_CSV.
logqueue_row() {
  local content="$1" insertion_time="$2" dequeue_count="$3"
  echo "$(csv_quote "$content"),$(csv_quote "$insertion_time"),$(csv_quote "$dequeue_count")" >> "$LOGQUEUE_CSV"
}

if [[ -z "$BATCH_ID" ]]; then
  console "Usage: $0 <batch_id>"
  exit 1
fi

for tool in az jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    console "Error: required tool '$tool' not found on PATH"
    exit 1
  fi
done

# Needs bash 4+ for associative arrays (MANIFEST_BLOB/CASE_BLOB/OUTCOME_BLOB below).
# macOS ships bash 3.2 as /bin/bash — if that resolves first on PATH, `declare -A`
# fails with a cryptic "invalid option" error instead of this clear message.
if (( BASH_VERSINFO[0] < 4 )); then
  console "Error: bash 4+ required (found ${BASH_VERSION}). On macOS, install a newer bash via Homebrew ('brew install bash') and ensure it resolves before /bin/bash on PATH."
  exit 1
fi

if [[ -n "${AZURE_STORAGE_CONNECTION_STRING:-}" ]]; then
  AUTH_ARGS=(--connection-string "$AZURE_STORAGE_CONNECTION_STRING")
elif [[ -n "${DLRM_STORAGE_ACCOUNT:-}" ]]; then
  AUTH_ARGS=(--account-name "$DLRM_STORAGE_ACCOUNT" --auth-mode login)
else
  console "Error: set either DLRM_STORAGE_ACCOUNT (use az login identity) or AZURE_STORAGE_CONNECTION_STRING"
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

csv_quote() {
  local value="$1"
  value="${value//\"/\"\"}"
  printf '"%s"' "$value"
}

TMPDIR_RECON=$(mktemp -d)
trap 'rm -rf "$TMPDIR_RECON"' EXIT

ANOMALY_COUNT=0

# Every anomaly passes through here so the final summary count always matches the report.
log_anomaly() {
  ANOMALY_COUNT=$((ANOMALY_COUNT + 1))
  console "Anomaly: $1"
}

# List every case.json/manifest.json blob under the prefix. A real listing failure (auth,
# network, nonexistent container) is fatal; genuinely zero matches is not.
#
# Discovery anchors on EITHER metafile, not just case.json: a submission missing one of
# them must still surface as an anomaly row, not silently disappear from the report.
#
# stderr is captured to a file (not the console) because az/Python print interpreter-level
# warnings on every invocation regardless of success, and `--only-show-errors` doesn't
# suppress those (this pattern repeats below for the same reason).
console "==> Listing blobs under $SOURCE/$BATCH_ID/ in container $DLRM_CONTAINER..."

BLOB_LIST_ERR="$TMPDIR_RECON/blob-list-err"
if ! BLOB_LIST=$(az storage blob list \
  "${AUTH_ARGS[@]}" \
  --container-name "$DLRM_CONTAINER" \
  --prefix "$SOURCE/$BATCH_ID/" \
  --query "[].name" --output tsv --only-show-errors 2>"$BLOB_LIST_ERR"); then
  console "Error: failed to list blobs under prefix $SOURCE/$BATCH_ID/: $(cat "$BLOB_LIST_ERR")"
  exit 1
fi

BLOB_COUNT=$(printf '%s\n' "$BLOB_LIST" | sed '/^$/d' | wc -l | tr -d ' ')
console "==> Found $BLOB_COUNT blob(s); scanning for case.json/manifest.json and checking path structure..."

# Peek dlrmlogqueue (non-destructive) to cross-reference each submission against it. Fatal
# on failure, same tier as blob listing above.
console "==> Peeking up to 32 message(s) from log queue $DLRM_LOG_QUEUE (non-destructive)..."

LOGQ_ERR="$TMPDIR_RECON/logqueue-peek-err"
if ! LOGQ_JSON=$(az storage message peek \
    "${AUTH_ARGS[@]}" \
    --queue-name "$DLRM_LOG_QUEUE" \
    --num-messages 32 \
    --output json --only-show-errors 2>"$LOGQ_ERR"); then
  console "Error: failed to peek log queue $DLRM_LOG_QUEUE: $(cat "$LOGQ_ERR")"
  exit 1
fi

LOGQ_COUNT=$(jq 'length' <<< "$LOGQ_JSON")
console "==> Log queue peek returned $LOGQ_COUNT message(s)."
if [[ "$LOGQ_COUNT" -eq 32 ]]; then
  console "Warning: log queue peek returned exactly 32 messages (the max peek can return) — the queue may hold more; peek always reads from the front, so any additional entries are invisible to this report."
fi

# One jq pass feeds both the raw dump file and the in-memory match set. `content` is
# confirmed; `insertionTime`/`dequeueCount` are best-effort — verify against a live
# `az storage message peek --output json` if they come back blank.
LOGQ_ROWS=$(jq -r '.[] | [(.content // ""), (.insertionTime // ""), (.dequeueCount // 0)] | @tsv' <<< "$LOGQ_JSON")

archive_if_exists "$LOGQUEUE_CSV"
logqueue_init
logqueue_row "content" "insertion_time" "dequeue_count"

LOGQ_PREFIXES=""
while IFS=$'\t' read -r msg_content msg_insertion msg_dequeue; do
  [[ -z "$msg_content" && -z "$msg_insertion" ]] && continue
  logqueue_row "$msg_content" "$msg_insertion" "$msg_dequeue"
  LOGQ_PREFIXES+="$msg_content"$'\n'
done <<< "$LOGQ_ROWS"

if [[ "$LOGQ_COUNT" -gt 0 && -z "${LOGQ_PREFIXES//$'\n'/}" ]]; then
  console "Warning: peeked $LOGQ_COUNT log-queue message(s) but could not extract any content — the '.content' JSON field assumption may not match this az CLI version; the log_queue column is unreliable for this run."
fi

console "==> Wrote $LOGQUEUE_CSV."

# "true"/"false": whether $1 (a submission prefix) appears verbatim among the peeked
# messages. -Fx forces a literal, whole-line match so one prefix can't substring-match
# a different, longer one.
log_queue_flag() {
  if grep -Fxq "$1" <<< "$LOGQ_PREFIXES"; then
    echo "true"
  else
    echo "false"
  fi
}

# awk (not grep) for the metafile filter: a pattern matching zero lines still exits 0,
# so an empty batch doesn't trip `set -e`/`pipefail` the way a zero-match grep would.
METAFILE_PATHS=$(awk -F'/' '$0 ~ /\/(case|manifest)\.json$/ {print}' <<< "$BLOB_LIST")

# One-pass lookup of each submission's manifest.json/case.json/outcome.json blob path,
# keyed by its 4-token prefix. $BLOB_LIST already has the full, authoritative existence
# answer for every one of these files, so this replaces what used to be 3 separate
# `az storage blob exists` network round-trips *per submission* with an O(1) in-memory
# lookup below — no extra az calls needed to re-ask a question already answered here.
#
# Matched by path *suffix* (not by assuming a fixed "$prefix/manifest.json" shape),
# because a submission's metafiles aren't guaranteed to sit directly under its 4-token
# prefix — extra nesting beyond level 4 is tolerated elsewhere in this script (see the
# NF_COUNT>=5 branch below), so the lookup must tolerate it too.
declare -A MANIFEST_BLOB CASE_BLOB OUTCOME_BLOB
while IFS= read -r BLOB_PATH; do
  [[ -z "$BLOB_PATH" ]] && continue
  NF_COUNT=$(awk -F'/' '{print NF}' <<< "$BLOB_PATH")
  (( NF_COUNT < 5 )) && continue
  PFX=$(awk -F'/' '{print $1"/"$2"/"$3"/"$4}' <<< "$BLOB_PATH")
  case "$BLOB_PATH" in
    */manifest.json) MANIFEST_BLOB["$PFX"]="$BLOB_PATH" ;;
    */case.json)      CASE_BLOB["$PFX"]="$BLOB_PATH" ;;
    */outcome.json)   OUTCOME_BLOB["$PFX"]="$BLOB_PATH" ;;
  esac
done <<< "$BLOB_LIST"

# Only initialized after both fatal setup steps above have succeeded, so a setup failure
# leaves no $STORAGE_CSV on disk at all.
archive_if_exists "$STORAGE_CSV"
storage_init
console "==> Writing $STORAGE_CSV..."

VALID_PREFIXES=""
while IFS= read -r BLOB_PATH; do
  [[ -z "$BLOB_PATH" ]] && continue

  NF_COUNT=$(awk -F'/' '{print NF}' <<< "$BLOB_PATH")

  if (( NF_COUNT < 4 )); then
    # Mirrors EventGridTriggerJava.java's own guard (tokens.size() < 4): the real
    # function app rejects this at ingestion and never even enqueues it.
    SUBMISSION_ID_GUESS=$(awk -F'/' '{print $(NF-1)}' <<< "$BLOB_PATH")
    ANOMALY_MSG="invalid path structure: only $((NF_COUNT - 1)) directory level(s) before filename (expected 4) — EventGridTriggerJava rejects this at ingestion (tokens.size() < 4) and never enqueues it (blob: $BLOB_PATH)"
    log_anomaly "$ANOMALY_MSG"
    csv_row "" "$SUBMISSION_ID_GUESS" "" "" "$ANOMALY_MSG" ""
    continue
  fi

  if (( NF_COUNT == 4 )); then
    # Passes EventGridTriggerJava's minimum token count but is one level short, so the
    # filename gets mis-keyed as the submissionId; TimerTriggerJava then finds nothing
    # under that prefix and silently drops it as a missing case/manifest file.
    SUBMISSION_ID_GUESS=$(awk -F'/' '{print $(NF-1)}' <<< "$BLOB_PATH")
    ANOMALY_MSG="invalid path structure: only 3 directory levels before filename (expected 4) — passes the function app's minimum-token check but gets mis-keyed and silently dropped by TimerTriggerJava as a missing case/manifest file (blob: $BLOB_PATH)"
    log_anomaly "$ANOMALY_MSG"
    csv_row "" "$SUBMISSION_ID_GUESS" "" "" "$ANOMALY_MSG" ""
    continue
  fi

  # NF_COUNT >= 5: a normal, processable submission (extra nesting beyond level 4 doesn't
  # matter — EventGridTriggerJava's queue key always comes from tokens[0..3]). case.json
  # and manifest.json share the same 4-token prefix, so `sort -u` below dedupes them.
  PFX=$(awk -F'/' '{print $1"/"$2"/"$3"/"$4}' <<< "$BLOB_PATH")
  VALID_PREFIXES+="$PFX"$'\n'
done <<< "$METAFILE_PATHS"

SUBMISSIONS=$(printf '%s' "$VALID_PREFIXES" | sed '/^$/d' | sort -u)
TOTAL_SUBMISSIONS=$(printf '%s\n' "$SUBMISSIONS" | sed '/^$/d' | wc -l | tr -d ' ')

if [[ -z "$SUBMISSIONS" ]]; then
  if [[ "$ANOMALY_COUNT" -eq 0 ]]; then
    console "Warning: no case.json or manifest.json blobs found under prefix $SOURCE/$BATCH_ID/"
  fi
  exit 0
fi

console "==> Discovered $TOTAL_SUBMISSIONS submission(s) to process ($ANOMALY_COUNT path anomal$([[ $ANOMALY_COUNT -eq 1 ]] && echo y || echo ies) so far)."

# Batch-download every case.json/outcome.json for the whole batch in 2 calls total,
# instead of one `az storage blob download` per submission per file. Downloaded files
# land under $DOWNLOAD_DIR preserving each blob's own container-relative path, so a
# submission's local file is exactly "$DOWNLOAD_DIR/${CASE_BLOB[$prefix]}" — no guessing
# about nesting depth. manifest.json is never downloaded — only its existence (already
# resolved above) is ever needed.
#
# The trailing "/*<name>.json" pattern anchors on the literal "/" right after $BATCH_ID,
# so a batch id that's a prefix of another (e.g. "2026-07" vs "2026-07-17") can't
# cross-match — the character after "2026-07" in the pattern must be "/", which
# "2026-07-17/..." doesn't have at that position.
console "==> Batch-downloading case.json/outcome.json for $TOTAL_SUBMISSIONS submission(s)..."

DOWNLOAD_DIR="$TMPDIR_RECON/downloads"
mkdir -p "$DOWNLOAD_DIR"

CASE_DL_ERR="$TMPDIR_RECON/case-downloadbatch-err"
if ! az storage blob download-batch \
    "${AUTH_ARGS[@]}" \
    --source "$DLRM_CONTAINER" \
    --destination "$DOWNLOAD_DIR" \
    --pattern "$SOURCE/$BATCH_ID/*case.json" \
    --no-progress --only-show-errors >/dev/null 2>"$CASE_DL_ERR"; then
  console "Error: failed to batch-download case.json blobs under $SOURCE/$BATCH_ID/: $(cat "$CASE_DL_ERR")"
  exit 1
fi

OUTCOME_DL_ERR="$TMPDIR_RECON/outcome-downloadbatch-err"
if ! az storage blob download-batch \
    "${AUTH_ARGS[@]}" \
    --source "$DLRM_CONTAINER" \
    --destination "$DOWNLOAD_DIR" \
    --pattern "$SOURCE/$BATCH_ID/*outcome.json" \
    --no-progress --only-show-errors >/dev/null 2>"$OUTCOME_DL_ERR"; then
  console "Error: failed to batch-download outcome.json blobs under $SOURCE/$BATCH_ID/: $(cat "$OUTCOME_DL_ERR")"
  exit 1
fi

console "==> Batch download complete."

# Processes one submission prefix and always prints exactly one CSV row for it.
# Calling this as the condition of `if` disables `set -e` for its whole body (bash's
# documented behaviour for functions invoked in an if/while/&&/||/! context), so a
# failing az/jq call inside becomes a reported anomaly instead of an aborted script.
process_submission() {
  local prefix="$1"
  local submission_id case_urn="" outcome_success="" outcome_description="" anomalies="" jq_err

  submission_id=$(cut -d'/' -f4 <<< "$prefix")

  # manifest.json is checked first: TimerTriggerJava requires BOTH case.json and
  # manifest.json — if manifest.json is confirmed missing, the case is a dead end, so
  # emit the row and skip the case.json/outcome.json checks. Existence is an O(1) lookup
  # against MANIFEST_BLOB (built once from $BLOB_LIST above) — no az call here at all.
  if [[ -z "${MANIFEST_BLOB[$prefix]:-}" ]]; then
    anomalies="manifest.json missing — function app requires both case.json and manifest.json; submission was deleted from the queue and never forwarded to stagingdlrm"
    log_anomaly "manifest.json missing [submission: $submission_id, prefix: $prefix]"
    csv_row "" "$submission_id" "" "" "$anomalies" "$(log_queue_flag "$prefix")"
    return 0
  fi

  if [[ -z "${CASE_BLOB[$prefix]:-}" ]]; then
    anomalies="${anomalies:+$anomalies; }case.json missing — function app requires both case.json and manifest.json; submission was deleted from the queue and never forwarded to stagingdlrm (case URN cannot be determined)"
    log_anomaly "case.json missing [submission: $submission_id, prefix: $prefix]"
  else
    local case_json_file="$DOWNLOAD_DIR/${CASE_BLOB[$prefix]}"
    if [[ ! -f "$case_json_file" ]]; then
      # $BLOB_LIST said this blob exists, but it's missing locally after the batch
      # download — a transient per-blob gap inside download-batch, same tier as the old
      # per-submission download failure.
      anomalies="${anomalies:+$anomalies; }case.json download failed: file listed in storage but missing after batch download"
      log_anomaly "case.json download failed [submission: $submission_id]: file listed in storage but missing after batch download"
    elif ! jq_err=$(jq empty "$case_json_file" 2>&1); then
      anomalies="${anomalies:+$anomalies; }case.json is not valid JSON: ${jq_err:-parse error}"
      log_anomaly "case.json is not valid JSON [submission: $submission_id]: ${jq_err:-parse error}"
    else
      case_urn=$(jq -r '.migratedCase.caseDetails.prosecutorCaseReference // ""' "$case_json_file")
      if [[ -z "$case_urn" ]]; then
        anomalies="${anomalies:+$anomalies; }case.json missing migratedCase.caseDetails.prosecutorCaseReference"
        log_anomaly "case.json missing prosecutorCaseReference [submission: $submission_id]"
      fi
    fi
  fi

  if [[ -n "${OUTCOME_BLOB[$prefix]:-}" ]]; then
    local outcome_json_file="$DOWNLOAD_DIR/${OUTCOME_BLOB[$prefix]}"
    if [[ ! -f "$outcome_json_file" ]]; then
      anomalies="${anomalies:+$anomalies; }outcome.json download failed: file listed in storage but missing after batch download"
      log_anomaly "outcome.json download failed [submission: $submission_id]: file listed in storage but missing after batch download"
    elif ! jq_err=$(jq empty "$outcome_json_file" 2>&1); then
      anomalies="${anomalies:+$anomalies; }outcome.json is not valid JSON: ${jq_err:-parse error}"
      log_anomaly "outcome.json is not valid JSON [submission: $submission_id]: ${jq_err:-parse error}"
    else
      # NOT `.success // ""`: jq's `//` substitutes on `false` as well as `null`/missing,
      # which would collapse a genuine, valid "success": false outcome to "" and make the
      # missing-field check below misfire on real failure outcomes. Only null (which is
      # also what jq returns for a truly absent key) should fall back to "".
      outcome_success=$(jq -r 'if .success == null then "" else (.success | tostring) end' "$outcome_json_file")
      outcome_description=$(jq -r '.description // ""' "$outcome_json_file")
      if [[ -z "$outcome_success" ]]; then
        anomalies="${anomalies:+$anomalies; }outcome.json missing success field"
        log_anomaly "outcome.json missing success field [submission: $submission_id]"
      fi
    fi
  fi

  csv_row "$case_urn" "$submission_id" "$outcome_success" "$outcome_description" "$anomalies" "$(log_queue_flag "$prefix")"
}

SUBMISSION_INDEX=0
while IFS= read -r PREFIX; do
  [[ -z "$PREFIX" ]] && continue
  SUBMISSION_INDEX=$((SUBMISSION_INDEX + 1))
  SUBMISSION_ID_FOR_LOG=$(cut -d'/' -f4 <<< "$PREFIX")
  console "[$SUBMISSION_INDEX/$TOTAL_SUBMISSIONS] $SUBMISSION_ID_FOR_LOG"
  if ! process_submission "$PREFIX"; then
    # Belt-and-suspenders: even an unexpected failure inside process_submission still
    # yields one row, so the report's row count always matches the submission count.
    log_anomaly "unexpected failure while processing submission [prefix: $PREFIX]"
    csv_row "" "$SUBMISSION_ID_FOR_LOG" "" "" "unexpected failure while processing this submission — see stderr" "$(log_queue_flag "$PREFIX")"
  fi
done <<< "$SUBMISSIONS"

console "==> Done. Processed $TOTAL_SUBMISSIONS submission(s)."

if [[ "$ANOMALY_COUNT" -gt 0 ]]; then
  if [[ "$ANOMALY_COUNT" -eq 1 ]]; then
    console "Completed with 1 anomaly — see the row(s) with a non-empty anomaly column and stderr above."
  else
    console "Completed with $ANOMALY_COUNT anomalies — see the row(s) with a non-empty anomaly column and stderr above."
  fi
else
  console "No anomalies detected."
fi

console "Output files:"
console "  $STORAGE_CSV"
console "  $LOGQUEUE_CSV"
