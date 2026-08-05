# LIBRA Ingestion — Feasibility Analysis

Status: **initial analysis / feasibility doc**, intended as input to the SDLC orchestrator
pipeline (`requirements-analyst` → `architecture-designer` → `story-writer`) for generating
enabler stories per system.

This doc was compacted from a longer working analysis whose §§1–12 argued a
separate-schema/separate-endpoint design that §13 of the same document then reversed. Keeping a
superseded design in the folder invited it being read as current, so it was removed. Everything
load-bearing was carried across: §7 below records the discarded alternative and why it was dropped.

Field-level schema impact — what changes in the func-app gate, the canonical schema, pcfdlrm and
Progression — is in [`libra-schema-impact.md`](./libra-schema-impact.md) and its CSV.

---

## Solution overview

The shape of the solution in one page: the **as-is** (XHIBIT-only) pipeline, the **target**
pipeline with LIBRA added, and the three places where source-system-keyed **strategies** are
introduced. The section-by-section detail follows from §1 onward.

Diagram legend: `◆ CHANGED` = existing code/config modified · `◆ NEW` = new extension point
introduced · `◆ UNLOCKED` = existing capability that starts being used, with no code change ·
`(unchanged)` = no LIBRA-specific change identified.

### A. As-is — XHIBIT only

```
Azure Blob Storage
  XHIBIT/{batch}/{case}/{submissionId}/{fileName}
  │
  ▼
[FUNCAPP]  stagingdlrm-azure-functions
  dlrm_folder_name = XHIBIT           ── single value, exact match only
  ONE fixed local schema pair (case + manifest)
  hand-maintained copies, no build-time link to the canonical schemas
  └── already drifted from canonical → payload can pass here and be
      rejected downstream as a terminal (non-retryable) 4xx
  │  POST  → one hardcoded stagingDLRM URL
  ▼
[STAGINGDLRM]  command-api → command-handler → aggregate → event-processor
  no source-system branching anywhere in production code
  case-details.json:  initiationCode  enum ["O"]        ── LIBRA cannot pass
                      receivingCourt  unconditionally required
  no non-schema validation layer
  MigratedCaseSubmissionRejected event exists but is DORMANT (never applied)
  │  POST → pcfdlrm
  ▼
[PCFDLRM]  cpp-context-prosecution-casefile-dlrm
  CcProsecutionValidationRuleProvider — rule sets keyed by CaseType × Channel
  └── NO source-system axis: all DLRM traffic shares one map
  3 hardcoded XHIBIT-only guards (materials file-type, hearing/defendant
  match, defendant-field defaulting) — silently no-op for any other source
  │
  ▼
[PROGRESSION]  migration-aware natively (migrationSourceSystem is first-class)

[RECONCILIATION tooling]  SOURCE="XHIBIT" hardcoded in the report scripts
```

### B. Target — XHIBIT + LIBRA on one pipeline

Same topology, same endpoint, same schema family. Source system becomes a **dispatch key**
rather than a fork in the pipeline.

