# Requirements — DD-43192: Java 25 / WildFly 40 / Jakarta EE 11 upgrade of stagingDLRM

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> Requirements altitude — nothing here prescribes a class layout. Implementation **tasks** come from
> the design / story-writer stage.
>
> **Scoped to `cpp-context-stagingdlrm`, branch `team/25.104.x`.** The PCFDLRM half is
> [DD-43194](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43191-DD-43194-j25-upgrade).
> Mechanics are fixed by [the upgrade-mechanics ADR](../adrs/DD-43191-j25-upgrade-mechanics.md); this document
> does not restate them, it makes them testable.

## Story

**[DD-43192](https://tools.hmcts.net/jira/browse/DD-43192) — Upgrade stagingDLRM to Java 25 /
WildFly 40 / Jakarta EE 11**

| | |
|---|---|
| Epic | [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191) — Java 25 upgrade, DLRM contexts |
| Size | **L** — the largest story in the epic; it carries the epic's only Azure Functions module |
| Repo | `cpp-context-stagingdlrm` |
| Target branch | **`team/25.104.x`**, cut from `main` before the parity story; this story is the second PR onto it |
| Depends on | **[DD-43192 parity stage](../DD-43191-DD-43192-j25-parity/00-input-brief.md) merged to `team/25.104.x`** — hard, see FR1. Plus [the upgrade-mechanics ADR](../adrs/DD-43191-j25-upgrade-mechanics.md) accepted, including its open decision 6 |
| Sibling story | [DD-43194](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43191-DD-43194-j25-upgrade) — same stage in PCFDLRM, independently deliverable |
| Production changes | **yes** — this is the upgrade |
| Platform ticket | PEG-3296 |

### Summary (JIRA summary line)

`[Java 25] Upgrade stagingDLRM to 25.104.0-M10 / WildFly 40 / Jakarta EE 11, including the Azure Functions app, and produce a QA Docker image`

### User story

As a **CPP platform engineer retiring the Java 17 estate**,
I want **stagingDLRM building, deploying and behaving identically on Java 25, WildFly 40 and
Jakarta EE 11 — with the DD-43192 parity tests still green and a QA Docker image published**,
so that **the DLRM migration pipeline is not the reason the platform has to keep a Java 17 runtime
alive, and any behaviour that did move is visible as a failing test rather than as corrupted migrated
case data**.

## Reference and sizing

The reference upgrade is `cpp-context-prosecution-casefile` commit **`122a5a8fdc`** on
`team/25.104.x` (401 files, +5870/−5018), checked out locally at
`../cpp-context-prosecution-casefile`. Its **shape** is the template; its version pins are stale
against the fleet tracker (`docs/analysis/j25-upgrade/Java 25 Upgrade Status.pdf`) and must not be
copied (the upgrade-mechanics ADR decision 2).

Measured surface in this repo, at `main`:

| Surface | This repo | Reference, for scale |
|---|---|---|
| `javax.*` import lines to migrate | **92**, across **40 files** | 544 |
| `javax.json` | 53 | 220 |
| `javax.ws.*` | 22 | 15 |
| `javax.inject` | 11 | 155 |
| `javax.annotation.PostConstruct` | 4 | — |
| `javax.enterprise` | 1 | 32 |
| `javax.persistence` | **0** — no JPA code | 118 |
| `javaee-api` declarations | 9 modules + 2 generator-plugin dependency blocks | — |
| `beans.xml` to migrate | 10, all legacy `xmlns.jcp.org` namespace | 12 |
| `.drl` | 1 (`command-migrate-case-submission-api.drl`, 2 rules) | 2 |

**This is roughly one sixth of the reference's Java churn**, because the persistence layer that
dominates the reference does not exist here (`docs/architecture/dlrm-flow-reference.md` §3.1's module
map has no repository/`@Entity` module — `stagingdlrm-viewstore-persistence` ships only
`persistence.xml`/`beans.xml`). The offsetting cost is the Azure Functions module
(`stagingdlrm-azure-functions`, §2 of the same reference), which the reference does not have and — per
the fleet tracker — **no other CPP context has either**.

## Requirements

### A. Branch, versions, and the gate

- **FR1 — Do not start until the DD-43192 parity PR has merged into `team/25.104.x`.** Both stages
  share the branch, and the parity tests are only J17 evidence for as long as nothing has upgraded it.
  This story is what ends that state, so starting early would retroactively invalidate the gate it
  depends on (the parity-method ADR's Method 1, the upgrade-mechanics ADR decision 1). The branch itself is cut from `main` before the
  parity story, not by this one.
- **FR1a — Migrate the parity tests as part of this story's `javax`→`jakarta` sweep.** They are source
  files on the same branch and are swept like any other. This is the reason the single-branch layout was
  chosen: the tests are authored once. Their **assertions must not change** — only their imports. This
  includes the BC-11 test's assertion (a `NullPointerException` from a null value reaching
  `JsonObjects.createObjectBuilder().add(...)`) — the parity-method ADR's decision 8 established that
  this is the corrected, real mechanism to keep pinning; do not reintroduce a classpath/`ServiceLoader`
  inventory test on this branch on the strength of the *original*, superseded BC-11 hypothesis.
- **FR2 — Target the latest platform milestones, verified at implementation time.**
  `cpp-platform-maven-service-parent-pom` `17.104.1` → **`25.104.0-M10`**; `cpp-platform-core-domain`
  `17.104.4` → **`25.104.0-M11`**. These are the fleet tracker's figures as at 06 Aug 2026 and are where
  every merged context sits. **Reconfirm before starting** — if they have advanced, take the newer.
- **FR3 — The DD-43192 parity tests must be green on Java 25 at the end of this story.** A parity test
  that goes red is a **finding, not a test to relax**. If one cannot be made green, the story stops and
  the divergence is raised; silently adjusting an assertion defeats the entire two-stage design.
- **FR4 — Interface pins move only as far as the enforcer requires.** The reference bumped
  `referencedata.version`, `progression.version` and `sjp.version` purely to satisfy the
  latest-interfaces enforcer. Bump this repo's pins (`coredomain`, `pcfdlrm`, `system.id-mapper`,
  `progression` — `dlrm-flow-reference.md` §1.2's outbound-interface table names the last two as this
  repo's actual downstream callers) to what the enforcer demands and no further; an opportunistic bump
  makes the diff unreviewable.

### B. The Jakarta EE migration

- **FR5 — Migrate only the Jakarta EE namespaces.** `javax.json` → `jakarta.json`, `javax.ws.*` →
  `jakarta.ws.*`, `javax.inject` → `jakarta.inject`, `javax.annotation.PostConstruct` →
  `jakarta.annotation.PostConstruct`, `javax.enterprise` → `jakarta.enterprise`.
- **FR6 — Do not rename JDK `javax.*` packages.** `javax.net.ssl.SSLContext` in
  `stagingdlrm-azure-functions`' `StagingDlrmCommandHelper` (the same class that builds the BC-11 error
  payload, per `dlrm-flow-reference.md` §2.4/§5) is **JDK API, not Jakarta**, and has no `jakarta`
  equivalent. A blanket find-and-replace across `javax.` will break the build here. The same applies to
  any `javax.crypto`, `javax.xml.parsers` or `javax.naming` that appears later.
