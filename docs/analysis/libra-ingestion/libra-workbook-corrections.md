# LIBRA workbook corrections and downstream gaps

**For:** the owner of `DLRM - CP Migration Data Schema V0.13.xlsx`, tab `Libra Case - Min Data`.
**From:** [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) — stagingDLRM schema enablement,
epic [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067).

Two things: **discrepancies in workbook V0.13** (sections A–C), and **the LIBRA fields that do not
reach Progression** (section D), each needing a decision on whether the data is wanted.
All found while making the stagingDLRM canonical schema accept LIBRA. Each was
identified by comparing the workbook against the live schemas in `stagingdlrm-domain-value-schema`,
`pcfdlrm-domain-value-schema` (tag `v17.104.21`) and `criminal-court-public-model` — not by reading
the sheet by eye. The comparison is regenerable: `./tools/schema-gen/regenerate.sh`.

**Nothing here blocks DD-43081.** In every case the schema keeps its current constraint and the
sheet is proposed for correction.

> ## Read this first — most of this document is already answered
>
> **A revised V0.13 landed on 12 Aug 2026**, after this document was written. It keeps the same
> version label, so it is easy to mistake for the same file, but it renames rows, adds five and
> deletes six. Re-running the comparison against it closes **six of the eleven asks in sections
> A–C** outright:
>
> | Ask | Status against the revised sheet |
> |---|---|
> | **1** `hearingType` Format `TBC` | **closed** — now `A10`, matching the schema's `maxLength: 10` |
> | **2** `arrestDate` Format blank | **closed** — now `D10`, matching the core date pattern |
> | **3** `observedEthnicity` type conflict | **half closed** — the defendant's row is now `N1`, so it agrees with the schema's `integer`. **The parent-guardian equivalent is still blank and should be set to match** |
> | **4** `offenceDateCode` Format `N1` | **open** — still `N1`; the range question stands |
> | **5** rename `cjsOffenceCode` → `offenceCode` | **closed** — done in the sheet |
> | **6** `organisationTelephoneNumber` duplicate | **closed by deletion** — but `companyTelephoneNumber` was deleted with it, which leaves the contract's `defendant.telephoneNumberBusiness` with nothing feeding it. **Confirm both deletions were intended** |
> | **7–8** initiation codes | **open, and wider** — the sheet now documents five codes (C, J, O, Q, X — `X = Remitted` is new) against a schema enum of `["O"]` |
> | **9** `prosecutorOffenceId` dangling reference | **closed** — the sheet now declares `prosecutorOffenceID` on the Offence section as `A36` |
> | **10** six fields where LIBRA should match XHIBIT | **closed** — `caseMarker` is now mandatory in all four case-type columns, which was the only live one |
> | **11** payload nesting | **open** — still the most urgent item, and unaffected by the revision |
>
> **Two new asks** arrive with the revision, both in section A: `courtRoomId` (12) and the
> parent-guardian `organisationName` (13).
>
> Sections A–C below are left as originally written, each item now carrying its revised status, so
> that the reasoning stays readable and nothing looks like it was quietly dropped. **Section D's
> field-by-field accounting has been rebuilt** against the revised sheet.

Row numbers throughout are from the `Libra Case - Min Data` tab of the **revised** V0.13 and have
shifted by two or three from the original.

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

### 1 — `hearingType` — ✅ CLOSED by the revision (now row 20)

| | |
|---|---|
| Workbook | Format cell said `TBC`; the revised sheet says **`A10`** |
| Schema | `type: string, maxLength: 10` |
| Ask | ~~Confirm the LIBRA maximum length~~ — answered: `A10` matches the schema exactly. No action |
| **Dev status** | **Unchanged** — `hearings[*].hearingType`, already flowing to PCFDLRM and Progression |

### 2 — `arrestDate` — ✅ CLOSED by the revision (now row 131)

