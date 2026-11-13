# 02 — Design

- **Inputs:** `01-requirements.md`, [ADR-DD-43946](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/blob/team/libra4/docs/pipeline/adrs/ADR-DD-43946-libra-material-upload-date.md). Q1 – Q6 answered in `01-requirements.md`.

## Change

| # | File | Change | FR |
|---|------|--------|----|
| C-1 | `stagingdlrm-azure-functions/src/main/resources/stagingdlrm.manifest.json` | Add optional `uploadedDateTime` (`type: string`, `format: date-time`) to both entries of the existing `items` tuple. The tuple itself is unchanged (Q3). | FR-1 |
| C-2 | `StagingDlrmCommandHelper` (material loop) | If the entry has `uploadedDateTime`, add it to the material as `receivedDateTime`. | FR-2, FR-4 |
| C-3 | none | Canonical schema and `MigratedCaseConvertor` already carry `receivedDateTime`. | FR-3 |
| C-4 | New `ManifestUploadDateValidator` (`azure/validator/`), with an injected `Clock` | For every `files` entry with `uploadedDateTime`: parse with `Instant.parse`; unparseable or after `clock.instant()` → error message `files[<i>].uploadedDateTime is invalid` / `... is in the future`. Returns the messages. | FR-5 |
| C-5 | `TimerTriggerJava` (after the case and manifest schema checks pass, before building the payload) | Run C-4; if it returns messages → `processClientError(message, baseUriArray, errorMessage, manifestJsonContent, submissionId, caseUrn)`, the existing error-submission path. | FR-5 |

`receivedDateTime` is the existing transport field between stagingdlrm and pcfdlrm; per the ADR it
carries the LIBRA upload date, and pcfdlrm maps it to `progression`'s `uploadDateTime`.

**Q3 and the tuple:** the schema still only format-checks the first two entries, but C-4 parses
`uploadedDateTime` on every entry, so a bad or future date on any entry is caught here. Other fields on
entries 3+ remain unchecked, as today.

**Future check (Q6):** a JSON schema cannot express "not in the future", so it is a code check. "Future"
means after the instant the Function App processes the submission; a time later today counts.

## Options considered

| Option | Why / why not |
|---|---|
| **A. Manifest field → existing `receivedDateTime` (chosen)** | No schema change between the services. |
| B. New `uploadedDateTime` on the canonical and pcfdlrm schemas | Schema change in both repos for a field that already exists unused. |
| C. Put the date in `case.json` | The date belongs to a file; `manifest.json` is where files are described. |
| D. Future date: clear it and continue (as DD-43870 does for DOB) | Not chosen (Q6): the developer wants the submission rejected. |
| E. Future date: reject in pcfdlrm | The material rejection path in pcfdlrm is not built (it throws). Rejecting at the gate also stops the case before anything is created. |

## Tests

- `JsonSchemaValidatorTest` (manifest): valid `uploadedDateTime` accepted; invalid value on the first
  entry rejected.
- `ManifestUploadDateValidatorTest` (fixed `Clock`): past and equal-to-now accepted; one second in the
  future rejected; unparseable value on entry 3 rejected; absent field accepted; message uses the index.
- `TimerTriggerJavaTest`: future date → client-error path, no command sent.
- `StagingDlrmCommandHelperTest`: entry with `uploadedDateTime` → material `receivedDateTime`; without → absent.
- `MigratedCaseConvertorTest` / event-processor fixture: `receivedDateTime` reaches the pcfdlrm payload.
