# Stories — DD-43208

## Story 1 — Prefix schema-validation failures with the marker phrase

**As** an operator monitoring the DLRM migration,
**I want** Function App schema-validation failures to be counted as received submissions,
**so that** `migrated-case-submission-received` reflects every submission received.

**Acceptance criteria**

- Given case or manifest schema validation fails in the Function App, the error message
  sent on `receive-error-migrated-case-submission` contains
  `"JSON schema validation has failed"`.
- Given that error event is processed, both `migrated-case-submission-received` and
  `error-migrated-case-submission-received` are incremented once each.
- The normal happy path and non-schema failures (material mismatch, server error) are
  unchanged.

**Implementation** — `TimerTriggerJava`: add `JSON_SCHEMA_VALIDATION_FAILED` constant and
prefix the joined validation messages in the schema-validation `processClientError`
overload.

**Tests**

- `TimerTriggerJavaTest.shouldPrefixSchemaValidationFailureMarkerWhenCaseSchemaValidationFails`
  — asserts the captured error message contains the marker and the raw validation detail,
  and that the normal submission payload is never built.
- `StagingDlrmEventProcessorTest.shouldIncrementBothCountersWhenErrorSubmissionFailedSchemaValidationInFunctionApp`
  — error event whose description carries the marker increments both counters (processed
  counter never).
- `StagingDlrmEventProcessorTest.shouldHandleErrorMigratedCaseSubmissionReceived` — extended
  to assert the received counter is **not** incremented for a non-schema error description.

## Verification

- Unit: `stagingdlrm-azure-functions` and `stagingdlrm-event-processor` test suites green.
- End-to-end (dev/sandbox): run a batch with one API-level failure and one Function App
  schema failure; confirm `migrated-case-submission-received` == 2 and
  `error-migrated-case-submission-received` == 2.
