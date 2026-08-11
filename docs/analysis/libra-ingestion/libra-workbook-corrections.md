# LIBRA workbook corrections and downstream gaps

**For:** the owner of `DLRM - CP Migration Data Schema V0.13.xlsx`, tab `Libra Case - Min Data`.
**From:** [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) — stagingDLRM schema enablement,
epic [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067).

Two things: **eleven discrepancies in workbook V0.13** (sections A–C), and **the 35 LIBRA fields
that do not reach Progression** (section D), each needing a decision on whether the data is wanted.
All found while making the stagingDLRM canonical schema accept LIBRA. Each was
identified by comparing the workbook against the live schemas in `stagingdlrm-domain-value-schema`,
`pcfdlrm-domain-value-schema` (tag `v17.104.21`) and `criminal-court-public-model` — not by reading
the sheet by eye. The comparison is regenerable: `./tools/schema-gen/regenerate.sh`.

**Nothing here blocks DD-43081.** In every case the schema keeps its current constraint and the
sheet is proposed for correction. Items 10 and 11 are the two that change what the LIBRA extract has
to produce, so they are the time-critical ones.

Row numbers are from the `Libra Case - Min Data` tab.

## Dev status vocabulary

Every field below carries a **Dev status** saying what DD-43081 actually did with it. Paths are
canonical JSON paths with the `$.migratedCase.` prefix omitted.

| Value | Meaning |
|---|---|
| **Declared + mapped** | In the canonical schema, and `MigratedCaseConvertor` sends it to PCFDLRM |
| **Declared, not mapped** | In the canonical schema; PCFDLRM has no reachable home, so the converter mapping is deferred. Accepted and stored, not propagated |
| **Declared, never mapped** | In the canonical schema only so the payload is not rejected; nothing downstream models it, so it is deliberately discarded |
| **Not declared** | Absent from the canonical schema. Its container is open, so LIBRA may send it and it is silently ignored |
| **Unchanged** | Existing canonical field; DD-43081 changes nothing about it |
| **Relaxed + rule** | `required` dropped in canonical, re-imposed as an XHIBIT validation rule |
| **No change — out of scope** | Deliberately untouched by DD-43081 |

---

## A. Format cells that are blank or `TBC`

The generator reads the Format column to derive type and bounds. Where it is blank or `TBC` it
cannot, so the canonical constraint is kept and the question comes back here.

### 1 — `hearingType` (row 21)

| | |
|---|---|
| Workbook | Format cell says `TBC` |
| Schema | `type: string, maxLength: 10` |
| Ask | **Confirm the LIBRA maximum length.** If it is ≤ 10 the schema is already correct; if longer, tell us and we will reassess |
| **Dev status** | **Unchanged** — `hearings[*].hearingType`, already flowing to PCFDLRM and Progression |

### 2 — `arrestDate` (row 133)

| | |
|---|---|
| Workbook | Format cell blank |
| Schema | `$ref` to the core date pattern (`YYYY-MM-DD`) |
| Ask | **Confirm the date format LIBRA supplies.** If it is not ISO-8601, this becomes a transformation rather than a pass-through |
| **Dev status** | **Unchanged** — `defendants[*].offences[*].arrestDate`, already flowing |

### 3 — `observedEthnicity` (rows 73 and 117)

| | |
|---|---|
| Workbook | Format cell blank; the value reads as a string |
| Schema | `type: integer` |
| Ask | **A genuine type conflict — please adjudicate.** Is LIBRA's observed-ethnicity value numeric or alphanumeric? Row 117 is the parent-guardian equivalent and should match |
| **Dev status** | **Unchanged** — `…personalInformation.observedEthnicity` stays `integer`, already flowing |

### 4 — `offenceDateCode` (row 130)

| | |
|---|---|
| Workbook | Description enumerates `1 = on or in, 2 = before, 3 = after, 4 = between, 5 = on or about, 6 = on or before`. Format cell is `N1`, i.e. one numeric digit, which implies 0–9 |
| Schema | `integer, minimum: 1, maximum: 6` — matching the description |
| Ask | **Confirm 1–6 is the real range and correct the Format cell**, so tooling stops inferring 0–9 from the digit count. The schema is not being changed |
| **Dev status** | **Unchanged** — `defendants[*].offences[*].offenceDateCode` keeps `1–6`, already flowing |

