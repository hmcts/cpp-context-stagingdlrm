#!/usr/bin/env python3
"""
test_summary_report.py — stdlib unittest test specs for RSE01 and RSE02 (see
docs/pipeline/DD-43014-reconciliation-report-enhancements/03-stories.md):

  RSE01:
  - FR1: `material_count` added to the technical summary report, carried
    straight over from stagingdlrm_report.csv.
  - FR8: `hearing_status` reports per-category hearing *counts* instead of
    boolean presence.

  RSE02:
  - FR2/FR3: `--report-type=business|dlrm` project the same case rows onto a
    narrower, fixed column list.
  - FR4: `--report-type=technical` (or no flag) is unchanged from RSE01.
  - FR6: business/DLRM reports contain the same set of cases as technical,
    no rows dropped by projection.
  - Un-namespaced `defendant_count` alias onto the internal
    `staging_defendant_count` key for business/DLRM output.
  - CLI validation: unrecognised --report-type exits non-zero with a clear
    stderr message before writing any file.

No new dependency (NFR1) — pure standard library, matching
summary-report.py's own constraint. Not part of the Maven build; this
directory has no JUnit/CI wiring (see CLAUDE.md's SDLC Orchestrator
section) — run directly:

  python3 -m unittest tools/reconciliation/test_summary_report.py -v

Test classes:
  - DeriveHearingFlagsTests: unit tests against the pure
    `derive_hearing_flags` function (AC005/AC006).
  - ParseReportTypeTests: unit tests against the pure `parse_report_type`
    function (AC010) and `project_row` (AC012 aliasing).
  - SummaryReportEndToEndTests: subprocess-driven, fixture-CSV-in/
    output-CSV-out tests against the whole script (AC001-AC004), since
    `main()` reads/writes real files relative to the current directory
    rather than exposing a testable row-building function.
  - ReportTypeEndToEndTests: subprocess-driven tests covering
    --report-type=technical|business|dlrm end to end (AC007-AC011).
"""

import csv
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import importlib.util

SCRIPT_PATH = Path(__file__).resolve().parent / "summary-report.py"

# The script's filename has a hyphen, so it can't be `import`ed normally —
# load it directly from its file path instead.
_spec = importlib.util.spec_from_file_location("summary_report", SCRIPT_PATH)
summary_report = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(summary_report)


class DeriveHearingFlagsTests(unittest.TestCase):
    """Unit tests for derive_hearing_flags — AC005/AC006."""

    def test_empty_hearings_array_is_blank(self):
        self.assertEqual(summary_report.derive_hearing_flags("[]"), [])

    def test_missing_hearings_is_blank(self):
        self.assertEqual(summary_report.derive_hearing_flags(""), [])

    def test_unparseable_json_is_blank(self):
        self.assertEqual(summary_report.derive_hearing_flags("not-valid-json"), [])

    def test_non_list_json_is_blank(self):
        self.assertEqual(summary_report.derive_hearing_flags('{"not": "a list"}'), [])

    def test_counts_multiple_hearings_of_the_same_category(self):
        # AC005: 2 week-commencing hearings + 1 unallocated hearing.
        hearings = (
            '[{"week_commencing_start_date": "2026-08-01", "week_commencing_end_date": "2026-08-07"},'
            ' {"week_commencing_start_date": "2026-08-08", "week_commencing_end_date": "2026-08-14"},'
            ' {"allocated": false, "unscheduled": false}]'
        )
        self.assertEqual(
            summary_report.derive_hearing_flags(hearings),
            ["week_commencing_hearing=2", "unallocated_hearing=1"],
        )

    def test_zero_count_categories_are_omitted(self):
        hearings = '[{"allocated": true}]'
        flags = summary_report.derive_hearing_flags(hearings)
        self.assertEqual(flags, ["allocated_hearing=1"])
        self.assertFalse(any(f.startswith("unscheduled_hearing") for f in flags))
        self.assertFalse(any(f.startswith("week_commencing_hearing") for f in flags))
        self.assertFalse(any(f.startswith("unallocated_hearing") for f in flags))

    def test_a_hearing_can_count_in_more_than_one_category(self):
        hearings = (
            '[{"allocated": true, "week_commencing_start_date": "2026-08-01",'
            ' "week_commencing_end_date": "2026-08-07"}]'
        )
        self.assertEqual(
            summary_report.derive_hearing_flags(hearings),
            ["allocated_hearing=1", "week_commencing_hearing=1"],
        )

    def test_unscheduled_count(self):
        hearings = '[{"unscheduled": true}, {"unscheduled": true}, {"unscheduled": true}]'
        self.assertEqual(summary_report.derive_hearing_flags(hearings), ["unscheduled_hearing=3"])


