# User Stories — LIBRA enabler: Function App LIBRA ingest

> Stage 3 artefact (story-writer). Source: `01-requirements.md` (approved),
> `02-design.md` (approved), `00-input-brief.md`.

## Jira mapping

**DD-43086 is already a single Jira story** under epic
[DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) (`01-requirements.md` header table,
Size: M). Per `docs/pipeline/sdlc-team-workflow.md`'s "one story = one pipeline run" principle,
splitting an epic into stories is a refinement-time activity that has already happened — DD-43086
*is* the story-level unit, and stage 3 here is "writing the story properly", not "split the epic".

The slices below (`LIBRA01`–`LIBRA04`) are therefore **not new Jira tickets and not new
pipelines**. They are internal delivery slices of the one DD-43086 ticket — a proposed PR
sequence for a single stage-5 implementation effort — chosen because the design doc's own
"Landing order" section (`02-design.md` §"Scope map") already sequences the work this way. All
four slices report progress against the same DD-43086 ticket; there is nothing to link or create
in Jira beyond what already exists.

## Summary table

| Slice | Title | FRs | ACs covered | Dependencies | Can run in parallel with |
|---|---|---|---|---|---|
| LIBRA01 | Folder-name list gate + shared source-system token helper | FR1, FR7 | AC1, AC2 | None | — |
| LIBRA02 | Author the LIBRA normalised case-submission schema | FR3, FR3a | AC3, AC4 | None (independent of LIBRA01) | LIBRA01 |
| LIBRA03 | Source-system-keyed schema selection, shared manifest validator, outcome-path confirmation | FR4, FR5, FR8 | AC5, AC6, AC7, AC9 | LIBRA01 (needs `SubmissionPathTokens`), LIBRA02 (needs `libra.case-submission.json` to select) | — |
| LIBRA04 | Drift-detection ratchet between Function App and canonical schemas (conditional) | FR6 | AC8 | None technically (reads two pre-committed flattened files); sequenced last by convention | LIBRA01, LIBRA02 |

**Not a slice — a risk to raise, not a deliverable:** FR2, the EventGrid subscription path
filter. It is Terraform/ARM outside this repo. Tracked under "Risks and dependencies" below, not
as LIBRA05, because there is no code to write against it in this repo — only a question to put to
the infra owner.

### Sequencing

DD-43086 is sized **M**, not large — a single-PR delivery would also be legitimate. This story
proposes a **four-slice split** rather than one PR, for three reasons drawn directly from
`02-design.md` and `01-requirements.md` rather than manufactured for their own sake:

1. **The design doc already defines the sequence.** `02-design.md`'s scope map states the landing
   order explicitly: "FR1 then FR7 first (small, independent, shrink the diff), then the schema
   chain FR3, then the selection map FR4/FR5, then the FR6 guard once its two decisions are
   confirmed." LIBRA01–LIBRA04 are that sequence made into reviewable units, not a new split
   invented at this stage.
2. **FR6 is explicitly conditional, not settled.** `01-requirements.md` FR6 and "Notes for the
   design stage" point 2 both frame it as a proposal needing two decisions (do it or drop it;
   fail-build or warn-only) before design, and `02-design.md`'s FR6 section repeats this as "two
   open decisions to confirm ... before/during implementation". Isolating it as its own slice
   means the other three-quarters of the story is not blocked on those decisions, and the slice
   can be dropped entirely without touching production code in the other three.
3. **FR3 (schema authoring) and FR4/FR5 (selection) have a real dependency, not just a stylistic
   one.** FR4's map entry for LIBRA loads `libra.case-submission.json` (FR3's output), and FR4's
   lookup key is produced by FR7's helper (LIBRA01). Sequencing them as separate, individually
   testable slices means AC3/AC4 (schema content is correct) are proven before AC5/AC6 (selection
   wiring is correct) are attempted, rather than debugging both at once in one large diff.

LIBRA01 and LIBRA02 have no dependency on each other (one touches `EventGridTriggerJava` /
`SubmissionPathTokens`, the other only adds new `resources/*.json` files) and can be built and
reviewed in parallel by two developers if the team has the capacity; LIBRA03 is the integration
point and must land after both. LIBRA04 can be authored in parallel with any of the others (it
only reads two already-committed flattened JSON documents) but is held last pending the two open
decisions, per the design doc.

All four slices remain part of the **same** stage-5/6/7/8 run for DD-43086 — the same branch (or
a short-lived stack of branches merging into it), the same code review, the same CI build, the
same sandbox deploy. This is a PR-sequencing decision inside one pipeline, not four pipelines.

---

## LIBRA01: Folder-name list gate + shared source-system token helper

