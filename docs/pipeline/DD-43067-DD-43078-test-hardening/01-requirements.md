# Requirements — LIBRA enabler: test hardening

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> Requirements altitude — nothing here prescribes a class layout. Implementation **tasks**,
> including the per-repo split, come from the design / story-writer stage.

## Story

**[DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) — Pin XHIBIT behaviour and make the
DLRM test suites LIBRA-extensible**

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Size | M |
| Repos | `cpp-context-stagingdlrm`, `cpp-context-prosecution-casefile-dlrm` |
| Depends on | nothing — no external blockers, can start immediately |
| Production changes | **none** — test, fixture and test-support code only |

### Summary (JIRA summary line)

`[LIBRA enabler] Harden stagingDLRM + PCFDLRM tests: exhaustive whole-payload XHIBIT unit coverage, representative XHIBIT-only ITs`

### User story

As a **developer about to relax `case-details.json` for LIBRA**,
I want **the stagingDLRM and PCFDLRM unit suites to assert complete payloads for XHIBIT across
every scenario that matters, the integration tests to prove the same journeys for XHIBIT only at
representative depth, and the source system to be a scenario parameter throughout**,
so that **removing schema constraints cannot silently change what XHIBIT sends downstream, and
LIBRA scenarios can later be added as scenario data rather than as new test classes**.

## Depth model

| Layer | Depth | Rationale |
|---|---|---|
| Unit / component | **Exhaustive.** Every scenario that matters: each field the relaxation will touch accepted *and* rejected, each rule path, each variant. | Fast, in `mvn test`, no environment — the right place for a scenario matrix. |
| Integration | **Representative.** Enough journeys to prove the wiring and that the payload crossing each service boundary is whole. No scenario matrix. | Needs Docker and a running environment, so enumerating variants there is expensive and slow on every build. |

## Scope

`cpp-context-stagingdlrm`:

- `stagingdlrm-azure-functions` — `EventGridTriggerJava`, `TimerTriggerJava`, `EventGridMonitor`
- `stagingdlrm-command/stagingdlrm-command-handler`
- `stagingdlrm-domain/stagingdlrm-domain-aggregate` — `MigratedCaseSubmissionAggregate`
- `stagingdlrm-event/stagingdlrm-event-processor` — `StagingDlrmEventProcessor`,
  `PcfDlrmEventProcessor`, `MigratedCaseConvertor`
- `stagingdlrm-integration-test` — representative depth only

`cpp-context-prosecution-casefile-dlrm`:

- `pcfdlrm-command-handler` — `MigratedCaseFileHandler`
- `pcfdlrm-domain-aggregate` — `MigratedCaseFileAggregate`
- `pcfdlrm-event-processor`
- Validation rules — `CcProsecutionValidationRuleProvider` and the rule sets it selects
- `pcfdlrm-integration-test` — representative depth only

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
- **FR5b — Cover the partial-officer-block rejection.** Five fields are `exists_mandatory` in
  Progression's payload schema — `policeOfficerRank`, `policeWorkerReferenceNumber`,
  `policeWorkerLocationCode`, officer `surname` and officer `address1`. They are mandatory *if the
  officer block is sent at all*, so a LIBRA case with a partial block is rejected downstream. Pin
  that as a rejection scenario when DD-43081 FR13 lands, rather than discovering it in an
  environment.
- **FR6 — Pin the three XHIBIT-only behaviours (PCFDLRM).** Each currently no-ops or is
  suppressed for non-XHIBIT sources, and each is a decision point once LIBRA arrives (analysis
  §3.4, §5 Q6). Assert current XHIBIT behaviour **and** current non-XHIBIT behaviour, so changing
  either is a visible, deliberate test change:
  1. `ExhibitFiileTypeValidationRule` — materials / Court Record Sheet file-type check; no-ops
     for any non-XHIBIT source.
  2. `MigratedCaseFileAggregate`'s hearing/defendant-matching check — condition computed for
     every case, problem surfaced only for XHIBIT.
  3. `ProsecutionCaseFileHelper.applyRuleToDefendantFields()` — defaults/normalises gender,
     language and ethnicity codes after a validation failure, XHIBIT only.