| | |
|---|---|
| Workbook | Format cell was blank; the revised sheet says **`D10`** |
| Schema | `$ref` to the core date pattern (`YYYY-MM-DD`) |
| Ask | ~~Confirm the date format LIBRA supplies~~ — answered: `D10` is the ISO date, so this stays a pass-through. No action |
| **Dev status** | **Unchanged** — `defendants[*].offences[*].arrestDate`, already flowing |

### 3 — `observedEthnicity` (rows 70 and 114) — ⚠️ HALF CLOSED

| | |
|---|---|
| Workbook | Row 70 (defendant) was blank and is now **`N1`**, i.e. numeric — which settles it. **Row 114, the parent-guardian equivalent, is still blank** |
| Schema | `type: integer` |
| Ask | **Set row 114 to `N1` to match row 70.** The conflict is resolved for the defendant; the parent-guardian row is the only reason this stays open, and it is the same field in the same shared definition |
| **Dev status** | **Unchanged** — `…personalInformation.observedEthnicity` stays `integer`, already flowing. The parent-guardian path is one of the three `review-constraint` rows in the impact matrix purely because of the blank cell |

### 4 — `offenceDateCode` (row 128) — ❌ STILL OPEN

| | |
|---|---|
| Workbook | Description enumerates `1 = on or in, 2 = before, 3 = after, 4 = between, 5 = on or about, 6 = on or before`. Format cell is still `N1`, i.e. one numeric digit, which implies 0–9 |
| Schema | `integer, minimum: 1, maximum: 6` — matching the description |
| Ask | **Confirm 1–6 is the real range and correct the Format cell**, so tooling stops inferring 0–9 from the digit count. The schema is not being changed |
| **Dev status** | **Unchanged** — `defendants[*].offences[*].offenceDateCode` keeps `1–6`, already flowing |

### 12 — `courtRoomId` (row 16) — 🆕 NEW in the revision

| | |
|---|---|
| Workbook | A new row, Format `A36` — a 36-character string |
| Schema | `type: integer`, in both the canonical schema and the live XHIBIT contract |
| Ask | **A genuine type conflict — please adjudicate.** `A36` is UUID-shaped, which suggests LIBRA holds a court-room *identifier* rather than the integer room number the contract models. If LIBRA really supplies a 36-character value, the contract cannot carry it and this needs a mapping decision, not a Format-cell correction |
| **Dev status** | **Not implemented** — arrived after DD-43081's schema work. The contract's `integer` is what the generated LIBRA schema states |

### 13 — parent-guardian `organisationName` (row 101) — 🆕 NEW in the revision

| | |
|---|---|
| Workbook | Format cell blank |
| Schema | `type: string, maxLength: 255` |
| Ask | **Confirm the LIBRA maximum length.** If it is ≤ 255 the schema is already correct. The defendant's own `organisationName` row says `A255`, so this is most likely just an unfilled cell |
| **Dev status** | **Unchanged** — the contract's `maxLength: 255` is used |

---

## B. Naming and duplication

### 5 — Rename `cjsOffenceCode` to `offenceCode` — ✅ CLOSED by the revision (now row 125)

| | |
|---|---|
| Workbook | Was `cjsOffenceCode`; the revised sheet says **`offenceCode`** |
| Schema | `offenceCode` in canonical, PCFDLRM **and** the core case model. Otherwise identical: `string`, `maxLength: 8`, no change needed |
| Ask | ~~Rename in the sheet so it matches the contract~~ — done. No action |
| **Dev status** | **Unchanged** — `defendants[*].offences[*].offenceCode`, already flowing under the canonical name |

**The wider naming question is still open, and has grown.** 39 sheet labels differ from the contract's
property name — 21 of them the `Parent Guardian - <field>` rows, whose prefix the contract expresses as
nesting. They are listed in
[`libra-schema-impact.md`](./libra-schema-impact.md) §2 under *"Searching the CSV by the sheet's own
field name will sometimes fail"*.

This no longer costs review time the way it did: `schema/libra/dlrm-libra-0.13.provenance.json`
records every field's `sheetField` and `sheetRow` against the same JSONPath the impact matrix uses, so
a workbook label can be looked up mechanically. **Worth a decision all the same:** align the sheet's
labels with the contract, or accept the generated translation table as the permanent reconciliation.
Aligning is now the smaller job — the three code/UUID fields in D4 are the only ones where the
difference is more than a label.

