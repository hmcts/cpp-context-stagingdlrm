# Requirements — DD-43225: Address mandatoriness realignment

> Stage 1 artefact. Source: [`00-input-brief.md`](./00-input-brief.md). Feeds
> [`02-design.md`](./02-design.md).

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43225](https://tools.hmcts.net/jira/browse/DD-43225) — Address mandatoriness realignment |
| Repo | `cpp-context-stagingdlrm` |

## Story

### Summary (JIRA summary line)

Make the defendant-level `address` optional for LIBRA and make the individual's
`personalInformation.address` mandatory for both LIBRA and XHIBIT.

### User story

As an **operator running LIBRA and XHIBIT migrations**, I want **a LIBRA case accepted when it has no
defendant-level address, but every case required to carry the individual's address**, so that **real
LIBRA data is not rejected for a field it does not always send, while the address we depend on downstream
is always present**.

## Requirements

### A. Defendant-level address — optional for LIBRA

- **FR1 — Drop the LIBRA defendant-address rule.** Remove the
  `$.migratedCase.defendants[*].address` presence rule from the LIBRA rule set in
  `MigratedCaseValidationRuleEngine`. The LIBRA hearing rules and initiation-code rule are unchanged.
- **FR2 — LIBRA gate: `defendant.address` no longer required.** Remove `address` from the `defendant`
  `required` array in `libra.case-submission.json`. The property is still declared; its sub-field
  constraints still apply when an address is supplied.
- **FR3 — Mirror in the 0.13.1 contract.** Remove `address` from the `defendant` `required` in
  `dlrm-libra-0.13.1.json`, keeping gate ↔ contract parity.
- **FR4 — XHIBIT untouched.** XHIBIT never required `defendant.address`; no XHIBIT change.

### B. Individual address — mandatory for LIBRA and XHIBIT

- **FR5 — Canonical schema requires the individual's address.** `personal-information.json` declares
  `required: ["surname","address"]`. Being the shared command-layer schema, this enforces
  `individual.personalInformation.address` for **both** source systems (and for the parent-guardian block,
  which reuses the definition).
- **FR6 — LIBRA gate parity.** Add `address` to the `personalInformation` `required` in
  `libra.case-submission.json`.
- **FR7 — Mirror in the 0.13.1 contract.** Add `address` to `personalInformation` `required` in
  `dlrm-libra-0.13.1.json`.
- **FR8 — No new rule-engine rule.** The requirement is expressed in the shared schema, which the
  provenance sidecar's `personalInformation.address` deviation already anticipated — so no source-system
  rule is added.

### C. Generated artefacts

- **FR9 — `dlrm-libra-0.13.json` / provenance are not regenerated.** The source workbook is absent from
  the repo (only a stale older revision exists in git; regenerating from it would regress the schema by
  ~12 fields). The divergence is recorded in `libra-workbook-corrections.md`; regeneration is deferred
  until the current workbook is available (set `defendant/address1` row 82 to `O`, accept the
  shared-`address` intersection).

## Acceptance criteria

- **AC1** — A LIBRA submission with no `defendant.address` is accepted by the gate and by the rule engine;
  XHIBIT behaviour is unchanged.
- **AC2** — A submission whose `individual.personalInformation` omits `address` is rejected — at the
  canonical schema (both source systems) and at the LIBRA gate.
- **AC3** — `defendant.address`, when present, still validates against its declared sub-field constraints.
- **AC4** — Gate ↔ 0.13.1 contract parity holds for both `required` arrays.
- **AC5** — No new source-system rule is added for the individual address.
- **AC6** — Affected unit tests and fixtures are updated; the affected modules build green
  (`domain-value-schema`, `domain-aggregate`, `azure-functions`).

## Out of scope

- XHIBIT gate schemas; PCFDLRM; the converter.
- Regenerating `dlrm-libra-0.13.json` / provenance / the workbook.
- Reworking the shared `address` definition to keep officer/parent-guardian `address1` mandatory
  independently of the defendant (accepted as the intersection).

## Notes for the design stage

- Gate ↔ domain parity is governed by [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md).
- The individual-address requirement lives in one shared schema, not per source system — the XHIBIT gate
  is shallow (no `personalInformation`), so canonical is XHIBIT's enforcement point.
