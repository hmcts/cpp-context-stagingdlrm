# Requirements — LIBRA enabler: schema enablement

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> Requirements altitude — nothing here prescribes a class layout. Implementation **tasks**,
> including the per-repo split, come from the design / story-writer stage.

## Story

**[DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) — Implement the LIBRA schema delta across stagingDLRM and PCFDLRM**

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Size | L |
| Repos | `cpp-context-stagingdlrm`, `cpp-context-prosecution-casefile-dlrm` |
| Extends | [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) test suites and DSL |
| Blocked by | real LIBRA sample; `initiationCode` decision; `prosecutorOffenceId` workbook fix; FR14a accept-or-strip decision |
| Source of record | [`libra-schema-impact.md`](../../analysis/libra-ingestion/libra-schema-impact.md) |

### Summary (JIRA summary line)

`[LIBRA enabler] Implement the LIBRA schema delta: relax 6 blocking constraints + anyOf, add 39 fields across both repos, resolve 3 accept-only fields, restore enforcement via source-system validation`

### User story

As a **service owner migrating magistrates' court cases from LIBRA**,
I want **the shared DLRM schema to accept a LIBRA case file, carry LIBRA's fields through to
PCFDLRM, and enforce each source system's own rules in code rather than in the schema**,
so that **LIBRA can be ingested through the existing pipeline without forking it and without
weakening what XHIBIT relies on**.

## Requirements

### A. Relax what LIBRA cannot satisfy (stagingDLRM canonical schema)

- **FR1 — Relax the four blocking `caseDetails` requirements.** `dateReceived`, `receiptType`,
  `receivingCourt` and `retrialIndicator` are unconditionally `required` today and LIBRA supplies
  none of them. Remove them from `caseDetails.required`; the fields themselves stay for XHIBIT.
- **FR2 — Relax the `anyOf` combinator.** `anyOf: [dateOfCommittal | dateOfSending]` cannot be
  satisfied by LIBRA, which supplies neither. Note LIBRA has **no case-level court field at all**,
  so the §3.3 proposal of "at least one of `sendingCourt`/`receivingCourt`" is not sufficient
  either — the requirement has to be dropped, or satisfied by deriving a court from the hearing.
- **FR3 — Relax `durationMinutes` and `prosecutorOffenceId`.** Both are required within objects
  LIBRA does supply (`hearings[]`, `offences[]` with `minItems: 1`).
  `prosecutorOffenceId` is blocked on the workbook fix (see FR16) — the workbook declares
  `offenceID` under Listed Offences but nothing on the offence itself.
  **These are the only two.** The CSV shows 13 `relax-required` rows, but 5 of them sit beneath
  objects that are themselves optional (`hearings[].weekCommencingDate.startDate`,
  `personalInformation.address.address1`, and three under `parentGuardianInformation`), so LIBRA
  omitting them costs nothing. Do not chase those 5 — see `00-input-brief.md`, "The 6 real
  blockers". The remaining 6 are FR1's four plus these two, and 2 more are FR9's.
- **FR4 — Drop the `initiationCode` enum.** `enum: ["O"]` compiles to a single-value Java enum, so
  any other value fails Jackson deserialization outright — this cannot be fixed by relaxing
  `required`. Replace with a plain string. Handle the POJO ripple:
  `MigratedCaseConvertor`'s `.getInitiationCode().name()` becomes `.getInitiationCode()`, and
  expect the same wherever a relaxed field was a generated enum.
- **NFR1 — Relax nothing else.** Only constraints LIBRA demonstrably cannot satisfy. Purely
  structural constraints (oucode lengths, patterns, `additionalProperties: false`) stay.

### B. Restore enforcement in code, per source system

The shared schema can only express what is true of both systems. Everything FR1–FR4 removes has to
reappear as a source-system rule, or it is a silent XHIBIT regression.

- **FR5 — XHIBIT validation rules.** Enforce for XHIBIT what the schema no longer does:
  `dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator`, the
  `dateOfCommittal`/`dateOfSending` pair, `durationMinutes`, `prosecutorOffenceId`, and
  `initiationCode == "O"`.
- **FR6 — LIBRA validation rules.** Enforce what the workbook mandates but canonical leaves
  optional: `hearings[].dateOfHearing`, `hearings[].timeOfHearing`,
  `personalInformation.forename`. Plus LIBRA's `initiationCode` value set, once agreed (FR15).
