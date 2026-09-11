# Input Brief — Keep received metric in sync on Function App schema failure

**Source:** Jira DD-43208 (https://tools.hmcts.net/jira/browse/DD-43208).

## 1. Original ask

When schema validation fails in the Function App:

- `error-migrated-case-submission-received` must be incremented.
- `migrated-case-submission-received` must **also** be incremented.

`migrated-case-submission-received` should represent *all* migrated case submissions
received, regardless of whether the failure is at the API level or during schema
validation in the Function App. So the total received must stay in sync with the combined
processing outcomes.

Example: one submission fails at the API level and one fails schema validation in the
Function App →

- `migrated-case-submission-received` = 2
- `error-migrated-case-submission-received` = 2

Currently a schema validation failure in the Function App increments only
`error-migrated-case-submission-received`, so the metrics drift apart.

## 2. Verified current state (codebase, 2026-08-24)

- Both metrics are Micrometer counters in the **event processor**
  (`stagingdlrm-event-processor`), not in the Function App. The Function App only POSTs a
  command; the counters increment when the resulting domain event is handled.
- `StagingDlrmEventProcessor.sendEventToGrid` increments `migratedCaseSubmissionReceivedCounter`
  for a failed submission **only when the error description matches one of** `stagingContextErrors`
  = `"JSON schema validation has failed"`, `"Duplicate Submission ID"`,
  `"Case Already exists in progression"`.
- **API-level** failures work: the framework's 400 body contains
  `"JSON schema validation has failed on ..."`, so it matches → both counters increment.
- **Function App** schema failures do **not** work: `TimerTriggerJava.processClientError`
  builds the error message from raw networknt `ValidationMessage` texts, which never
  contain the marker phrase → no match → only the error counter increments. This is the bug.

## 3. Out of scope

- Material-count mismatch and generic downstream errors (they carry no schema marker and
  are not covered by this ticket).
- Any change to the counter definitions or the Micrometer registry wiring.