### User story
As a **service owner migrating magistrates' court cases from LIBRA**,
I want **the Function App's `dlrm_folder_name` check to accept a configured list of source-system
folders (not just a single exact match), without ever honouring a wildcard, and one shared helper
that both the folder gate and the schema-selection logic use to derive the source-system token**,
so that **LIBRA blobs are no longer rejected purely because the folder gate only knew about one
configured value, the folder gate stays the strict source-system boundary it is designed to be,
and later schema selection (LIBRA03) is provably keyed on the same token the gate already
validated**.

### Background
FR1 + FR7 from `01-requirements.md`, designed in `02-design.md` §"FR1" and §"FR7". Today
`EventGridTriggerJava:84` does a single-value exact match
(`folderName.trim().equalsIgnoreCase(tokens.get(0))`) while the sibling batch-name check
(`EventGridTriggerJava:93-97`, `validateBatchNames:119-126`) already supports a comma-separated
list and a `*` wildcard. `02-design.md` collapses both onto one private helper,
`validateConfiguredNames(configuredNames, token, wildcardAllowed)`, with the wildcard behaviour
passed as a parameter — `false` for the folder gate, `true` (unchanged) for the batch gate. FR7
adds a new stateless utility, `event/SubmissionPathTokens`, extracting the source-system token
derivation that is currently hand-duplicated across `EventGridTriggerJava`, `TimerTriggerJava`
and `EventGridMonitor`. Per `02-design.md`, FR7 depends on FR1 landing first (FR1 settles how the
folder token is compared; FR7 then guarantees FR4/LIBRA03 keys on that same token) — the two are
bundled here for exactly that reason, not because they are otherwise inseparable.

### Acceptance criteria
- [ ] AC1: Given `dlrm_folder_name=XHIBIT,LIBRA`, when a blob lands under either the `XHIBIT` or
      `LIBRA` folder, then it is accepted; and when it lands under any other folder, then it is
      rejected and logged.
- [ ] AC2: Given `dlrm_folder_name=*`, when a blob lands under any folder, then it is **rejected**
      — the wildcard must not widen the source-system gate (`wildcardAllowed=false` on the folder
      check, per `02-design.md` §"FR1").

### Out of scope for this slice
- The LIBRA normalised schema itself (LIBRA02).
- Schema selection / the validator map (LIBRA03) — `SubmissionPathTokens` is introduced here but
  not yet consumed by `TimerTriggerJava`; that wiring is LIBRA03's job.
- The EventGrid subscription path filter (FR2) — infra, out of this repo; see "Risks and
  dependencies" below.
- Any change to the queue message format, path parsing, or submission-id extraction (NFR1) —
  these already treat the folder token as opaque data and stay untouched.

### Definition of done
- [ ] Code reviewed and approved (peer dev first pass, tech lead approves per
      `docs/pipeline/sdlc-team-workflow.md`).
- [ ] `validateConfiguredNames(configuredNames, token, wildcardAllowed)` replaces
      `validateBatchNames`; both the folder-name and batch-name checks route through it, differing
      only by the `wildcardAllowed` parameter (`02-design.md` §"FR1").
- [ ] New `event/SubmissionPathTokens` utility added, with `split(path)` and
      `sourceSystem(path)` (lower-case only, no `trim()` — matches the existing untrimmed folder
      comparison exactly, per `02-design.md` §"FR7").
- [ ] `EventGridTriggerJavaTest` extended (not forked) with LIBRA/XHIBIT accept and
      unconfigured-folder-reject cases (AC1) and the `dlrm_folder_name=*` reject case (AC2), per
      FR9 and the DD-43078 suites this extends.
- [ ] New `SubmissionPathTokensTest` added (no prior suite exists for this new class).
- [ ] All existing XHIBIT scenarios in `EventGridTriggerJavaTest` remain green (regression toward
      AC9, confirmed again in full at LIBRA03).
- [ ] No new runtime dependency introduced (NFR3 — this slice is WildFly-side-unaffected either
      way, but flagged since it is the first slice landed).
- [ ] `mvn test` green for `stagingdlrm-azure-functions`.

### Notes / open questions
- None outstanding. This slice has no dependency on the other three and can start immediately.

---

## LIBRA02: Author the LIBRA normalised case-submission schema

### User story
As a **service owner migrating magistrates' court cases from LIBRA**,
I want **a LIBRA-specific normalised case-submission schema that omits the fields LIBRA never
supplies, requires the four fields LIBRA-specific analysis identified as genuinely mandatory, and
declares (without requiring) the fields a closed schema would otherwise reject**,
so that **a well-formed LIBRA payload is accepted at the Function App gate on its own terms,
rather than being validated against XHIBIT's schema or a naive copy of it**.

