# Requirements — LIBRA enabler: test hardening

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> Requirements altitude — nothing here prescribes a class layout. Implementation **tasks** come
> from the design / story-writer stage.
>
> **Scoped to `cpp-context-stagingdlrm`.** The PCFDLRM half is
> [DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening).
> FR5b, FR6, FR7 and AC4/AC5 moved there; their numbers are left as gaps rather than renumbered,
> so existing references stay valid.

## Story

**[DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) — Pin XHIBIT behaviour and make the
stagingDLRM test suites LIBRA-extensible**

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Size | M |
| Repo | `cpp-context-stagingdlrm` |
| Depends on | ADR-001 approved before stage 5. No other blocker — can start immediately |
| Sibling story | [DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening) — same hardening in PCFDLRM, independently deliverable |
| Production changes | **none** — test, fixture and test-support code only |

### Summary (JIRA summary line)

`[LIBRA enabler] Harden stagingDLRM tests: exhaustive whole-payload XHIBIT unit coverage, schema constraint pins, representative XHIBIT-only ITs`

### User story

As a **developer about to relax `case-details.json` for LIBRA**,
I want **the stagingDLRM unit suites to assert complete payloads for XHIBIT across every scenario
that matters, the integration tests to prove the same journeys for XHIBIT only at representative
depth, and the source system to be a scenario parameter throughout**,
so that **removing schema constraints cannot silently change what XHIBIT sends downstream, and
LIBRA scenarios can later be added as scenario data rather than as new test classes**.

## Depth model

| Layer | Depth | Rationale |
|---|---|---|
| Unit / component | **Exhaustive.** Every scenario that matters: each field the relaxation will touch accepted *and* rejected, each rule path, each variant. | Fast, in `mvn test`, no environment — the right place for a scenario matrix. |
| Integration | **Representative.** Enough journeys to prove the wiring and that the payload crossing each service boundary is whole. No scenario matrix. | Needs Docker and a running environment, so enumerating variants there is expensive and slow on every build. |

## Scope

- `stagingdlrm-azure-functions` — `EventGridTriggerJava`, `TimerTriggerJava`, `EventGridMonitor`
- `stagingdlrm-command/stagingdlrm-command-handler`
- `stagingdlrm-domain/stagingdlrm-domain-aggregate` — `MigratedCaseSubmissionAggregate`
- `stagingdlrm-domain/stagingdlrm-domain-value-schema` — the canonical schemas the relaxation touches
- `stagingdlrm-event/stagingdlrm-event-processor` — `StagingDlrmEventProcessor`,
  `PcfDlrmEventProcessor`, `MigratedCaseConvertor`
- `stagingdlrm-integration-test` — representative depth only

## Requirements

- **FR1 — XHIBIT is the explicit baseline.** Every scenario states `migrationSourceSystemName`
  explicitly rather than relying on a fixture default, and the baseline value is `XHIBIT`. No
  scenario may pass because the field happened to be absent or defaulted.
- **FR2 — Assertions cover whole payloads.** For each command accepted, domain event appended,
  and outbound payload produced, the expected result is asserted as a **complete payload**
  compared against a fixture, not a selection of fields. Non-deterministic values (generated
  UUIDs, timestamps) are excluded by an **explicit, enumerated** list, so an accidental new or
  dropped field cannot slip through an over-broad wildcard. Applies to: stagingDLRM's pcfdlrm REST
  payload and EventGrid outcome; PCFDLRM's `public.pcfdlrm.migrated-case-file-processed` event and
  the payload it builds for Progression.
- **FR3 — Source system is a scenario parameter.** The suites are structured so a source system is
  data, not control flow. Adding a source system later must not require a parallel test class, a
  copied fixture tree, or an `if` on source system inside a test.
- **FR4 — Adopt a scenario DSL where it earns its place.** Suites with more than a couple of
  multi-step or multi-variant cases adopt a scenario-stream DSL in the spirit of
  `HearingFinancialResultsAggregateNCESTest` / `HearingFinancialResultAggregateTestSteps`
  (`cpp-context-results` — shape described in `00-input-brief.md`). Simple single-assertion tests
  stay as they are; the DSL is a means to FR2 and FR3, not a target.