- **FR7 — Source-system rule strategy.** A `MigratedCaseValidationRules` implementation per
  `MigrationSourceSystemName`, selected on the source system the submission already carries and
  evaluated **within the aggregate**, as part of the command it guards. No `if`/`else` on source
  system anywhere — selection is the strategy's job, not a branch. Enforcement belongs to the
  aggregate because FR8 applies the rejection event from there, and an aggregate that emits a
  rejection decided somewhere else is not enforcing its own invariant. The command handler stays
  a pass-through and gains no source-system awareness. See design-stage note 8 for the reference
  implementation.
- **FR8 — Rejection flow to the uploader.** Extend the dormant `MigratedCaseSubmissionRejected`
  event (it currently lacks `submissionId` and any reason/errors field), apply it from the
  aggregate on rule failure, and handle it in the event processor so the outcome reaches Blob
  Storage the same way a schema rejection does today.
- **FR9 — Canonical stays stricter where it already is.** `caseMarkers[].markerTypeCode` and
  `selfDefinedInformation.gender` are `required` in canonical while the workbook marks them
  `O`/`CM`. Per the constraint policy, canonical wins and LIBRA data must comply; both are
  included in the workbook-correction list (FR16).
  **Deliberate divergence from the CSV:** both rows read `change_type: relax-required`, because the
  tool reports what LIBRA needs in order to pass, not what was decided. This FR overrides it. Same
  for `offenceDateCode` in FR10 (`relax-constraint` in the CSV). Do not "fix" the schema to match
  the CSV on these three.

### C. Constraint conflicts — canonical wins, workbook gets corrected

12 `exists_different_constraint` rows: 10 real conflicts, plus 2 generator artefacts (`minimum: 0`
added to `N<n>` integers) that the tool now strips before comparing, so they never surface.

- **FR10 — Keep canonical where the workbook is looser or blank.** No change to the schema for:

  | Field | Canonical (keep) | Workbook says |
  |---|---|---|
  | `offences[].arrestDate` | `$ref` date pattern | no Format cell |
  | `personalInformation.observedEthnicity` | `integer` | no Format cell |
  | `hearings[].hearingType` | `maxLength: 10` | `TBC` |
  | `offences[].offenceDateCode` | `integer` 1–6 | `N1` (0–9) |

- **FR11 — Adopt the workbook only where it is stricter (discretionary).** Tighten canonical to
  match: `individualAlias.firstName`/`givenName2`/`givenName3`/`lastName` → `maxLength: 35`;
  `migrationSourceSystem.migrationSourceSystemCaseIdentifier` → `maxLength: 100`. Confirm no
  existing XHIBIT data exceeds these before tightening.
  **This is opportunistic hardening, not a LIBRA need.** All five rows read
  `change_type: libra-rule-only` / `change_required: no` — canonical is already the more permissive
  side, so nothing forces the change. If it is dropped, the workbook's tighter bound belongs in the
  LIBRA validation rules (FR6) instead. Do not expect a CSV row to justify this FR.

### D. Add the LIBRA fields (tiers 1–4)

41 fields are in tiers 1–4 of impact §5. Two of them have **no home in either PCFDLRM or the core
case model**, so adding them achieves nothing.