```
Azure Blob Storage
  XHIBIT/{batch}/...          LIBRA/{batch}/...          ◆ CHANGED (EventGrid
  └── path convention already treats sourceSystem as a variable segment       subscription
                                                          path filter may need widening — §5 Q4)
  │
  ▼
[FUNCAPP]  ONE deployment serves both source systems (not forked)
  dlrm_folder_name = XHIBIT,LIBRA                                    ◆ CHANGED
  └── comma-separated list, mirroring dlrm_batch_name's existing pattern;
      deliberately NO wildcard — folder name is the source-system security gate
  │
  │  sourceSystem token parsed from blob path
  ▼
  ┌─ SCHEMA-SELECTION STRATEGY ─────────────────────────────────┐  ◆ NEW
  │  Map<sourceSystem, { caseValidator, manifestValidator }>    │
  │    XHIBIT ──→ XHIBIT normalised case schema                 │
  │    LIBRA  ──→ LIBRA  normalised case schema                 │
  │    (manifest schema, submission URL, content type: SHARED)  │
  │                                                             │
  │  table-driven lookup — not conditionals scattered through   │
  │  TimerTriggerJava                                           │
  └─────────────────────────────────────────────────────────────┘
        ▲
        │  schemas UNPACKED AT BUILD TIME from                            ◆ NEW
        │  stagingdlrm-domain-value-schema (maven-dependency-plugin),
        │  replacing the hand-maintained copies
        │
  [ SINGLE NORMALISED SCHEMA SOURCE ]  ── one canonical definition, consumed by
  stagingdlrm-domain-value-schema        both the FUNCAPP (pre-validation) and
                                         stagingDLRM (canonical validation), so
                                         a second source system cannot introduce
                                         a third independently-drifting copy
  │
  │  POST  → ONE stagingDLRM endpoint (/receive-migrated-case-submission)
  ▼
[STAGINGDLRM]  ONE endpoint · ONE schema family · ONE command/event type
  command-api                                                        (unchanged)
  │
  case-details.json RELAXED, shared by both source systems:           ◆ CHANGED
    initiationCode  →  plain string (enum dropped)
    allOf[ anyOf(dateOfCommittal | dateOfSending),
           anyOf(sendingCourt    | receivingCourt) ]
  ripple: MigratedCaseConvertor .getInitiationCode().name() → .getInitiationCode()
  │
  ▼
  ┌─ VALIDATION-RULES STRATEGY  (command-handler) ──────────────┐  ◆ NEW
  │  Map<MigrationSourceSystemName, MigratedCaseValidationRules>│
  │    XHIBIT ──→ XHIBIT rules                                  │
  │    LIBRA  ──→ LIBRA  rules   (rule content TBD — §5 Q1)     │
  │                                                             │
  │  invoked BEFORE the aggregate call; deliberately mirrors    │
  │  the FUNCAPP's source-system-keyed dispatch                 │
  └─────────────────────────────────────────────────────────────┘
        │                              │
        │ pass                         │ fail
        ▼                              ▼
  MigratedCaseSubmissionReceived   MigratedCaseSubmissionRejected     ◆ NEW
        │                          (dormant event EXTENDED with
        │                           submissionId + validationErrors,
        │                           applied by a new aggregate method,
        │                           4th @Handles in event-processor →
        │                           outcome file reaches the uploader)
        │  POST → pcfdlrm
        ▼
[PCFDLRM]
  real initiationCode now flows through, so LIBRA cases route into the       ◆ UNLOCKED
  ALREADY-BUILT SUMMONS / REQUISITION rule sets — no PCFDLRM code change
  for that dimension (value must be agreed with reference-data team — §5 Q2)
  │
  ▼
  ┌─ RULE-SET STRATEGY  (CcProsecutionValidationRuleProvider) ──┐  ◆ CHANGED
  │  today:   Map<CaseType × Channel, ruleSet>                  │
  │  target:  + source-system axis, IF many rules diverge       │
  │           Map<sourceSystem, Map<CaseType × Channel, …>>     │
  │  else:    keep the existing map and add mirror-image scoped │
  │           guards for the 3 XHIBIT-only behaviours           │
  │           (decision gated on real LIBRA data — §3.4, §5 Q6) │
  └─────────────────────────────────────────────────────────────┘
  │
  ▼
[PROGRESSION]  no LIBRA-specific change identified — regression-test only   (unchanged)

[RECONCILIATION tooling]  --source-system parameter replaces the hardcoded   ◆ CHANGED
                          SOURCE="XHIBIT" literal (operational prerequisite)
```

### C. The new design elements

Three strategy points, one normalisation fix, and one reused-but-dormant event:

1. **Schema-selection strategy in the Function App** — the only layer where the two source
   systems validate against *different* schemas. Selection is a source-system-keyed map, and the
   schemas it selects from are the **normalised canonical schemas pulled in at build time** from
   `stagingdlrm-domain-value-schema` — so the Function App stops holding its own hand-maintained
   fork. This fixes an already-observed live drift bug as a side effect, and is the reason adding
   LIBRA does not create a third drifting copy. (§3.2)
2. **Validation-rules strategy in stagingDLRM** — a `MigratedCaseValidationRules` implementation
   per `MigrationSourceSystemName`, resolved from a map and invoked before the aggregate. It is
   the first non-schema validation mechanism in the module; the extension point is wired
   end-to-end now, with LIBRA's actual rule content deferred until a real sample exists. (§3.3)
3. **Rule-set strategy in PCFDLRM** — PCFDLRM already has the mature map-based mechanism; the
   change is to add a *source-system axis* to it (or, if only a handful of rules diverge, mirror
   the three existing scoped XHIBIT guards rather than over-abstract). This is the one strategy
   whose final shape is a genuine open decision. (§3.4)
4. **Shared schema relaxed, not forked** — one `case-details.json` serves both systems
   (`initiationCode` un-enumerated, `sendingCourt`/`receivingCourt` as an `anyOf` pair). No new
   `$id` namespace, no new endpoint, no new command or event type. (§2, §3.3)
5. **Dormant rejection event reused** — `MigratedCaseSubmissionRejected` already exists as
   schema + POJO; it is extended and wired rather than replaced, so a strategy-rule rejection
   surfaces to the uploader through exactly the same outcome-file path a schema rejection uses
   today. (§3.3)

What deliberately does **not** change: the pipeline topology, the number of Function App
deployments, the stagingDLRM endpoint and its command/event types, and Progression.

### D. Change points at a glance

