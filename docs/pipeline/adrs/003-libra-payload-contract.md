# ADR-003: The LIBRA payload contract — canonical is the source of truth, and the payload nests

## Status

**Accepted 2026-08-11** — by the tech lead, at stage 3 of
[DD-43081](https://tools.hmcts.net/jira/browse/DD-43081), for the two parties this team owns:

| Party | What they own | Position |
|---|---|---|
| DD-43081 | the canonical schema — declares this shape | **Accepted.** May start stage 5 against it |
| [DD-43086](https://tools.hmcts.net/jira/browse/DD-43086) | the Function App gate schema — must accept exactly this shape | **Accepted.** Same team, same repo |
| **LIBRA extract team** | produces `case.json` — must emit this shape | **Outstanding.** Not this team's to accept |

**The extract team's confirmation is the one open item.** Until it is given, this ADR is the
pipeline's stated contract rather than an agreed one, and gate G1 on DD-43081 stays open. It does
not block stage 5: canonical follows PCFDLRM by existing convention regardless, so the shape would
not change on their answer — only the cost of a mismatch would.

No real LIBRA `case.json` exists yet, so **confirming this now costs nothing and retrofitting it
later costs a great deal.** That is the whole reason for the ADR.

## Date

2026-08-11

## Scope

The **shape of `migratedCase`** as it crosses Blob Storage → Function App → stagingDLRM. It does not
cover which fields are propagated onward to PCFDLRM or Progression — that is DD-43081's FR14 and the
downstream-gaps section of
[`libra-workbook-corrections.md`](../../analysis/libra-ingestion/libra-workbook-corrections.md).

## Context

The workbook `DLRM - CP Migration Data Schema V0.13.xlsx` is the only description of LIBRA's data,
and its `Libra Case - Min Data` tab is a **flat list of rows**. Read literally as a payload, it puts
five defendant attributes directly on `defendant` and `vehicleCode` directly on `offence`.

The pipeline's contract says otherwise, and the evidence is in the live schemas rather than in
anyone's preference:

- stagingDLRM's canonical schema is a **strict subset of PCFDLRM's at every level** — comparing the
  two, no canonical property sits at a different level from its PCFDLRM counterpart.
- `MigratedCaseConvertor` **renames fields but never moves them between objects** (`forename` →
  `firstName`, `surname` → `lastName`).

So the flat reading is a spreadsheet artefact, not the contract. Left unresolved it fails in two
different ways, one of them silent:

- `migratedCase`, `caseDetails`, `defendant` and `selfDefinedInformation` are
  `additionalProperties: false`. A field sent at the wrong level there is an **undeclared property —
  a terminal, non-retryable 4xx** on every submission.
- `offence` is open. A misplaced `vehicleCode` is **silently dropped**, and nobody notices until the
  data is missing in Progression.

## Decision

**1. The canonical schema in `stagingdlrm-domain-value-schema` is the contract of record.** The
workbook describes what LIBRA *holds*; canonical defines what the pipeline *accepts*. Where they
disagree, canonical wins and the workbook is corrected.

**2. These six fields are nested, not flat.** The payload uses the names in the middle column;
PCFDLRM's differing names are a converter concern and do not appear in `case.json`.

| Sheet row | Payload sends | At |
|---|---|---|
| 77 | `driverNumber` | `defendant.individual` |
| 78 | `licenseCode` | `defendant.individual` |
| 92 | `nationalInsuranceNumber` | `defendant.individual` |
| 75 | `occupation` | `defendant.individual.personalInformation` |
| 76 | `defendantOccupationCode` | `defendant.individual.personalInformation` |
| 145 | `vehicleCode` | `offence.vehicleRelatedOffence` |

**3. `officerInCase` is a top-level property of `migratedCase`**, a sibling of `caseDetails`,
`defendants`, `hearings` and `migrationSourceSystem` — with its address under
`officerInCase.address`.

**4. Omit an optional object entirely; never send it partly filled.** Container optionality is how
the schema accommodates data LIBRA does not hold. An object that *is* sent must satisfy its own
`required` list. This governs six fields whose containers are optional:

| Field | Container |
|---|---|
| `personalInformation.address.address1` | `address` |
| `parentGuardianInformation.address.address1` | `parentGuardianInformation` |
| `parentGuardianInformation.personalInformation.address.address1` | as above |
| `parentGuardianInformation.personalInformation.surname` | as above |
| `hearings[*].weekCommencingDate.startDate` | `weekCommencingDate` |
| `caseDetails.caseMarkers[*].markerTypeCode` | `caseMarkers` |

Practically: send no `weekCommencingDate` rather than one without a `startDate`, and no `caseMarker`
rather than one without a `markerTypeCode`.

**5. `initiationCode` is one of the platform's seven** — `Q, R, S, C, J, Z, O`, per
`uk.gov.justice.core.courts.InitiationCode`. LIBRA is expected to use `C, J, Q, S`; a value outside
the seven is rejected by the schema, and one inside the seven but outside LIBRA's agreed set is
rejected by a validation rule.

**6. The Function App gate schema is derived from canonical and must never be more lenient.** A gate
looser than canonical produces a terminal 4xx *after* the payload has been enqueued and dispatched —
the failure mode already observed on the XHIBIT path. Stricter is acceptable; looser is not.

## Options considered

| Option | Why not |
|---|---|
| **Payload stays flat; the converter re-nests** | Breaks the level-preserving convention the XHIBIT flow has always followed, and canonical's `defendant` would then hold data the domain models elsewhere. The re-nesting is only ~6 lines, so this is a modelling objection, not an effort one |
| **Open the closed objects** (`additionalProperties: true`) | Cheapest edit, but it removes the guarantee for **XHIBIT** too — any typo'd field would silently pass. Trades a LIBRA convenience for an XHIBIT regression |
| **Wait for a real LIBRA sample before deciding** | The extract is being built now. Waiting means discovering the mismatch after `case.json` exists, when changing it is a coordinated three-party release rather than a specification |
| **Canonical follows the workbook** | Would make canonical diverge from PCFDLRM at three levels and force the converter to re-nest for the first time in its life |

## Consequences

- **The extract cannot be written from the workbook alone.** It needs the canonical schema, or a
  worked example generated from it. Producing a synthetic `case.json` from workbook V0.13 is the
  cheapest way to make this concrete for all three parties.
- **Three artefacts must stay in step**: canonical, the Function App gate schema, and the extract.
  Decision 6 makes the direction of drift safe; it does not prevent drift.
- **Changing the nesting after LIBRA cases have been submitted is a breaking change** — submissions
  are terminal on 4xx, so a mismatch means manual resubmission of every affected case, not silent
  recovery.
- **This ADR does not settle the parent-guardian block.** The impact matrix cannot see it (see the
  corrections document, D5), so parent-guardian shape is verified by hand against canonical rather
  than asserted here.
- **QA gains a stable assertion target.** The end-to-end test in `cpp-apitests` can be authored
  against this contract and DD-43081's T4 outbound fixtures before the journey is runnable.

## Compliance notes

What to check on any story touching the LIBRA payload:

1. No field is declared in canonical at a level PCFDLRM does not hold it at.
2. No `additionalProperties` is loosened to accommodate a payload; the payload changes instead.
3. The Function App gate schema is not more lenient than canonical for any field both declare.
4. A worked `case.json` validates against canonical, and the same file validates against the gate.
5. Adding a field to canonical for LIBRA does not make it `required` — every LIBRA addition is
   optional, so XHIBIT stays valid.
