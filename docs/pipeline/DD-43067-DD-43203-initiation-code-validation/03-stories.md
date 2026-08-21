# Stories — DD-43203: initiation-code update + validation

> Stage 3 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [`02-design.md`](./02-design.md),
> [ADR-002](../adrs/002-source-system-keyed-dispatch.md), [ADR-003](../adrs/003-libra-payload-contract.md).

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43203](https://tools.hmcts.net/jira/browse/DD-43203) — initiation-code update + validation (size **S**) |
| Repo | `cpp-context-stagingdlrm` |
| Enhances | DD-43081 (delivered); func-app half is a later DD-43086 enhancement |

One story, one atomic change. Widening the schema without the rule silently loosens XHIBIT; the rule
without the widening cannot deserialize a LIBRA code. They merge together.

## Findings — verified state of the code

- **F1** — `case-details.json` `initiationCode` is `enum: ["O"]`, `required`; the generated field is a
  single-constant enum. `[scope-affecting]`
- **F2** — `MigratedCaseConvertor.buildCaseDetails` calls `.getInitiationCode().name()` — widening
  (not dropping) the enum keeps this compiling. `[scope-affecting]`
- **F3** — `MigratedCaseValidationRuleEngine` already dispatches a `static final` map keyed by
  `MigrationSourceSystemName`; the rejection path (Rejected + Processed(false), never Received) is in
  place from DD-43081. No initiation-code rule exists yet.
- **F4** — Every XHIBIT `src` fixture uses `"O"`; every LIBRA `src` fixture used `"O"` because the old
  enum forbade anything else. `[scope-affecting]`
- **F5** — `dlrm-libra-0.13.1.json` declares `initiationCode` `["C","Q","J","R"]`.

## Sub-tasks

### DD-43203a — Widen enum + add rule + fixtures

**Size:** S · **Depends on:** nothing

> As a **migration engineer submitting a LIBRA case file**, I want **LIBRA's real initiation codes
> accepted and out-of-contract codes refused with a named reason**, so that **a valid LIBRA case is
> not a 4xx and a bad code is not silently forwarded**.

#### Scope

| Artefact | Change |
|---|---|
| `case-details.json` | `initiationCode.enum` → `["C","Q","J","R","O"]` (LIBRA set + XHIBIT `O`) |
| `InitiationCodeValidationRule.java` (new) | allowed-values rule, static `withAllowedValues(getter, codes…)` factory |
| `MigratedCaseValidationRuleEngine.java` | register under XHIBIT (`"O"`) and LIBRA (`"C","Q","J","R"`); add `initiationCode(...)` helper |
| aggregate / event-processor / integration LIBRA fixtures | `"O"` → `"C"`; add `submission-invalid-initiation-code.json` for XHIBIT and LIBRA |
| `MigratedCaseValidationRuleEngineTest`, `MigratedCaseSubmissionRejectionTest` | initiation-code cases |

`MigratedCaseConvertor` and `stagingdlrm-command-handler` are **not** touched.

#### Acceptance criteria

- [ ] AC1: LIBRA `C`/`Q`/`J`/`R` deserializes and passes validation.
- [ ] AC2: LIBRA `O` deserializes but is rejected at `$.migratedCase.caseDetails.initiationCode`;
  the aggregate emits `Rejected` + `Processed(false, VALIDATION_FAILED)` and no `Received`.
- [ ] AC3: XHIBIT with any code ≠ `O` is rejected at the same path.
- [ ] AC4: XHIBIT `O` passes; every DD-43078 XHIBIT whole-payload fixture is byte-identical.
- [ ] AC5: the rule message contains none of `JSON_SCHEMA`, `Duplicate Submission ID`,
  `Case Already exists in progression`.
- [ ] AC6: `mvn clean install` green; the generated `InitiationCode` enum has the five constants
  `C,Q,J,R,O`; no hand-edited generated sources.

#### Definition of done

- [ ] One rule class, two configured instances (no class per source system).
- [ ] No `if`/`switch` on source system outside the map (ADR-002 compliance).
- [ ] No converter change.

## Out of scope for the whole story

- Func-app gate schemas (`stagingdlrm-azure-functions`) — DD-43086, later.
- PCFDLRM `CaseType` routing from the initiation code; the `H` test-fixture question
  (workbook-corrections item 7).

## Notes carried forward

- LIBRA's routing into PCFDLRM's `CcProsecutionValidationRuleProvider` depends on the agreed code set;
  this story makes the codes valid and enforced in stagingDLRM, not the downstream routing.
