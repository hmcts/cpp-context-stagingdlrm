# Stories — DD-43499: case-level business validation (stagingdlrm half)

> Stage 3 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`02-design.md`](./02-design.md), [ADR-002](../adrs/002-source-system-keyed-dispatch.md).

| | |
|---|---|
| Epic | [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995) |
| Story | [DD-43499](https://tools.hmcts.net/jira/browse/DD-43499) — Business validation for Case level items (size **S**) |
| Repo | `cpp-context-stagingdlrm` |
| Other half | pcfdlrm — `cpp-context-prosecution-casefile-dlrm` PR #38 |

> **Changed at PR review:** C-1/C-2 (S-1, S-2) are dropped — the shared schema must not change, as it would affect XHIBIT. Only S-3 (informant on LIBRA Summons) is delivered; rows for C-1/C-2 below are kept for the record.

One story, one change set: two schema constraints and one LIBRA rule. Azure Functions intake is out
of scope.

## Findings — verified state of the code (`origin/team/libra1` @ `0b678de`)

- **F1** — `case-details.json` `prosecutorCaseReference` is `maxLength` 36, no pattern.
- **F2** — `migrationSourceSystem.json` `migrationSourceSystemCaseIdentifier` (the source system's own
  case number, sent in the payload) is required but has no `maxLength`. Not to be confused with the
  CPP `caseId`, generated via system-id-mapper — not touched.
- **F3** — Both files are reached from the command API and command handler schemas through
  `migrated/migrated-case.json`, so one edit covers both. Shared by XHIBIT and LIBRA.
- **F4** — `informant` exists only for LIBRA (added in DD-43081), is optional, `maxLength` 92, and is
  not sent downstream. XHIBIT never sends it.
- **F5** — The LIBRA rule list in `MigratedCaseValidationRuleEngine` has no `informant` rule.
  `RequiredFieldRule` has no condition, so a new rule type is needed.
- **F6** — The only Summons (`S`) fixture, `json/aggregate/libra/submission-valid-initiation-code-s.json`,
  has no `informant`. The IT LIBRA base fixture is code `C`.

## DD-43499 — Validate LIBRA case-level fields

**Size:** S · **Depends on:** nothing

> As a **migration engineer submitting a LIBRA case**, I want **a case with a badly formed case
> reference or LIBRA case number, or a Summons case with no informant, to be rejected in stagingdlrm**,
> so that **it is not passed on to pcfdlrm.**

### Scope

| Artefact | Change |
|---|---|
| `case-details.json` | `prosecutorCaseReference` gains `"pattern": "^[A-Za-z0-9-]+$"` |
| `migrationSourceSystem.json` | `migrationSourceSystemCaseIdentifier` gains `"maxLength": 100` |
| `RequiredWhenRule.java` (new) | Field required only when a condition holds; same error shape as `RequiredFieldRule` |
| `MigratedCaseValidationRuleEngine.java` | LIBRA list gains `informant` required when initiation code is `S` |
| `MigratedCaseSubmissionSchemaContractTest` | Reject rows for C-1 and C-2; accept row for a valid reference with a hyphen |
| `MigratedCaseValidationRuleEngineTest` | LIBRA `S` without `informant` rejected |
| `libra/submission-valid-initiation-code-s.json` | Gains `informant` |
| `libra/submission-summons-missing-informant.json` (new fixture) | LIBRA `S` payload without `informant` |
| `ValidationRuleRejectionIT` | One test mutating `LIBRA_BASE` with both C-1/C-2 violations: one POST, both 400 messages |
| `ValidationRuleRejectionIT` | One row: LIBRA Summons missing informant; small set-field mutator |

Not touched: Azure Functions, the XHIBIT rule list, the format checks on `cpsOrganisation`,
`informant` length and `caseMarkers[].markerTypeCode`, and the converter to pcfdlrm.

### Acceptance criteria

- [ ] ~~AC1~~ (dropped): a case whose `prosecutorCaseReference` has a character other than a letter, digit or
  hyphen is rejected with HTTP 400 (LIBRA and XHIBIT).
- [ ] ~~AC2~~ (dropped): a case whose source-system case number (`migrationSourceSystemCaseIdentifier`) is over 100 characters is rejected with
  HTTP 400 (LIBRA and XHIBIT).
- [ ] AC3: a LIBRA case with initiation code `S` and no `informant` is rejected with one validation
  error at `$.migratedCase.caseDetails.informant` and is not forwarded to pcfdlrm.
- [ ] AC4: a LIBRA case with code `S` and an `informant`, or any other code without one, is accepted.
- [ ] AC5: XHIBIT behaviour is otherwise unchanged; every existing fixture stays valid.
- [ ] AC6: `mvn clean install` green and `./runIntegrationTests.sh` green, with the two new IT
  checks included.

### Definition of done

- [ ] No `if`/`switch` on source system outside the rule map (ADR-002).
- [ ] No XHIBIT-only fields in LIBRA test data.
- [ ] No hand-edited generated sources.

## Out of scope for the whole story

- Azure Functions intake schemas.
- Generating `prosecutorCaseReference`; uniqueness of `migrationSourceSystemCaseIdentifier`.
- Dropping bad optional values — pcfdlrm does this for well-formed but unrecognised values.
