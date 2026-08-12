# LIBRA schema impact — func-app, stagingDLRM canonical, pcfdlrm, Progression

What has to change in each schema a migrated case passes through, for the source system to be
LIBRA as well as XHIBIT — derived mechanically from the data-schema workbook and from the live
schemas in the repos, rather than by reading them by eye.

**Companion to** [`libra-ingestion-analysis.md`](./libra-ingestion-analysis.md) — §3.3 of that
document proposes the stagingDLRM schema relaxation; §1 of this one shows that proposal is
incomplete, and §3 lists what else has to change.

## Provenance

| | |
|---|---|
| LIBRA | `schema/libra/dlrm-libra-0.13.json` — generated from the `Libra Case - Min Data` tab of `DLRM - CP Migration Data Schema V0.13.xlsx`, **named and typed by the live contracts** (below) |
| LIBRA provenance | `schema/libra/dlrm-libra-0.13.provenance.json` — what the workbook itself says about each field, keyed by JSONPath. The schema is written to be shared outside the team and carries none of it; this is the internal half, and it is what the matrix joins on |
| Live XHIBIT contract | `schema/xhibit/dlrm-xhibit-0.12.json` — the production contract, and the shape, conventions and property naming the generated LIBRA schema follows |
| Func-app gate | `schema/canonical/staging-dlrm-funcapp-flattened.json` — flattened from `stagingdlrm-azure-functions/src/main/resources` (8 files, live) |
| stagingDLRM canonical | `schema/canonical/staging-dlrm-canonical-flattened.json` — flattened from `stagingdlrm-domain-value-schema/.../json/schema` (30 files, live) |
| pcfdlrm | `cpp-context-prosecution-casefile-dlrm` → `pcfdlrm-domain-value-schema/.../json/schema` |
| Progression | `cpp.platform.core.domain` → `criminal-court-public-model`, rooted at **`courtReferral.json`** — the payload pcfdlrm actually sends (command `progression.initiate-court-proceedings`, type `uk.gov.justice.core.courts.InitiateCourtProceedings` = `{id, CourtReferral}`) |
| Coverage | all 150 sheet rows accounted for: 131 field rows, of which 130 emitted and 1 structural, **0 unmapped, 0 conflicts** |
| Produced | workbook V0.13 **as revised 12 Aug 2026** (see below); regenerate with `./tools/schema-gen/regenerate.sh` |

**The workbook is not the naming or typing authority.** Where a live contract already models a field,
the generated LIBRA schema carries the **contract's** property name and the **contract's** definition,
not the workbook's label or the type inferred from its Format cell. Only `required` comes from the
sheet. This is what lets the matrix be read as a contract comparison at all: both sides speak the
contract's vocabulary. Every point where the sheet said something different is recorded in the
provenance sidecar and reported by the generator, classified as a conflict, an unstated Format cell,
or the contract merely being looser than LIBRA's own data dictionary.

**XHIBIT is not generated from the workbook.** It is already in production at schema v0.12, so its
contract is *read* from the live schema sets above rather than re-derived — the func-app's own
resources are the XHIBIT gate today and stay exactly as they are. Every field the canonical schema
already carries therefore provably reaches pcfdlrm and Progression, which is why the matrix marks
those rows `assumed_flowing` instead of re-verifying them — `assumed_`, not `already_`, because it is an assumption (see the footnote in §8).

**Totals: 160 fields, 56 needing a change.**

### The workbook was revised after the first pass, and it changed the answers

The V0.13 file keeps its version label but is not the revision this analysis was first run against.
It resolves five of the asks in
[`libra-workbook-corrections.md`](./libra-workbook-corrections.md), **deletes six fields**, and adds
one new conflict. Everything below is against the revised sheet; row numbers have shifted by two or
three throughout.

| Change in the sheet | Effect here |
|---|---|
| `cjsOffenceCode` renamed `offenceCode`; `prosecutorOffenceID` added as a row | corrections asks 5 and 9 resolved — the dangling `listedOffences` reference now has a target |
| `hearingType` `TBC` → `A10`; `arrestDate` blank → `D10`; defendant `observedEthnicity` blank → `N1` | corrections asks 1–3 resolved; all three now agree with the contract on type |
| `caseMarker` now mandatory in all four case-type columns | corrections ask 10's live question resolved |
| `courtRoomId` and `durationMinutes` added as rows | `durationMinutes` was a `relax-required` blocker and no longer is. **`courtRoomId` is a new conflict: the sheet says `A36`, a 36-character string, where both contracts say `integer`** |
| `summonsCode`, `writtenChargePostingDate`, `backDuty`, `backDutyDateFrom`, `backDutyDateTo`, `organisationTelephoneNumber` deleted | six fields dropped from the analysis. `summonsCode` was previously recorded as reaching Progression; the `backDuty*` group as pcfdlrm-only. Corrections ask 6 (the `organisationTelephoneNumber` duplicate) is resolved by deletion — **confirm that was intended, along with `companyTelephoneNumber`, which went with it and leaves `defendant.telephoneNumberBusiness` unsupplied** |
| defendant and officer `primaryEmail`/`secondaryEmail` renamed `emailAddress1`/`emailAddress2` | the defendant's now map to the contract's own `defendant.emailAddress1/2` rather than through `personalInformation.contactDetails` |
| `verdictCode` → `verdictType`, `allocationDecisionRecordedDate` → `allocationDecisionDate` | name-only; §7 unaffected in substance |
| `initiationCode` comment now lists five codes, adding `X = Remitted` | the `relax-enum` row is wider than before: C, J, O, Q, X against `enum: ["O"]` |

`build-schema-impact.py` warns rather than fails on the six deletions — its curated downstream claims
for them are kept, so restoring any row to the sheet needs no tooling change.

---

## 1. Headline — a LIBRA payload fails at two gates, for different reasons