class ParseReportTypeTests(unittest.TestCase):
    """Unit tests for parse_report_type (AC010) and project_row (AC012)."""

    def test_defaults_to_technical_with_no_args(self):
        self.assertEqual(summary_report.parse_report_type([]), "technical")

    def test_accepts_technical(self):
        self.assertEqual(summary_report.parse_report_type(["--report-type=technical"]), "technical")

    def test_accepts_business(self):
        self.assertEqual(summary_report.parse_report_type(["--report-type=business"]), "business")

    def test_accepts_dlrm(self):
        self.assertEqual(summary_report.parse_report_type(["--report-type=dlrm"]), "dlrm")

    def test_unrecognised_report_type_exits_non_zero(self):
        # AC010
        with self.assertRaises(SystemExit) as ctx:
            summary_report.parse_report_type(["--report-type=foo"])
        self.assertNotEqual(ctx.exception.code, 0)

    def test_unrecognised_argument_exits_non_zero(self):
        with self.assertRaises(SystemExit) as ctx:
            summary_report.parse_report_type(["--bogus"])
        self.assertNotEqual(ctx.exception.code, 0)

    def test_project_row_aliases_defendant_count(self):
        # AC012: staging_defendant_count's value appears under the
        # un-namespaced defendant_count header.
        row = {"staging_defendant_count": "3", "material_count": "1", "case_urn": "URN001"}
        projected = summary_report.project_row(row, summary_report.BUSINESS_COLUMNS)
        self.assertEqual(projected["defendant_count"], "3")
        self.assertNotIn("staging_defendant_count", projected)

    def test_project_row_leaves_material_count_key_name_unchanged(self):
        row = {"staging_defendant_count": "3", "material_count": "1", "case_urn": "URN001"}
        projected = summary_report.project_row(row, summary_report.BUSINESS_COLUMNS)
        self.assertEqual(projected["material_count"], "1")

    def test_project_row_only_includes_requested_columns(self):
        row = {
            "staging_defendant_count": "3", "material_count": "1", "case_urn": "URN001",
            "batch_id": "b1", "submission_id": "sub-1",
        }
        projected = summary_report.project_row(row, summary_report.BUSINESS_COLUMNS)
        self.assertEqual(set(projected.keys()), set(summary_report.BUSINESS_COLUMNS))

    def test_project_row_defaults_missing_fields_to_blank(self):
        projected = summary_report.project_row({}, summary_report.DLRM_COLUMNS)
        self.assertEqual(projected, {col: "" for col in summary_report.DLRM_COLUMNS})


