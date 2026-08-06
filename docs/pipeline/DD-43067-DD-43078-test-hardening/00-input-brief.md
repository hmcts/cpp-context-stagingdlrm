# Input brief — LIBRA enabler: test hardening

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this
> directory.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) — stagingDLRM test hardening |
| Repo | `cpp-context-stagingdlrm` |
| Sibling story | [DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening) — the same hardening in `cpp-context-prosecution-casefile-dlrm` |

## The epic this story belongs to

**DD-43067 — LIBRA enabler.** Ingest magistrates' court case files from legacy system LIBRA
through the existing DLRM pipeline (Azure Blob → Function App → stagingDLRM → PCFDLRM →
Progression), reusing the XHIBIT path rather than forking it.

**Design decision already taken for the epic** (analysis §2): XHIBIT and LIBRA share **one**
stagingDLRM endpoint and **one** schema family. Source-system-specific behaviour is pluggable
strategies inside the shared path, not duplicated schemas, endpoints, or command/event types. The
rejected separate-schema alternative and the reasoning are in
[`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) §7.

**Repo in scope for this story:** `cpp-context-stagingdlrm`. Progression and
`cpp.platform.core.domain` are not expected to change (analysis §3.5).

## Why the PCFDLRM half is a separate story

The two halves of the hardening are **fully independent**: no shared code, no shared fixtures, no
ordering constraint, different repos, separate CI runs. Either can merge first and either can slip
a sprint without stranding the other. Under the team workflow's slice test that makes them two
stories, not one story spanning two repos — so DD-43078 (this story) keeps the stagingDLRM work and
[DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening)
carries PCFDLRM.

**One thing is genuinely shared**: the scenario-DSL and whole-payload assertion convention, so that
two developers working in parallel do not invent two dialects. It is recorded once, as
[ADR-001](../adrs/001-dlrm-scenario-test-dsl.md) in this repo, and linked from DD-43099 — never
copied. It must be approved before either story starts stage 5.

## This story's request

Harden the existing tests in **stagingDLRM** — the Function App, and the context: handler,
aggregate, event-processor, rules — so that:

1. Tests are written with **XHIBIT** as the source system, and **assertions cover whole
   payloads**, because the schema is being relaxed.
2. Tests can be **cleanly extended for LIBRA later** to add new scenarios — following a DSL
   framework like `HearingFinancialResultsAggregateNCESTest` in `cpp-context-results`, where
   needed.
3. **Integration tests cover XHIBIT exclusively, but not to the same extent as the unit tests** —
   unit tests need to cover every possible scenario.

**No production behaviour changes.** Test, fixture and test-support code only.

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Repo representation | The PCFDLRM work is its **own story** under the epic ([DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening)), not a task on this one — the two halves are independently deliverable. |
| Test scope | Unit/component **and** in-repo integration tests, at different depths (below). `cpp-apitests` out of scope. |
| Artefact layout | One pipeline directory per story, named `<epicKey>-<storyKey>-<slug>`, each self-contained. |
| Story independence | Stories under this epic are **independent** — this one carries no cross-story dependency and can be picked up on its own. |
| Shared convention | [ADR-001](../adrs/001-dlrm-scenario-test-dsl.md) lives in this repo and is linked from DD-43099, not copied; approved before either story starts stage 5. |

## Depth model — the two test layers are not held to the same bar

| Layer | Depth expected |
|---|---|
| Unit / component | **Exhaustive.** Every scenario that matters — each field the relaxation will touch accepted and rejected, each rule path, each source-system variant. |
| Integration | **Representative.** Enough journeys to prove the wiring and that the payload crossing each service boundary is whole. No scenario matrix. |

The asymmetry is about cost, not confidence: ITs need Docker and a running environment
(`./runIntegrationTests.sh`, or `mvn verify -P stagingdlrm-integration-test`), so they are the
wrong place to enumerate field-level variants.

## Why now — the driver

The schema relaxation LIBRA needs is wider than
[`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) §3.3 states. Per
[`libra-schema-impact.md`](../../analysis/libra-ingestion/libra-schema-impact.md) §1, LIBRA's
workbook supplies **none** of `dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator`
— all four unconditionally `required` today — **and** neither half of the
`anyOf: [dateOfCommittal | dateOfSending]` pair. So six constraints come off
`case-details.json`, plus the `initiationCode` enum.

