# ADR `DD-43191-j25-parity-method` — J17→J25 parity-test method and behavioural-change scope for the DLRM contexts

## Status

**Accepted 2026-09-02** — at the stage 1 review of [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191),
covering the parity stage of both repos. **Amended the same day** by decision 7, which re-scopes Bucket A
against what the `team/25.104.x` branch actually contains. Read decision 7 before scoping stage 4.

> **Mirrored.** This file exists in both DLRM repos:
> `cpp-context-stagingdlrm/docs/pipeline/adrs/DD-43191-j25-parity-method.md` and
> `cpp-context-prosecution-casefile-dlrm/docs/pipeline/adrs/DD-43191-j25-parity-method.md`.
> **Amend both, or neither.** The two repos are worked by different developers in isolation, so each
> repo's pipeline must be self-contained. Sections that apply to only one repo say so inline, so the two
> copies stay byte-identical rather than forking into near-copies.

## Date

2026-09-02

## Scope

The parity stage of both pipelines: **DD-43192 parity** (`cpp-context-stagingdlrm`) and **DD-43194
parity** (`cpp-context-prosecution-casefile-dlrm`). The upgrade stages consume what this ADR defines as
their regression gate; the branch mechanics that make that gate valid are
[the upgrade-mechanics ADR](./DD-43191-j25-upgrade-mechanics.md) decision 1.

Platform ticket **PEG-3377** owns parity testing across the fleet.

## Context

A J17→J25 upgrade of a CPP context changes almost no context code. That is the problem. The build goes
green because everything recompiled, and the risk sits in library and server defaults that shifted
underneath unchanged source — which is precisely why none of it fails at compile time.