### 6 — `organisationTelephoneNumber` may duplicate `companyTelephoneNumber` — ✅ CLOSED by deletion, with a query

| | |
|---|---|
| Workbook | Both rows existed: `companyTelephoneNumber` → `defendant.telephoneNumberBusiness`, and `organisationTelephoneNumber` with a blank Format cell. **The revised sheet deletes both** |
| Schema | Only one business-telephone field exists on the defendant, and nothing now supplies it |
| Ask | The duplication is resolved. **But confirm deleting `companyTelephoneNumber` was intended** — the contract's `defendant.telephoneNumberBusiness` is a live XHIBIT field that LIBRA now supplies nothing for. If LIBRA does hold a business telephone number, one row needs restoring |
| **Dev status** | **Declared, not mapped** — DD-43081 added `defendants[*].organisationTelephoneNumber` to canonical against the earlier sheet. With the row gone it is a field nothing populates: **remove it, or leave it as an accepted-but-unused optional.** Decide before T3 lands |

### 7 — `initiationCode: "H"` in Function App fixtures

| | |
|---|---|
| Workbook | Not applicable — this comes from test data, not the sheet |
| Schema | The CPP platform enum `uk.gov.justice.core.courts.InitiationCode` declares `Q, R, S, C, J, Z, O`. `H` is in neither that nor stagingDLRM's |
| Ask | **Is `H` a real initiation code or dead test data?** If real, the platform enum is missing it and that is a wider reference-data issue |
| **Dev status** | `caseDetails.initiationCode` enum **widened** to the platform's seven. `H` is not among them and is rejected by the schema for both source systems |

### 8 — Which initiation codes does XHIBIT legitimately send? — ❌ STILL OPEN, and LIBRA's set has grown

| | |
|---|---|
| Workbook | The XHIBIT tab documents more than one initiation code. **The revised LIBRA row 5 now documents five: `O` = Other, `C` = Charge, `Q` = Postal Charge, `J` = Single Justice Notice, and `X` = Remitted — `X` is new in this revision** |
| Schema | stagingDLRM's canonical schema has `enum: ["O"]` — narrower than both the workbook and the platform enum's seven codes |
| Ask | **Confirm XHIBIT's real code set, and confirm `X` is a real LIBRA initiation code.** DD-43081 widens the schema to the platform's seven and pins each source system to its own set in code; we need both lists to pin them correctly rather than replicate an over-tight constraint. Note the platform enum declares `Q, R, S, C, J, Z, O` — **`X` is not among them**, so if it is real it is a second reference-data gap alongside item 7's `H` |
| **Dev status** | `caseDetails.initiationCode` **widened + rule** — XHIBIT pinned by `InitiationCodeValidationRule`; its parameter waits on this answer. The LIBRA rule now needs five codes, not four, and `X` needs adding to the platform enum first |

---

## C. Contract shape — these change what the extract produces

### 9 — `prosecutorOffenceId` is a dangling reference — ✅ CLOSED by the revision

| | |
|---|---|
| Workbook | Row 47 declares `offenceID` under **Listed Offences** — the hearing-side reference to an offence. The Offence section declared **no** `prosecutorOffenceId`; the revised sheet **adds `prosecutorOffenceID` as row 124, Format `A36`** |
| Schema | Canonical requires `offence.prosecutorOffenceId` and uses it as the target of exactly that reference. `A36` matches its `maxLength: 36` exactly |
| Ask | ~~Either the sheet is missing a row, or LIBRA identifies offences by `offenceSequenceNo`~~ — answered: the row was missing and is now there. No action |
| **Dev status** | **Relaxed + rule** — `required` was dropped from `defendants[*].offences[*].prosecutorOffenceId` and re-imposed as an XHIBIT rule. **Now belt-and-braces rather than load-bearing**: the sheet supplies the field, so the relaxation could be reverted and the field made unconditionally `required` again if the sheet is trusted. Leaving it as it stands is also defensible — it costs nothing and the XHIBIT rule preserves the check |