- **FR12 — Add the 18 tier-1/2 fields to canonical as optional.** At **PCFDLRM's nesting level,
  not the workbook's flat one** — `driverNumber`, `licenseCode`, `nationalInsuranceNumber` on
  `individual`; `occupation`, `defendantOccupationCode` on `personalInformation`. Tier-2 fields
  take PCFDLRM's names: `licenseCode` → `driverLicenceCode`, `defendantOccupationCode` →
  `occupationCode`, `middleName2` → `givenName3`, `prosecutorCompensation` →
  `appliedCompensation` (confirm the last one's semantics before mapping).
  `nationalInsuranceNumber` is nearly free — stagingDLRM's own `pcf-definitions.json` already
  defines the pattern and references it nowhere.
- **FR13 — Add the tier-3 officer block and tier-4 fields, with the matching PCFDLRM work**, so
  none of them is write-only:
  - **stagingDLRM:** add the officer-in-case block (18 of the 20 tier-3 fields) and the 3 tier-4
    fields (`convictionDate`, `numPreviousConvictions`, `organisationTelephoneNumber`).
  - **PCFDLRM:** wire the **orphaned** `pcf-policeOfficerInCase.json` (it exists but is referenced
    by nothing), and add the 6 fields PCFDLRM lacks — `policeWorkerReferenceNumber`,
    `policeWorkerLocationCode`, `faxNumber`, `convictionDate`, `numPreviousConvictions`,
    `organisationTelephoneNumber`.
  - Note core's `policeOfficerInCase.json` marks `personDetails`, `policeOfficerRank`,
    `policeWorkerReferenceNumber` and `policeWorkerLocationCode` **all `required`** when the block
    is present — so a partial officer block will be rejected downstream. Filter
    `progression_status: exists_mandatory` for the full list: those three plus officer `surname`
    (`person.lastName`) and officer `address1` (`address.address1`). Five fields, all in this block,
    and the only `exists_mandatory` rows in the matrix — mandatory *if the block is sent at all*,
    which is why this is a constraint on the new work rather than a live defect.
- **FR14 — Exclude what has nowhere to go, but "exclude" is not free.** Do **not** *map* onward
  `uniquePropertyReferenceNumber` or `dxAddress` (no home in PCFDLRM or core), nor the 3 tier-5
  fields (`informant`, `writtenChargePostingDate`, `prosecutorCosts`). Net **mapped** additions:
  **39**.
  Record explicitly that **7 of the 39 reach PCFDLRM but die before Progression** — `backDuty`,
  `backDutyDateFrom`, `backDutyDateTo`, `prosecutorOfferAOCP`, `prosecutorCompensation`,
  `middleName2`, officer `forename3` — and confirm PCFDLRM is the intended consumer for those.
  (`vehicleMake` was previously listed here and is now known to reach Progression via
  `offence.offenceFacts`; the old ruling measured reachability against the wrong core model — see
  impact §8.)
- **FR14a — Resolve the three tier-5 fields; they cannot simply be left out.** `informant` and
  `writtenChargePostingDate` sit in `caseDetails`, and `prosecutorCosts` in `defendant`, and **both
  objects are `additionalProperties: false`**. LIBRA's payload carries all three, so omitting them
  from the schema is a terminal, non-retryable 4xx on **every** LIBRA submission — not a no-op. The
  CSV classes them `change_type: declare-only`, `change_required: yes`. Pick one per field:
  1. declare in canonical as an accepted-but-unmapped optional field (`MigratedCaseConvertor` does
     not carry it onward), or
  2. strip it before the command call, or
  3. require the LIBRA extract not to emit it — a dependency on the workbook owner / extract team,
     so it cannot be an AC until they confirm.
  The same question applies to `uniquePropertyReferenceNumber` and `dxAddress` **if** the new
  `officerInCase` block in FR13 is authored closed, which the generated LIBRA schema is.
  Note also that `informant`'s nesting is unsettled: the workbook puts it on `caseDetails`, but the
  committed RAML **example payloads in both repos** put it at `caseDetails.prosecutor.informant`,
  and canonical `prosecutor` is closed with a single property.

### E. Cross-team and workbook outputs

- **FR15 — Resolve LIBRA's `initiationCode` value(s)** with the PCFDLRM / reference-data team. The
  value determines which existing PCFDLRM rule set LIBRA routes into; if it is `S`/`Q`, LIBRA
  routes into the already-built `SUMMONS`/`REQUISITION` sets with no PCFDLRM rule change for that
  dimension. Then decide, per each of the three XHIBIT-only behaviours PCFDLRM guards (analysis
  §3.4), whether LIBRA needs equivalent, different, or no handling — mirroring the existing scoped
  guards if only a few rules diverge, or adding a source-system axis to
  `CcProsecutionValidationRuleProvider` if the profile differs materially.
- **FR16 — Produce a LIBRA-workbook correction list.** A documented set of amendments to request
  from the workbook owner, bringing the workbook back into sync with canonical rather than the
  reverse. At minimum: the four blank/`TBC` Format cells in FR10; `offenceDateCode`'s range;
  the missing `prosecutorOffenceId` on the offence (impact §6); the apparent
  `organisationTelephoneNumber` / `companyTelephoneNumber` duplication; and the `markerTypeCode` /
  `gender` mandatoriness mismatches from FR9. Add the **RAML example payloads** in both repos: they
  are not validated at build time and have drifted from the schemas they illustrate — the
  stagingDLRM example carries `caseDetails.caseId` and `caseDetails.summonsCode` (neither declared,
  and `caseDetails` is closed), omits all four fields FR1 relaxes, and puts `informant` under
  `prosecutor`. This is a fourth instance of the hand-maintained-copy drift theme. Delivered as an artefact in this story's directory,
  reviewable without reading the schema.

### F. Tests

- **FR17 — Extend the DD-43078 suites, don't fork them.** LIBRA scenarios are added as scenario
  data on the existing DSL. Unit level: exhaustive — every relaxed constraint accepted *and*
  rejected for each source system, every added field carried through, both new rejection flows.
  IT level: **one representative LIBRA journey per repo**, at the depth DD-43078 established.
- **NFR2 — No XHIBIT regression.** Every XHIBIT scenario from DD-43078 passes unchanged. This is
  the regression signal that story was built to provide, and the exit criterion for this one.

## Acceptance criteria

- **AC1** Given a real LIBRA `case.json` + `manifest.json`, when submitted, then it is accepted,
  forwarded to PCFDLRM, processed, and a success outcome is written to Blob Storage.
- **AC2** Given every XHIBIT scenario from DD-43078, when the suites run after every change here,
  then all pass unchanged.
- **AC3** Given an XHIBIT submission missing any field in FR5, when it is validated, then it is
  rejected by the XHIBIT rules — proving the relaxation did not weaken XHIBIT.
- **AC4** Given a LIBRA submission missing any field in FR6, when it is validated, then a
  `MigratedCaseSubmissionRejected` outcome carrying `submissionId` and the validation errors
  reaches Blob Storage.
- **AC5** Given an `initiationCode` value other than `"O"`, when a submission is deserialized and
  processed, then it succeeds — demonstrating the compiled-enum blocker is gone.
- **AC6** Given a LIBRA case carrying each of the 39 mapped fields, when processed, then each
  arrives at PCFDLRM, and each of the 6 new PCFDLRM fields is populated.
- **AC6a** Given a LIBRA case carrying `informant`, `writtenChargePostingDate` and
  `prosecutorCosts`, when submitted, then it is **accepted** — proving FR14a is resolved and a
  closed-object rejection cannot happen on a real payload.
- **AC7** Given a LIBRA case with an officer-in-case block, when PCFDLRM processes it, then the
  block reaches the payload built for Progression with all four core-required fields present.
- **AC8** Given the constraint conflicts in FR10, when the schema is inspected, then canonical's
  constraint is unchanged for all four, and `caseMarkers[].markerTypeCode` / `gender` are still
  `required` per FR9 — the three deliberate divergences from the CSV hold. FR11 is discretionary; if
  taken, the two tightened constraints are in place.
- **AC9** Given the workbook-correction list from FR16, when review completes, then it is shared
  with the workbook owner and any accepted amendment is reflected in a regenerated delta.
- **AC10** Given `mvn clean install` in both repos, when it completes, then all suites pass and the
  regenerated matrix shows no unexplained `not_in_libra` blocker for LIBRA, and no
  `declare-only` row left unresolved.

## Out of scope

- **plea / verdict / allocationDecision code-vs-UUID** (impact §7). The workbook supplies
  reference-data codes; canonical and PCFDLRM expect resolved UUIDs; no resolver exists anywhere in
  the pipeline, and it affects XHIBIT equally. **Raise as its own ticket.**
- *Mapping* tier 5 fields, plus `uniquePropertyReferenceNumber` and `dxAddress`, onward to PCFDLRM
  or Progression (FR14). Deciding how canonical **accepts** them is in scope — see FR14a.
- The Function App's source-system schema selection, comma-separated `dlrm_folder_name`, and the
  build-time schema-drift fix. **These are a prerequisite for a LIBRA blob to reach stagingDLRM at
  all** — they need their own story under this epic, and the drift fix must land before a second
  local schema is added or it creates a third drifting copy (analysis §3.2).
- `tools/reconciliation/` `--source-system` support — operationally required before a LIBRA batch
  can be reconciled, but not a schema change. Own ticket.
- The EventGrid subscription path filter (infra, outside these repos — analysis §5 Q4).
- Progression and `cpp.platform.core.domain` changes — none identified (analysis §3.5), to be
  confirmed by AC1 rather than assumed.
- Fixing the delta tooling's blocker heuristic (it over-reports by ignoring optional parent
  objects — see `00-input-brief.md`). Own ticket.

