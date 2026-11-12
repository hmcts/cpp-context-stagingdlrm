# 01 — Requirements

- **Story:** DD-43946 (stagingdlrm half) — see `00-input-brief.md`.
- **Contract:** [ADR-DD-43946](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/blob/team/libra4/docs/pipeline/adrs/ADR-DD-43946-libra-material-upload-date.md).

## Answered questions

Confirmed by the developer 2026-11-11.

| # | Question | Answer |
|---|----------|--------|
| Q1 | Name and format of the input field. | `files[].uploadedDateTime`, ISO-8601 UTC with milliseconds, e.g. `2026-11-11T14:04:07.107Z`. |
| Q2 | Mandatory for LIBRA? | Optional. When absent the material is built as today. |
| Q3 | Fix the `files.items` tuple so every entry is validated? | No. Out of scope for this story. |
| Q4 | XHIBIT? | Unchanged. It does not send the field. |
| Q5 | Jira for this half (a story belongs to one repo). | A sub-task under DD-43946. |
| Q6 | Future `uploadedDateTime`? | Not allowed. The whole submission is rejected as an error submission, like a manifest schema failure. |

## Requirements

| ID | Requirement |
|----|-------------|
| FR-1 | `stagingdlrm.manifest.json` accepts an optional `uploadedDateTime` (date-time) on both `files.items` entry schemas. An invalid value on one of the first two entries fails manifest validation as today (error submission). |
| FR-2 | `StagingDlrmCommandHelper` copies `uploadedDateTime`, when present, to the material's `receivedDateTime`. |
| FR-3 | The service passes `receivedDateTime` to pcfdlrm unchanged (it already does; covered by a test). |
| FR-4 | Absent field → material built as today. XHIBIT unchanged. |
| FR-5 | The Function App rejects the submission (client error, same path as a manifest schema failure) when any `files` entry has an `uploadedDateTime` that is unparseable or later than the time the Function App processes the submission (UTC instant comparison). Every entry is checked, not just the first two. The error names the entry index, not the file name. |
| FR-6 | Tests for FR-1 – FR-5; existing tests and ITs stay green. |

## Acceptance criteria

- **AC-1:** GIVEN a LIBRA manifest entry with `uploadedDateTime`, WHEN the submission is processed,
  THEN the material sent to pcfdlrm has that value as `receivedDateTime`.
- **AC-2:** GIVEN a manifest entry without it, THEN the material is as today.
- **AC-3:** GIVEN an invalid `uploadedDateTime` on any manifest entry, THEN the submission is rejected
  as an error submission.
- **AC-4:** GIVEN a manifest entry whose `uploadedDateTime` is in the future, THEN the submission is
  rejected as an error submission and nothing is sent to stagingdlrm.