The reference now has a target, so a LIBRA case can no longer carry listed-offence references pointing
at nothing.

### 10 — Six fields where LIBRA should match XHIBIT — ✅ CLOSED by the revision

Canonical requires each of these **within its containing object**, and those containers are
optional. So LIBRA may omit the object entirely — but an object it *does* send must be well-formed.
The schema was **not** relaxed for any of them.

| Field | Sheet row | Container | Status |
|---|---|---|---|
| `personalInformation.address.address1` | 82 | `address` — optional | no data change needed |
| `parentGuardianInformation.address.address1` | 116 | `parentGuardianInformation` — optional | no data change needed |
| `parentGuardianInformation.personalInformation.address.address1` | 116 | as above | no data change needed |
| `parentGuardianInformation.personalInformation.surname` | 106 | as above | no data change needed — the sheet marks it mandatory |
| `hearings[*].weekCommencingDate.startDate` | — | `weekCommencingDate` — optional | LIBRA omits the container |
| `caseDetails.caseMarkers[*].markerTypeCode` | 12 | `caseMarkers` — optional | **resolved: now `M` in all four case-type columns** |

**Dev status for all six: Unchanged** — the constraint stays exactly as it is; no schema change.

The five that needed no data change still need none. **The one live question was `markerTypeCode`** —
LIBRA does send `caseMarkers`, and the sheet marked the code itself optional. The revised sheet marks
it **mandatory in all four case-type columns**, which answers it: the schema's `required` is correct as
it stands, and the generated LIBRA schema now requires it too. Nothing further to decide.

### 11 — The payload nests; the sheet's flat Defendant section does not describe the contract

**For the LIBRA extract team and the DD-43086 Function App owner, not the workbook owner.**
Written up as [ADR-003](../../pipeline/adrs/003-libra-payload-contract.md), which is the binding
form of this item — this section is the summary.

Six fields are flat rows in the workbook but nested in the contract:

| Field | Sheet row | Where the contract holds it |
|---|---|---|
| `driverNumber` | 74 | `defendant.individual` |
| `licenseCode` | 75 | `defendant.individual` |
| `nationalInsuranceNumber` | 89 | `defendant.individual` |
| `occupation` | 72 | `defendant.individual.personalInformation` |
| `defendantOccupationCode` | 73 | `defendant.individual.personalInformation` |
| `vehicleCode` | 140 | `offence.vehicleRelatedOffence` |

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
workbook error — the sheet correctly describes what LIBRA holds. The gap is downstream: of the 41
fields LIBRA adds, **11 reach Progression and 30 do not**, for four different reasons. Those 30 need
a decision on whether the data is wanted, not a correction to a cell.

Rebuilt against the revised sheet. The counts moved because the revision **deleted six of the fields
this section used to account for** — `summonsCode`, `writtenChargePostingDate`, `backDuty`,
`backDutyDateFrom`, `backDutyDateTo` and `organisationTelephoneNumber`. If any of those was deleted by
mistake, its former row here says what was going to happen to it.

Every row is a LIBRA field with its **Dev status**. `PCFDLRM` and `Progression` say whether a
counterpart exists in each contract.

### D0 — Reaching Progression today (11)

Listed for completeness, so that a field's absence from this document is never ambiguous. These are
**Declared + mapped**: added to canonical by DD-43081 and sent onward by `MigratedCaseConvertor`.