## Risks and notes

- **Coupled blast radius** is the accepted cost of the shared-schema design (analysis §2). NFR2 is
  the mitigation and must stay deliberate: test both source systems on every change to shared
  validation or schema code.
- **The relaxation is the risky half, the additions are the safe half.** FR1–FR4 change behaviour
  for XHIBIT; FR12–FR13 are additive. If the story needs to be split, that is the seam.
- `MigratedCaseConvertor` is an explicit field-by-field mapping and the single translation point
  into PCFDLRM's types — every field in FR12–FR13 needs a line there, and it is the likeliest
  place for a silent drop.
- **core's `phone` and `nino` patterns diverge from stagingDLRM's** (impact §8). stagingDLRM allows
  a leading `+` on phone and core does not, so a number valid here fails core's pattern. Not caused
  by this story, but FR12 adds `nationalInsuranceNumber`, which sits on the stricter DLRM pattern —
  worth confirming which wins before wiring it.
- **`durationMinutes` relaxation has an operational edge**: it is `required` today, so existing
  XHIBIT fixtures always carry it. Relaxing it means the field can now be absent in a payload
  PCFDLRM receives — check PCFDLRM does not assume presence.

## Notes for the design stage

1. **This story is large.** The natural split, if needed: (a) relaxation + source-system validation
   + rejection flow (FR1–FR9, the behaviour-changing half); (b) field additions across both repos
   (FR12–FR14, additive); (c) cross-team decisions and the workbook correction list (FR15–FR16),
   which can run in parallel from day one since they are conversations, not code.