The source of candidates is
[`j25-behavioural-change-investigation-report.md`](../../analysis/j25-upgrade/j25-behavioural-change-investigation-report.md),
which catalogues **24 behavioural changes** (17 Confirmed, 3 Refuted, 2 Mixed, 2 Inconclusive) across
the fleet. The reference implementation is `cpp-context-users-groups` PR
[#217](https://github.com/hmcts/cpp-context-users-groups/pull/217) and its `doc/j25-parity-checklist.md`.

**The reference PR must not be copied file-for-file.** Seven of its fourteen files are
viewstore-repository tests for entities neither DLRM repo has: both `*-viewstore-persistence` modules
contain **zero Java files** — no `@Entity`, no repository, only `persistence.xml` and `beans.xml`. The
whole persistence cluster, which dominates the fleet's risk profile and the reference PR, is simply
absent here.

---

## Decision 1 — Method 1: author **and execute** on J17, on the pre-upgrade branch

Every parity test is written against the **pre-upgrade `team/25.104.x`** stack — which is `main`'s, byte
for byte: `service-parent-pom 17.104.1`, JDK 17, `centos8-j17` agent — and **run** there.

- **A test that has not been executed on J17 is marked 🟡 and does not count toward the parity story's
  completion.** Authored-not-executed is a legitimate state for IT-tier items (see decision 2); it is
  not a legitimate state for a unit-tier item.
- **Tests use J17 idiom**: `javax` imports, no `jakarta`, no J25-conditional branches, no
  `@EnabledOnJre`. A test that already straddles both stacks pins neither. The upgrade story migrates
  them with everything else (the upgrade-mechanics ADR decision 1).
- **Every parity test names its item** — a one-line reference to its BC or DLRM identifier and what is
  expected to move, on the test itself. An unlabelled parity test is indistinguishable from a redundant
  one and will be deleted by the next person.

**Rejected alternative — write the tests after the upgrade, or against both stacks.** Both produce a
suite that passes because nothing recompiled. Neither can tell you a behaviour moved, which is the only
thing this story exists to do.

## Decision 2 — Depth follows tier, not enthusiasm

| Tier | Depth | Rationale |
|---|---|---|
| **Unit / component** | **Exhaustive** for the repo's primary item — every input class the parser or validator treats differently, accept *and* reject. Sufficient-branch for the rest. | Fast, in `mvn test`, no environment. The right place for an input matrix. |
| **Build-time assertion** | **A single decisive check** per item (BC-07, BC-11, BC-12, BC-21). | These are packaging and code-generation facts, not runtime behaviour. A test that boots a container to observe them is the wrong instrument. |
| **Integration** | **Authored, not executed** — written and marked 🟡 until Docker and a WildFly 40 image are available. | ITs need `CPP_DOCKER_DIR`. No WildFly 40 image was confirmed as of the investigation report. Blocking the parity story on that would block the whole epic. |

## Decision 3 — Scope is closed, and closed differently per repo

The items in play are exactly the Bucket A lists below. **Adding or removing an item requires this ADR
to be amended, not a local decision at stage 4 or 5.** Writing a test for an N/A item — in particular
anything in the persistence cluster, which has no code to bind to — is a defect in the story, not extra
value.

Three buckets:

- **Bucket A** — a real seam in this repo's code. Write a test.
- **Bucket B** — framework-tier, owned by PEG-3296, no context-side binding site. **Record a check in
  the checklist; do not write a test.** BC-14, BC-15, BC-16, BC-17.
- **N/A** — no binding site in this repo. Do nothing, and do not re-litigate it at stage 5.

**N/A for both repos**, verified against the code: the persistence cluster (BC-01, BC-02, BC-04, BC-05,
BC-06, BC-24), BC-09 and BC-10 (no Activiti), BC-18 (no `ActiveMQConnectionFactory`), BC-19 (SJP),
BC-22 (no Tika), BC-23 (no Quartz).

## Decision 4 — A J17 run outranks the report

Where an executed J17 run contradicts the investigation report, **the test pins the observed behaviour**
and the contradiction is written into the checklist row.

This is not hypothetical. In the reference context, three BC-04 tests were written to the report's claim
that Hibernate 5 silently coerces NULL→0; a real J17 run threw instead. Expect at least one such
correction per repo, and record it — a corrected row is the most valuable output the parity story
produces.

**A live J17 defect found this way is raised, not fixed here.** The test pins the observed behaviour, the
defect is recorded, and the fix is someone else's story. A parity story that starts fixing bugs stops
being a gate.

## Decision 5 — The checklist is the deliverable, not just the tests

Each repo produces **`docs/j25-parity-checklist.md`** — one row per item in its Bucket A and Bucket B,
carrying: the item, its seam, the test that pins it, its status marker, and any correction to the report.

Status markers: **🟢** executed green on J17 · **🟡** authored, not executed · **📝** existing coverage
annotated, no new test warranted · **⚪** Bucket B check recorded, no test.

*(The reference used `doc/`; both DLRM repos use `docs/`.)*

This is what the upgrade story reads to know what its gate covers. A suite without the checklist leaves
the upgrade dev guessing which green ticks mean anything.

## Decision 6 — DLRM-01: two validator stacks, two regression tables *(stagingDLRM only)*

**DLRM-01 is not in the 24-BC catalogue.** It was found by a code read of this repo on 2026-08-28, which
corrected an earlier assumption, and it applies only to `cpp-context-stagingdlrm` — pcfdlrm has no
Function App.

stagingDLRM has **two** JSON validation stacks with **different upgrade exposure**:

| Tier | Library | Moves on J25? | Item |
|---|---|---|---|
| Function App gate | `com.networknt:json-schema-validator` **1.0.83, hard-pinned** in `stagingdlrm-azure-functions/pom.xml` | **No** — pinned | — |
| …but its parser | Jackson `ObjectMapper.readTree`, BOM-managed | **Yes**, 2.12.7 → 2.21.4 | **DLRM-01** |
| Schema catalogue | `org.json` 20231013 → 20251224 + everit | **Yes** | **BC-13** |

So the func-app gate is *not* exposed to BC-13's `org.json`/everit mechanism; its exposure is the Jackson
bump behind `readTree`. **Each tier needs its own pinned numeric-literal table** — at minimum `0`, `007`,
`01`, `.5`, `10.0`, `1e3`, `12345678901234567890`.

**A single shared table standing in for both is not acceptable.** The two tiers use different libraries
with different upgrade exposure, and a shared table hides which one moved. They may additionally be
asserted to agree with each other.

## Decision 7 — Branch-reality amendment: Bucket A is scoped to what `team/25.104.x` contains

*Added 2026-09-02, at handover to stage 2.*

**The stage 1 artefacts were authored on a branch that carried the LIBRA-era work. `team/25.104.x` is cut
from `main`, which does not.** Several binding sites the requirements name by class do not exist on the
branch the parity story will actually run on. Verified against the branch, not inferred:

| Named in the requirements | On `team/25.104.x` | Effect on Bucket A |
|---|---|---|
| `SchemaMatchers`, `MigratedCaseSubmissionSchemaContractTest` *(stagingDLRM BC-13)* | **Absent.** `stagingdlrm-domain-value-schema` has **zero Java** — `src/main/resources/json` only | **BC-13 stays in scope**, but is authored from scratch against the schema catalogue, not by extending an existing helper. Size it accordingly |
| Source-system-keyed func-app validators *(stagingDLRM DLRM-01)* | **Absent.** The gate is `JsonSchemaValidator` + `Validator`, not keyed by source system | **DLRM-01 stays in scope**, but the "cover **both** source systems" clause does **not** apply. Pin the single gate as it exists |
| `stagingdlrm-test-support` / `pcfdlrm-test-support` | **Untracked build output only** (`target/` from a `…-LIBRA1-SNAPSHOT` build); not in either root POM's `<modules>` | Not available. No parity test may depend on it |
| `WholePayloadMatcher` *(pcfdlrm BC-13)* | **Absent** — the class does not exist on this branch | **BC-13 becomes N/A for pcfdlrm.** Its only stated binding site was this class. Record it as N/A in the checklist with this reason; do not invent a substitute |

**Everything else in Bucket A was verified present and is unaffected:**

- stagingDLRM BC-03 — `command-migrate-case-submission-api.drl` declares **2 rules**
  (`Migrate Case Submission`, `Error Migrate Case Submission`); the coverage gap on the second is real
- stagingDLRM BC-11 — `javax.json` in command-handler, event-listener, domain-event, domain-aggregate
  and the func-app. **Re-derive the coordinate count on the branch** rather than taking the brief's
  figure of six
- pcfdlrm BC-08 — `MigratedCaseFileAggregate` plus the `…ToCC…Converter` classes in the event processor
  are present. **Four** `ToCC` converters exist; the brief names two. Derive the carrier list from the
  code, not from the brief
- pcfdlrm BC-03 — `command-receive-migrated-case-file-api.drl`, single rule, `ReceiveMigratedCaseRuleTest`
  present and coverage-complete → annotate (📝), do not add tests
- BC-07, both repos — `liquibase.properties` declares **both** `liquibase.hub.mode: off` **and**
  `liquibase.headless: true`. Pin the key set; the deletion is the upgrade story's FR18

**ADR-001 (scenario-test DSL) and ADR-002 (source-system-keyed dispatch) are not carried onto this
branch.** Both describe LIBRA-era conventions whose code is absent here, so a copy would point stage 4 at
things it cannot use. They remain on the LIBRA branches and apply to the LIBRA stories. Where a J25
requirement cites them, read this decision instead.

---

## Bucket A — the two repos, side by side

Both repos share the method and the BC list. They do **not** share where the risk sits, and levelling
them would put effort in the wrong place.

| | **stagingDLRM** (DD-43192) | **PCFDLRM** (DD-43194) |
|---|---|---|
| Primary item | **BC-13 + DLRM-01** — two schema-validation stacks | **BC-08** — `ZonedDateTime` zone identity |
| Where the primary risk lives | the ingestion gate | **main code, on the outbound payload path** |
| `ZonedDateTime` in main code | none (one test helper) | **aggregate + CC converters** |
| everit / `org.json` product seam | yes (`domain-value-schema` catalogue) | **none** |
| Function App | yes (DLRM-01) | none |
| Access-control coverage | 2 rules, 1 covered — **real gap** | 1 rule, both paths covered — **complete** |

**The consequence: BC-08 is where the pcfdlrm story earns its keep.** A wrong zone identity does not fail
a build or return a 4xx; it writes a plausible-looking wrong timestamp into a migrated case.

### stagingDLRM — Bucket A, 9 items

| BC | Seam | Weight |
|---|---|---|
| **BC-13** | JSON-schema validation strictness (`org.json` 20231013→20251224, everit) at the schema-catalogue tier | **primary** |
| **DLRM-01** | Jackson `ObjectMapper.readTree` parse behaviour (2.12.7→2.21.4) at the Function App gate. Not in the 24-BC catalogue | **primary** |
| BC-11 | JSON-P provider collision (glassfish→Parsson) — `javax.json` coordinates incl. the func-app on `org.glassfish:javax.json:1.0.2` | high |
| BC-03 | Drools 7→10 allow/deny — 2 rules, only 1 covered | high |
| BC-20 | Drools harness rule-count gate | low (cheap) |
| BC-12 | RESTEasy engine swap — the func-app's 4 compile-scope artifacts | medium |
| BC-21 | Codegen (reflections 0.9.10→0.10.2) — all 4 generator plugins + RAML | medium |
| BC-07 | Liquibase 4→5 removed properties | low (deploy blocker) |
| BC-08 | Jackson `'Z'` → `ZoneOffset.UTC` — one test helper only → annotate (📝) | thin |

### PCFDLRM — Bucket A, 7 items

*(BC-13 removed by decision 7; DLRM-01 does not apply — no Function App.)*

| BC | Seam | Weight |
|---|---|---|
| **BC-08** | Jackson `'Z'` → `ZoneOffset.UTC` identity drift in **main** code — `MigratedCaseFileAggregate` and the `…ToCC…Converter` classes building the outbound payload | **primary** |
| BC-11 | JSON-P provider collision — `javax.json` across domain-aggregate, domain-event, query-view, event-listener, command-handler | high |
| BC-03 | Drools 7→10 — single rule, already coverage-complete → annotate (📝) | high |
| BC-20 | Drools harness rule-count gate | low (cheap) |
| BC-21 | Codegen — a **larger** surface than stagingDLRM's: generator plugins across 8 modules. Derive the expected type set from the schema resources, not hard-coded | medium |
| BC-12 | RESTEasy — `resteasy-multipart-provider` in `pcfdlrm-integration-test`; record its expected scope | low (IT tier) |
| BC-07 | Liquibase 4→5 removed properties | low (deploy blocker) |

### Both repos — Bucket B, record a check only

**BC-14** (CDI 4 discovery-mode + legacy `beans.xml` emptying interceptor chains — *Refuted acute, latent
hazard*; the upgrade story's FR8 preserving `bean-discovery-mode="all"` is what keeps that verdict
holding), **BC-15** (core-domain missing schema fields — a precondition on the `coredomain` bump, the upgrade-mechanics ADR
decision 2), **BC-16** (`/internal/metrics/*` gutted to 404), **BC-17** (`stream_error` hash/identity
shift).

## Decision 8 — BC-11 corrected: `JsonObjectBuilder` null-value NPE parity, not JSON-P provider collision

*Added 2026-09-04. Supersedes this ADR's original BC-11 characterization wherever it appears above (the
Bucket A tables' BC-11 rows, and decision 3's scope list) — this decision is the amendment instrument and
wins where the two disagree, exactly as decision 7 established for its own corrections.*

