# 01 — Requirements

- **Story:** DD-43944 (stagingdlrm half) — see `00-input-brief.md`.
- **Contract:** [ADR-DD-43944](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/blob/team/libra4/docs/pipeline/adrs/ADR-DD-43944-libra-first-court-hearing-location.md).

## Answered questions

Confirmed by the developer 2026-11-13; Q2 – Q4 revised for the business change of 2026-11-25.

| # | Question | Answer |
|---|----------|--------|
| Q1 | Must the value reach Progression / CP? | Yes, inside `ProsecutionCase.migrationSourceSystem`; core-domain adds the field, Progression and pcfdlrm bump `coredomain` (their own stories). This story only carries it to pcfdlrm. |
| Q2 | Where does LIBRA send it? | On `caseDetails` in `case.json`. The Function App moves it into `migrationSourceSystem` (was: manifest `migrationSourceSystem`, 2026-11-13). |
| Q3 | Validation at the gate? | Optional string, exactly 7 characters (same as `hearings[].courtHearingLocation`). Wrong length rejects the submission as a case schema failure. Reference-data check is in pcfdlrm. |
| Q4 | XHIBIT? | Declared only on the LIBRA case schema; XHIBIT is unaffected. The shared manifest schema no longer declares it. |
| Q5 | Update the LIBRA contract document? | Yes (2026-11-25): the field is now in `case.json`, so `dlrm-libra-0.13.1.json` declares it on `caseDetails`, recorded in `libra-workbook-corrections.md` (no version bump, as earlier 0.13.1 changes). |

## Requirements

| ID | Requirement |
|----|-------------|
| FR-1 | LIBRA `caseDetails` (`libra.case-submission.json`) accepts optional `firstCourtHearingLocation` (string, length 7). The Function App `migrationSourceSystem.json` does not declare it. |
| FR-1a | The Function App removes it from `caseDetails` and adds it to `migratedCase.migrationSourceSystem`. |
| FR-2 | Canonical `migrationSourceSystem.json` declares the same optional field. |
| FR-3 | `MigratedCaseConvertor.buildMigrationSourceSystem` copies it to pcfdlrm. |
| FR-4 | Field absent → handled as today. XHIBIT unchanged. |
| FR-5 | Tests for FR-1 – FR-4; existing tests and ITs stay green. |

## Acceptance criteria

- **AC-1:** GIVEN a LIBRA `case.json` whose `caseDetails` has a 7-character
  `firstCourtHearingLocation`, WHEN it is processed, THEN stagingdlrm and pcfdlrm receive it in
  `migrationSourceSystem` and not in `caseDetails`.
- **AC-2:** GIVEN a case without it, THEN the submission is handled as today.
- **AC-3:** GIVEN a value that is not 7 characters, THEN the submission is rejected by case validation.
- **AC-3a:** GIVEN a manifest whose `migrationSourceSystem` has the field, THEN it is rejected by manifest validation.
- **AC-4:** GIVEN an XHIBIT submission, THEN nothing changes.
