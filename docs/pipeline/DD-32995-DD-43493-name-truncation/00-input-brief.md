# 00 — Input Brief

- **Epic:** [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995) — Business validation for Libra cases
- **Story:** [DD-43493](https://tools.hmcts.net/jira/browse/DD-43493) — Business validation for Defendant level items
- **Repo:** `cpp-context-stagingdlrm` (`stagingdlrm`). The PCFDLRM side of DD-43493 is in
  `cpp-context-prosecution-casefile-dlrm`, `docs/pipeline/DD-32995-DD-43493-defendant-validation/`.
- **Branch:** `dev/dd-32995-libra-defendant-validation` (one branch and one PR for the STAGINGDLRM side of DD-43493, DD-43494 and DD-43501)

## Story ask (name rows)

DD-43493, defendant `forename`, `forename2`, `forename3`, `surname` (format A255):

> If name exceeds field size, only the first 35 characters are to be used from the source.

## Why STAGINGDLRM

The LIBRA gate allows defendant and guardian names up to 255 characters, and the convertor passed
them through unchanged. PCFDLRM's command schema caps `lastName`,
`givenName2` and `givenName3` at 35 and `firstName` at 130, so a longer name passed STAGINGDLRM and
then failed PCFDLRM's schema (HTTP 400), with no outcome recorded for the submission. Truncation is a
mapping step, so it belongs in `MigratedCaseConvertor`.

## Decision

Truncate defendant and parent guardian names to their first 35 characters when mapping to PCFDLRM.
Individual alias names are not truncated: PCFDLRM's alias schema has no length limit, so they pass
through unchanged.
