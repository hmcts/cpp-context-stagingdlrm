# Design — Reconciliation Summary Report Enhancements

> Stage 2 artefact (architecture-designer). Source: `01-requirements.md` (approved).
> No ADR required — this change is additive, backward-compatible (FR-6), and touches no
> architecturally-significant or one-way-door decision.

## Overview

All changes are confined to `summary-report.py`, `run-all.sh`, and `README.md`.
`summary-report.sh` needs no change — it already does `exec python3 "$PY_FILE" "$@"`,
forwarding any arguments (including a new `--report-type=`) to the Python script
unchanged.

## `summary-report.py`

### Column-set constants

Keep one internal, full-fidelity row-building step (today's `main()` loop), and add
`material_count` to it:

```python
"material_count": s.get("material_count", ""),
```

Then define three ordered column lists instead of the single `OUTPUT_COLUMNS`:

```python
TECHNICAL_COLUMNS = [
    "batch_id", "submission_id", "case_urn", "case_id",
    "funcapp_outcome_success", "funcapp_outcome_description",
    "staging_status", "staging_description", "azure_location",
    "pcf_status", "pcf_description",
    "staging_hearing_count", "staging_defendant_count", "material_count",
    "listing_case_reference", "listing_hearing_count",
    "case_reference_match", "hearing_count_match",
    "hearing_status",
    "overall_status", "overall_description",
]

BUSINESS_COLUMNS = [
    "case_urn", "defendant_count", "material_count",
    "hearing_status", "overall_status", "overall_description",
]

DLRM_COLUMNS = [
    "batch_id", "azure_location", "case_urn", "defendant_count", "material_count",
    "hearing_status", "overall_status", "overall_description",
]

REPORT_TYPES = {
    "technical": ("summary_report.csv", TECHNICAL_COLUMNS),
    "business": ("summary_report_business.csv", BUSINESS_COLUMNS),
    "dlrm": ("summary_report_dlrm.csv", DLRM_COLUMNS),
}
```

`material_count` sits next to `staging_defendant_count` in `TECHNICAL_COLUMNS` (FR-1) —
same section of the row as the other staging-sourced counts.

### Un-namespaced `defendant_count`/`material_count` in business/DLRM

The internal row dict keeps using `staging_defendant_count`/`material_count` as its keys
(unchanged from today's build-up logic). At output time, when projecting to
`BUSINESS_COLUMNS`/`DLRM_COLUMNS`, map the requirements' un-namespaced names onto the
internal dict's existing keys:

```python
FIELD_ALIASES = {
    "defendant_count": "staging_defendant_count",
    # material_count already matches the internal key name directly
}
```

This keeps the join/derivation logic (the `main()` loop building each row) written
exactly once regardless of `--report-type` — only the final column projection differs,
satisfying NFR-3 (same row set, narrower columns) without duplicating logic per type.

### CLI parsing

Extend the current argument handling (today: `len(sys.argv) != 1` → usage error) to
recognise an optional `--report-type=` argument, defaulting to `"technical"`:

```python
def parse_report_type(argv):
    report_type = "technical"
    for arg in argv:
        if arg.startswith("--report-type="):
            report_type = arg.split("=", 1)[1]
        else:
            print(f"Usage: {sys.argv[0]} [--report-type=technical|business|dlrm]", file=sys.stderr)
            sys.exit(1)
    if report_type not in REPORT_TYPES:
        print(f"Error: unknown --report-type '{report_type}' (expected one of: {', '.join(REPORT_TYPES)})", file=sys.stderr)
        sys.exit(1)
    return report_type
```

Plain functions over `argparse` here, matching the file's existing terse style for a
single flag (either is acceptable — implementer's call, per NFR-1 both are stdlib).

### Output

`main()` picks `filename, columns = REPORT_TYPES[report_type]`, writes to
`output/<filename>` (through the existing `archive_if_exists` timestamp-suffix logic,
unchanged), and the `DictWriter` uses `fieldnames=columns` with `extrasaction="ignore"` so
each row dict can keep every internal key without needing a per-type dict rebuild.

### `derive_hearing_flags` — counts instead of boolean presence (FR-8)

Today's function computes `any(...)` per category and emits a fixed `"<flag>=true"`
string when at least one hearing matches. Replace the four `any()` checks with `sum()`
counts, keep the same per-hearing classification helpers, keep the `; `-join and the
omit-when-absent behaviour (now omit-when-count-is-zero):

```python
def derive_hearing_flags(hearings_raw):
    try:
        hearings = json.loads(hearings_raw) if hearings_raw else []
    except (json.JSONDecodeError, TypeError):
        hearings = []
    if not isinstance(hearings, list):
        hearings = []
    hearings = [h for h in hearings if isinstance(h, dict)]

    if not hearings:
        return []

    def is_allocated(h):
        return h.get("allocated") is True

    def is_unscheduled(h):
        return h.get("unscheduled") is True

    def is_week_commencing(h):
        return bool(h.get("week_commencing_start_date") and h.get("week_commencing_end_date"))

    allocated_count = sum(1 for h in hearings if is_allocated(h))
    unscheduled_count = sum(1 for h in hearings if is_unscheduled(h))
    week_commencing_count = sum(1 for h in hearings if is_week_commencing(h))
    unallocated_count = sum(
        1 for h in hearings
        if not (is_allocated(h) or is_unscheduled(h) or is_week_commencing(h))
    )

    flags = []
    if allocated_count:
        flags.append(f"allocated_hearing={allocated_count}")
    if unscheduled_count:
        flags.append(f"unscheduled_hearing={unscheduled_count}")
    if week_commencing_count:
        flags.append(f"week_commencing_hearing={week_commencing_count}")
    if unallocated_count:
        flags.append(f"unallocated_hearing={unallocated_count}")
    return flags
```

`hearing_status = "; ".join(hearing_flags)` (in `main()`) is unchanged — only the flag
strings themselves change from `=true` to `=<count>`. Per-hearing classification semantics
are unchanged too: a hearing can still contribute to more than one category (e.g.
allocated *and* week-commencing at once), and "unallocated" is still defined per-hearing
as "matches none of the other three" — this rewrite only changes counting vs. presence,
nothing about what counts as a match. Because `hearing_status` is a shared field reused by
column projection (not recomputed per report type), this same count format automatically
flows into the business and DLRM reports wherever they include `hearing_status` — no
separate change needed for RSE-02.

## `run-all.sh`

Add a `--report-type=*` case to the existing arg-parsing loop, mirroring `--archive=`:

```bash
REPORT_TYPE=""
...
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
```

Pass it through only when set, so an empty `REPORT_TYPE` falls back to
`summary-report.py`'s own default (FR-5/FR-6):

```bash
run_step "5/5 summary-report" "$SCRIPT_DIR/summary-report.sh" ${REPORT_TYPE:+--report-type="$REPORT_TYPE"}
```

Update the `OUTPUT_FILENAMES` array (used by `--archive=` to relocate pre-existing output
before a fresh run) to include the two new filenames, so archiving works correctly
regardless of which report type(s) a prior run produced:

```bash
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
```

Update the script's header comment (`USAGE:` line and `--archive=<tag>` documentation
block) to mention the new flag, and the `Usage Example` section with one example using
it.

## `README.md`

- Move `material_count` out of the "deliberately omits" paragraph under
  `summary_report.csv`'s field reference and into its column table (FR-1/FR-7).
- Update `hearing_status`'s existing description (currently documents the `=true`
  boolean-flag format) to describe the count format instead, with a worked example
  (FR-8).
- Add a new subsection under "Report field reference" (or immediately after it) listing
  all three report types, their exact columns, and their output filenames — mirroring the
  style already used for the four per-hop reports.
- Update "Usage" to show `--report-type=` on both `summary-report.sh` and `run-all.sh`
  invocations.

## Testing approach (for Stage 4/Test Specs, informative only)

Per the CLAUDE.md SDLC section: this directory has no JUnit/Maven test harness. Scope
tests to Python's stdlib `unittest` against `summary-report.py`'s pure functions
(`parse_report_type`, the column-projection logic) plus a small fixture-based end-to-end
check (fixed input CSVs in, assert exact output columns/rows per `--report-type`) — no new
dependency, consistent with NFR-1.
