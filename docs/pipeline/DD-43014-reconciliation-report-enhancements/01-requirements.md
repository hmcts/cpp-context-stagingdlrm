# Requirements — Reconciliation Summary Report Enhancements

> Stage 1 artefact (requirements-analyst). Source: `00-input-brief.md`. Parent Jira ticket:
> [DD-43014](https://tools.hmcts.net/jira/browse/DD-43014).

## Functional requirements

- **FR-1 — `material_count` in the technical report.** The technical report gains a
  `material_count` column, sourced from `stagingdlrm_report.csv`'s existing
  `material_count` column — a straight carry-over, the same pattern already used for
  `staging_hearing_count`/`staging_defendant_count`. No new data collection, no change to
  any upstream script.
- **FR-2 — `--report-type` option.** The summary script accepts
  `--report-type=technical|business|dlrm`. Omitting the flag is equivalent to
  `--report-type=technical` (today's behaviour, unchanged). Any other value is a usage
  error (non-zero exit, clear message on stderr).
- **FR-3 — Business report columns.** When `--report-type=business`, the output contains
  exactly these columns, in this order: `case_urn, defendant_count, material_count,
  hearing_status, overall_status, overall_description`.
- **FR-4 — DLRM report columns.** When `--report-type=dlrm`, the output contains exactly
  these columns, in this order: `batch_id, azure_location, case_urn, defendant_count,
  material_count, hearing_status, overall_status, overall_description`.
- **FR-5 — `run-all.sh` passthrough.** `run-all.sh` accepts an optional `--report-type=`
  argument (alongside its existing `--archive=<tag>`, in any order) and forwards it to
  `summary-report.sh` for step 5/5. Omitting it preserves today's behaviour exactly
  (technical report, unnamed output file).
- **FR-6 — Backward-compatible filenames.** `--report-type=technical` (or the flag
  omitted) writes to the existing `output/summary_report.csv` path, unchanged, so nothing
  that already consumes that exact filename breaks. `business` and `dlrm` write to new,
  distinctly-named files: `output/summary_report_business.csv` and
  `output/summary_report_dlrm.csv` respectively — so re-running with a different
  `--report-type` never silently overwrites a different variant's output.
- **FR-7 — Documentation.** `tools/reconciliation/README.md`'s "Report field reference"
  section is updated: `material_count` moves out of the summary report's "deliberately
  omits" list and into its column table; a new subsection documents all three report
  types (columns + filenames) and the `--report-type` flag, referenced from the "Usage"
  section too.
- **FR-8 — `hearing_status` stores counts, not booleans.** `hearing_status`'s four flags
  (`allocated_hearing`, `unscheduled_hearing`, `week_commencing_hearing`,
  `unallocated_hearing`) report the **count** of matching hearings for that case (e.g.
  `week_commencing_hearing=2`) instead of `=true`. The `; `-separator is unchanged, and a
  flag is still omitted entirely when its count is `0` (same omit-if-absent behaviour as
  today). This is a breaking format change to an already-shipped column, applied
  uniformly to all four flags — intentional, not additive (no second column). Since
  `hearing_status` is a shared field reused by column projection, this also changes its
  value in the business and DLRM reports (FR-3/FR-4), not just the technical report.

## Non-functional requirements

- **NFR-1 — No new dependencies.** `summary-report.py` remains pure standard-library
  Python (no `pip install`), consistent with its existing "no extra packages" constraint
  and the repo's documented prerequisites.
- **NFR-2 — Upstream scripts untouched.** No changes to `function-app-report.sh`,
  `stagingdlrm-report.sh`, `pcfdlrm-report.sh`, `listing-report.sh`, or their output CSVs.
  This is scoped entirely to `summary-report.py`, `summary-report.sh`, `run-all.sh`, and
  the README.
- **NFR-3 — No silent data loss.** Business/DLRM report generation uses the exact same
  join/derivation logic as the technical report (same `overall_status` etc.) — only the
  column *projection* at write-time differs. A case that appears in the technical report
  must appear in the business/DLRM report too (same row count, narrower columns).
- **NFR-4 — Fail clearly on bad input.** An unrecognised `--report-type` value must fail
  fast with a non-zero exit and a clear stderr message, matching the script's existing
  `Usage:`-and-`exit(1)` style — not a silent fallback to `technical`.

## Acceptance criteria (summary — full detail in `03-stories.md`)

- AC-1: Given no `--report-type` flag, the script behaves exactly as it does today, plus
  the new `material_count` column.
- AC-2: Given `--report-type=business`, the output file is `summary_report_business.csv`
  with exactly the 6 columns from FR-3, one row per case matching the technical report's
  row set.
- AC-3: Given `--report-type=dlrm`, the output file is `summary_report_dlrm.csv` with
  exactly the 8 columns from FR-4.
- AC-4: Given `run-all.sh <batch_id> --report-type=business`, step 5/5 produces
  `summary_report_business.csv` and the pipeline still completes successfully end to end.
- AC-5: Given an invalid `--report-type=foo`, the script exits non-zero with a clear error
  before touching any output file.
- AC-6: Given a case with, say, 2 week-commencing hearings and 1 unallocated hearing,
  `hearing_status` reads `week_commencing_hearing=2; unallocated_hearing=1` — counts, `; `
  -separated, categories at `0` omitted — in every report type that includes the column.