That is the `caseDetails` slice. Across the whole payload the matrix now counts **17 `relax-*`
rows** — every one a check the shared schema stops performing for XHIBIT, and therefore something
this story's tests must pin. The mirror image is 8 `libra-rule-only` rows, where canonical is
already permissive and only LIBRA's rules tighten; those need no XHIBIT pin.

Every constraint removed is one the schema stops enforcing on **XHIBIT** too. Whatever the schema
no longer guarantees, the tests must. Tests asserting a handful of fields will keep passing while
the payload silently changes shape — the regression this story exists to prevent.

A schema rejection here is also **terminal, not transient** (4xx gets zero retries — analysis
§4), so a shape regression reaching production means manual resubmission, not automatic recovery.

This story has **no external blockers** — it needs no LIBRA sample and no cross-team decision, so
it can start immediately.

## What the current IT layer actually looks like

Checked, not assumed. Three IT classes — `ReceiveCaseFileSubmissionIT`,
`ReceiveErrorCaseSubmissionIT`, `CaseSubmissionProcessedIT` — with 11 XHIBIT fixtures and
**3 LIBRA fixtures**.

The three LIBRA fixtures are `stagingdlrm.receive-migrated-case-submission.json` (the
**default/base** fixture), `-with-multiple-hearing.json` and `-without-materials.json` — so the
base IT journey runs as LIBRA today and has never run as XHIBIT.

For contrast, PCFDLRM's base IT journey is already XHIBIT and only three of its 24 command fixtures
carry LIBRA, so DD-43099's equivalent work is smaller and carries no comparable canary.

## Terminology note

The request says `migrationSourceSystemId`. The actual schema fields are
**`migrationSourceSystemName`** (the enum, already `["LIBRA", "XHIBIT"]`) and
`migrationSourceSystemCaseIdentifier`. This story uses `migrationSourceSystemName`, since that is
what a test parameterises on.

## Reference pattern

`cpp-context-results`:

- `results-domain/results-domain-aggregate/src/test/java/uk/gov/moj/cpp/results/domain/aggregate/HearingFinancialResultsAggregateNCESTest.java`
- `.../HearingFinancialResultAggregateTestSteps.java` (the DSL itself, ~425 lines)

Shape of it, for design reference:

- Scenarios are `static Stream<Arguments>` methods — each row a human-readable label plus a
  fluent scenario object — consumed by `@ParameterizedTest(name = "{index} => {0}")` +
  `@MethodSource`. The test body is one line: `scenario.run(name, new Aggregate())`.
- A scenario is a sequence of named steps; each supplies an input event from a JSON fixture and
  declares expected outcomes.
- Assertions are **whole-payload JSON comparisons** against an expected fixture, with explicitly
  listed exclusions for non-deterministic values
  (`comparison().withPathsExcluded("materialId", "notificationId")`) and named parameter
  substitution — not field-by-field getters.

Adding a case is adding a row and two fixtures. That is the extensibility property requirement 2
asks for.

## Supporting analysis

Both regenerable from the data-schema workbook via `tools/schema-gen/`:

- [`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) — pipeline
  trace, per-system change plan, open questions, and the rejected alternative (§7).
- [`libra-schema-impact.md`](../../analysis/libra-ingestion/libra-schema-impact.md) — field-level
  impact across the func-app gate, the canonical schema, pcfdlrm and Progression (§2, with the
  matrix in `libra-schema-impact.csv`), and the downstream triage of the 44 LIBRA-added fields (§5).
