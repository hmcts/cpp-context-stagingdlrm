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
| LIBRA | `schema/libra/dlrm-libra-0.13.json` — generated from the `Libra Case - Min Data` tab of `DLRM - CP Migration Data Schema V0.13.xlsx` |
| Func-app gate | `schema/canonical/staging-dlrm-funcapp-flattened.json` — flattened from `stagingdlrm-azure-functions/src/main/resources` (8 files, live) |
| stagingDLRM canonical | `schema/canonical/staging-dlrm-canonical-flattened.json` — flattened from `stagingdlrm-domain-value-schema/.../json/schema` (30 files, live) |
| pcfdlrm | `cpp-context-prosecution-casefile-dlrm` → `pcfdlrm-domain-value-schema/.../json/schema` |
| Progression | `cpp.platform.core.domain` → `criminal-court-public-model`, rooted at **`courtReferral.json`** — the payload pcfdlrm actually sends (command `progression.initiate-court-proceedings`, type `uk.gov.justice.core.courts.InitiateCourtProceedings` = `{id, CourtReferral}`) |
| Coverage | all 155 sheet rows accounted for: 134 field rows emitted, 1 structural, **0 unmapped, 0 conflicts** |
| Produced | workbook V0.13; regenerate with `./tools/schema-gen/regenerate.sh` |

**XHIBIT is not generated from the workbook.** It is already in production at schema v0.12, so its
contract is *read* from the two live schema sets above rather than re-derived — the func-app's own
resources are the XHIBIT gate today and stay exactly as they are. Every field the canonical schema
already carries therefore provably reaches pcfdlrm and Progression, which is why the matrix marks
those rows `assumed_flowing` instead of re-verifying them — `assumed_`, not `already_`, because it is an assumption (see the footnote in §8).

**Totals: 165 fields, 67 needing a change.**

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

On top of that, canonical `caseDetails` is **closed**, so the three case-level fields LIBRA adds
(`writtenChargePostingDate`, `informant`, `summonsCode`) are a terminal, non-retryable 4xx until
they are declared — the func-app would have waved them through.

---

## 2. The impact matrix

One row per payload field, joined on **JSONPath** rather than definition name, so
structurally-equivalent fields line up even where the schemas organise their `definitions`
differently. Rooted at `$.migratedCase` — the submission-envelope fields the Function App
assembles from `manifest.json` and the blob path (`submissionId`, `metadata`, `materials`,
`channel`, `azureLocation`) are out of scope.

[**`libra-schema-impact.csv`**](./libra-schema-impact.csv) — 165 rows × 16 columns, UTF-8 with BOM
so Excel opens it directly. Sorted so the rows needing work come first. The full matrix is not
reproduced here: the CSV is the artefact, this document is the reasoning around it.

| Column | Meaning |
|---|---|
| `libra_field`, `jsonpath`, `container` | the **mapped canonical property name** for the sheet's field, and where it sits in the payload — *not* the sheet's own label where the two differ (see below) |
| `funcapp_xhibit_status` | what the **existing hardwired XHIBIT gate schema** enforces here today, and it is not changing: `required` · `optional` · `not_validated` · `rejected_as_additional` |
| `funcapp_libra_action` | what the **new source-system-selected LIBRA gate schema** should do: `require` · `declare` · `omit` · `not-validated-at-gate` |
| `canonical_field`, `canonical_status` | the canonical name, and how LIBRA compares to it |
| `pcfdlrm_field`, `pcfdlrm_status` | `already_flowing` · `exists_same_name` · `exists_different_name` · `no_field` · `unverified` |
| `progression_field`, `progression_status` | `assumed_flowing` (the 121 existing fields) · `exists_mandatory` · `exists_optional` · `no_field` — derived purely from the payload schema, never from converter code |
| `change_required` | `yes`/`no` — **filter on this for the work list** |
| `change_type` | the classification below |
| `change_detail` | the concrete action, per schema, prefixed `Canonical:` / `Func-app:` / `PCFDLRM:` / `Guard:` |
| `tier` | downstream tier, for the 44 added fields only; `n/a-already-in-canonical` on the other 121 |
| `notes` | sheet row, constraint quotes, blockers — the only column that is legitimately blank. Sheet rows are carried only for the 44 **added** fields; 57 of the 113 LIBRA-supplied rows have none. The generated schema in `schema/libra/` does record the sheet row in every field's `description` if you need the provenance for one of those |

