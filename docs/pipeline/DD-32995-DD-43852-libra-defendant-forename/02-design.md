# 02 — Design

- **Bug:** [DD-43852](https://tools.hmcts.net/jira/browse/DD-43852)
- **Inputs:** `01-requirements.md`, [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md).
  No production code changed at this stage.

## Summary

A LIBRA presence rule for the defendant forename in the domain rule engine (the authority), mirrored
structurally at the LIBRA Function App gate and in the 0.13.1 contract. No new rule type, event or
command. XHIBIT and the shared canonical schema are untouched.

## Enforcement layers

| Layer | Defendant `forename` | Parent/guardian `forename` |
|---|---|---|
| Domain rule engine (`MigratedCaseValidationRuleEngine`, LIBRA) | **required, non-blank, every person defendant** (C-1) | no rule |
| LIBRA gate (`libra.case-submission.json`) | **required, non-blank** via a defendant-only definition (C-2) | optional (shared definition unchanged) |
| LIBRA 0.13.1 contract (`dlrm-libra-0.13.1.json`) | **required, non-blank** (C-3) | optional |
| Canonical command schema (`personal-information.json`) | unchanged — shared with XHIBIT | unchanged |
| XHIBIT gate | unchanged | unchanged |

## Change

| # | File | Change | FR |
|---|------|--------|----|
| C-1 | `stagingdlrm-domain-aggregate/.../validation/MigratedCaseValidationRuleEngine.java` | Add to the LIBRA list: `RequiredFieldRule.of("$.migratedCase.defendants[*].individual.personalInformation.forename", submission -> presentOnEvery(personDefendants(submission), MigratedCaseValidationRuleEngine::nonBlankForename))`. `personDefendants` keeps only defendants with an `individual` (FR-4); the field function returns null when `personalInformation` or `forename` is absent or blank (Q4), so `presentOnEvery` fails if any person defendant lacks one (Q7). | FR-1, FR-2, FR-3, FR-4 |
| C-2 | `stagingdlrm-azure-functions/src/main/resources/libra.case-submission.json` | New `definitions/defendantPersonalInformation`: `allOf` [`#/definitions/personalInformation`, `{ "required": ["forename"], "properties": { "forename": { "pattern": "\\S" } } }`]. Point `individual.personalInformation` at it. The parent/guardian keeps `#/definitions/personalInformation` (Q8). | FR-2, FR-6 |
| C-3 | `docs/analysis/libra-ingestion/schema/libra/dlrm-libra-0.13.1.json` | Same split as C-2. | FR-7 |
| C-4 | `docs/analysis/libra-ingestion/libra-workbook-corrections.md` | Record the defendant forename as mandatory (DD-43852), and that `dlrm-libra-0.13.json` is not regenerated (as DD-43225). | FR-7 |

`pattern: "\\S"` needs at least one non-space character, so empty and spaces-only fail;
`minLength: 1` alone would let spaces through. `allOf` keeps the existing `maxLength: 255` and the
other `personalInformation` constraints in one place.

## Options considered

| Option | Why / why not |
|---|---|
| **C. stagingdlrm rule engine + gate mirror (chosen)** | Asked for in the ticket ("the staging context"). Stops the case earliest. Follows ADR-004: engine is the authority, gate mirrors. |
| C′. Gate schema only | Breaks ADR-004 (the gate never replaces the domain rules); the domain engine would not know the rule. |
| C″. Require `forename` in the shared `personalInformation` definition | Also rejects a person parent/guardian without a forename — outside the decision (Q8). |
| A. pcfdlrm rule + aggregate rejection | Gave the exact "Missing defendant forename" text, but rejected after staging; not chosen (Q6). |
| Canonical `personal-information.json` | Shared with XHIBIT — would change XHIBIT (FR-5). |

## Edge cases

- **Case types:** the rule does not look at the initiation code, so all four LIBRA case types
  (`J`, `S`, `C`, `Q`) reject alike (Q3).
- **Several defendants:** one person defendant without a forename rejects the submission (Q7).
- **Organisation defendants:** filtered out in C-1; at the gate, an organisation defendant has no
  `individual`, so the defendant-only definition is never applied.
- **Parent/guardian:** unchanged (Q8). The `oneOf` person/organisation branch for the guardian keeps
  the shared definition.
- **Gate vs engine message:** the gate rejects first in production with the schema validator's
  message; the engine message (Q5) applies when the gate is bypassed (e.g. direct command API calls
  in ITs).

## Tests

- `MigratedCaseValidationRuleEngineTest`: LIBRA forename absent / empty / blank → error with the
  jsonPath; present → no error; organisation defendant → no error; two defendants, second without a
  forename → error; XHIBIT without forename → no error.
- `JsonSchemaValidatorTest`: LIBRA gate rejects a defendant without a forename (absent, empty,
  blank); accepts a person parent/guardian without a forename. The shared `validIndividual()` test
  builder gains a forename.
- `ValidationRuleRejectionIT`: a LIBRA submission with the defendant forename removed → rejected by
  the rule with the forename jsonPath, not forwarded to pcfdlrm. (The IT posts to the command API,
  so it exercises the engine rule, not the gate.)
- No fixture changes: every existing LIBRA fixture already carries a defendant forename.
  `MigratedCaseSubmissionSchemaContractTest` covers the canonical schema, which is unchanged.
