#!/usr/bin/env python3
"""
summary-report.py — combines Script 1/2/3/4's output CSVs for a batch into
one per-case summary row with an overall_status, overall_description, and
hearing_status. Pure standard-library CSV processing — no database, no network, no
credentials; the only files it ever touches are the four inputs below and
its own output.

Expects, in an `output/` directory relative to the current directory:
  output/dlrm_storage.csv              — Script 1 (function-app-report.sh), optional
  output/stagingdlrm_report.csv        — Script 2 (stagingdlrm-report.sh), required
  output/pcfdlrm_report.csv            — Script 3 (pcfdlrm-report.sh), required
  output/listing_report.csv            — Script 4 (Listing), required
Run those scripts for this batch first. dlrm_storage.csv is the only optional
input — if function-app-report.sh couldn't be run for this batch (e.g. no
Azure access), summary-report.py still runs: funcapp_outcome_success/
funcapp_outcome_description come out blank for every row, and
overall_status=NEVER_INGESTED can no longer be detected (see join strategy
below) — everything else (staging/pcf/listing reconciliation) proceeds
unaffected. A missing dlrm_storage.csv prints a warning, not an error.

Writes output/summary_report.csv by default (creating the output/ directory
if it doesn't exist). Pass --report-type=business or --report-type=dlrm to
instead write a narrower column projection of the same case rows to
output/summary_report_business.csv / output/summary_report_dlrm.csv — see
REPORT_TYPES below for exact columns. Whichever file is written, if it
already exists from a previous run it's renamed with a timestamp suffix
first.

Join strategy: funcapp and staging are joined on submission_id via a full
outer join (union of both keys) — not a left join from staging — so a case
that's in blob storage but never reached stagingdlrm at all still surfaces
as a row instead of silently disappearing. (This detection is only possible
when dlrm_storage.csv is present — with it missing, funcapp contributes no
keys, so the "union" collapses to just staging's own submission_ids, and
there's no data to ever produce a NEVER_INGESTED row.) staging, pcf, and
listing are all joined on case_id via plain left joins (pcf/listing's own
inputs are exactly staging's case_id column, so there's nothing to
outer-join on those sides).

Listing is the true final step: a case_id found in listing_report.csv means
the case made it all the way through the pipeline. The hearing_count/
case_reference cross-checks against staging's own values are
only meaningful once pcf has actually succeeded (pcf_status=PROCESSED) —
that's also the only point at which we look the case_id up in listing at
all, matching the same staged logic already used for staging→pcf: a case
still stuck earlier isn't expected to be in listing yet, so it isn't
reported as "missing" there. A case_id present in staging with
pcf_status=PROCESSED but absent from listing surfaces as overall_status=
STUCK_AT_LISTING — this *is* the "missing case_id" report, in-row rather
than as a separate artifact. Exception: a case whose staging_hearing_count
is "0" (no hearings in the original migrated submission) will never appear
in listing_report.csv by design — listing-report.sql can only ever produce
a row for a case with at least one actual hearing — so that specific
absence surfaces instead as overall_status=PROCESSED_NO_HEARING_TO_LIST, not
STUCK_AT_LISTING, since there's nothing wrong or stuck about it.

overall_description is populated when a count/reference mismatch was found,
or when overall_status=PROCESSED_NO_HEARING_TO_LIST (explaining why listing columns
are blank) — blank otherwise. It does not affect overall_status, which
tracks pipeline progress, not data consistency.

hearing_status is a separate column carrying an aggregate hearing-allocation
status, computed once listing data was found for the case (blank otherwise)
from listing's own `hearings` JSON array. Each hearing in the case is
classified independently, then the case's flags report the count of hearings
matching each category: allocated_hearing=<n> for hearings that are
allocated, unscheduled_hearing=<n> for hearings that are unscheduled,
week_commencing_hearing=<n> for hearings that carry both a
week_commencing_start_date and week_commencing_end_date, and
unallocated_hearing=<n> for hearings matching none of the other three —
computed the same per-hearing-independent way as the other three, so a
hearing with none of those three properties is still counted even when a
different hearing on the same case does carry one of them. A category is
omitted entirely when its count is 0. Any combination of all four categories
can appear together for a case with a sufficiently varied mix of hearings
(e.g. "week_commencing_hearing=2; unallocated_hearing=1" for a case with 2
week-commencing hearings and 1 unallocated hearing). Blank (no flags at all)
if the case's `hearings` array is empty or fails to parse as JSON — not
treated as unallocated, since there's no hearing to classify. Does not
affect overall_status.

USAGE:
  ./summary-report.py [--report-type=technical|business|dlrm]
"""

