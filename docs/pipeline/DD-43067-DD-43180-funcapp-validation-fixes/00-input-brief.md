# Input brief — LIBRA enabler: Function App validation parity + counter/outcome fixes

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this directory.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43180](https://tools.hmcts.net/jira/browse/DD-43180) — Function App validation parity + counter/outcome fixes |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |
| Builds on | [DD-43203](../DD-43067-DD-43203-initiation-code-validation/00-input-brief.md) — domain-layer initiation-code validation (delivered) |

## The epic this story belongs to

**DD-43067 — LIBRA enabler.** Ingest magistrates' court case files from legacy system LIBRA through the
shared DLRM pipeline (Azure Blob → Function App → stagingDLRM → PCFDLRM → Progression). Source-system
behaviour is pluggable and source-system-keyed — see
[ADR-002](../adrs/002-source-system-keyed-dispatch.md).

## This story's request

DD-43203 added per-source-system validation in the **domain** aggregate
(`MigratedCaseValidationRuleEngine`) but deliberately left the **Function App gate** untouched. Three
gaps remain, all in `stagingdlrm-azure-functions` and the event processor.

### 1 — The gate does not enforce the same validation

The func-app schemas accept any `initiationCode` (no enum), so a payload the domain rules will reject on
its initiation code still passes the gate, is enqueued and POSTed, and fails later — the
enqueue-then-4xx failure mode [ADR-003 §6](../adrs/003-libra-payload-contract.md) warns about. DD-43180
adds the per-source-system `initiationCode` enum to each gate schema so the gate rejects it up front. The
func-app keeps separate schemas per source system, so this is purely structural — no rule engine, no
source-system conditionals. **The XHIBIT gate is otherwise left untouched** (its existing required fields
stay as they are; the XHIBIT `anyOf` is not added). On the LIBRA side the presence rules already hold at
the gate, so the only LIBRA addition is its `initiationCode` enum. Recorded as
[ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md), which amends ADR-002 rule 7.

### 2 — Received counter not incremented on a func-app schema failure

A func-app schema-validation failure is a submission that was received into staging and then errored, so
it must increment **both** the received counter and the error counter. Framework-level schema failures
already do — their `"JSON schema validation has failed"` description matches the marker
`StagingDlrmEventProcessor` keys the received counter on (`stagingContextErrors`). But a Function App
**gate** rejection carries only JSON-path validation messages (e.g.
`$.migratedCase.caseDetails.initiationCode: does not have a value in the enumeration [O]`) with **no**
such marker, so it increments only the error counter. The gate failure description must carry the marker
so it counts as received too.

### 3 — `caseUrn` empty in the outcome file

On the gate schema-failure path the func-app hard-codes `caseUrn = ""`, so the outcome JSON is written
with an empty URN. Every other path threads `caseUrn` correctly. It should be extracted from
`migratedCase.caseDetails.prosecutorCaseReference`, exactly as the happy path already does.

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Gate validation scope | Add the per-source-system `initiationCode` enum only. **Do not touch current XHIBIT gate validation** beyond that; focus on LIBRA. The XHIBIT `anyOf` and LIBRA `courtRoomId` rules stay domain-only |
| Received counter | A func-app schema failure must increment the received counter (**as well as** error). The gate rejection is tagged with the `"JSON schema validation has failed"` marker so the event processor counts it as received, matching the framework-level behaviour. The event processor's `stagingContextErrors` logic is unchanged |
| ADR | Record the gate/domain duplication as a new ADR-004 amending ADR-002 rule 7 |

## Scope boundaries

| In scope | Out of scope |
|---|---|
| `stagingdlrm-azure-functions` gate schemas (`case-details.json`, `libra.case-submission.json`) | Any domain-engine change — DD-43203 owns the authority |
| `stagingdlrm-event-processor` — received-counter decision | `MigratedCaseConvertor`, PCFDLRM |
| `stagingdlrm-azure-functions` `TimerTriggerJava` — `caseUrn` extraction | The `H` test-fixture question (workbook-corrections item 7) |

### 4 — The LIBRA gate schema has drifted from the 0.13.1 contract

`libra.case-submission.json` (the func-app LIBRA gate) predates the LIBRA 0.13.1 payload contract
([`dlrm-libra-0.13.1.json`](../../analysis/libra-ingestion/schema/libra/dlrm-libra-0.13.1.json)) and has
drifted from it. It still declares `officerInCase` (which 0.13.1 no longer sends) among other stale
fields, and is missing fields 0.13.1 requires. DD-43180 reconciles the gate to 0.13.1 — **group A+B**
(remove the extras, add the missing fields). Group C (semantic conflicts: `plea`/`verdict`/
`allocationDecision` code-vs-UUID, `observedEthnicity` type, `offenceDateCode` range) is left for the
extract team and raised as a follow-up.

| A — remove (not in 0.13.1) | B — add (required/declared by 0.13.1) |
|---|---|
| `migratedCase.officerInCase` (+ its definition); `caseDetails.summonsCode`, `writtenChargePostingDate`; `defendant.telephoneNumberBusiness`, `organisationTelephoneNumber`; `offence.backDuty`, `backDutyDateFrom`, `backDutyDateTo`, `convictionDate` | `hearing.courtRoomId` (req), `hearing.durationMinutes` (req); `offence.prosecutorOffenceId` (req), `convictingCourtCode`; `defendant.emailAddress1`, `emailAddress2`; `personalInformation.address`; `individualAlias.title` |

## Rules left domain-only

One engine rule is deliberately not mirrored at the gate:
- The XHIBIT `anyOf(dateOfCommittal, dateOfSending)` — excluded because DD-43180 does not touch XHIBIT
  gate validation beyond the initiation code.

The LIBRA `hearings[*].courtRoomId` rule **is** now mirrored — change 4 adds `courtRoomId` to the gate
hearing (required), so the earlier "no such gate field" limitation no longer holds. Recorded in
[ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md) §3.

## Supporting analysis

- [ADR-002](../adrs/002-source-system-keyed-dispatch.md), [ADR-003](../adrs/003-libra-payload-contract.md),
  [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md).
- DD-43203 story directory — the domain-layer rules this gate mirrors.
