# Requirements — LIBRA enabler: stagingDLRM schema enablement

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> Requirements altitude — nothing here prescribes a class layout. Implementation **tasks** come
> from the design / story-writer stage.
>
> **Scoped to `cpp-context-stagingdlrm`, excluding `stagingdlrm-azure-functions`.** The Function App
> half is [DD-43086](https://tools.hmcts.net/jira/browse/DD-43086); all PCFDLRM work is a separate
> pipeline in `cpp-context-prosecution-casefile-dlrm`.
>
> **Revised against `schema-diff_3.html`** (LIBRA 0.13 vs XHIBIT 0.12), reconciled with the **live**
> canonical schema. LIBRA 0.13 now aligns far more closely to XHIBIT than earlier drafts: `initiationCode`
> is `["O"]` on both sides, `durationMinutes` / `prosecutorOffenceId` are required on both, and
> plea/verdict/allocationDecision use the UUID identifier on both. Two cells in the diff's XHIBIT column
> contradict the live schema — `migrationSourceSystem` (live requires both fields; the diff says neither)
> and the `caseDetails` `anyOf` `dateOfCommittal|dateOfSending` (live has it; the diff dropped it). These
> are `tools/schema-gen` regeneration bugs in `dlrm-xhibit-0.12.json`; this document follows the live
> schema and they are noted for a tooling fix.

## Story

**[DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) — Make the stagingDLRM canonical schema
accept LIBRA without weakening XHIBIT**

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Size | L |
| Repo | `cpp-context-stagingdlrm` |
| Depends on | [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) — **delivered**. Provides the XHIBIT whole-payload regression baseline this story must not move |
| Blocks | Function App LIBRA gate (DD-43086) consumes the canonical schema this story produces |
| Partially gated by | PCFDLRM release + `pcfdlrm.version` bump — see FR14 |
| Production changes | schema, domain event, aggregate, event-processor (converter only), reconciliation SQL/Python. **No command-handler change** — see FR12a |

### Summary (JIRA summary line)

`[LIBRA enabler] stagingDLRM schema enablement: relax 5 constraints for LIBRA, add 14 LIBRA fields (0.13.1), re-impose XHIBIT rules in code, wire the rejection path`

### User story

As a **migration engineer submitting LIBRA magistrates' court case files**,
I want **the stagingDLRM canonical schema to accept a valid LIBRA payload and carry its fields
through to PCFDLRM**,
so that **LIBRA cases migrate through the existing DLRM pipeline** — and as the **team owning
XHIBIT in production**, I want **every constraint removed from the shared schema re-imposed as an
XHIBIT rule**, so that **XHIBIT's behaviour is provably unchanged**.

## Scope

- `stagingdlrm-domain/stagingdlrm-domain-value-schema` — the canonical schema family
- `stagingdlrm-domain/stagingdlrm-domain-event` — `stagingdlrm.events.migrated-case-submission-rejected`
- `stagingdlrm-domain/stagingdlrm-domain-aggregate` — `MigratedCaseSubmissionAggregate` and the new
  validation rule engine
- `stagingdlrm-event/stagingdlrm-event-processor` — `MigratedCaseConvertor` only
- `stagingdlrm-command/stagingdlrm-command-handler` — **unchanged**, listed so its exclusion is
  explicit rather than assumed
- `stagingdlrm-test-support` and the unit/IT suites — extended, not duplicated
- `tools/reconciliation/` — `stagingdlrm-report.sql` and `summary-report.py` (FR21). Outside the
  Maven build, so not covered by stage 7 CI

## Requirements

### A. Relax what LIBRA cannot satisfy

- **FR1 — Relax the 5 constraints a valid LIBRA payload fails.** In the shared canonical schema,
  not a LIBRA fork. All five sit on `caseDetails`, which is unconditionally present in every LIBRA
  payload (required on `migratedCase`), so each really does fire and really does block:
  - **4 unconditional `required`** — `caseDetails`: `dateReceived`, `receiptType`,
    `receivingCourt`, `retrialIndicator`. LIBRA supplies none of the four.
  - **The `anyOf` combinator** on `caseDetails` requiring one of `dateOfCommittal` / `dateOfSending`
    — LIBRA supplies neither, so the branch has to go. (The diff artefact drops this `anyOf`; the
    **live** schema still has it, so the relaxation stands — see the header note.)
  - **What is *not* relaxed, against earlier drafts.** `hearings[*].durationMinutes` and
    `defendants[*].offences[*].prosecutorOffenceId` are now required on **both** sides in LIBRA 0.13,
    so LIBRA satisfies them and no relaxation is needed. `caseDetails.initiationCode` is `enum: ["O"]`
    on both sides — **no widening**, so the whole initiation-code thread (schema change, allowed-values
    rules, reference-data decision) is gone. `gender`, `individual.selfDefinedInformation` and the
    plea/verdict/allocationDecision identifiers are canonical-required but LIBRA-optional inside
    **optional containers** — kept strict under FR2a, not relaxed.
- **FR2 — For a field already in the canonical schema, only its required/optional status may
  change.** No `maxLength`, `minLength`, `pattern`, `minimum`, `maximum`, `type` or `enum` on an
  existing field is touched — oucode lengths, date patterns and value ranges all stay exactly as
  they are. The `anyOf` combinator counts as required-ness, since its branches are pure
  `{"required": […]}`. FR1 is an exhaustive list, not a category; anything not named there is
  unchanged. Where LIBRA's stated constraint is *tighter* than canonical, it belongs in the LIBRA
  rules (FR12g), never in the schema. Confirmed against the two schema artefacts: LIBRA 0.13 and
  XHIBIT 0.12 differ **only** in `required` arrays — there are no `type`/`maxLength`/`enum`/`pattern`
  divergences on any shared field, so FR2 holds with no exception. In particular `initiationCode` is
  `enum: ["O"]` on both sides and is not changed by this story.
- **FR2a — An object may be absent; if it is present, its fields keep their strictness.** Container
  optionality is how the schema accommodates data a source system does not hold. Weakening the
  fields *inside* an optional object buys nothing — the object can simply be omitted — and costs the
  guarantee that a container, once sent, is well-formed. **Eleven constraints canonical requires and
  LIBRA 0.13 leaves optional stay exactly as they are on this basis** — each sits in a container that
  LIBRA may omit, so LIBRA either omits the container or sends it well-formed:

  | Kept strict | Container | Why it never fires when the container is absent |
  |---|---|---|
  | `…personalInformation.address.address1` | `address` | optional — `personal-information.json` requires only `surname` |
  | `…parentGuardianInformation.address.address1` | `parentGuardianInformation` | optional — `individual.json` requires only `personalInformation`, `selfDefinedInformation` |
  | `…parentGuardianInformation.personalInformation.address.address1` | as above | optional |
  | `…parentGuardianInformation.personalInformation.surname` | as above | optional |
  | `hearings[*].weekCommencingDate.startDate` | `weekCommencingDate` | optional — not in `migrated-hearing.json`'s `required` |
  | `caseDetails.caseMarkers[*].markerTypeCode` | `caseMarkers` | required within `case-marker.json`; the marker object itself is optional in canonical |
  | `defendants[*].individual.selfDefinedInformation` | `individual` | optional on `migrated-defendant.json` |
  | `…individual.selfDefinedInformation.gender` | `selfDefinedInformation` | optional in LIBRA — see the container above |
  | `…offences[*].plea.id` | `plea` | optional on `migrated-offence.json` |
  | `…offences[*].verdict.id` | `verdict` | optional on `migrated-offence.json` |
  | `…offences[*].allocationDecision.motReasonId` | `allocationDecision` | optional on `migrated-offence.json` |

  The last five are the `schema-diff_3` reconciliation call (kept strict, not relaxed): canonical
  requires them, LIBRA 0.13 does not, but each is inside an optional object, so LIBRA must send a
  well-formed container or none. **If the LIBRA extract genuinely sends a present-but-incomplete
  container** — a `plea` without an `id`, an `individual` without `selfDefinedInformation`, or a
  `caseMarkers` entry without `markerTypeCode` — that field returns as a schema decision, exactly as
  `markerTypeCode` did. All six such points are routed to the extract team under FR19/R5.
  Consequences worth stating: no schema `$ref`'d from more than one parent is modified, so no
  definition needs splitting; and `weekCommencingDate.startDate` staying mandatory means the
  `LocalDate.parse(null)` hazard never becomes reachable.
- **FR3 — No new schema file, `$id` namespace, endpoint, command or event type.** One schema family
  serves both source systems (epic design decision, analysis §2). A LIBRA-specific `$id` would
  generate a parallel Java package and force a parallel aggregate path.
- **FR4 — Where the workbook is looser than canonical, canonical wins.** A blank or `TBC` Format
  cell in workbook V0.13 is a gap in the workbook, not a requirement to loosen the schema. Adopt a
  workbook constraint only where it is *stricter*. The three affected fields —
  `hearings[*].hearingType`, `defendants[*].offences[*].arrestDate`,
  `…personalInformation.observedEthnicity` — keep their canonical constraints and go to the workbook
  owner under FR19.

### B. Add the LIBRA fields

Selection rule: **add what has a home in Progression; leave what is ambiguous or has no home.**
LIBRA 0.13 adds 38 fields the canonical schema does not have; 35 are declared, 3 are not.
(Earlier drafts counted 44/38 — LIBRA 0.13 dropped `summonsCode`, `writtenChargePostingDate`,
`organisationTelephoneNumber` and the three `backDuty*` fields, and now carries `emailAddress1/2`
on both sides rather than LIBRA-only.)

> **Updated for LIBRA 0.13.1** (`schema-diff.html`): 0.13.1 no longer sends `officerInCase` (14
> fields: 11 Group B + 3 Group C) or `offence.convictionDate` (1 Group B). With those gone, the
> declared total is **14** — Group A 11, Group B 1 (`numPreviousConvictions`), Group C 2
> (`informant`, `prosecutorCosts`). FR8–FR10 below are updated; FR9 is void.

- **FR5 — Declare the 11 fields that flow end to end today, and map them (Group A).** A counterpart
  exists in both PCFDLRM and Progression's `courtReferral.json` closure, so these need changes in
  this repo alone:
  `defendants[*]` `driverNumber`, `nationalInsuranceNumber`, `occupation`, `defendantOccupationCode`,
  `licenseCode`; `defendants[*].offences[*]` `statementOfFacts`, `statementOfFactsWelsh`,
  `vehicleCode`, `vehicleMake`, `vehicleRegistrationMark`;
  `…individual.selfDefinedInformation.additionalNationality`.
  (`caseDetails.summonsCode` was Group A in earlier drafts; LIBRA 0.13 no longer carries it.)
- **FR6 — Declare fields at PCFDLRM's nesting level, following the existing XHIBIT convention.**
  This is not a new rule — it is how the pipeline already works. Canonical is a strict **subset of
  PCFDLRM at every level**: comparing the two schema sets, no canonical property sits at a different
  level from its PCFDLRM counterpart, and `MigratedCaseConvertor` **renames but never re-nests**
  (`forename` → `firstName`, `surname` → `lastName`). PCFDLRM already holds `driverNumber`,
  `nationalInsuranceNumber` and `driverLicenceCode` on `individual`, `occupation` and
  `occupationCode` on `personalInformation`. The LIBRA fields are declared in the same places, so the
  converter stays a level-preserving copy — **with one exception**: the offence vehicle fields
  (`vehicleCode`, `vehicleMake`, `vehicleRegistrationMark`) are declared **flat** on
  `migrated-offence.json` (the 0.13.1 payload shape), and the T4 converter re-nests `vehicleCode` into
  PCFDLRM's `vehicleRelatedOffence` object. No canonical `vehicle-related-offence.json` and no PCFDLRM
  schema change — this supersedes the earlier "payload nests `vehicleCode`" reading (ADR-003 §2).
  **Consequence for the payload:** `migrated-defendant.json` is `additionalProperties: false`, so
  the LIBRA `case.json` must nest these fields to match. The workbook's flat Defendant section is a
  spreadsheet layout, not the payload contract — see FR19/R5 and the DD-43086 gate schema.
- **FR7 — Reuse existing definitions rather than re-declaring types.** `nationalInsuranceNumber` in
  particular already has a pattern defined in `pcf-definitions.json` that nothing references.
- **FR8 — Declare the Group B field Progression models but PCFDLRM does not.** Schema only — the
  converter mapping is FR14. **Under LIBRA 0.13.1 this is one field:**
  `defendants[*].numPreviousConvictions`. (Earlier drafts had 19: the 11 `officerInCase` fields, its
  6 `address` fields and `offences[*].convictionDate` — all gone from 0.13.1, `officerInCase` and
  `convictionDate` no longer being sent. `organisationTelephoneNumber` had already dropped in 0.13.)
- **FR9 — *(void under LIBRA 0.13.1)*.** Was: `officerInCase` is a new container on `migratedCase`
  and must be declared. 0.13.1 no longer sends `officerInCase`, so no container is declared and
  `migratedCase` is unchanged. Re-instate only if a later LIBRA version re-introduces the block.
- **FR10 — Declare 2 fields as accepted-but-unmapped (Group C).** `caseDetails.informant`;
  `defendants[*].prosecutorCosts`. Nothing downstream models them, but each sits in a **closed**
  canonical object that LIBRA populates, so ignoring them is not available. Declared optional,
  documented as unmapped, and deliberately not propagated. (Earlier drafts had 5 — the three
  `officerInCase` fields `dxAddress`, `forename3`, `uniquePropertyReferenceNumber` are gone from
  0.13.1; `writtenChargePostingDate` had already dropped in 0.13.)
- **FR11 — Every added field is optional.** No addition may make an XHIBIT payload invalid.

### C. Restore enforcement in code, per source system

- **FR12 — Validation runs inside `MigratedCaseSubmissionAggregate`, via a statically-initialised
  rule engine.** The aggregate owns the decision because the aggregate is what appends events, and
  it already makes exactly this kind of decision on the duplicate-submission branch. Follow the
  established CPP precedent: `HearingFinancialResultsAggregate.updateFinancialResults()` in
  `cpp-context-results` (line 188) calling `ResultNotificationRuleEngine` — a plain class, rules
  instantiated in code, invoked directly from the aggregate. **No CDI and no injection**, which is
  what makes an engine usable from an aggregate the container does not manage. Consequences that
  are requirements, not implementation detail:
  - **FR12a — `stagingdlrm-command-handler` is unchanged.** No rule resolution, no validation, no
    new branch. It hands the payload to the aggregate exactly as it does today.
  - **FR12b — Dispatch is a statically-initialised
    `Map<MigrationSourceSystemName, List<rule>>` held by the engine.** The engine selects the list
    for the payload's source system and invokes those rules; **rules carry no `appliesTo` method**
    and do not know which source system they serve. This departs from the `cpp-context-results`
    precedent, which puts dispatch in each rule — the map keeps dispatch in one readable place and
    makes each source system's full rule set inspectable at a glance. A rule needed by both source
    systems is listed under both keys.
    `migrationSourceSystemName` is a required enum of `["LIBRA","XHIBIT"]` compiling to a Java enum,
    so an unrecognised source system cannot reach the aggregate and no fallback needs defining. A
    missing map key would still be a programming error, not a runtime input case.
    Because dispatch is external to the rule, **one rule class can serve both source systems as two
    differently-configured instances** — e.g. `InitiationCodeValidationRule.withAllowedValues(…)`
    registered under `XHIBIT` with its code set and under `LIBRA` with `C, J, Q, S`. Immutable
    construction-time configuration is not per-submission state, so this stays within FR12c.
  - **FR12c — Rules must be stateless and thread-safe.** A `static final` map shares one instance of
    each rule across every aggregate instance and every thread, so a rule may hold no per-submission
    state. Being static, the map is also outside the aggregate's instance state, so it cannot join
    the serialized snapshot — the aggregate is `Serializable`, and rules must stay out of it.
  - **FR12d — Rules return validation errors, not events.** A deliberate departure from the
    `cpp-context-results` precedent, whose rules return events directly. The aggregate assembles the
    rejection events, because it — not the rules — owns `submissionId`, `caseUrn`, `azureLocation`
    and the outcome-description contract.
  - **FR12e — The duplicate check keeps its early return and runs first.** A payload that is both a
    duplicate and invalid is reported as a duplicate, exactly as today. Accepted consequence: since
    `sendEventToGrid` suppresses EventGrid for duplicates, such a payload produces **no outcome file
    at all** — unchanged behaviour, but now reachable by a second route.
  - **FR12f — XHIBIT rules re-impose all 5 relaxations from FR1.** One rule per relaxed constraint:
    four presence rules (`dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator`) and one
    at-least-one-of rule (`dateOfCommittal` / `dateOfSending`). Net behavioural change for XHIBIT must
    be zero. No `initiationCode` rule — the field is unchanged, so nothing was relaxed to re-impose.
  - **FR12g — LIBRA rules enforce the 5 constraints LIBRA states and the shared schema cannot** —
    all presence rules for fields LIBRA requires and canonical leaves optional:
    `hearings[*].dateOfHearing`, `hearings[*].timeOfHearing`, `hearings[*].courtRoomId`,
    `caseDetails.caseMarkers`, and `defendants[*].address`. No length rules survive — LIBRA 0.13 and
    canonical carry identical `maxLength`/`pattern` on every shared field (FR2), so the earlier
    `forename`, `individualAliases[*]` and `migrationSourceSystemCaseIdentifier` rules are gone, as is
    the `initiationCode` allowed-values rule (no widening). **Note:** LIBRA's `parentGuardianInformation`
    branches require `gender` + `personalInformation` (person) and `organisationName` (organisation)
    where canonical does not; because `parentGuardianInformation` is an optional `oneOf` container,
    this is deferred to the extract team under FR19/R5 rather than added as a rule.
  - **FR12h — LIBRA rule *content* is revisable without structural change.** It derives from
    workbook V0.13 with no real sample to validate against, so it will move.
- **FR13 — On validation failure the aggregate raises `MigratedCaseSubmissionRejected` +
  `MigratedCaseSubmissionProcessed(processingIsSuccessful = false)`**, mirroring the duplicate
  branch, and raises **neither** `MigratedCaseSubmissionReceived` nor any pcfdlrm-bound command.
  `MigratedCaseSubmissionReceived` is the forwarding trigger — `StagingDlrmEventProcessor:76`
  resolves a case id via `system-id-mapper` and POSTs to pcfdlrm unconditionally on it — so raising
  it would send the invalid case downstream. The duplicate branch avoids this the same way, by
  raising the distinct `DuplicatedMigratedCaseSubmissionReceived`, which no processor handles.
  - **FR13a — No new `@Handles` is required.** The existing
    `@Handles("stagingdlrm.events.migrated-case-submission-processed")` already calls
    `sendEventToGrid`, so emitting the pair delivers the outcome file by the current path.
  - **FR13b — `azureLocation` must be read from the command payload, not from replayed aggregate
    state.** The aggregate's `azureLocation` map is populated only by `apply(…Received)`, and no
    `Received` event precedes a first-submission rejection, so state would yield `null` and the
    outcome file would have no destination. `azureLocation` is `required` on
    `migrated-case-submission.json`, so the payload always carries it. Populating it in `apply()`
    does not help — the duplicate branch shows outcome events are *built* before `apply()` runs on
    the stream.
  - **FR13c — No `apply()` branch is added for `MigratedCaseSubmissionRejected`.** It falls through
    `otherwiseDoNothing()`, as `DuplicatedMigratedCaseSubmissionReceived` and
    `MigratedCaseSubmissionProcessed` already do. The rejection needs no replayed state (FR13b), so
    `MigratedCaseSubmissionReceived` remains the only event that mutates the aggregate.
  - **FR13d — Extend the dormant `stagingdlrm.events.migrated-case-submission-rejected` event to
    carry the whole `MigratedCaseSubmission` plus the failed rules.** Currently
    `{caseDetails, createdBy}` and referenced by no Java in the repo. It must name **which** rules
    failed, because a stagingDLRM rejection is terminal (4xx gets zero retries) and an unattributed
    failure leaves the uploader unable to fix and resubmit. Carrying the whole submission — as
    `DuplicatedMigratedCaseSubmissionReceived` already does — is **required, not stylistic**: FR21
    makes this event a reconciliation entry event, and the report needs `azureLocation` for its
    batch filter, `caseUrn` for its join, and the hearing/defendant/material counts. The
    `{caseDetails, createdBy}` shape supplies none of the first three.
  - **FR13e — The outcome `description` text must not collide with the existing sentinels.**
    `sendEventToGrid` suppresses EventGrid when the description matches `DUPLICATE_SUBMISSION_ID`,
    and classifies metrics by **bidirectional substring** match against
    `{JSON_SCHEMA, DUPLICATE_SUBMISSION_ID, CASE_ALREADY_EXISTS_IN_PROGRESSION}`. A flattened
    validation-error description mentioning any of those strings would be mis-suppressed or
    mis-counted.

### D. Propagate through the converter

- **FR14 — Map Group A's 11 fields in `MigratedCaseConvertor`**, honouring PCFDLRM's names where
  they differ (`defendantOccupationCode` → `occupationCode`, `licenseCode` → `driverLicenceCode`).
  The offence vehicle fields are re-nested by the converter: `vehicleMake` flat, `vehicleCode` and
  `vehicleRegistrationMark` into PCFDLRM's nested `vehicleRelatedOffence` (the only home PCFDLRM
  reads into `offenceFacts`). Under LIBRA 0.13.1 **Group B is one field**
  (`numPreviousConvictions`) and is **not** mapped: PCFDLRM at `pcfdlrm.version` 17.104.21 has no
  home for it, so it is write-only until a PCFDLRM release and a version bump here. Group C
  (`informant`, `prosecutorCosts`) is never mapped.
