# Requirements — DD-43226

## Goal

`address` is mandatory in the `personalInformation` value type; a submission without it
fails schema validation.

## Functional requirements

- **FR1** — `personal-information.json` lists `address` in its `required` array (with the
  existing `surname`).
- **FR2** — No other schema that references the address type is changed.

## Acceptance

Given a `personalInformation` payload with no `address`, when it is validated against the
schema, then validation fails.
