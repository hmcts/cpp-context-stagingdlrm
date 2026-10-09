# 00 — Input Brief

- **Epic:** [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995)
- **Story:** [DD-43499](https://tools.hmcts.net/jira/browse/DD-43499) — "Business validation for Case
  level items"
- **Repo:** `cpp-context-stagingdlrm` — the **schema/staging half**. Same story is fulfilled
  separately in `cpp-context-prosecution-casefile-dlrm` (pcfdlrm business validation); see
  `docs/pipeline/DD-32995-DD-43499-case-level-business-validation/` there.
- **Scope:** API level only (command-api + `stagingdlrm-domain-value-schema` + aggregate rule
  engine). Azure Functions intake not considered.

## Acceptance criteria (as given)

**AC-1:** GIVEN a case received onto CP via DLRM LIBRA migration, WHEN validation runs for the case
elements and a business rule is not met, THEN the system rejects or accepts the case per the table.

| Field | Format | Business rule | SJP / Summons / Charge / Postal Req | Missing → |
|---|---|---|---|---|
| `prosecutingAuthority` | A7 | Valid CJS OU code; CPS use cpsOrg (A codes) | M / M / M / M | Reject |
| `originatingOrganisation` | A7 | Valid CJS OU code | M / M / M / M | Reject |
| `initiationCode` | A1 | C, Q, J, S (R — TBC) | M / M / M / M | Reject |
| `prosecutorCaseReference` | A36 | `[A-Za-z0-9-]` | M / M / M / M | Generate, accept |
| `migrationSourceSystemCaseIdentifier` | A100 | Unique across migrated cases | M / M / M / M | Reject |
| `migrationSourceSystemName` | A6 | `LIBRA` | M / M / M / M | Reject |
| `cpsOrganisation` | A7 | — | O / O / O / O | Accept |
| `informant` | A92 | — | O / M / O / O | Reject for Summons |
| `caseMarkers` | Array | CJS markers | O / O / O / O | Accept |

## Decisions already made (pcfdlrm Stage 1, approved 2026-10-01) — don't re-open

1. Ticket note "invalid entries are nulled → missing-field behaviour" is **wrong**; BA to correct.
   Invalid values **follow XHIBIT** handling.
2. `prosecutorCaseReference` is **never generated** (known). Stays required.
3. `migrationSourceSystemCaseIdentifier` is **never unique** — no uniqueness check, out of scope.
4. Initiation code `R` is not a blocker — already allowed for LIBRA here; pcfdlrm doesn't refuse it.
5. CPS `prosecutingAuthority` (A codes): same as XHIBIT — single prosecutor-by-OU lookup in pcfdlrm.
6. `informant` Summons check is **BV in stagingdlrm** (rule engine), not schema — see S-3.
7. `prosecutingAuthority` is never null at pcfdlrm — this repo's schema requires it (length 7).

## Current state (`origin/team/libra1` @ `0b678de`)

Schema chain: `stagingdlrm.receive-migrated-case-submission.json` → `migrated/migrated-case.json` →
`case-details.json`, `pcf-prosecutor.json`, `migrationSourceSystem.json` (shared by XHIBIT + LIBRA).

| Field | Constraint today | Mapped to pcfdlrm? | Gap |
|---|---|---|---|
| `prosecutingAuthority` | required, length 7 | yes | — |
| `originatingOrganisation` | required, length 7 | yes | — |
| `initiationCode` | required, enum C/Q/J/R/O/S; LIBRA rule C/Q/J/R/S | yes | — |
| `prosecutorCaseReference` | required, maxLength 36 | yes (also system-id-mapper URN key) | no pattern |
| `migrationSourceSystemCaseIdentifier` | required, no maxLength | yes | no A100 limit |
| `migrationSourceSystemName` | required, enum LIBRA/XHIBIT | yes | — |
| `cpsOrganisation` | optional, length 7 | yes | — |
| `informant` | optional, maxLength 92 | **no** ("declare, never map") | no Summons check |
| `caseMarkers` | optional, `markerTypeCode` maxLength 3 | yes | — |

No API-level LIBRA tests reject any of these fields (`MigratedCaseSubmissionSchemaContractTest` is
XHIBIT-only).

## Candidate requirements (for this repo's Stage 1)

| ID | Gap |
|----|-----|
| S-1 | `prosecutorCaseReference`: add `[A-Za-z0-9-]` pattern (SV). |
| S-2 | `migrationSourceSystemCaseIdentifier`: add maxLength 100 (SV). |
| S-3 | `informant` missing on LIBRA Summons (`S`) → reject (BV): new rule in the LIBRA list of `MigratedCaseValidationRuleEngine` → `migrated-case-submission-rejected`. Not mapped to pcfdlrm. |

**Why S-3 is BV:** the API schema is shared and holds no source-system conditionals (ADR-002);
"required if LIBRA and `S`" would need nested draft-04 `anyOf`/`not`. Precedent: LIBRA hearing
`RequiredFieldRule`s, XHIBIT `AtLeastOneOfRule`, DD-43203 initiation-code sets — all in the rule
engine.

## Test approach (user direction 2026-10-01)

Negative ITs are allowed here but kept **minimal** by reusing existing multi-case tests (unlike
pcfdlrm, where negative LIBRA ITs are not written):

- **S-1, S-2 (SV, HTTP 400):** follow `ReceiveXhibitCaseFileSubmissionIT.shouldRaiseBadRequest` —
  one bad-request payload, one POST, asserts several 400 messages at once. One test in
  `ValidationRuleRejectionIT` that mutates `LIBRA_BASE` to carry both violations (no new fixture).
- **S-3 (BV):** add one row to `ValidationRuleRejectionIT.rejectionScenarios()` — "LIBRA Summons
  missing informant", `LIBRA_BASE`, mutator sets `initiationCode` `S` (the base has no `informant`),
  expected `$.migratedCase.caseDetails.informant`. Needs a small set-field mutator beside the
  existing remove helpers.

## Open questions

1. S-1/S-2 as schema (SV) — S-1 pattern would also apply to XHIBIT (shared schema); confirm OK.
