# Design — LIBRA enabler: stagingDLRM schema enablement

> Stage 2 artefact. Source: [`01-requirements.md`](./01-requirements.md).
> Split per the team workflow: **2a** cross-context impact, **2b** inside the service.
> Every claim about current behaviour was read from the working tree at `795b4ca0`.

## 2a — Cross-context impact

**No other repo has to change for this story to ship.** Five boundaries are touched, all additively.

| Boundary | Impact | Action |
|---|---|---|
| **PCFDLRM** (`pcfdlrm.version` 17.104.21) | All 12 Group A fields are present at tag `v17.104.21`. Group B's 20 have no builder methods there — compile-blocked, deferred (FR14) | None for Group A |
| **`system-id-mapper`** | Unchanged. The rejection path must not reach it: `StagingDlrmEventProcessor:76` calls `getCaseIdForPtiURN` on `…submission-received`, which FR13 never raises on rejection | Assert absence (AC8a) |
| **Azure EventGrid → func-app** | `Outcome.description` is free text carried verbatim into the outcome JSON; a rejection description is additive | Text constrained by FR13e/FR21d |
| **DD-43086 (func-app)** | Consumes the canonical schema this story produces, including `officerInCase` | Canonical lands first. See F3 |
| **Progression** | None — all 32 added fields have a home in the `courtReferral.json` closure | Regression only |

**Deployment order.** stagingDLRM is deployable alone. LIBRA traffic cannot arrive until DD-43086
opens the gate, so a relaxed schema is never exposed to LIBRA before its rules exist — provided FR1
and FR12f ship together, which is a same-story constraint.

## 2b — Design inside the service

### The shape of the problem

```text
FR1  relax 10 constraints  ──┐
                             ├─► the schema stops enforcing them for XHIBIT too
FR12f 10 XHIBIT rules      ──┘   so these two are one atomic change
                                          │
FR13 rejection needs somewhere to go ─────┤
                                          ▼
                             MigratedCaseSubmissionRejected + Processed(failure)
                                          │
FR21 …or the rejected case vanishes ──────┘
     from reconciliation entirely
```

The 38 field additions and the converter mapping are additive and independent of that chain.

### What is *not* relaxed, and why it matters here

FR2a keeps a field strict when its containing object is optional and LIBRA omits the object. Six
constraints fall out on that basis. The design consequence is the useful part: **no schema `$ref`'d
from more than one parent is modified.** `pcf-address.json` (three parents) and
`personal-information.json` (two parents) are untouched, so no definition has to be split into
per-parent copies, no new generated POJO types appear, and
`MigratedCaseConvertor.buildParentGuardianInformation()` keeps calling the shared
`buildPersonalInformation` / `buildAddress`.

### Schema changes, file by file

> **Superseded for T3 by LIBRA 0.13.1** (`schema-diff.html`) — see [`03-stories.md`](./03-stories.md)
> T3. 0.13.1 drops `officerInCase` and `offence.convictionDate`, so the `officer-in-case.json` and
> `migrated-case.json` rows and the `convictionDate` addition below do **not** apply. The vehicle
> fields (`vehicleCode`, `vehicleMake`, `vehicleRegistrationMark`) are declared **flat** on
> `migrated-offence.json`: stagingDLRM adds **no** `vehicle-related-offence.json`, PCFDLRM's schema is
> **unchanged**, and the T4 converter populates PCFDLRM's nested `vehicleRelatedOffence` (supersedes
> ADR-003 §2 row 145). The `case-details.json` relaxations/`initiationCode` row is the older 0.13
> draft; the authoritative relaxation list is FR1.

`stagingdlrm-domain-value-schema/src/main/resources/json/schema/`

| File | Relaxations (FR1) | Additions |
|---|---|---|
| `case-details.json` | drop 4 `required` (`dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator`); drop the `anyOf`; widen `initiationCode` enum to `Q,R,S,C,J,Z,O` | `summonsCode` (A); `informant`, `writtenChargePostingDate` (C) |
| `migrated/migrated-hearing.json` | drop `durationMinutes` from `required` | — |
| `migrated/migrated-offence.json` | drop `prosecutorOffenceId` from `required` | `statementOfFacts`, `statementOfFactsWelsh`, `vehicleMake`, `vehicleRegistrationMark`, `vehicleCode` (A — see F4); `convictionDate` (B) |
| `self-defined-information.json` | drop `gender` from `required` | `additionalNationality` (A) |
| `individual.json` | — | `driverNumber`, `nationalInsuranceNumber`, `licenseCode` (A — see F3) |
| `personal-information.json` | — | `occupation`, `defendantOccupationCode` (A — see F3) |
| `migrated/migrated-defendant.json` | — | `numPreviousConvictions`, `organisationTelephoneNumber` (B); `prosecutorCosts` (C) |
| `officer-in-case.json` | — | **new** — 11 fields (B) + 3 unmapped (C); `address` `$ref`s the existing `address.json` |
| `migrated/migrated-case.json` | — | `officerInCase` property (FR9) |