### Searching the CSV by the sheet's own field name will sometimes fail

`libra_field` reports the canonical property the sheet's field maps to, because that is what the
comparison is against. For **13 of the 109 mapped fields** the two names differ, so a text search
for the workbook's label finds nothing. The curated mapping is `MAPPING` in
`tools/schema-gen/generate-dlrm-schema.py`; the divergences are:

| Sheet section / field | `libra_field` in the CSV |
|---|---|
| Case Marker → `caseMarker` | `markerTypeCode` |
| Listed Offences → `offenceID` | `listedOffences` |
| Defendant → `forename2`, `forename3` | `middleName`, `middleName2` |
| Defendant → `companyTelephoneNumber` | `telephoneNumberBusiness` |
| Defendant → `workTelephoneNumber`, `homeTelephoneNumber`, `mobileTelephoneNumber` | `work`, `home`, `mobile` |
| Defendant → `selfDefinedEthnicity` | `ethnicity` |
| Offence → `cjsOffenceCode` | `offenceCode` |
| Offence → `offenceSequenceNo` | `offenceSequenceNumber` |
| Offence → `allocationDecision`, `allocationDecisionRecordedDate` | `allocationDecisionCode`, `allocationDecisionDate` |

`Listed Offences → offenceID` is also the one that collapses rather than renaming: canonical models
`listedDefendant.listedOffences` as an `array of string(36)`, so the offence IDs **are** the array
items. There is no `listedOffences[*].offenceID` JSONPath, and the CSV is keyed by JSONPath, so the
whole of sheet row 48 is a single row reading `exists_same_constraint` / `change_type: none`.

That row is correct about the array and says nothing about the relationship it carries — see §6,
where `listedOffences` is the reference whose target, `offence.prosecutorOffenceId`, the sheet never
declares.

**No cell is blank except `notes`.** A column that does not apply to a row says `n/a` rather than
being left empty, because a blank in a spreadsheet reads as "not filled in" as readily as "does not
apply" and Excel's filter shows it as `(Blanks)` either way. The four field-name columns use the bare
marker — the status column beside each one already gives the reason:

| Blank cell | Reads | Because |
|---|---|---|
| `libra_field` = `n/a` (52) | `canonical_status: not_in_libra` | LIBRA does not supply the field |
| `canonical_field` = `n/a` (44) | `canonical_status: added_not_in_canonical` | the canonical schema has no such field yet |
| `pcfdlrm_field` = `n/a` | `already_flowing` (121) or `no_field` (11) | not re-verified because XHIBIT already ships it, or genuinely absent from pcfdlrm |
| `progression_field` = `n/a` | `assumed_flowing` (121) or `no_field` (12) | already in the canonical schema so taken as given, or no counterpart in the payload schema |
| `tier` = `n/a-already-in-canonical` (121) | — | not a new field, so there is no downstream-triage question. `tier` has no status column of its own, hence the self-describing filler |

Two filters give you the two workstreams, and they partition the 67 rows of work exactly:
`tier` ≠ `n/a-…` is the 44-field **addition** work; `change_required = yes` with `tier` = `n/a-…`
is the 23-row **relaxation and mapping** work.

### `change_type`

