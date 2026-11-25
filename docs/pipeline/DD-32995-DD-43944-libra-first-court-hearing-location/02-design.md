# 02 — Design

- **Inputs:** `01-requirements.md`, [ADR-DD-43944](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/blob/team/libra4/docs/pipeline/adrs/ADR-DD-43944-libra-first-court-hearing-location.md). Q1 – Q5 answered in `01-requirements.md`.

## Change

| # | File | Change | FR |
|---|------|--------|----|
| C-1 | `stagingdlrm-azure-functions/src/main/resources/libra.case-submission.json` | Add `firstCourtHearingLocation` to `definitions.caseDetails`: `{"type": "string", "minLength": 7, "maxLength": 7}`. Not required. Remove it from the Function App `migrationSourceSystem.json` (added by the 2026-11-13 version). | FR-1 |
| C-2 | `stagingdlrm-domain/stagingdlrm-domain-value-schema/.../migrationSourceSystem.json` | Same property, with a description. | FR-2 |
| C-3 | `MigratedCaseConvertor.buildMigrationSourceSystem` | `.withFirstCourtHearingLocation(migrationSourceSystem.getFirstCourtHearingLocation())`. Needs the pcfdlrm half's schema (pcfdlrm `pcfdlrm-command-api` RAML version bump). | FR-3 |
| C-4 | `StagingDlrmCommandHelper.buildMigratedCaseJsonBuilder` | Move `caseDetails.firstCourtHearingLocation` into `migrationSourceSystem` and leave it out of `caseDetails` (canonical `caseDetails` is closed). | FR-1a |

## Options considered

| Option | Why / why not |
|---|---|
| **A. LIBRA sends it on `caseDetails`; Function App moves it into `migrationSourceSystem` (chosen — business change 2026-11-25)** | Business wants it in `caseDetails` of the extract; downstream contract stays in `migrationSourceSystem`. |
| B. LIBRA sends it in manifest `migrationSourceSystem` | 2026-11-13 version; superseded. |
| C. Keep it on `caseDetails` all the way | Needs canonical, pcfdlrm, core-domain and Progression changes again; no benefit. |

## Order of work

C-3 compiles only against a pcfdlrm build whose `migrated-migrationSourceSystem.json` has the field.
Full order (ADR): core-domain release → Progression `coredomain` bump deployed → pcfdlrm → this
story's C-3 (with a pcfdlrm version bump). C-1 and C-2 can go first on their own: canonical would then
accept and drop the value.

## Tests

- `JsonSchemaValidatorTest` (LIBRA case): field of length 7 accepted; length 6 and 8 rejected; absent accepted. (Manifest): field in `migrationSourceSystem` rejected.
- `StagingDlrmCommandHelperTest`: field moved from `caseDetails` into `migratedCase.migrationSourceSystem`.
- `MigratedCaseConvertorTest`: field copied to pcfdlrm `migrationSourceSystem`; absent → null.
