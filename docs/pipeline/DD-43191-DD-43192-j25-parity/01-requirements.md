# Requirements — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> Requirements altitude — nothing here prescribes a class layout or a test-class name. Implementation
> **tasks** come from the design / story-writer stage.
>
> **Scoped to `cpp-context-stagingdlrm`, branch `team/25.104.x` while it is still J17.** The PCFDLRM half is
> [DD-43194](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43191-DD-43194-j25-parity).
> Method and scope are fixed by [the parity-method ADR](../adrs/DD-43191-j25-parity-method.md), including
> its **decision 8** (BC-11 corrected) — this document is written against the corrected finding from the
> start, not discovered mid-implementation.

## Story

**[DD-43192](https://tools.hmcts.net/jira/browse/DD-43192) — Pin stagingDLRM's J17 behaviour at the
seams the Java 25 upgrade will move**

| | |
|---|---|
| Epic | [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191) — Java 25 upgrade, DLRM contexts |
| Size | **M** |
| Repo | `cpp-context-stagingdlrm` |
| Target branch | **`team/25.104.x`**, cut from `main` before this story starts and not yet upgraded — so still `service-parent-pom 17.104.1` on JDK 17 |
| Depends on | [the parity-method ADR](../adrs/DD-43191-j25-parity-method.md) accepted before stage 5. No other blocker — can start immediately |
| Blocks | [DD-43192 upgrade stage](../DD-43191-DD-43192-j25-upgrade/00-input-brief.md) — same branch; that story may not start until this one merges |
| Sibling story | [DD-43194](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43191-DD-43194-j25-parity) — same stage in PCFDLRM, independently deliverable |
| Production changes | **none expected** — test, fixture and documentation only. See FR15 |
| Platform tickets | PEG-3296 (upgrade), **PEG-3377** (parity testing) |

### Summary (JIRA summary line)

`[Java 25] Pin stagingDLRM J17 behaviour: Function App schema-validation parse strictness, JsonObjectBuilder null-value parity, access-control rule coverage, codegen and deploy-time guards`

### User story

As a **developer who will shortly move stagingDLRM to Java 25, WildFly 40 and Jakarta EE 11**,
I want **the behaviours that the upgrade's library bumps are known to move to be asserted against the
current Java 17 stack, executed green on Java 17, and merged to `team/25.104.x` before anything on that
branch is upgraded**,
so that **the upgrade branch inherits a regression gate that fails loudly if a behaviour shifts,
instead of a suite that keeps passing because none of the framework's own code recompiled**.

## Depth model

| Tier | Depth | Rationale |
|---|---|---|
| **Unit / component** | Originally **exhaustive** for both primary items (BC-13, DLRM-01): every input class the validator treats differently, accept *and* reject. Both tables were built, run green, and withdrawn (FR5, FR6) — neither primary item has a test any more. Sufficient-branch coverage for the rest still stands. | Fast, in `mvn test`, no environment. The right place for an input matrix, when one exists. |
| **Build-time assertion** | Originally **a single decisive check** per item (BC-11, BC-12, BC-21's `messaging-client-generator-plugin` sub-item). All three were built and run green, then withdrawn — BC-07 never had one to begin with. No item in this tier carries a test any more; see FR8/FR11/FR12/FR13, each withdrawn for its own distinct reason. | These are packaging and code-generation facts, not runtime behaviour; a test that boots a container to observe them is the wrong instrument. |
| **Integration** | **Authored, not executed.** Any IT-tier item is written and marked 🟡 until Docker and a WildFly image are available. | ITs need `CPP_DOCKER_DIR`; no WildFly 40 image existed as of the investigation report. Blocking this story on that would block the whole epic. |

## Scope

- `stagingdlrm-domain/stagingdlrm-domain-value-schema` — **out of scope.** BC-13's catalogue-tier test
  was built here, ran green on J17, then removed per FR5's revision; the module has zero Java again and
  no code change is expected in it for this story.
- `stagingdlrm-azure-functions` — **out of scope for new code.** The networknt/Jackson gate (DLRM-01),
  BC-11 (corrected) and BC-12 each had a test built here, run green on J17, then withdrawn (FR6, FR8,
  FR11) — the module carries no test-scope change for this story any more. `docs/architecture/dlrm-flow-reference.md`
  §2.3–§2.4 and §6 remains the seam map for the record: `JsonSchemaValidator`
  validates `case.json`/`manifest.json` against `stagingdlrm.case-submission.json` /
  `stagingdlrm.manifest.json`; `StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload` (§2.4,
  §5) is BC-11's real call site
- `stagingdlrm-command/stagingdlrm-command-api` — access-control DRL and its harness (BC-03, BC-20).
  `docs/architecture/material-file-flow.md` §3 confirms `stagingdlrm.receive-migrated-case-submission` is
  system-user-only ACL; the untested rule gates the sibling error-path command
- `stagingdlrm-viewstore/stagingdlrm-viewstore-liquibase` — `liquibase.properties` (BC-07)
- `stagingdlrm-event/stagingdlrm-event-processor` — the single `ZonedDateTime` site (BC-08)
- Module POMs — `javax.json` coordinate inventory (background only, see FR8) and generator-plugin
  configuration (BC-21)
- `docs/j25-parity-checklist.md` — new

Out of the module scope entirely: `stagingdlrm-testharness`, `stagingdlrm-performance-test`,
`stagingdlrm-viewstore-persistence` (no Java), `stagingdlrm-query` and `stagingdlrm-event-listener`
(no Java).

## Requirements

### A. Method — binding on every item below

- **FR1 — Authored on J17 *and executed* on J17.** Every parity test is written against the
  **pre-upgrade `team/25.104.x`** stack — which is `main`'s, byte for byte: `service-parent-pom
  17.104.1`, JDK 17, `centos8-j17` — and **run**. A test that has not been executed on J17 is marked 🟡 and does not count
  toward this story's completion. Where a J17 run contradicts a source document, the test pins
  the **observed** behaviour and the contradiction is written into the checklist row — the run outranks
  the report. *(This has already happened once for this exact story: BC-11's original hypothesis was
  itself superseded by a later, corrected fleet-wide run — see FR8.)*
- **FR2 — Every parity test names its item.** A one-line reference to its BC or DLRM identifier, and
  what is expected to move, on the test itself. An unlabelled parity test is indistinguishable from a
  redundant one and will be deleted by the next person.
- **FR3 — Scope is closed.** The items in play are exactly the parity-method ADR's Bucket A for this repo: **BC-03,
  BC-07, BC-08, BC-11, BC-12, BC-13, BC-20, BC-21, DLRM-01**. Adding an item requires the parity-method ADR amended,
  not a local decision. Writing a test for an N/A item — in particular anything in the persistence
  cluster, which has no code to bind to — is a defect in this story, not extra value.
- **FR4 — Tests use J17 idiom.** `javax` imports, no `jakarta`, no J25-conditional branches, no
  `@EnabledOnJre`. The upgrade story migrates them like any other source file; a test that already
  straddles both stacks pins neither.

### B. The two primary items

- **FR5 — BC-13: record, do not author.** *(Revised 2026-09-07 — a unit test was authored, run green
  on J17, and then deliberately removed; see `docs/j25-parity-checklist.md`'s BC-13 note.)* The `org.json`
  20231013→20251224 and everit consolidation move underneath the runtime catalogue validator, and
  `case-details.json`/`migrated-hearing.json` are real, code-verified binding sites
  (`docs/architecture/dlrm-flow-reference.md` §6). The decision taken: the schema `.json` files and their
  content are not changing during the J25 upgrade, so there is no live scenario for a
  numeric-literal/accept-reject test to catch here — the same reasoning FR12 already applies to
  `catalog-generation-plugin`. Record the seam, the fact that a test existed and passed on J17, and this
  reasoning in the checklist; do not carry a numeric-literal table or `ClasspathSchemaClient`-style
  `$ref` resolver for this item.
- **FR6 — DLRM-01: recorded, not pinned by a test.** *(Revised 2026-09-07 — a full test suite was
  authored, run green on J17, and then withdrawn by explicit instruction: "this is not part of the java
  25 upgrades." Recorded plainly: unlike BC-11's FR8, DLRM-01's Jackson-version premise was never
  actually confirmed to diverge on J25 in this repo — only J17 was run, which is this story's own
  author-then-confirm-at-upgrade method, not a completed before/after comparison.)* The gate's schema
  library (`com.networknt:json-schema-validator` 1.0.83) is hard-pinned and does **not** move; Jackson
  (2.12.7→2.21.4) does, behind `ObjectMapper.readTree` (`dlrm-flow-reference.md` §2.3 step 3d, §5's
  `JsonSchemaValidator` row) — the seam remains real and code-verified even with no test pinning it.
- **FR7 — withdrawn.** *(Revised 2026-09-07.)* Originally required DLRM-01's and BC-13's numeric-literal
  tables to be asserted separately rather than shared. Both tables have since been removed (FR5, FR6);
  the finding that they genuinely diverged on several literals while both existed is recorded as
  historical context in the checklist's "Notable J17 findings," not as a live requirement.

### C. The remaining items

- **FR8 — BC-11: recorded, not pinned by a test.** *(Revised 2026-09-07 — a test was authored, run
  green on J17, and then withdrawn by the same instruction as FR6. Distinct reasoning from FR6, though:
  BC-11's corrected finding — see below — is itself classified "Refuted / parity," so its test was
  arguably never a candidate to catch a J25 upgrade difference in the first place, only a pre-existing,
  JDK-independent contract.)* The original 24-BC catalogue and this repo's earlier planning both
  characterised BC-11 as a JSON-P `ServiceLoader` provider collision, provable by a classpath-resource
  count across the modules declaring `javax.json` coordinates. **`Parity+Testing+Java17+-_+Java25.pdf`'s
  BC-11 entry, marked "CORRECTED 2026-08-26", supersedes that hypothesis** (parity-method ADR decision
  8): the verified mechanism is that
  `uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder().add(key, null)` throws
  `NullPointerException` identically on J17/glassfish and J25/Parsson — a pre-existing latent-bug parity,
  not a J25 regression. This repo has a real, concrete binding site for the *corrected* mechanism:
  `StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload` (`dlrm-flow-reference.md` §2.4,
  §5) builds its payload with exactly this framework helper and adds a `responseString` parameter as
  `"errorMessage"` — a value reachable as null on the error path (§2.6's Path 3, the direct-write outcome
  case). The `javax.json` coordinate/provider inventory the original hypothesis would have required
  remains **not** the right instrument regardless of whether this item ends up tested or recorded.
- **FR9 — BC-03: close the access-control branch gap.** `command-migrate-case-submission-api.drl`
  declares **two** rules; `receive-error-migrated-case-submission` has never been tested on any JDK.
  This is the rule gating the error path traced in `dlrm-flow-reference.md` §2.5/§2.6 (schema-validation
  failure, material-count mismatch, or 4xx-on-main-POST all route here). Both rules must have an
  **allow** and a **deny** case. This is a genuine J17 coverage fix as well as a parity pin — note that
  BC-03 *itself* (Drools recompilation flipping allow/deny) is independently **Refuted** by both the
  investigation report and the fleet-wide guide; this requirement's value is the coverage gap, not risk
  mitigation.
- **FR10 — BC-20: prove the rule harness is not vacuous.** Assert a **non-zero loaded rule count** for
  the command-API knowledge base. Without it, a J25 zero-rule load presents as a passing deny test and
  is indistinguishable from a BC-03 allow/deny flip.
- **FR11 — BC-12: recorded, not pinned by a test.** *(Revised 2026-09-07 — a build-time assertion was
  authored, run green on J17, and then withdrawn by explicit decision — not because the risk was found
  absent; see `docs/j25-parity-checklist.md`'s BC-12 note.)* The func-app carries four compile-scope
  RESTEasy artifacts and, unlike a WAR, has no container to supply them (`dlrm-flow-reference.md` §2
  confirms the func-app is a "standalone JAR — runs outside the WildFly/JMS stack"; `StagingDlrmCommandHelper`
  genuinely builds a JAX-RS `Client` via `ClientBuilder` to POST to `stagingdlrm-command-api`, so this is
  live production code, not a theoretical risk). The fleet-wide `provided` + `packagingExcludes` fix
  would compile cleanly and then fail at runtime in Azure with `NoClassDefFoundError` if applied here.
  See [the upgrade-mechanics ADR](../adrs/DD-43191-j25-upgrade-mechanics.md) decision 5 — **its carve-out
  is now the only safeguard**; nothing in this repo's test suite catches a violation of it.
- **FR12 — BC-21: pin the generated-artefact inventory, where the `reflections` premise actually
  applies.** *(Revised 2026-09-07 — the `messaging-client-generator-plugin` sub-item was authored, run
  green, and then withdrawn: re-verifying its own premise by decompiling the generator's full dependency
  chain found zero use of `org.reflections` anywhere in it, unlike `catalog-generation-plugin` where the
  library is genuinely bundled and called. See `docs/j25-parity-checklist.md`'s BC-21 note.)* All four
  generators run in this repo (pojo, catalog, messaging-client, rest-client) plus RAML, but the
  `reflections` 0.9.10→0.10.2 scanning-contract change this requirement targets only actually applies to
  `catalog-generation-plugin`'s file-discovery mechanism — confirmed by decompilation, not assumed
  uniform across all four. No sub-item of BC-21 carries a test any more; each is unpinned for its own
  distinct, recorded reason.
- **FR13 — BC-07: record the Liquibase property-set risk; do not author a unit test that doesn't
  actually pin it.** Liquibase 4→5 rejects properties it removed, as a pre-install migration-job
  failure — a deploy blocker, not a behaviour change, and genuinely live here: `liquibase.properties`
  is bundled into `stagingdlrm-viewstore-liquibase.jar` and executed by `docker/scripts/liquibase.sh` at
  container startup. A plain `Properties.load()`-based unit test only proves the file's key set, which
  is identical on J17 and J25 regardless of Liquibase's own version — it does not exercise Liquibase's
  property-validation logic, so it cannot actually catch the divergence. The only test that would mean
  anything here needs Liquibase itself to run, which is IT-tier per this story's own depth model.
  Record the risk, the key set, and the reasoning in the checklist as a Bucket-B-style check; do not
  author a unit test that reads as a pin but doesn't function as one.
- **FR14 — BC-08: record, do not author, and do not touch unrelated code to do it.** The repo's only
  `ZonedDateTime` is in an event-processor **test helper**, and it is never serialized through Jackson
  anywhere in this repo's own tests either — there is no incidental J17 coverage to annotate.
  Authoring a parity test around a test helper would pin the fixture, not the product; adding an
  in-code comment to a file this story otherwise makes no change to is noise on unrelated code, not a
  pin. Record the finding (📝) and its reasoning in `docs/j25-parity-checklist.md` only.

### D. Recording, and boundaries

- **FR15 — A live J17 defect is raised, not fixed here.** If a parity test reveals a defect on the
  current stack, the test pins the **observed** behaviour, the defect is recorded in the checklist and
  raised as its own ticket, and this PR does not fix it. Rationale: the parity PR's value is that it is
  reviewable as "pins existing behaviour"; mixing behaviour changes into it destroys that property.
  (FR9's added coverage is not an exception — it adds tests, it does not change a rule.)
- **FR16 — Bucket B items produce a recorded check, not a test.** BC-14 (`beans.xml`
  `bean-discovery-mode`), BC-15 (core-domain field availability), BC-16 (`/internal/metrics`),
  BC-17 (`stream_error` identity) are framework-owned. Each gets a checklist row stating what was
  checked, the result, and why no context-level test follows. A reviewer must be able to see that the
  absence of a test was a decision.
- **FR17 — `docs/j25-parity-checklist.md` is a deliverable, not a by-product.** One row per Bucket A
  and Bucket B item, carrying the parity-method ADR's legend (🟡 authored-not-executed · 🟢 executed green on J17 ·
  🔴 gap · ⬜ N/A · 📝 existing test annotated), the J17 run evidence for every 🟢, and an explicit
  gaps section — including which source (investigation report vs. fleet-wide guide) each row's finding
  came from where the two disagree.
- **FR18 — Nothing in this story touches the build's Java target, the parent pom or the CI agent.**
  No version bump, no `jakarta` rename, no change to `azure-pipelines.yaml`'s `centos8-j17` demand.
  Those belong to the upgrade story. **On this branch layout that is a correctness requirement, not
  tidiness:** the branch is only J17 evidence for as long as nothing has upgraded it, so a stray pom
  bump here would silently invalidate every run this story produces.

## Acceptance criteria

- **AC1** — Each of the nine Bucket A items has either a test executed green on J17 (🟢), or a
  checklist row explaining why it is 📝, ⚪ or 🔴, with a named reason. No item is silently absent.
- **AC2** — `mvn clean install -DskipITs` passes on `main` with JDK 17, with every new test executing
  (not skipped, not disabled).
- **AC3** — **Withdrawn.** *(Revised 2026-09-07 — required a DLRM-01 numeric-literal table; that table
  was authored, run green, and removed. See FR6.)*
- **AC4** — Both rules in `command-migrate-case-submission-api.drl` have a passing allow case and a
  passing deny case, and the command-API knowledge base asserts a non-zero rule count.
- **AC5** — **Withdrawn.** *(Revised 2026-09-07 — required a BC-11 test pinning the corrected
  `JsonObjectBuilder` null-value NPE parity; that test was authored, run green, and removed. See FR8.)*
- **AC6** — **Withdrawn.** *(Revised 2026-09-07 — required the Function App gate's accept/reject paths
  pinned; DLRM-01's test suite covering this was authored, run green, and removed. See FR6.)*
- **AC7** — `docs/j25-parity-checklist.md` exists, covers every BC-01..BC-24 plus DLRM-01 with a
  legend mark, and records the exact command and result for every 🟢.
- **AC8** — Every new test names its BC/DLRM item.
- **AC9** — `git diff main` for this PR contains no change under `src/main` except generated-code or
  documentation, and no pom version change. (FR15, FR18.)
- **AC10** — Any J17 run that contradicts a source document is recorded in the checklist with both the
  claim and the observed behaviour, and which source (investigation report vs. fleet-wide guide) is
  superseded.

## Out of scope

- Any Java 25, WildFly 40 or Jakarta EE 11 change — the upgrade story, DD-43192-j25-upgrade.
- Cutting `team/25.104.x` — done before this story starts.
- The persistence cluster (BC-01, BC-02, BC-04, BC-05, BC-06, BC-24) — no JPA entities or repositories
  exist in this repo, so there is nothing to pin.
- BC-09/BC-10 (no Activiti), BC-18 (no `ActiveMQConnectionFactory`), BC-19 (SJP-specific),
  BC-22 (no Tika), BC-23 (no Quartz).
- `cpp-context-prosecution-casefile-dlrm` — DD-43194, its own pipeline.
- Framework and platform repositories — PEG-3296 owns those.
- Fixing any live J17 defect this story surfaces (FR15).
- Executing IT-tier items to green — authored-only until a WildFly 40 image exists.
- Anything past `receive-migrated-case-file` to pcfdlrm — `material-file-flow.md`'s cross-context trace
  (Material, Alfresco, Progression) is architectural context for this story, not code this repo owns.

## Risks and notes

- **BC-12 is an unguarded, verified-real risk, not a theoretical one dropped for lack of exposure.**
  Unlike BC-13/BC-21's catalog test, FR11's removal (2026-09-07) was a deliberate decision made *after*
  confirming the risk is live: `StagingDlrmCommandHelper` genuinely depends on the bundled RESTEasy
  artifacts at runtime, and the upgrade-mechanics ADR's decision 5 documents a fleet-wide sweep that
  would silently break this module if applied here. The only remaining safeguard is that decision being
  read and followed by whoever performs the upgrade — flag this explicitly to whoever picks up the
  DD-43192 upgrade stage.
- **A source document can itself be superseded, and this story has already lived through it once.**
  BC-11's treatment changed between the original investigation report and the later fleet-wide guide.
  Treat every BC entry as a hypothesis to verify against this repo's actual code, not a fact to
  transcribe — and prefer the fleet-wide guide's dated corrections over the original report's verdict
  wherever the two disagree.
- **DLRM-01 had no precedent anywhere in the fleet, which is part of why it was withdrawn rather than
  kept.** *(Revised 2026-09-07 — DLRM-01's test suite was built, run green on J17, then removed; this
  entry is kept as the reasoning that made the withdrawal an easier call, not as live scope.)* No other
  CPP context has an Azure Functions module (confirmed against the 06 Aug 2026 tracker — `stagingdlrm`
  is the only Azure Functions line item), so no other parity story had pinned a networknt/Jackson gate;
  there was no reference implementation to copy and no reviewer with prior experience of it. Confirmed
  directly, not just inferred from the tracker: reading the 13 fleet PRs cited as completed
  (`notification`#38, `notification-notify`#44, `system-id-mapper`#27, `system-scheduling`#22,
  `system-announcement`#14, `system-doc-generator`#590, `hearing`#293, `listing`#106,
  `mi-reportdata`#587, `work-management-proxy`#29, `businessprocesses`#77, `resulting`#123,
  `users-groups`#217), none attempts a schema-validation numeric-literal matrix at any depth — the
  deepest of them (`users-groups`#217) covers persistence, access-control branch gaps and one Jackson-zone
  test. **The withdrawal brings this story's scope back in line with the fleet baseline** rather than
  leaving it a real outlier: 11 of the 13 PRs are a single ~26-line `AccessControlRuleCountTest` and
  nothing else, and `users-groups`#217's own checklist puts BC-07/11/12/13/21 in its *own* "Bucket B —
  verify once, no per-context test" — the same landing spot this story ended up at for those same items,
  by a different route (built, run green, then withdrawn, rather than never attempted).
- **BC-11's assertion was about a real call site, which is easy to get wrong if copied from another
  repo without checking — this still matters even though the test itself was withdrawn.** The parity-method
  ADR's decision 8 explicitly warns pcfdlrm's own story not to assume the same call-site shape applies
  there; that warning stands regardless of whether this repo carries a test for it.
- **BC-21's inventory assertion risks becoming a maintenance burden.** A hard-coded list of every
  generated type will be edited by every future schema change. The design stage should prefer an
  assertion over the generator's *contract* (a count, or the presence of the types a named schema
  should yield) to a literal manifest.
- **The `centos8-j17` agent is a dependency of this story, not an incidental.** If the platform retires
  it before this story merges, the J17 execution evidence FR1 requires becomes unobtainable and the
  ordering in the upgrade-mechanics ADR decision 1 has to be revisited.
- **These tests will not exist on `main`.** Both stages live on `team/25.104.x`, so the shipping J17
  line does not get them. One item is worth cherry-picking back regardless of the J25 timeline: FR9's
  BC-03 branch-gap fix, because `receive-error-migrated-case-submission` has never been tested on any
  JDK. See the upgrade-mechanics ADR decision 1.
- **Owner unassigned.** stagingdlrm shows owner "?" on the 06 Aug 2026 PEG-3296 tracker snapshot.
  Confirm with Platform Engineering that DLRM is genuinely unowned rather than queued behind their
  completed contexts.

## Notes for the design stage

1. **BC-13: FR5 no longer requires a table — do not rebuild one by default.** *(Revised 2026-09-07.)*
   A catalogue-tier harness was built (in `domain-value-schema`, zero Java before it), ran green on
   J17, and was removed as a deliberate scope decision (see FR5). If a future pass reopens BC-13, the
   prior implementation's approach is recorded in git history (a `ClasspathSchemaClient` resolving
   `$ref`s via the module's own generated `META-INF/schema_catalog.json`) rather than needing
   re-deriving from scratch — but building it back by default, without re-confirming the reasoning in
   FR5 no longer holds, would silently re-widen scope this story deliberately narrowed.
2. **DLRM-01's seam question is moot now — recorded for history only.** *(Revised 2026-09-07.)*
   `JsonSchemaValidator` takes an `ExecutionContext`, so the gate was testable only as far as that mock
   allowed; the suite that was built extended `JsonSchemaValidatorTest` directly rather than going via
   `TimerTriggerJava`, ran green, and was then withdrawn (FR6). If a future pass reopens DLRM-01, this
   was the seam choice that worked — don't re-derive it, but do re-confirm FR6's reasoning still holds
   before rebuilding on it.
3. **FR11, FR12 and FR13 all ended up unpinned by a test, for different reasons — don't conflate them.**
   FR13 (BC-07) has no test because no unit-tier instrument can actually catch its risk. FR11 (BC-12) had
   a working instrument (a `pom.xml` DOM-parsing unit test, run green) that was withdrawn by explicit
   decision despite the risk being real and verified. FR12's `messaging-client-generator-plugin` sub-item
   had a working instrument too (a reflection-based method-count test, run green), withdrawn because
   re-verifying its stated premise found the generator never actually used `org.reflections` at all — a
   third reason again, distinct from both FR11's and FR13's. If BC-12 is ever revisited, the instrument
   choice from its removed test — a unit test, not a `maven-enforcer` rule or script — is still the right
   answer; only FR13 needed the "no unit-tier instrument exists" reasoning, and only FR12's
   messaging-client sub-item needed the "premise doesn't apply to this generator" reasoning.
4. **FR8's call site was verified fresh at the time — re-verify again if BC-11 is reopened.** Code moves
   between stage 1 and stage 4; the test that was built confirmed
   `StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload` still built its payload via
   `JsonObjects.createObjectBuilder()` with a nullable value, ran green, and was then withdrawn (FR8) —
   don't assume that confirmation still holds without checking again.
5. **Neither primary item has a test any more — this repo has no "primary item" left to protect.**
   *(Revised 2026-09-07.)* BC-13 (FR5) and DLRM-01 (FR6) were both built, run green, and withdrawn; so
   was BC-11 (FR8) and, most recently, BC-21's `messaging-client-generator-plugin` sub-item (FR12) — the
   one remaining item this note previously pointed to as still tested. If the story is cut further, there
   is no primary-item test left to sequence first or protect by comparison — BC-03 and BC-20 (FR9/FR10)
   are now the *only* Bucket A items this story actually leaves behind as 🟢.
6. **Read `docs/architecture/dlrm-flow-reference.md` before scoping any Function App test.** It is the
   single most detailed map of exactly which class does what at each processing stage, including line
   citations, and prevents re-deriving facts (schema file names, retry/outcome-write branching) that are
   already documented.
7. **Name the BC-20 test `AccessControlRuleCountTest`, not a BC-numbered name.** Every one of the 13
   fleet PRs read for this story that includes a BC-20 guard uses this exact class name (confirmed from
   `system-id-mapper`#27's real merged file), with a single
   `kieBaseShouldCompileAtLeastOneRule()`-style test asserting
   `KieServices.get().getKieClasspathContainer().getKieBase(name).getKiePackages()...sum() > 0`. This
   requirements document does not prescribe class names as a rule (see the header note), but this one
   naming choice is worth calling out explicitly: a reviewer familiar with the fleet's other ~12 parity
   PRs will look for this exact name, and a differently-named class doing the same job reads as a
   bespoke, unreviewed pattern rather than the fleet's own established one.
