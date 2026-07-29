# DLRM Reconciliation Tooling

Standalone scripts (not part of the Maven build) that reconcile a migration batch across
every hop it passes through before a caseworker can see it:

```
Azure Blob Storage → Azure Function App → stagingdlrm → pcfdlrm → Listing
```

Given a `batch_id`, the pipeline produces one CSV per hop plus a combined summary CSV with
an `overall_status` per case, so a migration pilot batch can be signed off (or its failures
triaged) without manually cross-referencing four different systems by hand.

## Key design facts

Worth knowing before changing or extending any of this:

- **Neither stagingdlrm nor pcfdlrm has a real view store.** Both contexts' Liquibase
  changelogs are empty and their query APIs have no endpoints, despite the module
  scaffolding existing. Status is derived by querying each context's own CQRS **`event_log`**
  table directly (`stream_id, position_in_stream, name, payload, metadata`, `payload`/
  `metadata` stored as `text` — cast to `::jsonb` to extract fields). Listing is the one
  real view store in this chain (`listed_cases` table, live query API).
- **No domain event carries an explicit `batchId`.** Batch is only ever embedded as a path
  segment inside `azureLocation` (e.g. `XHIBIT/Batch0001/28DI10000175/<submissionId>`).
  Batch filtering against stagingdlrm/pcfdlrm's `event_log` is done via
  `azureLocation LIKE '{source}/{batch_id}/%'`.
- **Join keys differ per hop:** stagingdlrm's `event_log.stream_id` *is* the `submissionId`
  (no separate lookup needed). pcfdlrm's `event_log.stream_id` *is* the `caseId`, resolved
  once by stagingdlrm via `system-id-mapper` and recorded on the
  `stagingdlrm.events.migrated-case-submission-processed` event. That same `caseId` is
  `ListedCases.caseId` in Listing — so `caseId` is the join key from stagingdlrm onward,
  while `submissionId` is only meaningful for the funcapp ↔ stagingdlrm join.
- **A genuine success always wins over a later duplicate/retry event on the same stream.**
  Every status-derivation query orders by `(success IS TRUE) DESC, position_in_stream DESC`
  — a case that succeeded and was later resubmitted still reports as processed/accepted,
  with resubmission activity surfaced in its own separate count column instead of being
  able to shadow the earlier success.
- **Most event names that sound fatal aren't.** In pcfdlrm only ~12 of 72 `ProblemCode`
  values are actually case-blocking; events like `defendant-validation-failed` are
  non-fatal diagnostics and a case can carry dozens of them and still end up `PROCESSED`.
  `pcfdlrm_status` is derived purely from the blocking codes — don't infer failure from
  event names alone.
- **A case with zero hearings never appears in `listing_report.csv` at all** (`listed_cases`
  has one row per hearing) — this is expected, and is reported as
  `overall_status=PROCESSED_NO_HEARING_TO_LIST`, not as a stuck/missing case.
- **Script 1 (`function-app-report.sh`) makes exactly 4 Azure CLI calls total, regardless of
  batch size** — one `blob list` plus two `download-batch` calls, with an in-memory lookup
  replacing what was originally a handful of per-submission `az` round-trips. Don't
  reintroduce a per-submission `az storage blob` call when extending this script; it was
  removed for being too slow on real batch sizes.

## Prerequisites

- `az` CLI, authenticated (`az login`) — or `AZURE_STORAGE_CONNECTION_STRING` for
  non-interactive/CI use
- `jq`
- `psql`
- `python3` (standard library only — no extra packages)
- bash 4+ (`function-app-report.sh` uses associative arrays; macOS's system `/bin/bash` is
  3.2 and will fail with a clear error rather than a cryptic one)

## Environment variables

Source `env.sh <dev04|ste|prd>` to set all of these for a target environment in one go (must
be *sourced*, not executed — `. ./env.sh dev04` or `source ./env.sh dev04`). Copy it to
`env_mine.sh` (gitignored) to fill in real values without touching the tracked template.