| Row | Field | Canonical path | PCFDLRM name |
|---|---|---|---|
| 62 | `additionalNationality` | `…individual.selfDefinedInformation.additionalNationality` | same |
| 72 | `occupation` | `…individual.personalInformation.occupation` | same |
| 73 | `defendantOccupationCode` | `…individual.personalInformation.defendantOccupationCode` | `occupationCode` |
| 74 | `driverNumber` | `…individual.driverNumber` | same |
| 75 | `licenseCode` | `…individual.licenseCode` | `driverLicenceCode` |
| 89 | `nationalInsuranceNumber` | `…individual.nationalInsuranceNumber` | same |
| 137 | `statementOfFacts` | `…offences[*].statementOfFacts` | same |
| 138 | `statementOfFactsWelsh` | `…offences[*].statementOfFactsWelsh` | same |
| 140 | `vehicleCode` | `…offences[*].vehicleRelatedOffence.vehicleCode` | same, nested |
| 141 | `vehicleMake` | `…offences[*].vehicleMake` | same |
| 142 | `vehicleRegistrationMark` | `…offences[*].vehicleRegistrationMark` | same — but see below |

`summonsCode` was the twelfth until the revision deleted it. It had a home end to end
(`caseDetails.summonsCode` in PCFDLRM, `prosecutionCase.summonsCode` in core) and DD-43081 added and
mapped it. **Confirm the deletion was intended** — this is the one deleted field that was already
flowing all the way to Progression.

`vehicleRegistrationMark` exists in **two** PCFDLRM schemas, flat on the offence and on
`vehicleRelatedOffence`. Which one reaches Progression's `offenceFacts.vehicleRegistration` is
being confirmed with the PCFDLRM team before the mapping is written.

### D1 — Dropped from the schema entirely (5)

No counterpart anywhere in Progression's `courtReferral.json` closure. Their canonical containers
are open, or they are declared-and-ignored, so omitting them changes nothing today.

| Row | Field | PCFDLRM | Progression | Dev status |
|---|---|---|---|---|
| 139 | `prosecutorCompensation` | different name | **none** | **Not declared** — `offence` is open, so LIBRA may send it and it is ignored |
| 143 | `prosecutorOfferAOCP` | same name | **none** | **Not declared** — as above |
| 58 | `middleName2` | different name | **none** | **Not declared** — `personalInformation` is open, so it is ignored. Also supplied at row 105 for a parent guardian, which shares the same definition |
| 90 | `prosecutorCosts` | none | **none** | **Declared, never mapped** — `defendants[*].prosecutorCosts`; `defendant` is closed |
| 10 | `informant` | none | **none** | **Declared, never mapped** — `caseDetails.informant`; `caseDetails` is closed |

**The first three exist in PCFDLRM.** They die at the PCFDLRM → Progression hop, not before. So the
question is not whether the data is junk — it is **whether PCFDLRM is the intended consumer**. If it
is, these should be carried and we have dropped them wrongly. `prosecutorCosts`,
`prosecutorOfferAOCP` and `prosecutorCompensation` are magistrates'/fixed-penalty concepts with
no Crown Court equivalent, which is the likeliest reason Progression has no home for them.

The `backDuty`, `backDutyDateFrom` and `backDutyDateTo` group sat here too, in the same
"exists in PCFDLRM, nothing in Progression" position, until the revision deleted all three. So did
`writtenChargePostingDate`, which had no home anywhere. **If PCFDLRM turns out to be the intended
consumer, the `backDuty*` deletion is the one to revisit.**

`informant` and `prosecutorCosts` have no home anywhere. They are still **declared** in the canonical
schema, because `caseDetails` and `defendant` are closed objects and LIBRA sends the fields — an
undeclared field there is a terminal 4xx. They are accepted and discarded.

### D2 — Declared, never propagated (3)

Officer fields with no counterpart in PCFDLRM or Progression. Declared only so the payload passes.

| Row | Field | PCFDLRM | Progression | Dev status |
|---|---|---|---|---|
| 25 | `forename3` | different name | **none** | **Declared, never mapped** — `officerInCase.forename3` |
| 30 | `uniquePropertyReferenceNumber` | none | **none** | **Declared, never mapped** — `officerInCase.uniquePropertyReferenceNumber` |
| 42 | `DXAddress` | none (only `cps-core-domain/contact-details.json`, a different domain) | **none** | **Declared, never mapped** — `officerInCase.dxAddress` |

