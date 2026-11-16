# 00 — Input Brief

> **Self-contained** — everything an SDLC stage needs for this story is in this directory.

| | |
|---|---|
| Epic | [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995) — Business validation for Libra cases |
| Story | [DD-43944](https://tools.hmcts.net/jira/browse/DD-43944) — Libra Case Level - Add Court Hearing Location (stagingdlrm half) |
| Repo | `cpp-context-stagingdlrm` — **this repo only**. Base branch `team/libra4`. |
| Shared decision | [ADR-DD-43944](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/blob/team/libra4/docs/pipeline/adrs/ADR-DD-43944-libra-first-court-hearing-location.md) in `cpp-context-prosecution-casefile-dlrm` (single copy) |

## Epic framing

DD-32995 brings each LIBRA field's business validation in line with the LIBRA extract. A LIBRA
submission is `case.json` (validated by `libra.case-submission.json`) plus `manifest.json`
(`stagingdlrm.manifest.json`, shared with XHIBIT). The Function App assembles them, stagingdlrm
validates against the canonical `stagingdlrm-domain-value-schema` and maps to pcfdlrm, and pcfdlrm
sends the case to Progression. XHIBIT is already in production and must not change.

## Request

As given in the ticket: add an optional field giving the court to which the case was originally sent
by the prosecuting authority.

| Field | Format | Business rules | Comment | SJP / Summons / Charge / Postal Req |
|---|---|---|---|---|
| `firstCourtHearingLocation` | A7 | OU code | Will be of use in particular when no hearings are received. | O / O / O / O |

**Requirement change (developer, 2026-11-13):** the field goes **inside `migrationSourceSystem`**, not
on `caseDetails`.

## Current behaviour (code, `team/libra4`)

- **Where `migrationSourceSystem` comes from:** `manifest.json`, not `case.json` (the LIBRA case
  schema rejects it in `case.json`). `StagingDlrmCommandHelper` (`:179`, `:198`) copies the
  manifest's `migrationSourceSystem` object unchanged into `migratedCase.migrationSourceSystem`.
- **Function App `migrationSourceSystem.json`:** closed; `migrationSourceSystemName` and
  `migrationSourceSystemCaseIdentifier`, both required. Shared by XHIBIT and LIBRA via the one
  manifest validator.
- **Canonical `migrationSourceSystem.json`:** closed; the same two fields.
- **`MigratedCaseConvertor.buildMigrationSourceSystem` (`:259`):** copies the two fields by name to
  pcfdlrm. Anything else is dropped.
- **Downstream (pcfdlrm half):** pcfdlrm's `migrationSourceSystem` is closed with the same two
  fields; Progression's core `migrationSourceSystem` (`criminal-court-public-model`) is closed and has
  no court field in any version.
- **Existing court fields:** each hearing has `courtHearingLocation` (string, length 7). Canonical
  `caseDetails.sendingCourt` is documented as the LIBRA case court but LIBRA does not send it.
