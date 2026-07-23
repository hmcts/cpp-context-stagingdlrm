# User Stories — Reconciliation Summary Report Enhancements

> Stage 3 artefact (story-writer). Source: `01-requirements.md` (approved),
> `02-design.md` (approved), `00-input-brief.md`.

## Jira mapping

Parent ticket: [DD-43014](https://tools.hmcts.net/jira/browse/DD-43014) — "reconciliation
report first version". Each story below has a linked Jira sub-task.

| Story | Jira sub-task |
|---|---|
| RSE-01 | [DD-42990](https://tools.hmcts.net/jira/browse/DD-42990) |
| RSE-02 | [DD-42988](https://tools.hmcts.net/jira/browse/DD-42988) |
| RSE-03 | [DD-43000](https://tools.hmcts.net/jira/browse/DD-43000) |

## Summary table

| Story ID | Title | Size | Dependencies | Can run in parallel with |
|---|---|---|---|---|
| RSE-01 | Add `material_count` and `hearing_status` counts to the technical summary report | S | None | — |
| RSE-02 | `--report-type` option with business/DLRM column projections | S | RSE-01 | — |
| RSE-03 | `run-all.sh` passthrough + archive-list + README | S | RSE-02 | — |

**Sequencing:** strictly linear — RSE-02 extends the column-set machinery RSE-01
introduces `material_count` into; RSE-03 wires the flag RSE-02 defines through the
orchestrator script. All three are small enough to land as one PR if preferred, but are
kept separable in case RSE-01 alone needs to ship first (e.g. if `--report-type` needs
more review time).

---

## RSE-01: Add `material_count` and `hearing_status` counts to the technical summary report

### User story
As a **reconciliation report user (engineer or TL triaging a migration batch)**,
I want **the summary report to include each case's `material_count`, and `hearing_status`
to show the count of each hearing type rather than just whether it's present**,
so that **I can see materials-attached counts and how many hearings of each type a case
has, without opening the per-hop `stagingdlrm_report.csv`/`listing_report.csv` separately
or under-counting a case with, say, several unallocated hearings**.

### Background
FR-1 (`material_count`) and FR-8 (`hearing_status` counts) — bundled into one story at the
requester's direction rather than split, since both are small, independent, additive
changes to the same output row. `stagingdlrm_report.csv` already has a `material_count`
column, deliberately left out of the summary report until now — zero risk, no
join/derivation logic changes. `hearing_status` already exists but currently reports
boolean presence (`allocated_hearing=true`) via `derive_hearing_flags`'s `any()` checks;
FR-8 changes it to report counts (`allocated_hearing=2`) via `sum()` instead — see
`02-design.md` for the exact rewrite. Both changes are additive to the row, but FR-8 is a
**breaking format change** to `hearing_status`'s existing values for anyone already
consuming that column.

### Acceptance criteria
- [ ] AC-001: Given a batch with cases carrying non-zero `material_count` values, when
      `summary-report.py` runs with no `--report-type` flag, then `summary_report.csv`
      contains a `material_count` column matching `stagingdlrm_report.csv`'s value for
      each case.
- [ ] AC-002: Given a case whose `material_count` is `"0"` in `stagingdlrm_report.csv`,
      when the summary report is generated, then `material_count` is `"0"` (not blank) —
      matching the existing "0 is a real fact" convention documented for that column.
- [ ] AC-003: Given a case with a blank `material_count` (an `ERROR`-status row in
      `stagingdlrm_report.csv`), when the summary report is generated, then
      `material_count` is blank too.
- [ ] AC-004: All other existing columns/values in `summary_report.csv` are byte-for-byte
      unchanged for a batch already reconciled today (regression check).
- [ ] AC-005 (delivers AC-6): Given a case with, e.g., 2 week-commencing hearings and 1
      unallocated hearing in `listing_report.csv`'s `hearings` array, when
      `derive_hearing_flags` runs, then `hearing_status` reads `week_commencing_hearing=2;
      unallocated_hearing=1` — `; `-separated counts, categories with a zero count omitted
      entirely, applied uniformly across all four categories (`allocated_hearing`,
      `unscheduled_hearing`, `week_commencing_hearing`, `unallocated_hearing`).
- [ ] AC-006: Given a case with an empty/unparseable `hearings` array (no listing data
      found, or JSON fails to parse), when `derive_hearing_flags` runs, then
      `hearing_status` is blank — unchanged from today's behaviour (regression check for
      the rewrite).

### Out of scope for this story
- `--report-type` and the business/DLRM reports (RSE-02) — though RSE-02 inherits this
  story's `hearing_status` count format automatically, since it's a shared field reused by
  column projection, not recomputed per report type.
- Any change to `stagingdlrm-report.sh` or `listing-report.sh` themselves.
- Any change to hearing classification logic itself (what counts as "allocated",
  "unscheduled", etc.) — only counting vs. boolean presence changes.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `material_count` added to the technical report's column list, sourced from the
      existing internal row dict (already carries `s.get(...)` for staging fields).
- [ ] `derive_hearing_flags` rewritten per `02-design.md`: `sum()` counts replace `any()`
      checks for all four categories; `; ` join separator unchanged; zero-count categories
      still omitted.
- [ ] `tools/reconciliation/README.md`'s field reference updated: `material_count` moved
      out of the "deliberately omits" note into the column table, and `hearing_status`'s
      description updated to the count format with a worked example (partial fulfilment of
      FR-7 — full FR-7 completes in RSE-03).
- [ ] Manually verified against the existing fixture CSVs already checked into
      `reconciliation/output/` from a prior real run (or a small synthetic fixture set),
      including at least one case with multiple hearings of the same type to confirm
      counting (not just presence) works.

### Notes / open questions
- None outstanding.

---

## RSE-02: `--report-type` option with business/DLRM column projections

### User story
As a **reconciliation report user needing a simplified view**,
I want **`summary-report.py` to accept `--report-type=technical|business|dlrm`**,
so that **I can generate a business-friendly or DLRM-stakeholder-friendly report without
hand-picking columns out of the full technical CSV myself**.

### Background
FR-2, FR-3, FR-4, FR-6. Depends on RSE-01 (the `material_count` field this story's
business/DLRM column lists both reference must already exist in the row-building logic;
`hearing_status`'s count format from RSE-01/FR-8 is inherited automatically since it's the
same shared field, just projected into fewer columns). This is the core logic story —
RSE-03 only wires the orchestrator script and docs around it.

### Acceptance criteria
- [ ] AC-007 (delivers AC-1): Given no `--report-type` flag, when the script runs, then
      behaviour is identical to today plus `material_count`/`hearing_status` counts
      (RSE-01) — output remains `output/summary_report.csv` with the full technical
      column set.
- [ ] AC-008 (delivers AC-2): Given `--report-type=business`, when the script runs, then
      `output/summary_report_business.csv` is written containing exactly `case_urn,
      defendant_count, material_count, hearing_status, overall_status,
      overall_description`, one row per case, in that column order.
- [ ] AC-009 (delivers AC-3): Given `--report-type=dlrm`, when the script runs, then
      `output/summary_report_dlrm.csv` is written containing exactly `batch_id,
      azure_location, case_urn, defendant_count, material_count, hearing_status,
      overall_status, overall_description`, in that column order.
- [ ] AC-010 (delivers AC-5): Given `--report-type=foo` (unrecognised), when the script
      runs, then it exits non-zero with a clear stderr message before writing any file.
- [ ] AC-011 (delivers NFR-3): Given the same batch, the business and DLRM reports contain
      the same set of cases (by `case_urn`) as the technical report for that run — no rows
      silently dropped by the column projection.
- [ ] AC-012: Given `--report-type=business` or `=dlrm`, when a case's underlying
      `staging_defendant_count` value is used, then it appears under the `defendant_count`
      header (the un-namespaced alias per `02-design.md`), not `staging_defendant_count`.
      `hearing_status` in these reports already reflects RSE-01's count format with no
      further change here (delivers AC-6 for these two report types).

### Out of scope for this story
- `run-all.sh` changes (RSE-03).
- README updates beyond what RSE-01 already covers (RSE-03 completes FR-7).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `TECHNICAL_COLUMNS`/`BUSINESS_COLUMNS`/`DLRM_COLUMNS`/`REPORT_TYPES` (or equivalent)
      added; CLI parsing for `--report-type=` added with validation and clear error
      message.
- [ ] Manual verification: run the script three times (once per `--report-type`) against
      the same fixture batch and diff the outputs against the column lists in
      `01-requirements.md`.
- [ ] Regression-checked: `--report-type=technical` output is unchanged from RSE-01's
      output for the same fixture batch (AC-007).

### Notes / open questions
- None outstanding.

---

## RSE-03: `run-all.sh` passthrough + archive-list update + README

### User story
As a **reconciliation pipeline operator**,
I want **`run-all.sh` to accept and forward `--report-type=`, and the README to document
all three report types**,
so that **I can generate a business or DLRM report as part of the normal end-to-end
pipeline run, without invoking `summary-report.sh` separately, and so anyone reading the
README understands the new option**.

### Background
FR-5, FR-6 (archive side), FR-7. Depends on RSE-02 (the flag being wired through must
already exist in `summary-report.py`). Completes the initiative.

### Acceptance criteria
- [ ] AC-013 (delivers AC-4): Given `run-all.sh <batch_id> --report-type=business`, when
      the pipeline runs, then step 5/5 produces `output/summary_report_business.csv` and
      the overall run still exits 0.
- [ ] AC-014: Given `run-all.sh <batch_id>` with no `--report-type` (existing usage,
      possibly combined with `--archive=<tag>` in either order), when the pipeline runs,
      then behaviour is unchanged from today (technical report, default filename).
- [ ] AC-015: Given `run-all.sh <batch_id> --archive=<tag>` where a prior run already
      produced `summary_report_business.csv` and/or `summary_report_dlrm.csv`, when the
      archive step runs, then both files (if present) are relocated to
      `output/<tag>_summary_report_business.csv` / `_dlrm.csv`, matching existing
      behaviour for `summary_report.csv`.
- [ ] AC-016: `README.md`'s "Report field reference" documents all three report types
      (columns + output filenames), the `hearing_status` count format (FR-8), and the
      "Usage" section shows `--report-type=` on both `summary-report.sh` and `run-all.sh`
      invocations (completes FR-7).

### Out of scope for this story
- Any change to `summary-report.py`'s internal logic (RSE-01/RSE-02 already complete it).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `run-all.sh`'s arg-parsing loop, `OUTPUT_FILENAMES` array, header comment
      (`USAGE:`/`--archive=<tag>` doc block/`Usage Example`), and final console message
      updated.
- [ ] `README.md` updated per AC-016.
- [ ] End-to-end manual run against a small real/test batch with
      `--report-type=business` and `--report-type=dlrm`, confirming AC-013–AC-015.

### Notes / open questions
- None outstanding.
