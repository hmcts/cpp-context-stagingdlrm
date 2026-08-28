# Stories — DD-43225: Address mandatoriness realignment

> Stage 3 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [`02-design.md`](./02-design.md),
> [ADR-002](../adrs/002-source-system-keyed-dispatch.md),
> [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md).

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43225](https://tools.hmcts.net/jira/browse/DD-43225) — Address mandatoriness realignment (size **S**) |
| Repo | `cpp-context-stagingdlrm` |
| Builds on | DD-43180 (delivered) — the gate reconciled to the 0.13.1 contract |

Two independently reviewable sub-tasks, opposite directions on two different addresses.

## Findings — verified state of the code

- **F1** — `defendant.address` was required for LIBRA in three places: the rule engine
  (`defendants[*].address`), the gate `defendant.required`, and 0.13.1. XHIBIT required it nowhere.
  `[scope-affecting]`
- **F2** — `individual.personalInformation.address` was required nowhere at runtime: the shared
  `personal-information.json` required only `surname`, the LIBRA gate `personalInformation` required only
  `surname`, and there was no rule. The provenance sidecar's deviation flagged it for enforcement.
  `[scope-affecting]`
- **F3** — The XHIBIT gate is shallow: it does not model `defendants`/`individual`/`personalInformation`,
  so XHIBIT's only enforcement point for the individual address is the canonical command schema.
- **F4** — `dlrm-libra-0.13.json` is generated from a workbook that is **not in the repo**; the version in
  git history is older and does not reproduce the committed artefacts.

## DD-43225a — Defendant address optional for LIBRA

**Size:** S · **Depends on:** nothing

> As an **operator running LIBRA migrations**, I want **a case with no defendant-level address accepted**,
> so that **real LIBRA data is not rejected for a field it does not always send**.

### Scope

| Artefact | Change |
|---|---|
| `MigratedCaseValidationRuleEngine.java` | remove the LIBRA `defendants[*].address` rule + unused helper/import |
| `libra.case-submission.json` | `defendant.required` − `address` |
| `dlrm-libra-0.13.1.json` | `defendant.required` − `address` |
| `MigratedCaseValidationRuleEngineTest`, `ValidationRuleRejectionIT`, `JsonSchemaValidatorTest` | flip to accept / drop rejection scenario / add accept test |

### Acceptance criteria

- [ ] AC1: a LIBRA submission with no `defendant.address` is accepted by the gate and the rule engine.
- [ ] AC2: XHIBIT is unchanged.
- [ ] AC3: a supplied `defendant.address` still validates against its sub-field constraints.
- [ ] AC4: gate ↔ 0.13.1 parity holds.

## DD-43225b — Individual address mandatory for LIBRA and XHIBIT

**Size:** S · **Depends on:** nothing

> As an **operator running LIBRA and XHIBIT migrations**, I want **every case to carry the individual's
> address**, so that **the address we rely on downstream is always present**.

### Scope

| Artefact | Change |
|---|---|
| `personal-information.json` | `required: ["surname","address"]` — enforces both source systems at the command layer |
| `libra.case-submission.json` | `personalInformation.required` + `address` |
| `dlrm-libra-0.13.1.json` | `personalInformation.required` + `address` |
| `JsonSchemaValidatorTest`, `MigratedCaseSubmissionSchemaContractTest` | add reject tests; builders/fixtures gain an address |
| LIBRA IT fixture + 6 `aggregate/libra/*.json` | add `individual.personalInformation.address` |

### Acceptance criteria

- [ ] AC1: a payload whose `individual.personalInformation` omits `address` is rejected — canonical schema
  (both source systems) and LIBRA gate.
- [ ] AC2: no new source-system rule is added (schema enforces).
- [ ] AC3: the parent-guardian block (reusing the definition) is covered.
- [ ] AC4: gate ↔ 0.13.1 parity holds.

## Not delivered here — follow-up

- **DD-43225c (deferred) — regenerate `dlrm-libra-0.13.json`.** Requires the current workbook (absent from
  the repo). Set `defendant/address1` (row 82) to `O`, add the individual-address mandatoriness, run
  `regenerate.sh`, accepting the shared-`address` intersection. Divergence recorded in
  `libra-workbook-corrections.md`.

## Out of scope for the whole story

- XHIBIT gate schemas; PCFDLRM; the converter.
- A code-level split of the shared `address` definition.

## Notes

- The affected unit-test modules build green: `domain-value-schema`, `domain-aggregate`,
  `azure-functions`. Integration tests need Docker and were not run.
- Gate ↔ domain parity is governed by ADR-004.