Four files carry relaxations; `pcf-address.json`, `personal-information.json`,
`parent-guardian-information.json`, `case-marker.json` and `migrated-week-commencing-date.json`
keep their constraints.

**Existing fields change required/optional only** (FR2) — no `maxLength`, `pattern`, `minimum`,
`maximum`, `type` or `enum` is touched, with `initiationCode` the sole exception, because its
single-constant enum is a deserialization wall rather than a validation nicety.

**Every addition is optional** (FR11). Group C entries carry a `description` marking them accepted
but not propagated, so the omission from the converter reads as intent.

**Reuse existing definitions** (FR7): `nationalInsuranceNumber` `$ref`s the pattern already in
`pcf-definitions.json`, and `officerInCase.address` `$ref`s `address.json`. Note from impact §8 that
`pcf-definitions.json`'s NI pattern is stricter than core's `nino`, and its `phone` allows a leading
`+` where core's does not — neither blocks this story, both matter when `$ref`-ing a phone-shaped
officer field.

### The rule engine

New package `uk.gov.moj.cpp.stagingdlrm.aggregate.validation` in `stagingdlrm-domain-aggregate`,
per [ADR-002](../adrs/002-source-system-keyed-dispatch.md).

```text
MigratedCaseValidationRule      interface — List<ValidationError> apply(RuleInput)
RuleInput                       record   — the MigratedCaseSubmission
ValidationError                 record   — jsonPath + message
MigratedCaseValidationRuleEngine class    — static final Map<MigrationSourceSystemName,
                                                             List<MigratedCaseValidationRule>>
```

**Rules are parameterised instances, not one class per constraint.** FR1 and FR12f express the same
10 constraints in two languages; making the Java side declarative is what keeps them diffable by eye:

```java
XHIBIT, List.of(
    RequiredFieldRule.at("$.migratedCase.caseDetails.dateReceived"),
    RequiredFieldRule.at("$.migratedCase.caseDetails.receiptType"),
    …
    AtLeastOneOfRule.of("$.migratedCase.caseDetails", "dateOfCommittal", "dateOfSending"),
    InitiationCodeValidationRule.withAllowedValues("O"))
```

Four generic types cover all 19 rules: `RequiredFieldRule`, `AtLeastOneOfRule`,
`InitiationCodeValidationRule`, `MaxLengthRule`. All instances are immutable and constructed once;
the engine holds no per-submission state and never joins the aggregate's serialized snapshot.

### The rejection branch in the aggregate

`MigratedCaseSubmissionAggregate.receiveMigratedCaseSubmission()` gains a second branch, after the
existing duplicate early return:

```text
if (duplicate)         → Duplicated…Received + Processed(false, DUPLICATE_SUBMISSION_ID)   [unchanged]
errors = engine.validate(sourceSystem, input)
if (!errors.isEmpty()) → MigratedCaseSubmissionRejected(submission, errors)
                       + Processed(false, VALIDATION_FAILED + flattened errors)
otherwise              → MigratedCaseSubmissionReceived                                    [unchanged]
```

Three properties it must hold:

- **`azureLocation` comes from the payload**, not `this.azureLocation` — the map is populated only
  by `apply(…Received)`, which has not run on a first submission. It is `required` on
  `migrated-case-submission.json`, so the payload always carries it.
- **No `apply()` branch** for the rejected event; it falls through `otherwiseDoNothing()` alongside
  `Duplicated…` and `Processed`.
- **`MigratedCaseSubmissionReceived` is never raised on this path**, because it is the pcfdlrm
  forwarding trigger.

`stagingdlrm.events.migrated-case-submission-rejected.json` carries the whole
`MigratedCaseSubmission` plus a `validationErrors` array — required by FR21, which makes it a
reconciliation entry event.

**`VALIDATION_FAILED` is a shared constant.** It is read in three places with three matching
semantics: `StagingDlrmEventProcessor:144` (equality, EventGrid suppression), `:182` (bidirectional
substring, metrics) and `stagingdlrm-report.sql:153` (SQL equality). It must not contain
`JSON_SCHEMA`, `Duplicate Submission ID` or `Case Already exists in progression` as a substring.