| `change_type` | Count | Change needed? | Meaning |
|---|---:|---|---|
| `none` | 90 | no | identical on both sides, or LIBRA simply does not send an already-optional field |
| `add-field-blocked-downstream` | 23 | yes | canonical can take it, but pcfdlrm has no home yet — adding it there alone makes it write-only |
| `add-optional-field` | 18 | yes | new LIBRA field with a full downstream home; add to canonical as optional |
| `relax-required` | 13 | yes | canonical demands a field LIBRA cannot supply |
| `libra-rule-only` | 8 | no | the schema already permits LIBRA's values; the sheet's *tighter* rule belongs in the LIBRA validation rules |
| `map-rename` | 3 | yes | same concept, different name/representation — a transformation, not a schema change |
| `review-constraint` | 3 | yes | differs in a way no mechanical rule can adjudicate (type conflict, or canonical bounds what the sheet leaves silent) |
| `declare-only` | 3 | yes | nothing downstream models it, but its canonical parent is closed, so the payload cannot carry it silently — declare it unmapped, or strip it |
| `relax-combinator` | 2 | yes | an `anyOf`/`oneOf` required-branch LIBRA cannot satisfy |
| `relax-enum` | 1 | yes | canonical's enum excludes LIBRA's codes |
| `relax-constraint` | 1 | yes | canonical is demonstrably the narrower bound |

**Direction is checked, not assumed.** An absent bound is an unbounded one, so `maxLength: 35` on
the LIBRA side against no `maxLength` in canonical means canonical is already the more permissive
of the two and needs no change. Five fields that a naive diff reports as differences —
`migrationSourceSystemCaseIdentifier` and the four `individualAlias` name fields — are
`libra-rule-only` for exactly this reason. Only where canonical is genuinely narrower is a
relaxation real.

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
| `require` | 4 | `initiationCode`, `originatingOrganisation`, `prosecutorCaseReference`, `prosecutor.prosecutingAuthority` — mandatory for every LIBRA case type |
| `declare` | 5 | `summonsCode`, `informant`, `writtenChargePostingDate`, `cpsOrganisation`, `caseMarkers[*].markerTypeCode` |
| `not-validated-at-gate` | 149 | below the depth this gate validates |

`declare` is load-bearing, not cosmetic: the generated LIBRA `caseDetails` is **closed**
(`additionalProperties: false`) where the XHIBIT gate's is **open**, so a LIBRA field left
undeclared is rejected at the gate rather than passed through. Note also that the enum, pattern and
length work in the canonical section below does **not** apply here — the gate carries no
constraints at all (§8), only presence checks.

#### One decision to take before authoring it: how deep should the LIBRA gate validate?

| Gate | Leaves validated | Branches |
|---|---:|---|
| XHIBIT (today) | 8 | `caseDetails` only |
| LIBRA, if the generated schema is used as-is | 113 | `caseDetails` 9, `defendants` 76, `hearings` 6, `migrationSourceSystem` 2, `officerInCase` 20 |

That is a 14× stronger pre-validation for LIBRA than XHIBIT gets at the same point. Earlier, cheaper
rejection is a real benefit, but it cuts both ways: the workbook's blank and `TBC` Format cells (the
three `review-constraint` rows in §4) become **false rejections at the gate** — the earliest and
least diagnosable failure in the chain — and the two source systems end up with materially different
pre-validation strength.

The matrix does not presume an answer. `funcapp_libra_action` defaults to the `case-details` depth
the XHIBIT gate uses today, which is the conservative reading; pass
`--funcapp-libra-depth full` to `build-schema-impact.py` for the other view. The decision belongs to
the Function App story (DD-43086).

### stagingDLRM canonical (`stagingdlrm-domain-value-schema`)