| Variable | Used by | Default |
|---|---|---|
| `DLRM_STORAGE_ACCOUNT` *or* `AZURE_STORAGE_CONNECTION_STRING` | `function-app-report.sh` | none — one is required. Connection-string auth takes precedence if both are set. |
| `DLRM_CONTAINER` | `function-app-report.sh` | `dlrmcontainer` |
| `DLRM_LOG_QUEUE` | `function-app-report.sh` | `dlrmlogqueue` |
| `STAGINGDLRM_DB_USER` | `stagingdlrm-report.sh` | none — required |
| `STAGINGDLRM_DB_HOST` / `_PORT` / `_NAME` | `stagingdlrm-report.sh` | `localhost` / `5432` / `stagingdlrmeventstore` |
| `PCFDLRM_DB_USER` | `pcfdlrm-report.sh` | none — required |
| `PCFDLRM_DB_HOST` / `_PORT` / `_NAME` | `pcfdlrm-report.sh` | `localhost` / `5432` / `pcfdlrmeventstore` |
| `LISTING_DB_USER` | `listing-report.sh` | none — required |
| `LISTING_DB_HOST` / `_PORT` / `_NAME` | `listing-report.sh` | `localhost` / `5432` / `listingviewstore` |

None of the DB scripts read a password from the environment — `psql` always prompts
interactively.

## Usage

