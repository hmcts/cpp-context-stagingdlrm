# Design — DD-43208

## Decision

Make the Function App's schema-validation error message **contain the marker phrase**
`"JSON schema validation has failed"`, so the existing (and already tested) event-processor
logic increments both counters. No change to the event processor itself.

This mirrors how API-level failures already work — the framework's 400 body starts with
the same phrase, which is exactly why that path already increments both counters.

## Why not change the event processor

`sendEventToGrid` is shared between the error handler and the processed handler. The
`stagingContextErrors` string-match is still required by the processed-handler path
(duplicate / case-already-exists), and unconditionally incrementing the received counter in
the error handler would also change material-mismatch behaviour (out of scope) and risks
double counting. The lowest-risk, intent-consistent fix is at the source of the description.

## Change

`TimerTriggerJava` — the schema-validation `processClientError(Set<ValidationMessage> ...)`
overload (the only caller for the case/manifest schema-failure branch):

```
final String errorMessage = JSON_SCHEMA_VALIDATION_FAILED + ": " + String.join(", ", messages);
```

New public constant `JSON_SCHEMA_VALIDATION_FAILED = "JSON schema validation has failed"`,
matching the event processor's `JSON_SCHEMA` constant text exactly (the coupling is by the
error description, since the two modules do not share code).

## Flow after the change

```
Function App schema validation fails
  → error message = "JSON schema validation has failed: <networknt messages>"
  → receive-error-migrated-case-submission command
  → error-migrated-case-submission-received event
  → StagingDlrmEventProcessor.handleErrorMigratedCaseSubmissionReceived
  → sendEventToGrid: description contains the marker
      → migrated-case-submission-received  ++   (FR1)
      → error-migrated-case-submission-received ++
```

## Side effect (acceptable)

The outcome file / EventGrid description for a Function App schema failure now starts with
`"JSON schema validation has failed: ..."`, making it consistent with the API-level outcome.

## Risk / scope notes

- Material-count mismatch remains received-uncounted. If the "all received" principle is
  meant to cover it too, that is a follow-up, not this ticket.