| Work | Fields |
|---|---|
| Drop 4 unconditional `required` (§1) | `caseDetails.dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator` |
| Drop or source-system-scope the `anyOf` | `caseDetails.dateOfCommittal` / `dateOfSending` |
| Relax `required` where the sheet says optional/CM | `caseMarkers[*].markerTypeCode`, `selfDefinedInformation.gender` |
| Drop `required` on fields the sheet omits | `offence.prosecutorOffenceId`, `hearing.durationMinutes`, `hearing.weekCommencingDate.startDate`, `personalInformation.address.address1`, and the three `parentGuardianInformation` equivalents |
| Relax the enum | `caseDetails.initiationCode` — `enum: ["O"]`, LIBRA supplies C/J/Q/S |
| ~~Widen one bound~~ | ~~`offence.offenceDateCode` — canonical 1–6, LIBRA 0–9~~ — **withdrawn: tooling artefact, no change needed.** LIBRA's own row-130 description enumerates the same six values canonical enforces; the `0–9` is inferred from the Format cell `N1`. See the known-noise note in §9 |
| Decide, then act (§4) | `hearing.hearingType`, `offence.arrestDate`, `personalInformation.observedEthnicity` |
| Add 41 optional fields | §5 — 18 clean, 23 blocked at pcfdlrm |

### pcfdlrm

23 of the 44 added fields need a pcfdlrm change *first*, or the field is write-only in stagingDLRM.
20 of those are the `officerInCase` block (§5).

### Progression

No change identified. 32 of the 44 added fields already have a home in the payload schema (27
optional, 5 mandatory); the gaps are all at the pcfdlrm hop or have no home anywhere.

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

Three rows are the mirror image and need **no** schema change: `hearing.dateOfHearing`,
`hearing.timeOfHearing` and `personalInformation.forename` are mandatory in the sheet but optional
in canonical. A payload satisfying the stricter sheet rule still passes the looser schema, so these
belong in the LIBRA validation rules — `change_type` is `libra-rule-only`.

### Three that need a decision before anything is relaxed

| Field | Conflict |
|---|---|
| `personalInformation.observedEthnicity` | real type conflict: canonical `integer`, LIBRA `string`. The sheet leaves the Format cell blank |
| `offence.arrestDate` | canonical is a `$ref` to the core date pattern; the sheet leaves Format blank (row 133) |
| `hearing.hearingType` | canonical bounds `maxLength: 10`; the sheet's Format cell says `TBC` |

All three come from a blank or `TBC` Format cell, so they are workbook questions, not schema
decisions. The tool reports them as `review-constraint` rather than guessing a direction.

---

## 5. The 41 fields LIBRA adds, and where they land

44 fields are in the workbook and absent from the canonical schema. Where a downstream contract
already models one, adding it to canonical as an optional field is closing a pass-through gap
rather than inventing new modelling.

**32 of the 44 have a home in the payload schema. 12 have none.**

| tier | meaning | count |
|---|---|---:|
| `1-drop-in` | same field name already in pcfdlrm; safe to add to canonical as optional now | 14 |
| `2-renamed` | exists in pcfdlrm under a different name — add under pcfdlrm's name, or map in the converter | 4 |
| `3-officer-pcfdlrm-gap` | the `officerInCase` block. Live and reachable in the core case model; the missing link is pcfdlrm | 20 |
| `4-pcfdlrm-gap` | reachable in the core case model but **absent from pcfdlrm** — adding to canonical alone leaves it write-only | 3 |
| `5-no-downstream-home` | nothing reachable downstream models it. Do not map it onward — but see the caveat below, it cannot simply be ignored | 3 |

Tiers 3 and 4 are the same shape — the destination exists, the middle hop does not have the field —
and are separated only because tier 3 is one coherent block worth deciding as a unit.

**Tier 5 is not free.** All three of `informant`, `writtenChargePostingDate` and `prosecutorCosts`
sit inside canonical objects that are **closed** (`caseDetails` and `defendant` are both
`additionalProperties: false`), and LIBRA's payload carries them. "Do not add yet" would therefore
be a terminal 4xx on every LIBRA submission. Each needs one of: declare it in canonical as an
accepted-but-unmapped optional field, or strip it before the command call. The matrix classes these
as `declare-only` rather than `defer` for that reason — `defer` is reserved for a field whose
canonical parent is open, where ignoring it really is a no-op.