### Background
FR3 + FR3a from `01-requirements.md`, designed in `02-design.md` §"FR3 / FR3a" (the fully
independent, fully self-contained LIBRA schema: one file, `libra.case-submission.json` — no other
`libra-*.json` file exists, after several rounds of inlining during implementation). The XHIBIT
files are **not modified and not `$ref`-ed** by it — the two chains never touch. The content of
what was originally `libra-case-details.json` (now `definitions.caseDetails`, `$ref`-ed from
`properties.migratedCase.properties.caseDetails`) is derived from
`docs/analysis/libra-ingestion/libra-schema-impact.csv`'s `funcapp_libra_action` column (`omit`
7 fields / `require` 4 fields / `declare` 5 fields / `not-validated-at-gate` 149 fields below
`caseDetails` depth) — explicitly **not** by editing a copy of `case-details.json`, per
`01-requirements.md` FR3 "Notes" point 3. `additionalProperties: false` (closed) is load-bearing:
it is what makes the `omit` fields rejected-if-sent and the `declare` fields mandatory-to-declare
(present-but-optional), unlike XHIBIT's open schema. FR3a's depth decision — match the XHIBIT
gate's `caseDetails`-only depth for the first release rather than the generated ~113-leaf deep
schema — is carried forward as a settled recommendation in `02-design.md`, not reopened here; it
avoids turning the workbook's blank/`TBC` cells (`observedEthnicity`, `arrestDate`, `hearingType`)
into false rejections at the earliest, least-diagnosable point in the chain.

### Acceptance criteria
- [ ] AC3: Given a LIBRA `case.json` that omits `receiptType`, `receivingCourt`, `dateReceived`,
      `retrialIndicator` and both of `dateOfSending`/`dateOfCommittal`, when it is validated
      against the new LIBRA normalised schema, then it passes.
- [ ] AC4: Given the same payload, when it is validated against the existing XHIBIT normalised
      schema, then it fails — proving the two schemas are genuinely distinct (this AC does not
      require the schema-selection wiring in LIBRA03; it validates the LIBRA and XHIBIT schema
      files directly against the same fixture payload).

### Out of scope for this slice
- Wiring the new schema into `TimerTriggerJava`'s validator selection (LIBRA03) — this slice adds
  the schema *files* and proves them correct in isolation; LIBRA03 makes them reachable at
  runtime.
- Any change to `case-details.json`, `migrated-case.json`, `pcf-prosecutor.json` or
  `stagingdlrm.case-submission.json` (the XHIBIT chain) — explicitly not modified.
- Descending below `caseDetails` depth (`defendants`, `hearings`, `officerInCase`) — FR3a's
  settled recommendation for this release; revisit once a real LIBRA sample exists.
- The canonical schema relaxation (`initiationCode` enum, oucode lengths) — DD-43081's scope, not
  this repo's Function App gate.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] One new schema file added under `stagingdlrm-azure-functions/src/main/resources/`:
      `libra.case-submission.json`, fully self-contained root to leaf — `02-design.md`
      §"FR3 / FR3a". Every intermediate `libra-*.json` file created during implementation
      (`libra-migrated-case.json`, `libra-case-details.json`, `libra-prosecutor.json`,
      `case-marker.json`, `libra-defendant.json` and 19 more, `libra-hearing.json`,
      `libra-listed-defendant.json`, `libra-officer-in-case.json`,
      `libra-officer-in-case-address.json`, `libra-phone.json`, `libra-email.json`,
      `libra-date.json`) was inlined and deleted — see "Evolution" in `02-design.md`.
