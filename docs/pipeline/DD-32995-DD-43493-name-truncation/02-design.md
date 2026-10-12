# 02 — Design

- **Story:** [DD-43493](https://tools.hmcts.net/jira/browse/DD-43493) (epic [DD-32995](https://tools.hmcts.net/jira/browse/DD-32995))

## Change

`MigratedCaseConvertor`:

- `MAX_NAME_LENGTH = 35` and a private `truncateName` helper (null and short names unchanged).
- `buildPersonalInformation` truncates forename, surname, middleName and middleName2 (defendant and
  parent guardian).

## Not changed

Gate and canonical schemas, rule engine, events, individual alias names, other fields.

## Tests

`MigratedCaseConvertorTest`:

- `shouldTruncatePersonalInformationNamesToThirtyFiveCharacters` runs for null, a short name,
  exactly 35, 36 and 255 characters, and fails without the change.
