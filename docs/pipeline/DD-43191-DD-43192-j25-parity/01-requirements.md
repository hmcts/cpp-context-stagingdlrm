# Requirements — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> Requirements altitude — nothing here prescribes a class layout or a test-class name. Implementation
> **tasks** come from the design / story-writer stage.
>
> **Scoped to `cpp-context-stagingdlrm`, branch `team/25.104.x` while it is still J17.** The PCFDLRM half is
> [DD-43194](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43191-DD-43194-j25-parity).
> Method and scope are fixed by [the parity-method ADR](../adrs/DD-43191-j25-parity-method.md); this
> document does not restate them, it makes them testable.

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

`[Java 25] Pin stagingDLRM J17 behaviour: schema-validation strictness at both validator tiers, JSON-P provider resolution, access-control rule coverage, codegen and deploy-time guards`

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
| **Unit / component** | **Exhaustive** for the two primary items (BC-13, DLRM-01): every input class that the parser or validator treats differently, accept *and* reject. Sufficient-branch for the rest. | Fast, in `mvn test`, no environment. The right place for an input matrix. |
| **Build-time assertion** | **Single decisive check** per item (BC-11, BC-12, BC-21, BC-07). | These are packaging and code-generation facts, not runtime behaviour; a test that boots a container to observe them is the wrong instrument. |
| **Integration** | **Authored, not executed.** Any IT-tier item is written and marked 🟡 until Docker and a WildFly image are available. | ITs need `CPP_DOCKER_DIR`; no WildFly 40 image existed as of the investigation report. Blocking this story on that would block the whole epic. |

## Scope

- `stagingdlrm-domain/stagingdlrm-domain-value-schema` — the everit/`org.json` catalogue tier (BC-13)
- `stagingdlrm-azure-functions` — the networknt/Jackson gate (DLRM-01), plus BC-11 and BC-12 surface
- `stagingdlrm-command/stagingdlrm-command-api` — access-control DRL and its harness (BC-03, BC-20)
- `stagingdlrm-viewstore/stagingdlrm-viewstore-liquibase` — `liquibase.properties` (BC-07)
- `stagingdlrm-event/stagingdlrm-event-processor` — the single `ZonedDateTime` site (BC-08)
- Module POMs — `javax.json` coordinate inventory and generator-plugin configuration (BC-11, BC-21)
- `docs/j25-parity-checklist.md` — new

Out of the module scope entirely: `stagingdlrm-testharness`, `stagingdlrm-performance-test`,
`stagingdlrm-viewstore-persistence` (no Java), `stagingdlrm-query` and `stagingdlrm-event-listener`
(no Java).

## Requirements

### A. Method — binding on every item below