- **FR5 — Pin the constraints the relaxation will touch (stagingDLRM).** XHIBIT coverage asserting
  current behaviour for each affected field, **including the rejection path for each**, since a
  4xx rejection is terminal and its outcome file is part of the contract: `initiationCode`
  (currently `enum: ["O"]`), `receivingCourt`, `dateReceived`, `receiptType`, `retrialIndicator`,
  and the `anyOf: [dateOfCommittal | dateOfSending]` pair.
  **Derive the full list from the matrix rather than this paragraph:** filter
  `libra-schema-impact.csv` on `change_type` starting `relax-` — 17 rows, each with the guard
  sentence in `change_detail`. Those 17 are exactly the XHIBIT behaviours that lose their schema
  guarantee, so they are the pin list; DD-43081's FR5 is the same set expressed as validation rules.
  **Two caveats.** (a) `caseMarkers[].markerTypeCode`, `selfDefinedInformation.gender` and
  `offences[].offenceDateCode` read `relax-*` in the CSV but DD-43081 FR9/FR10 decided **not** to
  relax them — pin current behaviour and expect it to stay. (b) 5 further `relax-required` rows are
  tool over-reporting: they sit beneath objects that are themselves optional
  (`hearings[].weekCommencingDate.startDate`, `personalInformation.address.address1`, three under
  `parentGuardianInformation`). Nothing changes for them, so no pin is needed.
- **FR5a — Cover the fields canonical must newly *accept* without mapping onward.** DD-43081 FR14a
  resolves three LIBRA fields (`informant`, `writtenChargePostingDate`, `prosecutorCosts`) whose
  canonical parents are `additionalProperties: false`. Whichever resolution is chosen, the suites
  need a scenario proving a payload carrying them is **accepted**, and an XHIBIT scenario proving
  nothing about XHIBIT's handling changed. This is the one relaxation-adjacent case where the
  failure mode is acceptance, not rejection.
- **FR5b — moved to [DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening) FR8.**
  The partial-officer-block rejection is enforced against Progression's payload schema, so it is
  PCFDLRM's to pin.
- **FR6 — moved to DD-43099 FR5.** The three XHIBIT-only behaviours
  (`ExhibitFiileTypeValidationRule`, the aggregate's hearing/defendant-matching check,
  `ProsecutionCaseFileHelper.applyRuleToDefendantFields()`) all live in PCFDLRM.
- **FR7 — moved to DD-43099 FR6.** Rule-set selection by `initiationCode` is
  `CcProsecutionValidationRuleProvider`, in PCFDLRM. Note the *cause* is here: once this story's
  FR5 pin on the `initiationCode` enum is relaxed by DD-43081, real codes reach PCFDLRM for the
  first time.
- **FR8 — Cover the outcome path (stagingDLRM).** The EventGrid outcome is the uploader's only
  feedback, so success **and** failure outcome payloads are asserted whole. If the
  `MigratedCaseValidationRules` extension point and `MigratedCaseSubmissionRejected` wiring do not
  yet exist, this story does **not** create them — it covers what exists, and that is noted at
  closure.
- **FR9 — Function App path and schema handling.** `dlrm_folder_name` / `dlrm_batch_name`
  validation and the local-schema validation path are covered for XHIBIT such that a later
  comma-separated folder list and source-system-keyed schema selection are scenario additions,
  not a rewrite.
  Note the gate is a **presence check only** — its `caseDetails` declares 8 properties, all
  `required`, and carries no patterns, lengths or enums at all, with `additionalProperties: true`.
  So the pinnable behaviour there is *which fields must be present* and *that unknown fields pass*,
  not constraint enforcement. It never descends into `defendants`, `hearings` or `offences`.
- **FR10 — Integration tests cover XHIBIT exclusively, at representative depth.** The IT layer
  proves the wiring and boundary payloads; it does **not** replicate the unit matrix.
  - Every IT journey runs with `migrationSourceSystemName = XHIBIT`. Three fixtures currently do
    not — `stagingdlrm.receive-migrated-case-submission.json` (the **default/base** fixture),
    `-with-multiple-hearing.json`, `-without-materials.json` — so the base journey runs as LIBRA
    today. Each is re-pointed at XHIBIT or gains an XHIBIT equivalent; convert-vs-duplicate is a
    design decision, but LIBRA-only coverage of a journey is not an acceptable XHIBIT baseline.
  - Journeys kept at IT level: successful submission through to the pcfdlrm call; the terminal 4xx
    rejection with its outcome file; the processing-output/outcome-publication path. Field-level
    variants stay at unit level.
  - Boundary payloads are still asserted **whole** per FR2 — a thinner assertion would defeat the
    point of the layer.
