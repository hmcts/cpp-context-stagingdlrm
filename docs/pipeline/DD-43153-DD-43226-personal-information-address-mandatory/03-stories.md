# Stories — DD-43226

## Story 1 — Require address in personalInformation

**As** a consumer of migrated case submissions,
**I want** `address` to be mandatory in `personalInformation`,
**so that** every personal record carries an address.

**Acceptance criteria**

- `personal-information.json` lists `address` in `required`.
- A `personalInformation` payload without `address` fails schema validation.
- `parent-guardian-information.json` and `migrated-defendant.json` are unchanged.

**Implementation** — add `"address"` to the `required` array in `personal-information.json`.

**Tests**

- IT `ReceiveCaseFileSubmissionIT.shouldRaiseBadRequestWhenAddressMissingInPersonalInformation`
  — POSTs a submission whose defendant `personalInformation` omits `address`; asserts 400
  and body contains `required key [address] not found`. Fixture:
  `stagingdlrm.receive-migrated-case-submission-missing-address.json`.

## Verification

- Unit: value-schema module builds; existing fixtures already carry `address`, so no unit
  test changes were needed.
- IT: `mvn verify -P stagingdlrm-integration-test -pl stagingdlrm-integration-test -Dit.test=ReceiveCaseFileSubmissionIT`.
