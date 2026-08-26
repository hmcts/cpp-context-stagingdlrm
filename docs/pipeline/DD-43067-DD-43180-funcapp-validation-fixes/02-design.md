# Design — DD-43180: Function App validation parity + counter/outcome fixes

> Stage 2 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [ADR-002](../adrs/002-source-system-keyed-dispatch.md),
> [ADR-003](../adrs/003-libra-payload-contract.md),
> [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md).

## Pattern

No new pattern. Three localised changes: two structural schema edits at the gate, one boolean-list edit
in the event processor, and one field extraction in the func-app. The domain engine is untouched.

## Scope map (requirement → artefact)

| Req | Artefact | Change |
|---|---|---|
| FR1 | `stagingdlrm-azure-functions/src/main/resources/case-details.json` | `initiationCode` enum `["O"]` — XHIBIT gate otherwise untouched |
| FR2 | `stagingdlrm-azure-functions/src/main/resources/libra.case-submission.json` | `initiationCode` enum `["C","Q","J","R"]` |
| FR4 | `stagingdlrm-azure-functions/.../TimerTriggerJava.java` | prefix the gate validation-failure description with the `JSON schema validation has failed` marker (event processor unchanged) |
| FR5 | `stagingdlrm-azure-functions/.../TimerTriggerJava.java` | `extractCaseUrn(caseJsonContent)`; thread through `processClientError` |
| FR6 | `stagingdlrm-azure-functions/src/main/resources/libra.case-submission.json` | reconcile to 0.13.1 (A remove + B add); `+ libra-case-submission-valid.json`, `JsonSchemaValidatorTest` |

## FR1 / FR2 — Initiation-code enums at the gate

The func-app keeps separate schemas per source system (XHIBIT: `stagingdlrm.case-submission.json` →
`migrated-case.json` → `case-details.json`; LIBRA: self-contained `libra.case-submission.json`), so the
per-source-system `initiationCode` allowed-values are expressible structurally as an `enum` in each
system's schema — no rule engine. `case-details.json` is reached **only** by the XHIBIT chain, so its
`["O"]` enum is XHIBIT-scoped. This is the **only** XHIBIT gate change: the existing required fields are
left as-is and the XHIBIT `anyOf` is **not** added. On the LIBRA side the presence rules
(`defendant.address`, `hearing.dateOfHearing`, `hearing.timeOfHearing`) already hold, so the only LIBRA
`initiationCode` addition is its enum. The XHIBIT `anyOf` stays domain-only (ADR-004 §3); the LIBRA
`hearings[*].courtRoomId` rule is mirrored once FR6 adds the field to the gate hearing.

## FR4 — Received-counter fix

`StagingDlrmEventProcessor` increments the received counter on a failure when the description matches a
marker in `stagingContextErrors` — which includes `"JSON schema validation has failed"` (`JSON_SCHEMA`).
Framework-level schema failures already carry that text, so they increment received. A **gate** rejection
carries only JSON-path messages (e.g. `$.migratedCase.caseDetails.initiationCode: does not have a value
in the enumeration [O]`), which the marker match misses — so it wasn't counted as received.

The fix is in the func-app: `TimerTriggerJava` prefixes the joined gate validation messages with the
`"JSON schema validation has failed"` marker before building the error command. The description then
matches `JSON_SCHEMA`, so a gate rejection increments **both** the received and error counters, exactly
like a framework-level schema failure. The event processor is unchanged (`JSON_SCHEMA` stays in
`stagingContextErrors`); EventGrid suppression is a separate check (`isDuplicateSubmissionId`) and is
unaffected. The marker string is duplicated across the two layers by the substring-matching design; a
comment on the func-app constant records the coupling.

## FR5 — caseUrn extraction

The bug is a hard-coded `caseUrn = ""` in the `processClientError` overload used for gate failures. Fix:
a defensive `extractCaseUrn(caseJsonContent)` reading
`migratedCase.caseDetails.prosecutorCaseReference` (returning `""` on any parse/shape failure), computed
once in `processQueueMessage` from the **case** content and threaded into both the case- and
manifest-failure branches. It must read the case content specifically — on a manifest-only failure the
generic `jsonContent` param is the manifest, which has no `prosecutorCaseReference`. Downstream the value
flows unchanged: error command → `ErrorMigratedCaseSubmissionReceived` → `Outcome` →
`EventGridMonitorHelper` → outcome JSON.

## FR6 — LIBRA gate reconciliation to 0.13.1

The LIBRA gate (`libra.case-submission.json`) had drifted from the 0.13.1 payload contract. FR6 aligns it
(groups A+B): remove the fields 0.13.1 no longer carries (led by `officerInCase`), add the fields it
requires/declares (`courtRoomId`, `durationMinutes`, `prosecutorOffenceId` — all required — plus
`convictingCourtCode`, `emailAddress1/2`, `personalInformation.address`, `individualAlias.title`). The
LIBRA valid fixture and the `JsonSchemaValidatorTest` officerInCase/summonsCode cases move with it; the
newly-required fields are added to the valid fixture and the `validDefendant`/`validHearing` builders.
`migrationSourceSystem` stays out (assembly-added). Group C (code-vs-UUID, `observedEthnicity` type,
`offenceDateCode` range) is a follow-up for the extract team — flipping `plea`/`verdict` to required UUIDs
could reject a real extract that still sends codes (the open R3 question).

## Out of scope

Domain engine; PCFDLRM; LIBRA gate group-C conflicts (FR6a); mirroring the XHIBIT `anyOf` at the gate.

## Testing approach (for Stage 4 — informative)

- Gate: extend `JsonSchemaValidatorTest` with XHIBIT-rejects-`C` and LIBRA-rejects-`O`, mirroring
  `MigratedCaseValidationRuleEngineTest`; remove the stale "gate does not enforce an initiationCode enum"
  row; move the one accepted-payload fixture's out-of-enum `initiationCode` to a valid value.
- Counter: `StagingDlrmEventProcessorTest` asserts the received **and** error counters both increment on
  a schema failure — for a framework-form description and a gate-form (`marker: JSON-path`) description;
  `TimerTriggerJavaTest` asserts the func-app gate error message carries the marker.
- caseUrn: update the `TimerTriggerJavaTest` and `EventGridMonitorHelperTest` rejection cases to assert
  the extracted `prosecutorCaseReference`.