- **FR7 — Pin rule-set selection (PCFDLRM).** Assert which rule set
  `CcProsecutionValidationRuleProvider` selects for a given `initiationCode`. Today every migrated
  case lands in the generic default set because stagingDLRM forces `"O"`; once the enum is dropped,
  real codes will route into the existing `SUMMONS`/`REQUISITION`/`SJP` sets, and that change must
  be observable rather than incidental.
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
  - Every IT journey runs with `migrationSourceSystemName = XHIBIT`. **Both repos currently use
    LIBRA in their ITs.** In stagingDLRM three fixtures do —
    `stagingdlrm.receive-migrated-case-submission.json` (the **default/base** fixture),
    `-with-multiple-hearing.json`, `-without-materials.json` — so the base journey runs as LIBRA
    today. Each is re-pointed at XHIBIT or gains an XHIBIT equivalent; convert-vs-duplicate is a
    design decision, but LIBRA-only coverage of a journey is not an acceptable XHIBIT baseline.
  - Journeys kept at IT level — stagingDLRM: successful submission through to the pcfdlrm call;
    the terminal 4xx rejection with its outcome file; the processing-output/outcome-publication
    path. PCFDLRM: case file received and processed through to the public event; material
    addition. Field-level variants and the FR6 behaviours stay at unit level.
  - Boundary payloads are still asserted **whole** per FR2 — a thinner assertion would defeat the
    point of the layer.
- **NFR1 — No production code changes.** Any production defect found is raised as a separate
  ticket, not fixed here.
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
- **AC4** Given each behaviour in FR6, when the suites run, then both the XHIBIT and the
  non-XHIBIT path are asserted explicitly, not by omission.
- **AC5** Given an `initiationCode` value per FR7, when a migrated case is processed, then the
  test asserts which rule set was selected.
- **AC6** Given a deliberate experimental change that drops a field from an outbound payload,
  when the suites run, then at least one test fails. Demonstrated once at review; the experiment
  is not committed.
- **AC7** Given the IT suites, when they run, then no journey resolves `migrationSourceSystemName`
  to `LIBRA`, and the fixtures named in FR10 no longer provide LIBRA-only coverage.
- **AC8** Given `mvn clean install` in each repo, when it completes, then all unit suites pass, the
  ITs pass in their profile without a material runtime increase, and no production source file has
  changed.

## Out of scope

None of the following is part of this story:

- `cpp-apitests`, and any LIBRA scenario at either test layer.
- The schema relaxation itself.
- Creating the `MigratedCaseValidationRules` strategy or wiring `MigratedCaseSubmissionRejected`.
- Adding a source-system axis to PCFDLRM's rule provider.
- Wiring the orphaned `pcf-policeOfficerInCase.json` or the abandoned
  `getDlrmDefendantValidationRules()` stub.
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
- FR6 asserts absences ("no-ops for non-XHIBIT"), which is easy to write vacuously. Worth
  explicit review attention.
- **The LIBRA fixtures in both IT suites are a finding, not just a chore.** The stagingDLRM
  default IT journey has never run as XHIBIT. If re-pointing it changes the result, that is a real
  behavioural difference between the two source systems — surfaced before the relaxation rather
  than after — and should be raised immediately.

## Notes for the design stage

1. **Agree the scenario-DSL convention once, here.** Two developers in parallel repos will
   otherwise invent two dialects. Capture it as an ADR under `docs/pipeline/adrs/`: how a scenario
   is named, where fixtures live, how exclusions are declared.
2. **"Whole payload" needs a definition the team agrees on.** Suggested: compare the entire JSON
   of the payload under test, with every excluded path listed individually and a comment saying
   why. No wildcard or prefix exclusions.
3. **Resist scope creep at the IT layer.** FR10 caps the ITs deliberately. Once they are open, the
   temptation is to port unit scenarios into them — little gain, Docker runtime cost on every
   build.
4. **AC6 (the deliberate-break check) is the only real proof** that FR2 was achieved. Keep it as
   an explicit review step rather than folding it into a task.
5. **The per-repo split is the natural task boundary**, and the two halves are independent — no
   shared code, different repos — so they can run in parallel once the DSL convention in note 1 is
   agreed.