- **FR15 — `initiationCode` needs no change at all.** LIBRA 0.13 sends `["O"]`, the same single
  constant the schema already declares, so there is no schema change, no converter change, and
  `MigratedCaseConvertor:256`'s `.getInitiationCode().name()` keeps compiling unchanged. The value
  reaches PCFDLRM unchanged, where `initiationCode` is a plain string. (Earlier drafts widened the
  enum to the platform's seven codes; LIBRA 0.13 no longer requires it.)
- **FR16 — No source-system branching in the converter.** Divergence lives in the FR12 rules.
  The converter stays a single typed mapping for both source systems.

### E. Tests

- **FR17 — Extend the DD-43078 suites and fixtures; do not create parallel LIBRA tests.** LIBRA adds
  a sibling fixture set alongside `json/event-processor/xhibit/`, driven through the existing
  `FixtureLoader` / `WholePayloadMatcher` support. Source system stays a scenario parameter (DD-43078
  FR3), so no LIBRA-specific test class and no `if` on source system inside a test.
- **FR18 — The XHIBIT whole-payload fixtures are the regression gate and must not change.** If a
  DD-43078 XHIBIT expectation moves, the relaxation has leaked. Each of the 5 relaxations needs
  XHIBIT coverage proving the constraint still rejects — now via the FR12f rule and the FR13
  outcome file rather than via schema — and LIBRA coverage proving it now passes.
- **FR18a — Prove the invalid case is not forwarded.** A rejected submission must produce no
  `system-id-mapper` lookup and no pcfdlrm POST. This is the regression the FR13 event choice
  exists to prevent, so it needs its own assertion rather than being implied by the absence of a
  fixture.

### F. Cross-team and workbook outputs

- **FR19 — Produce the LIBRA-workbook corrections as a deliverable**, in a form that can go to the
  workbook owner: the three FR4 constraint conflicts, and the six FR2a strictness points that LIBRA
  0.13 marks optional inside an optional container (`gender`, `selfDefinedInformation`, the three
  plea/verdict/allocationDecision identifiers, and `markerTypeCode`) — confirm LIBRA sends a
  well-formed container or none. LIBRA 0.13 already resolved earlier corrections: `prosecutorOffenceId`
  is now required on both sides (no dangling reference), `organisationTelephoneNumber` is gone (the
  suspected duplicate), and `initiationCode` is `["O"]` on both sides (no enum contradiction).
- **FR20 — Publish the exclusion register.** The fields this story deliberately does not implement,
  each with its reason and the specific question it raises, for the Technical Architect —
  `00-input-brief.md` R1–R6.

### G. Reconciliation visibility

- **FR21 — A validation-rejected submission must appear in the reconciliation report.** Today it
  would not appear **at all** — not as an unknown status, but absent. `stagingdlrm-report.sql`'s
  `batch_streams` CTE admits a stream only if it carries
  `migrated-case-submission-received` or `error-migrated-case-submission-received`, and derives the
  `azureLocation` it batch-filters on from those two payloads. FR13 raises neither, so the stream
  is never selected and the case is missing from the stagingDLRM CSV and from the summary join
  downstream of it — silently dropping exactly the cases an operator most needs to see.
  - **FR21a — Admit the rejected event as a third entry event** in `batch_streams`, with its own
    `case_urn` and `azure_location` extraction paths. Depends on FR13d's payload shape.
  - **FR21b — Report rejections under a distinct status.** Without a new arm in the status `CASE`,
    a rejection falls through to `PROCESSED_FAILED`, conflating "stagingDLRM refused it" with "it
    failed downstream". These need separating for triage.
  - **FR21c — Add the new status to `summary-report.py`'s `STUCK_AT_STAGINGDLRM_STATUSES`.** That
    set is closed; a status missing from it degrades to `overall_status=UNKNOWN`.
  - **FR21d — The rejection description must be a single shared constant.** The SQL matches
    descriptions by **exact equality** (`= 'Duplicate Submission ID'`) while
    `StagingDlrmEventProcessor` matches by bidirectional substring, so ad-hoc text can satisfy one
    and break the other. See FR13e.
  - **FR21e — Verification is a manual run against a real batch.** `tools/reconciliation/` is
    outside the Maven build, so stage 7 CI does not cover it (repo `CLAUDE.md`). Any new Python
    tests use the stdlib `unittest` already present, and no new dependency is introduced.

## Acceptance criteria

- **AC1** — A LIBRA payload built from workbook V0.13 is accepted by the canonical schema and
  produces a `migrated-case-submission-received` event.
- **AC2** — Every DD-43078 XHIBIT whole-payload fixture passes byte-identically, with no fixture
  edited.
- **AC3** — For each of the 5 relaxed constraints: an XHIBIT payload violating it is rejected, and
  the rejection names that constraint in its outcome file.
- **AC4** — For each of the 5 LIBRA rules: a LIBRA payload violating it is rejected and named; the
  same payload shape submitted as XHIBIT is unaffected.
- **AC5** — All 14 declared fields (Groups A–C, LIBRA 0.13.1) round-trip through schema validation
  on a LIBRA payload without a validation error.
- **AC6** — Group A's 11 fields appear with correct values and PCFDLRM's names in the outbound
  PCFDLRM payload, asserted as a whole payload.
- **AC7** — Group C's 5 fields are accepted on input and absent from the outbound PCFDLRM payload.
- **AC8** — A rule rejection appends `MigratedCaseSubmissionRejected` + `MigratedCaseSubmissionProcessed`
  and no `MigratedCaseSubmissionReceived`, and produces an outcome file carrying the `submissionId`,
  the failed rules and a non-null `azureLocation` — on a **first** submission, where no prior
  `Received` event exists.
- **AC8a** — On that same rejection, `system-id-mapper` is not called and nothing is sent to pcfdlrm.
- **AC8b** — A payload that is both a duplicate and rule-invalid reports as a duplicate and emits no
  outcome file, unchanged from today.
- **AC10** — *(void under LIBRA 0.13.1 — `officerInCase` is no longer sent.)* Was: a LIBRA payload
  carrying `officerInCase`, including the three unmapped officer fields, is accepted rather than
  rejected as an additional property.
- **AC11** — `mvn clean install` is green with no hand-edits to generated sources.
- **AC12** — The workbook-corrections document (FR19) and the exclusion register (FR20) exist and
  are linked from the story.
- **AC13** — A validation-rejected submission appears in the stagingDLRM reconciliation CSV with its
  `case_urn`, `azure_location` and a distinct rejection status, and carries that status through to
  `summary-report.py`'s `overall_status` rather than degrading to `UNKNOWN`.

## Out of scope

Carried from `00-input-brief.md` R1–R6 as explicit non-requirements — each is a decision, not an
omission.

- **3 LIBRA fields dropped from the schema (R1)** — `prosecutorOfferAOCP`, `prosecutorCompensation`,
  `middleName2`. All exist in PCFDLRM but have no schema reachable from `courtReferral.json`, and their
  canonical containers are open, so omitting them is a true no-op. Open question: is PCFDLRM the
  intended consumer? (The `backDuty*` trio that was here in earlier drafts is gone from LIBRA 0.13
  entirely, so it is no longer even a decision.)
- **Propagation of the 5 declared-unmapped fields (R2)** — accepted, then discarded. Confirm with
  the TA that the data is genuinely not needed downstream.
- **plea / verdict / allocationDecision code → UUID resolution (R3) — resolved by LIBRA 0.13.** Both
  source systems now model these as UUID identifiers (`plea.id`, `verdict.id`,
  `allocationDecision.motReasonId`); there is no code→UUID resolution to do. The only divergence left
  is required (XHIBIT) vs optional-in-an-optional-container (LIBRA), handled under FR2a. No follow-up
  ticket needed.
- **`officerInCase` converter mapping (R4)** — schema here, mapping when PCFDLRM has the fields.
- **The Function App LIBRA gate and schema-selection strategy** — DD-43086, including how deep the
  LIBRA gate should validate.
- **All PCFDLRM work** — the source-system axis on `CcProsecutionValidationRuleProvider`, the
  `officerInCase` block, the three tier-4 fields, and the three XHIBIT-only guards.
- **Progression** — no change identified; regression validation only.
- **Deciding LIBRA's `initiationCode` value(s)** — no longer in play. LIBRA 0.13 sends `["O"]`, the
  same value the schema already declares, so there is no widening and no reference-data decision.
- **Reconciliation tooling `--source-system`** — the hardcoded `SOURCE="XHIBIT"` in the shell
  scripts stays a separate operational ticket. FR21 covers only rejection *visibility*, which is a
  consequence of an event this story introduces.

## Risks and notes

- **No real LIBRA sample exists** (analysis §5 Q1). Every constraint value and all LIBRA rule
  content comes from workbook V0.13. FR12h is the mitigation: rule content is data, not structure.
- **Shared-schema blast radius.** The accepted trade-off from the epic design (analysis §2, §7.3) —
  a bug in shared schema or validation code affects both source systems. FR18 and the DD-43078
  fixtures are the containment.
- **Relaxation is only half-safe until FR12f lands.** Between the schema relaxation and the XHIBIT
  rules, XHIBIT is under-validated. They ship together or the story is not done.
- **Group B ships write-only.** 19 fields validated on input and dropped at the converter until
  PCFDLRM catches up. Deliberate — the alternative is rejecting LIBRA payloads that carry them — but
  it must be visible, not discovered later as data loss.
- **FR2a strictness may return as a schema decision.** With no real LIBRA sample, the six FR2a points
  (`gender`, `selfDefinedInformation`, the three plea/verdict/allocationDecision identifiers,
  `markerTypeCode`) assume LIBRA sends a well-formed container or omits it. If the extract instead
  sends a present-but-incomplete container, that point flips to a relaxation — FR19 raises all six.

## Notes for the design stage

- FR12's rule map and DD-43086's Function App schema selection are the **same shape** — a
  source-system-keyed map — differing only in that the Function App may use CDI where the aggregate
  cannot. Recorded as [ADR-002](../adrs/002-source-system-keyed-dispatch.md) (Accepted).
- FR13 changes an existing domain event's schema. Check the event-store transformation /
  anonymisation rules in `stagingdlrm-domain-transformation-anonymise` and whether a dormant,
  never-emitted event needs a versioning step at all.
- FR1 and FR12f are the same 5 constraints expressed twice, in different languages. Design should
  say how they are kept in step — a single declarative source is worth considering over two
  hand-maintained lists.
- Still open from the FR13 discussion, deliberately left to design: whether `createdBy`, which
  nothing populates, is kept or dropped; and whether rejections need to reach the view store for
  query-api or the event-store record suffices. (The third question — whether `Rejected` carries the
  whole submission — is now settled by FR21's reconciliation needs, see FR13d.)
- `MigratedCaseConvertor` is 341 lines of explicit mapping across ~24 build methods. FR5's 11 fields
  land across four of them; FR8's 19 would add a new officer branch later.
- `libra-schema-impact.csv` and the `dlrm-libra-0.13.json` / `dlrm-xhibit-0.12.json` artefacts predate
  this revision and must be regenerated (`tools/schema-gen/regenerate.sh`) to match `schema-diff_3`;
  the regeneration must also fix the two `dlrm-xhibit-0.12.json` cells that disagree with the live
  schema (`migrationSourceSystem` required, and the `caseDetails` `anyOf`). Until then, derive the
  5 relaxations and 5 LIBRA rules from FR1/FR12f/FR12g here, not from the stale CSV.
