# ADR-004: The Function App gate mirrors the domain's per-source-system rules

## Status

**Accepted 2026-08-21** — by the tech lead, at stage 3 of
[DD-43180](https://tools.hmcts.net/jira/browse/DD-43180).

**Amends [ADR-002](./002-source-system-keyed-dispatch.md) rule 7**, which said "both layers expressing
the *same* rule is not [accepted]". DD-43180 deliberately has the gate express the same
per-source-system rules as the domain engine. Rule 7's *shape* still holds (the gate stays structural —
presence, type, allowed values); what changes is that the same constraint may now be enforced in both
layers on purpose.

## Date

2026-08-21

## Scope

The Function App JSON-schema gate (`stagingdlrm-azure-functions`) versus the domain
`MigratedCaseValidationRuleEngine` (`stagingdlrm-domain-aggregate`). It does not change the domain
engine — that remains the authority.

## Context

DD-43203 put the per-source-system business rules (initiation-code allowed values, required fields,
`anyOf`) in the **domain aggregate** only. A payload that violates one still passed the gate, was
enqueued and POSTed, and failed downstream — the enqueue-then-4xx failure mode ADR-003 §6 warns about.
That late failure is also what surfaced the two DD-43180 defects: a gate rejection reached the
`ErrorMigratedCaseSubmissionReceived` path where the received counter was wrongly bumped, and the
outcome file was written with an empty `caseUrn`.

The gate keeps **separate** schemas per source system, so each rule is expressible structurally in the
schema for the system it applies to — no rule engine, no source-system conditionals.

## Decision

**1. The gate expresses per-source-system constraints structurally, where they belong in the schema.**
The DD-43180 delta is the per-source-system `initiationCode` enum on each source's schema; existing gate
validation is otherwise left as-is:
- XHIBIT (`case-details.json`): add `initiationCode` enum `["O"]` **only**. The four required fields it
  already declared are unchanged, and the XHIBIT `anyOf(dateOfCommittal, dateOfSending)` is deliberately
  **not** added to the gate — DD-43180 does not touch XHIBIT gate validation beyond the initiation code.
- LIBRA (`libra.case-submission.json`): add `initiationCode` enum `["C","Q","J","R"]`. The presence rules
  (`defendant.address`, `hearing.dateOfHearing`, `hearing.timeOfHearing`) already held at the gate.

**2. The domain engine stays the authority.** The gate is an early, structural copy; it never replaces
the domain rules and never relaxes below them (ADR-003 §6 — stricter is fine, looser is not).

**3. Rules not mirrored at the gate stay domain-only, and are recorded.** One remains domain-only:
- The XHIBIT `anyOf(dateOfCommittal, dateOfSending)` — a deliberate scope choice (DD-43180 does not touch
  XHIBIT gate validation beyond the initiation code), not a technical limit.

The LIBRA `hearings[*].courtRoomId` presence rule **is** now mirrored at the gate: DD-43180 reconciled the
LIBRA gate schema to the 0.13.1 contract, which declares and requires `courtRoomId` on every hearing (an
earlier draft of this ADR wrongly recorded it as unmirrorable, when the gate had simply drifted from the
contract).

## Options considered

| Option | Why not |
|---|---|
| **Leave the gate as-is (domain-only rules)** | Keeps the enqueue-then-4xx failure mode and the counter/outcome defects DD-43180 exists to fix. |
| **Move the rules out of the domain into the gate only** | The gate is not the event-appending authority and cannot be the single point of truth for a business invariant; the aggregate must still guard itself. |
| **A shared rule definition consumed by both layers** | The two run in different processes/runtimes (plain Java gate vs. `Serializable` aggregate); sharing the definition couples deployments for little gain — the same objection ADR-002 raised. |

## Consequences

- **The two layers can drift.** Nothing compile-time guarantees the gate and the engine agree. Mitigation:
  the parity is asserted in tests (`JsonSchemaValidatorTest` mirrors `MigratedCaseValidationRuleEngineTest`
  per source system), and the schema-gen diff tooling (`tools/schema-gen/`) makes drift visible.
- **A gate rejection is now the common case for bad data**, so the counter/outcome behaviour on the
  error path must be correct — which is the rest of DD-43180.
- **ADR-002 rule 7 is no longer read as a prohibition on duplicate rules**, only on the gate expressing
  *non-structural* logic. A superseding note is added to ADR-002.

## Compliance notes

1. The gate is never more lenient than the domain engine for any shared constraint.
2. Each gate rule sits in the schema of the source system it applies to — no source-system conditionals
   in shared func-app code.
3. A domain rule not mirrored at the gate is recorded here (currently: the XHIBIT `anyOf`).
4. A parity test exists on both sides for every mirrored rule.
