# 02 — Design

- **Story:** [DD-43499](https://tools.hmcts.net/jira/browse/DD-43499) (epic
  [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995)) — stagingdlrm half
- **Inputs:** `00-input-brief.md` (decisions + S-1 – S-3). pcfdlrm half designed separately in
  `cpp-context-prosecution-casefile-dlrm` (same story directory name).
- **Baseline:** `origin/team/libra1` @ `0b678de`.
- **Delivery:** tests first (TDD).

## Summary

> **Changed at PR review:** C-1/C-2 (S-1, S-2) are dropped — the shared schema must not change, as it would affect XHIBIT. Only S-3 (informant on LIBRA Summons) is delivered; rows for C-1/C-2 below are kept for the record.

Two constraints on the shared API-level schema (S-1, S-2) and one LIBRA rule-engine rule (S-3) using
one new rule type. No new event, endpoint or schema file.

Existing format checks on the optional fields (`cpsOrganisation` length 7, `informant` ≤ 92,
`caseMarkers[].markerTypeCode` ≤ 3) **stay as they are**: a wrong format is rejected here (HTTP
400), as for XHIBIT. A well-formed but unrecognised value is dropped by pcfdlrm (its half of this
story).

## Change

| # | Req | File | Change |
|---|-----|------|--------|
| C-1 | S-1 | `stagingdlrm-domain-value-schema/.../json/schema/case-details.json` | `prosecutorCaseReference`: add `"pattern": "^[A-Za-z0-9-]+$"` (keeps `maxLength` 36). |
| C-2 | S-2 | `stagingdlrm-domain-value-schema/.../json/schema/migrationSourceSystem.json` | `migrationSourceSystemCaseIdentifier`: add `"maxLength": 100`. |
| C-3 | S-3 | `stagingdlrm-domain-aggregate/.../validation/RequiredWhenRule.java` (new) | Same as `RequiredFieldRule` plus a condition: the field is required only when the condition holds. Stateless and immutable per ADR-002, same `ValidationError` shape and message. |
| C-4 | S-3 | `stagingdlrm-domain-aggregate/.../validation/MigratedCaseValidationRuleEngine.java` (LIBRA list) | Add `RequiredWhenRule.of("$.migratedCase.caseDetails.informant", s -> "S".equals(initiationCode(s)), s -> caseDetails(s).getInformant())`. |

Both schema files are reached by the command API and command handler schemas through
`migrated/migrated-case.json`, so C-1/C-2 apply at the API with no other file change.

Failure outcomes: C-1/C-2 → HTTP 400 at the command API (no event). C-4 →
`migrated-case-submission-rejected` with one error at `$.migratedCase.caseDetails.informant`; not
forwarded to pcfdlrm.

## Options considered (C-3)

| Option | Verdict |
|---|---|
| **New `RequiredWhenRule` with a condition (chosen)** | The condition is stated in the rule, not hidden in a getter. One engine line. |
| `RequiredFieldRule` + helper returning a dummy non-null value when not `S` | Hides the condition behind a fake value; not a real reuse (unlike `presentOnEvery`, which is a genuine presence check). |
| Schema `anyOf`/`not` on the shared schema | Source-system conditional in a shared schema — ruled out by ADR-002. |

## Impact

- **XHIBIT:** C-1/C-2 also apply (shared schema). All 14 distinct `prosecutorCaseReference` values
  in the repo's fixtures already match the pattern. Live XHIBIT references cannot be checked from
  the repo; applying C-1/C-2 to XHIBIT is accepted. C-4 is LIBRA-only.
- **Empty string:** C-1 rejects `""` for `prosecutorCaseReference` (today it passes). It is a
  mandatory field, so this is intended.
- **Fixture:** `json/aggregate/libra/submission-valid-initiation-code-s.json` is the only Summons
  fixture and has no `informant`; it gains one so it stays valid. The IT LIBRA base fixture is
  initiation code `C`, so it is unaffected.

## Tests (written first, expected to fail before C-1 – C-4)

| Level | Test | Expectation |
|---|---|---|
| Unit | `MigratedCaseSubmissionSchemaContractTest` reject row: `prosecutorCaseReference` `AB_12` | fails pattern (C-1) |
| Unit | same, accept row: `prosecutorCaseReference` `AB-12cd` | accepted |
| Unit | same, reject row: `migrationSourceSystemCaseIdentifier` 101 chars | fails maxLength (C-2) |
| Unit | same, accept row: `migrationSourceSystemCaseIdentifier` exactly 100 chars | accepted |
| Unit | `MigratedCaseValidationRuleEngineTest`: LIBRA `S` without `informant` | one error at `$.migratedCase.caseDetails.informant` (C-4); same submission under XHIBIT has no such error |
| Unit | same, existing `aLibraSubmissionWithInitiationCodeSPassesEveryRule` | stays green once fixture carries `informant` |
| Unit | same, `aLibraNonSummonsSubmissionWithoutInformantIsAccepted` — runs for `C`, `Q`, `J`, `R` | no errors (rule applies to `S` only) |
| IT | `ValidationRuleRejectionIT`: one test mutating `LIBRA_BASE` to carry both C-1 and C-2 violations, one POST, both 400 messages asserted — same shape as `ReceiveXhibitCaseFileSubmissionIT.shouldRaiseBadRequest` (no new fixture) | after unit gate |
| IT | `ValidationRuleRejectionIT.rejectionScenarios()` row "LIBRA Summons missing informant": `LIBRA_BASE`, mutator sets `initiationCode` `S` (base has no `informant`) | after unit gate |

The IT row needs a small set-field mutator beside the existing remove helpers.

## ADR

Not needed — follows ADR-002 (structural in schema, per-source in engine) and the existing rule
shapes.

## Open questions

1. ~~C-1 applies to XHIBIT too.~~ Resolved: C-1 and C-2 apply to XHIBIT as well — accepted.
2. Schema-test expected messages are the validator's exact strings (matcher uses `equals`); confirm
   at implementation if wording differs.