| #  | Component                 | Change                                                       | Kind                       | Detail      |
|----|---------------------------|--------------------------------------------------------------|----------------------------|-------------|
| 1  | Azure Storage / EventGrid | Widen path filter if scoped to `XHIBIT/*`                    | Config (infra, unverified) | §3.1, §5 Q4 |
| 2  | Function App              | `dlrm_folder_name` accepts comma-separated list, no wildcard | Config + code              | §3.2        |
| 3  | Function App              | Source-system-keyed schema-selection strategy                | **New extension point**    | §3.2        |
| 4  | Function App              | Canonical schemas unpacked at build time (drift fix)         | Build                      | §3.2        |
| 5  | stagingDLRM               | `case-details.json` relaxed (shared, not forked)             | Schema                     | §3.3        |
| 6  | stagingDLRM               | `MigratedCaseConvertor` `initiationCode` ripple              | Code                       | §3.3        |
| 7  | stagingDLRM               | `MigratedCaseValidationRules` strategy registry              | **New extension point**    | §3.3        |
| 8  | stagingDLRM               | Extend + wire dormant `MigratedCaseSubmissionRejected`       | Domain event + handler     | §3.3        |
| 9  | PCFDLRM                   | Agree LIBRA `initiationCode` with reference-data team        | **Cross-repo decision**    | §3.4, §5 Q2 |
| 10 | PCFDLRM                   | Source-system axis on rule provider *or* mirrored guards     | Strategy (shape TBD)       | §3.4, §5 Q6 |
| 11 | Progression               | None — regression-test LIBRA through migration-aware logic   | Test only                  | §3.5        |
| 12 | Reconciliation tooling    | `--source-system` parameter                                  | Ops tooling                | §3.6        |

---

## 1. Goal

Ingest magistrates' court case files from legacy system **LIBRA**, reusing the existing DLRM
XHIBIT pipeline end to end:

```
Azure Blob Storage → Azure Function App (stagingdlrm-azure-functions)
  → stagingDLRM → PCFDLRM → Progression
```

## 2. Design decision

XHIBIT and LIBRA share **one** stagingDLRM REST endpoint
(`/receive-migrated-case-submission`) and **one** schema family
(`case-details.json`/`migrated-case.json`). Source-system-specific behaviour (validation
rules, field handling) is implemented as pluggable, source-system-keyed strategies inside the
shared code path — not as duplicated schemas, endpoints, or command/event types.

**Why**, in brief (full reasoning available on request):
- The schema/domain layer already anticipated both systems before this analysis started —
  `migrationSourceSystemName` was already an enum of `["LIBRA", "XHIBIT"]`, and
  `case-details.json` already had a `sendingCourt` field commented as LIBRA-specific.
- A duplicated-schema approach hits a hard compiled-type wall: `initiationCode` compiles from
  `enum: ["O"]` into a single-legal-value Java enum, which any other LIBRA value would fail to
  deserialize against.
- The neighbouring PCFDLRM service already solves this exact problem the same way — one
  shared schema/endpoint, a map-based rule-set provider for cases needing a different rule
  profile, and small scoped guards for the few rules that don't — proving the pattern works
  in production in this stack.
- The Function App already had a live, observed schema-drift bug between its local copy and
  stagingDLRM's canonical schema; a second LIBRA-specific schema fork risked a third drifting
  copy of the same problem.

**Accepted trade-off:** a shared endpoint/schema couples XHIBIT's and LIBRA's blast radius — a
bug in shared validation/schema code can affect both. Mitigate by testing both source systems
on every change to shared code (stagingDLRM's existing test suite already does this today).

The opposite design — a separate LIBRA schema and a separate stagingDLRM endpoint — was the
original brief and was worked through in detail before being dropped. **§7** records what it
would have required, the five evidence-based reasons it was discarded, and what would justify
revisiting it.

**Guiding principles carried through design:**
- Follow the same pipeline architecture as XHIBIT DLRM (no new topology).
- The Azure Function App is reused, not forked — one deployment serves both source systems.
- Code stays clean/concise: prefer table-/map-driven dispatch by source system over scattered
  conditionals; only introduce a new abstraction where more than a couple of rules diverge.

## 3. System-by-system: current state and required changes

### 3.1 Azure Storage

**Current state:** blob path convention `{sourceSystem}/{batch}/{case}/{submissionId}/{fileName}`
already treats `sourceSystem` as a variable path segment, not a hardcoded value. No structural
change needed.

**Required changes:**
- Verify whether the Blob Storage EventGrid subscription has an infra-level path filter scoped
  to `XHIBIT/*` (Terraform/ARM, outside this repo) — if so, widen it. **Unverified — open
  question, see §5.**

### 3.2 Azure Function App (`stagingdlrm-azure-functions`)

**Current state:**
- `EventGridTriggerJava` validates the blob path's folder-name segment against a single
  configured value (`dlrm_folder_name` env var), exact match only. The adjacent batch-name
  check already supports a comma-separated list and a `"*"` wildcard (`dlrm_batch_name`) — the
  folder-name check does not follow that pattern yet.
- `TimerTriggerJava` validates `case.json`/`manifest.json` against one fixed local schema pair
  and POSTs to one hardcoded stagingDLRM URL, regardless of source system.
- The Function App's local schema copies (`stagingdlrm.case-submission.json`, `case-details.json`,
  etc., under `src/main/resources/`) are hand-maintained files with **no build-time link** to
  the canonical schemas in `stagingdlrm-domain-value-schema` — no shared Maven dependency, no
  catalog mechanism (unlike the WildFly side, which resolves schema `$ref`s against a
  Maven-generated catalog). This has already caused real drift: the local copy is missing
  `sendingCourt`/`cpsOrganisation`/`caseMarkers`/`dateOfSending`/`dateOfCommittal`, lacks the
  `initiationCode` enum and oucode length constraints the canonical schema has, and is
  `additionalProperties: true` (lenient) vs. the canonical schema's `false` (strict).
