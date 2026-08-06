# Requirements — LIBRA enabler: Function App LIBRA ingest

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> Requirements altitude — nothing here prescribes a class layout. Implementation **tasks** come
> from the design / story-writer stage.

## Story

**[DD-43086](https://tools.hmcts.net/jira/browse/DD-43086) — Accept and route LIBRA blobs through the Azure Function App**

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Size | M |
| Repo / module | `cpp-context-stagingdlrm` → `stagingdlrm-azure-functions` |
| Extends | [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) Function App suites |
| Prerequisite for | [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081)'s end-to-end acceptance criterion |
| Blocked by | nothing in code; the EventGrid path filter needs the infra owner (FR2) |

### Summary (JIRA summary line)

`[LIBRA enabler] Function App: accept LIBRA blobs, select the normalised schema by source system, route to stagingDLRM`

### User story

As a **service owner migrating magistrates' court cases from LIBRA**,
I want **the Function App to accept blobs landing under the LIBRA folder, validate them against a
LIBRA-specific normalised schema, and forward them to the shared stagingDLRM endpoint**,
so that **a LIBRA submission can reach the pipeline at all, and an obviously malformed one is
rejected at the edge rather than consuming a terminal downstream rejection**.

## Requirements

### A. Accept LIBRA blobs

- **FR1 — `dlrm_folder_name` accepts a comma-separated list.** Generalise the membership check
  `validateBatchNames` already implements (`EventGridTriggerJava:119`) so both fields share one
  helper. Deployment config becomes `dlrm_folder_name=XHIBIT,LIBRA`.
  **The folder-name check must not support the `*` wildcard.** Unlike batch name, folder name *is*
  the source-system gate; wildcarding it would accept any legacy system's blobs. Pass the
  wildcard capability as a parameter so the two fields differ by configuration, not by duplicated
  code.
- **FR2 — Verify and if necessary widen the EventGrid subscription path filter.** If the Blob
  Storage EventGrid subscription is scoped to `XHIBIT/*`, no LIBRA blob will trigger the Function
  App however this module changes. Terraform/ARM outside this repo — raise with the infra owner and
  record the answer in this story. Currently **unverified** (analysis §5 Q4).
- **NFR1 — No change to XHIBIT path acceptance.** A blob under an unconfigured folder is still
  rejected, and the queue message format, path parsing and submission-id extraction are unchanged —
  they already treat the folder token as opaque data.

### B. Normalised schema per source system

The Function App **owns its own normalised schema for each source system** and does not depend on
`stagingdlrm-domain-value-schema`. This is a deliberate decision (see `00-input-brief.md`),
overriding analysis §3.2's build-time-unpack proposal.

- **FR3 — Author the LIBRA normalised schema.** A LIBRA-specific case-submission schema alongside
  the existing XHIBIT one, which is **not modified**. Do not derive it by editing a copy of the
  XHIBIT file — derive it from the matrix, which now carries a per-field instruction for exactly
  this task. Filter `libra-schema-impact.csv` on `funcapp_libra_action`:

  | Action | Fields |
  |---|---|
  | `omit` (7) | `dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator`, `dateOfCommittal`, `dateOfSending`, `sendingCourt` |
  | `require` (4) | `initiationCode`, `originatingOrganisation`, `prosecutorCaseReference`, `prosecutor.prosecutingAuthority` |
  | `declare` (5) | `summonsCode`, `informant`, `writtenChargePostingDate`, `cpsOrganisation`, `caseMarkers[].markerTypeCode` |
  | `not-validated-at-gate` (149) | below the depth this gate validates |

  `funcapp_xhibit_status` is the baseline beside it: only **4** of the 7 `omit` fields are actually
  `required` in the existing XHIBIT file (`dateReceived`, `receiptType`, `receivingCourt`,
  `retrialIndicator`) — the other three are not declared there at all. Those four are precisely the
  entries a copy-paste would wrongly demand of LIBRA.
  **`declare` is load-bearing, not cosmetic:** the generated LIBRA `caseDetails` is closed
  (`additionalProperties: false`) where the XHIBIT gate's is open, so a LIBRA field left undeclared
  is rejected *at the gate*.
  Note also that `initiationCode is not restricted to "O"` is trivially satisfied here — **the gate
  carries no constraints at all** (no enums, patterns or lengths, see FR6). The enum relaxation is
  canonical-side work in DD-43081, not this story's.
- **FR3a — Decide how deep the LIBRA gate validates.** Not a detail; it changes the failure profile:

  | Gate | Leaves validated | Branches |
  |---|---:|---|
  | XHIBIT (today) | 8 | `caseDetails` only |
  | LIBRA, if the generated schema is used as-is | 113 | `caseDetails` 9, `defendants` 76, `hearings` 6, `migrationSourceSystem` 2, `officerInCase` 20 |

  That is 14× stronger pre-validation for LIBRA than XHIBIT gets at the same point. Earlier and
  cheaper rejection is a genuine benefit, but it cuts both ways: the workbook's blank and `TBC`
  Format cells (`observedEthnicity`, `arrestDate`, `hearingType` — the three `review-constraint`
  rows) would become **false rejections at the gate**, the earliest and least diagnosable failure in
  the chain, and the two source systems end up with materially different pre-validation strength.
  **Recommendation: match the XHIBIT gate's `caseDetails` depth for the first release** and let the
  canonical schema remain the deep validator; revisit once a real LIBRA sample exists. The matrix
  defaults to that reading — pass `--funcapp-libra-depth full` to `build-schema-impact.py` for the
  other view.
- **FR4 — Source-system-keyed schema selection.** A table-driven lookup — a
  `Map<sourceSystem, {caseValidator, manifestValidator}>`-shaped structure — resolved once and
  cached, not branching logic scattered through `TimerTriggerJava`. Adding a third source system
  later must be one map entry.
- **FR5 — The manifest schema, submission URL and content type stay shared.** Only the case schema
  varies by source system. stagingDLRM exposes one endpoint regardless (epic design decision), so
  nothing about routing changes.
- **FR6 — Drift detection between the Function App and canonical.** *Proposed mitigation — see the
  consequence noted in `00-input-brief.md`; drop it if the team prefers to carry the risk.* A check
  that fails the build when a Function App schema is **more lenient** than canonical for a field
  both declare — the direction that produces a terminal 4xx. The local `case-details.json` is in
  that state today: `additionalProperties: true` vs canonical `false`, no `initiationCode` enum, no
  oucode length constraints, and five properties missing. Detection preserves independent
  ownership; it does not couple the modules.
  The comparison no longer needs writing from scratch: `tools/schema-gen/flatten-canonical-schema.py`
  now flattens **either** schema set into one diffable document, and both flattened forms are
  committed (`schema/canonical/staging-dlrm-canonical-flattened.json` and
  `…-funcapp-flattened.json`). The constraint-level drift is total, not partial — the gate carries
  **zero** constraints against canonical's patterns, lengths and enums — so a "more lenient than
  canonical" check will fire on every constrained field unless it is scoped to fields both declare
  *and* to `required`/`additionalProperties` rather than full constraint parity. Worth scoping
  deliberately, or FR6 will report 100+ findings on day one.
- **FR7 — Derive the source system from one shared helper.** The token is already validated by
  `EventGridTriggerJava` and re-extracted in `TimerTriggerJava`; the splitting logic is currently
  duplicated. One helper, used by both, so the value the schema selection keys on is provably the
  value the gate checked.

### C. Outcome path

- **FR8 — Outcome files land under the correct source-system folder for LIBRA.** `EventGridMonitor`
  writes the outcome JSON the uploader polls — the only feedback channel, since there is no
  synchronous response. Confirm the LIBRA outcome path is derived from the submission rather than
  from a configured constant, and that a **Function App-level rejection** still produces a usable
  outcome file: at that point the case has not been parsed, so the case URN is absent by
  construction. Assert what the file *does* contain.

### D. Tests

- **FR9 — Extend the DD-43078 Function App suites, don't fork them.** LIBRA scenarios added as
  scenario data on the existing structure. Unit level: folder-gate accept/reject per configured
  value, wildcard explicitly *not* widening the folder gate, schema selection per source system,
  both schemas' accept and reject paths, and the outcome file for an edge rejection. XHIBIT
  scenarios stay green.
- **NFR2 — Material bytes are still never downloaded.** The Function App assembles blob *paths*
  only; nothing here may start streaming material content.
- **NFR3 — No new runtime dependency on the WildFly side.** The Function App remains standalone;
  FR6's check is build/test-time only.

## Acceptance criteria

- **AC1** Given `dlrm_folder_name=XHIBIT,LIBRA`, when a blob lands under either folder, then it is
  accepted; and when it lands under any other folder, then it is rejected and logged.
- **AC2** Given `dlrm_folder_name=*`, when a blob lands under any folder, then it is **rejected** —
  the wildcard must not widen the source-system gate.
- **AC3** Given a LIBRA `case.json` that omits `receiptType`, `receivingCourt`, `dateReceived`,
  `retrialIndicator` and both of `dateOfSending`/`dateOfCommittal`, when `TimerTriggerJava`
  validates it, then it passes the LIBRA normalised schema.
- **AC4** Given the same payload, when validated against the XHIBIT normalised schema, then it
  fails — proving the two schemas are genuinely distinct and the selection is doing work.
- **AC5** Given a LIBRA submission folder, when `TimerTriggerJava` processes it, then the payload is
  POSTed to the same stagingDLRM endpoint and content type as an XHIBIT submission.
- **AC6** Given a submission whose blob path names a source system with no configured schema, when
  processed, then it fails with a clear diagnostic rather than a null-pointer or a silent default.
- **AC7** Given a Function App-level validation failure for LIBRA, when the outcome is written,
  then an outcome file appears under the LIBRA path and its content is asserted whole.
- **AC8** Given FR6 is implemented, when a Function App schema is made more lenient than canonical
  for a shared field, then the build fails.
- **AC9** Given `mvn clean install`, when it completes, then all Function App suites pass including
  the DD-43078 XHIBIT scenarios, unchanged.

## Out of scope

- Everything inside stagingDLRM and PCFDLRM — the canonical schema relaxation, the source-system
  validation-rules strategy, the rejection flow and the field additions all belong to
  [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081).
- Making the Function App depend on `stagingdlrm-domain-value-schema` — explicitly rejected; the
  Function App owns its own normalised schemas (`00-input-brief.md`).
- `tools/reconciliation/` `--source-system` support. Own ticket.
- Provisioning or changing the EventGrid subscription itself — FR2 is to verify and raise, not to
  implement infra.
- Any change to material handling, the queue message format, or the batch-size/retry behaviour.

## Risks and notes

- **FR2 is the single biggest delivery risk** and is outside the team's code. If the path filter is
  scoped to `XHIBIT/*`, every other requirement here is untestable end to end until infra changes
  it. Raise it on day one.
- **The drift is asymmetric in the dangerous direction.** The Function App's schema being *more
  lenient* than canonical is what causes terminal 4xx rejections; being stricter would only cause
  earlier, better-diagnosed rejections. FR6 checks only the dangerous direction deliberately.
- **Two places now express source-system rules** — the Function App's schemas and stagingDLRM's
  validation-rules strategy. Accepted with the per-source-system decision. Keep the Function App's
  schemas *structural* (shape, types, presence) and leave business rules to stagingDLRM, or the
  duplication will drift in behaviour as well as in shape.
- An edge rejection loses the case URN in the outcome file. If that proves too thin for support,
  the alternative is to let stagingDLRM reject instead — which is what a single shared schema in
  the Function App would have done.

## Notes for the design stage

1. **FR1 and FR7 are small and independent** — they can land first and reduce the diff for the
   schema work.
2. **FR6 needs a decision before it is designed.** It is a proposal, not a settled requirement;
   confirm with the team, and if accepted, decide whether it fails the build or only warns.
3. **The LIBRA normalised schema is the story's real content.** Derive it from the delta document
   rather than by copying the XHIBIT schema and editing — the delta is regenerable from the
   workbook via `tools/schema-gen/`, so it can be re-checked after any workbook revision.
4. **Coordinate FR3 with DD-43081's FR16** (the workbook-correction list). Several blank/`TBC`
   Format cells in the workbook affect what the LIBRA normalised schema can legitimately assert;
   authoring it will surface more of them.
