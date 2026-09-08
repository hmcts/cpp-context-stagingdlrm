# Input Brief — Make address mandatory in personalInformation

**Source:** Jira DD-43226 (https://tools.hmcts.net/jira/browse/DD-43226).

## Original ask

`address` must be a required field in the `personalInformation` value type.

## Verified current state (codebase, 2026-08-28)

- Schema: `stagingdlrm-domain/stagingdlrm-domain-value-schema/src/main/resources/json/schema/personal-information.json`.
- `address` is already a property (`$ref` → `.../schemas/address.json`, defined by
  `pcf-address.json`) but was listed only alongside `surname` in `required` at HEAD.
- Working copy now adds `address` to the `required` array.

## Scope

- **In:** `personal-information.json` only.
- **Out:** `parent-guardian-information.json` and `migrated/migrated-defendant.json`, which
  reference the same address type but are intentionally left unchanged.