import csv
import datetime
import json
import sys
from pathlib import Path

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

# The internal row dict keeps its own build-up key names (unchanged
# regardless of --report-type); this maps the requirements' un-namespaced
# business/DLRM column names onto those internal keys. Anything not listed
# here (e.g. material_count) already matches its internal key directly.
FIELD_ALIASES = {
    "defendant_count": "staging_defendant_count",
}

STUCK_AT_STAGINGDLRM_STATUSES = {
    "ERROR", "RECEIVED", "DUPLICATE", "CASE_ALREADY_EXISTS", "VALIDATION_REJECTED", "PROCESSED_FAILED", "UNKNOWN",
}


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


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


def project_row(row, columns):
    return {column: row.get(FIELD_ALIASES.get(column, column), "") for column in columns}


def archive_if_exists(path: Path):
    if path.exists():
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        backup = path.with_name(f"{path.stem}_{timestamp}{path.suffix}")
        path.rename(backup)
        print(f"==> Archived existing {path} to {backup}", file=sys.stderr)


def derive_status(staging_status, pcf_status, in_listing, staging_hearing_count):
    if staging_status is None:
        return "NEVER_INGESTED"
    if staging_status in STUCK_AT_STAGINGDLRM_STATUSES:
        return "STUCK_AT_STAGINGDLRM"
    if staging_status == "PROCESSED":
        if pcf_status != "PROCESSED":
            return "STUCK_AT_PCFDLRM"
        if not in_listing:
            if staging_hearing_count == "0":
                return "PROCESSED_NO_HEARING_TO_LIST"
            return "STUCK_AT_LISTING"
        return "PROCESSED"
    return "UNKNOWN"


def bool_str(value: bool) -> str:
    return "true" if value else "false"


def build_mismatch_parts(
    case_urn, listing_case_reference,
    staging_hearing_count, listing_hearing_count,
    case_reference_match, hearing_count_match,
):
    parts = []
    if case_reference_match == "false":
        parts.append(f"case_reference mismatch: staging={case_urn!r} vs listing={listing_case_reference!r}")
    if hearing_count_match == "false":
        parts.append(f"hearing_count mismatch: staging={staging_hearing_count!r} vs listing={listing_hearing_count!r}")
    return parts


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
    # A hearing matching none of the three above is itself an "unallocated"
    # hearing — computed the same per-hearing-independent way as the other
    # three, not as a whole-case fallback, so it isn't masked by a different,
    # flagged hearing on the same case.
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