class ReconciliationFixtureTestCase(unittest.TestCase):
    """Shared fixture-CSV-in/output-CSV-out plumbing for the whole-script tests below."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.cwd = Path(self._tmp.name)
        self.output_dir = self.cwd / "output"
        self.output_dir.mkdir()

    def tearDown(self):
        self._tmp.cleanup()

    def _write_csv(self, name, header, rows):
        path = self.output_dir / name
        with open(path, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(header)
            writer.writerows(rows)
        return path

    def _run(self, *extra_args, output_filename="summary_report.csv"):
        result = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), *extra_args],
            cwd=self.cwd,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        with open(self.output_dir / output_filename, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))

    def _run_expecting_failure(self, *extra_args):
        return subprocess.run(
            [sys.executable, str(SCRIPT_PATH), *extra_args],
            cwd=self.cwd,
            capture_output=True,
            text=True,
        )

    def _seed_common_fixture(self):
        # case-1: PROCESSED, non-zero material_count, 2 week-commencing + 1
        # unallocated hearing in listing.
        # case-2: PROCESSED, material_count "0" (a real fact, not missing data).
        # case-3: ERROR status — no case_id, material_count blank at source.
        self._write_csv(
            "stagingdlrm_report.csv",
            ["batch_id", "submission_id", "case_urn", "case_id", "status", "description",
             "azure_location", "hearing_count", "defendant_count", "material_count"],
            [
                ["b1", "sub-1", "URN001", "case-1", "PROCESSED", "", "XHIBIT/b1/sub-1", "3", "1", "2"],
                ["b1", "sub-2", "URN002", "case-2", "PROCESSED", "", "XHIBIT/b1/sub-2", "1", "1", "0"],
                ["b1", "sub-3", "URN003", "", "ERROR", "bad json", "XHIBIT/b1/sub-3", "", "", ""],
            ],
        )
        self._write_csv(
            "pcfdlrm_report.csv",
            ["case_id", "pcfdlrm_status", "description"],
            [
                ["case-1", "PROCESSED", ""],
                ["case-2", "PROCESSED", ""],
            ],
        )
        self._write_csv(
            "listing_report.csv",
            ["case_id", "case_reference", "hearing_count", "hearings"],
            [
                [
                    "case-1", "URN001", "3",
                    '[{"week_commencing_start_date": "2026-08-01", "week_commencing_end_date": "2026-08-07"},'
                    ' {"week_commencing_start_date": "2026-08-08", "week_commencing_end_date": "2026-08-14"},'
                    ' {"allocated": false, "unscheduled": false}]',
                ],
                ["case-2", "URN002", "1", "not-valid-json"],
            ],
        )

    def _row_for(self, rows, case_urn):
        matches = [r for r in rows if r["case_urn"] == case_urn]
        self.assertEqual(len(matches), 1, msg=f"expected exactly one row for {case_urn}")
        return matches[0]


class SummaryReportEndToEndTests(ReconciliationFixtureTestCase):
    """Fixture-CSV-in/output-CSV-out tests against the whole script — AC001-AC004."""

    def test_material_count_carried_over_from_staging(self):
        # AC001
        self._seed_common_fixture()
        rows = self._run()
        self.assertEqual(self._row_for(rows, "URN001")["material_count"], "2")

    def test_material_count_zero_is_not_blank(self):
        # AC002
        self._seed_common_fixture()
        rows = self._run()
        self.assertEqual(self._row_for(rows, "URN002")["material_count"], "0")

    def test_material_count_blank_for_error_row(self):
        # AC003
        self._seed_common_fixture()
        rows = self._run()
        self.assertEqual(self._row_for(rows, "URN003")["material_count"], "")

    def test_other_columns_unaffected_by_material_count_addition(self):
        # AC004 (regression): everything else about case-2's row is exactly
        # what today's join/derivation logic already produces.
        self._seed_common_fixture()
        rows = self._run()
        row = self._row_for(rows, "URN002")
        self.assertEqual(row["case_id"], "case-2")
        self.assertEqual(row["staging_status"], "PROCESSED")
        self.assertEqual(row["pcf_status"], "PROCESSED")
        self.assertEqual(row["staging_hearing_count"], "1")
        self.assertEqual(row["staging_defendant_count"], "1")
        self.assertEqual(row["overall_status"], "PROCESSED")
        self.assertEqual(row["overall_description"], "")
        # case-2's listing `hearings` is unparseable -> hearing_status blank (AC006 regression).
        self.assertEqual(row["hearing_status"], "")

    def test_hearing_status_reports_counts_end_to_end(self):
        # AC005, exercised through the full pipeline rather than the unit
        # test alone, per RSE01's DoD ("manually verified... including at
        # least one case with multiple hearings of the same type").
        self._seed_common_fixture()
        rows = self._run()
        row = self._row_for(rows, "URN001")
        self.assertEqual(row["hearing_status"], "week_commencing_hearing=2; unallocated_hearing=1")


class ReportTypeEndToEndTests(ReconciliationFixtureTestCase):
    """Subprocess-driven --report-type tests — AC007-AC011."""

    def test_no_flag_behaves_as_technical(self):
        # AC007: identical to today (RSE01) plus material_count/hearing_status.
        self._seed_common_fixture()
        rows = self._run()
        self.assertEqual(list(rows[0].keys()), summary_report.TECHNICAL_COLUMNS)
        row = self._row_for(rows, "URN001")
        self.assertEqual(row["material_count"], "2")
        self.assertEqual(row["hearing_status"], "week_commencing_hearing=2; unallocated_hearing=1")

    def test_report_type_technical_explicit_matches_default(self):
        # AC007
        self._seed_common_fixture()
        default_rows = self._run()
        explicit_rows = self._run("--report-type=technical")
        self.assertEqual(default_rows, explicit_rows)

    def test_report_type_business_writes_expected_columns_and_filename(self):
        # AC008
        self._seed_common_fixture()
        rows = self._run("--report-type=business", output_filename="summary_report_business.csv")
        self.assertEqual(
            list(rows[0].keys()),
            ["case_urn", "defendant_count", "material_count", "hearing_status",
             "overall_status", "overall_description"],
        )

    def test_report_type_dlrm_writes_expected_columns_and_filename(self):
        # AC009
        self._seed_common_fixture()
        rows = self._run("--report-type=dlrm", output_filename="summary_report_dlrm.csv")
        self.assertEqual(
            list(rows[0].keys()),
            ["batch_id", "azure_location", "case_urn", "defendant_count", "material_count",
             "hearing_status", "overall_status", "overall_description"],
        )

    def test_unrecognised_report_type_fails_before_writing_any_file(self):
        # AC010
        self._seed_common_fixture()
        result = self._run_expecting_failure("--report-type=foo")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("foo", result.stderr)
        self.assertFalse((self.output_dir / "summary_report.csv").exists())
        self.assertFalse((self.output_dir / "summary_report_business.csv").exists())
        self.assertFalse((self.output_dir / "summary_report_dlrm.csv").exists())

    def test_business_and_dlrm_reports_contain_same_cases_as_technical(self):
        # AC011 (NFR3): no rows silently dropped by column projection.
        self._seed_common_fixture()
        technical_urns = {r["case_urn"] for r in self._run()}
        business_urns = {
            r["case_urn"] for r in
            self._run("--report-type=business", output_filename="summary_report_business.csv")
        }
        dlrm_urns = {
            r["case_urn"] for r in
            self._run("--report-type=dlrm", output_filename="summary_report_dlrm.csv")
        }
        self.assertEqual(technical_urns, business_urns)
        self.assertEqual(technical_urns, dlrm_urns)

    def test_defendant_count_aliases_staging_defendant_count_end_to_end(self):
        # AC012
        self._seed_common_fixture()
        rows = self._run("--report-type=business", output_filename="summary_report_business.csv")
        row = self._row_for(rows, "URN001")
        self.assertEqual(row["defendant_count"], "1")

    def test_hearing_status_count_format_flows_into_business_report(self):
        # AC012 (hearing_status count format inherited unchanged from RSE01).
        self._seed_common_fixture()
        rows = self._run("--report-type=business", output_filename="summary_report_business.csv")
        row = self._row_for(rows, "URN001")
        self.assertEqual(row["hearing_status"], "week_commencing_hearing=2; unallocated_hearing=1")


if __name__ == "__main__":
    unittest.main()