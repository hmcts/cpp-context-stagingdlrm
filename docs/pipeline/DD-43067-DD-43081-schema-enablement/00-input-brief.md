# Input brief — LIBRA enabler: stagingDLRM schema enablement

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this
> directory.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) — stagingDLRM schema enablement |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |
| Prerequisite | [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) — XHIBIT regression test hardening, **delivered** (T1–T4 merged) |

## The epic this story belongs to

**DD-43067 — LIBRA enabler.** Ingest magistrates' court case files from legacy system LIBRA
through the existing DLRM pipeline (Azure Blob → Function App → stagingDLRM → PCFDLRM →
Progression), reusing the XHIBIT path rather than forking it.

**Design decision already taken for the epic** (analysis §2): XHIBIT and LIBRA share **one**
stagingDLRM endpoint and **one** schema family. Source-system-specific behaviour is pluggable,
source-system-keyed strategies inside the shared path — not duplicated schemas, endpoints, or
command/event types. The rejected separate-schema alternative and the five evidence-based reasons
it was dropped are in
[`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) §7.

The consequence that drives half this story: **a shared schema can only express what is true for
both source systems.** Anything true of only one has to move out of JSON Schema and into
source-system validation. Relaxing `receiptType` so LIBRA can pass, without an XHIBIT rule to
replace it, is a silent regression for XHIBIT.

## Scope boundaries

| In scope | Out of scope |
|---|---|
| `stagingdlrm-domain-value-schema` — canonical schema | `stagingdlrm-azure-functions` — the LIBRA gate schema and source-system schema selection belong to **[DD-43086](https://tools.hmcts.net/jira/browse/DD-43086)**, even though the module lives in this repo |
| `stagingdlrm-domain-event` — the rejection event schema | `cpp-context-prosecution-casefile-dlrm` — its own pipeline in its own repo |
| `stagingdlrm-domain-aggregate` — validation rule engine + rejection path | `cpp-context-progression` — no change identified (analysis §3.5) |
| `stagingdlrm-event-processor` — `MigratedCaseConvertor` | Reconciliation tooling `--source-system` parameter (analysis §3.6) — operational, separate ticket |
| `tools/reconciliation/` — rejection visibility in the report (change set 4) | |

## This story's request

Four change sets, in dependency order.

### Change set 1 — Schema changes

Selection rule set by the tech lead: **add the fields that have a home in Progression; leave the
ones that are ambiguous or have no home downstream.** The full 165-field comparison is
[`libra-schema-impact.csv`](../../analysis/libra-ingestion/libra-schema-impact.csv); the paragraphs
below are that CSV filtered by this rule.

LIBRA sends 44 fields the canonical schema does not have. Applying the rule splits them three ways.

**Group A — 12 fields: add to canonical, map in the converter now.** These have a counterpart in
PCFDLRM *and* in Progression's `courtReferral.json` closure today, so they flow end to end with
changes in this repo alone.

| Container | Fields |
|---|---|
| `caseDetails` | `summonsCode` |
| `defendants[*]` | `driverNumber`, `nationalInsuranceNumber`, `occupation`, `defendantOccupationCode`†, `licenseCode`† |
| `defendants[*].offences[*]` | `statementOfFacts`, `statementOfFactsWelsh`, `vehicleCode`, `vehicleMake`, `vehicleRegistrationMark` |
| `…individual.selfDefinedInformation` | `additionalNationality` |

† PCFDLRM holds these under a different name (`occupationCode`, `driverLicenceCode`) — a converter
mapping, not a schema difference.

Note on nesting: the workbook's Defendant section is flat, but PCFDLRM puts `driverNumber`,
`licenseCode` and `nationalInsuranceNumber` on `individual`, and `occupation` /
`defendantOccupationCode` on `personalInformation`. **Canonical follows PCFDLRM, as the XHIBIT flow
already does** — canonical is a strict subset of PCFDLRM at every level today, and
`MigratedCaseConvertor` renames without ever re-nesting. So these are declared at PCFDLRM's level
and the LIBRA payload must nest to match; see register item 11.

**Group B — 20 fields: add to canonical now, converter mapping blocked on PCFDLRM.** Progression
already models these, but PCFDLRM has no home for them, so they are write-only in this repo until
the PCFDLRM pipeline lands.

| Container | Fields |
|---|---|
| `officerInCase` (new) | `forename`, `forename2`, `surname`, `policeOfficerRank`, `policeWorkerReferenceNumber`, `policeWorkerLocationCode`, `primaryEmail`, `secondaryEmail`, `workTelephoneNumber`, `mobileTelephoneNumber`, `faxNumber` |
| `officerInCase.address` (new) | `address1`–`address5`, `postcode` |
| `defendants[*]` | `numPreviousConvictions`, `organisationTelephoneNumber`‡ |
| `defendants[*].offences[*]` | `convictionDate` |

‡ Possible workbook duplicate of `companyTelephoneNumber` — see the register below.

`officerInCase` is a **new top-level container on `migratedCase`**, which is
`additionalProperties: false`. Declaring it is therefore not optional: LIBRA sends the block, so
leaving it undeclared is a terminal 4xx on every LIBRA submission carrying an officer.

**Group C — 6 fields: declare in canonical as accepted-but-unmapped.** No Progression home, so
nothing to propagate — but each sits in a **closed** canonical object that LIBRA populates, so
silently ignoring them is not available.

| Container (`additionalProperties: false`) | Fields |
|---|---|
| `caseDetails` | `informant`, `writtenChargePostingDate` |
| `defendants[*]` | `prosecutorCosts` |
| `officerInCase` (new, closed) | `dxAddress`, `forename3`, `uniquePropertyReferenceNumber` |

They are declared as optional, documented as unmapped, and deliberately **not** touched by
`MigratedCaseConvertor`.

**Group D — 6 fields: left out entirely.** No Progression home, and their canonical containers
(`offence`, `personalInformation`) are already open, so omitting them really is a no-op. Listed in
the register below for the Technical Architect.

**Relaxations — 10 constraints.** Separately from the additions, the canonical schema currently
rejects a valid LIBRA case on 10 counts. Verified against the live
`case-details.json` / `migrated-*.json` today:

| Kind | Count | What |
|---|---|---|
| Unconditional `required` | 7 | `caseDetails`: `dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator` · `hearings[*].durationMinutes` · `offences[*].prosecutorOffenceId` · `selfDefinedInformation.gender` |
| `anyOf` combinator | 2 | `caseDetails` requires one of `dateOfCommittal` / `dateOfSending`; LIBRA supplies **neither** |
| `enum` | 1 | `caseDetails.initiationCode` is `enum: ["O"]`; LIBRA supplies C / J / Q / S — widened to the platform's seven codes, see below |

All 7 sit in objects that are always present in a LIBRA payload, so each really does block.

**A further 6 constraints the CSV reports as `relax-required` stay exactly as they are.** They sit
inside objects LIBRA omits entirely, so the constraint never fires and weakening it buys nothing —
an object may be absent, but if it is present its fields keep their strictness (FR2a). Where LIBRA's
sheet disagrees, the sheet is corrected to match XHIBIT.

All 10 still need an XHIBIT rule (change set 2), because relaxing any of them removes the only
thing enforcing it for XHIBIT.

**Rule: for a field already in canonical, only required/optional changes.** No length, pattern,
range, type or enum is touched — where LIBRA is tighter than canonical, that belongs in the LIBRA
rules, not the schema. `initiationCode` is the single exception, because its one-constant enum
blocks deserialization outright.

**`initiationCode` widens to the platform's seven codes.** `enum: ["O"]` compiles to a Java enum
with a **single constant**, held on `CaseDetails` as that type — so a LIBRA value like `"S"` is an
`InvalidFormatException` at deserialization, before any aggregate or rule code runs. The constraint
cannot be handled by a validation rule; it has to change in the schema.

The CPP platform's own `uk.gov.justice.core.courts.InitiationCode` already declares
`Q, R, S, C, J, Z, O` — every code LIBRA supplies — so stagingDLRM's `["O"]` is the outlier. The
schema adopts the platform's seven and the field **stays a typed enum**.

Two consequences:

- **No converter change for `initiationCode`.** `MigratedCaseConvertor:256`'s
  `.getInitiationCode().name()` keeps compiling, because the field remains an enum and simply gains
  constants. Keeping it typed is the reason to widen rather than remove.
- **Both source systems need an allowed-values rule** (change set 2), because the schema admits all
  seven to each: XHIBIT restricted to its own set, LIBRA to `C, J, Q, S`. One parameterised rule
  class registered twice covers it.

**Constraint conflicts where the workbook is looser: keep canonical.** A blank or `TBC` Format cell
in the workbook is a gap in the workbook, not a requirement to loosen the schema. Adopt the
workbook only where it is *stricter*. The three affected rows go to the workbook owner as proposed
corrections (see deliverables).

### Change set 2 — Validation rules

Introduce the first non-schema validation layer in this module — **inside
`MigratedCaseSubmissionAggregate`**, not in the command handler. The aggregate is what appends
events, and it already makes exactly this kind of decision on its duplicate-submission branch.

The mechanism follows the established CPP precedent:
`HearingFinancialResultsAggregate.updateFinancialResults()` in `cpp-context-results` (line 188)
calling `ResultNotificationRuleEngine` — a plain class, rules instantiated in code, invoked
directly from the aggregate, with no CDI and no injection. That is what makes an engine usable from
an aggregate the container does not manage.

**One deliberate departure from that precedent:** dispatch is a statically-initialised
`Map<MigrationSourceSystemName, List<rule>>` held by the engine, which selects the list for the
payload's source system and runs it. Rules carry **no `appliesTo` method** and do not know which
source system they serve. Keeping dispatch in the map rather than in 25 individual rules makes each
source system's full rule set readable in one place. Because the map is `static final`, rules are
shared across all aggregate instances and threads and must therefore be **stateless**.

**`stagingdlrm-command-handler` is therefore unchanged.** Both rule sets are in scope.

**XHIBIT rules — re-impose all 10 relaxed constraints.** This is the regression guard: every
constraint change set 1 removes from the schema reappears here, keyed to XHIBIT. Net effect on
XHIBIT must be zero. For `initiationCode` that is an allowed-values rule restricting XHIBIT to its
own code set, not a presence check.

**LIBRA rules — 9 constraints the workbook states and the schema cannot.**

| Fields | Rule |
|---|---|
| `hearings[*].dateOfHearing`, `hearings[*].timeOfHearing`, `…personalInformation.forename` | mandatory for LIBRA, optional in canonical |
| `individualAliases[*]`: `firstName`, `givenName2`, `givenName3`, `lastName` | workbook length limits tighter than canonical |
| `migrationSourceSystem.migrationSourceSystemCaseIdentifier` | as above |
| `caseDetails.initiationCode` | restricted to `C, J, Q, S` — the widened schema also admits `R`, `Z` and `O`, which LIBRA does not send |

The first eight are fields where LIBRA is *stricter* than canonical, so no schema change is
involved. The ninth exists only because change set 1 widened the enum — without it, widening the
schema for LIBRA silently widens LIBRA itself.

**Caveat carried forward:** no real LIBRA `case.json` sample exists yet (analysis §5 Q1), so the
LIBRA rule content is derived from workbook V0.13 and is unvalidated against real data. Rules being
individual classes makes revising it a content change, not a structural one.

**The rejection path — mirroring the duplicate branch exactly.** On failure the aggregate raises
`MigratedCaseSubmissionRejected` + `MigratedCaseSubmissionProcessed(processingIsSuccessful = false)`
and **not** `MigratedCaseSubmissionReceived`, because `Received` is the forwarding trigger:
`StagingDlrmEventProcessor:76` resolves a case id via `system-id-mapper` and POSTs to pcfdlrm
unconditionally on it. The duplicate branch already avoids this the same way, by raising the
distinct `DuplicatedMigratedCaseSubmissionReceived`, which no processor handles.

Three consequences:

- **No new `@Handles` is needed.** The existing handler for
  `stagingdlrm.events.migrated-case-submission-processed` already calls `sendEventToGrid`, so the
  outcome file is delivered by the current path.
- **`azureLocation` must come from the command payload.** The aggregate's map is populated only by
  `apply(…Received)`, which never runs before a first-submission rejection — state would give
  `null` and the outcome file would have no destination. It is `required` on
  `migrated-case-submission.json`, so the payload always has it.
- **No `apply()` branch for the rejection.** It falls through `otherwiseDoNothing()` like
  `Duplicated…` and `Processed` already do; `Received` stays the only event mutating the aggregate.

The dormant `stagingdlrm.events.migrated-case-submission-rejected` event exists as schema +
generated POJO and is referenced by **no Java in the repo** (verified). Its shape
(`caseDetails`, `createdBy`) is unusable as-is — no `submissionId`, no reason field — so it is
extended rather than replaced.

The duplicate check keeps its early return and runs first, so a payload that is both duplicate and
invalid reports as a duplicate — and, since `sendEventToGrid` suppresses EventGrid for duplicates,
produces no outcome file. Unchanged behaviour, now reachable by a second route.

This matters because a stagingDLRM rejection is **terminal, not transient** — 4xx gets zero
retries, so the uploader must resubmit (analysis §4). The rejection path is part of the contract,
not an edge case.

### Change set 3 — Converter / event-processor propagation

`MigratedCaseConvertor` is an explicit field-by-field typed mapping (341 lines, ~24 build methods),
not a passthrough. Propagate **Group A's 12 fields** — 10 direct copies plus the two renames.
`initiationCode` needs no change here: widening the enum rather than dropping it keeps
`.getInitiationCode().name()` compiling.

**Group B is blocked at compile time, not by choice.** The converter's target types are generated
from `pcfdlrm-domain-value-schema` / `pcfdlrm-command-api`, pinned at `pcfdlrm.version` **17.104.21**
in the parent POM. The builder methods for those 20 fields do not exist, so the mapping cannot be
written until PCFDLRM releases them and this repo bumps the version. Sequencing consequence: the
schema half of Group B ships in this story, the converter half is a follow-up gated on the PCFDLRM
pipeline.

**Group C is deliberately not mapped.** Declared, accepted, dropped at the converter.

### Change set 4 — Reconciliation visibility

Without this, a validation-rejected case is **absent from the reconciliation report entirely** —
not flagged as unknown, missing. `stagingdlrm-report.sql`'s `batch_streams` CTE selects a stream
only if it carries `migrated-case-submission-received` or
`error-migrated-case-submission-received`, and derives the `azureLocation` it batch-filters on from
those two payloads. Change set 2 raises neither, so the stream is never selected, and the case
disappears from the stagingDLRM CSV and from the summary join built on it.

Four edits, all in `tools/reconciliation/`:

1. Admit the rejected event as a third entry event in `batch_streams`, with its own `case_urn` /
   `azure_location` extraction.
2. Add a distinct status arm — otherwise a rejection falls through to `PROCESSED_FAILED` and reads
   as a downstream failure rather than a stagingDLRM refusal.
3. Add that status to `summary-report.py`'s `STUCK_AT_STAGINGDLRM_STATUSES`, a closed set; anything
   missing from it degrades to `overall_status=UNKNOWN`.
4. Use one shared constant for the rejection description — the SQL matches descriptions by exact
   equality where the event processor matches by bidirectional substring.

**This is what settles the rejection event's payload shape** (change set 2): the report needs
`azureLocation`, `caseUrn` and the hearing/defendant/material counts, none of which the dormant
`{caseDetails, createdBy}` shape carries. Hence the whole `MigratedCaseSubmission`.

`tools/reconciliation/` is outside the Maven build, so CI does not cover it — verification is a
manual run against a real batch, and any new tests use the stdlib `unittest` already there.

### Testing

Extend the DD-43078 suites and DSL — do **not** create parallel LIBRA tests. That work landed
whole-payload fixture comparison at the event-processor seam
(`stagingdlrm-test-support`: `FixtureLoader`, `WholePayloadMatcher`) with fixtures under
`json/event-processor/xhibit/` (minimal, maximal, empty-materials, no-contact-details). LIBRA adds
a sibling `libra/` fixture set. The XHIBIT fixtures are the regression guard for change set 2: they
must stay byte-identical through the relaxation.

## Deliverables

1. Relaxed + extended canonical schema (Groups A–C, 10 relaxations).
2. Source-system rule engine with both rule sets wired — 10 XHIBIT rules, 9 LIBRA rules.
3. Extended `MigratedCaseSubmissionRejected` event and the aggregate rejection branch.
4. Converter propagation for Group A's 12 fields.
5. Reconciliation report changes so rejections are visible (change set 4).
6. LIBRA fixture set extending the DD-43078 suites; XHIBIT fixtures unchanged.
7. **Proposed LIBRA-workbook corrections** — the register below, in a form that can go to the
   workbook owner.

## Register of what this story does *not* implement

Requested explicitly by the tech lead, to be taken to the Technical Architect. Everything here is
a deliberate exclusion with a reason, not an oversight.

### R1 — 6 LIBRA fields dropped from the schema (Group D)

All six exist in PCFDLRM but die at the PCFDLRM → Progression hop: no schema reachable from
`courtReferral.json` has them. Their canonical containers are open, so dropping them is a genuine
no-op.

| Field | Container | PCFDLRM | Progression |
|---|---|---|---|
| `backDuty` | `offences[*]` | same name | none |
| `backDutyDateFrom` | `offences[*]` | same name | none |
| `backDutyDateTo` | `offences[*]` | same name | none |
| `prosecutorOfferAOCP` | `offences[*]` | same name | none |
| `prosecutorCompensation` | `offences[*]` | different name | none |
| `middleName2` | `…personalInformation` | different name | none |

**Question for the TA:** these are magistrates'/fixed-penalty concepts with no Crown Court
equivalent. Is PCFDLRM the intended consumer — in which case they should be added — or is
Progression the destination, in which case dropping them is correct and the workbook should say so?

### R2 — 6 fields declared but never propagated (Group C)

Accepted at the gate so LIBRA payloads are not rejected, then discarded. `informant`,
`writtenChargePostingDate`, `prosecutorCosts`, `dxAddress`, officer `forename3`,
`uniquePropertyReferenceNumber`. Nothing downstream models any of them (`writtenChargePostingDate`'s
nearest match is `sjpReferral.noticeDate`, a different field). **Question for the TA:** confirm the
data is genuinely not needed downstream before we build a schema that silently swallows it.

### R3 — plea / verdict / allocationDecision remain code-vs-UUID

The workbook models these as reference-data **codes**; the canonical schema models them as
already-resolved **UUIDs** (`plea.id`, `verdict.id`, `allocationDecision.motReasonId`), each
required within its object. Nothing in the pipeline performs code → UUID resolution. This is **not
LIBRA-specific** — the XHIBIT tab models them as codes too — so it is a pre-existing gap between
the workbook and the schema, for both source systems. Loosening the canonical type to accept a raw
code would push an unresolved value downstream, so it is excluded rather than bodged. **Needs a
follow-up ticket and a decision on where resolution belongs.**

### R4 — `officerInCase` converter mapping deferred

Schema in this story, mapping when PCFDLRM has the fields (see change set 3). Note the workbook's
own section heading hedges — *"Unsure if this is persisted in Libra and CP progression"* — so the
block needs a product decision regardless of what the schemas allow. 3 of its 20 fields have no
home anywhere and fall under R2.

### R5 — Workbook corrections for the owner

Written up as
[`libra-workbook-corrections.md`](../../analysis/libra-ingestion/libra-workbook-corrections.md),
which carries the sheet rows and the evidence. Summarised here:

| # | Row / field | Issue |
|---|---|---|
| 1 | `hearings[*].hearingType` | Format cell says `TBC`; canonical bounds `maxLength: 10`. Keeping canonical — confirm the LIBRA limit |
| 2 | `offences[*].arrestDate` | Format cell blank (row 133); canonical enforces the core date pattern. Keeping canonical — confirm |
| 3 | `…personalInformation.observedEthnicity` | Real type conflict: canonical `integer`, LIBRA `string`, Format cell blank. Keeping canonical — needs adjudication |
| 4 | `prosecutorOffenceId` | Dangling reference: LIBRA declares `offenceID` under Listed Offences but no `prosecutorOffenceId` on the offence itself, which canonical requires as the target of that reference. Either a missing workbook row, or LIBRA identifies offences by `offenceSequenceNo` |
| 5 | `organisationTelephoneNumber` (row 63) | Looks like a duplicate of `companyTelephoneNumber` (row 62 → `defendant.telephoneNumberBusiness`); Format cell blank. Confirm before adding either — currently in Group B |
| 6 | `initiationCode` for XHIBIT | The workbook's XHIBIT tab documents more than one initiation code, so `enum: ["O"]` already contradicted the workbook *before* LIBRA — and the CPP platform's own `uk.gov.justice.core.courts.InitiationCode` declares seven (`Q, R, S, C, J, Z, O`), confirming stagingDLRM was the outlier. Confirm which codes XHIBIT legitimately sends, so the XHIBIT allowed-values rule pins the right set rather than replicating an over-tight constraint |
| 7 | `initiationCode: "H"` in func-app fixtures | The func-app's `command-helper` test fixtures use `"H"`, which is in neither the current nor the widened enum. Confirm whether `H` is dead test data or a real code the platform enum is missing |
| 8 | **Rename `cjsOffenceCode` → `offenceCode` in the sheet** | The workbook's Offence section calls it `cjsOffenceCode`; canonical, PCFDLRM and the core model all call it `offenceCode`, and the field is otherwise identical on both sides (`string`, `maxLength: 8`, `exists_same_constraint`, no change needed). The mismatch is name-only and costs review time — searching the impact CSV for `cjsOffenceCode` returns nothing, because the CSV keys on the canonical name. **Request the rename so the sheet and the schema agree.** Twelve other fields diverge the same way (full list in impact §2); worth deciding whether to align those too, or to accept the translation table as the reconciliation |
| 9 | `offenceDateCode` Format cell vs its own value list | Row 130's description enumerates values 1–6, but the Format cell (`N1`) implies 0–9, and the tooling trusts the Format cell. Canonical's `minimum: 1, maximum: 6` matches the description and is **not** relaxed (design F5). Confirm 1–6 is the real range so the Format cell can be corrected or the constraint reconsidered |
| 10 | **Align the LIBRA sheet with XHIBIT on six fields** — *for the Technical Architect* | `personalInformation.address.address1`, `parentGuardianInformation.address.address1`, `…parentGuardianInformation.personalInformation.address.address1` and `.surname`, `hearings[*].weekCommencingDate.startDate`, `caseDetails.caseMarkers[*].markerTypeCode`. Canonical requires each **within its containing object**, and those containers are optional — so LIBRA may omit the object entirely, but a container it does send must be well-formed (FR2a). The schema is **not** relaxed for these. Five need no data change, because LIBRA omits the container; the exception is `markerTypeCode`, which the sheet marks optional/conditional while LIBRA does send `caseMarkers`. **Confirm LIBRA always supplies a marker type code when it sends a marker, and correct the sheet to match XHIBIT.** If it genuinely cannot, this one returns as a schema decision |
| 11 | **The payload nests; the sheet's flat Defendant section does not describe the contract** | `driverNumber`, `licenseCode`, `nationalInsuranceNumber`, `occupation`, `defendantOccupationCode` and `vehicleCode` are flat rows in the workbook, but PCFDLRM holds the first three on `individual`, the next two on `personalInformation` and the last on `vehicleRelatedOffence` — and canonical mirrors PCFDLRM's nesting at every level today, exactly as the XHIBIT flow does. The LIBRA `case.json` must nest them: `migrated-defendant.json` is `additionalProperties: false`, so flat fields are a terminal 4xx, and a flat `vehicleCode` is silently dropped because `offence` is open. **Tell the extract team and the DD-43086 owner before the extract is written** |

### R6 — Out of this repo

Function App LIBRA gate and schema-selection strategy (DD-43086) · all PCFDLRM work, including the
`officerInCase` block, the three tier-4 fields, and the source-system axis on
`CcProsecutionValidationRuleProvider` · reconciliation tooling `--source-system` parameter ·
Progression regression validation.

## Known blockers

- **No real LIBRA `case.json` / `manifest.json` sample exists** (analysis §5 Q1). The single
  biggest unknown. Constraint values and LIBRA rule content come from workbook V0.13 and are
  unvalidated against real data.
- **LIBRA's `initiationCode` value(s) are undecided** (§5 Q2). Must be agreed with the PCFDLRM /
  reference-data team, because the value determines which PCFDLRM rule set LIBRA routes into
  (already-built SUMMONS / REQUISITION sets vs. the generic default). The schema relaxation here
  does not depend on the answer, but the end-to-end behaviour does.
- **PCFDLRM version bump** gates the Group B converter work (change set 3).

## Supporting analysis

Both regenerable from workbook V0.13 via `tools/schema-gen/regenerate.sh`:

- [`libra-schema-impact.md`](../../analysis/libra-ingestion/libra-schema-impact.md) — §1 the
  relaxation scope at both gates, §2 the matrix and its `change_type` vocabulary, §3 the work per
  schema, §4 why each relaxation needs a source-system guard, §5 the downstream tier triage, §6 the
  XHIBIT-only fields, §8 the func-app/canonical drift and core-type divergences.
- [`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) —
  pipeline trace, per-system change plan, open questions, and the rejected alternative (§7).
- [`libra-schema-impact.csv`](../../analysis/libra-ingestion/libra-schema-impact.csv) — 165 rows,
  one per payload field. `change_required=yes` gives the 67-row work list; `tier` ≠ `n/a-…` gives
  the 44 added fields.