---

## B. Naming and duplication

### 5 — Rename `cjsOffenceCode` to `offenceCode` (row 127)

| | |
|---|---|
| Workbook | `cjsOffenceCode` |
| Schema | `offenceCode` in canonical, PCFDLRM **and** the core case model. Otherwise identical: `string`, `maxLength: 8`, no change needed |
| Ask | **Rename in the sheet so it matches the contract.** The mismatch is name-only but costs review time — searching the impact matrix for `cjsOffenceCode` returns nothing, because the matrix keys on the canonical name |
| **Dev status** | **Unchanged** — `defendants[*].offences[*].offenceCode`, already flowing under the canonical name |

Twelve other fields diverge the same way. They are listed in
[`libra-schema-impact.md`](./libra-schema-impact.md) §2 under *"Searching the CSV by the sheet's own
field name will sometimes fail"*. **Worth a decision:** align all thirteen, or accept the
translation table as the permanent reconciliation.

### 6 — `organisationTelephoneNumber` may duplicate `companyTelephoneNumber` (row 63)

| | |
|---|---|
| Workbook | Row 62 `companyTelephoneNumber` → maps to `defendant.telephoneNumberBusiness`. Row 63 adds `organisationTelephoneNumber` with a **blank Format cell** |
| Schema | Only one business-telephone field exists on the defendant |
| Ask | **Are these the same field?** If so, delete row 63. If not, say what distinguishes them — we are currently planning to add `organisationTelephoneNumber` to the schema and would rather not add a duplicate |
| **Dev status** | **Declared, not mapped** — added at `defendants[*].organisationTelephoneNumber`; no PCFDLRM home. If it is a duplicate, remove it before T3 lands |

### 7 — `initiationCode: "H"` in Function App fixtures

| | |
|---|---|
| Workbook | Not applicable — this comes from test data, not the sheet |
| Schema | The CPP platform enum `uk.gov.justice.core.courts.InitiationCode` declares `Q, R, S, C, J, Z, O`. `H` is in neither that nor stagingDLRM's |
| Ask | **Is `H` a real initiation code or dead test data?** If real, the platform enum is missing it and that is a wider reference-data issue |
| **Dev status** | `caseDetails.initiationCode` enum **widened** to the platform's seven. `H` is not among them and is rejected by the schema for both source systems |

### 8 — Which initiation codes does XHIBIT legitimately send?

| | |
|---|---|
| Workbook | The XHIBIT tab documents more than one initiation code |
| Schema | stagingDLRM's canonical schema has `enum: ["O"]` — narrower than both the workbook and the platform enum's seven codes |
| Ask | **Confirm XHIBIT's real code set.** DD-43081 widens the schema to the platform's seven and pins each source system to its own set in code; we need the XHIBIT list to pin it correctly rather than replicate an over-tight constraint |
| **Dev status** | `caseDetails.initiationCode` **widened + rule** — XHIBIT pinned by `InitiationCodeValidationRule`; its parameter waits on this answer |

---

## C. Contract shape — these change what the extract produces

### 9 — `prosecutorOffenceId` is a dangling reference

| | |
|---|---|
| Workbook | Row 48 declares `offenceID` under **Listed Offences** — the hearing-side reference to an offence. The Offence section itself declares **no** `prosecutorOffenceId` |
| Schema | Canonical requires `offence.prosecutorOffenceId` and uses it as the target of exactly that reference |
| Ask | **Either the sheet is missing a row on the Offence section, or LIBRA identifies offences by `offenceSequenceNo` (row 128).** As it stands, `listedOffences` entries point at an identifier the offence never declares |
| **Dev status** | **Relaxed + rule** — `required` dropped from `defendants[*].offences[*].prosecutorOffenceId`, re-imposed as an XHIBIT rule. Still declared and still mapped to PCFDLRM when supplied |

Not resolvable from the sheet. DD-43081 makes `prosecutorOffenceId` optional so LIBRA can pass, which
means a LIBRA case can carry listed-offence references pointing at nothing until this is answered.

