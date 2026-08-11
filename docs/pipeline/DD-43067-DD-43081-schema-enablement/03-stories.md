# Stories — DD-43081: stagingDLRM schema enablement

> Stage 3 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [`02-design.md`](./02-design.md),
> [ADR-002](../adrs/002-source-system-keyed-dispatch.md) (**Accepted** 2026-08-11).
> Every file, line and constraint named below was checked against the working tree at `795b4ca0`.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) — stagingDLRM schema enablement (size **L**) |
| Repo | `cpp-context-stagingdlrm` |
| Depends on | [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) — delivered; supplies the XHIBIT regression baseline |
| Blocks | [DD-43086](https://tools.hmcts.net/jira/browse/DD-43086) — func-app gate consumes the canonical schema |
| Sub-stories | 6 (T1–T6) |

DD-43081 stays **one Jira story**. T1–T6 are sub-tasks: each is independently reviewable and
mergeable, but LIBRA is not ingestible until T2 and T3 have both landed.

**T1 owns `stagingdlrm-domain-aggregate` for the sprint** — no concurrent story may touch it.

---

## Sequence

```text
 T1  rejection path + rule engine              M   no rules yet; path proven by a test-only rule
  |
  +--> T2  10 relaxations + 10 XHIBIT rules    L   atomic
  |
  +--> T3  38 field additions                  M   declares per ADR-003
             |
             +--> T4  converter: Group A + null-guard    M
             |
             +--> T5  9 LIBRA rules + LIBRA fixtures     M   also needs T1 + T2

 T6  workbook-corrections pack                 S   independent, any time
```

T2 and T3 are independent of each other and can run in parallel once T1 lands.

| Gate | Question | Blocks |
|---|---|---|
| **G1** | Confirm the LIBRA extract implements [ADR-003](../adrs/003-libra-payload-contract.md) (design F3, F4) | Nothing in this repo — T3 declares per ADR-003 regardless. Only bites if the extract ships flat |
| **G2** | Which initiation codes may XHIBIT legitimately send? | T2's `InitiationCodeValidationRule` content |

[ADR-003](../adrs/003-libra-payload-contract.md) is **Accepted** for DD-43081 and DD-43086, so T3
declares the nested shape and does not wait on G1. G1 is the extract team's confirmation — chase it
before the extract is built, because a mismatch found afterwards is a three-party fix. G2 is a
workbook answer (T6) fixing one rule's parameter.

---

## T1 — Rejection path and rule engine

**Size:** M · **Depends on:** nothing

> As an **operator investigating a failed LIBRA migration**,
> I want **a submission rejected by a business rule to produce an outcome file naming what failed,
> and to appear in the reconciliation report**,
> so that **I can find and fix the case, instead of it disappearing between stagingDLRM and the
> report**.

Ships the mechanism with **no rules registered**. The value is the path itself; T2 and T5 fill the map.

### Scope

| Artefact | Change |
|---|---|
| `…/aggregate/validation/` (new package) | `MigratedCaseValidationRule`, `RuleInput`, `ValidationError`, `MigratedCaseValidationRuleEngine` with a `static final Map<MigrationSourceSystemName, List<rule>>` |
| `MigratedCaseSubmissionAggregate` | rejection branch after the duplicate early return |
| `stagingdlrm.events.migrated-case-submission-rejected.json` | carries the whole `MigratedCaseSubmission` + `validationErrors` |
| `tools/reconciliation/stagingdlrm-report.sql` | rejected event as a third entry event; `VALIDATION_REJECTED` status arm |
| `tools/reconciliation/summary-report.py` | add the status to `STUCK_AT_STAGINGDLRM_STATUSES` |

`stagingdlrm-command-handler` is **not** touched (FR12a).

### Acceptance criteria

1. The engine is a plain class with a `static final` map keyed by `MigrationSourceSystemName`; no CDI, no injection, no `appliesTo` on the rule interface (ADR-002).
2. Rules receive `RuleInput` and return `List<ValidationError>` — never events.
3. With a test-only always-failing rule registered for XHIBIT, the aggregate appends `MigratedCaseSubmissionRejected` **and** `MigratedCaseSubmissionProcessed(processingIsSuccessful=false)`, and **no** `MigratedCaseSubmissionReceived`.
4. On that rejection, `system-id-mapper` is not called and nothing is sent to pcfdlrm.
5. `azureLocation` on the outcome is read from the command payload and is non-null on a **first** submission, where no prior `Received` event exists.
6. No `apply()` branch is added for the rejected event — `MigratedCaseSubmissionReceived` remains the only event mutating aggregate state.
7. A payload that is both a duplicate and rule-invalid reports as a duplicate and emits no outcome file.
8. The rejection description is a single shared constant containing none of `JSON_SCHEMA`, `Duplicate Submission ID`, `Case Already exists in progression` as a substring in either direction.
9. With the map empty, every existing XHIBIT test passes and no DD-43078 fixture is edited.
10. A rejected submission appears in `stagingdlrm_report.csv` with its `case_urn`, `azure_location` and a distinct status, and reaches `summary_report.csv` as that status rather than `UNKNOWN`. *(Manual run against a real batch — CI does not cover `tools/reconciliation/`.)*

**Traceability:** FR12, FR12a–e, FR13, FR13a–e, FR21, FR21a–e · AC8, AC8a, AC8b, AC13 · ADR-002

---

## T2 — The 10 relaxations and the 10 XHIBIT rules

**Size:** L · **Depends on:** T1 · **G2** for one rule's parameter

> As the **team running XHIBIT in production**,
> I want **every constraint removed from the shared schema re-imposed as an XHIBIT rule in the same
> change**,
> so that **making the schema accept LIBRA provably does not weaken XHIBIT**.

**Atomic.** The relaxation and its rules merge together; between them XHIBIT is under-validated.

### Scope

| Artefact | Change |
|---|---|
| `case-details.json` | drop 4 `required`; drop the `anyOf`; widen `initiationCode` enum to `Q,R,S,C,J,Z,O` |
| `migrated-hearing.json` | drop `durationMinutes` from `required` |
| `migrated-offence.json` | drop `prosecutorOffenceId` from `required` |
| `self-defined-information.json` | drop `gender` from `required` |
| `MigratedCaseValidationRuleEngine` | 10 XHIBIT rules from 4 generic types |
| `MigratedCaseConvertor` | null-guard `buildSelfDefinedInformation` (design F1) |

No schema `$ref`'d from more than one parent is touched — `pcf-address.json`,
`personal-information.json`, `parent-guardian-information.json`, `case-marker.json` and
`migrated-week-commencing-date.json` all keep their constraints (FR2a).

### Acceptance criteria

1. Exactly the 10 constraints in FR1 are relaxed. No `maxLength`, `minLength`, `pattern`, `minimum`, `maximum` or `type` on any existing field changes (FR2); `initiationCode`'s enum is the only value-constraint change.
2. The six FR2a constraints remain enforced: `defendants[*].address.address1`, `…individual.personalInformation.surname` and `.address.address1`, the two `parentGuardianInformation` equivalents, `weekCommencingDate.startDate` and `caseMarkers[*].markerTypeCode` all still reject when their container is present and the field is missing.
3. For each of the 10: an XHIBIT payload violating it is rejected by a rule, and the outcome names that constraint.
4. Every DD-43078 XHIBIT whole-payload fixture passes byte-identically, with no fixture edited.
5. `initiationCode` `O` passes as XHIBIT; a value outside the seven (e.g. `"H"`) is rejected by the schema for both source systems.
6. A payload omitting `selfDefinedInformation.gender` converts without throwing — the unboxing NPE is guarded, mirroring `buildParentGuardianInformation`.
7. `mvn clean install` green, with no hand-edits to generated sources.

**Traceability:** FR1, FR2, FR2a, FR4, FR12f · AC2, AC3, AC9, AC9a · design F1, F5

---

## T3 — The 38 field additions

**Size:** M · **Depends on:** T1

> As a **migration engineer submitting a LIBRA case file**,
> I want **the canonical schema to accept every LIBRA field that has a home downstream, and the
> three it must tolerate but cannot use**,
> so that **a valid LIBRA payload is not rejected as a terminal 4xx for carrying its own data**.

Schema only — converter mapping is T4.

### Scope

Groups A (12), B (20) and C (6) per FR5/FR8/FR10, declared at the nesting
[ADR-003](../adrs/003-libra-payload-contract.md) fixes, across `case-details.json`, `individual.json`,
`personal-information.json`, `migrated-offence.json`, `migrated-defendant.json`,
`self-defined-information.json`, plus a new `officer-in-case.json` and the `officerInCase` property
on `migrated-case.json`.

### Acceptance criteria

1. All 38 fields round-trip through schema validation on a LIBRA payload with no validation error.
2. Every added field is optional; an existing XHIBIT payload remains valid.
3. A LIBRA payload carrying `officerInCase`, including `dxAddress`, `forename3` and `uniquePropertyReferenceNumber`, is accepted rather than rejected as an additional property.
4. Group C's 6 fields carry a `description` marking them accepted but not propagated.
5. `nationalInsuranceNumber` `$ref`s the existing `pcf-definitions.json` pattern rather than redeclaring it (FR7).
6. `officerInCase.address` `$ref`s the existing `address.json`; that file is unmodified.
7. The 6 Group D fields are **absent** from the schema, and a LIBRA payload carrying them still validates — their containers are open.
8. `mvn clean install` green; generated POJOs include the new fields.

**Traceability:** FR5–FR11 · AC5, AC10 · design F3, F4

---

## T4 — Converter propagation for Group A

**Size:** M · **Depends on:** T3

> As **PCFDLRM**,
> I want **the 12 LIBRA fields that already have a home in my contract to arrive populated**,
> so that **they reach Progression instead of being validated and dropped**.

### Scope

`MigratedCaseConvertor` — `buildIndividual` (3), `buildPersonalInformation` (2, renamed to
`occupationCode` / `driverLicenceCode`), `buildOffences` (5), `buildSelfDefinedInformation` (1),
`buildCaseDetails` (1).

Not all five offence fields map flat: `vehicleMake` and `vehicleRegistrationMark` are flat on
PCFDLRM's `migrated-offence.json`, but `vehicleCode` sits on a nested `vehicleRelatedOffence`
object — the shape `buildAlcoholRelatedOffence` already handles (design F4).

### Acceptance criteria

1. All 12 Group A fields appear with correct values and PCFDLRM's names in the outbound payload, asserted as a **whole payload**.
2. Group C's 6 fields are accepted on input and **absent** from the outbound payload.
3. Group B's 20 fields are not mapped, recorded in code as deliberate rather than omitted silently.
4. `initiationCode` reaches PCFDLRM unchanged for all seven codes; `.getInitiationCode().name()` is untouched (FR15).
5. No source-system branching anywhere in the converter (FR16).
6. Every DD-43078 XHIBIT outbound fixture passes byte-identically.
7. `vehicleCode` is written to the nested `vehicleRelatedOffence` object, built the way `buildAlcoholRelatedOffence` builds its counterpart.
8. `vehicleRegistrationMark` is written to whichever of its **two** PCFDLRM homes reaches Progression's `offenceFacts.vehicleRegistration` — confirm with the PCFDLRM team before mapping.

**Traceability:** FR14, FR15, FR16 · AC6, AC7 · design F4

---

## T5 — LIBRA rules and fixtures

**Size:** M · **Depends on:** T1, T2, T3

> As a **developer adding LIBRA to a shared pipeline**,
> I want **LIBRA's own stricter constraints enforced as rules and a LIBRA fixture set beside the
> XHIBIT one**,
> so that **LIBRA is validated to its own contract and future changes are regression-tested for
> both source systems**.

### Scope

The 9 LIBRA rules (FR12g) registered under `LIBRA`, and LIBRA fixture sets under
`json/aggregate/libra/` and `json/event-processor/libra/`, driven through the existing
`FixtureLoader` / `WholePayloadMatcher`.

### Acceptance criteria

1. For each of the 9 rules: a LIBRA payload violating it is rejected and named in the outcome; the same payload shape submitted as XHIBIT is unaffected.
2. `initiationCode` `C`, `J`, `Q`, `S` pass as LIBRA and reach the outbound payload unchanged; `R`, `Z` and `O` as LIBRA are rejected by the allowed-values rule.
3. A LIBRA payload built from workbook V0.13 is accepted end to end and produces `migrated-case-submission-received`.
4. Source system is a scenario parameter — no LIBRA-specific test class, no `if` on source system inside a test (DD-43078 FR3).
5. No XHIBIT fixture is edited.
6. LIBRA rule content is data: adding or changing one is a map entry plus a rule instance, no structural change (FR12h).

**Traceability:** FR12g, FR12h, FR17, FR18, FR18a · AC1, AC4, AC9

---

## T6 — Workbook-corrections pack

**Size:** S · **Depends on:** nothing

> As the **owner of the DLRM migration data schema workbook**,
> I want **one list of the discrepancies this analysis found, each with its evidence**,
> so that **I can correct V0.13 rather than have every consumer re-derive the same questions**.

### Scope

[`docs/analysis/libra-ingestion/libra-workbook-corrections.md`](../../analysis/libra-ingestion/libra-workbook-corrections.md)
— **written**. Eleven items with sheet rows and evidence, grouped as Format-cell gaps, naming and
duplication, and contract shape. It sits beside the workbook it corrects rather than in this
directory, because it outlives DD-43081 and DD-43086 and the pcfdlrm pipeline both need it.

Remaining work is circulation, not authorship.

### Acceptance criteria

1. Each item states the sheet row, what the workbook says, what the schema says, and the specific question. **Done.**
2. The exclusion register (R1–R6) is linked for the Technical Architect. **Done.**
3. Raised as a sub-task on epic DD-43067 so answers land against the epic, not against DD-43081 which closes first.
4. Item 11 (payload nesting) is routed to the **LIBRA extract team and the DD-43086 owner**, not the workbook owner, and raised before the extract is built.
5. Answers feed back into T2's `InitiationCodeValidationRule` (G2) and, for item 10's `markerTypeCode`, into FR2a.

**Traceability:** FR19, FR20 · AC12

---

## Notes

- **AC11** (`mvn clean install` green) applies to every task and is not restated per task.
- **`tools/reconciliation/` is not covered by CI** — T1's AC10 is a manual run against a real batch
  in dev/sandbox. QA owns that verification; stage 7 is not a green tick there.
- **Group B ships write-only** after T3: 20 fields validated on input and dropped at the converter
  until PCFDLRM releases them and `pcfdlrm.version` is bumped. Deliberate, and visible in T4 AC3.
- **[ADR-002](../adrs/002-source-system-keyed-dispatch.md)** governs T1's rule map and T5's rule
  registration; its compliance notes are the review checklist for T1, T2 and T5.
