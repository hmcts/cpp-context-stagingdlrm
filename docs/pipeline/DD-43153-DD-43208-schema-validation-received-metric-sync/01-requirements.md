# Requirements — DD-43208

## Goal

`migrated-case-submission-received` counts every received submission, whether it later
fails at the API level or during Function App schema validation. The received total stays
in sync with the combined outcome counters.

## Functional requirements

- **FR1** — When case or manifest schema validation fails in the Function App, the
  resulting outcome must cause both `migrated-case-submission-received` and
  `error-migrated-case-submission-received` to be incremented (once each per submission).
- **FR2** — API-level schema-validation failures must keep their current behaviour (both
  counters increment). No regression.
- **FR3** — Failures that are *not* schema validation (material-count mismatch, generic
  downstream/server errors) keep their current behaviour — only the error counter
  increments.
- **FR4** — No double counting: a submission that is received normally and later fails
  downstream must increment `migrated-case-submission-received` exactly once.

## Non-functional

- Reuse the existing counter mechanism; no new metric names, no new dependencies.
- Function App remains outside WildFly/JMS; it cannot touch the counters directly, so the
  fix works through the error description it already sends.

## Acceptance

Given one submission failing at the API level and one failing schema validation in the
Function App, when both are processed, then `migrated-case-submission-received` = 2 and
`error-migrated-case-submission-received` = 2.