- [ ] `caseDetails` (`definitions.caseDetails`, `$ref`-ed once — factored out for readability, not
      reuse) matches the matrix exactly: 7 `omit` fields absent from `properties`, 4 `require`
      fields present in both `properties` and `required` (`initiationCode`,
      `originatingOrganisation`, `prosecutorCaseReference`, `prosecutor`), 5 `declare` fields
      present in `properties` but not `required`, `additionalProperties: false`.
      `caseDetails.prosecutor` (now its own `definitions.prosecutor` entry, `$ref`-ed from
      `definitions.caseDetails.properties.prosecutor` — factored out the same way as
      `caseDetails` itself) requires `prosecutingAuthority` (the one `require` item
      one level down) — matches the LIBRA workbook schema's own `prosecutor` definition, and is
      kept independently declared from `pcf-prosecutor.json` (a deliberate decision, confirmed
      during implementation after checking the workbook — `02-design.md` §"FR3 / FR3a").
      **Extended later still:** every `caseDetails` property carries the workbook's
      `maxLength`/`minLength` (`prosecutorCaseReference: 36`, `originatingOrganisation`/
      `cpsOrganisation`/`prosecutor.prosecutingAuthority: 7`, `initiationCode`/`summonsCode: 1`,
      `informant: 92`, `caseMarkers[].markerTypeCode: 3`) — the same length-constraint reversal
      already made for `defendant`/`hearing`/`officerInCase`, extended to `caseDetails` too
      (`enum`/`pattern` still absent). The shared `libra-case-submission-valid.json` fixture was
      updated to satisfy the new lengths (`summonsCode`, `cpsOrganisation`,
      `caseMarkers[0].markerTypeCode`). `prosecutor` and `caseMarkers` were then each factored out
      as their own `definitions.prosecutor`/`definitions.caseMarkers` entries (the latter named to
      match the workbook's own definition name), `$ref`-ed from
      `definitions.caseDetails.properties.prosecutor`/`.caseMarkers.items` — same "used once,
      factored out for readability" treatment as `caseDetails` itself.
      **Extended once more:** `prosecutor.additionalProperties` flipped from `true` to `false`,
      matching the workbook's own `prosecutor` definition exactly — the divergence from
      `pcf-prosecutor.json` (which stays open) is now complete. New proving test:
      `shouldRejectLibraCaseSubmissionWithAnUndeclaredProsecutorProperty`.
      `hearing.timeOfHearing` was also factored out as its own `definitions.timeOfHearing` entry,
      `$ref`-ed from `definitions.hearing.properties.timeOfHearing` — same "used once, factored
      out for readability" treatment. `hearing.listedDefendants.items` was factored out the same
      way as `definitions.listedDefendant` (singular, matching the workbook's own definition
      name). `defendant.address` was factored out as `definitions.address` — `officerInCase.address`
      stays inline deliberately, since the workbook names it as a separate definition
      (`officerInCaseAddress`) despite identical content. **`postcode` was then also factored out**
      as `definitions.postcode`, `$ref`-ed from all three places it's declared identically
      (`address`, `officerInCase.address`, and the deeply-nested
      `defendant.individual.parentGuardianInformation…personalInformation.address`) — unlike the
      other factorings, this one has no workbook counterpart (the workbook duplicates the postcode
      regex inline in each address type); it's a func-app-local deduplication of one long regex,
      not a mirror of workbook structure. `defendant.individual` and, within it,
      `individual.personalInformation` were each factored out the same way as
      `definitions.individual`/`definitions.personalInformation` — both workbook definition names,
      both used once, both factored for readability. `personalInformation.contactDetails` and
      `defendant.individualAliases.items` were factored out the same way, as
      `definitions.contactDetails`/`definitions.individualAlias` (singular, matching the
      workbook's own array-item definition name) — `definitions` now has 16 entries.
      `individual.selfDefinedInformation` and `individual.parentGuardianInformation` were factored
      out the same way, as `definitions.selfDefinedInformation`/`definitions.parentGuardianInformation`
      — `definitions` now has 18 entries and `individual` is fully flat. Naming note:
      `parentGuardianInformation` is kept as one combined `oneOf` definition here, where 0.13
      splits it into `parentGuardianPerson`/`parentGuardianOrganisation` — matching how
      `dlrm-0.9.1.json` names this concept instead (`02-design.md` §"FR3 / FR3a" "Evolution").
      The `personalInformation`/`contactDetails`-shaped objects nested inside its person branch
      were then factored out as `definitions.parentGuardianPersonalInformation`/
      `definitions.parentGuardianContactDetails` — distinct workbook definitions from (and a
      different shape to) `individual`'s `personalInformation`/`contactDetails`, not the same ones
      reused — `definitions` now has 20 entries.
      **Then reversed for `contactDetails` specifically:** spotted as byte-for-byte identical to
      `contactDetails` (true in the workbook too, description text aside), and — unlike
      `address`/`officerInCase.address` and `prosecutor`/`pcf-prosecutor.json` — explicitly merged
      on request rather than kept separate. `parentGuardianContactDetails` deleted;
      `parentGuardianPersonalInformation.properties.contactDetails` now `$ref`s
      `definitions.contactDetails` directly. `definitions` back down to 19 entries.
      `parentGuardianPersonalInformation.properties.address` was merged the same way, into
      `definitions.address` (also byte-for-byte identical, in this schema and the workbook) —
      `parentGuardianPersonalInformation` is now fully flat. **A full-document scan for other
      duplicate shapes** then found `officerInCase.properties.address` — flagged earlier as
      identical to `definitions.address` but left inline at the time — as the one remaining
      genuine candidate; merged the same way, on request. Other scan hits (single-property leaf
      schemas like `{"type": "string", "maxLength": 1}` shared by unrelated fields such as
      `initiationCode`/`summonsCode`/`licenseCode`/`custodyStatus`) were left alone — coincidental
      value matches between distinct business fields, not genuine shared concepts.
      `defendant.offences.items` was then factored out as `definitions.offence` (singular,
      matching the workbook's own definition name) — the last inline top-level `defendant`
      property — `definitions` now has 20 entries. `offence`'s own four nested objects
      (`alcoholRelatedOffence`, `plea`, `verdict`, `allocationDecision`) were factored out next,
      each its own `definitions` entry matching the workbook's names exactly — `offence` is now
      fully flat and `definitions` has 25 entries (the running tally undercounted by one from the
      `contactDetails` merge onward — the actual file always had `postcode` too). The only inline object literals left in the
      whole schema are `parentGuardianInformation`'s own two `oneOf` branches, kept inline by
      design (it's deliberately one combined definition, not split further).
      **Final audit:** every object-type schema in the file was checked against the workbook for
      `additionalProperties`. One genuine gap found: `caseMarkers` was open where the workbook
      closes it — fixed to `additionalProperties: false`, with a new proving test
      (`shouldRejectLibraCaseSubmissionWithAnUndeclaredCaseMarkerProperty`). The other 6
      definitions without it (`offence`, `individual`, `personalInformation`,
      `parentGuardianPersonalInformation`, `hearing`, `listedDefendant`) are faithful to the
      workbook, which leaves them open too — not oversights.
      **Code review pass** approved with comments, two fixed: `defendant.offences` was missing
      `minItems: 1` (an oversight — sibling arrays already had it) — fixed with a new proving
      test; and this doc's `definitions` count had undercounted by one throughout (`postcode`
      wasn't folded back in) — corrected to 25. `postcode` was then renamed to `ukGovPostCode`
      (the JSON property name `postcode` in payloads is unchanged). **Extended once more, beyond
      the review's findings:** `minItems: 1` added to `migratedCase.defendants` and
      `migratedCase.hearings` — no workbook counterpart (the workbook leaves both bare, only
      constraining nested arrays), a deliberate LIBRA-gate strengthening on request. The shared
      fixture's `"defendants": []` was replaced with one fully-populated defendant; the "declared
      but optional" test's `hearings` row now supplies one valid hearing instead of an empty
      array. Two new proving tests
      (`shouldRejectLibraCaseSubmissionWithNoDefendants`,
      `shouldRejectLibraCaseSubmissionWithAnEmptyHearingsArray`).
      **A second code review** confirmed everything above and made one suggestion (assertions
      checked only message count, not that the message names `minItems`) — addressed below.
      **Extended again:** `minItems: 1` added to `caseDetails.caseMarkers`,
      `defendant.aliasForCorporate`, `defendant.individualAliases` too — same no-workbook-basis
      strengthening, all three optional so omitting the key stays valid. New parameterized tests
      (`shouldRejectLibraCaseSubmissionWithAnEmptyCaseDetailsArray`,
      `shouldRejectLibraCaseSubmissionWithAnEmptyDefendantArray`). All five `minItems` tests now
      share an `assertRejectedByMinItems(...)` helper asserting both the message count and that
      the message contains networknt's `"minimum of"` wording — addressing the review suggestion.
- [ ] `libra.case-submission.json`'s `migratedCase` level declares `caseDetails`, `defendants`
      (required), `hearings`, `officerInCase` (declared but optional), `additionalProperties: false`
      (closed) — matching the LIBRA workbook schema's own `migratedCase` definition
      (`docs/analysis/libra-ingestion/schema/libra/dlrm-libra-0.13.json`) at this level. Deliberate,
      LIBRA-only strengthening beyond XHIBIT's `migrated-case.json` (which requires only
      `caseDetails` and stays open, untouched) — the safe direction, not a new source of drift risk
      (FR6). `migrationSourceSystem` is deliberately **not** declared at all (was briefly `$ref`-ed
      to the shared `migrationSourceSystem.json`, also used by the manifest gate, and required,
      then removed) — because the object is closed, a payload carrying it is rejected as
      undeclared, not treated as optional. `migrationSourceSystem.json` itself keeps the
      `required: ["migrationSourceSystemName", "migrationSourceSystemCaseIdentifier"]` extension
      made while it was briefly referenced — it still independently strengthens the manifest gate.
- [ ] `defendants`, `hearings` and `officerInCase` are each fully expanded to the LIBRA workbook's
      corresponding definition graph, transitively — `defendant` → `address`/`individual`/
      `individualAlias`/`offence` and everything those reach (`plea`, `verdict`,
      `allocationDecision`, `alcoholRelatedOffence`, parent/guardian types, etc.); `hearing` →
      `listedDefendant`; `officerInCase` → `officerInCaseAddress`. Each is its own root-level
      `#/definitions/...` entry within `libra.case-submission.json` (`#/definitions/defendant`,
      `#/definitions/hearing`, `#/definitions/officerInCase`), with
      `migratedCase.defendants.items`/`migratedCase.hearings.items`/`migratedCase.officerInCase`
      each reduced to a single `$ref`. Unlike the rest of this gate, these three branches carry
      the workbook's full constraints (`pattern`/`maxLength`/`minimum`/`maximum`/`minItems`)
      verbatim, not bare types — a deliberate, explicitly scoped reversal of both FR3a's depth
      limit and its "no constraints" principle for these branches only (`02-design.md`
      §"FR3a — depth decision"). `date`, `phone` and `email` — reused 11/12/6 times respectively
      across the graph — are three further root-level `#/definitions/...` entries alongside the
      other four, rather than duplicated literally at every occurrence or kept as separate files.
- [ ] **Regression caught and fixed along the way:** the "declared but optional" test for
      `officerInCase` had previously asserted an empty `{}` was accepted — true only while
      `officerInCase` was bare `type: object`; false once it gained 5 required fields. Updated to
      supply a requirement-satisfying value instead.
- [ ] `JsonSchemaValidatorTest` extended (not forked) with LIBRA accept/reject rows alongside the
      existing XHIBIT rows, per FR9 and the DD-43078 suites this extends: the AC3 payload passes
      LIBRA, the AC4 case proves it fails XHIBIT.
- [ ] All existing XHIBIT rows in `JsonSchemaValidatorTest` remain green.
- [ ] `mvn test` green for `stagingdlrm-azure-functions`.

### Notes / open questions
- Coordinate with DD-43081's FR16 (the workbook-correction list) per `01-requirements.md` "Notes
  for the design stage" point 4 — several blank/`TBC` Format cells in the workbook affect what
  this schema can legitimately assert, and authoring it may surface more of them. Not a blocker
  for landing this slice, but worth flagging back to whoever owns the workbook if new gaps are
  found during authoring.

---

## LIBRA03: Source-system-keyed schema selection, shared manifest validator, outcome-path confirmation

### User story
As a **service owner migrating magistrates' court cases from LIBRA**,
I want **`TimerTriggerJava` to resolve the correct case schema for a submission by its
source-system token — using the LIBRA schema for LIBRA submissions and the XHIBIT schema for
XHIBIT submissions, sharing one manifest validator and one stagingDLRM endpoint across both — and
to fail clearly rather than silently or with a null pointer if the source system has no configured
schema**,
so that **a LIBRA submission is actually routed through the pipeline end to end, an unconfigured
source system produces a diagnosable failure instead of a crash, and a Function App-level
rejection still leaves the uploader a usable outcome file to poll for**.

### Background
FR4 + FR5 + FR8 from `01-requirements.md`, designed in `02-design.md` §"FR4 / FR5" and §"FR8".
Replaces `TimerTriggerJava`'s two hard-wired XHIBIT-only validator fields
(`caseJsonSchemaValidator`, `manifestJsonSchemaValidator`, `TimerTriggerJava:65-67`,
`:373-383`) with a table-driven `Map<String, SourceSystemValidators>` resolved once and cached,
keyed on the lower-cased token from LIBRA01's `SubmissionPathTokens.sourceSystem(...)`. One shared
`JsonSchemaValidator` instance for `stagingdlrm.manifest.json` is referenced by every map entry
(FR5) — only the case schema varies by source system; the submission URL and content type are
untouched, so both source systems POST to the same shared stagingDLRM endpoint (AC5). An
unconfigured source system must hit an explicit `isNull(validators)` branch — `SEVERE` diagnostic
naming the source system, delete the queue message, route to the log queue, return — mirroring the
existing case/manifest-missing branch (`TimerTriggerJava:123-128`); no fallback to XHIBIT's schema
and no `NullPointerException` (AC6). FR8 adds no production code — `02-design.md` traces that the
outcome path is already fully submission-derived (`EventGridMonitorHelper`, `EventGridMonitor`,
`TimerTriggerJava.writeOutcome`) and that a Function-App-level rejection already produces a usable
outcome file with an explicit empty-string `caseUrn` (not null, not missing) — this slice is
"confirm via tests", proven naturally once LIBRA02's schema can actually produce a rejection to
observe.

### Acceptance criteria
- [ ] AC5: Given a LIBRA submission folder, when `TimerTriggerJava` processes it, then the payload
      is POSTed to the same stagingDLRM endpoint and content type as an XHIBIT submission.
- [ ] AC6: Given a submission whose blob path names a source system with no configured schema,
      when it is processed, then it fails with a clear diagnostic (`SEVERE` log naming the source
      system, message deleted and routed to the log queue) rather than a null-pointer exception or
      a silent default to another source system's schema.
- [ ] AC7: Given a Function App-level validation failure for a LIBRA submission, when the outcome
      is written, then an outcome file appears under the LIBRA path and its content is asserted
      whole — including `success: false`, a populated `description`, and `caseUrn: ""` (an
      explicit empty string, not a missing key).
- [ ] AC9: Given `mvn clean install`, when it completes, then all Function App suites pass,
      including the DD-43078 XHIBIT scenarios, unchanged — the full-suite regression checkpoint for
      the functional chain landed across LIBRA01–LIBRA03 (independent of whether LIBRA04/FR6 is
      taken up).

### Out of scope for this slice
- The LIBRA schema's content (LIBRA02 — this slice consumes `libra.case-submission.json`, it does
  not author it).
- The folder gate and `SubmissionPathTokens` (LIBRA01 — this slice consumes the helper, it does
  not define it).
- The drift-detection guard (LIBRA04/FR6) — a separate, conditional concern.
- Any change to the outcome JSON's shape, the queue message format, or material handling (NFR2) —
  FR8 is confirmation by test, not a production change.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] New `validator/SourceSystemValidators` record (`caseValidator`, `manifestValidator`) added.
- [ ] `TimerTriggerJava` replaces its two hard-wired validator fields with
      `Map<String, SourceSystemValidators> validatorsBySourceSystem`, resolved once and cached,
      with one shared manifest `JsonSchemaValidator` instance referenced by both map entries
      (`02-design.md` §"FR4 / FR5").
- [ ] `processQueueMessage` resolves the pair via `SubmissionPathTokens.sourceSystem(queueMessage)`
      before validation, and the `isNull(validators)` branch is implemented exactly as designed
      (SEVERE log via the existing `LoggerHelper.logSevere` overload, delete + log-queue, return).
- [ ] `TimerTriggerJavaTest` extended (not forked) with: schema-selection routing per source
      system (AC5), the unconfigured-source-system diagnostic path (AC6), and the edge-rejection
      outcome file asserted whole including `caseUrn: ""` (AC7), per FR9 and the DD-43078 suites
      this extends.
- [ ] All existing XHIBIT scenarios across `EventGridTriggerJavaTest`, `JsonSchemaValidatorTest`
      and `TimerTriggerJavaTest` remain green.
- [ ] `mvn clean install` green for `stagingdlrm-azure-functions`, confirming AC9.
- [ ] NFR2 confirmed unaffected — no material bytes are downloaded or streamed anywhere in this
      change; only blob paths are assembled into the payload.

### Notes / open questions
- This slice is the integration point: it cannot be meaningfully tested end to end until both
  LIBRA01 (`SubmissionPathTokens`) and LIBRA02 (`libra.case-submission.json`) have landed. Sequence
  accordingly if run by different developers.
- Per `00-input-brief.md`, this slice is also the point at which DD-43081's end-to-end acceptance
  criterion (a real LIBRA submission accepted and processed) becomes provable — it is a
  prerequisite for that story's AC, not a dependency this story has on DD-43081.

---

## LIBRA04: Drift-detection ratchet between Function App and canonical schemas (conditional)

> **This slice is a proposal, not a settled requirement.** Do not start implementation until the
> two open decisions below are confirmed with the team. If the answer to decision 1 is "drop it",
> this slice is removed from DD-43086 entirely with no impact on LIBRA01–LIBRA03.

### User story
As a **service owner responsible for the Function App / stagingDLRM schema pair**,
I want **an automated check that fails when the Function App's schema becomes newly more lenient
than canonical for a field both declare**,
so that **the asymmetric, dangerous drift that already causes terminal 4xx rejections in
production cannot silently get worse, without forcing the two schemas to be coupled**.

### Background
FR6 from `01-requirements.md`, designed in `02-design.md` §"FR6". This is deliberately a **JUnit
test, not a build plugin** (matches NFR3 — build/test-time only, no new runtime dependency) that
reads the two already-committed, already-flattened documents
(`docs/analysis/libra-ingestion/schema/canonical/staging-dlrm-funcapp-flattened.json` and
`…-canonical-flattened.json`) and compares, for every definition present in both
(`caseDetails`, `migratedCase`, `prosecutor`, `migrationSourceSystem`,
`migrationSourceSystemName` today), only `additionalProperties` and `required` — the two axes
that produce a terminal 4xx when the Function App is more lenient than canonical. It is
deliberately **not** full constraint parity (patterns/lengths/enums), because the gate is
presence-only by design (FR3a) and a naive full-parity check would report 100+ findings on day
one. It is designed as a **ratchet against a pinned baseline of 6 known, pre-existing findings**
(verified by running the comparison directly, not just reasoning about it) — failing only on *new*
drift beyond that baseline, since the pre-existing XHIBIT/canonical drift is the accepted status
quo this story does not eliminate (`00-input-brief.md`). Scope is XHIBIT vs canonical only — a
LIBRA-vs-canonical comparison is not meaningful until DD-43081 relaxes canonical for the LIBRA
fields, and the definition intersection above naturally excludes the LIBRA-only shapes.

### Acceptance criteria
- [ ] AC8: Given FR6 is implemented, when a Function App schema is made more lenient than
      canonical for a shared field beyond the pinned baseline, then the build fails.

### Out of scope for this slice
- Regenerating the flattened comparison documents — that stays the manual job of
  `tools/schema-gen/flatten-canonical-schema.py`; this test reads committed output, it does not
  produce it.
- Full constraint-parity checking (patterns, lengths, enums) — scoped out deliberately per
  `02-design.md`, to avoid 100+ false findings against the intentionally presence-only gate.
- A LIBRA-vs-canonical comparison — not meaningful until DD-43081 lands the canonical relaxation.
- Eliminating the existing drift (`additionalProperties: true` on `caseDetails`/`migratedCase`/
  `prosecutor`, the two missing `migrationSourceSystem` `required` entries, the
  `prosecutor.prosecutingAuthority` `required` gap) — this is *detection* of new drift, not a fix
  for the baseline.

### Definition of done
- [ ] **Decision 1 confirmed and recorded here before implementation starts**: implement FR6, or
      drop it and carry the risk. (Open — see "Notes / open questions".)
- [ ] **Decision 2 confirmed and recorded here before implementation starts** (only if decision 1
      is "implement"): fail-the-build against the pinned baseline (recommended in `02-design.md`),
      or warn-only. (Open — see "Notes / open questions".)
- [ ] Code reviewed and approved.
- [ ] New test `stagingdlrm-azure-functions/src/test/java/uk/gov/moj/cpp/stagingdlrm/azure/validator/FuncAppCanonicalSchemaDriftTest` added, comparing only `additionalProperties` and
      `required` across the definition intersection of the two committed flattened documents.
- [ ] The 6-finding baseline is pinned in the test (not re-derived at runtime) and documented
      inline with a one-line justification per finding, matching the table in `02-design.md`
      §"FR6".
- [ ] A deliberately-introduced new leniency (e.g. a test fixture asserting the check fires) proves
      AC8 — the test must be shown to fail before the baseline is corrected, and pass once the
      pinned baseline correctly reflects only the pre-existing 6 findings.
- [ ] `mvn test` green for `stagingdlrm-azure-functions`, with this new test included in the run
      (build/test-time only — NFR3 confirmed, no new WildFly-side runtime dependency).

### Notes / open questions
- **Open — do FR6 at all, or drop it?** `01-requirements.md` and `02-design.md` both frame this as
  the team's call, not a settled requirement. Needs a decision from the requester/tech lead before
  this slice is picked up.
- **Open — if implemented, fail-the-build or warn-only?** `02-design.md` recommends
  fail-the-build **against the pinned baseline** so only genuinely new drift breaks CI; a
  warn-only mode (log the finding, green build) is the lighter alternative if the team is not
  ready to gate on it yet. AC8 as written in `01-requirements.md` implies fail-the-build.
- This slice has no code dependency on LIBRA01–LIBRA03 (it reads two static, pre-committed files)
  and could be authored in parallel with any of them once the two decisions above are answered —
  it is sequenced last here only because the decisions are the actual blocker, not the code.

---

## Risks and dependencies (not deliverable in this story)

- **FR2 — EventGrid subscription path filter — the single biggest delivery risk, and out of this
  repo.** If the Blob Storage EventGrid subscription is scoped to `XHIBIT/*`, no LIBRA blob will
  ever trigger the Function App regardless of LIBRA01–LIBRA04. This is Terraform/ARM work outside
  `cpp-context-stagingdlrm` — raise it with the infra owner on day one (per `00-input-brief.md`
  "Known blockers" and `01-requirements.md` "Risks and notes") and record the answer against
  DD-43086 in Jira once known. It is not a LIBRA0x slice because there is no code in this repo to
  write against it — only a verification step and, if needed, an infra change request to track
  separately.
- **`dlrm_folder_name` must not gain wildcard support** — enforced by LIBRA01's AC2, called out
  again here because it is a "must never regress" constraint rather than a one-off AC: any future
  change to the folder gate must preserve `wildcardAllowed=false`.
- **Two places will express source-system rules once this story lands** — the Function App's
  schemas (this story) and stagingDLRM's validation-rules strategy (DD-43081). Accepted with the
  per-source-system decision in `00-input-brief.md`; keep the Function App's schemas *structural*
  (shape, types, presence) and leave business rules to stagingDLRM, or the two will drift in
  behaviour as well as shape.

## Out of scope for the whole DD-43086 story (all slices)

- Everything inside stagingDLRM and PCFDLRM — canonical schema relaxation, the source-system
  validation-rules strategy, the rejection flow, and field additions all belong to
  [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081).
- Making the Function App depend on `stagingdlrm-domain-value-schema` — explicitly rejected; the
  Function App owns its own normalised schemas (`00-input-brief.md`).
- `tools/reconciliation/` `--source-system` support — separate ticket.
- Provisioning or changing the EventGrid subscription itself — FR2 is verify-and-raise, not an
  infra change delivered by this story.
- Any change to material handling, the queue message format, or batch-size/retry behaviour.

## Notes for the design stage carried forward

- FR1/FR7 (LIBRA01) and FR3/FR3a (LIBRA02) can start immediately and in parallel — neither depends
  on the other.
- LIBRA04/FR6 needs the two decisions above confirmed before it is picked up; it is not blocking
  the rest of DD-43086 either way.
- Coordinate LIBRA02 with DD-43081's FR16 (the workbook-correction list) — several blank/`TBC`
  Format cells affect what the LIBRA schema can legitimately assert.