### Converter changes

`MigratedCaseConvertor` — two edits:

1. **Group A's 12 fields** across `buildIndividual` (3), `buildPersonalInformation` (2, renamed to
   PCFDLRM's `occupationCode` / `driverLicenceCode`), `buildOffences` (5),
   `buildSelfDefinedInformation` (1), `buildCaseDetails` (1).
2. **One null-guard** — `buildSelfDefinedInformation:196` calls `getValueFromCode(gender)`
   unguarded; FR1 makes `gender` optional, which makes that path reachable (F1).

`initiationCode` needs no change: widening rather than dropping keeps
`.getInitiationCode().name()` compiling.

### Reconciliation changes

`tools/reconciliation/`, two files:

- `stagingdlrm-report.sql` — `batch_streams` gains the rejected event as a third entry event, with
  `case_urn` from `{migratedCaseSubmission,migratedCase,caseDetails,prosecutorCaseReference}` and
  `azure_location` from `{migratedCaseSubmission,azureLocation}`, identical to the received event.
  The status `CASE` gains a `VALIDATION_REJECTED` arm ahead of the `PROCESSED_FAILED` fallback.
- `summary-report.py:129` — `STUCK_AT_STAGINGDLRM_STATUSES` gains the same value, or
  `derive_status` degrades those rows to `UNKNOWN`.

`hearing_count` / `defendant_count` / `material_count` populate for rejected rows for free, because
the event carries the submission. Verification is a manual run against a real batch; CI does not
reach this directory.

### Test layout

Extending DD-43078 (FR17). LIBRA fixtures sit beside the existing `xhibit/` trees:

- `stagingdlrm-domain-aggregate/src/test/resources/json/aggregate/libra/`
- `stagingdlrm-event-processor/src/test/resources/json/event-processor/libra/`

Each of the 10 XHIBIT relaxations needs a pair — accepted before, rejected-by-rule after — and each
of the 9 LIBRA rules a violating case. **XHIBIT fixtures must not be edited**; if one moves, a
relaxation has leaked past its rule. Rule classes are unit-testable without the aggregate, and the
aggregate tests exercise the real engine rather than a mock.

## FR → design traceability

| FR | Where |
|---|---|
| FR1, FR2, FR2a, FR4 | *What is not relaxed* · *Schema changes, file by file* |
| FR3 | No new `$id`; `officer-in-case.json` sits in the existing namespace |
| FR5–FR11 | *Schema changes* additions column; nesting per **F3** |
| FR12, FR12a–h | *The rule engine* |
| FR13, FR13a–e | *The rejection branch in the aggregate* |
| FR14, FR15, FR16 | *Converter changes* |
| FR17, FR18, FR18a | *Test layout* |
| FR19, FR20 | Documentation deliverables — no design impact |
| FR21, FR21a–e | *Reconciliation changes* |

## Findings

**F1 — `buildSelfDefinedInformation` will NPE once `gender` is optional.** Line 196 calls
`getValueFromCode(selfDefinedInformation.getGender())` unguarded, and
`MigratedGender.getValueFromCode(Integer)` compares `gender.code == code`, **auto-unboxing a null
Integer**. FR1 makes that path reachable. The failure lands in the event processor *after* the
domain event is committed — an exception on an already-accepted submission, not a clean 4xx.
`buildParentGuardianInformation:183` already guards with `if (gender != null)`; the same guard is
needed here, with a LIBRA fixture that omits the field.

**F2 — `getValueFromCode` passes unknown codes through** as `code.toString()` rather than failing,
so an out-of-range gender reaches PCFDLRM as a raw number. Out of scope; worth a follow-up ticket.

**F3 — The LIBRA payload must nest six fields; the workbook's flat layout is the outlier.**
`dlrm-libra-0.13.json`, generated from the workbook, puts `driverNumber`, `licenseCode`,
`nationalInsuranceNumber`, `occupation` and `defendantOccupationCode` flat on `defendant`, and
`vehicleCode` flat on `offence`. The pipeline's existing convention says otherwise, and the evidence
is in the two live schema sets: canonical is a strict **subset of PCFDLRM at every level** — no
canonical property sits at a different level from its PCFDLRM counterpart — and
`MigratedCaseConvertor` renames without ever re-nesting. PCFDLRM already holds all six where FR6
declares them.

So canonical follows PCFDLRM, and the payload must match: `migrated-defendant.json` is
`additionalProperties: false`, so flat fields against a nested declaration are a terminal 4xx.
`offence` is open, so a flat `vehicleCode` is instead **silently dropped** — quieter and worse.