- Because of this drift, a payload can pass the Function App's local validation but be
  rejected by stagingDLRM's stricter canonical schema — and that rejection is **non-retryable**
  (4xx failures do not retry; only 5xx does), producing a terminal, unrecoverable failure for
  that submission. A failure caught by the Function App's own local schema also loses the case
  URN in the resulting outcome file (it hasn't been parsed yet at that point); a failure caught
  downstream at stagingDLRM keeps it.

**Required changes:**
- Extend `dlrm_folder_name` to accept a comma-separated list (mirroring `dlrm_batch_name`'s
  existing pattern) — **without** wildcard support, since folder name is the source-system
  security gate. Deployment config becomes `dlrm_folder_name=XHIBIT,LIBRA`.
- Add a LIBRA-specific local case-submission schema at this layer, and make `TimerTriggerJava`
  select the case schema by source-system token before validating (table-driven: a
  `Map<sourceSystem, {caseValidator, manifestValidator}>`-shaped lookup, not branching logic
  scattered through the class). The manifest schema, submission URL, and content type are
  **shared** across both source systems (only stagingDLRM's schema differs by source system,
  and only at this Function-App pre-validation layer — stagingDLRM itself exposes one endpoint
  regardless, per §2).
- Fix the schema-drift gap as part of this work (recommended: add
  `stagingdlrm-domain-value-schema` as a build dependency and use
  `maven-dependency-plugin`'s `unpack-dependencies` to populate the Function App's schema
  resources at build time, replacing the hand-maintained copies) — doing this before adding a
  second (LIBRA) local schema avoids creating a third independently-drifting copy.

### 3.3 stagingDLRM (`stagingdlrm-command-api`, `-command-handler`, `-domain-aggregate`,
`-domain-value-schema`, `-domain-event`, `-event-processor`)

**Current state:**
- Already largely source-system-agnostic: no production code branches on
  `migrationSourceSystemName` anywhere in the REST layer, command handler, aggregate, or event
  processor; `MigratedCaseConvertor` (event-processor → PCFDLRM) passes it through as opaque
  data. Existing unit/integration tests already exercise LIBRA successfully end to end.
- `stagingdlrm-command-api` is untyped — it forwards the raw JSON envelope to the JMS command
  queue without deserializing into a generated type. Schema validation happens automatically
  via the framework's RAML-declared schema, resolved against a build-time-generated catalog
  from `stagingdlrm-domain-value-schema`.
- `stagingdlrm-command-handler` and `MigratedCaseSubmissionAggregate` **are** hard-typed to
  generated POJOs (`MigratedCaseSubmission`/`MigratedCase`/`CaseDetails`), generated by
  `stagingdlrm-domain-event`'s `pojo-generation-plugin` (which sweeps
  `stagingdlrm-domain-value-schema`'s schemas via a classpath execution). Package names are
  derived from each schema's own `$id` URL.
- `MigratedCaseConvertor` (event-processor) does an explicit, field-by-field typed mapping from
  stagingDLRM's generated types into PCFDLRM's generated types — not a raw passthrough. No
  source-system branching in it today.
- The canonical `case-details.json` schema is currently **too strict to accept LIBRA data
  as-is**: `initiationCode` is `enum: ["O"]`, which compiles into a Java enum with a single
  legal value — any other value fails Jackson deserialization outright, not just schema
  validation. `receivingCourt` is unconditionally `required`, despite being documented as
  XHIBIT-semantic (a `sendingCourt` field, documented as LIBRA-semantic, already exists but is
  optional).
