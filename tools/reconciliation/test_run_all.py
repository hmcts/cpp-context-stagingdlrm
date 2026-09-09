#!/usr/bin/env python3
"""
test_run_all.py — stdlib unittest test specs for RSE03 (see
docs/pipeline/DD-43014-reconciliation-report-enhancements/03-stories.md):

  - FR5: `run-all.sh` accepts an optional `--report-type=` argument (alongside
    the existing `--archive=<tag>`, in either order) and forwards it to
    `summary-report.sh` for step 5/5.
  - FR6 (archive side): `--archive=<tag>` relocates
    `summary_report_business.csv`/`summary_report_dlrm.csv` the same way it
    already relocates `summary_report.csv`, if present.

run-all.sh's own job is sequencing, arg-parsing, and archiving — the actual
report logic belongs to summary-report.py (see test_summary_report.py) and
the other four scripts require real Azure/Postgres access. So each test here
runs a copy of run-all.sh against stub replacements of all five step scripts
(each just records its own invocation / touches its known output file),
never touching az/psql/network. This isolates exactly what changed in this
story: passthrough of --report-type and the widened OUTPUT_FILENAMES
archive list.

No new dependency (NFR1) — pure standard library. Not part of the Maven
build; this directory has no JUnit/CI wiring (see CLAUDE.md's SDLC
Orchestrator section) — run directly:

  python3 -m unittest tools/reconciliation/test_run_all.py -v
"""

import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

RUN_ALL_PATH = Path(__file__).resolve().parent / "run-all.sh"

# Stubs for the four upstream steps: each just needs to exist and exit 0 —
# run-all.sh's own logic under test doesn't depend on their real output.
_NOOP_STEP_SCRIPT = "#!/usr/bin/env bash\nset -euo pipefail\nmkdir -p output\nexit 0\n"

# Stub for summary-report.sh: records the args it was invoked with (so the
# test can assert --report-type was forwarded correctly) and touches the
# output file the real script would have produced for that report type.
_SUMMARY_STUB_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail
mkdir -p output
echo "$@" > output/summary-report-args.txt
REPORT_TYPE="technical"
for arg in "$@"; do
  case "$arg" in
    --report-type=*) REPORT_TYPE="${arg#--report-type=}" ;;
  esac
done
case "$REPORT_TYPE" in
  business) echo stub > output/summary_report_business.csv ;;
  dlrm) echo stub > output/summary_report_dlrm.csv ;;
  *) echo stub > output/summary_report.csv ;;
esac
"""


def _make_executable(path: Path):
    path.chmod(path.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)


class RunAllReportTypeTests(unittest.TestCase):
    """Subprocess-driven tests for run-all.sh — AC013-AC015."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.script_dir = Path(self._tmp.name) / "scripts"
        self.script_dir.mkdir()
        self.cwd = Path(self._tmp.name) / "workdir"
        self.cwd.mkdir()
        self.output_dir = self.cwd / "output"

        shutil.copy(RUN_ALL_PATH, self.script_dir / "run-all.sh")
        _make_executable(self.script_dir / "run-all.sh")

        for name in (
            "function-app-report.sh",
            "stagingdlrm-report.sh",
            "pcfdlrm-report.sh",
            "listing-report.sh",
        ):
            path = self.script_dir / name
            path.write_text(_NOOP_STEP_SCRIPT)
            _make_executable(path)

        summary_stub = self.script_dir / "summary-report.sh"
        summary_stub.write_text(_SUMMARY_STUB_SCRIPT)
        _make_executable(summary_stub)

    def tearDown(self):
        self._tmp.cleanup()

    def _run(self, *args):
        return subprocess.run(
            [str(self.script_dir / "run-all.sh"), *args],
            cwd=self.cwd,
            capture_output=True,
            text=True,
        )

    def test_no_report_type_behaves_as_technical(self):
        # AC014: existing usage (no --report-type) is unchanged.
        result = self._run("batch1")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertTrue((self.output_dir / "summary_report.csv").exists())
        self.assertFalse((self.output_dir / "summary_report_business.csv").exists())
        self.assertFalse((self.output_dir / "summary_report_dlrm.csv").exists())
        self.assertIn("summary_report.csv", result.stderr)

    def test_report_type_business_is_forwarded_to_summary_report(self):
        # AC013
        result = self._run("batch1", "--report-type=business")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("--report-type=business", (self.output_dir / "summary-report-args.txt").read_text())
        self.assertTrue((self.output_dir / "summary_report_business.csv").exists())
        self.assertIn("summary_report_business.csv", result.stderr)

    def test_report_type_dlrm_is_forwarded_to_summary_report(self):
        # AC013 (dlrm variant)
        result = self._run("batch1", "--report-type=dlrm")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("--report-type=dlrm", (self.output_dir / "summary-report-args.txt").read_text())
        self.assertTrue((self.output_dir / "summary_report_dlrm.csv").exists())
        self.assertIn("summary_report_dlrm.csv", result.stderr)

    def test_batch_id_then_report_type_then_archive_all_parse(self):
        # AC014: order independence — batch_id, --archive=, --report-type= in this order.
        result = self._run("batch1", "--report-type=business", "--archive=tag1")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertTrue((self.output_dir / "summary_report_business.csv").exists())

    def test_archive_then_report_type_then_batch_id_all_parse(self):
        # AC014: order independence — --archive=, --report-type=, batch_id in this order.
        result = self._run("--archive=tag1", "--report-type=business", "batch1")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertTrue((self.output_dir / "summary_report_business.csv").exists())

    def test_archive_relocates_prior_business_and_dlrm_outputs(self):
        # AC015
        self.output_dir.mkdir(exist_ok=True)
        (self.output_dir / "summary_report_business.csv").write_text("old-business\n")
        (self.output_dir / "summary_report_dlrm.csv").write_text("old-dlrm\n")

        result = self._run("batch1", "--archive=oldrun")

        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertEqual(
            (self.output_dir / "oldrun_summary_report_business.csv").read_text(), "old-business\n"
        )
        self.assertEqual(
            (self.output_dir / "oldrun_summary_report_dlrm.csv").read_text(), "old-dlrm\n"
        )
        # Relocated, not left behind under their original names.
        self.assertFalse((self.output_dir / "summary_report_business.csv").exists())
        self.assertFalse((self.output_dir / "summary_report_dlrm.csv").exists())

    def test_archive_skips_business_and_dlrm_files_that_dont_exist(self):
        # AC015: a report type not generated in the prior run is silently skipped.
        result = self._run("batch1", "--archive=oldrun")
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertFalse((self.output_dir / "oldrun_summary_report_business.csv").exists())
        self.assertFalse((self.output_dir / "oldrun_summary_report_dlrm.csv").exists())

    def test_missing_batch_id_exits_non_zero(self):
        result = self._run()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()