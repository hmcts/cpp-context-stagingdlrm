# 01 — Requirements

- **Story:** DD-43944 (stagingdlrm half) — see `00-input-brief.md`.
- **Contract:** [ADR-DD-43944](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/blob/team/libra4/docs/pipeline/adrs/ADR-DD-43944-libra-first-court-hearing-location.md).

## Answered questions

Confirmed by the developer 2026-11-13.

| # | Question | Answer |
|---|----------|--------|
| Q1 | Must the value reach Progression / CP? | Yes, inside `ProsecutionCase.migrationSourceSystem`; core-domain adds the field, Progression and pcfdlrm bump `coredomain` (their own stories). This story only carries it to pcfdlrm. |
| Q2 | Where does LIBRA send it? | In `manifest.json` `migrationSourceSystem`. |
| Q3 | Validation at the gate? | Optional string, exactly 7 characters (same as `hearings[].courtHearingLocation`). Wrong length rejects the submission as a manifest schema failure. Reference-data check is in pcfdlrm. |
| Q4 | XHIBIT? The manifest schema is shared. | Not restricted to LIBRA; XHIBIT does not send it and is unaffected. |
| Q5 | Update the LIBRA contract document? | No: `dlrm-libra-0.13.1.json` covers `case.json` only. |

## Requirements

| ID | Requirement |
|----|-------------|
| FR-1 | Function App `migrationSourceSystem.json` accepts optional `firstCourtHearingLocation` (string, length 7). |
| FR-2 | Canonical `migrationSourceSystem.json` declares the same optional field. |
| FR-3 | `MigratedCaseConvertor.buildMigrationSourceSystem` copies it to pcfdlrm. |
| FR-4 | Field absent → handled as today. XHIBIT unchanged. |
| FR-5 | Tests for FR-1 – FR-4; existing tests and ITs stay green. |

## Acceptance criteria

- **AC-1:** GIVEN a LIBRA manifest whose `migrationSourceSystem` has a 7-character
  `firstCourtHearingLocation`, WHEN it is processed, THEN pcfdlrm receives it in
  `migrationSourceSystem`.
- **AC-2:** GIVEN a manifest without it, THEN the submission is handled as today.
- **AC-3:** GIVEN a value that is not 7 characters, THEN the submission is rejected by manifest validation.
- **AC-4:** GIVEN an XHIBIT submission, THEN nothing changes.
