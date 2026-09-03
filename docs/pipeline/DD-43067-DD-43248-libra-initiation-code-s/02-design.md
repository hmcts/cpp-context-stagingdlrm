# Design — DD-43248: admit LIBRA initiation code `S`

> Stage 2 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [ADR-002](../adrs/002-source-system-keyed-dispatch.md),
> [ADR-003](../adrs/003-libra-payload-contract.md).

## Pattern

No new pattern. DD-43203 built four gates for the initiation code; this story adds one value, `S`, to
the three of them LIBRA passes through, and to the shared extract contract. The fourth gate (the
XHIBIT rule / XHIBIT func-app schema) is deliberately left alone.

## Why all four, not just the two named files

`S` must be admitted at **every** point a LIBRA `S` is checked, or it is rejected somewhere between
ingest and the aggregate:

| Gate | File | Without `S` |
|---|---|---|
| Func-app LIBRA gate | `stagingdlrm-azure-functions/.../libra.case-submission.json` | `S` rejected at ingest — a terminal 4xx *after* enqueue (ADR-003 dec 6) |
| Canonical enum → generated `InitiationCode` | `stagingdlrm-domain-value-schema/.../json/schema/case-details.json` | `S` is an `InvalidFormatException` at deserialization, before any rule runs |
| Aggregate business rule | `MigratedCaseValidationRuleEngine.java` (LIBRA set) | `S` deserializes but the LIBRA rule rejects it |
| Shared extract contract | `docs/analysis/.../dlrm-libra-0.13.1.json` | extract contract disagrees with what LIBRA sends |

The canonical `case-details.json` is the one that matters at runtime: `migrated/migrated-case.json`
`$ref`s it (`http://cpp.moj.gov.uk/stagingdlrm/json/schemas/case-details.json`), so the migrated
model the aggregate deserializes into shares the same generated `InitiationCode` enum. Edit canonical
and both the value-schema and migrated enums gain `S` on a clean build.

## Scope map (requirement → artefact)

| Req | Artefact | Change |
|---|---|---|
| FR1 | `stagingdlrm-domain-value-schema/.../json/schema/case-details.json` | `initiationCode.enum` → `["C","Q","J","R","O","S"]` |
| FR3/FR4 | `MigratedCaseValidationRuleEngine.java` | LIBRA set → `"C","Q","J","R","S"`; XHIBIT untouched |
| FR5 | `stagingdlrm-azure-functions/.../libra.case-submission.json` | `initiationCode.enum` gains `"S"` |
| FR6 | `docs/analysis/libra-ingestion/schema/libra/dlrm-libra-0.13.1.json` | `initiationCode.enum` gains `"S"` |
| FR2 | `MigratedCaseConvertor.java` | **none** — `.name()` still compiles |

`InitiationCodeValidationRule.java` is **not** touched — it is a generic allowed-values rule; the set
lives in the engine's `RULES` map (ADR-002 rule 4). `dlrm-libra-0.13.provenance.json` is **not**
touched — `S` is a contract override, recorded the same way `R` was, not a workbook-derived value.

## Rejection behaviour (unchanged, reused)

An XHIBIT `S` flows through the existing DD-43081 branch: `MigratedCaseSubmissionRejected` +
`MigratedCaseSubmissionProcessed(false, VALIDATION_FAILED)`, never `Received`, nothing forwarded. No
new event, no handler change.

## Testing approach (for Stage 4 — informative)

- `MigratedCaseValidationRuleEngineTest` — a LIBRA `S` passes every rule; an `S`-coded payload is not
  permitted for XHIBIT (cross-system isolation). One new fixture,
  `libra/submission-valid-initiation-code-s.json`.
- `JsonSchemaValidatorTest` — the LIBRA gate accepts `S` (mutate the valid fixture's code to `S`,
  expect no messages), alongside the existing `O`-is-rejected row.
- Regression: every existing `C/Q/J/R`/`O` fixture stays valid; the generated enum has six constants.

## Out of scope

XHIBIT set and its func-app schema. `R` removal. PCFDLRM `CaseType` routing. Any converter change.
