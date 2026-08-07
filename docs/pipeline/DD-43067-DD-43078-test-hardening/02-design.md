# Design — LIBRA enabler: test hardening

> Stage 2 artefact. Source: [`01-requirements.md`](./01-requirements.md).
> Split per the team workflow: **2a** cross-context impact (tech lead), **2b** inside the service
> (story owner). The shared convention this repo and DD-43099 both build against is
> [ADR-001](../adrs/001-dlrm-scenario-test-dsl.md) — not restated here.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) — stagingDLRM test hardening |
| Repo | `cpp-context-stagingdlrm` |
| Sibling story | [DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening) — PCFDLRM half, independently deliverable |
| Production changes | none (see [NFR1 reading](#nfr1-the-one-build-change)) |

---

## 2a — Cross-context impact

**No cross-context impact.** Test, fixture and test-support code only. Nothing changes in any
schema, RAML, event contract, JMS subscription, `system-id-mapper` interaction or Azure resource.
No event is added, renamed or re-routed; `public.pcfdlrm.migrated-case-file-processed` and the
`pcfdlrm.receive-migrated-case-file` REST contract are asserted, not altered.

Two things remain for the lead, and both are about how the work is *run*:

**This story and DD-43099 are independently deliverable.** They were originally scoped as one story
spanning both repos; splitting them restores the workflow's one-story-one-repo rule, which their
independence already satisfied — no shared code, no shared fixtures, no ordering constraint,
separate CI runs. Either can merge first, and either can slip a sprint without stranding the other.

**One shared contract, single-homed:** [ADR-001](../adrs/001-dlrm-scenario-test-dsl.md). It fixes
the scenario-row shape, the whole-payload comparison semantics, the fixture layout and the
source-system parameter mechanism, so two developers working in parallel do not diverge into two
dialects. It
lives in this repo and DD-43099 links it by URL rather than holding a copy — a second copy would
drift the moment the decision changed. It must be approved before *either* story starts stage 5,
exactly as the workflow's cross-repo rule requires.

Nothing is deployed by this story, so there is no deploy order to agree and stage 8 is a no-op
beyond confirming the build is green.

**`cpp-apitests` is out of scope.** The workflow asks for a third test scope when two stories change
a contract that spans them. Neither story changes a contract, and the requester scoped
`cpp-apitests` out explicitly. Recorded here so the omission is a decision, not a gap.

**MbD vs context service does not arise** — this is an existing CQRS/ES context service and no
pattern choice is being made.

---

## 2b — Design inside the service

### The shape of the problem

The suites fail FR2/FR3 in three distinct ways, and each needs a different remedy. Naming them
separately matters, because "add whole-payload assertions everywhere" is not the work.

| Failure mode | Where | Remedy |
|---|---|---|
| **Asserts nothing about the payload** — the subject is a `RETURNS_DEEP_STUBS` mock, so there is no payload to assert | `MigratedCaseSubmissionAggregateTest`, `StagingdlrmCommandHandlerTest` | Replace mocks with fixture-deserialised POJOs, then assert whole |
| **Asserts a handful of fields of a real payload** | `MigratedCaseConvertorTest`, `StagingDlrmEventProcessorTest`, the ITs | Keep the structure, swap selective getters for whole-payload comparison |
| **The constraint is not covered at all** — schema accept/reject has no unit-level home | the 17 `relax-*` rows | New schema-contract suite (below) |

The first is the largest gap. `MigratedCaseSubmissionAggregateTest:52` mocks the entire submission
with deep stubs and asserts `getSubmissionId()` round-trips — the aggregate could drop every other
field and the test would pass.

### Shared foundations (per ADR-001)

New test-scoped module `stagingdlrm-test-support` carrying `FixtureLoader` and
`WholePayloadMatcher`. Consumers: domain-aggregate, domain-value-schema, command-handler,
event-processor, azure-functions, integration-test.

(ADR-001 also specified a `Comparison` builder. It was implemented and then dropped before merge —
no call site needed it; see the appendix note in ADR-001.)

**Both classes are written out in full in ADR-001's appendix**, with the anchored-exclusion,
wildcard-rejection and unused-exclusion changes already applied. T1 is a copy-and-adjust-the-package
job — it needs no access to `cpp-context-results`, and must not substitute a Maven dependency on
`uk.gov.moj.cpp.results:test-utilities` (that artefact drags `results-domain-common`, an unrelated
context's domain module, onto the test classpath). The only new dependency is
`org.skyscreamer:jsonassert` at test scope, version-managed by `maven-common-bom` — this repo does
not currently have it anywhere.

**No step-sequencing layer** (ADR-001 §3). Counted across this repo's aggregate suite: 7 tests, 9
command invocations — only `shouldRaiseDuplicateMigratedCaseSubmissionReceived` (receive → record
output → receive) and `shouldRaiseCaseAlreadyProcessedAndExistsInProgressionEvents` (receive →
receiveCaseAlreadyProcessed) issue more than one. Two tests do not justify ~400 lines of
`Scenario`/`StepDef` infrastructure; they issue their commands as sequential calls with a
whole-payload assertion after each. Everything else is `@ParameterizedTest` + `@MethodSource` rows,
which is what actually delivers FR3 and AC2.

Two mechanisms already exist and are reused rather than rebuilt:

- **`test-utils-core` is already a test dependency of nearly every module**, and its
  `JsonSchemaValidationMatcher` (`isValidForSchema` / `isNotValidForSchema` /
  `failsValidationWithMessage`) runs **everit** — the same validator
  `microservice-framework/core`'s `SchemaCatalogAwareJsonSchemaValidator` uses in production. The
  FR5 pins need no new dependency and exercise the real validator.
- **`ObjectBuilder`** in `stagingdlrm-event-processor` already parameterises on
  `MigrationSourceSystemName`. It stays for tests that legitimately build POJOs without a fixture;
  it is not migrated wholesale. (PCFDLRM's equivalent does *not* parameterise — hardcoding the
  source system is DD-43099's largest single gap, and this repo does not share it.)

### The FR5 pin list, resolved

FR5 says to derive the list from `libra-schema-impact.csv` rather than from prose. Done — filtering
`change_type` on `relax-*` gives **17 rows**, which the two FR5 caveats reduce to a definite
scenario table:

| Group | Count | Rows | Scenario needed |
|---|---|---|---|
| **Pin accept + reject** | 8 constraints (9 rows) | `caseDetails.dateReceived`, `.receiptType`, `.receivingCourt`, `.retrialIndicator`, `.initiationCode` (enum `O`), the `anyOf[dateOfCommittal\|dateOfSending]` pair (2 rows, 1 constraint), `hearings[*].durationMinutes`, `defendants[*].offences[*].prosecutorOffenceId` | valid XHIBIT payload → accepted; same payload with the field removed / value out of enum → rejected, with the message pinned |
| **Pin as *staying*** (caveat a — DD-43081 FR9/FR10 decided not to relax) | 3 | `caseDetails.caseMarkers[*].markerTypeCode`, `defendants[*].individual.selfDefinedInformation.gender`, `defendants[*].offences[*].offenceDateCode` (`maximum: 6`) | reject scenario only — asserts the constraint is still enforced after DD-43081 |
| **No pin** (caveat b — beneath optional parents, tool over-reporting) | 5 | `hearings[*].weekCommencingDate.startDate`, `defendants[*].individual.personalInformation.address.address1`, and three under `parentGuardianInformation` (`.address.address1`, `.personalInformation.surname`, `.personalInformation.address.address1`) | none — nothing changes for them |

**11 pin scenarios**, of which 8 are accept+reject pairs and 3 are reject-only. That is the concrete
target for AC3, and it is small enough to review row by row.

Home for them (**confirmed at the gate 2026-08-06 — decision 3**): a new
`CaseDetailsSchemaContractTest` (and a defendants/hearings sibling if the class gets unwieldy) in
**`stagingdlrm-domain/stagingdlrm-domain-value-schema/src/test/java`** — the module that owns
`json/schema/case-details.json`. The pin lives with the thing it pins, so DD-43081's relaxation and
the test that must change with it land in the same module, the same PR and the same reviewer's diff
— which is the mechanism FR5 depends on, not merely a red build somewhere in CI.

This module currently has **no `src/test` and a single compile dependency** (`common-core-domain`),
so T5 also adds the test scaffolding: JUnit 5, hamcrest, `test-utils-core` and `stagingdlrm-test-support`,
all at test scope. That is a one-off cost and is accepted. One thing for T5 to verify rather than
assume: `catalog-generation-plugin` runs in this module at `generate-sources`, so the generated
catalog should already be on the test classpath for `JsonSchemaValidationMatcher` to resolve schemas
through — confirm before building the suite around it.

The reject assertions use `failsValidationWithMessage` rather than bare `isNotValidForSchema`: a
4xx is terminal for the uploader (analysis §4), and the message is what lands in the outcome file,
so it is part of the contract.

### Component by component

| Component | Now | Design | Scenario rows? |
|---|---|---|---|
| `stagingdlrm-domain-value-schema` | no tests | **New** `*SchemaContractTest` — the 11 pins above, `@ParameterizedTest` over (name, fixture, expected outcome, message fragment) | rows |
| `MigratedCaseSubmissionAggregateTest` (236 lines) | deep-stub mocks; asserts `getSubmissionId()` only | Fixture-deserialised `MigratedCaseSubmission`; **rows adopted** — 7 scenarios as `@MethodSource` data. The two multi-command journeys (duplicate: receive → record output → receive again → `Duplicated…` + `…Processed`; and `receiveCaseAlreadyProcessed`) issue sequential calls, asserting whole payloads between them. Whole-payload assert each appended domain event | **yes** |
| `StagingdlrmCommandHandlerTest` (253 lines) | mock aggregate, `withJsonPath` spot checks | Keep the mocked aggregate (the handler's job is enveloping, not domain logic) but assert the appended `JsonEnvelope` payload **whole** against a fixture | no |
| `MigratedCaseConvertorTest` (123 lines) | 5 tests, field-by-field getters on 4 fields | **Highest-value FR2 target** — the convertor is an explicit field-by-field mapping and the likeliest silent-drop site. `@ParameterizedTest` table of (name, input fixture, expected `MigratedCaseDetails` fixture, exclusions). No steps, so no chaining | rows |
| `StagingDlrmEventProcessorTest` (286 lines) | `ArgumentCaptor` + 3–5 `assertEquals` per test | Keep captors; assert the captured `ReceiveMigratedCaseFile` payload and the `Outcome` **whole** (FR2 names both). Covers FR8's success and failure outcome payloads | no |
| `PcfDlrmEventProcessorTest` (59 lines) | thin | Whole-payload assert on the `record-submission-processing-output` command it raises | no |
| `stagingdlrm-azure-functions` (FR9) | `JsonSchemaValidatorTest` uses inline Java text blocks; `EventGridTriggerJavaTest`, `TimerTriggerJavaTest` | Move text-block payloads to fixtures under `json/<component-slug>/`; parameterise `dlrm_folder_name` / `dlrm_batch_name` path validation so a comma-separated folder list is a row. Pin the gate's actual contract: **which of the 8 `caseDetails` properties must be present, that their declared JSON type is enforced, and that unknown fields pass** (`additionalProperties: true`, no patterns/lengths/enums, never descends into `defendants`/`hearings`/`offences`) | rows |
| `stagingdlrm-integration-test` (FR10) | 3 IT classes, 11 XHIBIT + **3 LIBRA** fixtures | See below | no |

**FR8 caveat.** `MigratedCaseValidationRules` and `MigratedCaseSubmissionRejected` do not exist in
the codebase. Per FR8 this story does not create them; it covers the outcome path that exists —
`EventGridService.sendEventToEventGrid(Outcome)` for success and for
`ErrorMigratedCaseSubmissionReceived` — and the omission is recorded at closure.

### Integration tests (FR10)

Three fixtures carry `"migrationSourceSystemName": "LIBRA"`:
`stagingdlrm.receive-migrated-case-submission.json` (the **base** fixture, used by
`shouldAcceptCaseFileSubmissionRequest` and the duplicate journey),
`-with-multiple-hearing.json`, `-without-materials.json`.

**Decision: convert, do not duplicate.** Re-point all three at XHIBIT. Duplicating would leave
LIBRA journeys running at IT level, which FR10 and AC7 forbid, and would add Docker runtime for
coverage the story says belongs at unit level.

`shouldAcceptCaseFileSubmissionRequest:105` currently asserts `stringList.add("LIBRA")` against the
forwarded message — that assertion becomes `XHIBIT` and is the canary: **if re-pointing changes any
other result, that is a real behavioural difference between the two source systems and gets raised
immediately** (requirements *Risks and notes*). The base journey has never run as XHIBIT.

Boundary payloads asserted whole per FR2: the forwarded `pcfdlrm.receive-migrated-case-file` message
and the outcome. The current field-by-field comparison (`commandMigrateCaseDetails.get("caseId")`,
`commonDefendantMatches(...)`) is replaced, not supplemented.

Journeys kept, per FR10 — successful submission through to the pcfdlrm call; the terminal 4xx
rejection with its outcome file; the processing-output/outcome-publication path. No new journeys.

### FR5a is deferred within the story, not dropped

It depends on a DD-43081 decision that has not landed — FR14a's resolution for `informant` /
`writtenChargePostingDate` / `prosecutorCosts`, whose canonical parents are
`additionalProperties: false`. The scenario rows are authored as soon as it lands. **Gate decision
2 (2026-08-06): if it has not landed when this story reaches stage 5, the rows carry to DD-43081 and
this story does not wait.** This is the only part of the story with an external dependency.

Carrying is cheap and low-risk, for three reasons established at the gate:

- **DD-43081 works in this repo** (`Repos: cpp-context-stagingdlrm, cpp-context-prosecution-casefile-dlrm`)
  and its own header records that it *extends DD-43078's test suites and DSL*. The carried rows land
  in the `CaseDetailsSchemaContractTest` T5 creates, using the same fixtures and the same DSL — not
  a handoff into unfamiliar code.
- **DD-43081 already owns the accept-half.** Its **AC6a** reads: *"Given a LIBRA case carrying
  `informant`, `writtenChargePostingDate` and `prosecutorCosts`, when submitted, then it is
  accepted — proving FR14a is resolved and a closed-object rejection cannot happen on a real
  payload."* Carrying adds no work there; it declines to duplicate work that story already has.
- **FR14a blocks DD-43081 itself** — it is named in that story's own *Blocked by* list. Waiting here
  would import a blocker that gates the downstream story regardless.

**One half is not covered by AC6a, and must not be lost.** FR5a asks for two things: the LIBRA
payload is accepted (= AC6a), *and* an XHIBIT scenario proving nothing about XHIBIT's handling
changed. DD-43081's AC8 covers the deliberate FR9/FR10 constraint divergences, not XHIBIT's handling
of the tier-5 accept-only fields. So the carry is recorded **specifically**, not generally:

> FR5a's LIBRA-accept half is covered by DD-43081 AC6a. Its XHIBIT-unchanged half has **no
> corresponding AC in DD-43081** and needs one adding before that story closes.

**Action still outstanding:** that gap has been recorded here but *not* on the DD-43081 side. Either
add the AC to `docs/pipeline/DD-43067-DD-43081-schema-enablement/01-requirements.md` or raise it
with that story's owner. Until one of those happens, the gap is single-sided and only visible from
this document — which is the failure mode FR5 exists to prevent, one level up.

---

## FR → design traceability

| Req | Where it is satisfied |
|---|---|
| FR1 | ADR-001 §4 — `withSourceSystem(...)` mandatory per step; fixtures use `{{SOURCE_SYSTEM}}` |
| FR2 | ADR-001 §1 — STRICT compare, anchored enumerated exclusions, unused exclusion fails |
| FR3 | ADR-001 §3–4 — scenario rows + fixture parameter; no `if` on source system in a test body |
| FR4 | Scenario rows throughout; step chaining deferred per ADR-001 §3 (2 multi-command tests) |
| FR5 | 11 pins in `stagingdlrm-domain-value-schema`, derived from the CSV |
| FR5a | Deferred pending DD-43081 FR14a; carried to DD-43081 if unresolved at stage 5 (gate decision Q2) |
| FR5b, FR6, FR7 | Moved to DD-43099 (PCFDLRM) — see its design |
| FR8 | `StagingDlrmEventProcessorTest` outcome payloads; missing wiring recorded, not built |
| FR9 | func-app fixtures + path-validation rows; presence-and-declared-type contract pinned |
| FR10 | 3 fixtures converted; journeys unchanged |
| NFR1 | No `src/main` change in any module — see below |
| NFR2 | Whole-payload comparison is in-memory JSONassert, no I/O beyond fixture reads; ITs gain no journeys |

---

## NFR1: the one build change

One new Maven module, `stagingdlrm-test-support`, consumed only at `<scope>test</scope>`. No
`src/main` file in any deployable module changes, no WAR gains a dependency, no runtime artefact is
affected. AC8's "no production source file has changed" holds literally.

It is nonetheless a reactor change, so it was put to the gate explicitly. **Approved 2026-08-06 —
adding `stagingdlrm-test-support` is within the remit of NFR1** (see gate decision 1). The ADR-001
fallback — duplicate the support classes per module and re-scope FR3/AC2 from per-repo to
per-module — is therefore not taken, and FR3/AC2 stay per-repo. DD-43099 makes the same call
independently for its own repo.

---

## Findings raised during design

1. **`cpp-context-results`' exclusion matching is a substring match.** `JsonMatcher` compiles each
   exclusion to a regex and matches with `find()`, so `"materialId"` excludes every path containing
   that token at any depth. Copying it verbatim would have imported exactly the over-broad-wildcard
   hole FR2 forbids. ADR-001 §1 anchors it. This is a finding about the *reference pattern*, not
   about `cpp-context-results` — no change is proposed there.
2. **The aggregate suite asserts essentially nothing.** Deep-stub mocks mean
   `MigratedCaseSubmissionAggregate` could drop every field but `submissionId` and all five tests
   would pass. This is the single largest gap in this repo and is sequenced first among the
   assertion tasks in stage 5 (T3).
3. **`MigratedCaseValidationRules` / `MigratedCaseSubmissionRejected` do not exist.** FR8 anticipated
   this. Confirmed by search; recorded so closure does not read as an omission.
4. **This repo is in worse shape than PCFDLRM**, contrary to the epic-level assumption that the two
   halves are comparable. DD-43099's aggregate suite asserts real emitted-event content across 39
   methods; this one asserts a round-tripped ID. Sizing should reflect that this half is the larger
   of the two.

## Gate decisions

Numbered as originally raised, so the numbers stay stable as questions are answered.

- **Q1 — Does the new test-support module clear NFR1?** **Resolved 2026-08-06 — yes.** Adding
   `stagingdlrm-test-support` is within the remit of NFR1: it is consumed only at test scope, no
   `src/main` file changes and no deployable artefact is affected. T1 proceeds as designed and the
   ADR-001 per-module duplication fallback is closed off. FR3/AC2 remain per-repo.
- **Q2 — FR5a timing.** **Resolved 2026-08-06 — carry to DD-43081, do not wait.** If DD-43081 FR14a
  has not landed when this story reaches stage 5, the FR5a rows are delivered as part of DD-43081
  and DD-43078 closes without them. Rationale and the one gap this leaves (the XHIBIT-unchanged
  half, uncovered by DD-43081 AC6a) are in [FR5a is deferred within the story, not
  dropped](#fr5a-is-deferred-within-the-story-not-dropped).
- **Q3 — Does the FR5 pin list belong in `stagingdlrm-domain-value-schema`?** **Resolved 2026-08-06 —
   yes.** Locality is the point: DD-43081 edits `case-details.json` in that module, so the pin that
   goes red is in the same diff rather than two modules away in CI. The alternative
   (`stagingdlrm-command-api`, which already has test infrastructure) was rejected for that reason.
   The module gains its first tests and its first test-scope dependencies as part of T5; accepted as
   a one-off cost.

**No open questions remain. Stage 2 is gated; ADR-001 is approved and stage 5 may start once T1's
prerequisites are in place.**

One follow-up is outstanding but does not block this story: adding the XHIBIT-unchanged AC to
DD-43081 (see FR5a above).

## Notes for stage 3 (story-writer)

Numbered in execution order.

| # | Task | Depends on |
|---|---|---|
| T1 | `stagingdlrm-test-support` module — `FixtureLoader`, `WholePayloadMatcher` | ADR-001 approved |
| T2 | Func-app: fixtures, path rows, presence-and-declared-type contract (FR9) | T1 |
| T3 | Aggregate + command-handler: de-mock, scenario rows, whole-payload | T1 |
| T4 | Convertor + event-processors: whole-payload at the component seam | T1 |
| T5 | Schema-contract suite — the 11 FR5 pins | T1 |
| T6 | ITs: convert 3 LIBRA fixtures, whole boundary payloads (FR10) | T1 |

**The func-app work is taken first**, at the story owner's request. It is the most self-contained
task in the story — `stagingdlrm-azure-functions` shares no test code or fixtures with the
WildFly-side modules, so it can be finished and reviewed without touching anything the other tasks
edit. T1 still has to land ahead of it: its fixtures are loaded through `FixtureLoader` and asserted
with `WholePayloadMatcher`, so it cannot literally be first. If ADR-001 approval slips, the part of
T2 that can start without T1 is lifting the inline text blocks in `JsonSchemaValidatorTest` out to
`json/<component-slug>/` fixture files.

T3 closes the largest gap (finding 2). T6 carries the behavioural-difference canary, but T4 retires
most of it first: the convertor and event processor produce the same `ReceiveMigratedCaseFile`
payload the IT asserts at the WireMock boundary, so a difference between the two source systems
surfaces in a unit run with a field-level diff before the IT layer is touched.

**T4 and T6 were swapped after this design was gated** — this table reflects the revised order; see
`03-stories.md` § Sequence for the reasoning. The task *content* is unchanged from what was gated,
apart from T4's scope reduction recorded there.

DD-43099's tasks run in parallel in its own repo. The only sync point is ADR-001.

**AC6 stays a review step, not a task.** Deliberately dropping a field from an outbound payload and
demonstrating a failing test is the only real proof FR2 landed; folding it into a task turns it into
a checkbox. It is demonstrated once at the stage 6 gate and not committed.