### 10 — Six fields where LIBRA should match XHIBIT

Canonical requires each of these **within its containing object**, and those containers are
optional. So LIBRA may omit the object entirely — but an object it *does* send must be well-formed.
The schema is **not** being relaxed for these.

| Field | Sheet row | Container |
|---|---|---|
| `personalInformation.address.address1` | 85 | `address` — optional |
| `parentGuardianInformation.address.address1` | 119 | `parentGuardianInformation` — optional |
| `parentGuardianInformation.personalInformation.address.address1` | 119 | as above |
| `parentGuardianInformation.personalInformation.surname` | 109 | as above |
| `hearings[*].weekCommencingDate.startDate` | — | `weekCommencingDate` — optional |
| `caseDetails.caseMarkers[*].markerTypeCode` | 15 | `caseMarkers` — optional |

**Dev status for all six: Unchanged** — the constraint stays exactly as it is; no schema change.

Five need no data change, because LIBRA omits the container altogether.

**The exception is `markerTypeCode` (row 15).** LIBRA *does* send `caseMarkers`, and the sheet marks
the code itself optional/conditional. **Ask: does LIBRA always supply a marker type code when it
sends a marker?** If yes, correct the sheet to mandatory, matching XHIBIT. If it genuinely can send
a marker without a code, tell us — that one comes back as a schema decision.

### 11 — The payload nests; the sheet's flat Defendant section does not describe the contract

**For the LIBRA extract team and the DD-43086 Function App owner, not the workbook owner.**
Written up as [ADR-003](../../pipeline/adrs/003-libra-payload-contract.md), which is the binding
form of this item — this section is the summary.

Six fields are flat rows in the workbook but nested in the contract:

| Field | Sheet row | Where the contract holds it |
|---|---|---|
| `driverNumber` | 77 | `defendant.individual` |
| `licenseCode` | 78 | `defendant.individual` |
| `nationalInsuranceNumber` | 92 | `defendant.individual` |
| `occupation` | 75 | `defendant.individual.personalInformation` |
| `defendantOccupationCode` | 76 | `defendant.individual.personalInformation` |
| `vehicleCode` | 145 | `offence.vehicleRelatedOffence` |

**Dev status for all six: Declared + mapped** at the nested paths above. The payload uses the names
in this table; PCFDLRM's differing names (`driverLicenceCode`, `occupationCode`) are applied by the
converter and never appear in `case.json`.

This is not a new convention. stagingDLRM's canonical schema is a strict subset of PCFDLRM's **at
every level** today, and the XHIBIT converter renames fields but never moves them between objects.
PCFDLRM already holds all six exactly where the table says.

**The LIBRA `case.json` must nest them to match**, and the failure modes differ:

- The five defendant fields — `migrated-defendant.json` is `additionalProperties: false`, so flat
  fields are a **terminal, non-retryable 4xx** on every submission.
- `vehicleCode` — `offence` is open, so a flat value is **silently dropped**. Quieter, and worse.

**Ask: confirm the extract nests these before it is built.** No real LIBRA `case.json` exists yet,
so specifying it now is free; retrofitting once the extract ships is not.

---

## D. Field-by-field status — where every LIBRA field ends up

**For the Technical Architect and the workbook owner jointly.** Nothing in this section is a
workbook error — the sheet correctly describes what LIBRA holds. The gap is downstream: of the 47
fields LIBRA adds, **12 reach Progression and 35 do not**, for four different reasons. Those 35 need
a decision on whether the data is wanted, not a correction to a cell.

Every row is a LIBRA field with its **Dev status**. `PCFDLRM` and `Progression` say whether a
counterpart exists in each contract.

### D0 — Reaching Progression today (12)

Listed for completeness, so that a field's absence from this document is never ambiguous. These are
**Declared + mapped**: added to canonical by DD-43081 and sent onward by `MigratedCaseConvertor`.

