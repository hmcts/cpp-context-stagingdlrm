# 01 — Requirements

- **Bug:** DD-43852 — see `00-input-brief.md`.
- **Scope:** stagingdlrm, LIBRA only.

## Decisions and open questions

| # | Question | Status |
|---|----------|--------|
| Q1 | Reject a LIBRA case whose person defendant has no forename, or accept it as today? | **Resolved (daily standup, 2026-11-06):** reject. Reopens DD-43493 D2 for LIBRA. |
| Q2 | LIBRA only, or XHIBIT too? | **Resolved:** LIBRA only. |
| Q3 | Which LIBRA case types? | **Resolved (2026-11-06):** every LIBRA case type — SJP, Summons, Charge and Requisition. |
| Q4 | Does an empty or whitespace-only forename count as missing? | **Resolved (2026-11-06):** yes — absent, empty or whitespace only all reject. |
| Q5 | Rejection description text. | **Resolved (2026-11-06):** the standard rule-engine message, `Missing required field: $.migratedCase.defendants[*].individual.personalInformation.forename`; a gate rejection carries the schema validator's message. Supersedes "Missing defendant forename", which was agreed for a pcfdlrm rejection. |
| Q6 | Which repo rejects? | **Resolved (2026-11-06):** stagingdlrm (`02-design.md` option C), as the earlier ticket comment asked. |
| Q7 | Several defendants in one LIBRA case? | **Resolved (2026-11-06):** every defendant is checked; if any person defendant has no forename, the whole case is rejected. |
| Q8 | Parent/guardian forename? | **Resolved (2026-11-06):** unchanged (optional). The defendant gets its own definition at the gate and in the 0.13.1 contract (`02-design.md` C-2, C-3). |

## Requirements

| ID | Requirement |
|----|-------------|
| FR-1 | On a LIBRA case of any type (SJP, Summons, Charge, Requisition), a person defendant (`individual` present) with `personalInformation.forename` absent, empty or whitespace only → submission rejected; the case does not reach pcfdlrm or progression. Every defendant is checked; one defendant without a forename rejects the whole case. |
| FR-2 | The rejection is enforced in the domain rule engine (the authority, ADR-004) and mirrored at the LIBRA Function App gate. |
| FR-3 | A LIBRA person defendant with a forename → unchanged. |
| FR-4 | Organisation defendants (no `individual`) → unchanged. |
| FR-5 | XHIBIT → unchanged: the canonical (shared) `personal-information.json` and the XHIBIT gate are not touched. |
| FR-6 | Parent/guardian forename → unchanged (Q8). |
| FR-7 | The LIBRA 0.13.1 contract mirrors the gate, and the change is recorded in `libra-workbook-corrections.md`. |
| FR-8 | Tests for FR-1 – FR-6 at each layer; existing tests and ITs stay green. |

## Acceptance criteria

- **AC-1:** GIVEN a LIBRA submission in which any person defendant has no forename (absent, empty
  or blank), WHEN it is received, THEN it is rejected as an error submission and is not sent to
  pcfdlrm.
- **AC-2:** GIVEN a LIBRA submission in which every person defendant has a forename (organisation
  defendants are not checked), WHEN it is received, THEN the outcome is unchanged.
- **AC-3:** GIVEN an XHIBIT submission whose person defendant has no forename, or a LIBRA
  submission whose person parent/guardian has no forename, WHEN it is received, THEN the outcome is
  unchanged.