This is a **coordination item (G1)**, not an open design decision: the LIBRA extract and DD-43086's
gate schema must be told the nesting before the extract is written. No real `case.json` exists yet
(analysis §5 Q1), so specifying it now is free.

**F4 — `vehicleCode` is nested in PCFDLRM and `vehicleRegistrationMark` is duplicated.** At
`v17.104.21`, `vehicleMake` and `vehicleRegistrationMark` are flat on `migrated-offence.json`, but
`vehicleCode` sits on `vehicle-related-offence.json`, `$ref`'d as `vehicleRelatedOffence` — the shape
`buildAlcoholRelatedOffence` already handles. Two consequences: FR6's nesting question (G1) extends
to `vehicleCode`; and `vehicleRegistrationMark` exists in **both** PCFDLRM schemas, so which one
reaches Progression's `offenceFacts.vehicleRegistration` needs confirming before T4 maps it.

**F5 — `offenceDateCode` needs no relaxation; the CSV's `relax-constraint` row is a tooling
artefact.** `generate-dlrm-schema.py:432` derives *both* bounds of an `N<n>` integer from the Format
cell's digit count, and `build-schema-impact.py:586` strips only the `minimum`. The surviving all-9s
`maximum` means "one digit", not "LIBRA sends 0–9", and the sheet's row-130 description enumerates
1–6 — exactly canonical's range. Recorded in impact §9; the tooling fix is a follow-up.

**F6 — The runtime entry schema is a second file, and it already diverges from canonical.** The
framework validates inbound commands against
`stagingdlrm-command-api/src/raml/json/schema/stagingdlrm.receive-migrated-case-submission.json`,
not against canonical's `migrated-case-submission.json`. It `$ref`s
`migrated-case.json`, so **every FR1 and FR5–FR11 change propagates automatically and this file
needs no edit** — including the new `officerInCase` property. But it maintains its own
envelope-level `required` and `additionalProperties: false`, and the two envelopes already differ:
the RAML one has no `channel`, while canonical's does and the generated POJO exposes it. Pre-existing
and out of scope; recorded so nobody assumes canonical is the only gate a payload passes.

**F7 — Event anonymisation is a no-op; current behaviour is retained deliberately.**
`events-anonymisation-rule.json` contains exactly one entry, for
`stagingdlrm.events.case-received` — an event name that matches **none** of the six real events in
`stagingdlrm-domain-event`. Events are therefore stored in the event store as-is. **Decision: keep
current behaviour.** DD-43081 adds `nationalInsuranceNumber`, `driverNumber`, `occupation` and the
officer block to the payload, and FR13's rejected event carries the whole `MigratedCaseSubmission`,
so the volume of unanonymised data grows — accepted, consistent with how every existing event is
already stored. No anonymisation rule is added by this story.

*(Checked and clear: the catalog generator sweeps `src/main/resources/json/schema` by directory, so
the new `officer-in-case.json` needs no manual registration.)*

## Gates

| # | Question | Recommendation | Blocks |
|---|---|---|---|
| **G1** | Confirm the LIBRA extract implements [ADR-003](../adrs/003-libra-payload-contract.md) (F3, F4) | ADR-003 **Accepted** for DD-43081 and DD-43086; the extract team's confirmation is the open item | Nothing in this repo — 6 Group A fields arrive unmapped only if the extract ships flat |
| **G2** | Which initiation codes may XHIBIT legitimately send? | Workbook owner (R5) — the XHIBIT rule pins whatever is agreed | FR12f content |

Neither gate blocks work in this repo. G1 still carries the longest lead time: canonical follows
PCFDLRM either way, but a mismatch found after the extract ships is a coordinated three-party fix.

## Notes for stage 3

- **The natural split is five tasks**, with the relaxation and its XHIBIT rules inseparable: a
  relaxed schema without its rules is an XHIBIT regression.
- **Module collision warning:** the rule engine and aggregate work owns
  `stagingdlrm-domain-aggregate`; no concurrent story may touch it.
- F1 is a defect fix with its own test case — it should be visible in the story, not buried inside
  "converter changes".
- FR13 changes an existing domain event's schema. Check the anonymisation rules in
  `stagingdlrm-domain-transformation-anonymise`, and whether a dormant, never-emitted event needs a
  versioning step at all.
- Open for design/build to settle: whether `createdBy`, which nothing populates, is kept on the
  rejected event; and whether rejections need to reach the view store for query-api or the
  event-store record suffices.