- **FR7 — Swap `javaee-api` for the Jakarta equivalent everywhere it is declared, including inside
  plugin `<dependencies>`.** Nine modules declare it as a normal dependency; `stagingdlrm-event-processor`
  also declares it **twice inside generator-plugin dependency blocks** (`pom.xml:105`, `pom.xml:117`).
  The plugin-internal ones are the ones that get missed, and they fail at code-generation time with a
  message that does not name them.
- **FR8 — Migrate the CDI and persistence descriptors.** All 10 `beans.xml` are on the legacy
  `http://xmlns.jcp.org/xml/ns/javaee` namespace, and `persistence.xml` declares
  `http://java.sun.com/xml/ns/persistence` version `1.0`. Move to the Jakarta namespaces and current
  versions. **`bean-discovery-mode="all"` must be preserved explicitly on all 10** — it is already
  present, and it is the sole reason BC-14's *Refuted* verdict holds. Losing it would empty the
  interceptor chains silently.

### B2. Dependencies that will not resolve on the 25.104.x chain

*Added at stage 1 review, after a dependency scan. Numbered after the existing requirements rather than
renumbering them, so earlier references stay valid.*

- **FR23 — `stagingdlrm-viewstore-persistence` declares four coordinates that no longer exist on the Java 25 chain.**
  This is a **hard dependency-resolution failure**, not a behaviour change: `mvn clean install` will fail
  on this module before compiling anything. All four are in stagingdlrm-viewstore-persistence's pom (`pom.xml:20`, `:100`, `:105`, `:110`, `:116`):
  1. `uk.gov.justice.services:persistence-deltaspike` — the module was **removed from the framework
     reactor and BOM**; its four non-DeltaSpike classes were relocated to a new `persistence-jpa` module
     in `cp-microservice-framework` 25.104.0-M3.
  2. three `org.apache.deltaspike.*` test artifacts (`deltaspike-test-control-module-api`/`-impl`, `deltaspike-cdictrl-openejb`,
     and `deltaspike-cdictrl-api` where present) — **DeltaSpike is removed entirely** from
     `cp-maven-common-bom` (8 artifacts), so these have no managed version.
  3. `org.hibernate:hibernate-entitymanager` — **removed in Hibernate 6**, merged into `hibernate-core`;
     the groupId also moved to `org.hibernate.orm`.
  4. `src/test/resources/META-INF/apache-deltaspike_test-container.properties` — the reference upgrade
     **deleted this exact file** and added a test `persistence.xml` in its place. This repo has no test
     `persistence.xml`.
