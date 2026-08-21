# Stories — DD-43180: Function App validation parity + counter/outcome fixes

> Stage 3 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [`02-design.md`](./02-design.md),
> [ADR-002](../adrs/002-source-system-keyed-dispatch.md), [ADR-003](../adrs/003-libra-payload-contract.md),
> [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md).

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43180](https://tools.hmcts.net/jira/browse/DD-43180) — Function App validation parity + counter/outcome fixes (size **S**) |
| Repo | `cpp-context-stagingdlrm` |
| Builds on | DD-43203 (delivered) — the domain rules this gate mirrors |

Three independently reviewable sub-tasks; all small, all in `stagingdlrm-azure-functions` /
`stagingdlrm-event-processor`.

## Findings — verified state of the code

- **F1** — The func-app gate accepts any `initiationCode` (no enum) in both `case-details.json` (XHIBIT)
  and `libra.case-submission.json` (LIBRA). `[scope-affecting]`
- **F2** — XHIBIT `case-details.json` already requires the four DD-43081-relaxed fields; LIBRA already
  requires `defendant.address`, `hearing.dateOfHearing`, `hearing.timeOfHearing`. Only the enums (+ XHIBIT
  `anyOf`) are missing.
- **F3** — `StagingDlrmEventProcessor` increments the received counter when the failure description
  matches a `stagingContextErrors` marker (incl. `JSON_SCHEMA` = `"JSON schema validation has failed"`).
  A **gate** rejection carries only JSON-path messages, so it misses the marker and is **not** counted as
  received — only the error counter fires. `[scope-affecting]`
- **F4** — `TimerTriggerJava.processClientError` hard-codes `caseUrn = ""` on the gate-failure path; every
  other path threads it from `prosecutorCaseReference`. `[scope-affecting]`
- **F5** — `libra.case-submission.json` has drifted from the 0.13.1 payload contract: it still declares
  `officerInCase` (dropped by 0.13.1) and 8 other extras, and is missing fields 0.13.1 requires
  (`courtRoomId`, `durationMinutes`, `prosecutorOffenceId`, …). `[scope-affecting]`

## DD-43180a — Gate schema parity

**Size:** S · **Depends on:** nothing

> As an **operator running LIBRA and XHIBIT migrations**, I want **the gate to reject an out-of-contract
> payload up front**, so that **bad data fails fast instead of as a late downstream 4xx**.

### Scope

| Artefact | Change |
|---|---|
| `case-details.json` | `initiationCode` enum `["O"]` — **XHIBIT gate otherwise untouched** |
| `libra.case-submission.json` | `initiationCode` enum `["C","Q","J","R"]` |
| `JsonSchemaValidatorTest` + one fixture | rejection cases; move the one accepted fixture's out-of-enum `initiationCode` to a valid value |

### Acceptance criteria

- [ ] AC1: XHIBIT rejects `initiationCode` `C`; LIBRA rejects `O`; each names `initiationCode`.
- [ ] AC2: no XHIBIT gate change other than the `initiationCode` enum — no `anyOf`, no change to the
  existing required fields.
- [ ] AC3: valid XHIBIT (`O`) and LIBRA (`C`) payloads still pass.
- [ ] AC4: the gate is not more lenient than the domain engine for any shared constraint (ADR-004).
- [ ] AC5: the XHIBIT `anyOf` is **not** added to the gate — recorded as domain-only (ADR-004 §3).
  (The LIBRA `courtRoomId` rule is mirrored via DD-43180d.)

## DD-43180b — Received-counter fix

**Size:** S · **Depends on:** nothing

> As a **team reading migration metrics**, I want **a func-app schema failure to count as a received
> submission (as well as an error)**, so that **the received counter reflects every case that entered
> staging, whether it was rejected at the gate or by the framework**.

### Scope

`TimerTriggerJava` — prefix the gate validation-failure description with the
`"JSON schema validation has failed"` marker the event processor keys the received counter on. The event
processor is unchanged (`JSON_SCHEMA` stays in `stagingContextErrors`). Tests: `StagingDlrmEventProcessorTest`
asserts received **and** error both increment for a framework-form and a gate-form description;
`TimerTriggerJavaTest` asserts the func-app gate error message carries the marker.

### Acceptance criteria

- [ ] AC1: a func-app schema-validation failure (gate rejection or framework-level) increments both
  `migratedCaseSubmissionReceivedCounter` and `errorMigratedCaseSubmissionReceivedCounter`.
- [ ] AC2: duplicate / case-already-exists / validation-rule failures still increment the received counter.
- [ ] AC3: EventGrid suppression behaviour is unchanged.

## DD-43180c — caseUrn in the outcome

**Size:** S · **Depends on:** nothing

> As an **operator investigating a gate rejection**, I want **the outcome file to name the case**, so that
> **I can find and resubmit it**.

### Scope

`TimerTriggerJava` — `extractCaseUrn(caseJsonContent)` (defensive), threaded through `processClientError`.
`TimerTriggerJavaTest` + `EventGridMonitorHelperTest` — assert the extracted URN.

### Acceptance criteria

- [ ] AC1: a gate-rejection outcome carries `caseUrn` = the case's `prosecutorCaseReference`.
- [ ] AC2: a malformed case JSON yields `caseUrn` = `""` rather than throwing.

## DD-43180d — LIBRA gate reconciliation to 0.13.1

**Size:** M · **Depends on:** nothing

> As a **developer maintaining the LIBRA gate**, I want **`libra.case-submission.json` to match the
> 0.13.1 payload contract**, so that **the gate accepts what LIBRA sends and rejects what it doesn't,
> instead of validating a stale shape**.

### Scope

`libra.case-submission.json` — remove group A (officerInCase + 8 extras), add group B (courtRoomId,
durationMinutes, prosecutorOffenceId — all required — convictingCourtCode, emailAddress1/2,
personalInformation.address, individualAlias.title). `libra-case-submission-valid.json` and
`JsonSchemaValidatorTest` (the officerInCase and summonsCode cases; the `validDefendant`/`validHearing`
builders and hearing/offence required rows) move with it. `migrationSourceSystem` stays out (assembly-added).

### Acceptance criteria

- [ ] AC1: the gate no longer declares `officerInCase` or the other group-A extras; a LIBRA payload
  omitting them validates.
- [ ] AC2: group-B fields are declared; `courtRoomId`, `durationMinutes`, `prosecutorOffenceId` are required.
- [ ] AC3: group C (code-vs-UUID, `observedEthnicity` type, `offenceDateCode` range) is **not** changed —
  raised as a follow-up for the extract team.

## Out of scope for the whole story

- Any domain-engine change; PCFDLRM; the `H` fixture question; any XHIBIT gate change beyond the
  `initiationCode` enum; mirroring the XHIBIT `anyOf` at the gate; LIBRA gate group-C conflicts (DD-43180d
  AC3 — code-vs-UUID, `observedEthnicity` type, `offenceDateCode` range).

## Notes

- `mvn clean install` green applies to every sub-task.
- ADR-004 governs the gate/domain duplication and is the review checklist for DD-43180a.