Where they sit: `officerInCase` 14 + its `address` 6, `offences[*]` 11, `defendants[*]` 8,
`caseDetails` 3, `personalInformation` 1, `selfDefinedInformation` 1.

### What counts as "exists downstream"

A field existing *somewhere* in the core repo is **not** enough. Core also holds outcome,
enforcement, SJP and `DesignSchemas/` content that a migrated case never touches. A core claim
counts only if its schema file is **reachable by `$ref` from `courtReferral.json`** — 70 core files
are, and the closure stays inside `criminal-court-public-model` (no cross-module hop, checked with
all 474 core schemas indexed and resolvable by `id`). The tool computes that reachable set and **exits non-zero** if any curated claim
fails it, so "found in core" cannot quietly mean "found in an unrelated schema".

That distinction decides three judgements: `forename3`/`middleName2` (a third given name exists
only in `cpsPersonDefendantDetails.json`, not reachable), `prosecutorCosts` (only in
`summons-document-content.json`) and `writtenChargePostingDate` (nearest match is
`sjpReferral.noticeDate` — reachable, but a different name, so `no_field` per the definition above).

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

### Five things to decide before adding anything

1. **Add at the downstream nesting level, not the workbook's.** The sheet's Defendant section is
   flat, so `driverNumber`, `licenseCode` and `nationalInsuranceNumber` read as defendant-level
   fields, and `occupation`/`defendantOccupationCode` likewise. pcfdlrm puts the first three on
   `individual` and the last two on `personalInformation`; core agrees (`apiPersonDefendant`,
   `apiPerson`). Following them keeps `MigratedCaseConvertor` a 1:1 copy.
2. **`nationalInsuranceNumber` is nearly free** — stagingDLRM's own `pcf-definitions.json` already
   defines the pattern and references it nowhere (§8). Live in pcfdlrm and core. Note core's `nino`
   is looser than both DLRM services' pattern (§8).
3. **Tiers 3 and 4 need a pcfdlrm change first**, or the field is write-only. That is 23 of the 44.
4. **Three fields die at the pcfdlrm→Progression hop even once added**, because no reachable core
   schema has them: `middleName2`/officer `forename3`, `vehicleMake`, and
   `backDutyDateFrom`/`backDutyDateTo`/`prosecutorOfferAOCP` (pcfdlrm only). Worth adding if
   pcfdlrm is the intended consumer; not if Progression is.
5. **`organisationTelephoneNumber` may be a workbook duplicate.** The sheet already has
   `companyTelephoneNumber` (row 62 → `defendant.telephoneNumberBusiness`); row 63 adds
   `organisationTelephoneNumber` with a blank Format cell. Confirm with the workbook owner before
   adding either.

### Two data-loss notes worth carrying into design

- `statementOfFacts` — §4 of the analysis doc records that Progression narrows statement-of-facts to
  a single case-level field sourced from the first offence of the first defendant. LIBRA supplies it
  **per offence**, so multi-offence LIBRA cases lose data at that hop. Core confirms it:
  `prosecutionCase.statementOfFacts` is case-level.
- `backDuty*`, `vehicle*`, `prosecutorCosts`, `prosecutorCompensation`, `prosecutorOfferAOCP` are
  magistrates'/fixed-penalty concepts with no Crown Court equivalent — the mirror image of the §5
  Q8 question about pcfdlrm assuming Crown-Court-only concepts.

---

## 6. Fields the canonical schema carries that LIBRA never sends

52 rows read `not_in_libra`. Most are harmless (already optional), and 13 are the `relax-required`
work in §3. Two are worth calling out beyond that:

