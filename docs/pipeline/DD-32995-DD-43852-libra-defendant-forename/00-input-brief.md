# 00 — Input Brief

> **Self-contained** — everything an SDLC stage needs for this story is in this directory.

| | |
|---|---|
| Epic | [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995) — Business validation for Libra cases |
| Bug | [DD-43852](https://tools.hmcts.net/jira/browse/DD-43852) — Libra charge automation - Forename is mandatory but case gets successfully created without forename |
| Blocks | [DD-43493](https://tools.hmcts.net/jira/browse/DD-43493) — Business validation for Defendant level items |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |

## Epic framing

LIBRA cases migrated through DLRM (Azure Blob → Function App → stagingDLRM → PCFDLRM → Progression)
must pass business validation before they reach `progression`. Each field in the LIBRA migration
schema has a rule. When a rule is broken, the case is either rejected, or the value is nulled and the
case accepted.

## Bug

A LIBRA Charge case whose person defendant has no forename is accepted and created in CP. The LIBRA
validation spreadsheet marks the defendant forename as mandatory ("M, reject").

In CP the case shows the defendant by surname only. Editing the defendant's details then fails,
because the CP edit-case screen requires a forename ("Enter this information").

## Discussion (ticket comments)

- **Ticket comment, 1 month ago:** cases should not be created if the individual's forename is
  missing; if the staging context lets case creation proceed without it, that needs to change.
- **Ticket comment, 3 days ago:** forename is mandatory in CP MCC, but SPI is believed to allow a
  case without it. QA asked to re-test and document.
- **QA re-test, 2 days ago:** confirmed `spi-in` creates a case without a forename, and the CP
  edit-case flow then requires one.
- **Ticket comment, 2 days ago (from Crime IT Support):** the SPI spec does not require a forename,
  but CP does; an SPI case without a forename "ends up on DLQ".
- **Ticket comment, 2 days ago:** confirmed SPI allows a case without a forename; in MCC the name is
  mandatory, so a case cannot be created there without it.

## Decision

- **Daily standup, 2026-11-06 (recorded on the ticket):** a LIBRA case with no defendant forename
  is **rejected**.
- **2026-11-06:** the rejection is made in **stagingdlrm**, as the earlier ticket comment asked
  ("the staging context"). It reopens DD-43493 decision D2, which kept forename optional by
  following the STAGINGDLRM schema.

## Current behaviour (code)

- **Function App LIBRA gate** (`stagingdlrm-azure-functions/src/main/resources/libra.case-submission.json`):
  `definitions/personalInformation` requires only `surname` and `address`; `forename` is an optional
  string (max 255). The same definition is used by `individual.personalInformation` (the defendant)
  **and** by the person branch of `parentGuardianInformation` (`oneOf[0]`).
- **Domain rule engine** (`MigratedCaseValidationRuleEngine`, the authority per
  [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md)): the LIBRA rule list has hearing
  presence rules, `informant` when Summons, and the initiation-code allow-list. No defendant name
  rule. `RequiredFieldRule` reports `"Missing required field: <jsonPath>"`; `presentOnEvery` applies a
  presence check to each element of a repeating block.
- **LIBRA 0.13.1 contract** (`docs/analysis/libra-ingestion/schema/libra/dlrm-libra-0.13.1.json`):
  `personalInformation.required` = `surname`, `address`; shared with the parent/guardian too.
- **Canonical command schema** (`stagingdlrm-domain-value-schema/.../personal-information.json`):
  shared with XHIBIT; `forename` optional.
- **Precedent:** [DD-43225](../DD-43067-DD-43225-address-mandatoriness/02-design.md) made
  `personalInformation.address` mandatory across the gate, the 0.13.1 contract and the canonical
  schema, and recorded it in `libra-workbook-corrections.md`.