| Row | Field | Canonical path | PCFDLRM name |
|---|---|---|---|
| 13 | `summonsCode` | `caseDetails.summonsCode` | same |
| 65 | `additionalNationality` | `…individual.selfDefinedInformation.additionalNationality` | same |
| 75 | `occupation` | `…individual.personalInformation.occupation` | same |
| 76 | `defendantOccupationCode` | `…individual.personalInformation.defendantOccupationCode` | `occupationCode` |
| 77 | `driverNumber` | `…individual.driverNumber` | same |
| 78 | `licenseCode` | `…individual.licenseCode` | `driverLicenceCode` |
| 92 | `nationalInsuranceNumber` | `…individual.nationalInsuranceNumber` | same |
| 139 | `statementOfFacts` | `…offences[*].statementOfFacts` | same |
| 140 | `statementOfFactsWelsh` | `…offences[*].statementOfFactsWelsh` | same |
| 145 | `vehicleCode` | `…offences[*].vehicleRelatedOffence.vehicleCode` | same, nested |
| 146 | `vehicleMake` | `…offences[*].vehicleMake` | same |
| 147 | `vehicleRegistrationMark` | `…offences[*].vehicleRegistrationMark` | same — but see below |

`vehicleRegistrationMark` exists in **two** PCFDLRM schemas, flat on the offence and on
`vehicleRelatedOffence`. Which one reaches Progression's `offenceFacts.vehicleRegistration` is
being confirmed with the PCFDLRM team before the mapping is written.

### D1 — Dropped from the schema entirely (9)

No counterpart anywhere in Progression's `courtReferral.json` closure. Their canonical containers
are open, or they are declared-and-ignored, so omitting them changes nothing today.

| Row | Field | PCFDLRM | Progression | Dev status |
|---|---|---|---|---|
| 141 | `prosecutorCompensation` | different name | **none** | **Not declared** — `offence` is open, so LIBRA may send it and it is ignored |
| 142 | `backDuty` | same name | **none** | **Not declared** — as above |
| 143 | `backDutyDateFrom` | same name | **none** | **Not declared** — as above |
| 144 | `backDutyDateTo` | same name | **none** | **Not declared** — as above |
| 148 | `prosecutorOfferAOCP` | same name | **none** | **Not declared** — as above |
| 59 | `middleName2` | different name | **none** | **Not declared** — `personalInformation` is open, so it is ignored |
| 11 | `writtenChargePostingDate` | none | **none** | **Declared, never mapped** — `caseDetails.writtenChargePostingDate`; `caseDetails` is closed |
| 12 | `informant` | none | **none** | **Declared, never mapped** — `caseDetails.informant`; `caseDetails` is closed |
| 93 | `prosecutorCosts` | none | **none** | **Declared, never mapped** — `defendants[*].prosecutorCosts`; `defendant` is closed |

**The first six exist in PCFDLRM.** They die at the PCFDLRM → Progression hop, not before. So the
question is not whether the data is junk — it is **whether PCFDLRM is the intended consumer**. If it
is, these six should be carried and we have dropped them wrongly. The `backDuty*`, `prosecutorCosts`,
`prosecutorOfferAOCP` and `prosecutorCompensation` group are magistrates'/fixed-penalty concepts with
no Crown Court equivalent, which is the likeliest reason Progression has no home for them.

The last three have no home anywhere. They are still **declared** in the canonical schema, because
`caseDetails` and `defendant` are closed objects and LIBRA sends the fields — an undeclared field
there is a terminal 4xx. They are accepted and discarded.

### D2 — Declared, never propagated (3)

Officer fields with no counterpart in PCFDLRM or Progression. Declared only so the payload passes.

| Row | Field | PCFDLRM | Progression | Dev status |
|---|---|---|---|---|
| 26 | `forename3` | different name | **none** | **Declared, never mapped** — `officerInCase.forename3` |
| 31 | `uniquePropertyReferenceNumber` | none | **none** | **Declared, never mapped** — `officerInCase.uniquePropertyReferenceNumber` |
| 43 | `dxAddress` | none (only `cps-core-domain/contact-details.json`, a different domain) | **none** | **Declared, never mapped** — `officerInCase.dxAddress` |

**Ask: confirm this data is genuinely not needed downstream** before we build a schema that
knowingly swallows it.

### D3 — Declared, converter mapping deferred (20)

