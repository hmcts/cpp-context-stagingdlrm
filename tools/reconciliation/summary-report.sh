#!/usr/bin/env bash
# Version 0.2.0 (2026-07-15)
#
# summary-report.sh — thin wrapper around summary-report.py (pure standard-
# library Python CSV processing — no database, no network, no credentials).
# See summary-report.py for the join/verdict logic and its design notes.
#
# USAGE:
#   ./summary-report.sh [--report-type=technical|business|dlrm]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY_FILE="$SCRIPT_DIR/summary-report.py"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: required tool 'python3' not found on PATH" >&2
  echo "On macOS, install it via Homebrew: brew install python3" >&2
  exit 1
fi

exec python3 "$PY_FILE" "$@"