**Ask: confirm this data is genuinely not needed downstream** before we build a schema that
knowingly swallows it.

### D3 — Declared, converter mapping deferred (19)

Progression **does** model these — five of them as *mandatory* fields. They are blocked at the middle
hop: PCFDLRM's `pcf-policeOfficerInCase.json` declares only `{personalInformation, policeOfficerRank}`
and **is referenced by nothing**, so the officer block is unreachable from PCFDLRM's payload root.

All 19 share one **Dev status: Declared, not mapped** — present in canonical, accepted and stored,
but not propagated until PCFDLRM has a reachable home. Canonical paths are given per row.

| Row | Field | Canonical path | PCFDLRM | Progression |
|---|---|---|---|---|
| 23 | `forename` | `officerInCase.forename` | different name | optional |
| 24 | `forename2` | `officerInCase.forename2` | different name | optional |
| 26 | `surname` | `officerInCase.surname` | different name | **mandatory** |
| 27 | `policeOfficerRank` | `officerInCase.policeOfficerRank` | same name | **mandatory** |
| 28 | `policeWorkerReferenceNumber` | `officerInCase.policeWorkerReferenceNumber` | none | **mandatory** |
| 29 | `policeWorkerLocationCode` | `officerInCase.policeWorkerLocationCode` | none | **mandatory** |
| 31 | `address1` | `officerInCase.address.address1` | same name | **mandatory** |
| 32–35 | `address2`–`address5` | `officerInCase.address.*` | same name | optional |
| 36 | `postcode` | `officerInCase.address.postcode` | same name | optional |
| 37 | `workTelephoneNumber` | `officerInCase.workTelephoneNumber` | different name | optional |
| 38 | `mobileTelephoneNumber` | `officerInCase.mobileTelephoneNumber` | different name | optional |
| 39 | `emailAddress1` | `officerInCase.primaryEmail` | same name | optional |
| 40 | `emailAddress2` | `officerInCase.secondaryEmail` | same name | optional |
| 41 | `faxNumber` | `officerInCase.faxNumber` | none | optional |
| 88 | `numPreviousConvictions` | `defendants[*].numPreviousConvictions` | none | optional |
| 148 | `ConvictionDate` | `defendants[*].offences[*].convictionDate` | none | optional |

Two changes from the revision: the officer's `primaryEmail`/`secondaryEmail` rows are now labelled
`emailAddress1`/`emailAddress2` — the canonical names are unchanged, since nothing downstream models an
officer and the primary/secondary naming matches PCFDLRM's contact-details schema — and
`organisationTelephoneNumber` has left this list entirely, having been deleted from the sheet (item 6),
which takes the count from 20 to 19.

stagingDLRM declares all 19 now, so no data is rejected, but nothing maps them onward until PCFDLRM
adds the fields and wires the officer block. **Two asks:**

1. **Is the officer-in-case block actually wanted?** The sheet's own heading hedges — *"Unsure if
   this is persisted in Libra and CP progression"*. Progression is clearly ready for it, and marks
   five of its fields mandatory, so the answer looks like yes; please confirm.
2. **Prioritise the PCFDLRM work** if so, since these fields are write-only until it lands.

### D4 — Codes that never become identifiers (3)

| Row | LIBRA supplies | Sheet Format | Canonical expects | Dev status |
|---|---|---|---|---|
| 144 | `pleaCode` | `A36` | `plea.id` — a UUID, required | **No change — out of scope.** The code is neither accepted nor resolved |
| 146 | `verdictType` | `A36` | `verdict.id` — a UUID, required | **No change — out of scope** |
| 149 | `allocationDecision` | `A36` | `allocationDecision.motReasonId` — a UUID, required | **No change — out of scope** |

The workbook models these as reference-data **codes**; the schema models them as already-resolved
**UUIDs**. Nothing anywhere in the pipeline performs code → UUID resolution.

**This is not LIBRA-specific** — the XHIBIT tab models them as codes too, so the gap is between the
workbook's modelling and the schema's, for both source systems. It is out of scope for DD-43081 and
needs its own ticket, plus a decision on where resolution belongs.