2. **FR16 is a conversation-starter, not a blocker.** Draft the correction list early — several
   answers change FR10's field list.
3. **The delta is regenerable.** After any workbook revision, run
   `./tools/schema-gen/regenerate.sh` — it refreshes both flattened live schemas, the generated
   LIBRA schema and the impact CSV in dependency order — then re-read §1 and §5 rather than
   re-deriving by hand. The curated downstream claims are verified against both downstream
   checkouts on each run, and a stale claim fails the run.
4. **Deriving the FR5 / FR6 rule lists from the CSV.** `change_type` is the switch: every
   `relax-*` row (17) is a constraint the shared schema stops enforcing, so it needs an **XHIBIT**
   rule (FR5); every `libra-rule-only` row (8) is the mirror image — canonical already permits it
   and the workbook is stricter — so it needs a **LIBRA** rule (FR6). `change_detail` carries the
   guard sentence per row. No separate column: `change_type` already says it.
5. **Two traps if anyone re-derives the Progression column.** (a) The payload PCFDLRM sends is
   `progression.initiate-court-proceedings` carrying `InitiateCourtProceedings` = `{id, CourtReferral}`,
   so the root is **`courtReferral.json`** — *not* `apiProsecutionCase.json`. core's
   `criminal-court-public-model` holds two parallel families, the external `api*` read model and the
   internal command model, and their reachability closures are disjoint. (b) Matching a field by
   name alone picks whichever schema sorts first, which is how officer `forename`/`surname` once
   resolved to `judicialRole.json` and came out mandatory for the wrong reason. Both are documented
   in impact §8.
6. **`assumed_flowing` is an assumption.** The 121 fields already in canonical are taken as reaching
   Progression because XHIBIT is in production; it is not verified, and three of them
   (`alcoholOrDrugLevelAmount`, `alcoholOrDrugLevelMethod`, `middleName`) have no counterpart in
   PCFDLRM's schema at all. Pre-existing and XHIBIT-only, so out of scope here, but do not read the
   label as a guarantee.
7. **Sequencing against the Function App story.** Nothing in this story can be proven end to end
   until a LIBRA blob can reach stagingDLRM, which is the Function App story's job. Unit and
   component coverage does not depend on it; AC1 does.
8. **FR7 has a reference implementation in `cpp-context-results`.**
   `HearingFinancialResultsAggregate.updateFinancialResults` calls a static factory
   (`ResultNotificationRuleEngine.resultNotificationRuleEngine()`) inline in the command method;
   the engine self-registers its rules and each rule selects itself. Three properties are the
   reason to follow it. It needs **no injected collaborator** — an event-sourced aggregate is
   reconstructed by `AggregateService` and replayed, so it cannot hold one. It leaves the
   **command signature unchanged**, since the source system is already inside the submission —
   which is what stops DD-43078's aggregate scenarios from being rewritten when this lands. And it
   keeps the rules in the domain module, where FR8's rejection event is raised. One deliberate
   divergence to confirm at design: results dispatches by per-rule `appliesTo(input)` predicate,
   whereas source-system selection here is strictly one-of-N, so a map keyed on
   `MigrationSourceSystemName` is the simpler fit.
