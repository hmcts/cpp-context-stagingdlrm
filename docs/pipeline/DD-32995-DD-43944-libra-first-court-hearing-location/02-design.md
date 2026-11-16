# 02 — Design

- **Inputs:** `01-requirements.md`, [ADR-DD-43944](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/blob/team/libra4/docs/pipeline/adrs/ADR-DD-43944-libra-first-court-hearing-location.md). Q1 – Q5 answered in `01-requirements.md`.

## Change

| # | File | Change | FR |
|---|------|--------|----|
| C-1 | `stagingdlrm-azure-functions/src/main/resources/migrationSourceSystem.json` | Add `firstCourtHearingLocation`: `{"type": "string", "minLength": 7, "maxLength": 7}`. Not required. | FR-1 |
| C-2 | `stagingdlrm-domain/stagingdlrm-domain-value-schema/.../migrationSourceSystem.json` | Same property, with a description. | FR-2 |
| C-3 | `MigratedCaseConvertor.buildMigrationSourceSystem` | `.withFirstCourtHearingLocation(migrationSourceSystem.getFirstCourtHearingLocation())`. Needs the pcfdlrm half's schema (pcfdlrm `pcfdlrm-command-api` RAML version bump). | FR-3 |
| C-4 | none | `StagingDlrmCommandHelper` already copies `migrationSourceSystem` whole. | FR-1 |

## Options considered

| Option | Why / why not |
|---|---|
| **A. In `migrationSourceSystem` (chosen — requirement change)** | Developer's decision. |
| B. On `caseDetails` (original ticket wording) | Superseded. |
| C. Restrict the field to LIBRA at the gate | The manifest validator is shared; an optional field cannot affect XHIBIT. |

## Order of work

C-3 compiles only against a pcfdlrm build whose `migrated-migrationSourceSystem.json` has the field.
Full order (ADR): core-domain release → Progression `coredomain` bump deployed → pcfdlrm → this
story's C-3 (with a pcfdlrm version bump). C-1 and C-2 can go first on their own: canonical would then
accept and drop the value.

## Tests

- `JsonSchemaValidatorTest` (manifest): field of length 7 accepted; length 6 and 8 rejected; absent accepted.
- `StagingDlrmCommandHelperTest`: field copied into `migratedCase.migrationSourceSystem` (whole payload).
- `MigratedCaseConvertorTest`: field copied to pcfdlrm `migrationSourceSystem`; absent → null.
