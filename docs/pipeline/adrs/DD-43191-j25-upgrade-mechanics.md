# ADR `DD-43191-j25-upgrade-mechanics` — J25 upgrade mechanics: branch strategy, milestone target, and Function App JDK

## Status

**Accepted 2026-09-02** — at the stage 1 review of [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191),
covering all four pipelines in the epic. **Decision 6 is OPEN** and must be closed at the stage 2 gate of
the stagingDLRM upgrade story; every other decision here is settled and may be built against.

> **Mirrored.** This file exists in both DLRM repos:
> `cpp-context-stagingdlrm/docs/pipeline/adrs/DD-43191-j25-upgrade-mechanics.md` and
> `cpp-context-prosecution-casefile-dlrm/docs/pipeline/adrs/DD-43191-j25-upgrade-mechanics.md`.
> **Amend both, or neither.** The two repos are worked by different developers in isolation, so each
> repo's pipeline must be self-contained — a cross-repo link would leave one dev reading a file they do
> not have checked out. Sections that apply to only one repo say so inline, so the two copies stay
> byte-identical rather than forking into near-copies.

## Date

2026-09-02

## Scope — four pipelines, two repos

| Story | Repo | Stage |
|---|---|---|
| DD-43192 parity | `cpp-context-stagingdlrm` | writes the J17 evidence |
| DD-43192 upgrade | `cpp-context-stagingdlrm` | consumes it |
| DD-43194 parity | `cpp-context-prosecution-casefile-dlrm` | writes the J17 evidence |
| DD-43194 upgrade | `cpp-context-prosecution-casefile-dlrm` | consumes it |

Platform ticket **PEG-3296** (J25 upgrade) owns the framework and platform repos; **PEG-3377** owns
parity testing. Neither is in scope for these four stories.

The **two repos are independently deliverable**. There is no shared code and no ordering constraint
between them — either repo may go first, and neither dev waits on the other's gates. The ordering
constraint in decision 1 is **within** a repo, between its own two stages.

## Context

Both DLRM contexts move Java 17 → Java 25, WildFly 26.1 → 40, Jakarta EE 8 → 10/11, with roughly thirty
transitive library bumps behind the platform parent POM. Nothing in that set fails at compile time in
the interesting cases — the risk is behavioural, which is why the epic is split into a parity stage and
an upgrade stage rather than run as a single sweep ([the parity-method ADR](./DD-43191-j25-parity-method.md)).

Two facts shape everything below:

- **The parity tests are only evidence of J17 behaviour for as long as nothing has upgraded the branch
  they run on.** The moment the parent POM moves, a green run proves the *new* behaviour, not the old.
- **The fleet has already made these mistakes.** Around forty contexts have run this upgrade. Their
  recorded failures — generator plugins missing parsson, `javaee-api` surviving inside plugin
  dependency blocks, jacoco choking on JDK 25 bytecode, Docker images failing after merge — are cheaper
  to inherit than to rediscover.

---

## Decision 1 — One branch per repo, two PRs onto it, parity first

Each repo cuts **`team/25.104.x` from `main`** before its parity story starts, and both stages land on
that one branch:

```
cut team/25.104.x from main  ▸  parity PR → team/25.104.x  ▸  upgrade PR → team/25.104.x  ▸  pipeline/Docker fix PR
```

**The parity PR must merge while the branch is still J17.** The upgrade story is what ends that state,
so it must not start until the parity PR has landed. Starting early does not merely risk a conflict —
it retroactively invalidates the gate the upgrade story depends on.

Two consequences worth stating:

- **The parity tests are migrated `javax`→`jakarta` by the upgrade story's own sweep**, like every other
  source file on the branch. They are authored once, not twice. Their **assertions must not change** —
  only their imports.
- **A parity test that goes red on Java 25 is a finding, not a test to relax.** If one cannot be made
  green, the upgrade story stops and the divergence is raised. Silently adjusting an assertion defeats
  the entire two-stage design.

**Rejected alternative — a branch per stage.** It removes the ordering constraint but forces the parity
tests to be written twice (once in `javax`, once in `jakarta`) or cherry-picked across a namespace
rename, which is exactly the kind of hand-editing that loses assertions.