**One observation that might close this cheaply.** All three Format cells say `A36` — a 36-character
string, which is exactly UUID-shaped and much longer than any reference-data code needs. **Ask: do
these three columns already hold resolved identifiers, with the `…Code`/`…Type` names being
historical?** If so, this whole section resolves to a naming correction. If they really are short
codes, `A36` is itself a Format-cell error worth fixing alongside the resolution decision.

The generated LIBRA schema now states the contract's position on all three — `plea.id`, `verdict.id`
and `allocationDecision.motReasonId`, each a `$ref` to the UUID pattern — with the sheet's own names
and Format cells recorded in `schema/libra/dlrm-libra-0.13.provenance.json`. So the shared schema tells
the extract team what a payload must carry, and nothing pretends a code would be accepted.

### D5 — The parent-guardian block was invisible to the comparison — ✅ FIXED

Recorded here originally as a limitation of the analysis rather than a decision. **The tooling has
since been fixed and this section is closed** — kept because the hand-check it asked for was
performed, and its result is worth keeping.

The problem was that the impact matrix contained **27 parent-guardian rows, every one reading
`not_in_libra`** — the comparison believed LIBRA supplied no parent-guardian data at all, while the
generated schema gave parent-guardian five definitions of its own carrying roughly 24 fields. Neither
was true. The two sides could not be joined because the matrix matches on JSONPath and the two schemas
organised the same fields differently.

**What changed.** The generator now reuses the contract's shared `personalInformation`, `address` and
`contactDetails` definitions exactly where the contract does, and resolves each container against the
contract's own `oneOf` branches. Parent-guardian fields therefore join on JSONPath like everything
else. The matrix now carries **6** parent-guardian `not_in_libra` rows, not 27, and they are genuine:
they are the *organisation* branch's address, which LIBRA supplies only on the person branch.

**The hand-check confirmed what this section predicted.** The field names line up: `forename`,
`surname`, `address1`–`address5`, `postcode`, `organisationName`, `companyTelephoneNumber`,
`dateOfBirth`, `gender` and `selfDefinedEthnicity` all have counterparts, so a LIBRA payload carrying a
parent guardian validates. The mechanical comparison then found three things the hand-check had not:

- **`middleName2`** on parent-guardian personal information has no home in the contract — as
  predicted, the same field and the same answer as its defendant twin in D1.
- **`observedEthnicity`** (row 114) has a blank Format cell where the defendant's equivalent is now
  `N1` — ask 3.
- **`organisationName`** (row 101) has a blank Format cell — ask 13.

One consequence of the fix needs recording, because it is a real loosening: `required` on a shared
definition can only be the **intersection** of its users. The sheet marks `forename` mandatory for a
defendant but optional for a parent guardian, and `address` mandatory for a parent guardian where the
defendant has none — so both are now optional in the schema, matching the live contract's own
`required: ["surname"]`. **Two of the sheet's mandatory marks are therefore enforced nowhere** and need
to become LIBRA validation rules. They are listed in the provenance sidecar's `deviations`.

---

## Completeness — what is and is not covered

LIBRA supplies 38 fields the canonical schema does not have — 39 rows in the matrix, because
`middleName2` is reachable at two paths — plus 3 modelled as codes where the schema expects
identifiers. This document accounts for **all 41**, so a field's absence from it is never ambiguous:

| | Count | Where |
|---|---|---|
| Added, mapped, reaching Progression | 11 | **D0** — no question outstanding |
| Dropped from the schema | 5 | **D1** |
| Declared, never propagated | 3 | **D2** |
| Declared, converter deferred | 19 | **D3** |
| Codes never resolved to identifiers | 3 | **D4** |
| **Total not reaching Progression** | **30** | |

Two further fields still flow under the contract's constraint rather than the workbook's, because the
sheet's Format cell is blank: parent-guardian `observedEthnicity` and `organisationName`. They are
ambiguity decisions rather than drops, and are asks 3 and 13 in **section A**. `hearingType` and
`arrestDate` were in this position until the revision filled their Format cells.