- **FR24 — Prefer deletion over migration here, and justify whichever is chosen.**
  `stagingdlrm-viewstore-persistence` **contains no Java code at all** — no `@Entity`, no repository, no
  test (confirmed against `docs/architecture/dlrm-flow-reference.md` §3.1's module map: role listed as
  "DeltaSpike/JPA persistence", but this parity story independently verified zero `.java` files exist).
  The DeltaSpike and Hibernate 5 test scaffolding exists to support tests that do not exist. So the
  reference's migration path (swap `persistence-deltaspike` → `persistence-jpa`, replace the properties
  file with a test `persistence.xml`) would faithfully reproduce scaffolding for nothing.
  **Establish first whether the module is needed at all** — it ships a `persistence.xml` and a
  `beans.xml` that the runtime may still require for the view-store datasource wiring, in which case the
  module stays and only its dependencies go. Record the reasoning either way; a module deleted by
  accident is worse than a dependency list trimmed too cautiously.

### C. Code generation

- **FR9 — Apply the two recorded generator fixes.** Both plugins run in this repo, and both have a
  documented fleet failure (fleet tracker context rows):
  - `messaging-client-generator-plugin` needs **parsson** in its plugin dependencies
    (as `cpp-context-system-scheduling` found — "messaging-client-generator needed parsson in plugin
    deps" per the tracker);
  - `rest-client-generator-plugin` needs the **jakartaee-api** swap (as
    `cpp-context-system-announcement` found — "rest-client-generator jakartaee-api swap" per the tracker).
- **FR10 — The generated-artefact inventory must not shrink.** BC-21's `reflections` 0.9.10→0.10.2
  scanning-contract change alters what is discovered. The parity story's derived inventory assertion
  (its FR12) must still pass; a silently smaller set of generated types is a defect in this story.

### D. Build, pipeline and packaging

- **FR11 — Move the build to the Java 25 track.** `azure-pipelines.yaml:29` demands
  `identifier -equals centos8-j17` → **`ubuntu-j25`**; repoint the template ref to the **`wildfly40`**
  track and set `aksDeployBranch` accordingly.
- **FR12 — `jacoco` must be at 0.8.14 or later.** The parent's 0.8.12 does not handle JDK 25 bytecode;
  every migrated context on the fleet tracker has needed a local override.
- **FR13 — Assess `jboss-deployment-structure.xml`, do not assume.** One already exists at
  `stagingdlrm-event/stagingdlrm-event-listener/src/main/webapp/WEB-INF/`. The reference *added* one at
  repo root. Determine whether the existing one needs amending and whether other WARs
  (`dlrm-flow-reference.md` §3.1 lists `command-api`, `command-handler`, `event-listener`,
  `event-processor`, `query-api`, all WAR-packaged) need their own —
  **and check it against BC-12**, because a `jboss-deployment-structure.xml` that disables the `jaxrs`
  subsystem, combined with the new fleet-wide `packagingExcludes` stripping bundled RESTEasy, is the
  investigation report's flagged possible deploy-breaker.
- **FR14 — Establish where this repo's container image comes from before assuming nothing is needed.**
  There is no `Dockerfile` at this repo's root, so the fleet's "Dockerfile base → Ubuntu 24.04, remove
  the RHEL `yum` lines" item has no obvious target here — but that must be **confirmed, not assumed**.
  Three facts to reconcile: `context-validation.yaml`'s image step is gated only by a repo-name
  exclusion list that **does not exclude this repo**, so the image step *will* run; its `dockerfilePath`
  parameter defaults to `'Dockerfile'`; and the reference context does ship one, at
  `docker/Dockerfile_prosecutioncasefile-service` — not at root. Meanwhile the tracker shows `support`,
  `system-id-mapper` and `notification` have no `Dockerfile` at all and all three have QA images.
  **Determine which of those patterns applies here**, then either fix the file the `wildfly40` track
  needs or record that none exists. Do not create a `Dockerfile` to satisfy a checklist, and do not
  assume the template covers it.

### E. The Azure Functions module — this repo only

- **FR15 — `stagingdlrm-azure-functions` moves to Java 25.** Verified supportable: Azure Functions
  lists Java 25 as GA to May 2029, on both Windows and Linux, and this app is `<os>windows</os>` on a
  dedicated App Service Plan (`dlrm-flow-reference.md` §2: "Runtime: Azure Functions v2 (Java), Extension
  Bundle 4.x... standalone JAR, runs outside the WildFly/JMS stack") — so the Linux-Consumption
  restriction does not apply. Six pom items, enumerated in
  [the upgrade-mechanics ADR](../adrs/DD-43191-j25-upgrade-mechanics.md) decision 4:
  `maven.compiler.source`/`target` 17→25; plugin `<runtime><javaVersion>` 17→25;
  `azure-functions-maven-plugin` 1.24.0 → a version accepting `javaVersion 25`;
  `azure-functions-java-library` 3.1.0 → newer; `javax:javaee-api:8.0` migrated or dropped;
  `org.glassfish:javax.json:1.0.2` → parsson.
- **FR16 — The fallback is explicit, not implicit.** If the plugin or library blocks Java 25, this
  module stays at 17 while the WildFly modules move — the reactor tolerates a mixed target because the
  module is a standalone JAR with no WildFly coupling. Its jakarta and parsson changes still land
  regardless, being correctness fixes independent of the JDK. **If the fallback is taken it must be
  recorded in `02-design.md` and raised as a follow-up**, not left as an undocumented inconsistency.
- **FR17 — BC-12's fleet fix must not be applied to the Function App.** Its four compile-scope
  RESTEasy artifacts (`-client`, `-jaxb-provider`, `-jackson2-provider`, `-multipart-provider`) **must
  stay bundled**. Marking them `provided` compiles and then fails at runtime in Azure with
  `NoClassDefFoundError`, because nothing there supplies them. Exclude the module by name from the
  `provided` + `packagingExcludes` change **and say so in the PR description**, so a later fleet-wide
  sweep does not "correct" it (the upgrade-mechanics ADR decision 5). The parity story's
  `Bc12RestEasyPackagingParityTest` (or equivalent) pins this carve-out as a build-time assertion —
  keep it green rather than "fixing" it to match the fleet pattern.

### F. Known defects to fix in this story

- **FR18 — Delete `liquibase.hub.mode: off` from `liquibase.properties`.** Finding from the parity
  story's stage 2. Liquibase Hub was sunset and its configuration removed; on Liquibase 5.0.3 this is an
  unknown-parameter failure in the **K8s pre-install migration job**, before any application code runs.
  It is a live deploy blocker on this branch. **Also verify `liquibase.headless`** the same way — lower
  confidence — by running Liquibase 5 against the file. (The parity story's own `LiquibasePropertiesParityTest`,
  or equivalent, pins the pre-upgrade key set — this story is what actually removes the offending key.)
- **FR19 — Check the missing core-domain fields before bumping `coredomain.version`.** The investigation
  report records schema fields absent from the J25 `cpp-platform-core-domain` line pending a
  release-management cherry-pick (BC-15). Confirm none of them feeds this context before moving
  `17.104.4` → M11.
- **FR20 — Resolve the `anonymise` module decision.** The upgrade-mechanics ADR decision 6 is **open**:
  `stagingdlrm-domain-transformation-anonymise` exists here. Determine whether removal (as seen in other
  fleet contexts that dropped their equivalent module) was mandated (the framework dropped
  `stream-transformation-*` support on the 25.104.x line) or incidental. **Default if
  unresolved: retain and migrate** — deleting an anonymisation rule set on an assumption is the more
  expensive mistake. Whichever way it goes, record the reasoning.

### G. Definition of done

- **FR21 — Done is a QA Docker image, not a merged PR.** Nine contexts on the 06 Aug 2026 fleet tracker
  are "Merged — no Docker image produced (build failed)", several still carrying an open pipeline-revert
  or Docker-fix PR. Budget for a follow-up pipeline/image PR as part of this story rather than treating
  it as an afterthought.
- **FR22 — Integration tests must pass on the Java 25 stack.** The repo has 4 IT classes
  (`dlrm-flow-reference.md`'s module map lists `stagingdlrm-integration-test`, WireMock-backed). If no
  WildFly 40 image is available, that is a blocker to record and escalate, not a reason to declare done
  on unit tests alone.

## Acceptance criteria

- **AC1** — `git log` on `team/25.104.x` shows the DD-43192 parity commits as **ancestors of** this
  story's first commit, and no commit of this story predates the parity merge (FR1).
- **AC2** — `mvn clean install` passes on JDK 25 for the full reactor.
- **AC3** — Every DD-43192 parity test passes on Java 25, with no assertion weakened — only imports
  changed. Any that cannot is recorded as a finding with its divergence described (FR3, FR1a).
- **AC4** — `grep -rE '^import (static )?javax\.' --include='*.java'` returns **only** JDK packages —
  `javax.net.ssl` and any other genuinely-JDK namespace. No Jakarta EE `javax.*` import remains (FR5,
  FR6).
- **AC5** — No `javaee-api` coordinate remains anywhere, **including inside plugin `<dependencies>`**
  (FR7). Verified by grep across all poms, not by build success alone.
- **AC6** — All 10 `beans.xml` are on the Jakarta namespace and **all 10 still declare
  `bean-discovery-mode="all"`** (FR8).
- **AC7** — The build runs on the `ubuntu-j25` agent and the `wildfly40` template track (FR11).
- **AC8** — `liquibase.properties` contains no `liquibase.hub.mode`, and Liquibase 5 accepts the file
  (FR18).
- **AC9** — The Function App builds and deploys on Java 25 **or** the documented fallback to 17 is taken
  and recorded in `02-design.md` with a raised follow-up (FR15, FR16).
- **AC10** — The Function App's four RESTEasy dependencies remain compile-scope and bundled, and the
  PR description states the exclusion explicitly (FR17).
- **AC11** — The `anonymise` decision is recorded with its reasoning, either way (FR20).
- **AC12** — A QA Docker image is published to `crmdvrepo01.azurecr.io/hmcts/` and its tag recorded
  (FR21).
- **AC13** — The integration-test suite result on the Java 25 stack is recorded — passing, or blocked
  with the blocker named (FR22).
- **AC14** — No `deltaspike`, `persistence-deltaspike` or `hibernate-entitymanager` coordinate
  remains in any pom, and `apache-deltaspike_test-container.properties` is gone (FR23).
- **AC15** — The decision on `stagingdlrm-viewstore-persistence` — retained with trimmed dependencies, or removed — is
  recorded with its reasoning (FR24).

## Out of scope

- Writing parity tests — DD-43192 parity stage, already merged to `team/25.104.x`. Migrating their
  imports is in scope (FR1a); changing their assertions is not.
- `cpp-context-prosecution-casefile-dlrm` — DD-43194, its own pipeline.
- Framework and platform repository changes — PEG-3296 owns those. In particular BC-15's core-domain
  cherry-pick and BC-17's `cp-event-store` fixes are **not** this story's to make; FR19 only *checks*.
- A production release. The fleet tracker shows only `support` has gone that far ("first Java-25
  production-release guinea-pig").
- The "material-client decoupling" PR the fleet standard includes — **verified not applicable**, neither
  DLRM repo has that dependency. (Not to be confused with the cross-context material *file flow* this
  repo participates in, `docs/architecture/material-file-flow.md` — that is a runtime message flow to
  pcfdlrm/Material, not a build dependency.)
- Opportunistic dependency bumps beyond what the enforcer requires (FR4).
- Refactoring, reformatting or test cleanup unrelated to the upgrade.

## Risks and notes

- **`azure-functions-maven-plugin 1.24.0` may not accept `javaVersion 25`.** Unverifiable from the repo,
  no fleet precedent (stagingdlrm is the tracker's only Azure Functions context), and the single
  highest-uncertainty item in the epic. Sequence it early enough that FR16's fallback can be taken
  without re-planning the sprint.
- **FR6 is the most likely self-inflicted failure.** A blanket `javax.` → `jakarta.` replacement is the
  obvious way to do FR5 and it will break `javax.net.ssl` inside `StagingDlrmCommandHelper`. The
  migration must be package-by-package.
- **The plugin-internal `javaee-api` declarations (FR7) fail late and obscurely.** They are inside
  `<plugin><dependencies>` in `stagingdlrm-event-processor`, so they surface as a code-generation error
  rather than a compile error, and the message does not name the coordinate.
- **FR3 is the story's real risk and its real value.** The parity tests exist to go red. Treat a red as
  the system working; the pressure to "just fix the assertion" is exactly what the two-stage split was
  designed to resist. This applies with particular force to the BC-11 test (FR1a) — it pins a
  `NullPointerException`, and "fixing" it to not throw would silently null-guard away a behaviour the
  parity story deliberately chose to observe rather than patch.
- **The tracker's version figures are a month old** (06 Aug 2026) and several reference contexts have
  been bumped since their own upgrade commits (e.g. `mi-reportdata` merged still pointing at
  `hearing.version 25.104.0-M1-SNAPSHOT`, flagged on the tracker as a follow-up to advance). FR2 says
  reconfirm.
- **BC-12's deploy-breaker interaction is untested anywhere.** The investigation report flags "check
  this first": whether a query-api WAR retains any JAX-RS runtime path once `packagingExcludes` strips
  bundled RESTEasy *and* `jboss-deployment-structure.xml` disables the `jaxrs` subsystem. This repo has
  both an existing `jboss-deployment-structure.xml` and query/command WARs
  (`dlrm-flow-reference.md` §3.1), so FR13 is not a formality.
- **Owner unassigned.** stagingdlrm shows owner "?" on the 06 Aug 2026 PEG-3296 tracker snapshot.
  Confirm with Platform Engineering before cutting the branch.
- **The first `mvn clean install` on the new branch will fail at dependency resolution, not
  compilation** (FR23). Four dead coordinates sit in a module with no Java in it. Expect this as the
  opening move of implementation rather than as a surprise, and resolve FR24 before spending time
  migrating scaffolding that may not need to exist.

## Notes for the design stage

1. **Decide the migration mechanism for FR5/FR6.** A scripted per-package rewrite with an explicit
   allowlist of the five Jakarta namespaces is safer than a blanket replace, and makes AC4 checkable.
2. **Enumerate every `javaee-api` site before starting**, separating normal dependencies from
   plugin-internal ones. There are 9 of the former and 2 of the latter; the count is the checklist.
3. **Sequence FR15/FR16 (the Function App) early.** It is the only item with no precedent and the only
   one with a fallback that changes the story's shape.
4. **Resolve FR20 (`anonymise`) before touching `stagingdlrm-domain-transformations`.** The cheap check
   is whether `uk.gov.justice.services:stream-transformation-*` still resolves on the 25.104.x chain.
5. **Treat FR13 as an investigation, not a task.** The BC-12 interaction may turn out to be this repo's
   biggest deploy risk, and it is cheaper to find in design than in a failed sandbox deploy.
6. **Plan the follow-up image PR into the story from the start** (FR21). Nine fleet contexts learned
   this the expensive way.
7. **Re-read `docs/architecture/dlrm-flow-reference.md` and `material-file-flow.md` before migrating
   `StagingDlrmCommandHelper` and `EventGridMonitorHelper`.** Both classes carry the BC-11 pin and the
   outcome-write branching (§2.6's four paths) that must survive the `javax`→`jakarta` sweep unchanged
   in behaviour — the architecture docs are the fastest way to confirm nothing downstream of a renamed
   import silently changed which write-path fires.