**What changed.** Every BC-11 reference elsewhere in this ADR was written against the original
investigation report's hypothesis: a JSON-P `ServiceLoader` provider collision (glassfish → Parsson),
provable by a classpath-resource count. Fleet-wide empirical work across the ~14 contexts Platform
Engineering has since completed (`docs/pipeline/DD-43191-DD-43192-j25-parity/Parity+Testing+Java17+-_+Java25.pdf`,
its BC-11 entry marked "CORRECTED 2026-08-26") replaces that hypothesis with a verified one:

> Via the framework helper `uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder()`,
> `add(key, (String) null)` throws `NullPointerException` identically on **both** J17/glassfish and
> J25/Parsson — the helper carries its own null-guard, so glassfish never silently accepted a null value
> either. Empirically verified on `cpp-context-notification-notify`. A nullable value reaching `add()`
> (e.g. an error message that can be null) is a **pre-existing latent bug that NPEs the same on both
> runtimes — not a J25 regression.** Disposition: **Refuted / parity** (the report's original entry rated
> it a **Fix**).

**Effect on Bucket A — BC-11 stays in scope in both repos, but its test shape changes.** A real call site
exists in each:

| Repo | Call site | What the corrected test pins |
|---|---|---|
| stagingDLRM | `stagingdlrm-azure-functions`' `StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload` — `createObjectBuilder().add("errorMessage", responseString)`, `responseString` reachable as null on an error path | That a null `responseString` throws `NullPointerException` identically — parity, not a classpath inventory |
| PCFDLRM | Verify against this repo's own code at stage 4 before assuming the same shape applies — do not copy stagingDLRM's call site without checking | — |

The **javax.json coordinate inventory** (glassfish vs Parsson GAVs, which modules declare which, at what
scope) that this ADR's earlier decisions recorded for BC-11 is not wrong as *classpath fact* and may be
kept as background in a repo's checklist — but it is no longer the BC-11 **assertion**. A `ServiceLoader`/
provider-count test proves nothing about this corrected mechanism, and should not be built or kept as the
BC-11 pin. Where a repo's parity story already has such a test authored, replace it with a call-site NPE-
parity test rather than running both — the corrected finding is what BC-11 now means, not an addition to
the old one.

## Consequences

- The parity story is **on the critical path** in each repo. It cannot be deferred without stalling that
  repo's upgrade, because the upgrade's regression gate is its only output.
- Both stories will produce **fewer tests than the reference PR** and should. Seven of the reference's
  fourteen files have no binding site here.
- Decision 7 means the requirements documents contain clause-level references to classes that are not on
  the branch. They are **not** rewritten — this ADR is the amendment instrument, and it wins where the
  two disagree.
- The primary items differ per repo, so the two parity stories are **not** comparable in shape or size.
  Do not use one as a template for the other.
- If a parity story must be cut, **cut from the back of its Bucket A table** — the primary items carry
  the novelty; the tail is small and well understood.
