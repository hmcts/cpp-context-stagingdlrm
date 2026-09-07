# Implementation tasks — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 3 artefact. Source: [`02-design.md`](./02-design.md). Each task is independently committable
> and independently verifiable with a single `mvn -o test -pl <module> -Dtest=<class>` run.

## T1 — `stagingdlrm-domain-value-schema`: BC-13 test infrastructure and pins (primary)
- Test-scope deps: everit-json-schema, junit-jupiter-api/engine.
- `ClasspathSchemaClient` + `Bc13SchemaValidationParityTest` (required/enum/anyOf/type + numeric table + parse-vs-validation) + `SchemaCatalogGenerationParityTest` (BC-21 catalog half).
- Acceptance: FR5, FR7 (BC-13 half), FR12 (catalog half), AC1, AC3.

## T2 — `stagingdlrm-azure-functions`: DLRM-01 pins (primary)
- Extend `JsonSchemaValidatorTest`: malformed JSON, array-payload rejection, duplicate-key resolution, `documentType` numeric-literal table.
- Acceptance: FR6, FR7 (DLRM-01 half), AC1, AC3, AC6.

## T3 — `stagingdlrm-azure-functions`: BC-11 corrected pin
- Extend `StagingDlrmCommandHelperTest`: null `responseString` → `NullPointerException`.
- Acceptance: FR8, AC1, AC5.

## T4 — `stagingdlrm-command-api`: BC-03 + BC-20
- `AccessControlTest` allow/deny pair for `stagingdlrm.receive-error-migrated-case-submission`; `AccessControlRuleCountTest` (named to match the fleet-wide convention, not a BC-numbered name).
- Acceptance: FR9, FR10, AC1, AC4.

## T5 — `stagingdlrm-azure-functions`: BC-12
- `Bc12RestEasyPackagingParityTest`.
- Acceptance: FR11, AC1.

## T6 — `stagingdlrm-command-api`: BC-21 (messaging-client half)
- `Bc21MessagingClientGenerationParityTest`.
- Acceptance: FR12, AC1.

## T7 — `stagingdlrm-viewstore-liquibase`: BC-07 — no unit-level pin, recorded as a check
- A `Properties.load()` unit test was authored, then removed - it doesn't exercise Liquibase's own
  property-validation logic, only that the file has 3 keys (true on both J17 and J25 regardless of
  Liquibase's version). The changelog it points at is also empty (a separate, pre-existing fact).
  Recorded in `docs/j25-parity-checklist.md` as a Bucket-B-style check (⚪) with the reasoning, not a
  unit test - a meaningful pin needs Liquibase itself to run, which is IT-tier.
- Acceptance: FR13 revised (see `01-requirements.md`), AC1.

## T8 — BC-08: record only, no code touched
- No change to `stagingdlrm-event-processor` at all - `ObjectBuilder.buildMetaData`'s `ZonedDateTime` is
  never serialized through Jackson anywhere in this repo, so there's no incidental coverage to annotate
  and no test to write. Recorded directly in `docs/j25-parity-checklist.md`.
- Acceptance: FR14, AC1.

## T9 — ADR decision 8 (already present on this branch)
- Verify both DLRM repos' copies of the parity-method ADR still carry decision 8 and are byte-identical.

## T10 — `docs/j25-parity-checklist.md`
- One row per Bucket A (9) + Bucket B (4) + N/A (12), exact command + result per 🟢.
- Acceptance: FR17, AC7, AC8, AC10.

## T11 — Full-reactor verification
- `mvn -o clean install -DskipITs` on every module that resolves offline; confirm the known `stagingdlrm-event-processor` gap is pre-existing via `git stash`.
- Acceptance: AC2, AC9.

## Out of scope
Any `src/main` change, any pom version bump, `cpp-context-prosecution-casefile-dlrm` (its own story), executing IT-tier items to green.
