# 01 — Requirements

- **Story:** [DD-43501](https://tools.hmcts.net/jira/browse/DD-43501) (epic [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995))
- **Base:** `team/libra1` @ `65f62ba`

## Today

The LIBRA gate schema (`stagingdlrm-azure-functions/src/main/resources/libra.case-submission.json`)
defines `parentGuardianInformation` as a `oneOf` of a person and an organisation. The organisation
branch allows only `organisationName` (required, max 255) and `companyTelephoneNumber` (phone
pattern), with `additionalProperties: false`, so an organisation guardian's address cannot be sent.

The canonical command schema (`stagingdlrm-domain-value-schema/.../parent-guardian-information.json`)
already allows an optional `address` on the organisation branch, and
`MigratedCaseConvertor.buildParentGuardianInformation` already maps it to PCFDLRM
(`withAddress(buildAddress(parentGuardianInformation.getAddress()))`).

## Requirements

| ID | Requirement |
|---|---|
| R1 | The LIBRA organisation guardian accepts an `address` using the existing `#/definitions/address` (address1 required; address1–5 max 35; postcode UK pattern, max 8). |
| R2 | The organisation guardian's `address` is required: a LIBRA organisation guardian without it fails the gate and the submission is rejected. |
| R3 | No change to the canonical schema, the convertor, XHIBIT or the person-guardian branch. |

## Per-field outcome (LIBRA)

| Field | Outcome after this story |
|---|---|
| organisationName | required (unchanged) |
| companyTelephoneNumber | optional, phone pattern (unchanged) |
| address | **required** |
| address.address1 | required within address (existing definition) → missing rejects |
| address.address2–5 | optional, max 35 |
| address.postcode | optional, UK postcode pattern |

## Decisions (BA and TL, 2026-10-12)

- Organisation guardians are not sent to `progression`: PCFDLRM keeps its current behaviour (the core
  `associatedPerson` model requires a person).
- An invalid organisation guardian address or postcode is rejected by the gate schema (current
  behaviour kept).
