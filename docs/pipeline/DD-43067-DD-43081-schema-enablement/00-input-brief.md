# Input brief — LIBRA enabler: schema enablement

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this
> directory.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) — schema enablement |

## The epic this story belongs to

**DD-43067 — LIBRA enabler.** Ingest magistrates' court case files from legacy system LIBRA
through the existing DLRM pipeline (Azure Blob → Function App → stagingDLRM → PCFDLRM →
Progression), reusing the XHIBIT path rather than forking it.

**Design decision already taken for the epic** (analysis §2): XHIBIT and LIBRA share **one**
stagingDLRM endpoint and **one** schema family. Source-system-specific behaviour is pluggable
strategies inside the shared path, not duplicated schemas, endpoints, or command/event types. The
rejected separate-schema alternative and the reasoning are in
[`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) §7.

**Repos in scope:** `cpp-context-stagingdlrm`, `cpp-context-prosecution-casefile-dlrm`.

## This story's request

Implement the LIBRA schema changes **as depicted in
[`libra-schema-impact.md`](../../analysis/libra-ingestion/libra-schema-impact.md)** — the whole
delta, not a hand-picked subset. Extend the test suites built in
[DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) with LIBRA scenarios rather than creating
parallel ones.

The delta is a machine-generated comparison of the `Libra Case - Min Data` tab of
`DLRM - CP Migration Data Schema V0.13.xlsx` against the canonical runtime schema in
`stagingdlrm-domain-value-schema`, regenerable via `tools/schema-gen/`. Its §2 matrix is the
field-level source of record; §5 triages the added fields by whether PCFDLRM and Progression
already model them.

## The delta, by the numbers

165 fields compared, rooted at `$.migratedCase`:

| Status | Count | What it means for this story |
|---|---|---|
| `exists_same_constraint` | 49 | nothing to do |
| `added_not_in_canonical` | 44 | new LIBRA fields — triaged in §5 (see field scope below) |
| `not_in_libra` | 52 | in canonical, absent from LIBRA — most are harmless; **6 are real blockers** |
| `exists_different_constraint` | 12 | 10 real conflicts, 2 are generator artefacts |
| `exists_required_in_libra_optional_in_canonical` | 3 | LIBRA mandates, canonical optional → LIBRA-specific validation |
| `exists_optional_in_libra_required_in_canonical` | 2 | canonical stricter than the workbook |
| `exists_renamed_and_retyped` | 3 | the code-vs-UUID cases — **out of scope**, see below |

### The 6 real blockers

The delta CSV flags 11 fields as "required in canonical, absent from LIBRA — blocker", but that
flag does not check whether the **containing object** is optional. Filtered properly:

| Field | Why it genuinely blocks |
|---|---|
| `caseDetails.dateReceived` | `caseDetails` is always required |
| `caseDetails.receiptType` | as above |
| `caseDetails.receivingCourt` | as above |
| `caseDetails.retrialIndicator` | as above |
| `hearings[].durationMinutes` | required within a hearing, and LIBRA does supply hearings |
| `offences[].prosecutorOffenceId` | `offences` is `minItems: 1` on every defendant |

Plus the object-level combinator `anyOf: [dateOfCommittal | dateOfSending]`, which LIBRA satisfies
with **neither** half.

The other 5 flagged entries — `weekCommencingDate.startDate`,
`personalInformation.address.address1`, and three under `parentGuardianInformation` — sit beneath
objects that are themselves optional, so LIBRA omitting them costs nothing. **This is a defect in
the delta tooling's blocker heuristic, worth fixing separately.**

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Constraint conflicts where the workbook is looser or leaves Format blank | **Keep canonical.** A blank or `TBC` Format cell is a workbook gap, not a requirement to loosen. Adopt the workbook only where it is *stricter*. **And produce a proposed set of LIBRA-workbook corrections so the workbook comes back into sync with canonical** — a deliverable of this story, not just a note. |
| How many of the 44 added fields | **Tiers 1–4 — 41 fields — including the PCFDLRM work** needed so tier 3–4 fields are not write-only. Tier 5 (3 fields) not *mapped* onward — but they cannot simply be omitted from the schema, because their canonical parents are closed and LIBRA sends them. See FR14a. |
| plea / verdict / allocationDecision code-vs-UUID | **Out of scope.** Needs a code-to-UUID resolution step that exists nowhere in the pipeline, and it affects XHIBIT equally. Record as a known gap with a follow-up ticket. |
| Test approach | Extend the DD-43078 suites and DSL; do not create parallel LIBRA tests. |

## Two consequences of the tier 1–4 decision worth knowing up front

Derived from §5's verified downstream data, not assumed:

1. **2 of the 41 have no home in either PCFDLRM or the core case model** —
   `uniquePropertyReferenceNumber` and `dxAddress`. Adding them to canonical achieves nothing;
   the story proposes excluding them, leaving **39**.
2. **7 of the 41 reach PCFDLRM but die before Progression**, because no schema reachable from the
   `courtReferral.json` payload has them: `backDuty`, `backDutyDateFrom`, `backDutyDateTo`,
   `prosecutorOfferAOCP`, `prosecutorCompensation`, `middleName2` and officer `forename3`. Fine if
   PCFDLRM is the intended consumer; needs a conscious decision if Progression is.
   (`vehicleMake` was an eighth until the reachability root was corrected — impact §8.)

## Why the relaxation needs matching validation

Every constraint removed is one the schema stops enforcing on **XHIBIT** too. The shared-schema
design (analysis §2) means the schema can only express what is true for *both* systems, so
anything true of only one has to move into source-system validation. That is why this story is not
just a schema edit: relaxing `receiptType` without an XHIBIT rule to replace it is a silent
regression for XHIBIT.

A schema rejection here is **terminal, not transient** (4xx gets zero retries — analysis §4), so
the rejection path and its outcome file are part of the contract, not an edge case.

## Known blockers

- **No real LIBRA `case.json` / `manifest.json` sample exists yet** (analysis §5 Q1). The final
  constraint list and the LIBRA rule content cannot be confirmed without one.
- **LIBRA's `initiationCode` value(s) are undecided** (§5 Q2) — must be agreed with the PCFDLRM /
  reference-data team, because the value determines which existing rule set LIBRA routes into.
- **`prosecutorOffenceId` is a dangling reference in the workbook** (impact §6) — LIBRA declares
  `offenceID` under Listed Offences but no `prosecutorOffenceId` on the offence itself, which
  canonical requires. Needs the workbook owner.
- **`organisationTelephoneNumber` looks like a workbook duplicate** of `companyTelephoneNumber`
  (row 63 has a blank Format cell) — confirm before adding either.

## Supporting analysis

Both regenerable from the workbook via `tools/schema-gen/`:

- [`libra-schema-impact.md`](../../analysis/libra-ingestion/libra-schema-impact.md) — §1 the
  relaxation scope at both gates, §2 the matrix and its `change_type` vocabulary, §3 the work
  per schema, §4 the source-system guard each relaxation needs, §5 the downstream tier triage,
  §6 the XHIBIT-only fields, §8 the func-app/canonical drift and core-type divergences.
- [`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) — pipeline
  trace, per-system change plan, open questions, and the rejected alternative (§7).

CSV companion for filtering: `docs/analysis/libra-ingestion/libra-schema-impact.csv` — 165 rows,
one per payload field; filter `change_required=yes` for the 67-row work list.