Every script writes its output to `./output` relative to wherever you run it from (see
[Output](#output) below) — so run them with `reconciliation/` as your current directory,
which is where this repo's own `output/`/`archived/` folders already live:

```bash
cd reconciliation
source ../tools/reconciliation/env.sh <envname>   # sets required DB/storage env vars — see Environment variables above
../tools/reconciliation/run-all.sh <batch_id>
../tools/reconciliation/run-all.sh <batch_id> --archive=<tag>   # tag+rename any pre-existing output CSVs first
../tools/reconciliation/run-all.sh <batch_id> --report-type=business   # business-friendly column set (see Report types below)
../tools/reconciliation/run-all.sh <batch_id> --archive=<tag> --report-type=dlrm   # either order is fine
```

This runs, in sequence, failing fast on the first non-zero exit:

```
run-all.sh <batch_id> [--report-type=technical|business|dlrm]
  ├─ 1/5  function-app-report.sh <batch_id>            → dlrm_storage.csv, dlrm_logqueue.csv
  ├─ 2/5  stagingdlrm-report.sh <batch_id>              → stagingdlrm_report.csv
  ├─ 3/5  pcfdlrm-report.sh    [stagingdlrm_report.csv] → pcfdlrm_report.csv
  ├─ 4/5  listing-report.sh    [stagingdlrm_report.csv] → listing_report.csv
  └─ 5/5  summary-report.sh    [--report-type=...] (reads the four files above) → summary_report.csv / summary_report_business.csv / summary_report_dlrm.csv
```

`summary-report.sh` can also be run directly (e.g. to regenerate just the summary without
re-running the whole pipeline), forwarding `--report-type=` the same way:

```bash
../tools/reconciliation/summary-report.sh                       # technical (default)
../tools/reconciliation/summary-report.sh --report-type=business
../tools/reconciliation/summary-report.sh --report-type=dlrm
```

Scripts 3 and 4 both key off Script 2's output independently (not off each other) — they
could run in parallel, since pcfdlrm and Listing are independent downstream branches from
stagingdlrm. Each script is also independently runnable/inspectable on its own (same
`reconciliation/` current-directory requirement applies); `psql`-backed scripts (2–4) will
pause for an interactive password prompt.

`source_system` is hardcoded to `XHIBIT` throughout — this pipeline is not currently used
for any other migration source system.

Bundle the scripts for handing to someone without a full repo checkout (run from
`tools/reconciliation/` itself — this one packages sibling files, not output):
```bash
cd tools/reconciliation
./create-scripts-zip.sh   # produces dlrm-reconciliation-scripts.zip in this directory
```

## Output

All CSVs are written to `./output` relative to your current directory when you invoke the
scripts (gitignored, fixed filenames — no batch_id/timestamp in the name, so downstream
scripts always know where to find "the" latest output for the batch just run). If a file
already exists from a previous run, it's renamed with a timestamp suffix before being
overwritten — never silently clobbered. Run from `reconciliation/` (as shown above) and
this lands in this repo's own `reconciliation/output/`; `reconciliation/archived/` is for
manually filing away past runs' CSVs (also gitignored) and isn't written to automatically.

## Report field reference

### `dlrm_storage.csv` — `function-app-report.sh`

One row per case submission found under the `<source_system>/<batch_id>/` prefix in Azure
Blob Storage.

| Column | Meaning |
|---|---|
| `case_urn` | The prosecutor's case reference (`migratedCase.caseDetails.prosecutorCaseReference`) from that submission's `case.json`. Blank if `case.json` is missing/unreadable/invalid. |
| `batch_id` | The batch identifier passed as the script's argument. |
| `submission_id` | The UUID segment of the blob path (4th path token). |
| `outcome_success` | The `success` field from `outcome.json`, once the function app has POSTed the case onward. Blank if no outcome file exists yet. |
| `outcome_description` | The `description` field from `outcome.json` — typically the HTTP status/error detail on failure. Blank on success or no outcome yet. |
| `anomaly` | Semicolon-joined list of data problems noticed for this submission (malformed path, missing files, JSON parse failure, transient `az` failure). Blank means it checked out cleanly — first column to check when triaging. |
| `log_queue` | Whether this submission's prefix was found among the (first 32) messages peeked from the dead-letter queue during this run — `true`/`false`, or blank if the path was too malformed to compute a prefix. A queue deeper than 32 messages can produce a false `false`; the console warns when that's possible. |

### `stagingdlrm_report.csv` — `stagingdlrm-report.sh`

One row per case submission found in stagingdlrm's `event_log` under the `XHIBIT/<batch_id>/`
prefix.

| Column | Meaning |
|---|---|
| `batch_id` | The batch identifier passed as the script's argument. |
| `submission_id` | stagingdlrm's identifier for this submission — the `event_log` stream ID. |
| `case_urn` | The prosecutor's case reference, resolved from the submission's entry event (`received` or `error`). |
| `case_id` | The CPP Case File UUID, resolved via `system-id-mapper`. Only set once a `processed` event exists — blank for `RECEIVED`/`ERROR`. |
| `status` | `RECEIVED` (validated, nothing since) / `PROCESSED` / `DUPLICATE` / `CASE_ALREADY_EXISTS` / `PROCESSED_FAILED` (other failed-processing reason) / `ERROR` (schema/validation failure) / `UNKNOWN` (defensive fallback). |
| `description` | Failure reason for `PROCESSED_FAILED`/`DUPLICATE`/`CASE_ALREADY_EXISTS`/`ERROR`. Blank on success. |
| `duplicate_submissions_received` | Count of duplicate-resubmission events, independent of `status`. |
| `azure_location` | The blob path (`source/batch/case/submissionId`) — cross-reference with the funcapp report. |
| `hearing_count` | Number of hearings in the submitted case. Blank for `ERROR` rows. |
| `defendant_count` | Number of defendants in the submitted case. Blank for `ERROR` rows. |
| `material_count` | Number of materials attached. `0` is a real "no materials" fact; blank only for `ERROR` rows. |
| `latest_event` | The raw stagingdlrm event name that determined `status` — useful for debugging. |
| `last_updated` | Timestamp of that latest event. |

### `pcfdlrm_report.csv` — `pcfdlrm-report.sh`

One row per `case_id` from `stagingdlrm_report.csv`, reporting its latest status in
pcfdlrm's `event_log`.

| Column | Meaning |
|---|---|
| `case_urn` | Resolved from pcfdlrm's own events, not carried over from Script 2. Blank if pcfdlrm has no record of the case at all. |
| `case_id` | The CPP Case File UUID — the join key. |
| `pcfdlrm_status` | `RECEIVED` (validated, not yet accepted) / `PROCESSED` (accepted) / `REJECTED` (reason in `description`) / `NOT_FOUND_IN_AUTOMATION` (accepted without a prior valid receive) / `NOT_RECEIVED_BY_PCFDLRM` (never appears in pcfdlrm's `event_log`) / `UNKNOWN` (defensive fallback). |
| `description` | The specific rejection reason when `pcfdlrm_status=REJECTED` (one of ~10 hardcoded validation failures). Blank otherwise. |
| `validation_warning_count` | Count of non-fatal `migrated-case-validated-with-warnings` events (case/defendant/hearing/offence-level). |
| `validation_warning_details` | `"<type>: <message>"` per warning event, joined with `\|`. |
| `defendant_validation_failed_count` | Count of `defendant-validation-failed` events — non-fatal in itself; only specific problem codes actually block a case (reflected via `pcfdlrm_status=REJECTED`, not this column). |
| `defendant_validation_failed_details` | Per-event `"code1; code2"` summary, joined with `\|`. |
| `hearing_validation_failed_count` | Count of a legacy event with no producing code path left in the current codebase (rare). |
| `material_uploaded_count` | Count of materials with a **confirmed** upload (`material-added-pending-process`, not the earlier `material-added` "requested" event). |
| `latest_event` | The raw pcfdlrm event name that determined `pcfdlrm_status`. |
| `last_updated` | Timestamp of that latest event. |

### `listing_report.csv` — `listing-report.sh`

One row per `case_id` **that has actually reached Listing** — a case absent from
`listed_cases` produces no row at all.

| Column | Meaning |
|---|---|
| `case_id` | The CPP Case File UUID — the join key. Absence from this file surfaces downstream as `overall_status=STUCK_AT_LISTING`. |
| `case_reference` | The case reference recorded by Listing — cross-checked against stagingdlrm's `case_urn` in the summary report's `case_reference_match`. |
| `hearing_count` | Number of hearing rows for this case in Listing. Cross-checked against stagingdlrm's own `hearing_count` in the summary report. |
| `hearings` | Verbose per-hearing JSON array (`hearing_id`, `unscheduled`, `allocated`, `week_commencing_start_date`/`_end_date`, `start_date`/`end_date`, `estimated_minutes`). Summarized into the summary report's `hearing_status` column rather than surfaced raw there. |

### `summary_report.csv` — `summary-report.sh` (+ `summary-report.py`)

Combines all four reports above into one row per case with an overall verdict. Pure
Python standard-library CSV processing — no database, no network. `dlrm_storage.csv` is
the only optional input (a missing one just blanks the `funcapp_*` columns and disables
`NEVER_INGESTED` detection); the other three are hard-required.

| Column | Meaning |
|---|---|
| `batch_id` | Same value on every row. |
| `submission_id` | The join key between funcapp and staging. |
| `case_urn` | Sourced from staging, falling back to funcapp's for a case that never reached stagingdlrm. |
| `case_id` | Sourced from staging — the join key into `pcf_*`/`listing_*` columns. Blank until staging has processed the case. |
| `funcapp_outcome_success` / `funcapp_outcome_description` | Whether the function app successfully forwarded the case, and why not if it didn't. Blank if `dlrm_storage.csv` wasn't available. |
| `staging_status` / `staging_description` | From `stagingdlrm_report.csv`'s `status`/`description`. |
| `azure_location` | From staging's own `azure_location` — blank for `NEVER_INGESTED` rows. |
| `pcf_status` / `pcf_description` | From `pcfdlrm_report.csv`'s `pcfdlrm_status`/`description`. Blank if the case never reached staging with a resolved `case_id`. |
| `staging_hearing_count` / `staging_defendant_count` | The submitted case's counts per staging — `staging_hearing_count` feeds the listing cross-check; `staging_defendant_count` has no listing-side counterpart and is reference-only. |
| `material_count` | Carried straight over from `stagingdlrm_report.csv`'s own `material_count` column (see above) — no listing-side counterpart, reference-only. |
| `listing_case_reference` / `listing_hearing_count` | From `listing_report.csv` — only populated once `pcf_status=PROCESSED` **and** the case was actually found in listing. |
| `case_reference_match` / `hearing_count_match` | `true`/`false` comparison of staging vs. listing values, computed only when listing data was found. A mismatch does **not** change `overall_status` — it's a separate data-consistency signal, surfaced via `overall_description`. |
| `hearing_status` | Aggregate hearing-allocation status from listing's `hearings` array, computed only when listing data was found: semicolon-joined counts of matching hearings per category — `allocated_hearing=<n>` / `unscheduled_hearing=<n>` / `week_commencing_hearing=<n>` / `unallocated_hearing=<n>` (any combination can appear together; a category is omitted entirely when its count is `0`). E.g. a case with 2 week-commencing hearings and 1 unallocated hearing reads `week_commencing_hearing=2; unallocated_hearing=1`. Blank if no listing data or `hearings` is empty. |
| `overall_status` | `NEVER_INGESTED` (never reached stagingdlrm) / `STUCK_AT_STAGINGDLRM` (staging status is `ERROR`/`RECEIVED`/`DUPLICATE`/`CASE_ALREADY_EXISTS`/`PROCESSED_FAILED`/`UNKNOWN`) / `STUCK_AT_PCFDLRM` (staging succeeded, pcf hasn't) / `PROCESSED_NO_HEARING_TO_LIST` (staging+pcf succeeded, case has no hearings — never reaches Listing by design) / `STUCK_AT_LISTING` (staging+pcf succeeded, case has hearings, but absent from Listing) / `PROCESSED` (all three stages succeeded) / `UNKNOWN` (defensive fallback). **First column to check when triaging a batch.** |
| `overall_description` | Blank unless a match-flag mismatch was found, in which case it names the field(s) and both values (e.g. `hearing_count mismatch: staging='3' vs listing='2'`). Also populated (with an explanatory note) for `PROCESSED_NO_HEARING_TO_LIST` rows. Does not affect `overall_status`. |

This summary deliberately omits columns available in the per-script CSVs (`latest_event`,
`last_updated`, `funcapp_anomaly`, `staging_duplicate_submissions_received`, pcf's
non-fatal diagnostic detail columns) — go to the individual reports above for that detail.

### Report types (`--report-type=`)

`summary-report.py`/`summary-report.sh` (and `run-all.sh`, which forwards the flag) accept
`--report-type=technical|business|dlrm`, defaulting to `technical` when omitted. All three
project the exact same set of case rows and join/derivation logic — only the column
selection differs, so no report type ever drops a case the others include.

| Report type | Output file | Columns (in order) |
|---|---|---|
| `technical` (default) | `output/summary_report.csv` | All columns listed in the field reference above. |
| `business` | `output/summary_report_business.csv` | `case_urn, defendant_count, material_count, hearing_status, overall_status, overall_description` |
| `dlrm` | `output/summary_report_dlrm.csv` | `batch_id, azure_location, case_urn, defendant_count, material_count, hearing_status, overall_status, overall_description` |

`defendant_count` in the `business`/`dlrm` reports is the same value as `technical`'s
`staging_defendant_count` column, just un-namespaced. An unrecognised `--report-type`
value (e.g. `--report-type=foo`) exits non-zero with a stderr message before writing any
file.

## Known limitations

- `CASE_ALREADY_EXISTS` (`stagingdlrm_report.csv`'s `status`) has not yet been observed
  against a live batch — the derivation logic is grounded in
  `MigratedCaseSubmissionAggregate`'s own constants, but hasn't been confirmed end-to-end.
- The dead-letter queue check in `dlrm_storage.csv`'s `log_queue` column only peeks the
  front 32 messages of `dlrmlogqueue` — a genuinely dead-lettered submission can still show
  `false` if the queue holds more than that at peek time.
- A `case_id` with a fatal-sounding event name isn't necessarily rejected in pcfdlrm — see
  "Key design facts" above. Don't add new logic that infers failure from event name alone.