Progression **does** model these — five of them as *mandatory* fields. They are blocked at the middle
hop: PCFDLRM's `pcf-policeOfficerInCase.json` declares only `{personalInformation, policeOfficerRank}`
and **is referenced by nothing**, so the officer block is unreachable from PCFDLRM's payload root.

All 20 share one **Dev status: Declared, not mapped** — present in canonical, accepted and stored,
but not propagated until PCFDLRM has a reachable home. Canonical paths are given per row.

| Row | Field | Canonical path | PCFDLRM | Progression |
|---|---|---|---|---|
| 24 | `forename` | `officerInCase.forename` | different name | optional |
| 25 | `forename2` | `officerInCase.forename2` | different name | optional |
| 27 | `surname` | `officerInCase.surname` | different name | **mandatory** |
| 28 | `policeOfficerRank` | `officerInCase.policeOfficerRank` | same name | **mandatory** |
| 29 | `policeWorkerReferenceNumber` | `officerInCase.policeWorkerReferenceNumber` | none | **mandatory** |
| 30 | `policeWorkerLocationCode` | `officerInCase.policeWorkerLocationCode` | none | **mandatory** |
| 32 | `address1` | `officerInCase.address.address1` | same name | **mandatory** |
| 33–36 | `address2`–`address5` | `officerInCase.address.*` | same name | optional |
| 37 | `postcode` | `officerInCase.address.postcode` | same name | optional |
| 38 | `workTelephoneNumber` | `officerInCase.workTelephoneNumber` | different name | optional |
| 39 | `mobileTelephoneNumber` | `officerInCase.mobileTelephoneNumber` | different name | optional |
| 40 | `primaryEmail` | `officerInCase.primaryEmail` | same name | optional |
| 41 | `secondaryEmail` | `officerInCase.secondaryEmail` | same name | optional |
| 42 | `faxNumber` | `officerInCase.faxNumber` | none | optional |
| 63 | `organisationTelephoneNumber` | `defendants[*].organisationTelephoneNumber` | none | optional |
| 91 | `numPreviousConvictions` | `defendants[*].numPreviousConvictions` | none | optional |
| 153 | `convictionDate` | `defendants[*].offences[*].convictionDate` | none | optional |

stagingDLRM declares all 20 now, so no data is rejected, but nothing maps them onward until PCFDLRM
adds the fields and wires the officer block. **Two asks:**

1. **Is the officer-in-case block actually wanted?** The sheet's own heading hedges — *"Unsure if
   this is persisted in Libra and CP progression"*. Progression is clearly ready for it, and marks
   five of its fields mandatory, so the answer looks like yes; please confirm.
2. **Prioritise the PCFDLRM work** if so, since these fields are write-only until it lands.

### D4 — Codes that never become identifiers (3)

| Row | LIBRA supplies | Canonical expects | Dev status |
|---|---|---|---|
| 149 | `pleaCode` | `plea.id` — a UUID, required | **No change — out of scope.** The code is neither accepted nor resolved |
| 151 | `verdictCode` | `verdict.id` — a UUID, required | **No change — out of scope** |
| 154 | `allocationDecisionCode` | `allocationDecision.motReasonId` — a UUID, required | **No change — out of scope** |

The workbook models these as reference-data **codes**; the schema models them as already-resolved
**UUIDs**. Nothing anywhere in the pipeline performs code → UUID resolution.

**This is not LIBRA-specific** — the XHIBIT tab models them as codes too, so the gap is between the
workbook's modelling and the schema's, for both source systems. It is out of scope for DD-43081 and
needs its own ticket, plus a decision on where resolution belongs.

### D5 — The parent-guardian block is invisible to the comparison

A limitation of the analysis rather than a decision, found while assembling this document, and
recorded so it is not mistaken for coverage.

The impact matrix contains **27 parent-guardian rows, and every one reads `not_in_libra`** — i.e.
the comparison believes LIBRA supplies no parent-guardian data whatsoever. The workbook says
otherwise: the generated LIBRA schema has five parent-guardian definitions carrying roughly
24 fields (`parentGuardianPerson`, `…PersonalInformation`, `…ContactDetails`, `…Address`,
`…Organisation`).