- No validation mechanism beyond JSON Schema exists yet in this module (the one non-schema
  mechanism present, `access-control-drools`, is authorization-only).
- A `MigratedCaseSubmissionRejected` domain event already exists (schema + generated POJO) but
  is **unused anywhere in the code** — not applied by the aggregate, not handled by the event
  processor. Its current shape (`caseDetails`, `createdBy`) is incomplete for a
  validation-rejection use case (no `submissionId`, no reason/error field).

**Required changes:**
- **Schema**: loosen `case-details.json` — drop the `initiationCode` enum (plain `string`
  instead), and relax `receivingCourt`'s unconditional `required` into "at least one of
  `sendingCourt`/`receivingCourt`" using the same `anyOf` combinator pattern the schema already
  uses for `dateOfCommittal`/`dateOfSending` (wrapped in `allOf`, since a JSON object can only
  declare one `anyOf` key):
  ```json
  "allOf": [
    { "anyOf": [ { "required": ["dateOfCommittal"] }, { "required": ["dateOfSending"] } ] },
    { "anyOf": [ { "required": ["sendingCourt"] }, { "required": ["receivingCourt"] } ] }
  ]
  ```
  Purely structural constraints (oucode lengths, etc.) stay as-is. No new schema file, no new
  `$id` namespace — one schema serves both source systems.
- **POJO ripple**: once `initiationCode` is a plain `String`, `MigratedCaseConvertor.buildCaseDetails()`'s
  `.getInitiationCode().name()` call needs to become `.getInitiationCode()`.
- **Command-api**: no change — already source-system-agnostic.
- **Command-handler**: add a new pluggable validation layer — a
  `MigratedCaseValidationRules` strategy per `MigrationSourceSystemName` (a small
  `Map<MigrationSourceSystemName, MigratedCaseValidationRules>` registry, mirroring the
  Function App's source-system-keyed dispatch for consistency), invoked before the aggregate
  call. Real rule content is TBD pending a LIBRA sample (§5); the extension point should be
  wired end-to-end now regardless.
- **Aggregate**: extend the dormant `MigratedCaseSubmissionRejected` event (add `submissionId`
  and a `validationErrors`/reason field) and add a new aggregate method that applies it on
  validation failure — mirroring the aggregate's existing duplicate-submission-id branch
  pattern, rather than introducing a new event type from scratch.
- **Event processor**: add a fourth `@Handles` method for the (extended)
  `MigratedCaseSubmissionRejected` event, modelled directly on the existing
  error-submission-received handler (same shared outcome-publishing helper) — so a
  validation-rule rejection surfaces to the original uploader the same way a schema rejection
  does today.

### 3.4 PCFDLRM (`cpp-context-prosecution-casefile-dlrm`)

**Current state:**
- `MigratedCaseFileHandler` (command-handler) is thin and fully source-system-agnostic —
  delegates entirely to `MigratedCaseFileAggregate`.
- PCFDLRM's **own** `case-details.json` schema is already more permissive than stagingDLRM's
  pre-fix schema: `initiationCode` is a plain string (validity enforced against reference data
  in code, not a compiled enum), and `sendingCourt`/`receivingCourt` are both optional. No
  schema-level blocker exists on this side.
- A mature, map-based validation-rule-**set**-selection mechanism already exists
  (`CcProsecutionValidationRuleProvider`), keyed by case-initiation-code-derived `CaseType`
  (`CHARGE`/`REQUISITION`/`SJP`/`SUMMONS`/`OTHER`) and by `Channel` — giving genuinely
  different rule sets per case type today. It has **no source-system axis**: all
  DLRM-migrated defendants (XHIBIT today, LIBRA eventually) route through one shared map.
- **Cross-repo dependency worth flagging explicitly**: because stagingDLRM's schema currently
  forces `initiationCode` to always be `"O"`, every migrated case today lands in PCFDLRM's
  generic default rule set. If LIBRA's real initiation code is genuinely `"S"` (Summons) or
  `"Q"` (Requisition) — the standard magistrates'-court initiation methods — then once
  stagingDLRM's schema is loosened (§3.3) to let that real value through, LIBRA cases would
  automatically route into PCFDLRM's **already-built** Summons/Requisition rule sets, with no
  PCFDLRM code change needed for that dimension. **This means LIBRA's `initiationCode` value
  must be decided jointly with the PCFDLRM/reference-data team, not by stagingDLRM alone.**
- Three existing XHIBIT-only behaviours, each a small scoped guard outside the map-based
  mechanism above:
  1. `ExhibitFiileTypeValidationRule` (materials/Court Record Sheet file-type check) — no-ops
     for any non-XHIBIT source. LIBRA materials get **zero** file-type validation today.
  2. `MigratedCaseFileAggregate`'s hearing/defendant-matching check — the underlying condition
     is computed for every case, but the resulting problem is only ever surfaced for XHIBIT.
  3. `ProsecutionCaseFileHelper.applyRuleToDefendantFields()` — defaults/normalises specific
     defendant fields (gender, language, ethnicity codes) after a validation failure, but only
     for XHIBIT.
- An unused, apparently-abandoned method (`getDlrmDefendantValidationRules()`) exists in
  `CcProsecutionValidationRuleProvider` — worth asking the PCFDLRM team whether it was early,
  unfinished LIBRA groundwork.
- Unresolved from the original brief: whether PCFDLRM assumes Crown-Court-only concepts
  (allocation decisions, jury verdicts) that a magistrates' case won't have.

**Required changes:**
- Resolve LIBRA's `initiationCode` value(s) jointly with the PCFDLRM/reference-data team (see
  cross-repo dependency above) before finalising stagingDLRM's schema relaxation.
