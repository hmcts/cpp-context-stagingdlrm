# Stories — DD-43248: admit LIBRA initiation code `S`

> Stage 3 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [`02-design.md`](./02-design.md),
> [ADR-002](../adrs/002-source-system-keyed-dispatch.md), [ADR-003](../adrs/003-libra-payload-contract.md).

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43248](https://tools.hmcts.net/jira/browse/DD-43248) — admit LIBRA initiation code `S` (size **S**) |
| Repo | `cpp-context-stagingdlrm` |
| Enhances | DD-43203 (delivered) |

One story, one atomic change. Adding `S` at only some of the four gates leaves a LIBRA `S` rejected at
the others, so they ship together.

## Findings — verified state of the code

- **F1** — Canonical `case-details.json` `initiationCode` is `enum: ["C","Q","J","R","O"]`; the
  generated `InitiationCode` has five constants. `[scope-affecting]`
- **F2** — `migrated/migrated-case.json` `$ref`s the canonical `case-details.json`, so the aggregate's
  `MigratedCaseSubmission` shares that enum — one edit updates both. `[scope-affecting]`
- **F3** — `MigratedCaseValidationRuleEngine` LIBRA set is `"C","Q","J","R"`; XHIBIT is `"O"`. The
  rule class is generic — the set lives in the map.
- **F4** — Func-app `libra.case-submission.json` `initiationCode.enum` is `["C","Q","J","R"]`
  (self-contained); the XHIBIT gate (`case-details.json`) is `["O"]`. `[scope-affecting]`
- **F5** — `dlrm-libra-0.13.1.json` `initiationCode.enum` is `["C","Q","J","R"]`.
- **F6** — `.getInitiationCode().name()` in `MigratedCaseConvertor` means widening (not narrowing) the
  enum keeps it compiling.

## Sub-tasks

### DD-43248a — Add `S` to LIBRA's initiation code

**Size:** S · **Depends on:** nothing

> As a **migration engineer submitting a LIBRA case with initiation code `S`**, I want **it accepted
> end to end**, so that **a valid `S` case is not a 4xx and XHIBIT still cannot send `S`.**

#### Scope

| Artefact | Change |
|---|---|
| `case-details.json` (canonical) | `initiationCode.enum` → `["C","Q","J","R","O","S"]` |
| `MigratedCaseValidationRuleEngine.java` | LIBRA set → `"C","Q","J","R","S"`; XHIBIT untouched |
| `libra.case-submission.json` (func-app LIBRA gate) | `initiationCode.enum` gains `"S"` |
| `dlrm-libra-0.13.1.json` (extract contract) | `initiationCode.enum` gains `"S"` |
| `MigratedCaseValidationRuleEngineTest` | LIBRA `S` accepted; `S` not permitted for XHIBIT |
| `libra/submission-valid-initiation-code-s.json` (new fixture) | LIBRA valid payload with `"S"` |
| `JsonSchemaValidatorTest` | LIBRA gate accepts `S` |

`InitiationCodeValidationRule.java`, `MigratedCaseConvertor`, the provenance sidecar, the XHIBIT set
and the func-app XHIBIT gate are **not** touched.

#### Acceptance criteria

- [ ] AC1: LIBRA `"S"` deserializes and passes aggregate validation.
- [ ] AC2: XHIBIT `"S"` is rejected at `$.migratedCase.caseDetails.initiationCode`.
- [ ] AC3: the func-app LIBRA gate accepts `"S"`; the XHIBIT gate still rejects any code ≠ `O`.
- [ ] AC4: every existing `C/Q/J/R`/`O` fixture stays valid — no regression.
- [ ] AC5: `mvn clean install` green; the generated `InitiationCode` enum has six constants
  `C,Q,J,R,O,S`; no hand-edited generated sources.

#### Definition of done

- [ ] One code added per gate; no new rule class, no new rule instance.
- [ ] No `if`/`switch` on source system outside the map (ADR-002).
- [ ] No converter change; XHIBIT behaviour net-zero.

## Out of scope for the whole story

- XHIBIT permitted set and the func-app XHIBIT gate.
- Removing `R`; PCFDLRM `CaseType` routing from the initiation code.

## Notes carried forward

- LIBRA's routing into PCFDLRM's `CcProsecutionValidationRuleProvider` is keyed off the code; this
  story makes `S` valid and enforced in stagingDLRM, not the downstream routing.