`courtRoomId` is the one field where the sheet and the contract disagree outright on type — ask 12.

The parent-guardian block (**D5**) is now covered by the mechanical comparison rather than being the
gap in this accounting.

---

## Summary of asks

Closed items are struck through, so nothing looks quietly dropped.

| # | Item | Owner | Urgency |
|---|---|---|---|
| ~~1–2~~ | ~~Format cells: `hearingType`, `arrestDate`~~ | — | **Closed** by the revision |
| 3 | Format cell: parent-guardian `observedEthnicity` (row 114) — set it to `N1` to match row 70 | Workbook owner | Before LIBRA data is validated in anger |
| 4 | Format cell: `offenceDateCode` — confirm 1–6 | Workbook owner | Before LIBRA data is validated in anger |
| 12 | **New** — `courtRoomId` is `A36` in the sheet, `integer` in both contracts | Workbook owner / Technical Architect | Before the extract is written |
| 13 | **New** — Format cell: parent-guardian `organisationName` | Workbook owner | Low |
| ~~5~~ | ~~Rename `cjsOffenceCode` → `offenceCode`~~ | — | **Closed** by the revision. The wider naming question (39 labels) stays open at low priority — the provenance sidecar makes it mechanical |
| 6 | ~~`organisationTelephoneNumber` duplicate~~ — closed by deletion, but **confirm deleting `companyTelephoneNumber` too was intended** | Workbook owner | Before T3 lands |
| 7–8 | Initiation codes: is `H` real; **is `X` real**; what does XHIBIT send | Reference data | Needed to pin both validation rules; `X` is not in the platform enum |
| ~~9~~ | ~~`prosecutorOffenceId` dangling reference~~ | — | **Closed** by the revision |
| ~~10~~ | ~~Six fields to match XHIBIT~~ | — | **Closed** — `markerTypeCode` is now mandatory in the sheet |
| 11 | Payload nesting | **LIBRA extract team + DD-43086** | **Most urgent** — before the extract is written |
| D0 | **`summonsCode` was deleted from the sheet and was already flowing end to end — confirm** | Workbook owner | Before the schema is frozen |
| D1 | 3 of the 5 dropped fields exist in PCFDLRM — is PCFDLRM the intended consumer? **And was deleting the `backDuty*` group intended?** | Technical Architect | Before the schema is frozen |
| D2 | Confirm 3 officer fields are genuinely unwanted downstream | Technical Architect | Low |
| D3 | Is the officer-in-case block wanted, and can the PCFDLRM work be prioritised? | Technical Architect + PCFDLRM | Blocks 19 fields from flowing |
| D4 | Where does plea/verdict/allocation code → UUID resolution belong? **And are the `A36` cells telling us they are already UUIDs?** | Technical Architect | Needs its own ticket; affects XHIBIT too |
| ~~D5~~ | ~~Verify the parent-guardian block by hand~~ | — | **Closed** — the tooling now joins it; two of the sheet's mandatory marks are intersected away and need LIBRA rules |

## Related

- [`libra-schema-impact.md`](./libra-schema-impact.md) — the full field-level comparison and its
  reasoning; [`libra-schema-impact.csv`](./libra-schema-impact.csv) is the 160-row matrix.
- [`schema/libra/dlrm-libra-0.13.provenance.json`](./schema/libra/dlrm-libra-0.13.provenance.json) —
  every field's sheet row, label, Format cell and per-case-type mandatoriness, keyed by JSONPath.
  Look a workbook label up here to find its row in the matrix. Its `deviations` list is every point
  where the contract overrode the sheet.
- [`libra-ingestion-analysis.md`](./libra-ingestion-analysis.md) — pipeline trace and per-system
  change plan.
- [DD-43081 input brief](../../pipeline/DD-43067-DD-43081-schema-enablement/00-input-brief.md) —
  carries the **exclusion register (R1–R6)**, the separate list of fields DD-43081 deliberately does
  not implement, addressed to the Technical Architect.