- Decide, per each of the three existing XHIBIT-only behaviours, whether LIBRA needs
  equivalent handling, different handling, or none — pending real LIBRA sample data (§5).
  If only a few rules diverge, extend with mirror-image scoped guards (matching the existing
  idiom); if LIBRA needs a materially different rule profile across many rules, extend
  `CcProsecutionValidationRuleProvider`'s map with a source-system axis instead (matching how
  `SJP`/`SUMMONS`/etc. already get distinct rule sets).
- Confirm with the PCFDLRM team whether magistrates'-court-shaped data (plea before venue,
  single justice procedure markers, etc.) needs new modelling here.

### 3.5 Progression (`cpp-context-progression`)

**Current state:** no LIBRA-specific changes identified. Progression's own `prosecutionCase.json`
schema is already migration-aware natively — `migrationSourceSystem` is a first-class field on
its canonical case model, not injected specially for DLRM — and `CaseAggregate` already has a
migration-specific business rule (`markMigratedCaseInActive`) baked into its core
case-creation logic, applying to any migrated case regardless of source system. There is no
translation layer in Progression analogous to PCFDLRM's converter — PCFDLRM builds
Progression's own canonical types directly (both services share the `criminal-court-public-model`
library), so PCFDLRM's converter is the single translation point in the whole pipeline.

**Required changes:** none identified from static analysis. Recommend validating with a real
LIBRA case once available — specifically that `markMigratedCaseInActive` and other
migration-aware logic behave correctly for LIBRA cases, not only XHIBIT (regression risk, not
a known gap).

### 3.6 Reconciliation tooling (`tools/reconciliation/`)

