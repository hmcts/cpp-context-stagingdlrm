# 02 — Design

- **Story:** [DD-43501](https://tools.hmcts.net/jira/browse/DD-43501) (epic [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995))

## Change

`libra.case-submission.json`, `definitions/parentGuardianInformation`, organisation branch
(`oneOf[1]`):

- add `"address": { "$ref": "#/definitions/address" }`;
- `required` becomes `["organisationName", "address"]`.

The existing `#/definitions/address` supplies `address1` required, `address1`–`address5` max 35 and
the postcode pattern, so no new definition is needed. `additionalProperties: false` is kept.

## Not changed

- Canonical command schema (already allows the address; XHIBIT unaffected).
- `MigratedCaseConvertor` (already maps the organisation guardian address to PCFDLRM).
- Person-guardian branch, rule engine, events, descriptors.

## Tests

`JsonSchemaValidatorTest`:

- `shouldAcceptLibraCaseSubmissionWithAnOrganisationParentGuardianWithAddress`
- `shouldRejectLibraCaseSubmissionWithAnOrganisationParentGuardianMissingAddress`

Both fail without the schema change.