def main():
    report_type = parse_report_type(sys.argv[1:])
    filename, columns = REPORT_TYPES[report_type]

    output_dir = Path.cwd() / "output"
    funcapp_path = output_dir / "dlrm_storage.csv"
    staging_path = output_dir / "stagingdlrm_report.csv"
    pcf_path = output_dir / "pcfdlrm_report.csv"
    listing_path = output_dir / "listing_report.csv"

    for path, script in (
        (staging_path, "stagingdlrm-report.sh"),
        (pcf_path, "pcfdlrm-report.sh"),
        (listing_path, "the Listing report script"),
    ):
        if not path.exists():
            print(f"Error: expected input {path} not found — run {script} for this batch first", file=sys.stderr)
            sys.exit(1)

    if funcapp_path.exists():
        funcapp_rows = read_csv(funcapp_path)
    else:
        print(
            f"Warning: {funcapp_path} not found — proceeding without function-app-report.sh "
            "data; funcapp_* columns will be blank and blob-storage-only cases "
            "(overall_status=NEVER_INGESTED) can't be detected for this run.",
            file=sys.stderr,
        )
        funcapp_rows = []

    staging_rows = read_csv(staging_path)
    pcf_rows = read_csv(pcf_path)
    listing_rows = read_csv(listing_path)

    funcapp_by_submission = {r["submission_id"]: r for r in funcapp_rows if r.get("submission_id")}
    staging_by_submission = {r["submission_id"]: r for r in staging_rows if r.get("submission_id")}
    pcf_by_case_id = {r["case_id"]: r for r in pcf_rows if r.get("case_id")}
    listing_by_case_id = {r["case_id"]: r for r in listing_rows if r.get("case_id")}

    all_submission_ids = list(dict.fromkeys(
        list(funcapp_by_submission.keys()) + list(staging_by_submission.keys())
    ))

    out_rows = []
    for submission_id in all_submission_ids:
        f = funcapp_by_submission.get(submission_id, {})
        s = staging_by_submission.get(submission_id, {})

        case_id = s.get("case_id") or ""
        p = pcf_by_case_id.get(case_id, {}) if case_id else {}

        staging_status = s.get("status") or None
        pcf_status = p.get("pcfdlrm_status") or None

        # Only look the case up in listing (and only cross-check its counts)
        # once pcf has actually succeeded — a case still stuck earlier isn't
        # expected to be in listing yet.
        listing_row = None
        if pcf_status == "PROCESSED" and case_id:
            listing_row = listing_by_case_id.get(case_id)

        case_urn = s.get("case_urn") or f.get("case_urn") or ""

        listing_case_reference = ""
        listing_hearing_count = ""
        case_reference_match = ""
        hearing_count_match = ""

        staging_hearing_count = s.get("hearing_count", "")
        staging_defendant_count = s.get("defendant_count", "")

        hearing_flags = []
        if listing_row is not None:
            listing_case_reference = listing_row.get("case_reference", "")
            listing_hearing_count = listing_row.get("hearing_count", "")
            case_reference_match = bool_str(case_urn == listing_case_reference)
            hearing_count_match = bool_str(staging_hearing_count == listing_hearing_count)
            hearing_flags = derive_hearing_flags(listing_row.get("hearings", ""))

        overall_status = derive_status(staging_status, pcf_status, listing_row is not None, staging_hearing_count)

        if overall_status == "PROCESSED_NO_HEARING_TO_LIST":
            description_parts = ["case has no hearings in the original submission — not expected to appear in Listing"]
        else:
            description_parts = build_mismatch_parts(
                case_urn, listing_case_reference,
                staging_hearing_count, listing_hearing_count,
                case_reference_match, hearing_count_match,
            )
        overall_description = "; ".join(description_parts)
        hearing_status = "; ".join(hearing_flags)

        out_rows.append({
            "batch_id": f.get("batch_id") or s.get("batch_id") or "",
            "submission_id": submission_id,
            "case_urn": case_urn,
            "case_id": case_id,
            "funcapp_outcome_success": f.get("outcome_success", ""),
            "funcapp_outcome_description": f.get("outcome_description", ""),
            "staging_status": staging_status or "",
            "staging_description": s.get("description", ""),
            "azure_location": s.get("azure_location", ""),
            "pcf_status": pcf_status or "",
            "pcf_description": p.get("description", ""),
            "staging_hearing_count": staging_hearing_count,
            "staging_defendant_count": staging_defendant_count,
            "material_count": s.get("material_count", ""),
            "listing_case_reference": listing_case_reference,
            "listing_hearing_count": listing_hearing_count,
            "case_reference_match": case_reference_match,
            "hearing_count_match": hearing_count_match,
            "hearing_status": hearing_status,
            "overall_status": overall_status,
            "overall_description": overall_description,
        })

    out_rows.sort(key=lambda r: r["case_urn"])

    output_dir.mkdir(exist_ok=True)
    out_path = output_dir / filename
    archive_if_exists(out_path)

    projected_rows = [project_row(row, columns) for row in out_rows]
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=columns)
        writer.writeheader()
        writer.writerows(projected_rows)

    print(f"==> Combining {funcapp_path} + {staging_path} + {pcf_path} + {listing_path} into {out_path}...", file=sys.stderr)
    print(f"==> Done. Wrote {len(out_rows)} case row(s) to {out_path}.", file=sys.stderr)

    counts = {}
    for row in out_rows:
        counts[row["overall_status"]] = counts.get(row["overall_status"], 0) + 1
    print("==> Status counts:", file=sys.stderr)
    for verdict, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"    {verdict}: {count}", file=sys.stderr)

    mismatches = [
        r for r in out_rows
        if "false" in (r["case_reference_match"], r["hearing_count_match"])
    ]
    if mismatches:
        print(f"==> Warning: {len(mismatches)} case(s) with a listing count/reference mismatch — see overall_description.", file=sys.stderr)


if __name__ == "__main__":
    main()