- **NFR1 — No production code changes.** Any production defect found is raised as a separate
  ticket, not fixed here. A new **test-scoped** module is permitted (see ADR-001 §2) since it
  changes no `src/main` file and no deployable artefact.
- **NFR2 — Runtime stays acceptable, per layer.** Unit suites stay in the normal `mvn test` run;
  whole-payload comparison must not push them into a separate profile. ITs stay in their existing
  profile and must not become materially slower — the constraint that keeps FR10 representative.

## Acceptance criteria

- **AC1** Given the hardened unit suites, when they run, then every scenario asserts at least one
  complete payload against a fixture, with any exclusions individually listed.
- **AC2** Given a developer adds a scenario for a different source system, when they do so, then
  the change is confined to scenario data plus fixtures — no new test class, no change to a test
  method body.
- **AC3** Given each field in FR5, when the suites run, then both the accepted and the rejected
  case are covered for XHIBIT.
- **AC4** — moved to DD-43099 AC4 (with FR6).
- **AC5** — moved to DD-43099 AC5 (with FR7).
- **AC6** Given a deliberate experimental change that drops a field from an outbound payload,
  when the suites run, then at least one test fails. Demonstrated once at review; the experiment
  is not committed.
- **AC7** Given the IT suites, when they run, then no journey resolves `migrationSourceSystemName`
  to `LIBRA`, and the fixtures named in FR10 no longer provide LIBRA-only coverage.
- **AC8** Given `mvn clean install`, when it completes, then all unit suites pass, the ITs pass in
  their profile without a material runtime increase, and no production source file has changed.

## Out of scope

None of the following is part of this story:

- `cpp-apitests`, and any LIBRA scenario at either test layer.
- The schema relaxation itself (DD-43081).
- Anything in `cpp-context-prosecution-casefile-dlrm` — that is
  [DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening).
- Creating the `MigratedCaseValidationRules` strategy or wiring `MigratedCaseSubmissionRejected`.
- `tools/reconciliation/`.
- Turning the ITs into a scenario matrix.

## Risks and notes

- The aggregate and command-handler are hard-typed to generated POJOs, so fixture-based
  whole-payload assertions must work with generated types rather than around them.
- `MigratedCaseConvertor` is an explicit field-by-field mapping — the likeliest place for a silent
  field drop, and the highest-value target for FR2.
- Defendant UUIDs are minted in the command-handler then **discarded and regenerated** in the
  event-processor (analysis §4). Exclusion lists must handle that without excluding so much that
  the assertion stops meaning anything.
- **The LIBRA fixtures in this IT suite are a finding, not just a chore.** The default IT journey
  has never run as XHIBIT. If re-pointing it changes the result, that is a real behavioural
  difference between the two source systems — surfaced before the relaxation rather than after —
  and should be raised immediately, on the epic rather than on this story alone.

## Notes for the design stage

1. **The scenario-DSL convention is settled** — [ADR-001](../adrs/001-dlrm-scenario-test-dsl.md),
   authored at stage 2 of this story and shared with DD-43099. It fixes how a scenario is named,
   where fixtures live, and how exclusions are declared. Do not re-derive it; DD-43099 links to it
   rather than holding a copy.
2. **"Whole payload" is defined in ADR-001 §1**: compare the entire JSON of the payload under
   test, with every excluded path listed individually and a comment saying why. No wildcard or
   prefix exclusions, and an exclusion that never matches fails the test.
3. **Resist scope creep at the IT layer.** FR10 caps the ITs deliberately. Once they are open, the
   temptation is to port unit scenarios into them — little gain, Docker runtime cost on every
   build.
4. **AC6 (the deliberate-break check) is the only real proof** that FR2 was achieved. Keep it as
   an explicit review step rather than folding it into a task.
5. **DD-43099 runs in parallel.** No shared code and no ordering constraint, so the only sync point
   is ADR-001 being approved before either reaches stage 5.
