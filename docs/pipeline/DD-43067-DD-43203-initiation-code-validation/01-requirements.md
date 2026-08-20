# Requirements — DD-43203: initiation-code update + validation

> Stage 1 artefact. Source: [`00-input-brief.md`](./00-input-brief.md). Feeds
> [`02-design.md`](./02-design.md).

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43203](https://tools.hmcts.net/jira/browse/DD-43203) — initiation-code update + validation |
| Repo | `cpp-context-stagingdlrm` |

## Story

### Summary (JIRA summary line)

Widen the canonical `initiationCode` enum so LIBRA payloads deserialize, and enforce each source
system's permitted code set as a business validation rule.

### User story

As a **migration engineer submitting a LIBRA case file**, I want **the pipeline to accept LIBRA's
real initiation codes and to reject a code the source system is not permitted to send**, so that **a
valid LIBRA case is not a terminal 4xx for its initiation code, and an out-of-contract code is
refused with a named reason rather than silently forwarded**.

## Requirements

### A. Schema

- **FR1 — Widen the canonical enum.** `case-details.json` `initiationCode.enum` becomes
  `["C","Q","J","R","O"]` — the union of LIBRA's `C,Q,J,R` and XHIBIT's `O`. The field stays
  `type: string`, stays a typed enum, stays in `required`. No other constraint changes. Only codes a
  source system is evidenced to send are admitted (`S`/`Z` are not).
- **FR2 — No converter change.** Because the field stays a typed enum,
  `MigratedCaseConvertor.buildCaseDetails`'s `.getInitiationCode().name()` compiles unchanged and the
  value reaches PCFDLRM as before.

### B. Validation rules

- **FR3 — Add `InitiationCodeValidationRule`** in
  `stagingdlrm-domain-aggregate/.../aggregate/validation/`, implementing `MigratedCaseValidationRule`
  with a static factory, mirroring `RequiredFieldRule` — stateless, immutable, source-system-agnostic
  (ADR-002 rule 2/4).
- **FR4 — Register it per source system** in `MigratedCaseValidationRuleEngine`'s static `RULES` map:
  XHIBIT allowed `["O"]`; LIBRA allowed `["C","Q","J","R"]`. Dispatch stays in the map; the rule
  carries no `appliesTo` (ADR-002).
- **FR5 — Reject with a named path.** A code outside the source system's set produces a
  `ValidationError` at `$.migratedCase.caseDetails.initiationCode`. The message must not contain
  `JSON_SCHEMA`, `Duplicate Submission ID` or `Case Already exists in progression` as a substring
  (the `sendEventToGrid` sentinels — DD-43081 FR13e).
- **FR6 — A null code is not this rule's concern.** Presence is enforced by the schema `required`;
  the rule only checks membership when a code is present.

### C. Regression

- **FR7 — XHIBIT net-zero.** Widening the schema now admits all seven codes to XHIBIT; the XHIBIT
  `["O"]` rule re-imposes the old constraint. Every XHIBIT whole-payload fixture from DD-43078 must
  pass byte-identically.
- **FR8 — LIBRA fixtures move off `O`.** Existing LIBRA fixtures carry `"O"` only because the old
  enum forced it; they move to a valid LIBRA code so the new LIBRA rule passes them.

## Acceptance criteria

- **AC1** — A LIBRA payload with `initiationCode` `C`/`Q`/`J`/`R` deserializes and passes validation.
- **AC2** — A LIBRA payload with `O` deserializes (schema) but is rejected by the LIBRA rule at the
  initiation-code path, and is **not** forwarded (no `Received`, no pcfdlrm POST).
- **AC3** — An XHIBIT payload with any code other than `O` is rejected by the XHIBIT rule.
- **AC4** — An XHIBIT payload with `O` passes, and all DD-43078 XHIBIT fixtures are byte-identical.
- **AC5** — `mvn clean install` green; the generated `InitiationCode` enum has the five constants
  `C,Q,J,R,O`; no hand-edited generated sources.

## Out of scope

- Func-app gate schemas (`stagingdlrm-azure-functions`) — DD-43086, later enhancement.
- Any converter or PCFDLRM change; PCFDLRM `CaseType` routing from the code.

## Notes for the design stage

- One rule class, two configured instances (ADR-002 rule 4) — do not write a class per source system.
- Read the code via the engine's existing `caseDetails(...)` helper; add a small `initiationCode(...)`
  helper returning `getInitiationCode()==null ? null : .name()`.