## Decision 2 — Target the latest platform milestones, reconfirmed at implementation time

| Artefact | From | To |
|---|---|---|
| `cpp-platform-maven-service-parent-pom` | `17.104.1` | **`25.104.0-M10`** |
| `cpp-platform-core-domain` | `17.104.4` (stagingDLRM) | **`25.104.0-M11`** |

These are the PEG-3296 tracker's figures as at **06 Aug 2026** and are where every merged context sits.
**Reconfirm before starting stage 5** — if they have advanced, take the newer.

Both repos use `cpp-context-prosecution-casefile` commit `122a5a8fdc` on its own `team/25.104.x` as the
**reference implementation** (401 files, +5870/−5018). Take its **shape, not its version numbers**: it
pinned `25.104.0-M4` and has since been bumped twice. For pcfdlrm it is also the upstream context this
one forwards to, so the shape match is closer.

**Interface pins move only as far as the enforcer requires.** The reference bumped
`referencedata.version`, `progression.version` and `sjp.version` purely to satisfy the
latest-interfaces enforcer (`RequireLatestMojInterfaceRule`). Bump to what the enforcer demands and no
further — an opportunistic bump makes the diff unreviewable.

**BC-15 is a precondition on the `coredomain` bump, not a follow-up.** The investigation report records
schema fields absent from the J25 `cpp-platform-core-domain` line pending a release-management
cherry-pick. Confirm none of them feeds the context **before** moving the pin. *pcfdlrm is the more
exposed of the two* — it constructs `uk.gov.justice.core.courts.Defendant` and `ListHearingRequest`
directly, so a missing field is a compile failure rather than a runtime surprise.

## Decision 3 — No material-client decoupling PR in either repo

The standard fleet upgrade is two PRs: a material-client decoupling PR, then the upgrade itself.
**Neither DLRM repo has that dependency**, verified against both poms. The decouple PR is dropped from
both pipelines. Do not add it back on the strength of a fleet checklist.

## Decision 4 — The Azure Functions module moves to Java 25 *(stagingDLRM only)*

`cpp-context-prosecution-casefile-dlrm` has no Azure Functions module; this decision and decision 5 do
not apply there.

`stagingdlrm-azure-functions` moves to Java 25. Verified supportable: Azure Functions lists Java 25 as
GA to **May 2029** on both Windows and Linux, and this app is `<os>windows</os>` on a dedicated App
Service Plan, so the Linux-Consumption restriction does not apply.

Six pom items:

1. `maven.compiler.source` / `maven.compiler.target` 17 → 25
2. plugin `<runtime><javaVersion>` 17 → 25
3. `azure-functions-maven-plugin` `1.24.0` → a version accepting `javaVersion 25`
4. `azure-functions-java-library` `3.1.0` → newer
5. `javax:javaee-api:8.0` migrated or dropped
6. `org.glassfish:javax.json:1.0.2` → parsson

**The fallback is explicit, not implicit.** If the plugin or the library blocks Java 25, the module
stays at 17 while the WildFly modules move — the reactor tolerates a mixed target because the module is
a standalone JAR with no WildFly coupling. Its jakarta and parsson changes still land regardless, being
correctness fixes independent of the JDK. **If the fallback is taken it must be recorded in
`02-design.md` and raised as a follow-up**, never left as an undocumented inconsistency.

Item 3 is **the highest-uncertainty item in the epic** — no other CPP context has an Azure Functions
module, so there is no fleet precedent. Sequence it early enough that the fallback can be taken without
re-planning the story.

## Decision 5 — BC-12's fleet fix is carved out for the Function App *(stagingDLRM only)*

The fleet-wide BC-12 fix marks bundled RESTEasy artifacts `provided` and adds `packagingExcludes`,
because a WAR gets them from the container. **The Function App is not a WAR.** Its four compile-scope
RESTEasy artifacts — `resteasy-client`, `-jaxb-provider`, `-jackson2-provider`, `-multipart-provider` —
**must stay bundled**. Marking them `provided` compiles cleanly and then fails at runtime in Azure with
`NoClassDefFoundError`, because nothing there supplies them.

