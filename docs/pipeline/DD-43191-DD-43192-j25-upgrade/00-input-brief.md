# Input brief — Java 25 / WildFly 40 / Jakarta EE 11 upgrade of stagingDLRM

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this directory.

| | |
|---|---|
| Epic | [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191) — Java 25 upgrade, DLRM contexts |
| Story | [DD-43192](https://tools.hmcts.net/jira/browse/DD-43192) — stagingDLRM, **upgrade stage** |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |
| Target branch | **`team/25.104.x`** — cut from `main` before the parity story; this story is the second of two PRs onto it |
| Depends on | [DD-43192 parity stage](../DD-43191-DD-43192-j25-parity/00-input-brief.md) — **hard dependency**, see below |
| Platform ticket | PEG-3296 |

## The epic this story belongs to

**DD-43191 — Java 25 upgrade, DLRM contexts.** Move `cpp-context-stagingdlrm` and
`cpp-context-prosecution-casefile-dlrm` from Java 17 to Java 25 (WildFly 26.1→40, Jakarta EE 8→10/11,
~30 transitive library bumps), de-risked by behavioural-parity tests written and executed on J17 first.

Four pipelines: parity and upgrade, for each of the two repos. Cross-cutting decisions live in two ADRs
and are **not restated** here:

- [**`DD-43191-j25-upgrade-mechanics`**](../adrs/DD-43191-j25-upgrade-mechanics.md) — branch/milestone strategy,
  Function App JDK, BC-12 func-app carve-out, the `anonymise` open question, pipeline track.
  **Read this before stage 2.**
- [**`DD-43191-j25-parity-method`**](../adrs/DD-43191-j25-parity-method.md) — the parity tests this story must
  keep green, and the BC matrix explaining which risks apply here.

## This story's request

Upgrade the context to Java 25 / WildFly 40 / Jakarta EE 11 on a new `team/25.104.x` branch, keeping the
DD-43192 parity tests green, and produce a QA Docker image.

**The hard dependency, and why it is hard.** Both stages share `team/25.104.x`. The parity PR must
merge into it **while it is still J17** — that is what makes its runs J17 evidence. This story is what
ends that state, so it must not start until the parity PR has landed (the parity-method ADR's Method 1, the upgrade-mechanics ADR
decision 1). Sequence:

```
cut team/25.104.x from main  ▸  parity PR → team/25.104.x  ▸  upgrade PR → team/25.104.x  ▸  pipeline/Docker fix PR
```

A useful consequence of the shared branch: the parity tests are migrated `javax`→`jakarta` by **this
story's own sweep**, along with every other source file. They are authored once, not twice.

**Reference implementation:** `cpp-context-prosecution-casefile` commit `122a5a8fdc` on `team/25.104.x`
(401 files, +5870/−5018), checked out locally at `../cpp-context-prosecution-casefile`. Take its
**shape, not its version numbers** — it pinned `25.104.0-M4` and has since been bumped twice.

**Target versions** (tracker, 06 Aug 2026 — reconfirm at stage 5):
`cpp-platform-maven-service-parent-pom` `17.104.1` → **`25.104.0-M10`**;
`cpp-platform-core-domain` `17.104.4` → **`25.104.0-M11`**.

**Known work, from the fleet's recorded failures and a scan of this repo:**

| Area | Detail |
|---|---|
| `javax.json` → jakarta/parsson | 6 coordinates: command-handler, event-listener, domain-event, domain-aggregate, **and the func-app** (`org.glassfish:javax.json:1.0.2`). This is the largest mechanical item |
| Generator plugins | `messaging-client-generator` needs parsson in its **plugin** deps (system-scheduling hit this); `rest-client-generator` needs the jakartaee-api swap (system-announcement hit this). This repo uses all four generators + RAML |
| `stagingdlrm-azure-functions` → Java 25 | Verified GA on Azure Functions to May 2029, Windows included. 6 pom items, listed in the upgrade-mechanics ADR decision 4, with a documented fallback to 17 |
| BC-12 carve-out | The fleet's `resteasy-client` → `provided` fix must **not** be applied to the func-app — it is a standalone JAR and needs them bundled. The upgrade-mechanics ADR decision 5 |
| `@Inject EntityManager` | → `@PersistenceContext(unitName)` (staging-dcs) — *likely N/A here, no JPA code* |
| `persistence.xml` | 1.0 → 3.0 namespace (system-announcement) |
| Pipeline | `centos8-j17` → `ubuntu-j25`; `wildfly40` template ref; `aksDeployBranch`; Dockerfile base Ubuntu 24.04, RHEL `yum` lines removed; jacoco → 0.8.14 |
| `jboss-deployment-structure.xml` | Reference added one — check whether this repo needs it |

**Not needed here, verified:** the "material-client decoupling" half of the standard fleet upgrade —
neither DLRM repo has that dependency, so no decouple PR (the upgrade-mechanics ADR decision 3).

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Pipeline shape | Four pipelines — parity + upgrade, per repo. This is the stagingDLRM upgrade one |
| Who cuts the branch | We do, from `main`, after the parity PR merges |
| Milestone target | **Latest** (`service-parent-pom M10` / `coredomain M11`), not the reference's M4 |
| Function App JDK | **Moves to 25.** Fallback to 17 documented in the upgrade-mechanics ADR decision 4 if the plugin or library blocks it |
| `anonymise` module | **OPEN** — must be closed at the stage 2 gate. Default if unresolved: retain and migrate. The upgrade-mechanics ADR decision 6 |

## Scope boundaries

| In scope | Out of scope |
|---|---|
| All pom/jakarta/codegen changes; func-app JDK bump; migrating the parity tests to `jakarta` | Writing parity tests — DD-43192 parity stage, already merged to this branch |
| `azure-pipelines.yaml` + `Dockerfile` for the `wildfly40`/`ubuntu-j25` track | `cpp-context-prosecution-casefile-dlrm` — DD-43194, its own pipeline |
| Producing a QA Docker image (this is the definition of done) | Framework/platform repo changes — PEG-3296 owns those |
| `stagingdlrm-domain-transformation-anonymise` decision + action | Production release — the tracker shows only `support` has gone that far |

## Known blockers / open items

- **`liquibase.properties` carries a property Liquibase 5 has removed — a live deploy blocker.**
  Found during a dependency and configuration scan of this repo on 2026-09-01. Verified against the
  file, not inferred.
  `stagingdlrm-viewstore/stagingdlrm-viewstore-liquibase/src/main/resources/liquibase.properties`
  declares `liquibase.hub.mode: off`. Liquibase Hub was sunset and its configuration removed; the
  property existed only to silence Hub warnings in 4.1.0–4.17.2. On Liquibase 5.0.3 this is an
  unknown-parameter failure in the **K8s pre-install migration job**, before any application code runs.
  BC-07's residual is exactly *"a per-context sweep of copied `liquibase.properties`"* — fixed in the 15
  framework repos, never swept here. **Delete the line; verify `liquibase.headless` the same way** (lower
  confidence) by running Liquibase 5 against the file. It was deliberately not fixed in the parity story:
  there is no J17 behaviour to pin, and FR15/AC9 there keep that PR to test-only changes.

- **`anonymise` module — unresolved.** `defence`, `resulting` and `results` all removed theirs during
  the J25 upgrade; this repo has one, pcfdlrm does not. The upgrade-mechanics ADR decision 6 sets out both readings and
  the default. Close it at the stage 2 gate.
- **Expect the Docker image build to fail first time.** Nine contexts on the tracker are "Merged — no
  Docker image produced (build failed)", several still carrying an open pipeline-revert or Docker-fix
  PR. **The image build never runs on a pull request** — `azure-pipelines.yaml` sends PR builds to
  `context-verify.yaml` (SonarQube only) and only merge builds to `context-validation.yaml`, which is
  where `docker-build.yaml` pushes to `crmdvrepo01.azurecr.io`. So whatever breaks it is undiscoverable
  until after this story's PR has merged. The image stays **in this story's scope** — treat "QA Docker
  image available", not "upgrade PR merged", as done — but expect it to take a second merge to get
  there.
- **`azure-functions-maven-plugin 1.24.0` may not accept `javaVersion 25`.** Unverifiable from the repo;
  the highest-uncertainty item in the epic, with no fleet precedent (no other CPP context has an Azure
  Functions module). Sequence it early enough to fall back without re-planning.
- **No local WildFly 40 Docker image exists** — `cpp-developers-docker` was still on the 26.1.3 image as
  of the investigation report; the tracker says a `java-25` branch now exists. Confirm before relying on
  local ITs.
- **BC-15 watch item.** `coredomain.version` moves `17.104.4` → M11. The report records 8 schema fields
  missing from the J25 core-domain line, pending a release-management cherry-pick. Check whether any
  field this context reads is among them before bumping.
- Owner is unassigned for stagingdlrm on the PEG-3296 tracker. Confirm with Platform Engineering before
  cutting the branch.
