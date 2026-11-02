# Input Brief — Reconciliation Summary Report Enhancements

**Source:** Direct request from Narayan Singh (Tech Lead), 2026-07-22, refined via
clarifying Q&A on 2026-07-22 and 2026-07-23. First use of the `hmcts-sdlc-orchestrator`
pipeline in this repo — see root `CLAUDE.md`'s "SDLC Orchestrator" section for how the
pipeline is adopted here.

## 1. Original ask

> 1. Update summary report to include `material_count` field
> 2. Update summary script to have options to generate 3 reports: technical report,
>    business report, dlrm report
> 3. Update `hearing_status` to store the count of each hearing type instead of just
>    whether it's present (e.g. `unallocated_hearing=1; week_commencing_hearing=2`
>    instead of `unallocated_hearing=true`) — added to RSE01 rather than as a separate
>    story.

## 2. Clarifying Q&A

**Q: Is `material_count` a straight carry-over, or a cross-hop check (submitted vs.
confirmed-uploaded)?**
A: Straight carry-over of `stagingdlrm_report.csv`'s existing `material_count` column
(materials submitted). No cross-check against pcfdlrm's `material_uploaded_count`.

**Q: What does each report type contain, and for whom?**
A:
- **Technical report** — today's full `summary_report.csv` column set, plus
  `material_count` from the point above.
- **Business report** — cut-down: `case_urn, defendant_count, material_count,
  hearing_status, overall_status, overall_description`.
- **DLRM report** — for the DLRM-side stakeholders specifically:
  `batch_id, azure_location, case_urn, defendant_count, material_count, hearing_status,
  overall_status, overall_description`.

**Q: How is the report type selected, and does `run-all.sh` need to know about it?**
A: A `--report-type` option on the summary script, default `technical`. `run-all.sh` must
also accept and pass this through.

**Q: Is the `hearing_status` count change a straight replacement of the boolean format, or
an additional column?**
A: A straight replacement — breaking change for any existing consumer of that column, not
a second column added alongside it.

**Q: What separator and which hearing types does this apply to?**
A: Keep the existing `; ` separator (not the `,` used in the original request example).
Applies to all four flags (`allocated_hearing`, `unscheduled_hearing`,
`week_commencing_hearing`, `unallocated_hearing`), not just the two given as examples.

**Q: How should zero-count categories be handled?**
A: Omit the flag entirely when its count is `0`, matching today's omit-if-absent
behaviour — never print `flag=0`.

## 3. Verified current state (codebase, 2026-07-22)

- `stagingdlrm_report.csv` (Script 2, `stagingdlrm-report.sh`) already has a
  `material_count` column ("Number of materials attached. `0` is a real 'no materials'
  fact; blank only for `ERROR` rows.") — it's just never carried into
  `summary_report.csv` today. `summary-report.py`'s own docstring lists "staging's material
  count" among columns "deliberately omitted" from the summary.
- `summary-report.sh` is a thin wrapper: `exec python3 "$PY_FILE" "$@"` — it already
  forwards any arguments to `summary-report.py` unchanged, so it needs no code change to
  support a new flag.
- `run-all.sh` already parses one optional flag (`--archive=<tag>`) via a `for arg in "$@"`
  / `case` loop, and keeps a fixed `OUTPUT_FILENAMES` array used by that flag to relocate
  existing output CSVs before a fresh run.
- Neither `defendant_count` nor `material_count` exist today under those exact names in
  `summary_report.csv` — the existing technical-report columns are namespaced
  `staging_defendant_count` (no `staging_material_count` exists at all, since the column
  was never carried over). The business/DLRM report specs above use un-namespaced names
  (`defendant_count`, `material_count`) since those reports don't carry any competing
  same-named value from another hop.
- `hearing_status` (`summary-report.py`'s `derive_hearing_flags`) currently computes
  `any(...)` per category (`allocated`/`unscheduled`/`week_commencing`/`unallocated`) and
  emits a fixed `"<flag>=true"` string when at least one hearing matches, `; `-joined,
  omitting any category with zero matches. This is the exact function/behaviour the Q&A
  above (§2) changes.

## 4. Out of scope

- Any change to the four upstream per-hop scripts (`function-app-report.sh`,
  `stagingdlrm-report.sh`, `pcfdlrm-report.sh`, `listing-report.sh`) or their CSVs.
- Cross-checking `material_count` against pcfdlrm's `material_uploaded_count` (explicitly
  ruled out above — may be a future enhancement, not this one).
- Any change to how batches are triggered/run, beyond threading the new flag through.