**Current state:** `SOURCE="XHIBIT"` is hardcoded in `stagingdlrm-report.sh` and
`function-app-report.sh` (documented as such in the tool's own README). Not part of the Maven
build.

**Required changes:** add a `--source-system` (or equivalent) parameter to both scripts and
the SQL script, replacing the hardcoded literal — needed operationally before LIBRA batches
can be reconciled, independent of the code changes above.

## 4. Cross-cutting technical notes (apply across §3.3–§3.5)

- **Exactly two real cross-schema translations happen in the whole pipeline**:
  stagingDLRM → PCFDLRM, and PCFDLRM → Progression. Progression has no translation layer of
  its own.
- **Identifiers are re-minted at several hops, not carried through.** A defendant's UUID is
  generated once in stagingDLRM's command-handler, then **discarded and regenerated** in
  stagingDLRM's own event-processor before the payload ever reaches PCFDLRM — meaning the
  defendant ID stagingDLRM persists in its own view store never matches the ID PCFDLRM/Progression
  use for that same defendant. Only the case UUID (resolved once via `system-id-mapper`) and
  the offence UUID (minted once, at the stagingDLRM→PCFDLRM hop) survive unchanged end to end.
  Worth an explicit test case for LIBRA, not assumed safe by analogy with XHIBIT.
- **`plea`/`verdict` and `convictingCourt`/`committingCourt` are not carried from the migrated
  payload into Progression** — they're regenerated from PCFDLRM's own reference data keyed by
  offence code/ID, and silently become `null` on no match, even if the original migrated data
  had real values. Real risk for LIBRA if its offence codes aren't yet represented in
  PCFDLRM's reference data — should be an explicit acceptance-test scenario.
- **`statementOfFacts`/`statementOfFactsWelsh` narrows to a single case-level field in
  Progression**, sourced only from the first offence of the first defendant — any case with
  multiple defendants/offences carrying distinct statement-of-facts text loses all but the
  first by the time it reaches Progression. Pre-existing behaviour, not LIBRA-specific, but
  worth being aware of when validating LIBRA cases with multiple defendants.
- **A stagingDLRM-side schema rejection is terminal, not transient.** Unlike 5xx errors (which
  retry via the queue), a 4xx schema-validation failure gets zero retries and immediately
  produces a permanent failure outcome — the uploader must investigate and resubmit as a new
  submission. Relevant for LIBRA rollout: a schema gap discovered in production means failed
  submissions requiring manual resubmission, not silent automatic recovery.

## 5. Open questions / assumptions to validate

1. **No real LIBRA `case.json`/`manifest.json` sample exists yet.** The single biggest
   unknown — blocks finalising the schema relaxation (§3.3), the `initiationCode` decision
   (§3.4), and the three PCFDLRM hotspot decisions (§3.4).
2. **What should LIBRA's `initiationCode` value(s) actually be?** Directly determines which
   PCFDLRM validation rule set LIBRA cases route through (§3.4) — must be resolved with the
   PCFDLRM/reference-data team, not stagingDLRM alone.
3. **Does LIBRA always populate both `sendingCourt`/`receivingCourt`, or can it lack one?**
   Affects how far the shared schema's relaxation (§3.3) needs to go.
4. **Does the Blob Storage EventGrid subscription have an infra-level path filter** scoped to
   `XHIBIT/*` that needs widening? (Terraform/ARM, outside this repo — unverified.)
5. **Expected LIBRA batch volumes and rollout timeline** — not yet discussed; needed to size
   testing and reconciliation effort.
6. **The three PCFDLRM XHIBIT-only behaviours (§3.4)** — does LIBRA need equivalent handling,
   different handling, or none, for each?
7. **Is PCFDLRM's abandoned `getDlrmDefendantValidationRules()` stub relevant prior LIBRA
   groundwork?** — question for the PCFDLRM team, not resolvable from code alone.
8. **Does PCFDLRM assume Crown-Court-only concepts** (allocation decisions, jury verdicts)
   that magistrates' cases won't have? — carried over from the original brief, not yet
   investigated in PCFDLRM's Crown-Court-specific modules.

## 6. Suggested next steps

1. Obtain a real (even anonymised/synthetic) LIBRA `case.json` + `manifest.json` sample —
   unblocks nearly every open question above.
2. Resolve LIBRA's `initiationCode` value(s) jointly with the PCFDLRM/reference-data team,
   given the cross-repo routing dependency (§3.4).
3. Fix the Function App / stagingDLRM schema-drift gap (§3.2) as part of this work, before
   adding LIBRA's local schema — avoids creating a third drifting copy.
4. Feed this document into the SDLC pipeline (`requirements-analyst` → `architecture-designer`
   → `story-writer`) to produce enabler stories per system (§3.1–§3.6).

---

## 7. Discarded alternative — separate LIBRA schema and separate stagingDLRM endpoint

A separate-schema / separate-endpoint design for LIBRA was the **original brief**, and was
worked through in detail before being dropped mid-analysis. It is recorded here so the decision
is auditable and so the reasoning is available if anyone proposes it again.

The blow-by-blow reasoning lived in the working analysis this document replaced (its §6 the
intermediate compromise, §12.1–§12.2 the compiled-type investigation that broke it, §13 the pivot,
§16 the closing verdict). That document has been removed, so the summary below is now the record —
§7.2 onward carries its conclusions, and this section is self-contained.

### 7.1 What the alternative was

The original brief carried five constraints; #2 and #3 were **"separate schema for LIBRA
cases"** and **"separate endpoint for LIBRA in stagingDLRM"** (alongside #1 same pipeline,
#4 reuse the Function App, #5 keep the code clean and concise). Read literally, that means:

| Layer                       | What the forked design required                                                                                                                                                             |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Schema                      | A LIBRA top-level submission schema with its own `$id` path segment and folder (e.g. `.../stagingdlrm/libra/json/schemas/case-details.json`), `$ref`-ing shared value types where identical |
| Generated POJOs             | That `$id` generates a **separate Java package** (`uk.gov.moj.cpp.stagingdlrm.libra.json.schemas`) — so a separate `CaseDetails`/`MigratedCase`/`MigratedCaseSubmission` class set          |
| Command API                 | A second RAML resource + a second JMS command name — cheap here, since this layer is untyped and just forwards the raw `JsonEnvelope`                                                       |
| Command handler / aggregate | **The hard part.** Both are nominally typed to the existing generated classes, so a LIBRA-typed envelope cannot enter the existing methods                                                  |
| Event processor             | `MigratedCaseConvertor` is a field-by-field typed mapping against the *shared* types, so it only works unchanged if LIBRA data is normalised into those types first                         |
| Function App                | Route LIBRA to the second submission path/content type                                                                                                                                      |

At the handler/aggregate boundary the fork forced a choice between two unattractive options
in detail:

1. **Sibling `@Handles` typed to the LIBRA class** — which then needs its own aggregate method
   and, realistically, its own domain events: real duplication in the two layers this pipeline
   currently shares completely.
2. **Translate-then-delegate** — map the LIBRA-generated type into the existing shared types in
   one narrow method, then call the same aggregate. Confines the divergence, but means the
   "separate schema" exists only at the inbound edge and is immediately normalised away.

An intermediate compromise was also drafted and then dropped: satisfy the *letter* of
both constraints — a distinct top-level schema document and a distinct REST resource/JMS command
— while composing both from shared value schemas and delegating into the same aggregate and
domain events. The pivot went further and dropped the constraints outright rather than
compromise on them.

### 7.2 Why it was discarded

Five evidence-based reasons, not general architectural preference:

1. **The codebase was already built to share.** Before this analysis started,
   `migrationSourceSystemName` was already a JSON Schema enum of `["LIBRA", "XHIBIT"]`, and
   `case-details.json` already carried a `sendingCourt` field commented as the LIBRA court
   alongside `receivingCourt` as the XHIBIT one. No production Java branches on source system
   anywhere, and existing tests already exercise LIBRA end to end. Forking machinery the
   original author explicitly designed as shared is hard to justify.
2. **A compiled-type dead end.** `initiationCode`'s `enum: ["O"]` compiles into a Java enum
   with one legal value — a hard Jackson deserialization failure for any other value, not
   merely a validation error. Under a forked design the fix still had to land on the *shared*
   downstream type (widen the enum), producing an awkward three-part workaround: wider shared
   enum + narrower per-source inbound schema + translate-then-delegate. The shared design simply
   relaxes the one schema and the problem disappears (§3.3).
3. **The failure mode was already demonstrated in this exact codebase.** Two hand-maintained
   copies of `case-details.json` (canonical vs. the Function App's local copy) had *already*
   drifted — missing fields, weaker constraints, `additionalProperties: true` vs `false`, no
   build-time reconciliation — and that drift can produce a terminal, non-retryable rejection
   (§3.2). A third LIBRA-specific fork was a near-certain repeat of an observed bug, not a
   hypothetical risk.
4. **A working precedent exists one hop downstream, built by another team.** PCFDLRM already
   solves this same problem the shared way: one schema/endpoint, a map-based rule-set provider
   for cases needing a different rule profile, and small scoped guards for the few rules that
   don't (§3.4). The shared-with-strategies approach is the established production idiom
   immediately adjacent to this code.
5. **One code path to reason about, test, and monitor.** One aggregate, one set of domain
   events, one converter into PCFDLRM — rather than two parallel command/event families whose
   behaviour must be kept consistent by discipline, forever, on every future change.

### 7.3 What the alternative was genuinely right about

The asks behind constraints #2/#3 — isolation, independent evolution, and per-stream
observability/access control — are legitimate, and the shared design does not deliver them for
free. The accepted trade-off (§2) is stated plainly: **a shared endpoint and schema couple
XHIBIT's and LIBRA's blast radius.** A bug in shared validation or schema code can affect both
streams at once, where a separate endpoint would have contained it to one. Because no real LIBRA
sample exists yet (§5 Q1), LIBRA's shape may keep moving for a while, and every change made to
accommodate it touches code XHIBIT already depends on in production.

Mitigations relied on instead of isolation: test both source systems on every change to shared
validation/schema code (the existing suite already does this), keep source-system divergence
inside the keyed strategies rather than letting it leak into shared code paths, and note that
submissions remain distinguishable by `migrationSourceSystemName` for observability and
reconciliation purposes.

### 7.4 Scope of the discard, and what would justify revisiting

**The discard applies to stagingDLRM's canonical schema and REST endpoint only.** The Function
App legitimately *keeps* per-source-system schemas at its pre-validation layer (§3.2) — that is
the one place where "separate schema" survives, selected by strategy from the single normalised
canonical source. One endpoint downstream, two normalised schema variants at the edge.

Reasons to reopen the decision:
- A real LIBRA sample turns out to be **structurally** divergent rather than differing in a few
  fields — i.e. the shared schema would have to be relaxed so far that it stops meaningfully
  constraining XHIBIT. Note the relaxation in §3.3 already weakens two XHIBIT constraints
  (`initiationCode` enum, unconditional `receivingCourt`); that is acceptable at this scale, but
  it is the dimension to watch.
- LIBRA needs a materially different rule profile across *many* PCFDLRM rules rather than a
  handful — already anticipated in §3.4 as the trigger for adding a source-system axis to
  `CcProsecutionValidationRuleProvider`, which is the shared-design answer to that pressure
  short of forking.
- An operational requirement emerges for genuinely independent throttling, access control, or
  incident containment per migration stream — the one need a shared endpoint cannot satisfy.
