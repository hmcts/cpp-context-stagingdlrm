# Input Brief — Progression Case-Existence Lookup Test Coverage

**Source:** Direct request from Gopal Saha (branch owner), interactive session, 2026-08-21.
Run **retrospectively** through the `hmcts-sdlc-orchestrator` pipeline at the user's
request ("use sdlc lets see what you come out of it") — the code below was already
written, reviewed, and merged into the branch *before* this pipeline run was requested.
This artefact documents that work in pipeline form rather than having driven it up front.

## 1. Jira ticket DD-43154 — original problem statement (authoritative source)

Taken directly from the ticket by the user — this is the actual root cause behind the fix
already merged into this branch (see `git log`: "Fix false 'case already exists' error for
migrated cases missing from Progression"), predating this pipeline run:

> Pilot identified system ID mapper entries for cases created as a result of receiving
> CPS documents where the case was subsequently filtered out and therefore not present in
> Progression.
>
> Case exist check applied to the DLRM Xhibit case ingestion pipeline needs to query
> Progression to determine case existence to avoid returning a case exists error to DLRM
> with the current implementation only looking at system ID mapper.

This is a **permanent** "no record in Progression" case, distinct from the in-flight/lag
scenario raised during PR review (§3, §4 below): a system-id-mapper entry was created
earlier from an incoming CPS document, the case was then filtered out upstream and will
**never** land in Progression. The pre-fix bug treated "mapping exists in system-id-mapper"
as sufficient to conclude "case exists," wrongly returning a case-already-exists error to
DLRM for exactly this case. The fix queries Progression itself rather than relying on
system-id-mapper presence alone — see `SystemMapperService.getCaseIdForPtiURN`.

## 2. Original ask (this pipeline run's actual scope — test coverage for that fix)

> `hmcts.com/hmcts/cpp-context-stagingdlrm/pull/40` review

Followed by, once review findings came back:

> Split into Two Separate Parameterized Tests (Best Practice) ... then we avoid if then
> else
>
> may be also add IT test

## 3. Clarifying Q&A (as it happened, interactively — not upfront)

**Q (implicit, from review): does the "Progression has no record of the case" branch of
`StagingDlrmEventProcessor.handleMigratedCaseSubmissionReceived` have integration
coverage?**
A: No — `ProgressionStub` only stubbed `ACTIVE`/`EJECTED` (HTTP 200) responses; nothing
stubbed a 404/no-record case. Confirmed by reading `ProgressionService.java` — a null
`Requester` payload becomes `Optional.empty()`, no exception path.

**Q: Should the remap test (`EJECTED` scenario) stay parameterized even though it now
carries only one scenario, for symmetry with the standard-lookup test?**
A ("no need to have arguments scenario just take into the test as its only 1"): No —
demoted to a plain `@Test` with inlined data. Only parameterize where there's more than
one case.

**Q: Should the theoretical duplicate-resubmission risk (finding #1 from the review —
Progression read-model lag could cause a re-send to pcfdlrm for a case it already
processed) be actioned in this piece of work?**
A ("no need business already addresses it"): No — pcfdlrm's idempotency on
`receive-migrated-case-file` is treated as the existing mitigation. Out of scope here.

## 4. Verified current state (codebase, 2026-08-21)

- `SystemMapperService.getCaseIdForPtiURN` (main/.../service/SystemMapperService.java)
  already had three branches after the DD-43154 fix landed: no record in Progression →
  `exists=false`, record present with non-`EJECTED` status (or missing `caseStatus`) →
  `exists=true`, `EJECTED` → remap + new mapping.
- `SystemMapperServiceTest` tested all three branches through one
  `@ParameterizedTest` with an `expectedRemap` flag driving an `if/expectedRemap` branch
  inside the test body.
- `ReceiveCaseFileSubmissionIT` had tests for `ACTIVE` and `EJECTED` via `ProgressionStub`,
  but none for "no record at all" — the exact branch this ticket's fix added handling for.
- `ProgressionStub` had no 404/not-found stub method.

## 5. Out of scope

- Any change to `SystemMapperService`'s production logic — this work is test-coverage
  only, no behaviour change.
- Investigating or hardening pcfdlrm's idempotency for the redelivery/lag scenario
  (review finding #1) — explicitly deferred to the business per §3 above.
- Re-running the full local IT suite is required by the repo's hard rule for changed
  `@Handles` entry points and was done as part of Stage 7 (see `docs/pipeline` note in
  README once filed) — not repeated as part of this documentation pass.
