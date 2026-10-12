# 00 — Input Brief

- **Epic:** [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995) — Business validation for Libra cases
- **Story:** [DD-43501](https://tools.hmcts.net/jira/browse/DD-43501) — Business validation for Parent Guardian level items
- **Repo:** `cpp-context-stagingdlrm` (`stagingdlrm`). The PCFDLRM side of DD-43501 is in
  `cpp-context-prosecution-casefile-dlrm`, `docs/pipeline/DD-32995-DD-43501-parent-guardian-validation/`.
- **Branch:** `dev/dd-32995-libra-defendant-validation` (one branch and one PR for the STAGINGDLRM side of DD-43493, DD-43494 and DD-43501)

## Epic framing

LIBRA cases migrated through DLRM must pass business validation before they reach Common Platform.
STAGINGDLRM owns schema-level validation (mandatory fields, presence, types, structure, lengths,
patterns); PCFDLRM validates against reference data and business rules.

## Story ask (organisation guardian rows of DD-43501)

| Field | Format | Rule | Sum / Chg / Req | Missing |
|---|---|---|---|---|
| organisationName | A255 | Provide if an organisation is the guardian | M | (no youth condition — follows the schema) |
| companyTelephoneNumber | A35 | Phone format | O | Accept |
| organisation.address1 | A35 | Provide if an organisation is the guardian | M | **Reject** |
| organisation.address2–5 | A35 | | O | Accept |
| organisation.postcode | A8 | Must match the postcode regex where provided | O | Accept |

## Decisions

- A parent guardian can be an organisation (confirmed by the team leader).
- The organisation guardian's `address` is **required**, so a missing `address1` rejects the case.
- Phone and email formats remain STAGINGDLRM's.