| Where | Absent from LIBRA | Note |
|---|---|---|
| `hearing` | `courtRoomId`, `durationMinutes`, `weekCommencingDate` | `durationMinutes` and `weekCommencingDate.startDate` are `required` today |
| `offence` | `prosecutorOffenceId`, `convictingCourtCode`, `count` | `prosecutorOffenceId` is `required` today — see below |
| `defendant` | `emailAddress1`, `emailAddress2` | LIBRA routes email through `personalInformation.contactDetails` instead |
| `individual` | `custodyTimeLimit` | |
| `personalInformation` | `address` | LIBRA carries address on `defendant` only |
| `individualAlias` | `title` | |
| `defendant` | `id` | the defendant UUID minted in the command handler — §4 of the analysis doc notes it is minted, discarded, then regenerated |

### `prosecutorOffenceId` is a dangling reference

The LIBRA sheet declares `offenceID` under **Listed Offences** (row 48 — the hearing-side
reference) but has **no `prosecutorOffenceId` on the offence itself**. Canonical both *requires*
that field and uses it as the target of exactly that reference, so as the sheet stands the
`listedOffences` entries point at an identifier the offence never declares.

Either the workbook is missing a row, or LIBRA expects the offence to be identified by
`offenceSequenceNo`. Question for whoever owns the workbook — it is not resolvable from the sheet.

---

## 7. Codes vs UUIDs — a transformation, not a schema change

The workbook models plea, verdict and allocation decision as **codes**, resolved against its own
`pleaType` / `verdictType` / `allocationDecision` tabs. The canonical schema models them as
already-resolved **UUIDs** (`plea.id`, `verdict.id`, `allocationDecision.motReasonId`), each
`required` within its object.

| Canonical | LIBRA sheet |
|---|---|
| `plea.id` (uuid) | `pleaCode` |
| `verdict.id` (uuid) | `verdictCode` |
| `allocationDecision.motReasonId` (uuid) | `allocationDecisionCode` |

This is **not LIBRA-specific** — the XHIBIT tab models them as codes too, so the gap is between the
workbook's modelling and the schema's, for both source systems. Something has to perform
code → UUID resolution and nothing in the pipeline does today. The matrix classes these as
`map-rename`: resolve them in the LIBRA transformation strategy rather than loosening the canonical
type to accept a code, which would push an unresolved value downstream.

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
python3 tools/schema-gen/build-schema-impact.py                       # markdown to stdout
python3 tools/schema-gen/build-schema-impact.py --changes-only         # just the work list
python3 tools/schema-gen/build-schema-impact.py --funcapp-libra-depth full   # deep-gate view (§3)
```

Regeneration is deterministic: a clean run reproduces the committed artefacts byte-for-byte.

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

**Curated vs computed.** The pcfdlrm/core field mapping for the 44 added fields is hand-curated in
`build-schema-impact.py`'s `MAPPING` — deciding that LIBRA's `licenseCode` is pcfdlrm's
`driverLicenceCode` is a judgement a name match cannot make. Every curated claim is re-verified
against both checkouts on each run, for existence *and* reachability; a stale claim exits non-zero
rather than printing a stale table. Everything else — statuses, change types, change details — is
computed.

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
  `relax-constraint` "widen maximum to 9 (canonical 6)". Discount it — the sheet's own row-130
  description enumerates `1 = on or in … 6 = on or before`, exactly canonical's range, so canonical
  needs no change. Any future `N<n>` integer whose canonical bound is semantic rather than
  digit-derived will report the same false positive until `libra_constraints()` also drops an
  all-9s `maximum`.
- The six `parentGuardian*` definitions exist only in the generated schema. The generator
  deliberately gives parent-guardian its own definitions instead of reusing the shared
  `personalInformation`/`address`/`contactDetails`, because the sheet's mandatoriness for those rows
  differs from the defendant's and sharing would silently weaken the defendant's own `required` list.

**How `required` is derived from the sheet:** a field is required only when every case-type mark it
carries is `M`. A blank cell means "not stated for this case type" and is ignored; any `O`/`CM`/`N/A`
disqualifies. LIBRA's four case-type columns are SJP Referral / Summons / Charge / Postal
Requisition. Per-case-type variance is preserved in each generated field's `description`, alongside
the originating sheet row number.


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
