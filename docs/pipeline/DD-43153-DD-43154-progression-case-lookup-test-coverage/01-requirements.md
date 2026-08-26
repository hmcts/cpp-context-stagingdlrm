# Requirements — Progression Case-Existence Lookup Test Coverage

> Stage 1 artefact (requirements-analyst). Source: `00-input-brief.md`. Parent Jira
> ticket: [DD-43154](https://tools.hmcts.net/jira/browse/DD-43154). Filed retrospectively
> against already-committed code — see the input brief's framing note.

## Actors

| Actor | Description |
|-------|-------------|
| stagingdlrm developer | Maintains `SystemMapperService` / `StagingDlrmEventProcessor`; relies on this test suite to catch regressions in the Progression lookup branches |
| CI pipeline | Runs unit + integration tests on every PR/merge build; must have real coverage to be a meaningful gate |

## Functional requirements

- **FR1 — Split the standard-lookup unit test from the remap unit test.** The single
  parameterized `SystemMapperServiceTest` method (which selected between two assertion
  paths via an `expectedRemap` boolean and an `if/else`) is replaced by two independently
  readable tests: one `@ParameterizedTest` over the no-remap scenarios (no record, record
  missing `prosecutionCase`, record with no `caseStatus`, `ACTIVE`), and one plain
  `@Test` for the single `EJECTED`-remap scenario. Neither test body branches on the
  scenario.
- **FR2 — Integration coverage for "no record in Progression".** Add a WireMock stub
  (`ProgressionStub.stubProgressionProsecutionCaseNotFound`) returning HTTP 404 for the
  prosecution-case-details endpoint, a fixture case-submission JSON using a case
  reference not otherwise stubbed, and an IT test
  (`ReceiveCaseFileSubmissionIT.shouldForwardToPcfdlrmWhenCaseHasNoRecordInProgression`)
  asserting the submission is still forwarded to pcfdlrm using the existing
  system-id-mapper case ID (no remap, no new mapping).
- **FR3 — No production behaviour change.** This work only adds test coverage; it must
  not alter `SystemMapperService`, `StagingDlrmEventProcessor`, or any other main-source
  file.

## Non-functional requirements

| ID | Category | Requirement | Threshold |
|----|----------|-------------|-----------|
| NFR1 | Test coverage (repo hard rule) | Any `@Handles` entry point whose real behaviour changed for a new branch must have ≥1 integration test for that branch | `stagingdlrm.events.migrated-case-submission-received` → all 3 Progression-status branches covered |
| NFR2 | CI correctness | Full local IT suite (`./runIntegrationTests.sh`) must be green, unmodified/unweakened, after the new test is added | 0 failures/errors across all IT classes |
| NFR3 | Data handling | No real case data, URNs, or PII in new fixtures — synthetic values only | Zero tolerance (repo hard rule) |

## Acceptance criteria

### FR1
- AC1: `shouldResolveCaseExistenceForStandardLookup` is a `@ParameterizedTest` covering
  the 4 no-remap scenarios; its body contains no `if`/`else` on the scenario.
- AC2: `shouldRemapAndCreateNewMappingWhenCaseEjected` is a plain `@Test` with inlined
  `EJECTED` data; the now-unused `remappingScenarios` method source is removed.
- AC3: `mvn test -Dtest=SystemMapperServiceTest` passes, 8/8.

### FR2
- AC4: Given a case URN mapped in system-id-mapper but with no matching record in
  Progression (404), when a migrated-case-submission is received, then the submission is
  still forwarded to pcfdlrm (`stagingdlrm.events.migrated-case-submission-received`
  observed, `verifyReceiveCaseFileRequested` satisfied) using the existing case ID.
- AC5: `./runIntegrationTests.sh` passes with the new test included and no existing test
  weakened or skipped.

### FR3
- AC6: `git diff` for this change touches only `src/test/**` files (plus new test
  fixture resources) — no file under `src/main/**` is modified.

## Constraints

- Repo hard rule: integration tests for changed `@Handles` entry points must be added and
  green via `./runIntegrationTests.sh` (see root `CLAUDE.md`, "Hard rules").
- No PII, case data, or court reference numbers in artefacts, prompts, or fixtures (root
  `CLAUDE.md`, "Hard rules").

## Out of scope

- Hardening or verifying pcfdlrm's idempotency on `receive-migrated-case-file` for the
  Progression-read-model-lag scenario (review finding #1) — explicitly deferred to the
  business, per the input brief.
- Any change to `SystemMapperService`'s lookup/remap logic itself.

## Open questions

1. **Accepted risk, not actioned — Progression read-model lag could cause a duplicate
   resubmission to pcfdlrm.** Raised as review finding #1 on PR #40
   (`SystemMapperService.java:55`): treating "no `prosecutionCase` in Progression" as
   "case doesn't exist" conflates a genuinely-missing case with one still **in flight**
   — pcfdlrm already has it, but Progression's read model hasn't caught up yet. Both
   produce the same `Optional.empty()` from `getCaseStatus`, so on event
   redelivery/reprocessing during that lag window (or maybe DLQ, if redelivery is
   exhausted), `getCaseIdForPtiURN` reports
   `caseAlreadyProcessedAndExistsInProgression=false` and `StagingDlrmEventProcessor`
   re-sends `ReceiveMigratedCaseFile` to pcfdlrm for the same caseId. Net effect: pcfdlrm
   gets a second `receive-migrated-case-file` for a caseId it's already mid-processing
   (or has already finished processing) — nothing in this code path re-checks or waits;
   it relies entirely on pcfdlrm's own idempotency for that endpoint+caseId to absorb the
   duplicate. — Owner: business/pcfdlrm team — Due: TBD. Explicitly deferred per the user
   ("no need business already addresses it", see input brief §3) — **flagged here for
   visibility, not for action in this piece of work.** A one-line code comment marking
   this is also in place at `SystemMapperService.java:56`.

2. **Accepted risk, partially actioned — the EJECTED-remap path can still leave the
   original mapping orphaned on partial failure.** Found while reviewing
   `SystemMapperService.getCaseIdForPtiURN`'s EJECTED branch
   (`SystemMapperService.java:59-63`), confirmed by decompiling
   `DefaultSystemIdMapperClient` (`id-mapper-client-17.103.5.jar`):
   - ~~`systemIdMapperClient.remap(...)`'s return value (`Optional<SystemIdMapping>`) is
     never checked at the call site~~ — **fixed** in a later commit on this same branch
     (`dev/DD-43154-ittest-fix`): the call site now does
     `.orElseThrow(() -> new IllegalStateException(...))`, so a `404` on `remap` (e.g. a
     stale `mappingId`) now throws instead of being silently swallowed into
     `createNewMapping(ptiUrn)` as if the rename had succeeded.
   - That fix trades the silent-orphan failure mode for a different one, still unactioned:
     on a **persistently** failing `remap()` (the same stale `mappingId` on every
     attempt), the thrown exception rolls back the JMS transaction and the event
     redelivers; `findBy(ptiUrn, ...)` still finds the un-renamed original mapping each
     time, so `remap()` retries with the *exact same failing call* forever, with no
     fallback path.
   - Separately, if `remap()` succeeds but the subsequent `add()` (inside
     `createNewMapping` → `attemptAddMapping`) fails, the two calls are independent,
     non-transactional HTTP requests with no compensation — `remap()`'s rename is not
     undone. The resulting `IllegalStateException` (`UNABLE_TO_CREATE_MAPPING`) is
     uncaught by `StagingDlrmEventProcessor.handleMigratedCaseSubmissionReceived`, which
     runs in a container-managed JMS transaction with no override, so it rolls back and
     the event redelivers. On redelivery, `findBy(ptiUrn, ...)` now finds nothing (the
     mapping was already renamed to `ptiUrn + "_Ejected"`), so the retry takes the "no
     existing mapping" branch and calls `createNewMapping` again with a **fresh random
     UUID** each attempt — `remap()` is never retried in that branch either. If `add()`
     never succeeds within the JMS redelivery limit (Artemis default: 10 attempts, per
     finding #1's DLQ note), the event dead-letters and the case's `ptiUrn` is left with
     **no live system-id-mapper entry at all**.
   The remaining gap (both bullets above) is orthogonal to the Progression-lag risk in #1
   and is still not actioned. — Owner: business/system-id-mapper team — Due: TBD.
   **Flagged here for visibility, not for action in this piece of work.**