Exclude the module by name from the `provided` + `packagingExcludes` change, **and say so in the PR
description**, so a later fleet-wide sweep does not "correct" it. The parity story pins this as a
build-time assertion (the parity-method ADR, BC-12) precisely so the carve-out cannot be undone silently.

Also check any `jboss-deployment-structure.xml` against BC-12: one that disables the `jaxrs` subsystem,
combined with `packagingExcludes` stripping bundled RESTEasy, is the report's flagged possible
deploy-breaker.

## Decision 6 — The `anonymise` module: **OPEN**

*Applies to stagingDLRM only — `cpp-context-prosecution-casefile-dlrm` has no such module.*

`stagingdlrm-domain-transformation-anonymise` holds the Event Store event-log anonymisation
transformation rules. `defence`, `resulting` and `results` all **removed** their equivalent modules
during their J25 upgrades. Two readings, and the evidence does not yet separate them:

- **Mandated** — the framework dropped `stream-transformation-*` support on the 25.104.x line, and
  those three contexts removed the module because it no longer resolves. If so, removal is forced and
  the anonymisation capability is lost fleet-wide, which is a platform question, not a context one.
- **Incidental** — those three contexts removed unused modules opportunistically while the diff was
  already large. If so, this repo should retain and migrate.

**Determine which, at the stage 2 gate.** Check whether `stream-transformation-tool-api` resolves on
the 25.104.x chain, and check with PEG-3296 whether the removal was directed.

**Default if unresolved: retain and migrate.** Deleting an anonymisation rule set on an assumption is
the more expensive mistake, and a retained module that later proves unnecessary is one follow-up
commit. Whichever way it goes, **record the reasoning** — the decision, not just the outcome.

## Decision 7 — Done is a QA Docker image, not a merged PR

Nine contexts on the PEG-3296 tracker sit at *"Merged — no Docker image produced (build failed)"*,
several still carrying an open pipeline-revert or Docker-fix PR.

**The image build never runs on a pull request.** `azure-pipelines.yaml` sends PR builds to
`context-verify.yaml` (SonarQube only), and only merge builds to `context-validation.yaml`, which is
where `docker-build.yaml` pushes to `crmdvrepo01.azurecr.io`. So whatever breaks the image is
undiscoverable until after the upgrade PR has merged.

The image therefore stays **in the upgrade story's scope** — treat *"QA Docker image available"*, not
*"upgrade PR merged"*, as done — and budget for a follow-up pipeline/image PR as part of the story
rather than as an afterthought.

The build-track changes are common to both repos: `centos8-j17` → `ubuntu-j25`, the template ref moved
to the **`wildfly40`** track, `aksDeployBranch` set accordingly, and **jacoco at 0.8.14 or later** (the
parent's 0.8.12 does not handle JDK 25 bytecode; every migrated context has needed a local override).

**Where the container image comes from must be established, not assumed.** Neither repo has a
`Dockerfile` at its root. The reference context ships one at
`docker/Dockerfile_prosecutioncasefile-service`; `support`, `system-id-mapper` and `notification` have
none at all and all three have QA images; and `context-validation.yaml`'s image step is gated only by a
repo-name exclusion list that excludes neither DLRM repo, with `dockerfilePath` defaulting to
`'Dockerfile'`. Determine which pattern applies, then either fix the file the `wildfly40` track needs or
record that none exists. Do not create a `Dockerfile` to satisfy a checklist.

## Consequences

- Each repo's two stages are **strictly sequential**; its parity story is on the critical path and
  cannot be deferred without stalling its upgrade.
- The two repos are **not** sequential with respect to each other. Nothing in this ADR makes one dev
  wait on the other.
- The mixed-JDK fallback in decision 4 means the stagingDLRM reactor may legitimately build one module
  at 17 and the rest at 25. Reviewers should expect that shape rather than flag it.
- Decision 6 being open means the stagingDLRM upgrade story carries one genuine design decision into
  stage 2. That is deliberate — it is not a decision stage 1 had the evidence to make.
- Integration tests need a WildFly 40 image. `cpp-developers-docker` was on the 26.1.3 image as of the
  investigation report; the tracker records a `java-25` branch since. Confirm before relying on local
  ITs, and if none is available, that is a blocker to record and escalate — not a reason to declare
  done on unit tests alone.
