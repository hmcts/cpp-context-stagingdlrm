# Design — DD-43225: Address mandatoriness realignment

> Stage 2 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [ADR-002](../adrs/002-source-system-keyed-dispatch.md),
> [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md).

## Pattern

No new pattern. Two `required`-array realignments applied consistently across the enforcement layers,
keeping gate ↔ domain ↔ contract parity. No new rule type, no new source-system conditional.

## Enforcement layers (where each address is checked)

| Layer | Defendant `address` | Individual `personalInformation.address` |
|---|---|---|
| Canonical command schema (`migrated-defendant.json` / `personal-information.json`) | already not required | **required** (FR5) — covers LIBRA + XHIBIT |
| LIBRA func-app gate (`libra.case-submission.json`) | **required → removed** (FR2) | **required → added** (FR6) |
| XHIBIT func-app gate | never modelled | not modelled (shallow gate) — canonical is the enforcement point |
| Domain rule engine (`MigratedCaseValidationRuleEngine`) | **LIBRA rule → removed** (FR1) | no rule (schema enforces) (FR8) |
| `0.13.1` contract (`dlrm-libra-0.13.1.json`) | **required → removed** (FR3) | **required → added** (FR7) |
| Generated `dlrm-libra-0.13.json` | unchanged — not regenerated (FR9) | unchanged — not regenerated (FR9) |

## Scope map (requirement → artefact)

| Req | Artefact | Change |
|---|---|---|
| FR1 | `stagingdlrm-domain-aggregate/.../MigratedCaseValidationRuleEngine.java` | drop LIBRA `defendants[*].address` rule; remove the now-unused `defendants()` helper + `Defendant` import |
| FR2 / FR6 | `stagingdlrm-azure-functions/src/main/resources/libra.case-submission.json` | `defendant.required` − `address`; `personalInformation.required` + `address` |
| FR3 / FR7 | `docs/analysis/libra-ingestion/schema/libra/dlrm-libra-0.13.1.json` | same two `required` edits |
| FR5 | `stagingdlrm-domain-value-schema/.../personal-information.json` | `required: ["surname","address"]` |
| tests | `MigratedCaseValidationRuleEngineTest`, `ValidationRuleRejectionIT`, `JsonSchemaValidatorTest`, `MigratedCaseSubmissionSchemaContractTest` | flip/adjust/add cases |
| fixtures | LIBRA IT fixture + 6 `aggregate/libra/*.json` | add `individual.personalInformation.address` |
| FR9 | `libra-workbook-corrections.md` | record both changes + the unregenerated-`0.13` divergence |

## A — Defendant address optional (LIBRA)

The LIBRA rule set had a `presentOnEvery(defendants, Defendant::getAddress)` presence rule. Removing it,
plus dropping `address` from the gate and 0.13.1 `defendant.required`, makes the object optional
everywhere LIBRA is validated. XHIBIT has no such rule or gate requirement. The `address` property and its
sub-field constraints remain, so a supplied address is still validated.

## B — Individual address mandatory (both source systems)

The individual's address is enforced once, in the shared canonical `personal-information.json`. Because
that schema backs the command payload for both source systems, XHIBIT (whose gate is shallow and does not
model `personalInformation`) is covered at the command layer, and the parent-guardian block — which
reuses the same definition — is covered too. The LIBRA gate and 0.13.1 mirror it for up-front rejection
and contract fidelity. No rule-engine rule is added: this is the direct-schema route the provenance
sidecar's `personalInformation.address` deviation ("enforce it in the LIBRA validation rules")
anticipated.

## C — Generated schema left unregenerated

`dlrm-libra-0.13.json` is generated from `DLRM - CP Migration Data Schema V0.13.xlsx`, which is not in the
repo (only an older revision survives in git, and it does not reproduce the committed artefacts). Rather
than regress the schema, the generated file and its provenance are left as-is and the divergence is
recorded. To reconcile later: place the current workbook, set `defendant/address1` (row 82) to `O`, and
re-run `regenerate.sh` — accepting that `address1` drops from the shared `address.required` (intersected
across officer/parent-guardian), the same pattern already noted for `personalInformation.address`.

## Testing approach (for Stage 4 — informative)

- Rule engine: `MigratedCaseValidationRuleEngineTest` — the missing-defendant-address case flips from
  rejected to **accepted**, reusing `submission-missing-defendant-address.json`.
- LIBRA IT: `ValidationRuleRejectionIT` drops the defendant-address rejection scenario; the LIBRA base
  fixture gains an individual address so it passes canonical validation before the rule check.
- Gate: `JsonSchemaValidatorTest` — the defendant `required` `@ValueSource` loses `address`, gains a
  defendant-without-address accept test; `validIndividual()` and the inline builder gain an address; a new
  "personalInformation requires address" reject test is added.
- Canonical: `MigratedCaseSubmissionSchemaContractTest` — a `personalInformation.address required` reject
  scenario (exercises the shared schema on the XHIBIT base fixture).
- Fixtures: the 6 `aggregate/libra/*.json` gain an individual address for realism/consistency.

## Out of scope

XHIBIT gate; PCFDLRM; the converter; regenerating the generated LIBRA schema; a code-level split of the
shared `address` definition.