The two sides cannot be joined because the matrix matches on JSONPath, and the generator gives
parent-guardian its **own** definitions where canonical reuses the shared `personalInformation`,
`address` and `contactDetails`. So LIBRA's parent-guardian fields produce no rows, and canonical's
parent-guardian paths all report as unsupplied. Neither is true.

**Materially this looks small.** Comparing the two by hand, the field names line up almost exactly —
`forename`, `surname`, `address1`–`address5`, `postcode`, `organisationName`,
`companyTelephoneNumber`, `dateOfBirth`, `gender`, `selfDefinedEthnicity` all have canonical
counterparts, so a LIBRA payload carrying a parent guardian should validate. The one difference
found is **`middleName2` on parent-guardian personal information**, which canonical has no home for —
the same field, and the same answer, as its defendant twin in D1.

**Ask: confirm by hand that LIBRA's parent-guardian block matches canonical's**, rather than relying
on the matrix for that section. Fixing the tooling to join these is a follow-up, not a blocker.

---

## Completeness — what is and is not covered

LIBRA supplies 44 fields the canonical schema does not have, plus 3 modelled as codes where the
schema expects identifiers. This document accounts for **all 47**, so a field's absence from it is never ambiguous:

| | Count | Where |
|---|---|---|
| Added, mapped, reaching Progression | 12 | **D0** — no question outstanding |
| Dropped from the schema | 9 | **D1** |
| Declared, never propagated | 3 | **D2** |
| Declared, converter deferred | 20 | **D3** |
| Codes never resolved to identifiers | 3 | **D4** |
| **Total not reaching Progression** | **35** | |

Three further fields — `hearingType`, `arrestDate` and `observedEthnicity` — **do** flow, but under
canonical's constraint rather than the workbook's, because the sheet's Format cell is blank or `TBC`.
They are ambiguity decisions rather than drops, and are in **section A**.

The parent-guardian block (**D5**) is the one area this accounting cannot vouch for.

---

## Summary of asks

| # | Item | Owner | Urgency |
|---|---|---|---|
| 1–4 | Format cells: `hearingType`, `arrestDate`, `observedEthnicity`, `offenceDateCode` | Workbook owner | Before LIBRA data is validated in anger |
| 5 | Rename `cjsOffenceCode` → `offenceCode` (+ decide on the other 12) | Workbook owner | Low — clarity, not correctness |
| 6 | `organisationTelephoneNumber` duplicate | Workbook owner | Before the field is added to the schema |
| 7–8 | Initiation codes: is `H` real; what does XHIBIT send | Reference data | Needed to pin the XHIBIT validation rule |
| 9 | `prosecutorOffenceId` dangling reference | Workbook owner | Before LIBRA cases are listed |
| 10 | Six fields to match XHIBIT — `markerTypeCode` is the live one | Workbook owner / Technical Architect | Before LIBRA data is validated |
| 11 | Payload nesting | **LIBRA extract team + DD-43086** | **Most urgent** — before the extract is written |
| D1 | 6 of the 9 dropped fields exist in PCFDLRM — is PCFDLRM the intended consumer? | Technical Architect | Before the schema is frozen |
| D2 | Confirm 3 officer fields are genuinely unwanted downstream | Technical Architect | Low |
| D3 | Is the officer-in-case block wanted, and can the PCFDLRM work be prioritised? | Technical Architect + PCFDLRM | Blocks 20 fields from flowing |
| D4 | Where does plea/verdict/allocation code → UUID resolution belong? | Technical Architect | Needs its own ticket; affects XHIBIT too |
| D5 | Verify the parent-guardian block by hand — the matrix cannot see it | Workbook owner + us | Before relying on the field counts |

## Related

- [`libra-schema-impact.md`](./libra-schema-impact.md) — the full field-level comparison and its
  reasoning; [`libra-schema-impact.csv`](./libra-schema-impact.csv) is the 165-row matrix.
- [`libra-ingestion-analysis.md`](./libra-ingestion-analysis.md) — pipeline trace and per-system
  change plan.
- [DD-43081 input brief](../../pipeline/DD-43067-DD-43081-schema-enablement/00-input-brief.md) —
  carries the **exclusion register (R1–R6)**, the separate list of fields DD-43081 deliberately does
  not implement, addressed to the Technical Architect.
