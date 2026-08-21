# Requirements — DD-43180: Function App validation parity + counter/outcome fixes

> Stage 1 artefact. Source: [`00-input-brief.md`](./00-input-brief.md). Feeds
> [`02-design.md`](./02-design.md).

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43180](https://tools.hmcts.net/jira/browse/DD-43180) — Function App validation parity + counter/outcome fixes |
| Repo | `cpp-context-stagingdlrm` |

## Story

### Summary (JIRA summary line)

Make the Function App gate reject the same per-source-system cases the domain rules do, stop counting a
gate schema-failure as a received submission, and populate `caseUrn` in the gate-rejection outcome.

### User story

As an **operator running LIBRA and XHIBIT migrations**, I want **the Function App gate to reject an
out-of-contract payload up front, without inflating the received metric, and to name the case in the
outcome file**, so that **bad data fails fast with accurate metrics and a traceable outcome instead of a
late downstream 4xx**.

## Requirements

### A. Gate schema — initiation code

- **FR1 — XHIBIT gate change is the `initiationCode` enum only.** In `case-details.json` add
  `initiationCode` enum `["O"]`. **No other XHIBIT gate validation is touched** — the existing required
  fields stay exactly as they are, and the XHIBIT `anyOf(dateOfCommittal, dateOfSending)` rule is **not**
  added to the gate (it stays domain-only).
- **FR2 — LIBRA gate: add the `initiationCode` enum.** In `libra.case-submission.json` add
  `initiationCode` enum `["C","Q","J","R"]`. `defendant.address`, `hearing.dateOfHearing`,
  `hearing.timeOfHearing` are already required in the LIBRA gate — no change.
- **FR3 — The gate is never more lenient than the domain engine** (ADR-003 §6, ADR-004). No gate rule
  relaxes a domain constraint.
- **FR3a — The XHIBIT `anyOf` rule stays domain-only** — DD-43180 does not touch XHIBIT gate validation
  beyond the initiation code (ADR-004 §3). The LIBRA `hearings[*].courtRoomId` rule is now mirrored at
  the gate (FR6 adds `courtRoomId` to the gate hearing).

### D. LIBRA gate reconciliation to the 0.13.1 contract

- **FR6 — Reconcile `libra.case-submission.json` to `dlrm-libra-0.13.1.json` (groups A+B).**
  - **Remove** the fields 0.13.1 no longer carries: `migratedCase.officerInCase` (and its definition),
    `caseDetails.summonsCode`, `caseDetails.writtenChargePostingDate`, `defendant.telephoneNumberBusiness`,
    `defendant.organisationTelephoneNumber`, `offence.backDuty`, `offence.backDutyDateFrom`,
    `offence.backDutyDateTo`, `offence.convictionDate`.
  - **Add** the fields 0.13.1 requires/declares: `hearing.courtRoomId` (required), `hearing.durationMinutes`
    (required), `offence.prosecutorOffenceId` (required), `offence.convictingCourtCode`,
    `defendant.emailAddress1`, `defendant.emailAddress2`, `personalInformation.address`,
    `individualAlias.title`.
- **FR6a — Group C conflicts are out of scope** and raised as a follow-up for the extract team:
  `plea`/`verdict`/`allocationDecision` code-vs-UUID, `personalInformation.observedEthnicity` type
  (string vs integer), `offenceDateCode` range (0–9 vs 1–6), and the `required`/`maxLength` differences.
- **FR6b — `migrationSourceSystem` stays out of the gate.** 0.13.1 is the assembled-submission contract;
  the func-app adds `migrationSourceSystem` from the manifest during assembly, so the raw `case.json` the
  gate validates does not carry it.

### B. Received-counter fix

- **FR4 — A func-app schema-validation failure increments both the received and the error counter.**
  A schema failure is a received-then-errored submission, so it must increment
  `migratedCaseSubmissionReceivedCounter` **and** `errorMigratedCaseSubmissionReceivedCounter`, matching
  framework-level schema failures.
- **FR4a — The gate failure description is tagged so the event processor counts it.** The Function App
  prefixes a gate validation-failure description with the `"JSON schema validation has failed"` marker
  that `StagingDlrmEventProcessor` keys the received counter on (`stagingContextErrors`). The event
  processor logic is unchanged — the fix is in the func-app description.

### C. caseUrn in the outcome

- **FR5 — The gate-rejection outcome carries the real `caseUrn`**, extracted from
  `migratedCase.caseDetails.prosecutorCaseReference` (as the happy path already does).
- **FR5a — Defensive extraction.** When the case JSON is malformed (a case-validation failure), fall back
  to `""` rather than throwing.

## Acceptance criteria

- **AC1** — A payload with an initiation code outside its source system's set is rejected by the gate,
  naming `initiationCode`; XHIBIT rejects `C`, LIBRA rejects `O`.
- **AC2** — No XHIBIT gate validation other than the `initiationCode` enum changes; the existing required
  fields and the absence of an `anyOf` at the gate are unchanged.
- **AC3** — Valid XHIBIT (`O`) and LIBRA (`C`/`Q`/`J`/`R`) payloads still pass; the only accepted-payload
  fixture change is the out-of-enum `initiationCode` value moved to a valid one.
- **AC4** — A func-app schema-validation failure (both a gate rejection and a framework-level failure)
  increments the received counter **and** the error counter.
- **AC5** — The outcome JSON for a gate rejection carries the extracted `caseUrn`; a malformed case JSON
  yields `""`.
- **AC6** — The LIBRA gate no longer declares `officerInCase` (or the other group-A extras), and a LIBRA
  payload omitting them still validates; the group-B fields are declared, with `courtRoomId`,
  `durationMinutes` and `prosecutorOffenceId` required.
- **AC7** — `mvn clean install` green.

## Out of scope

- Any domain-engine change; PCFDLRM; the `H` fixture question.
- Any XHIBIT gate change beyond the `initiationCode` enum; mirroring the XHIBIT `anyOf` at the gate.
- LIBRA gate group-C conflicts (FR6a) — code-vs-UUID, `observedEthnicity` type, `offenceDateCode` range.

## Notes for the design stage

- The gate keeps separate per-source-system schemas, so rules are structural, one schema per system —
  no rule engine, no source-system `if` in shared code (ADR-002 / ADR-004).
- `stagingContextErrors` in `StagingDlrmEventProcessor` is used only for the received-counter decision.
