# Requirements — DD-43248: admit LIBRA initiation code `S`

> Stage 1 artefact. Source: [`00-input-brief.md`](./00-input-brief.md). Feeds
> [`02-design.md`](./02-design.md).

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43248](https://tools.hmcts.net/jira/browse/DD-43248) — admit LIBRA initiation code `S` |
| Repo | `cpp-context-stagingdlrm` |

## Story

### Summary (JIRA summary line)

Admit initiation code `S` for LIBRA across the canonical enum, the validation rule and the Function
App LIBRA gate, leaving XHIBIT unchanged.

### User story

As a **migration engineer submitting a LIBRA case file with initiation code `S`**, I want **the
pipeline to accept it end to end**, so that **a valid `S` case is not a terminal 4xx at the Function
App gate, at deserialization, or at the aggregate rule.**

## Requirements

### A. Canonical schema

- **FR1 — Widen the canonical enum.** `case-details.json` `initiationCode.enum` becomes
  `["C","Q","J","R","O","S"]`. Field stays `type: string`, a typed enum, and `required`. The generated
  `InitiationCode` enum (and the migrated model that `$ref`s it) gains an `S` constant, so a LIBRA `S`
  deserializes.
- **FR2 — No converter change.** `.getInitiationCode().name()` still compiles and returns `"S"`.

### B. Validation rule

- **FR3 — LIBRA allowed set gains `S`.** In `MigratedCaseValidationRuleEngine`, the LIBRA
  `InitiationCodeValidationRule` set becomes `["C","Q","J","R","S"]`. No new class, no new rule
  instance, no `if`/`switch` on source system — one added string.
- **FR4 — XHIBIT unchanged.** XHIBIT stays `["O"]`; `S` is not permitted for XHIBIT.

### C. Function App LIBRA gate

- **FR5 — LIBRA gate gains `S`.** `libra.case-submission.json` `initiationCode.enum` becomes
  `["C","Q","J","R","S"]`, so the gate accepts `S` (never more lenient than canonical, never
  stricter for a code canonical admits — ADR-003 decision 6). The XHIBIT gate is untouched.

### D. Extract schema

- **FR6 — LIBRA extract schema gains `S`.** `dlrm-libra-0.13.1.json` `initiationCode.enum` gains `S`,
  keeping the shared extract contract aligned with what LIBRA sends. Its provenance sidecar is not
  regenerated (this is a contract override, as `R` was in DD-43203).

## Acceptance criteria

- **AC1** — A LIBRA payload with `initiationCode: "S"` deserializes and passes aggregate validation.
- **AC2** — An XHIBIT payload with `"S"` is rejected by the XHIBIT rule at
  `$.migratedCase.caseDetails.initiationCode`.
- **AC3** — The Function App LIBRA gate accepts a LIBRA payload with `"S"`; the XHIBIT gate still
  rejects any code other than `O`.
- **AC4** — Existing `C/Q/J/R` (LIBRA) and `O` (XHIBIT) behaviour is byte-identical; no fixture that
  was valid becomes invalid.
- **AC5** — `mvn clean install` green; the generated `InitiationCode` enum has six constants
  `C,Q,J,R,O,S`; no hand-edited generated sources.

## Out of scope

- XHIBIT permitted set; the func-app XHIBIT gate.
- Removing `R`; PCFDLRM `CaseType` routing from the code; any converter change.

## Notes for the design stage

- Four gates, one code each — no structural change. Reuse the DD-43203 rule and rejection path as-is.