- **FR1 — Authored on J17 *and executed* on J17.** Every parity test is written against the
  **pre-upgrade `team/25.104.x`** stack — which is `main`'s, byte for byte: `service-parent-pom
  17.104.1`, JDK 17, `centos8-j17` — and **run**. A test that has not been executed on J17 is marked 🟡 and does not count
  toward this story's completion. Where a J17 run contradicts the investigation report, the test pins
  the **observed** behaviour and the contradiction is written into the checklist row — the run outranks
  the report. *(The reference context had exactly this happen: three BC-04 tests asserted the report's
  "Hibernate 5 silently coerces NULL→0" and a real J17 run threw instead.)*
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

- **FR5 — BC-13: pin schema-validation strictness at the catalogue tier.** The `org.json`
  20231013→20251224 and everit consolidation move underneath `SchemaMatchers` and the framework's
  runtime catalogue validator. Pin, for the migrated-case-submission schema set:
  1. a **numeric-literal table** with named expected outcomes per input — at minimum `0`, `007`, `01`,
     `.5`, `10.0`, `1e3`, `12345678901234567890` — as the report's §2 recommendation specifies;
  2. the **accept path** for a valid payload, and the **reject path** with its validation message, for
     each constraint class the schema uses (type, enum, required, format, `anyOf`);
  3. the distinction between a **parse** failure and a **validation** failure as two different
     outcomes. This is where BC-13's "uncaught exception becomes an HTTP 500" shape lands if it lands
     anywhere, so it must be asserted, not inferred.
- **FR6 — DLRM-01: pin parse behaviour at the Function App gate.** The gate's schema library
  (`com.networknt:json-schema-validator` 1.0.83) is hard-pinned and does **not** move; Jackson
  (2.12.7→2.21.4) does, behind `ObjectMapper.readTree`. Pin the gate's observed J17 outcome for:
  malformed JSON; the **array-payload rejection** the validator performs before schema validation;
  duplicate object keys; and the same numeric-literal set as FR5. Cover **both source systems** — the
  gate's validators are source-system-keyed ([the parity-method ADR](../adrs/DD-43191-j25-parity-method.md) decision 7 — the gate is **not** source-system-keyed on this branch),
  so a LIBRA-only or XHIBIT-only pin leaves half the gate unpinned.
- **FR7 — FR5 and FR6 are separate tables over separate parsers.** They may additionally be asserted
  to agree with each other, but a single shared table standing in for both is not acceptable: the two
  tiers use different libraries with different upgrade exposure, and a shared table hides which one
  moved.

### C. The remaining items

- **FR8 — BC-11: pin JSON-P provider resolution.** Six `javax.json` coordinates exist across
  command-handler, event-listener, domain-event, domain-aggregate and the func-app (the last on
  `org.glassfish:javax.json:1.0.2`). Assert that **exactly one** JSON-P provider is resolvable on each
  affected module's classpath, and **which** one. The J25 failure mode is a `ServiceLoader` collision
  between glassfish and Parsson, so a count of one is the load-bearing assertion.
- **FR9 — BC-03: close the access-control branch gap.** `command-migrate-case-submission-api.drl`
  declares **two** rules; `receive-error-migrated-case-submission` has never been tested on any JDK.
  Both rules must have an **allow** and a **deny** case. This is a genuine J17 coverage fix as well as
  a parity pin.
- **FR10 — BC-20: prove the rule harness is not vacuous.** Assert a **non-zero loaded rule count** for
  the command-API knowledge base. Without it, a J25 zero-rule load presents as a passing deny test and
  is indistinguishable from a BC-03 allow/deny flip.
- **FR11 — BC-12: pin the Function App's RESTEasy packaging expectation.** The func-app carries four
  compile-scope RESTEasy artifacts and, unlike a WAR, has no container to supply them. Record — as a
  build-time assertion, not prose — that these must remain bundled, so the fleet-wide
  `provided` + `packagingExcludes` fix cannot later be applied here and turn into a runtime
  `NoClassDefFoundError` in Azure. See [the upgrade-mechanics ADR](../adrs/DD-43191-j25-upgrade-mechanics.md)
  decision 5.
- **FR12 — BC-21: pin the generated-artefact inventory.** All four generators run in this repo
  (pojo, catalog, messaging-client, rest-client) plus RAML. The `reflections` 0.9.10→0.10.2 scanning
  contract change alters what is discovered. Assert the **set of generated types** the build is
  expected to produce, so a silently smaller set fails rather than surfacing as a missing bean later.
- **FR13 — BC-07: pin the Liquibase property set.** Liquibase 4→5 rejects properties it removed, as a
  pre-install migration-job failure — a deploy blocker, not a behaviour change. Assert the key set
  present in `liquibase.properties` so an unsupported key is caught in `mvn test`, not in a K8s job.
- **FR14 — BC-08: annotate, do not author.** The repo's only `ZonedDateTime` is in an event-processor
  **test helper**. Annotate the existing coverage as already pinning J17 behaviour (📝) and record why
  no new test is warranted. Authoring a parity test around a test helper asserts the fixture, not the
  product.

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
  gaps section. It is what the upgrade story reads to know what its gate covers.
- **FR18 — Nothing in this story touches the build's Java target, the parent pom or the CI agent.**
  No version bump, no `jakarta` rename, no change to `azure-pipelines.yaml`'s `centos8-j17` demand.
  Those belong to the upgrade story. **On this branch layout that is a correctness requirement, not
  tidiness:** the branch is only J17 evidence for as long as nothing has upgraded it, so a stray pom
  bump here would silently invalidate every run this story produces.

## Acceptance criteria

- **AC1** — Each of the nine Bucket A items has either a test executed green on J17 (🟢), or a
  checklist row explaining why it is 📝 or 🔴, with a named reason. No item is silently absent.
- **AC2** — `mvn clean install -DskipITs` passes on `main` with JDK 17, with every new test executing
  (not skipped, not disabled).
- **AC3** — The BC-13 and DLRM-01 numeric-literal tables each exist, are separate, cover the seven
  named inputs at minimum, and each input has a **named expected outcome** rather than a
  "does not throw" assertion.
- **AC4** — Both rules in `command-migrate-case-submission-api.drl` have a passing allow case and a
  passing deny case, and the command-API knowledge base asserts a non-zero rule count.
- **AC5** — Each affected module asserts exactly one resolvable JSON-P provider, and names it.
- **AC6** — The Function App gate's accept and reject paths are pinned for **both** source systems,
  including the array-payload rejection.
- **AC7** — `docs/j25-parity-checklist.md` exists, covers every BC-01..BC-24 plus DLRM-01 with a
  legend mark, and records the exact command and result for every 🟢.
- **AC8** — Every new test names its BC/DLRM item.
- **AC9** — `git diff main` for this PR contains no change under `src/main` except generated-code or
  documentation, and no pom version change. (FR15, FR18.)
- **AC10** — Any J17 run that contradicts the investigation report is recorded in the checklist with
  both the report's claim and the observed behaviour.

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

## Risks and notes

- **The investigation report is a hypothesis catalogue, not a specification.** 3 of its 24 entries are
  Refuted, 2 Mixed, 2 Inconclusive, and its own authors flag fleet-wide counts as "directionally
  reliable, not precision-audited". FR1 exists because the reference context already found one of its
  load-bearing claims wrong under a real J17 run. Expect at least one correction here.
- **DLRM-01 has no precedent anywhere in the fleet.** No other CPP context has an Azure Functions
  module, so no other parity story has pinned a networknt/Jackson gate. There is no reference
  implementation to copy and no reviewer with prior experience of it.
- **BC-11's assertion is about classpath state, which is easy to assert badly.** A test that merely
  calls a JSON-P factory and succeeds proves nothing — under a collision, one provider still wins.
  The count and the identity are the assertions; "it worked" is not.
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
- **Owner unassigned.** stagingdlrm shows owner "?" on the PEG-3296 tracker. Confirm with Platform
  Engineering that DLRM is genuinely unowned rather than queued behind their completed contexts.

## Notes for the design stage

1. **Decide where the BC-13 table lives.** `domain-value-schema` already has the catalogue-tier
   harness (`SchemaMatchers`, `MigratedCaseSubmissionSchemaContractTest`) and the everit/`org.json`
   dependencies. Extending it is the obvious route; confirm it can express "named expected outcome per
   input" without contorting the existing matcher API.
2. **Decide the DLRM-01 seam.** `JsonSchemaValidator` takes an `ExecutionContext`, so the gate is
   testable only as far as that mock allows. Establish whether the parse outcomes can be observed at
   the validator, or whether `TimerTriggerJava` is the necessary entry point — this determines whether
   FR6 is one test class or two.
3. **Choose the instrument for FR8, FR11, FR12, FR13.** These are build-time facts. A unit test, a
   `maven-enforcer` rule and a small script are all viable and have very different maintenance costs.
   Pick one instrument for all four rather than four different ones.
4. **Source-system keying does not exist on this branch.** The parity-method ADR decision 7 supersedes
   FR6's "both source systems" clause — pin the single gate as it stands.
5. **Sequence the two primary items first.** BC-13 and DLRM-01 carry most of the value and all of the
   novelty; the other seven are small and well understood. If the story has to be cut, it should be cut
   from the back.
6. **`SchemaMatchers` already exists and is shared.** Extending it affects DD-43078/DD-43081's suites.
   Check [the parity-method ADR](../adrs/DD-43191-j25-parity-method.md) decision 7 — `SchemaMatchers` is **not on this branch** before changing its API rather than adding to
   it.
