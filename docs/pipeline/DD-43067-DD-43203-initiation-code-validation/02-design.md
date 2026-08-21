# Design — DD-43203: initiation-code update + validation

> Stage 2 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [ADR-002](../adrs/002-source-system-keyed-dispatch.md),
> [ADR-003](../adrs/003-libra-payload-contract.md).

## Pattern

No new pattern. The rejection path and the keyed rule engine already exist (DD-43081 T1/T2/T5). This
story widens one schema enum and adds one more rule instance under each source-system key — the exact
`InitiationCodeValidationRule.withAllowedValues(...)` shape ADR-002 rule 4 anticipated.

## Scope map (requirement → artefact)

| Req | Artefact | Change |
|---|---|---|
| FR1 | `stagingdlrm-domain-value-schema/.../json/schema/case-details.json` | `initiationCode.enum` → `["C","Q","J","R","O"]` |
| FR3 | `stagingdlrm-domain-aggregate/.../aggregate/validation/InitiationCodeValidationRule.java` | new rule class |
| FR4 | `MigratedCaseValidationRuleEngine.java` | one instance under `XHIBIT` (`"O"`), one under `LIBRA` (`"C","Q","J","R"`); new `initiationCode(...)` helper |
| FR2 | `MigratedCaseConvertor.java` | **none** — `.name()` still compiles |
| FR7/FR8 | aggregate + event-processor + integration LIBRA fixtures | `initiationCode` `"O"` → `"C"`; two new rejection fixtures |

## FR1 — Schema

`enum: ["O"]` compiles the generated `initiationCode` POJO field to a single-constant Java enum, so a
LIBRA `"C"` is an `InvalidFormatException` at deserialization. Adding constants keeps it a typed enum;
`.getInitiationCode().name()` returns the constant name (`"C"`), so the converter and everything
downstream are untouched. The enum admits exactly the union of the two systems' sets — `C,Q,J,R,O` —
and no more: the schema is a structural gate for values that provably occur, while the *business* set
(which code each source system may use) is pinned by the rule, where a source-system fact belongs
(ADR-002 rule 7). ADR-003's note of a wider platform enum (`…InitiationCode` = `Q,R,S,C,J,Z,O`) is not
adopted verbatim — `S` and `Z` are added only if a source system is later shown to send them.

## FR3 / FR4 — Rule and wiring

`InitiationCodeValidationRule` holds an immutable `Set<String>` of allowed values and a
`Function<MigratedCaseSubmission,String>` getter; `apply` returns one `ValidationError` at
`$.migratedCase.caseDetails.initiationCode` when the code is non-null and not in the set. Registered:

```java
XHIBIT → …, InitiationCodeValidationRule.withAllowedValues(this::initiationCode, "O")
LIBRA  → …, InitiationCodeValidationRule.withAllowedValues(this::initiationCode, "C", "Q", "J", "R")
```

The engine gains a private `initiationCode(submission)` returning `null` when the code is absent (the
schema `required` owns presence), else `.name()`. The rule is stateless, so it is safe in the
`static final` map shared across aggregate instances (ADR-002 rule 3).

## Rejection behaviour (unchanged, reused)

A membership failure flows through the existing DD-43081 branch: the aggregate appends
`MigratedCaseSubmissionRejected` + `MigratedCaseSubmissionProcessed(processingIsSuccessful=false,
description=VALIDATION_FAILED)` and **never** `MigratedCaseSubmissionReceived`, so nothing is forwarded
to pcfdlrm. No `apply()` branch, no new event, no handler change.

## FR7 / FR8 — Fixtures

XHIBIT fixtures stay on `"O"` — they are the regression gate and must not move. LIBRA fixtures were on
`"O"` only because the old enum forbade anything else; they move to `"C"`. Two new fixtures drive the
rule's rejection cases: a LIBRA payload with `"O"` and an XHIBIT payload with `"C"` — each
schema-valid, each rejected by its source system's rule.

## Out of scope

Func-app gate schemas (DD-43086). PCFDLRM `CaseType` routing. Any converter change.

## Testing approach (for Stage 4 — informative)

Extend the existing engine and rejection tests (no new test class, source system stays a parameter):
LIBRA `O` rejected, XHIBIT non-`O` rejected, valid codes pass each system, cross-system isolation, and
the aggregate emits `Rejected`+`Processed(false)` and no `Received` on a LIBRA `O`.