The relaxation §3.3 of the analysis doc proposes (drop the `initiationCode` enum, turn
`receivingCourt`'s `required` into "at least one of `sendingCourt`/`receivingCourt`") is not
enough, and the two gates fail on different things.

### The func-app gate: four missing mandatory fields, and nothing else

The Function App's schema is a **presence check only**. It declares `caseDetails` with 8
properties, all 8 `required`, and carries **not one constraint** — no patterns, no lengths, no
enums (§8). It also leaves `caseDetails` and `migratedCase` **open**
(`additionalProperties: true`), so unknown LIBRA fields sail straight through, and it never
descends into `defendants`, `hearings` or `offences` at all.

So exactly four things would block LIBRA there — all absent from the sheet, and all of them
entries that must simply not be carried across when the new LIBRA schema is authored alongside the
XHIBIT one (§3):

| Func-app `required` | LIBRA supplies it? |
|---|---|
| `dateReceived` | **no** |
| `receiptType` | **no** |
| `receivingCourt` | **no** |
| `retrialIndicator` | **no** |
| `initiationCode` | yes |
| `originatingOrganisation` | yes |
| `prosecutorCaseReference` | yes |
| `prosecutor.prosecutingAuthority` | yes (and optional there) |

### The canonical schema: four `required` entries plus an `anyOf`, plus a closed object

`caseDetails` in the canonical schema contains **none** of these seven XHIBIT fields:

| Absent from LIBRA | Status in the canonical schema |
|--------------------|--------------------------------|
| `dateReceived` | unconditionally `required` |
| `receiptType` | unconditionally `required` |
| `receivingCourt` | unconditionally `required` |
| `retrialIndicator` | unconditionally `required` |
| `dateOfSending` | one half of the `anyOf` (`dateOfCommittal` or `dateOfSending`) |
| `dateOfCommittal` | the other half of that `anyOf` |
| `sendingCourt` | optional — but this is the field documented as *the LIBRA court* |

That is four unconditional `required` entries **plus the `anyOf`**, so roughly six constraints to
relax, not two. The `anyOf` lives in neither `properties` nor the top-level `required`, which is
why an earlier hand-diff missed it; `build-schema-impact.py` now detects pure `{"required": […]}`
combinator branches explicitly and reports both halves as `relax-combinator`.

**This also answers §5 Q3** ("does LIBRA always populate both `sendingCourt`/`receivingCourt`, or
can it lack one?"): the sheet has **no court field on the case at all**. `courtHearingLocation` on
the hearing is LIBRA's only court reference. Relaxing to "at least one of the two" therefore still
would not admit a LIBRA case — the requirement has to be dropped, or satisfied by deriving a court
from the hearing.

On top of that, canonical `caseDetails` is **closed**, so the one case-level field LIBRA adds —
`informant` — is a terminal, non-retryable 4xx until it is declared; the func-app would have waved it
through. (`writtenChargePostingDate` and `summonsCode` were the other two until the workbook revision
deleted them.)

---

## 2. The impact matrix

One row per payload field, joined on **JSONPath** rather than definition name, so
structurally-equivalent fields line up even where the schemas organise their `definitions`
differently. Rooted at `$.migratedCase` — the submission-envelope fields the Function App
assembles from `manifest.json` and the blob path (`submissionId`, `metadata`, `materials`,
`channel`, `azureLocation`) are out of scope.

[**`libra-schema-impact.csv`**](./libra-schema-impact.csv) — 160 rows × 16 columns, UTF-8 with BOM
so Excel opens it directly. Sorted so the rows needing work come first. The full matrix is not
reproduced here: the CSV is the artefact, this document is the reasoning around it.

| Column | Meaning |
|---|---|
| `libra_field`, `jsonpath`, `container` | the **contract's property name** for the sheet's field, and where it sits in the payload — *not* the sheet's own label where the two differ (see below) |
| `funcapp_xhibit_status` | what the **existing hardwired XHIBIT gate schema** enforces here today, and it is not changing: `required` · `optional` · `not_validated` · `rejected_as_additional` |
| `funcapp_libra_action` | what the **new source-system-selected LIBRA gate schema** should do: `require` · `declare` · `omit` · `not-validated-at-gate` |
| `canonical_field`, `canonical_status` | the canonical name, and how LIBRA compares to it |
| `pcfdlrm_field`, `pcfdlrm_status` | `already_flowing` · `exists_same_name` · `exists_different_name` · `no_field` · `unverified` |
| `progression_field`, `progression_status` | `assumed_flowing` (the 121 existing fields) · `exists_mandatory` · `exists_optional` · `no_field` — derived purely from the payload schema, never from converter code |
| `change_required` | `yes`/`no` — **filter on this for the work list** |
| `change_type` | the classification below |
| `change_detail` | the concrete action, per schema, prefixed `Canonical:` / `Func-app:` / `PCFDLRM:` / `Guard:` |
| `tier` | downstream tier, for the 39 added fields only; `n/a-already-in-canonical` on the other 121 |
| `notes` | sheet row, constraint quotes, blockers — the only column that is legitimately blank. Sheet rows now come from the provenance sidecar rather than from the schema, so every LIBRA-supplied row carries one **except the nine noted at the end of this section**, which no sheet row supplies |

### Searching the CSV by the sheet's own field name will sometimes fail

`libra_field` reports the **contract's** property name, because that is what the comparison is
against and what a payload has to use. For **39 of the 130 mapped fields** the sheet's own label
differs, so a text search for the workbook's label finds nothing here.

**The lookup is [`schema/libra/dlrm-libra-0.13.provenance.json`](./schema/libra/dlrm-libra-0.13.provenance.json)**,
which records `sheetField`, `sheetRow` and `sheetSection` for every field against the same JSONPath
the CSV is keyed on — so a grep for a workbook label there gives you the path, and the path gives you
the CSV row. The mapping itself is `LIBRA_FIELDS` and `CONTRACT_ALIASES` in
`tools/schema-gen/generate-dlrm-schema.py`; every alias target is checked against the live XHIBIT
schema on each run, so a mapping cannot point at a name no contract has.

Twenty-one of the 39 are the `Parent Guardian - <field>` rows, whose labels carry a section prefix the
contract expresses as nesting instead. The other eighteen:

| Sheet section / field | `libra_field` in the CSV |
|---|---|
| Case → `Informant` | `informant` |
| Case Marker → `caseMarker` | `markerTypeCode` |
| Officer in Case → `emailAddress1`, `emailAddress2` | `primaryEmail`, `secondaryEmail` |
| Officer in Case → `DXAddress` | `dxAddress` |
| Listed Offences → `offenceID` | `listedOffences` |
| Defendant → `forename2`, `forename3` | `middleName`, `middleName2` |
| Defendant → `workTelephoneNumber`, `homeTelephoneNumber`, `mobileTelephoneNumber` | `work`, `home`, `mobile` |
| Defendant → `selfDefinedEthnicity` | `ethnicity` |
| Individual Alias Array → `Alias - Forename`, `– Forename2`, `– Forename3`, `- Surname` | `firstName`, `givenName2`, `givenName3`, `lastName` |
| Offence → `offenceSequenceNo` | `offenceSequenceNumber` |
| Offence → `pleaCode`, `verdictType`, `allocationDecision` | `id`, `id`, `motReasonId` — see §7 |
| Offence → `ConvictionDate` | `convictionDate` |

`Listed Offences → offenceID` is also the one that collapses rather than renaming: canonical models
`listedDefendant.listedOffences` as an `array of string(36)`, so the offence IDs **are** the array
items. There is no `listedOffences[*].offenceID` JSONPath, and the CSV is keyed by JSONPath, so the
whole of sheet row 47 is a single row reading `exists_same_constraint` / `change_type: none`.

That row is correct about the array and says nothing about the relationship it carries — see §6,
where `listedOffences` is the reference whose target, `offence.prosecutorOffenceId`, the sheet finally
declares in this revision.

**No cell is blank except `notes`.** A column that does not apply to a row says `n/a` rather than
being left empty, because a blank in a spreadsheet reads as "not filled in" as readily as "does not
apply" and Excel's filter shows it as `(Blanks)` either way. The four field-name columns use the bare
marker — the status column beside each one already gives the reason:

| Blank cell | Reads | Because |
|---|---|---|
| `libra_field` = `n/a` (21) | `canonical_status: not_in_libra` | LIBRA does not supply the field |
| `canonical_field` = `n/a` (39) | `canonical_status: added_not_in_canonical` | the canonical schema has no such field yet |
| `pcfdlrm_field` = `n/a` | `already_flowing` (121) or `no_field` (9) | not re-verified because XHIBIT already ships it, or genuinely absent from pcfdlrm |
| `progression_field` = `n/a` | `assumed_flowing` (121) or `no_field` (9) | already in the canonical schema so taken as given, or no counterpart in the payload schema |
| `tier` = `n/a-already-in-canonical` (121) | — | not a new field, so there is no downstream-triage question. `tier` has no status column of its own, hence the self-describing filler |

Two filters give you the two workstreams, and they partition the 56 rows of work exactly:
`tier` ≠ `n/a-…` is the 39-field **addition** work; `change_required = yes` with `tier` = `n/a-…`
is the 17-row **relaxation and mapping** work.

### Nine rows are reachable in the LIBRA schema without any sheet row supplying them

The generated schema reuses the contract's shared `address`, `personalInformation` and
`contactDetails` definitions wherever the contract does, which is what makes it diffable against the
XHIBIT file — but it also means a field can be *reachable* at a path the workbook never mentions.
Six `personalInformation.address.*` leaves, `personalInformation.contactDetails.primaryEmail` and
`.secondaryEmail`, and `parentGuardianInformation.personalInformation.title` are permitted by the
schema and supplied by no sheet row.

All nine are `change_type: none`, so they add no work, and each carries the note *"no sheet row
supplies this — reachable only because the contract reuses this definition here"*. Do not read them
as evidence that LIBRA sends the field.

### `change_type`

| `change_type` | Count | Change needed? | Meaning |
|---|---:|---|---|
| `none` | 92 | no | identical on both sides, or LIBRA simply does not send an already-optional field |
| `add-field-blocked-downstream` | 22 | yes | canonical can take it, but pcfdlrm has no home yet — adding it there alone makes it write-only |
| `add-optional-field` | 15 | yes | new LIBRA field with a full downstream home; add to canonical as optional |
| `libra-rule-only` | 12 | no | the schema already permits LIBRA's values; the sheet's *tighter* rule belongs in the LIBRA validation rules |
| `relax-required` | 7 | yes | canonical demands a field LIBRA cannot supply |
| `map-rename` | 3 | yes | same concept, different name *and* type — a transformation, not a schema change |
| `review-constraint` | 3 | yes | differs in a way no mechanical rule can adjudicate (type conflict, or canonical bounds what the sheet leaves silent) |
| `declare-only` | 2 | yes | nothing downstream models it, but its canonical parent is closed, so the payload cannot carry it silently — declare it unmapped, or strip it |
| `relax-combinator` | 2 | yes | an `anyOf`/`oneOf` required-branch LIBRA cannot satisfy |
| `relax-enum` | 1 | yes | canonical's enum excludes LIBRA's codes |
| `relax-constraint` | 1 | yes | canonical is demonstrably the narrower bound |

**Direction is checked, not assumed.** An absent bound is an unbounded one, so `maxLength: 35` on
the LIBRA side against no `maxLength` in canonical means canonical is already the more permissive
of the two and needs no change. Eight of the twelve `libra-rule-only` rows are exactly this:
`migrationSourceSystemCaseIdentifier`, the four `individualAlias` name fields, and the three
parent-guardian name fields where the sheet says `A35` and the contract's shared
`personalInformation` says 255. Only where canonical is genuinely narrower is a relaxation real.

**A rename alone is not a transformation.** `map-rename` is reserved for the three fields where the
sheet's name *and* type both differ from the contract's — a reference-data code against a resolved
UUID (§7). The other 36 name divergences need nothing at runtime beyond using the contract's name,
so they carry the `change_type` their constraints earn.

---

## 3. What has to change, by schema

### Func-app (`stagingdlrm-azure-functions/src/main/resources`)

**Nothing in the existing XHIBIT schema changes.** The gate today has hardwired support for one
schema; LIBRA support means selecting the schema pair by source system and authoring a **new LIBRA
schema alongside** the XHIBIT one, which stays exactly as it is. Loosening the shared copy instead
would silently stop catching malformed XHIBIT submissions at the cheapest point in the pipeline.

The two columns pair up to say what the new file needs. `funcapp_xhibit_status` is the baseline —
what the XHIBIT file enforces, and therefore what a copy-paste of it would demand — and
`funcapp_libra_action` is what the LIBRA file should do, read off the generated schema:

| `funcapp_libra_action` | Count | Fields |
|---|---:|---|
| `omit` | 7 | `dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator` (all four `required` in the XHIBIT file — **these are the entries not to carry across**), plus `dateOfCommittal`, `dateOfSending`, `sendingCourt` |
| `require` | 5 | `initiationCode`, `originatingOrganisation`, `prosecutorCaseReference`, `prosecutor.prosecutingAuthority`, `caseMarkers[*].markerTypeCode` — mandatory for every LIBRA case type |
| `declare` | 2 | `informant`, `cpsOrganisation` |
| `not-validated-at-gate` | 146 | below the depth this gate validates |

`markerTypeCode` moved from `declare` to `require`, and `summonsCode`/`writtenChargePostingDate` left
the list entirely, because of the workbook revision.

`declare` is load-bearing, not cosmetic: the generated LIBRA `caseDetails` is **closed**
(`additionalProperties: false`) where the XHIBIT gate's is **open**, so a LIBRA field left
undeclared is rejected at the gate rather than passed through. Note also that the enum, pattern and
length work in the canonical section below does **not** apply here — the gate carries no
constraints at all (§8), only presence checks.

#### One decision to take before authoring it: how deep should the LIBRA gate validate?

| Gate | Leaves validated | Branches |
|---|---:|---|
| XHIBIT (today) | 8 | `caseDetails` only |
| LIBRA, if the generated schema is used as-is | 139 | `caseDetails` 7, `defendants` 102, `hearings` 8, `migrationSourceSystem` 2, `officerInCase` 20 |

That is a 17× stronger pre-validation for LIBRA than XHIBIT gets at the same point. Earlier, cheaper
rejection is a real benefit, but it cuts both ways: the two remaining blank Format cells (§4) become
**false rejections at the gate** — the earliest and least diagnosable failure in the chain — and the
two source systems end up with materially different pre-validation strength.

The `defendants` count is inflated by the shape change: reusing the contract's shared
`personalInformation`, `address` and `contactDetails` definitions makes the parent-guardian block's
leaves reachable by more paths than the sheet has rows for. It is the same set of fields, counted at
every path the contract permits them.

The matrix does not presume an answer. `funcapp_libra_action` defaults to the `case-details` depth
the XHIBIT gate uses today, which is the conservative reading; pass
`--funcapp-libra-depth full` to `build-schema-impact.py` for the other view. The decision belongs to
the Function App story (DD-43086).

### stagingDLRM canonical (`stagingdlrm-domain-value-schema`)

| Work | Fields |
|---|---|
| Drop 4 unconditional `required` (§1) | `caseDetails.dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator` |
| Drop or source-system-scope the `anyOf` | `caseDetails.dateOfCommittal` / `dateOfSending` |
| Relax `required` where the sheet says optional/CM | `selfDefinedInformation.gender` |
| Drop `required` on fields the sheet omits | `hearing.weekCommencingDate.startDate`, `parentGuardianInformation.address.address1` (the organisation branch's address — the sheet supplies a parent-guardian address only on the person branch) |
| Relax the enum | `caseDetails.initiationCode` — `enum: ["O"]`, LIBRA supplies C, J, O, Q, X |
| ~~Widen one bound~~ | ~~`offence.offenceDateCode` — canonical 1–6, LIBRA 0–9~~ — **withdrawn: tooling artefact, no change needed.** LIBRA's own description enumerates the same six values canonical enforces; the `0–9` is inferred from the Format cell `N1`. See the known-noise note in §9 |
| Decide, then act (§4) | `hearing.courtRoomId`, and the two parent-guardian fields whose Format cell is blank |
| Add 39 optional fields | §5 — 15 clean, 22 blocked at pcfdlrm, 2 with no home at all |

Four entries left this table with the workbook revision: `caseMarkers[*].markerTypeCode` (now
mandatory in the sheet, so canonical's `required` is correct as it stands),
`offence.prosecutorOffenceId` and `hearing.durationMinutes` (both now supplied), and
`personalInformation.address.address1` — the sheet's parent-guardian address rows now resolve against
the contract's shared `address`, whose `required: [address1]` LIBRA satisfies.

### pcfdlrm

22 of the 39 added fields need a pcfdlrm change *first*, or the field is write-only in stagingDLRM.
20 of those are the `officerInCase` block (§5).

### Progression

No change identified. 30 of the 39 added fields already have a home in the payload schema (25
optional, 5 mandatory); the 9 gaps are all at the pcfdlrm hop or have no home anywhere.

---

## 4. The one consequence that repeats: relaxing a shared schema needs a source-system guard

Every `relax-*` row carries the same rider, and it is the single most repeated consequence in the
matrix:

> Relaxing the shared schema drops the check for XHIBIT too — re-impose it in the XHIBIT
> validation rules keyed on `migrationSourceSystemName`.

`caseDetails.receivingCourt` is the clearest case. It is unconditionally `required` today, which
is a genuine XHIBIT rule: an XHIBIT case without a receiving court is malformed. Making it optional
so LIBRA can pass means an XHIBIT submission missing it now sails through the schema and fails later,
deeper, and less legibly. The constraint has not disappeared — it has moved from JSON Schema into
the source-system validation-rules strategy, and it has to actually be written there.

The same applies to `initiationCode`. Dropping `enum: ["O"]` removes the only thing enforcing that
an XHIBIT case is initiation type `O`. Note though that the XHIBIT tab of the workbook itself
documents more than one initiation code, so `enum: ["O"]` already contradicted the workbook for
XHIBIT before LIBRA was considered — this relaxation corrects an over-tight constraint rather than
conceding one. (Recorded from a previous generation run against the `Xhibit Case - Min Data` tab;
that tab is no longer generated, so treat it as a finding to confirm with the workbook owner rather
than a live-verified claim.)

Two rows are the mirror image and need **no** schema change: `hearing.dateOfHearing` and
`hearing.timeOfHearing` are mandatory in the sheet but optional in canonical. A payload satisfying
the stricter sheet rule still passes the looser schema, so these belong in the LIBRA validation
rules — `change_type` is `libra-rule-only`.

### Two mandatory marks the schema cannot carry at all

`personalInformation.forename` used to be a third `libra-rule-only` row. It is now invisible to the
matrix, and so is a second mark, for a structural reason worth understanding.

The generated schema reuses the contract's shared `personalInformation` definition for both the
defendant and the parent guardian, as the contract does. `required` on a shared definition can only be
the **intersection** of its users — anything else imposes one section's rules on the other. The sheet
disagrees between the two in exactly two places:

| Field | Sheet says | Result |
|---|---|---|
| `personalInformation.forename` | mandatory for a defendant, optional for a parent guardian | dropped to optional |
| `personalInformation.address` | mandatory for a parent guardian, and the defendant carries no address there at all | dropped to optional |

Both land the definition on precisely the contract's own `required: ["surname"]`, so the schema is
right — but **two mandatory marks from the workbook are now enforced nowhere**. They are recorded in
the provenance sidecar's `deviations` list and printed by the generator under *"REQUIRED INTERSECTED
AWAY"*, and they belong in the LIBRA validation rules alongside the per-case-type variance.

### Three that need a decision before anything is relaxed

| Field | Conflict |
|---|---|
| `hearing.courtRoomId` | real type conflict, and new in this revision: both contracts say `integer`, the sheet's Format cell says `A36` — a 36-character string, which reads like an identifier rather than a room number |
| `parentGuardianInformation.personalInformation.observedEthnicity` | type conflict: contract `integer`, sheet's Format cell blank. The defendant's equivalent was the same question until this revision set it to `N1`; **row 114 should match row 70** |
| `parentGuardianInformation.organisationName` | the contract bounds `maxLength: 255`; the sheet's Format cell is blank |

`hearingType` and `arrestDate` were here until the revision gave them `A10` and `D10`. The remaining
two blank Format cells are workbook questions, not schema decisions; `courtRoomId` is a genuine
disagreement about the field's type. The tool reports all three as `review-constraint` rather than
guessing a direction.

---

## 5. The 39 fields LIBRA adds, and where they land

39 fields are in the workbook and absent from the canonical schema. Where a downstream contract
already models one, adding it to canonical as an optional field is closing a pass-through gap
rather than inventing new modelling.

**30 of the 39 have a home in the payload schema. 9 have none.**

| tier | meaning | count |
|---|---|---:|
| `1-drop-in` | same field name already in pcfdlrm; safe to add to canonical as optional now | 10 |
| `2-renamed` | exists in pcfdlrm under a different name — add under pcfdlrm's name, or map in the converter | 5 |
| `3-officer-pcfdlrm-gap` | the `officerInCase` block. Live and reachable in the core case model; the missing link is pcfdlrm | 20 |
| `4-pcfdlrm-gap` | reachable in the core case model but **absent from pcfdlrm** — adding to canonical alone leaves it write-only | 2 |
| `5-no-downstream-home` | nothing reachable downstream models it. Do not map it onward — but see the caveat below, it cannot simply be ignored | 2 |

Tiers 3 and 4 are the same shape — the destination exists, the middle hop does not have the field —
and are separated only because tier 3 is one coherent block worth deciding as a unit.

The count moved from 44 to 39 by way of six departures and one arrival. Deleted from the sheet:
`summonsCode` and the three `backDuty*` fields (all tier 1), `organisationTelephoneNumber` (tier 4)
and `writtenChargePostingDate` (tier 5). Added: a second row for `middleName2`, which is one field
reachable at two paths now that parent-guardian shares the contract's `personalInformation`.

**Tier 5 is not free.** Both `informant` and `prosecutorCosts` sit inside canonical objects that are
**closed** (`caseDetails` and `defendant` are both `additionalProperties: false`), and LIBRA's payload
carries them. "Do not add yet" would therefore be a terminal 4xx on every LIBRA submission. Each needs
one of: declare it in canonical as an accepted-but-unmapped optional field, or strip it before the
command call. The matrix classes these as `declare-only` rather than `defer` for that reason —
`defer` is reserved for a field whose canonical parent is open, where ignoring it really is a no-op.

Where they sit: `officerInCase` 14 + its `address` 6, `offences[*]` 8, `defendants[*]` 7,
`caseDetails` 1, `personalInformation` 1 + its parent-guardian path 1, `selfDefinedInformation` 1.
`middleName2` accounts for those last two: one field, reachable at both paths because the two share
the contract's `personalInformation` definition, and so two rows in the CSV.

### What counts as "exists downstream"

A field existing *somewhere* in the core repo is **not** enough. Core also holds outcome,
enforcement, SJP and `DesignSchemas/` content that a migrated case never touches. A core claim
counts only if its schema file is **reachable by `$ref` from `courtReferral.json`** — 70 core files
are, and the closure stays inside `criminal-court-public-model` (no cross-module hop, checked with
all 474 core schemas indexed and resolvable by `id`). The tool computes that reachable set and **exits non-zero** if any curated claim
fails it, so "found in core" cannot quietly mean "found in an unrelated schema".

That distinction decides two judgements: `forename3`/`middleName2` (a third given name exists
only in `cpsPersonDefendantDetails.json`, not reachable) and `prosecutorCosts` (only in
`summons-document-content.json`). It decided a third, `writtenChargePostingDate`, until the workbook
revision deleted that row — its nearest match was `sjpReferral.noticeDate`, reachable but under a
different name, so `no_field` per the definition above.

`vehicleMake` used to be a fourth. It was ruled out because `offenceFacts.json` is not reachable
from `apiProsecutionCase.json` — true of the **external** API model, and the wrong model. Rooted at
`courtReferral.json` it reaches Progression via `offence.offenceFacts`, so it now has a home end to
end. That correction is why the root matters more than it looks: see §8.

### `officerInCase` is a pcfdlrm gap, not an unknown (tier 3)

The officer block is **not** speculative downstream. `prosecutionCase.json` has a
`policeOfficerInCase` property pointing at `apiPoliceOfficerInCase.json`, which declares
`personDetails` (→ `apiPerson.json`), `policeOfficerRank`, `policeWorkerReferenceNumber` and
`policeWorkerLocationCode` — **all four `required`**. Progression already accepts an officer in
case, and the workbook's officer fields line up with it almost exactly.

The break is the middle hop. pcfdlrm's `pcf-policeOfficerInCase.json` has only
`{personalInformation, policeOfficerRank}` and is **referenced by nothing**, so
`policeWorkerReferenceNumber`, `policeWorkerLocationCode` and `faxNumber` need adding to pcfdlrm
before they can reach a destination already waiting for them.

Only two officer fields lack any reachable home: `uniquePropertyReferenceNumber` (nothing in
pcfdlrm or core) and `dxAddress` (only `cps-core-domain/contact-details.json`, a different domain).

Note the sheet heading itself hedges — "Unsure if this is persisted in Libra and CP progression" —
so the block needs a product decision before it is modelled, regardless of what the schemas allow.
That hedge is in the sheet and in the provenance sidecar; it is deliberately **not** in the shared
schema, whose `officerInCase` definition is described simply as "Officer in Case".

### Four things to decide before adding anything

1. **Add at the downstream nesting level, not the workbook's.** The sheet's Defendant section is
   flat, so `driverNumber`, `licenseCode` and `nationalInsuranceNumber` read as defendant-level
   fields, and `occupation`/`defendantOccupationCode` likewise. pcfdlrm puts the first three on
   `individual` and the last two on `personalInformation`; core agrees (`apiPersonDefendant`,
   `apiPerson`). Following them keeps `MigratedCaseConvertor` a 1:1 copy. This is
   [ADR-003](../../pipeline/adrs/003-libra-payload-contract.md), and it is the one place the
   generated schema still follows the sheet's nesting rather than the contract's, because no contract
   models these fields at all yet.
2. **`nationalInsuranceNumber` is nearly free** — stagingDLRM's own `pcf-definitions.json` already
   defines the pattern and references it nowhere (§8). Live in pcfdlrm and core. Note core's `nino`
   is looser than both DLRM services' pattern (§8).
3. **Tiers 3 and 4 need a pcfdlrm change first**, or the field is write-only. That is 22 of the 39.
4. **Three fields die at the pcfdlrm→Progression hop even once added**, because no reachable core
   schema has them: `middleName2`/officer `forename3`, `prosecutorCompensation`, and
   `prosecutorOfferAOCP` (pcfdlrm only). Worth adding if pcfdlrm is the intended consumer; not if
   Progression is.

The fifth item here was `organisationTelephoneNumber`, flagged as a possible duplicate of
`companyTelephoneNumber`. The revised sheet deletes **both**, which resolves the duplication and also
leaves the contract's `defendant.telephoneNumberBusiness` unsupplied — confirm that is intended.

### Two data-loss notes worth carrying into design

- `statementOfFacts` — §4 of the analysis doc records that Progression narrows statement-of-facts to
  a single case-level field sourced from the first offence of the first defendant. LIBRA supplies it
  **per offence**, so multi-offence LIBRA cases lose data at that hop. Core confirms it:
  `prosecutionCase.statementOfFacts` is case-level.
- `vehicle*`, `prosecutorCosts`, `prosecutorCompensation` and `prosecutorOfferAOCP` are
  magistrates'/fixed-penalty concepts with no Crown Court equivalent — the mirror image of the §5
  Q8 question about pcfdlrm assuming Crown-Court-only concepts. The `backDuty*` group belonged here
  too, until the revision deleted it.

---

## 6. Fields the canonical schema carries that LIBRA never sends

21 rows read `not_in_libra` — down from 52, mostly because the parent-guardian block now joins
properly (§9) and because the revision added `courtRoomId`, `durationMinutes` and
`prosecutorOffenceId`. Most of the 21 are harmless (already optional); 6 are the `relax-required`
work in §3.

| Where | Absent from LIBRA | Note |
|---|---|---|
| `caseDetails` | `dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator` | all four `required` today — the §1 blockers |
| `caseDetails` | `dateOfCommittal`, `dateOfSending`, `sendingCourt` | the first two are the `anyOf`; `sendingCourt` is optional |
| `hearing` | `weekCommencingDate.startDate`, `weekCommencingDate.duration` | `startDate` is `required` **within** `weekCommencingDate`, and LIBRA omits the whole object |
| `offence` | `convictingCourtCode`, `count` | |
| `defendant` | `telephoneNumberBusiness` | **new** — the revision deleted the sheet's `companyTelephoneNumber` row that fed it |
| `individual` | `custodyTimeLimit` | |
| `individualAlias` | `title` | |
| `parentGuardianInformation` | `address.address1`–`address5`, `postcode` | the **organisation** branch's address. LIBRA supplies a parent-guardian address only on the person branch, through `personalInformation` |
| `defendant` | `id` | the defendant UUID minted in the command handler — §4 of the analysis doc notes it is minted, discarded, then regenerated |

### `prosecutorOffenceId` was a dangling reference, and no longer is

The sheet used to declare `offenceID` under **Listed Offences** — the hearing-side reference — with
**no `prosecutorOffenceId` on the offence itself**, so `listedOffences` entries pointed at an
identifier the offence never declared. Canonical both requires that field and uses it as the target of
exactly that reference.

The revised sheet adds `prosecutorOffenceID` as a row on the Offence section (`A36`, matching the
contract's `maxLength: 36`), which resolves it: the row now reads `exists_same_constraint` /
`change_type: none`, and canonical's `required` needs no relaxation. Corrections ask 9 can be closed.
`DD-43081` had already made `prosecutorOffenceId` optional with an XHIBIT-only rule re-imposing it;
that change is now belt-and-braces rather than load-bearing, and could be reverted if the sheet is
trusted.

---

## 7. Codes vs UUIDs — a transformation, not a schema change

The workbook models plea, verdict and allocation decision as **codes**, resolved against its own
`pleaType` / `verdictType` / `allocationDecision` tabs. The canonical schema models them as
already-resolved **UUIDs** (`plea.id`, `verdict.id`, `allocationDecision.motReasonId`), each
`required` within its object.

| Canonical | LIBRA sheet | Sheet Format |
|---|---|---|
| `plea.id` (uuid) | `pleaCode` | `A36` |
| `verdict.id` (uuid) | `verdictType` | `A36` |
| `allocationDecision.motReasonId` (uuid) | `allocationDecision` | `A36` |

This is **not LIBRA-specific** — the XHIBIT tab models them as codes too, so the gap is between the
workbook's modelling and the schema's, for both source systems. Something has to perform
code → UUID resolution and nothing in the pipeline does today. The matrix classes these as
`map-rename`: resolve them in the LIBRA transformation strategy rather than loosening the canonical
type to accept a code, which would push an unresolved value downstream.

**The generated schema emits the contract's name and the contract's `$ref uuid`, not the sheet's.**
These three are the only entries in `CONTRACT_ALIASES`, and each target is checked against the live
XHIBIT schema on every run. So the shared schema states the obligation — a LIBRA payload must carry a
resolved UUID here — while the sidecar records that the workbook offers a code, and the matrix keeps
comparing the two. They are also the only three rows whose name difference is a genuine runtime
transformation, which is why `map-rename` is exactly 3 and not 39.

Note the `A36` Format cells: a 36-character string is UUID-shaped, so it is possible the sheet already
intends a resolved identifier and the `…Code`/`…Type` naming is historical. Worth confirming — it
would close this section entirely.

---

## 8. What the two live schemas reveal

### The func-app gate and the canonical schema have drifted, measurably

Now that both are flattened the drift is exact, and it is larger than "a few fields":

| | Func-app `caseDetails` | Canonical `caseDetails` |
|---|---|---|
| properties | 8 | 13 (`caseMarkers`, `cpsOrganisation`, `dateOfCommittal`, `dateOfSending`, `sendingCourt` extra) |
| `required` | the same 8 | the same 8, **plus** the `anyOf` |
| `additionalProperties` | `true` — unknown fields pass | `false` — unknown fields are rejected |
| constraints | **none at all** | patterns, lengths, and `initiationCode: enum ["O"]` |

The consequence is concrete and already live for XHIBIT: `initiationCode: "C"` passes the func-app
and **fails** the canonical enum. The cheap gate accepts it, the expensive one rejects it as a
terminal non-retryable 4xx after the payload has been enqueued and dispatched. `prosecutor` is a
second instance — `prosecutingAuthority` is `required` in canonical and optional at the gate.
This is the drift the analysis doc's §3.2 predicted, quantified.

### Progression's payload root: two parallel core families, only one of which is on the path

`criminal-court-public-model` carries the same domain twice, and picking the wrong one silently
measures the wrong contract:

| | Location | `id` namespace | Files |
|---|---|---|---|
| external (read/query model) | `.../json/schema/external/global/api*.json` | `…/core/courts/**external**/apiX.json` | 85 |
| internal (command model) | `.../json/schema/global/*.json` | `…/core/courts/X.json` | 168 |

pcfdlrm sends the command `progression.initiate-court-proceedings` carrying
`uk.gov.justice.core.courts.InitiateCourtProceedings`, which is `{id, CourtReferral}` — so the
payload root is **`courtReferral.json`** in the *internal* family. `apiProsecutionCase.json` is
`$ref`d only by `apiHearing.json` and is not on this path at all.

The two closures are **disjoint**, and the internal model is a superset in 7 of the 10 objects this
analysis touches (`prosecutionCase` 34 properties vs `apiProsecutionCase` 15; `offence` 54 vs 38).
Field *names* happen to be identical across both families, so a lookup against the wrong one still
resolves — what it gets wrong is reachability. That is exactly how `vehicleMake` was misjudged (§5).

**Footnote on `assumed_flowing`.** The 121 existing canonical fields are taken as flowing because
XHIBIT is in production and they are already in the schema. That is an assumption, not a
verification, and it is not strictly true: three of them — `alcoholOrDrugLevelAmount`,
`alcoholOrDrugLevelMethod` and `middleName` — have no counterpart in pcfdlrm's value-schema at all.
Pre-existing and XHIBIT-only, so out of scope for the LIBRA work, but recorded here rather than
absorbed into a label. The LIBRA-driven risk on those 121 rows is the *relaxation*, which
`change_type` carries: `relax-*` means an XHIBIT validation rule is now required (FR5),
`libra-rule-only` means a LIBRA one is (FR6).

### `pcf-definitions.json` re-declares five types core already owns

| Type in stagingDLRM `pcf-definitions.json` | Also in core? | Verdict |
|---|---|---|
| `channel`, `email`, `positiveInteger`, `ukGovPostCode` | yes | exact duplicates |
| `phone` | yes | **DIVERGED** |
| `datePattern` | no (core's `date` is identical) | duplicate under a different name |
| `nationalInsuranceNumber` | no — core calls it `nino` | **DIVERGED** |
| `isoDate`, `submissionStatus` | no | stagingDLRM-only, and referenced by nothing |

The two divergences are worth a decision:

- **`phone`** — stagingDLRM allows a leading `+` and caps length at 35 (`^[\+]?[0-9()\-\.\s]+$`,
  `maxLength: 35`); core allows neither (`^[0-9()\-\.\s]+$`, no maximum). A number with a leading
  `+` passes stagingDLRM and **fails** core's pattern — an interop risk for international numbers
  in whichever direction the value travels.
- **NI number** — stagingDLRM and pcfdlrm are byte-identical to each other (strict: exactly six
  digits then A–D, no spaces); core's `nino` tolerates whitespace between characters and a trailing
  space in place of the final letter. So `AB 12 34 56 C` passes core and fails both DLRM services.
  The §5 recommendation to add `nationalInsuranceNumber` by `$ref` stands — the two DLRM services
  agree — but the value reaching Progression may be one core would have accepted in a form
  stagingDLRM rejects first.

`language.json` (an `ENGLISH`/`WELSH`/`E`/`W` enum) is also declared and unreferenced; note
`documentationLanguage` and `hearingLanguage` on the defendant are plain `string, maxLength 1`
rather than `$ref`s to it, on both sides.

### The command payload is bigger than `migratedCase`

The canonical entry point is `migrated-case-submission.json`: `migratedCase`, `materials`,
`metadata`, `submissionId`, `channel`, `azureLocation` — with `migratedCase`, `metadata`,
`submissionId` and `azureLocation` all `required`. None of those four come from the
`Libra Case - Min Data` tab; the Function App assembles them from the blob path and the manifest.
**The generated LIBRA schema therefore covers only the `migratedCase` subtree, not the whole
command payload** — it is not a drop-in replacement for `migrated-case-submission.json`.

---

## 9. Regenerating, and what the tooling does not cover

```bash
# refresh every committed artefact in this folder, in dependency order
./tools/schema-gen/regenerate.sh
./tools/schema-gen/regenerate.sh --dry-run          # show what would run, write nothing

# or individually — outputs default to the CURRENT directory, so an ad-hoc run
# never writes into the repo. Pass --out/--out-dir to write here instead.
python3 tools/schema-gen/flatten-canonical-schema.py --dry-run
python3 tools/schema-gen/generate-dlrm-schema.py --dry-run
# the generator's report is where the sheet-vs-contract findings live: names mapped to the
# contract, required entries intersected away, conflicts, unstated Format cells, and any drift
# between the canonical module and the live XHIBIT schema
python3 tools/schema-gen/generate-dlrm-schema.py --dry-run \
    --compare docs/analysis/libra-ingestion/schema/xhibit/dlrm-xhibit-0.12.json
python3 tools/schema-gen/build-schema-impact.py                       # markdown to stdout
python3 tools/schema-gen/build-schema-impact.py --changes-only         # just the work list
python3 tools/schema-gen/build-schema-impact.py --funcapp-libra-depth full   # deep-gate view (§3)
```

Regeneration is deterministic: a clean run reproduces the committed artefacts byte-for-byte.

### Two outputs, and why the split matters

`generate-dlrm-schema.py` writes **two** files:

| File | Audience | Contents |
|---|---|---|
| `schema/libra/dlrm-libra-0.13.json` | **shareable** — the LIBRA extract team, the func-app owner, anyone building against it | a plain draft-04 schema in the live contract's shape. No workbook references, no sheet names, no row numbers, no per-case-type prose, no internal notes. Field descriptions are the contract's own wording, or the sheet's Description column for the fields no contract models |
| `schema/libra/dlrm-libra-0.13.provenance.json` | **internal** | per field, keyed by JSONPath: `sheetRow`, `sheetSection`, `sheetField`, `format`, `mandatoriness` per case type, `required`, `businessRules`, `comment`, `referenceDataSource`, `documentedValues`, `inContract`, and `sheetConstraint` where the contract's definition displaced the sheet's. Plus a `deviations` list — every override and every intersected `required`, 22 entries today |

Keep that split. Anything internal added to the schema will be read by people outside the team as
part of the contract.

**`build-schema-impact.py` needs the sidecar** (`--provenance`, wired up by `regenerate.sh`). Without
it the matrix would compare the contract's definitions against themselves and report every row as
identical, because the schema no longer carries what the workbook asked for. It fails rather than
running if the sidecar is missing, and fails if the sidecar describes a field the schema does not have.

**What the comparison covers:** definitions present on each side, properties per definition,
`required` lists, pure `{"required": […]}` `anyOf`/`oneOf` branches, `additionalProperties` (as the
func-app `rejected_as_additional` status), and per-field constraints (`type`, `$ref`, `maxLength`,
`minLength`, `maximum`, `minimum`, `pattern`, `enum`, `minItems`).

**What it does not cover:** `allOf`, `oneOf`/`anyOf` branches that carry more than a `required`
list, `dependencies`, `default`, and descriptions. `parentGuardianInformation`'s `oneOf` is walked
for its properties but its branch structure is not diffed.

**External references are resolved, not stubbed.** `justice.gov.uk` core types come from
`cpp.platform.core.domain/common-core-domain`, and Progression's inbound contract from
`criminal-court-public-model`. Nothing here compares a core type by name only. If that repo is
absent, `flatten-canonical-schema.py` falls back to stubs and says so, and
`build-schema-impact.py` marks its core claims `unverified`.

**Curated vs computed.** The pcfdlrm/core field mapping for the 39 added fields is hand-curated in
`build-schema-impact.py`'s `MAPPING` — deciding that LIBRA's `licenseCode` is pcfdlrm's
`driverLicenceCode` is a judgement a name match cannot make. Every curated claim is re-verified
against both checkouts on each run, for existence *and* reachability; a stale claim exits non-zero
rather than printing a stale table. Everything else — statuses, change types, change details — is
computed.

`MAPPING` still holds entries for the six fields the workbook revision deleted, and the run **warns**
about them rather than failing, so restoring a row to the sheet needs no tooling change. `RENAMES` is
the mirror image: it no longer performs the code-to-UUID mapping (the generator does), it asserts that
the generated schema still records those three sheet names, and **fails** if one stops appearing.

**Known noise to discount:**

- `minimum: 0` is added to generated `N<n>` integers where the live schema omits it. This is a
  generator artefact; the tool strips it before comparing, so it never appears as a difference.
- **`maximum` on `N<n>` integers is the *same* artefact, and the tool does not strip it — so it can
  appear as a false `relax-constraint`.** `generate-dlrm-schema.py` derives *both* bounds from the
  Format cell's digit count in one expression
  (`{"minimum": 0, "maximum": int("9" * n)}`), but `libra_constraints()` filters only the
  `minimum`. An all-9s `maximum` therefore means "n digits", not "LIBRA sends up to that value",
  and comparing it against a semantic canonical bound is invalid.
  **One row is affected today: `offence.offenceDateCode`**, reported as
  `relax-constraint` "widen maximum to 9 (canonical 6)". Discount it — the sheet's own description
  enumerates `1 = on or in … 6 = on or before`, exactly canonical's range, so canonical
  needs no change. Any future `N<n>` integer whose canonical bound is semantic rather than
  digit-derived will report the same false positive until `libra_constraints()` also drops an
  all-9s `maximum`.
- **Nine rows are reachable without a sheet row supplying them** — see the end of §2. A consequence of
  reusing the contract's shared definitions, not a claim about LIBRA's data.
- The parent-guardian block used to be invisible to the comparison: it produced 27 rows all reading
  `not_in_libra`, because the generator gave parent-guardian its own definitions where the contract
  reuses `personalInformation`/`address`/`contactDetails`, so nothing joined. **That is fixed.** The
  generated schema now reuses the same definitions the contract does, and contract lookup is per
  *container*, resolving through the contract's own `oneOf` branches — so parent-guardian fields join
  on JSONPath like everything else. The residual is the six organisation-branch address rows in §6,
  which are genuinely unsupplied rather than unjoinable. The cost of the fix is the `required`
  intersection in §4 and the nine rows above.

**How `required` is derived from the sheet:** a field is required only when every case-type mark it
carries is `M`. A blank cell means "not stated for this case type" and is ignored; any `O`/`CM`/`N/A`
disqualifies. LIBRA's four case-type columns are SJP Referral / Summons / Charge / Postal Requisition.
For a definition the contract reuses in several places, `required` is intersected across the sections
sharing it as well (§4). Per-case-type variance, the originating sheet row and every intersection are
in the provenance sidecar — the shared schema carries only the resulting `required` list.


---

## 10. Appendix — canonical → Progression payload, where the names differ

Not used by the CSV (the 121 existing fields read `assumed_flowing`), kept because it is the
expensive half to reconstruct and it answers a different question: *does the XHIBIT flow actually
deliver every field to Progression?* Every non-blank row below was verified against the
`courtReferral.json` closure.

| stagingDLRM canonical | Progression payload |
|---|---|
| `caseDetails.dateOfCommittal` | `prosecutionCase.committalDate` |
| `caseDetails.dateOfSending` | `prosecutionCase.dateOfSendingCase` |
| `caseDetails.prosecutorCaseReference` | `prosecutionCaseIdentifier.prosecutionAuthorityReference` *(required)* |
| `caseDetails.prosecutor.prosecutingAuthority` | `prosecutionCaseIdentifier.prosecutionAuthorityOUCode` |
| `caseDetails.dateReceived` | **none** — `receiptType`/`trialReceiptType` only |
| `defendants[].asn` | `personDefendant.arrestSummonsNumber` |
| `defendants[].pncIdentifier` | `defendant.pncId` |
| `defendants[].prosecutorDefendantId` | `defendant.prosecutionAuthorityReference` — *confirm* |
| `defendants[].individual.custodyStatus` | `personDefendant.custody` *(required)* — *confirm, different shape* |
| `defendants[].aliasForCorporate` | `defendantAlias.legalEntityName` |
| `defendants[].organisationName` | `organisation.name` *(required)* |
| `defendants[].telephoneNumberBusiness`, `parentGuardian…companyTelephoneNumber` | `contactNumber.work` |
| `defendants[].emailAddress1` / `emailAddress2` | `contactNumber.primaryEmail` / `secondaryEmail` |
| `defendants[].documentationLanguage` | `person.documentationLanguageNeeds` |
| `defendants[].languageRequirement` | `person.hearingLanguageNeeds` |
| `…personalInformation.forename` / `surname` | `person.firstName` / `person.lastName` *(required)* |
| `…personalInformation.observedEthnicity` | `ethnicity.observedEthnicityCode` |
| `…parentGuardianInformation.selfDefinedEthnicity` | `ethnicity.selfDefinedEthnicityCode` |
| `…selfDefinedInformation.nationality` | `person.nationalityCode` |
| `individualAliases[].givenName2` | `defendantAlias.middleName` |
| `individualAliases[].givenName3` | **none** — one `middleName` only |
| `offences[].offenceWording` / `offenceWordingWelsh` | `offence.wording` *(required)* / `wordingWelsh` |
| `offences[].offenceCommittedDate` / `offenceCommittedEndDate` | `offence.startDate` *(required)* / `endDate` |
| `offences[].offenceSequenceNumber` | `offence.orderIndex` |
| `offences[].convictingCourtCode` | `courtCentre.code` |
| `offences[].alcoholRelatedOffence.alcoholOrDrugLevelAmount` | `offenceFacts.alcoholReadingAmount` |
| `offences[].alcoholRelatedOffence.alcoholOrDrugLevelMethod` | `offenceFacts.alcoholReadingMethodCode` |
| `offences[].offenceLocation` | **none** — no location on `offence` |
| `offences[].prosecutorOffenceId` | **none** — no prosecutor offence ref |
| `hearings[].dateOfHearing`, `timeOfHearing` | `listHearingRequest.listedStartDateTime` — *both collapse into one* |
| `hearings[].durationMinutes` | `listHearingRequest.estimateMinutes` |
| `hearings[].listedDefendants[].listedOffences` | `listDefendantRequest.defendantOffences` |
| `hearings[].listedDefendants[].prosecutorDefendantId` | `listDefendantRequest.defendantId` — *confirm, core is a UUID* |
