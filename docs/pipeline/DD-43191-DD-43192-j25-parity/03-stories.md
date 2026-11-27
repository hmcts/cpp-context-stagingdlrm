# Implementation tasks — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 3 artefact. Source: [`02-design.md`](./02-design.md). Each task is independently committable
> and independently verifiable with a single `mvn -o test -pl <module> -Dtest=<class>` run.

## T1 — `stagingdlrm-domain-value-schema`: no change (BC-13 and BC-21's catalog half both withdrawn)
- BC-13: `ClasspathSchemaClient` + `Bc13SchemaValidationParityTest` were built and run green on J17
  (2026-09-04), then removed (2026-09-07) - the schema files aren't changing during the J25 upgrade, so
  there's nothing live for a strictness test to catch (see `docs/j25-parity-checklist.md`'s BC-13 note).
- BC-21's `catalog-generation-plugin` half: same reasoning, same outcome - not instrumented (see the
  BC-21 note).
- Module ends this story exactly as it started: zero Java, no test-scope pom dependencies.
- Acceptance: FR5 (revised), AC1.

## T2 — `stagingdlrm-azure-functions`: DLRM-01 — built, verified, withdrawn
- `JsonSchemaValidatorTest` was extended with malformed JSON, array-payload rejection, duplicate-key
  resolution and the `documentType` numeric-literal table; ran green on J17 (2026-09-04), then reverted
  whole-file to its pre-story state (2026-09-07) on direct instruction ("this is not part of the java 25
  upgrades") - the seam is real and code-verified but is no longer pinned by any test in this repo. See
  `docs/j25-parity-checklist.md`'s DLRM-01 note.
- Acceptance: FR6 (revised), FR7 (withdrawn), AC1, AC3 (withdrawn), AC6 (withdrawn).

## T3 — `stagingdlrm-azure-functions`: BC-11 — built, verified, withdrawn
- `StagingDlrmCommandHelperTest` was extended with a null-`responseString` → `NullPointerException` test;
  ran green on J17 (2026-09-04), then reverted whole-file to its pre-story state (2026-09-07), same
  instruction as T2 - distinct reasoning, though: BC-11's corrected finding is "Refuted / parity", so this
  was never really a J25-divergence candidate. See the checklist's BC-11 note.
- Acceptance: FR8 (revised), AC1, AC5 (withdrawn).

## T4 — `stagingdlrm-command-api`: BC-03 (kept) + BC-20 (built, verified, withdrawn)
- `AccessControlTest` allow/deny pair for `stagingdlrm.receive-error-migrated-case-submission` - this
  survived every review pass and is the *only* 🟢 this story leaves behind anywhere in the repo.
- `AccessControlRuleCountTest` (named to match the fleet-wide convention, not a BC-numbered name) was
  built and ran green on J17 (2026-09-07), then withdrawn the same day: it called
  `getKieClasspathContainer()` directly, bypassing `BaseDroolsAccessControlTest` (the harness
  `AccessControlTest` actually extends) entirely - so it could not detect BC-20's confirmed risk even in
  principle, whether or not `access-control-test-utils` is ever bumped to the defective version. See
  `docs/j25-parity-checklist.md`'s BC-20 note.
- Acceptance: FR9, FR10 (revised), AC1, AC4 (revised).

## T5 — `stagingdlrm-azure-functions`: BC-12 - built, verified, withdrawn by decision
- `Bc12RestEasyPackagingParityTest` was built and ran green on J17, then removed on direct instruction -
  not because the risk was found absent (it's real: `StagingDlrmCommandHelper` genuinely needs the
  bundled RESTEasy artifacts at runtime), but as a deliberate scope call. The upgrade-mechanics ADR's
  decision 5 is now the only safeguard against this regression - see `docs/j25-parity-checklist.md`'s
  BC-12 note.
- Acceptance: FR11 (revised), AC1.

## T6 — `stagingdlrm-command-api`: BC-21 (messaging-client half) — built, verified, withdrawn
- `Bc21MessagingClientGenerationParityTest` was built and ran green on J17 (2026-09-04) - it asserted a
  true fact (RAML-schema-count == `@Handles`-method-count, 4 == 4). Removed 2026-09-07 after re-verifying
  its own stated premise: unlike `catalog-generation-plugin`, decompiling this generator's full
  dependency chain found zero use of `org.reflections` anywhere in it. The generator reads the
  command-handler's RAML artifact via an explicit Maven dependency, not a classpath scan, so the
  `reflections` 0.9.10→0.10.2 risk this test was framed around was never real for it. See
  `docs/j25-parity-checklist.md`'s BC-21 note.
- Acceptance: FR12 (revised), AC1.

## T7 — `stagingdlrm-viewstore-liquibase`: BC-07 — no test, no viewstore
- No test and no pom change. This repo has no viewstore: zero Java files in
  `stagingdlrm-viewstore-persistence`, `stagingdlrm-event-listener` and `stagingdlrm-query-api`, no
  entity classes in `persistence.xml`, no changesets in the changelog. A key-set pin was authored and
  run green, then removed - there is no database to migrate.
- Risk recorded in `docs/j25-parity-checklist.md` instead: the jar still runs at container startup and
  Liquibase 5 rejects `liquibase.hub.mode` at config-parse time. Remedy is deletion under FR18.
- Acceptance: FR13, AC1.

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
