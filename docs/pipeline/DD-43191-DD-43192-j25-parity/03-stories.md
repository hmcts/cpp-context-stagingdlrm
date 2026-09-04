# Implementation tasks — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 3 artefact. Source: [`02-design.md`](./02-design.md). Each task is independently committable
> and independently verifiable with a single `mvn -o test -pl <module> -Dtest=<class>` run. Sequenced
> per the requirements' own guidance (primaries first; cut from the back if the story must be cut).

## T1 — `stagingdlrm-domain-value-schema`: BC-13 test infrastructure and pins (primary)

- Add test-scope deps to `pom.xml`: `com.github.everit-org.json-schema:org.everit.json.schema`,
  `org.junit.jupiter:junit-jupiter-api`, `org.junit.jupiter:junit-jupiter-engine`.
- `ClasspathSchemaClient` — reads `META-INF/schema_catalog.json`, resolves `$ref`s by catalogued id, plus
  the one hand-added `common-core-domain` `definitions.json` mapping.
- `Bc13SchemaValidationParityTest` — `required`/`enum`/`anyOf`/`type` accept+reject pairs on
  `case-details.json`; the `durationMinutes` numeric-literal table on `migrated-hearing.json`; one
  parse-failure-vs-validation-failure pair.
- `SchemaCatalogGenerationParityTest` (BC-21) — schema-file-count == catalog-entry-count, computed both
  ways at test time.
- Acceptance: FR5, FR7 (BC-13 half), FR12 (catalog half), AC1, AC3.

## T2 — `stagingdlrm-azure-functions`: DLRM-01 pins (primary)

- Extend `JsonSchemaValidatorTest`: malformed JSON, array-payload rejection, duplicate-key resolution,
  `documentType` numeric-literal table (manifest schema, no `maximum`).
- Acceptance: FR6, FR7 (DLRM-01 half), AC1, AC3, AC6.

## T3 — `stagingdlrm-azure-functions`: BC-11 corrected pin

- Extend `StagingDlrmCommandHelperTest`: null `responseString` → `NullPointerException` from
  `generateErrorMigratedCaseSubmissionPayload`.
- No `ServiceLoader`/classpath-count test — superseded by ADR decision 8.
- Acceptance: FR8 (as corrected by design), AC1, AC5 (re-scoped: identity of the mechanism pinned, not a
  provider-resolution count).

## T4 — `stagingdlrm-command-api`: BC-03 + BC-20

- `AccessControlTest` — add the allow/deny pair for `stagingdlrm.receive-error-migrated-case-submission`.
- `Bc20RuleHarnessParityTest` — `KieBase("COMMAND_API").getKiePackages()` rule count == 2, by name.
- Acceptance: FR9, FR10, AC1, AC4.

## T5 — `stagingdlrm-azure-functions`: BC-12

- `Bc12RestEasyPackagingParityTest` — parse `pom.xml`, assert 4 `org.jboss.resteasy` deps, no `<scope>`.
- Acceptance: FR11, AC1.

## T6 — `stagingdlrm-command-api`: BC-21 (messaging-client half)

- `Bc21MessagingClientGenerationParityTest` — RAML-schema-count (`stagingdlrm-command-handler`'s
  `src/raml/json/schema/**`) == `@Handles`-method-count on the generated remote client, via reflection.
- Acceptance: FR12, AC1.

## T7 — `stagingdlrm-viewstore-liquibase`: BC-07

- `LiquibasePropertiesParityTest` — exact key set + values.
- Acceptance: FR13, AC1.

## T8 — `stagingdlrm-event-processor`: BC-08

- Javadoc annotation on `ObjectBuilder.buildMetaData` (test helper) naming BC-08; no new test.
- Acceptance: FR14, AC1.

## T9 — `docs/j25-parity-checklist.md`

- One row per Bucket A item (9) + Bucket B (4) + N/A (12), written after T1–T8 are green, carrying the
  exact command + result for each 🟢, the BC-11 correction, the BC-13 "format" gap, and the two
  uninstrumented BC-21 generator families.
- Acceptance: FR17, AC7, AC8, AC10.

## T10 — Full-reactor verification

- `mvn -o clean install -DskipITs` on every module that resolves offline (same known
  `stagingdlrm-event-processor`/RAML-artifact gap as the design's BC-21 note — verify it is still
  pre-existing via a clean `git stash`, don't assume last session's finding still holds).
- Acceptance: AC2, AC9.

## Out of scope for this task list (unchanged from requirements)

Any `src/main` change, any pom version bump, `cpp-context-prosecution-casefile-dlrm` (its own story),
executing IT-tier items to green.
