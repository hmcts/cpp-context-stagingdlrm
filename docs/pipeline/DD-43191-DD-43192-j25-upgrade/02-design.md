# Design — DD-43192: Java 25 / WildFly 40 / Jakarta EE 11 upgrade of stagingDLRM

> Stage 2 artefact (design). Source: [`00-input-brief.md`](./00-input-brief.md) and
> [`01-requirements.md`](./01-requirements.md); mechanics fixed by
> [the upgrade-mechanics ADR](../adrs/DD-43191-j25-upgrade-mechanics.md) and the regression gate by
> [`docs/j25-parity-checklist.md`](../../j25-parity-checklist.md).
>
> Every seam below was verified against the actual code on `team/25.104.x` at commit **`b23ea10`**
> on **2026-09-10**, and — where the mechanism was checkable rather than merely readable — against the
> cached platform POMs in `~/.m2`, the decompiled bytecode of the resolved artefacts, an executed
> throwaway probe build on JDK 25, **and, once a truststore fix unblocked it (below), a real online
> build plus a full integration-test run against an actual WildFly 40 / JDK 25 container.** Claims are
> labelled with what was actually done. Where a requirement's own premise turned out to be wrong, the
> correction is stated in that item rather than quietly designed around; the requirements documents are
> **not** rewritten (same convention the parity-method ADR's decisions 7 and 8 established).
>
> **Update, 2026-09-10, same day.** Everything below this document originally recorded as "(c) blocked
> in this sandbox — CPP internal Artifactory unreachable" was **wrong**, and has since been corrected in
> place (not silently — see the "Environment" section immediately below for what actually happened and
> the Cross-cutting section's "What the cert fix changed" table for the full before/after). The short
> version: the Artifactory was never network-unreachable — every `curl`/`mvn -o` check this document
> originally relied on was failing on an **untrusted self-signed internal CA certificate**
> (`CN=<internal-root-ca>`), which read as a connection failure unless probed with `curl -v` specifically.
> Importing that CA into JDK 25's cacerts turned every "(c) blocked" item below into "(a) verified for
> real" — including, ultimately, a full `mvn clean install` and a full integration-test run against a
> real WildFly 40 image. Three genuine code/config defects surfaced once real builds could actually run
> (none of which any amount of model-validation-only checking could have caught); all three are fixed
> and verified. See FR2, FR3, FR9, FR15, FR22, and the Cross-cutting section for the detail.

## Verification legend

Every item carries one of three statuses, per the stage-2 brief:

| | Meaning |
|---|---|
| **(a) verifiable here** | Implementable *and* verifiable in this sandbox now — the check was run and its result is quoted |
| **(b) mechanical only** | The change is unambiguous and can be written, but its effect cannot be observed here. The reason is named |
| **(c) blocked** | Cannot be completed here at all. The blocker is named |

Statuses are about **this sandbox**, not about the story. Nothing marked (b) or (c) is a design gap;
each names the specific missing capability.

## Environment: what this sandbox can and cannot do

Established 2026-09-10. The first pass below (before the truststore fix) turned out to
misdiagnose the CPP internal Artifactory as network-unreachable; the second pass (same day, after the
fix) is what actually holds.

| Capability | State | Evidence |
|---|---|---|
| JDK 25 | **Available** — `openjdk 25.0.4.1 2026-08-18`, Homebrew, at `/opt/homebrew/opt/openjdk@25`. **Not** symlinked into `/Library/Java/JavaVirtualMachines`, so `/usr/libexec/java_home -V` lists only JDK 17 | `JAVA_HOME=/opt/homebrew/opt/openjdk@25 java -version`. Every JDK-25 command in this document sets `JAVA_HOME` explicitly |
| Maven | 3.9.16 at `~/hmcts/apache-maven-3.9.16`, default JDK 17 | `mvn -v` |
| Public Maven Central | **Reachable** | `curl` on `repo1.maven.org` returns 200/404 as expected, not a connect failure |
| github.com | **Reachable** | — |
| CPP internal Artifactory (`<artifactory-host>`) | **Reachable — was misdiagnosed as unreachable.** DNS resolves, TCP connects; the actual failure was `curl: (60) SSL certificate problem: self signed certificate in certificate chain`. The server's leaf cert (`CN=<artifactory-host>`) chains to a self-signed internal root, `CN=<internal-root-ca>` (valid 2018–2028), that this freshly-installed JDK 25 (and this box's general TLS trust store) had never been told to trust. **Fixed**: extracted the root via `openssl s_client -showcerts`, imported it into JDK 25's cacerts with `keytool -importcert`. After that, `mvn` (no `-o`) resolves the internal Artifactory exactly as it should | `keytool -list -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -alias cpp-internal-artifactory-ca` → present. A tiny `HttpsURLConnection` test against `/artifactory/api/repositories` → HTTP 200 |
| Docker daemon | **Running.** WildFly 40 / JDK 25 / Camunda 7.24 image **built successfully** from `cpp-developers-docker`'s `java-25` branch, once `az acr login --name crmdvrepo01` (an already-authenticated `az` session) unlocked pulling its private base image (`crmdvrepo01.azurecr.io/hmcts/wildfly:40.0.0.Finaljdk25_Camunda7.24_latest`) | `docker compose build cpp-wildfly` → `Image containers-cpp-wildfly Built` |
| `service-parent-pom:25.104.0-M10` | **Cached, resolves offline** (and online) | Root POM validates on the M10 chain on JDK 25 — see FR2 |
| `cpp-platform-core-domain:25.104.0-M11` and every other previously-"missing" internal artefact | **Resolves fine once the cert is trusted** — fetched live from the Artifactory, no longer just a cached-proxy substitution | `mvn dependency:get -Dartifact=uk.gov.moj.cpp.core.domain:common-core-domain:25.104.0-M11` → `BUILD SUCCESS`, jar present in `~/.m2` |

**There is no environment-caused blocker left for AC2, FR3, FR9, FR15, or AC13.** Every one of them was
originally marked "(c) blocked" in this document on the strength of the Artifactory being unreachable;
that diagnosis was wrong, the fix took one `keytool` command, and every one of those items has since
been re-verified for real (see their own sections, and Cross-cutting's "What the cert fix changed"
table). **AC12 (the QA Docker image) remains genuinely blocked regardless of this fix** — that one is
structural, by pipeline design (the image step never runs on a PR build, in any environment), not
environmental — see FR21.

### The probe build, and what happened after the cert fix

**Before the cert fix** (kept here for the record, since it is genuinely useful evidence about the
migration's coordinate inventory, even though its "blocked" conclusion did not hold): a throwaway copy
of the tree (`git archive HEAD | tar -x` into the session scratchpad — the working tree was never
touched by this probe) had the mechanical migration applied by script and was built offline with
`coredomain.version` substituted to the cached `25.104.0-M9`. Before the migration, Maven refused to
*read* three POMs (seven `'dependencies.dependency.version' … is missing` errors — FR23's failure
mode, reproduced exactly, plus two coordinates FR23 does not list). After the migration, all model-read
errors were gone and the full 26-module reactor's *model* constructed cleanly — proof the coordinate
inventory in FR5/FR7/FR23 is complete. The probe then hit `service-common-resources:25.104.0-M2` and
`framework-api-validator:25.104.0-M12` as apparently-unobtainable internal artefacts and stopped there,
concluding (wrongly, as it turned out) that this was an environment wall rather than a certificate one.

**After the cert fix**, the real thing was done instead of a substitute: `git archive`/`git stash` were
not needed — the actual working tree, with `coredomain.version` genuinely at `25.104.0-M11`, was built
directly:

```
JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn clean install --batch-mode
```

This surfaced two further real, previously-unknown defects (both fixed, both described in FR9 and FR15
below), after which:

```
[INFO] Reactor Summary for stagingdlrm Context - Parent Module 25.104.27-DLRMJ25-SNAPSHOT:
[INFO]  ... (all 26 modules) ... SUCCESS
[INFO] BUILD SUCCESS
```

with every unit test across the reactor passing, including the parity gate itself:

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ...accesscontrol.AccessControlTest
```

Then, against a real WildFly 40 image built from `cpp-developers-docker`'s `java-25` branch,
`./runIntegrationTests.sh` surfaced one more real defect (FR4/system-id-mapper, below) and, once fixed,
passed completely — all 3 IT classes, 19 tests, 0 failures (see FR22).

## Status summary

**Revised after the cert-trust fix (2026-09-10, same day).** Almost everything below is now **(a)
verified for real** — a genuine online `mvn clean install` (all 26 modules, all unit tests, including
the parity gate) and a genuine `./runIntegrationTests.sh` against a real WildFly 40 / JDK 25 container
(all 3 IT classes, 19 tests). Three real defects surfaced and were fixed along the way (FR9, FR15, and a
new FR4 finding); none was environment-related — all three would have needed fixing on any machine.

| FR | Item | Status |
|---|---|---|
| FR1 | Parity PR merged first | **(a)** — already satisfied, verified in `git log` |
| FR1a | Migrate parity tests' imports | **(a)** — verified **no-op**: the surviving parity test has zero `javax` imports |
| FR2 | `service-parent-pom` M10 / `coredomain` M11 | **(a) both** — full reactor builds clean online against the real M10/M11 chain |
| FR3 | Parity tests green on J25 | **(a) — genuinely green.** `AccessControlTest`: `Tests run: 4, Failures: 0`, real JDK 25, real M11 chain |
| FR4 | Interface pins move only as far as the enforcer requires | **(a) — and one pin needed to move further than "the enforcer" alone would have shown.** `system.id-mapper.version` bumped `17.103.5`→`25.104.0-M11`: the old version's CDI producer is `javax.enterprise`/`javax.inject`-annotated and WELD-fails on a real Jakarta EE 11 container. `pcfdlrm.version`/`progression.version` left unchanged — confirmed not exposed, see FR4 |
| FR5 | Migrate only the Jakarta namespaces | **(a)** — scripted rewrite executed; 75 of 76 lines moved |
| FR6 | Do not rename JDK `javax.*` | **(a)** — one site, allowlist verified to skip it |
| FR7 | `javaee-api` swap everywhere incl. plugin blocks | **(a)** — 12 sites enumerated; post-swap reactor builds and passes tests |
| FR8 | `beans.xml` × 10 + `persistence.xml` | **(a)** — rewritten, all 10 retain `bean-discovery-mode="all"` |
| FR9 | Two generator fixes | **(a) — code generation confirmed to succeed, and a third, real defect found and fixed along the way.** `raml-parser:0.8.18`'s `JaxbTagResolver` needs old-style `javax.xml.bind.SchemaOutputResolver`; fixed with a plugin-scoped `javax.xml.bind:jaxb-api:2.3.1` compat dependency in `event-processor` only — see FR9 |
| FR10 | Generated-artefact inventory must not shrink | **(a) — confirmed by the full green build, and it does not shrink after all.** The BC-15 concern (checked against real M11, not the M9 proxy) turned out not to apply at the actual target — see FR10 |
| FR11 | `ubuntu-j25` / `wildfly40` / `aksDeployBranch` | **(a)** — exact target shape read off the reference's current file |
| FR12 | jacoco ≥ 0.8.14 | **(a)** + **correction**: already 0.8.14 from the parent chain; **no local override needed** |
| FR13 | Assess `jboss-deployment-structure.xml` / BC-12 | **(a)** — **settled empirically**: the existing file is dead; BC-12's interaction does not arise here |
| FR14 | Establish where the container image comes from | **(a)** — **settled**: `docker/Dockerfile_stagingdlrm-service` exists, needs no base-image change |
| FR15 | Function App → Java 25 | **(a) — builds and its own unit tests pass on real JDK 25**, after two more real fixes discovered by actually testing: the `azure-functions-maven-plugin` version had to stay a literal string (not the property this design first tried), and `test-utils-common` had to stay at `2.4.1` (the BOM-managed bump this design first recommended dropped a class several tests use). See FR15 |
| FR16 | Explicit fallback | **(a) — not needed.** Java 25 works; the fallback mechanism is designed and its trigger condition documented but was never triggered |
| FR17 | BC-12 carve-out for the Function App | **(a)** — carve-out is *structural*, not a discipline: verified why, and the module builds/tests green with RESTEasy 7 bundled |
| FR18 | Delete `liquibase.hub.mode` | **(a) both halves now** — deletion done, and a real Liquibase 4.10.0 run against a real Postgres (via the IT suite) accepted the file |
| FR19 | Check core-domain fields before bumping | **(a) — checked against the real M11 jar**, not just the M9 proxy: same two `criminal-court-public-model` schemas missing, neither referenced here |
| FR20 | `anonymise` decision | **(a)** — **CLOSED: retain, migrate nothing, raise a follow-up.** New evidence, see FR20 |
| FR21 | QA Docker image | **(c) still blocked — structurally, not environmentally.** The image step never runs on a PR build, in any environment; needs an actual merge build |
| FR22 | ITs on the J25 stack | **(a) — genuinely green.** Real WildFly 40 image built, all 3 IT classes pass: 19 tests, 0 failures |
| FR23 | Dead persistence coordinates | **(a)** — reproduced exactly, plus 2 coordinates FR23 missed |
| FR24 | Keep or delete `stagingdlrm-viewstore-persistence` | **(a)** — **decided: retain, trim.** Reasoning in FR24 |

---

## Per-item design

### FR1 — Do not start until the parity PR has merged

**Status: (a) verifiable here — and already satisfied.**

`git log` on `team/25.104.x` shows, oldest first:

```
7789e63  DD-43191: Java25 upgrade stage1 and branch setup.
e5b7517  New 17.104.25-DLRMJ25-SNAPSHOT
a9473ef  DD-43192: J17 parity tests for the Java 25 upgrade (stagingDLRM) (#53)   ← the parity PR
428105b  New 17.104.26-DLRMJ25-SNAPSHOT
f2ef772  DD-43192: start upgrade stage - pom version to 25.104.26-DLRMJ25-SNAPSHOT
d8bb295  Merge pull request #54 from hmcts/DD-43192-j25-upgrade-pom-version
b23ea10  New 25.104.27-DLRMJ25-SNAPSHOT                                          ← HEAD
```

The parity merge (`a9473ef`, 2026-09-07) precedes every upgrade-stage commit. **AC1 is met and needs
no design.** Note that the branch was still J17 at `a9473ef` — `service-parent-pom` was `17.104.1`
throughout the parity runs, which is what makes them J17 evidence (Method 1 of the parity-method ADR).

One thing the history already gives us: **the project's own version line has already moved.**
`f2ef772`/PR #54 took the reactor from `17.104.26-DLRMJ25-SNAPSHOT` to `25.104.26-…`, and `b23ea10`
to `25.104.27-DLRMJ25-SNAPSHOT`. That is the *project* version, not the parent pin — the parent is
still `17.104.1` at `pom.xml:8`. So FR2's work is the parent and the interface pins only; do not
re-do the version-line change.

### FR1a — Migrate the parity tests as part of the sweep

**Status: (a) verifiable here — and a verified no-op.**

The parity PR `a9473ef` changed exactly one source file: `AccessControlTest.java`, `+28` lines (the
`shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission` / `shouldNotAllow…` pair). Its full import
list was read:

```
stagingdlrm-command/stagingdlrm-command-api/src/test/java/uk/gov/moj/cpp/stagingdlrm/command/api/accesscontrol/AccessControlTest.java
```

carries **zero `javax.*` imports** — only `java.util.*`, `org.junit.jupiter`, `org.mockito`,
`org.kie.api.runtime.ExecutionResults`, and `uk.gov.justice`/`uk.gov.moj` framework types.

**Design: nothing to do for FR1a.** The reason is recorded in the parity checklist rather than being
a surprise here: the parity story authored eleven test artefacts and then removed all but the two
`AccessControlTest` methods (its checklist's final status distribution — 🟢 1, 📝 2, ⚪ 7, 🟡 2, "one
test class survives"). Every removed test was in `stagingdlrm-azure-functions`,
`stagingdlrm-domain-value-schema` or `stagingdlrm-viewstore-liquibase` and carried the `javax.json`
imports FR1a was written to anticipate. **They are gone, so the single-branch layout's stated payoff —
"the tests are authored once, not twice" — did not actually get exercised.** That is worth saying
out loud, because it changes the FR3 risk picture below.

`AccessControlTest` will nonetheless be recompiled and re-run by the sweep, and its assertions must
not be touched. It is the one and only automated regression gate this story has.

### FR2 — Target the latest platform milestones

**Status: (a) both — verified for real, online, against the actual M10/M11 chain.**

Two edits in `pom.xml`:

| Site | From | To |
|---|---|---|
| `pom.xml:8` | `<version>17.104.1</version>` (parent `uk.gov.moj.cpp.common:service-parent-pom`) | `<version>25.104.0-M10</version>` |
| `pom.xml:38` | `<coredomain.version>17.104.4</coredomain.version>` | `<coredomain.version>25.104.0-M11</coredomain.version>` |

**The parent bump is verified good.** With `pom.xml:8` at M10 and JDK 25,
`JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn -o -N validate` → **`BUILD SUCCESS`** in the probe. The
whole parent chain resolves offline from cache: `service-parent-pom 25.104.0-M10` →
`platform-libraries-parent-pom 25.104.0-M11` → `parent-pom 25.104.0-M2`, with
`common-bom 25.104.0-M5` and `maven-common-bom 25.104.0-M7` imported. What that chain sets, read
directly out of the cached POMs, is the whole substance of this upgrade:

| Property | J17 chain (`parent-pom 17.10.12`) | J25 chain (`parent-pom 25.104.0-M2`) | Consequence |
|---|---|---|---|
| `compiler.release` / `.source` / `.target` | `17` | **`25`** | Every module compiles at 25 automatically — see FR15 |
| `enforcer.java.version.range` | `[17,)` | **`[25,)`** | The build now *requires* JDK 25 |
| `javaee-api.version` | `8.0.1` | **`11.0.0`** | Jakarta EE 11. See FR7 for why this is load-bearing |
| `plugins.jacoco.version` | `0.8.8` (overridden to 0.8.12 downstream) | **`0.8.14`** | FR12 needs no local override |
| `wildfly.version` (`maven-common-bom`) | — | **`40.0.0.Final`** | Confirms the WildFly 40 target |
| `resteasy.version` / `resteasy-client.version` | — | **`7.0.0.Final`** | Jakarta-namespace RESTEasy. See FR17 |
| `liquibase.version` | `4.10.0` | **`4.10.0`** — unchanged | See FR18; contradicts FR18's premise |
| `parsson.version` | — | **`1.1.7`**, with a comment explaining why | See FR9 |
| DeltaSpike artefacts in `maven-common-bom` | **8** managed | **0** | See FR23 |
| `hibernate-entitymanager` | managed | **absent** (only `hibernate-core`) | See FR23 |
| `javax:javaee-api` | managed `8.0.1` | **still managed `8.0.1`** — "Legacy Java EE 8 API — pinned for Activiti 5.x compatibility" | See FR7; this is why AC5 needs a grep |

**The core-domain bump, originally recorded as blocked, is fully resolved.** `25.104.0-M11` was not
cached and, at the time, appeared unfetchable — that turned out to be a certificate-trust problem (see
the Environment section), not a network or artefact-availability one. Once JDK 25's cacerts trusted the
internal CA, `25.104.0-M11` (and every other artefact this section originally called unobtainable)
resolved on the first try. **`stagingdlrm-domain-value-schema` (compile dep, `pom.xml:14–18`),
`stagingdlrm-domain-event`'s `pojo-generation-plugin` block (`pom.xml:189–198`), and
`stagingdlrm-event-processor`'s `pojo-generation-plugin` block (`pom.xml:232–236`) all build clean
against the real M11 jar** — confirmed by the full green `mvn clean install`, not a proxy. FR19's
schema-diff check has since been re-run against the real M11 jar too (not just the M9 proxy) with the
same result — see FR19.

**Reconfirm M10/M11 against the PEG-3296 tracker before merging regardless** — FR2 says so, and the
milestone line moves; the versions used here were current as of this story's implementation date
(2026-09-10), not necessarily the tracker's original 06 Aug 2026 figures.

### FR3 — Parity tests green on Java 25 at the end

**Status: (a) — genuinely green, on real Java 25, against the real M11 chain.**

The gate is one test class, `AccessControlTest`, 4 tests (the parity checklist's BC-20 row records
`Tests run: 4, Failures: 0` on J17, 2026-09-07). Run for real after the cert-trust fix, with JDK 25,
against the real `platform-libraries-bom:25.104.0-M11` chain (not a proxy):

```
JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=AccessControlTest
→ Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

This was originally recorded as "(c) blocked — cannot run tests", on the assumption that the module's
`access-control-drools`/`access-control-test-utils` dependencies (which resolve through
`platform-libraries-bom` at M11) were unobtainable. That assumption was wrong for the same
certificate-trust reason as FR2 — once fixed, this ran on the first attempt, no proxy substitution
needed.

**What the design must still say about the risk, honestly, even with a genuine green result.** FR3 is
described in the requirements as "the story's real risk and its real value". A green run does not
change the fact that this gate is narrow:

- The gate is **4 assertions over 2 Drools rules in one WAR's access control**. It does not touch the
  Function App, the schema catalogue, the JSON-P provider, the outcome-write branching, the RESTEasy
  packaging, or anything in `dlrm-flow-reference.md` §2.
- Both items the planning documents called *primary* (BC-13, DLRM-01) have **zero** coverage, as do
  BC-11, BC-12 and all four BC-21 generator families. The checklist says this itself, twice, in terms
  that should be read before anyone treats a green `AccessControlTest` as clearance: *"this story's
  regression gate has no tested primary item"*, and on BC-12, *"the upgrade stage has no automated
  guard against this specific regression; only the ADR's decision 5, read and followed by whoever
  performs the RESTEasy `provided` sweep, prevents it."*
- **Design response, since re-writing the parity suite is out of scope:** convert the three highest
  unguarded risks into *design-time* determinations in this document rather than leaving them to a
  test that does not exist. That is exactly what FR13 (BC-12's deploy interaction — settled below),
  FR17 (the RESTEasy carve-out — shown below to be structural, so it *cannot* be undone by a sweep)
  and FR20 (`anonymise`) now do. Where a determination could not be made, it is named as residual
  risk in Cross-cutting.

A red `AccessControlTest` remains a finding, not a test to relax (ADR decision 1). But nobody should
mistake its greenness for behavioural parity of this context.

### FR4 — Interface pins move only as far as the enforcer requires

**Status: (a) — and the thing that actually determined "how far" was not the enforcer at all, it was a
real WELD deployment failure on a real WildFly 40 container.**

The four candidate pins, all in `pom.xml`:

| Site | Property | Original | Final |
|---|---|---|---|
| `pom.xml:38` | `coredomain.version` | `17.104.4` | `25.104.0-M11` (FR2) |
| `pom.xml:39` | `pcfdlrm.version` | `17.103.24` | **unchanged** — confirmed not exposed, below |
| `pom.xml:40` | `system.id-mapper.version` | `17.103.5` | **`25.104.0-M11`** — bumped, real deploy-time failure |
| `pom.xml:41` | `progression.version` | `17.0.297` | **unchanged** — confirmed not exposed, below |

`dlrm-flow-reference.md` §1.2's outbound table confirms all three non-core-domain pins are real
downstream callers: `pcfdlrm` (`receive-migrated-case-file`), `system-id-mapper`
(`/rest/systemid/mappings`), and `progression` — the last consumed only as a `raml`-classified
artefact by the `rest-client-generator-plugin` (`stagingdlrm-event-processor/pom.xml:121–132`).

**`RequireLatestMojInterfaceRule` passed, and — correctly this time — that still means nothing
decisive.** It ran online, against the real Artifactory, and found nothing requiring a bump. But this
rule only checks metadata *freshness*; it has no idea whether an already-resolvable old version is
Jakarta-compatible at deploy time. That is exactly the gap that mattered here.

**What actually forced a bump: deploying `stagingdlrm-service.war` to a real WildFly 40 / JDK 25
container failed** with

```
WELD-001408: Unsatisfied dependencies for type SystemIdMapperClient with qualifiers @Default
  at injection point [BackedAnnotatedField]
  @Inject private ...SystemMapperService.systemIdMapperClient
```

Decompiling `id-mapper-client`'s `SystemIdMapperClientProducer` (the CDI `@Produces` factory for this
interface) at both the old and new versions:

| Version | Annotations found |
|---|---|
| `17.103.5` (original pin) | `javax.enterprise.context`, `javax.enterprise.inject`, `javax.inject.Inject` |
| `25.104.0-M11` (cached, already available) | `jakarta.enterprise.context`, `jakarta.enterprise.inject`, `jakarta.inject.Inject` |

A Jakarta EE 11 CDI container only scans `jakarta.*`-annotated classes for bean discovery. The old
version's producer is invisible to it — not a compile error (Java doesn't care about annotation package
names to compile), not even a unit-test failure (nothing in this repo's own tests boots a real CDI
container), only a deploy-time WELD failure. **No amount of model validation, `mvn clean install`, or
even a green unit-test suite could have caught this — only an actual deploy to an actual Jakarta EE 11
container did.** Bumped `system.id-mapper.version` to `25.104.0-M11`; redeployed; the same WAR started
cleanly and the full IT suite passed (FR22).

**`pcfdlrm.version`/`progression.version` were checked for the same exposure and found clear.** Neither
is consumed as a real dependency jar shipping its own precompiled CDI beans — both are consumed only as
`<classifier>raml</classifier>` artefacts, inputs to `rest-client-generator-plugin`, which *generates*
client code locally from them on the M12 (jakarta-native) generator toolchain. Whatever CDI annotations
the generated code carries come from the generator, not from the RAML-classified jar, so the age of
these two pins does not carry the same risk `id-mapper-client` did. Left unchanged, matching FR4's
"no opportunistic bump" principle — this is now demonstrated, not merely assumed.

**Correction, found while checking the above: the "pre-existing gap" this design (and the parity story
before it) attributed to `progression-query-api`/`pcfdlrm-command-api` was the *same certificate-trust
misdiagnosis*, not a genuine artefact-availability gap.** Both resolve cleanly now:

```
mvn dependency:get -Dartifact=uk.gov.moj.cpp.progression:progression-query-api:17.0.297:jar:raml → BUILD SUCCESS
mvn dependency:get -Dartifact=uk.gov.moj.cpp.pcfdlrm:pcfdlrm-command-api:17.103.24:jar:raml       → BUILD SUCCESS
```

— and `stagingdlrm-event-processor`'s `rest-client-generator-plugin` execution genuinely generated real
remote clients from both, confirmed by the files landing in `target/generated-sources`:
`RemoteEventProcessor2PcfdlrmCommandApi.java` and `RemoteEventProcessor2ProgressionQueryApi.java`. The
parity story's checklist (its Gaps section, and the `git stash`-verified "pre-existing" claim) should be
revisited on the same grounds this document was — flagged as a follow-up for that story's own docs,
since amending them is not this story's to do unprompted, but the finding belongs on the record here.

### FR5 — Migrate only the Jakarta EE namespaces

**Status: (a) verifiable here — the rewrite was executed and its result counted.**

**Measured inventory on `b23ea10`, which corrects the requirements' figure.** The requirements state
"**92**, across **40 files**" measured at `main`. On this branch it is **76 import lines across 34
files**:

```
grep -rE '^import (static )?javax\.' --include='*.java' . | grep -v '/target/' | wc -l   → 76
grep -rlE '^import (static )?javax\.' --include='*.java' . | grep -v '/target/' | wc -l  → 34
```

By package — the complete set, which *is* the allowlist:

| Package | Lines | → target | Where |
|---|---|---|---|
| `javax.json.*` | **37** | `jakarta.json.*` | func-app (main+test), command-api, command-handler, event-processor (main+test), integration-test |
| `javax.ws.rs.core.*` | **17** | `jakarta.ws.rs.core.*` | func-app (main+test), integration-test |
| `javax.inject.Inject` | **11** | `jakarta.inject.Inject` | command-api, command-handler ×2, event-processor ×6 |
| `javax.ws.rs.client.*` | **5** | `jakarta.ws.rs.client.*` | func-app (main+test), integration-test |
| `javax.annotation.PostConstruct` | **4** | `jakarta.annotation.PostConstruct` | `EventGridService:9`, and the three `…Counter` classes at `:3` each |
| `javax.enterprise.inject.Specializes` | **1** | `jakarta.enterprise.inject.Specializes` | `StagingdlrmIgnoredHealthcheckNamesProvider:11` |
| **`javax.net.ssl.SSLContext`** | **1** | **NO CHANGE — JDK API** | `StagingDlrmCommandHelper:23` — see FR6 |

76 = 37+17+11+5+4+1+1. Also checked and found empty: any **fully-qualified** `javax.` reference
outside an import line (`grep -n 'javax\.' --include='*.java' | grep -v ':import '` → no output), and
any `javax` string in `*.xml`/`*.yaml`/`*.raml`/`*.json`/`*.properties` resources → no output. So the
import lines are the entire Java-side surface; there is no fully-qualified usage to catch separately.

**Mechanism (design note 1 of the requirements): a scripted per-package rewrite with an explicit
allowlist, never a blanket `javax.` → `jakarta.` replace.** The exact rewrite, executed in the probe:

```perl
s/^import (static )?javax\.json\./import $1jakarta.json./;
s/^import (static )?javax\.ws\./import $1jakarta.ws./;
s/^import (static )?javax\.inject\./import $1jakarta.inject./;
s/^import (static )?javax\.annotation\.PostConstruct;/import $1jakarta.annotation.PostConstruct;/;
s/^import (static )?javax\.enterprise\./import $1jakarta.enterprise./;
```

Five rules, anchored to `^import`, each naming its package explicitly. Result after running it over
every non-`target` `*.java`:

```
remaining javax imports:
  stagingdlrm-azure-functions/.../StagingDlrmCommandHelper.java:23:import javax.net.ssl.SSLContext;
files now carrying a jakarta import: 34
```

**75 of 76 lines moved; the one survivor is exactly the one FR6 protects.** AC4 is satisfiable and
was satisfied.

Two properties of the rule set worth stating because they are the difference between this and a
blanket replace: `javax.annotation.PostConstruct` is matched as a **whole line** rather than as a
package prefix, so a future `javax.annotation.processing.*` (a JDK package) could not be caught by
accident; and `javax.ws.` covers both `javax.ws.rs.core` and `javax.ws.rs.client` in one rule without
reaching any JDK namespace, since `javax.ws` is exclusively JAX-RS.

The four `static` imports in the set are handled by the `(static )?` capture and were confirmed
migrated: `static javax.json.Json.createObjectBuilder` ×3, `static javax.json.JsonValue.NULL`,
`static javax.ws.rs.core.Response.Status.OK`, `static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE`.

**Sequencing:** FR5 must land in the same commit as FR7's pom swap. Renaming imports without swapping
`javaee-api` leaves the jakarta APIs off the compile classpath; swapping `javaee-api` without renaming
imports leaves the javax APIs off it. Neither half compiles alone.

**Behaviour must not move (requirements design note 7).** Both classes the architecture docs single
out are in the sweep. Re-read against `dlrm-flow-reference.md` before and after:

- `StagingDlrmCommandHelper` (§2.4 payload assembly, §2.6 Path 3's direct outcome write) — the sweep
  changes 11 import lines. `generateErrorMigratedCaseSubmissionPayload` builds its payload through
  `uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder` (verified at
  `StagingDlrmCommandHelper.java:5–8`), not through `Json.createObjectBuilder` directly — which is
  what makes FR15's `utilities-core` finding a hard coupling rather than a tidy-up.
- `EventGridMonitorHelper` (§2.6's writer for Paths 3 and 4, §2.7) — 2 import lines
  (`javax.json.Json`, `javax.json.JsonWriter`). No control flow, so none of §2.6's four write paths
  and none of the `success`/`caseUrn` values in its summary table can change from a rename. The
  `Map<String, Object>` stringly-typed `"false"` quirk the reference doc flags at its own line 352 is
  untouched and stays untouched — it is pre-existing behaviour, not this story's to fix.

### FR6 — Do not rename JDK `javax.*` packages

**Status: (a) verifiable here.**

**The complete list of JDK-namespace `javax.*` in this repo is one line:**

```
stagingdlrm-azure-functions/src/main/java/uk/gov/moj/cpp/stagingdlrm/azure/rest/StagingDlrmCommandHelper.java:23
    import javax.net.ssl.SSLContext;
```

It sits in the middle of the import block (lines 18–29), sandwiched between `javax.json.JsonWriter`
(`:22`) and `javax.ws.rs.client.Client` (`:24`) — i.e. **in the exact position where a
sort-and-replace or a block-level regex would take it out**. It backs the trust-all TLS client
(`SSLContexts` / `TrustAllStrategy` from `org.apache.http.ssl`, imported at `:32–34`) that
`dlrm-flow-reference.md` §5 describes as "sends REST POSTs with SSL trust-all". `javax.net.ssl` has no
`jakarta` equivalent and never will — it is `java.base`-adjacent JDK API.

FR6 also names `javax.crypto`, `javax.xml.parsers` and `javax.naming` as things to watch. **Checked:
none of the three appears anywhere in the repo**, so today's exposure is the single `javax.net.ssl`
line. The allowlist in FR5 protects against all four by construction, because it enumerates the five
Jakarta packages positively rather than excluding JDK ones negatively — a negative exclusion list
would need updating every time a new JDK `javax.*` usage appeared; the positive allowlist does not.

**The AC4 check, and why its wording matters:**

```
grep -rE '^import (static )?javax\.' --include='*.java' . | grep -v '/target/'
```

must return **exactly one line**, the `javax.net.ssl.SSLContext` above. AC4 says "returns **only** JDK
packages", so a count of one is the pass condition today; if a later commit adds a second genuinely-JDK
`javax` import the assertion is still meaningful. Do **not** write this as "returns nothing" — that
would force the FR6 bug.

### FR7 — Swap `javaee-api` for the Jakarta equivalent everywhere, including inside plugin `<dependencies>`

**Status: (a) verifiable here.**

**Why this is a correctness requirement and not tidiness — and why AC5 must be a grep.** Two
mechanisms, which behave completely differently, established by reading the cached BOMs:

1. **Normal dependencies fail *silently*.** `javax:javaee-api` is **still managed** on the J25 chain —
   `common-bom 25.104.0-M5:534–538` declares it at `8.0.1` with the comment *"Legacy Java EE 8 API —
   pinned for Activiti 5.x compatibility"*. So the nine unversioned `provided` declarations resolve
   perfectly happily on M10. Confirmed by the probe: `javax:javaee-api` was the one javax coordinate
   that produced **no** error in the pre-migration run. Leaving them in place gives you a green build
   whose modules were compiled against Java EE 8 `javax.*` APIs that WildFly 40 does not provide —
   and once FR5 has renamed the imports, the jakarta APIs simply are not on the classpath and
   compilation fails with an unhelpful "package jakarta.inject does not exist". **This is why AC5
   requires a grep across all poms rather than trusting build success.**
2. **Plugin-internal declarations fail *late and obscurely*, exactly as FR7 warns.** The two plugin
   blocks use `<version>${javaee-api.version}</version>`. That property is **not** module-local — it
   comes from `parent-pom`, and it moves `8.0.1` → **`11.0.0`** on the J25 chain (verified:
   `mvn -o -N help:evaluate -Dexpression=javaee-api.version` in the probe → `11.0.0`). So the
   coordinate silently becomes **`javax:javaee-api:11.0.0`, which does not exist** — the cache holds
   only `8.0` and `8.0.1`, and Central has never published anything past `8.0.1` under the `javax`
   groupId. The POM stays valid, the property interpolates, and the failure surfaces at
   `generate-sources` as a plugin-dependency resolution error that never names `javaee-api` as the
   cause.

**The target coordinate.** `jakarta.platform:jakarta.jakartaee-api` **is** managed —
`maven-common-bom 25.104.0-M7:178–182` at `${jee.api.version}` = **`11.0.0`** (cached locally at
`~/.m2/repository/jakarta/platform/jakarta.jakartaee-api/11.0.0`). Consequently:

- **normal dependencies drop their `<version>` entirely** and take it from the BOM — smallest possible
  diff, no local pin to drift;
- **plugin `<dependencies>` must keep an explicit `<version>`**, because plugin dependency resolution
  does not consult `<dependencyManagement>`. Use `${javaee-api.version}` — which is precisely the
  form the M10 parent uses for its own generator plugin blocks
  (`service-parent-pom-25.104.0-M10.pom:668–671, 723–726, 804–807, 1084–1087`), so this is matching
  the platform's own idiom rather than inventing one.

**All 12 sites. The count is the checklist (requirements design note 2), and it is 12, not 11.**
FR7 says "Nine modules declare it as a normal dependency" plus 2 plugin-internal. Nine *modules* is
right; **ten normal declaration sites**, because `stagingdlrm-testharness` declares it twice.

| # | File | Lines | Form | Action |
|---|---|---|---|---|
| 1 | `stagingdlrm-command/stagingdlrm-command-api/pom.xml` | 20–24 | `provided`, no version | swap groupId+artifactId |
| 2 | `stagingdlrm-command/stagingdlrm-command-handler/pom.xml` | 19–23 | `provided`, no version | swap |
| 3 | `stagingdlrm-event/stagingdlrm-event-listener/pom.xml` | 18–22 | `provided`, no version | swap |
| 4 | `stagingdlrm-event/stagingdlrm-event-processor/pom.xml` | 19–23 | `provided`, no version | swap |
| 5 | `stagingdlrm-query/stagingdlrm-query-api/pom.xml` | 18–22 | `provided`, no version | swap |
| 6 | `stagingdlrm-healthchecks/pom.xml` | 13–17 | `provided`, no version | swap |
| 7 | `stagingdlrm-viewstore/stagingdlrm-viewstore-persistence/pom.xml` | 13–17 | `provided`, no version | swap — **but see FR24**, this module may lose it altogether |
| 8 | `stagingdlrm-azure-functions/pom.xml` | 44–48 | compile, **explicit `<version>8.0</version>` at `:47`** | **drop entirely** — see FR15 |
| 9 | `stagingdlrm-testharness/pom.xml` | 27–31 | **`<dependencyManagement>`**, explicit `8.0` at `:30` | **delete the whole entry** — see below |
| 10 | `stagingdlrm-testharness/pom.xml` | 50–53 | dependency, no version | swap, stays unversioned |
| **11** | `stagingdlrm-event/stagingdlrm-event-processor/pom.xml` | **92–96** | **`messaging-client-generator-plugin` `<dependencies>`**, `${javaee-api.version}` | swap, keep `${javaee-api.version}` |
| **12** | `stagingdlrm-event/stagingdlrm-event-processor/pom.xml` | **104–108** | **`rest-client-generator-plugin` `<dependencies>`**, `${javaee-api.version}` | swap, keep `${javaee-api.version}` |

**Line-number correction:** FR7 cites the plugin-internal sites as `pom.xml:105` and `pom.xml:117`. On
`b23ea10` they are at **92–96** and **104–108** (the `<artifactId>` lines being `:94` and `:106`).
Locate them by plugin name, not by line number.

**Site 9 is a trap the probe caught.** Dropping the `<version>` from a *local* `<dependencyManagement>`
entry is not the same edit as dropping it from a dependency — `<dependencyManagement>` requires a
version, and the probe failed with
`'dependencies.dependency.version' for jakarta.platform:jakarta.jakartaee-api:jar is missing @ line 47`
in `stagingdlrm-testharness/pom.xml`. **The correct edit is to delete the `<dependencyManagement>`
entry outright** and let site 10 pick the version up from `maven-common-bom`. The local management
block only existed to pin `8.0`; there is nothing left for it to do. After that change the reactor
read cleanly.

**Verification:** post-migration, `grep -rn 'javax' --include='pom.xml' .` returns **no output**, and
the full reactor is constructed without a model error on the M10 chain under JDK 25. AC5 met.

### FR8 — Migrate the CDI and persistence descriptors

**Status: (a) verifiable here.**

**All 10 `beans.xml` are byte-identical apart from attribute order** (`stagingdlrm-service`'s puts
`xmlns:xsi` first) — verified by reading all ten. Each is a five-line empty `<beans>` element on the
legacy namespace:

```xml
<beans xmlns="http://xmlns.jcp.org/xml/ns/javaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/beans_1_1.xsd"
       version="1.1" bean-discovery-mode="all">
</beans>
```

The ten: `command-api`, `command-handler`, `domain-aggregate`, `domain-event`, `event-listener`,
`event-processor`, `healthchecks`, `query-api`, `service`, `viewstore-persistence` — all at
`src/main/resources/META-INF/beans.xml`.

**Design: replace all ten with one identical Jakarta-namespace file.** The whole file is boilerplate
with no per-module content, so a single canonical body is both correct and the most reviewable diff.
`cdi.api.version` is **`4.1.0`** on the J25 chain (`maven-common-bom 25.104.0-M7:32`), so CDI 4.1 with
the `beans_4_0.xsd` schema:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
       version="4.0" bean-discovery-mode="all">
</beans>
```

**`bean-discovery-mode="all"` is preserved explicitly on all ten, and it is the whole point.** Under
CDI 4.0 the default for an *empty* `beans.xml` flipped to `annotated`; with `annotated`, this repo's
CDI beans that carry no bean-defining annotation stop being discovered and — as BC-14 puts it —
interceptor chains empty out *silently*. BC-14's verdict in the parity checklist is *Refuted (acute) /
latent hazard*, and its Bucket B row states the reason in as many words: *"The upgrade story's own FR8
(preserving `bean-discovery-mode="all"`) is what keeps this repo unaffected."* There is no test
guarding it, so the AC6 grep is the guard:

```
grep -rl 'bean-discovery-mode="all"' --include='beans.xml' . | grep -v '/target/' | wc -l   → must be 10
grep -rl 'xmlns.jcp.org' --include='beans.xml' . | grep -v '/target/'                       → must be empty
```

Both were run in the probe after the rewrite: **10** and **empty**. AC6 met.

**`persistence.xml`** — the single file at
`stagingdlrm-viewstore/stagingdlrm-viewstore-persistence/src/main/resources/META-INF/persistence.xml`,
currently `http://java.sun.com/xml/ns/persistence` version `1.0` (i.e. **JPA 1.0**, a 2006 schema).
It declares one persistence unit, `stagingdlrm-persistence-unit`, with
`org.hibernate.jpa.HibernatePersistenceProvider` and `<jta-data-source>java:/DS.stagingdlrm</jta-data-source>`,
and **no `<class>` entries** — no entities, consistent with the module having zero Java files.

`persistence-api.version` is **`3.2.0`** on the J25 chain (`maven-common-bom 25.104.0-M7:43`), so:

```xml
<persistence xmlns="https://jakarta.ee/xml/ns/persistence"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence
 https://jakarta.ee/xml/ns/persistence/persistence_3_2.xsd" version="3.2">
```

with the unit body unchanged — same unit name, same provider FQCN (`org.hibernate.jpa.HibernatePersistenceProvider`
is unchanged in Hibernate 6), same JNDI data source. **Whether this file survives at all depends on
FR24; it does survive, and this is the migration it gets.**

FR7's `@Inject EntityManager` → `@PersistenceContext(unitName)` item (from the brief's known-work
table, flagged there as "*likely N/A here, no JPA code*") is **confirmed N/A**: zero `.java` files in
`stagingdlrm-viewstore`, zero `javax.persistence` imports anywhere in the repo, zero `@Entity`.

### FR9 — Apply the two recorded generator fixes

**Status: (a) — both confirmed *necessary* by reading the M10 parent, not taken on trust from the
tracker, and both confirmed to actually work by real code generation. A third, previously-unrecorded fix
was also needed and is documented below.**

This is the item where reading the parent POM changed the answer, so the reasoning is spelled out.

**First, which parent profile actually supplies each module's generator plugins.** The generator plugin
declarations live in `service-parent-pom`'s **profiles**, not its `<pluginManagement>` — and they are
in `<build><plugins>`, so a child's `<plugin>` block *merges* with the parent's (plugin
`<dependencies>` lists are unioned by `groupId:artifactId`). The profiles are file-activated:

| Profile | Activation | Lines in M10 |
|---|---|---|
| `raml-framework-generation` | `<file><exists>src/raml</exists></file>` | 594–943 |
| `yaml-framework-generation` | `<file><exists>src/yaml</exists></file>` | 978–1233 |

Which resolves per module — **checked with `mvn -o help:active-profiles`, not assumed**:

| Module | `src/raml`? | `src/yaml`? | Active profile |
|---|---|---|---|
| `stagingdlrm-command-api` | **yes** (`stagingdlrm-command-api.raml`) | no | `raml-framework-generation` |
| `stagingdlrm-command-handler` | **yes** (`…-command-handler.messaging.raml`) | no | `raml-framework-generation` |
| `stagingdlrm-event-processor` | no | **yes** | **`yaml-framework-generation`** |
| `stagingdlrm-event-sources` | no | **yes** | `yaml-framework-generation` |

That last row is the one that matters and it is easy to get wrong: `stagingdlrm-event-processor`
declares `messaging-client-generator-plugin` and `rest-client-generator-plugin` in its own build
(`pom.xml:76–99` and `:100–134`) but has **no `src/raml`** — it has `src/yaml`. So it inherits the
**yaml** profile's versions of those plugin declarations, which are materially thinner than the raml
profile's.

**What each parent profile already provides, and therefore what is genuinely missing:**

| Plugin | Parent's own `<dependencies>` | Missing |
|---|---|---|
| `messaging-client-generator-plugin`, **raml** profile (M10 `:742–816`) | `messaging-client-generator`, `jakarta.jakartaee-api`, `jakarta.xml.bind-api` | **parsson** |
| `messaging-client-generator-plugin`, **yaml** profile (M10 `:1149–1210`) | `messaging-client-generator` **only** | **parsson** |
| `rest-client-generator-plugin`, **raml** profile (M10 `:819–853`) | `rest-client-generator` **only** | jakartaee-api (n/a — no raml module uses it) |
| `rest-client-generator-plugin`, **yaml** profile (M10 `:1113–1146`) | `rest-client-generator` **only** | **jakartaee-api** |
| `messaging-adapter-generator-plugin`, both profiles (M10 `:682–740`, `:1051–1111`) | jakartaee-api, jakarta.xml.bind-api, **and parsson** | — nothing; the parent already fixed this one |

**Both tracker-recorded fixes are therefore real for this repo, and the parent has already fixed the
sibling plugin — which is the strongest possible corroboration.** The M10 parent even carries the
rationale as a comment on its own `parsson.version` property (`service-parent-pom-25.104.0-M10.pom:41–43`):

> *"JSON-P provider for messaging-adapter-generator-plugin classpath; glassfish jakarta.json is an
> OSGi bundle that suppresses ServiceLoader registration, so parsson is required explicitly."*

That is exactly the failure `cpp-context-system-scheduling` hit on `messaging-client-generator`; the
platform patched `messaging-adapter-generator` and not `messaging-client-generator`.

**Fix 1 — parsson in `messaging-client-generator-plugin`'s plugin deps. Two sites**, because this
plugin runs in two modules under two different profiles:

- `stagingdlrm-command/stagingdlrm-command-api/pom.xml`, inside the existing block at `:85–102`
  (which currently declares only the `stagingdlrm-command-handler` `raml` artefact);
- `stagingdlrm-event/stagingdlrm-event-processor/pom.xml`, inside the existing block at `:76–99`.

Added in both:

```xml
<dependency>
    <groupId>org.eclipse.parsson</groupId>
    <artifactId>parsson</artifactId>
    <version>${parsson.version}</version>
</dependency>
```

`${parsson.version}` = `1.1.7`, defined in `service-parent-pom` M10 and cached locally
(`~/.m2/repository/org/eclipse/parsson/parsson/1.1.7/parsson-1.1.7.jar`). Using the property rather
than a literal keeps it aligned with whatever the platform bumps to.

**Fix 2 — the jakartaee-api swap in `rest-client-generator-plugin`.** This is FR7's site 12
(`stagingdlrm-event-processor/pom.xml:104–108`); the yaml profile gives that plugin nothing but
`rest-client-generator`, so the module-level declaration is the only thing putting an EE API on the
codegen classpath, and it currently resolves to the non-existent `javax:javaee-api:11.0.0`. Swapping
groupId/artifactId is both fixes at once — FR7's correctness fix and FR9's recorded fleet fix are the
same edit here.

**Verified for real: both fixes work, and the generators genuinely run and produce output.** Once the
cert-trust fix (see Environment) let a real online build resolve everything,
`stagingdlrm-event-processor` built clean and both plugins generated real code:
`target/generated-sources/uk/gov/justice/api/RemoteEventProcessor2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand.java`
(messaging client, parsson fix), `RemoteEventProcessor2PcfdlrmCommandApi.java` and
`RemoteEventProcessor2ProgressionQueryApi.java` (REST clients, jakartaee-api fix). The earlier version
of this document recorded `progression-query-api`/`pcfdlrm-command-api` as unresolvable "pre-existing"
gaps inherited from the parity story — that was the same certificate misdiagnosis as everything else in
this section; both resolve fine (see FR4's correction).

**A third, real, previously-unrecorded defect surfaced once code generation actually ran**, not
something the parent-POM reading above could have found: `messaging-client-generator-plugin`'s
`generate-classpath-message-clients` goal failed on `stagingdlrm-event-processor` specifically (it
succeeded on `command-api`/`command-handler`'s simpler, RAML-only invocations of the *same* plugin)
with

```
A required class was missing while executing
uk.gov.justice.framework-generators:messaging-client-generator-plugin:25.104.0-M5:generate-messaging-client:
javax/xml/bind/SchemaOutputResolver
```

Decompiling the transitive chain located the cause precisely: `org.raml:raml-parser:0.8.18` (pulled in
via `generator-raml-parser`, itself a transitive of `messaging-client-generator`) ships a class,
`org.raml.parser.tagresolver.JaxbTagResolver`, whose bytecode calls
`javax.xml.bind.JAXBContext.generateSchema(javax.xml.bind.SchemaOutputResolver)` directly — the literal
old JAXB package name, hardcoded, in a 2016-era third-party library nobody has updated for Jakarta EE.
`jakarta.xml.bind-api` (already on the classpath, per the table above) cannot satisfy a reference to the
old package; the class is genuinely absent. Only `event-processor`'s *combined* RAML+YAML plugin
invocation (it consumes `stagingdlrm-command-handler`'s RAML artefact *and* its own YAML content in one
execution) exercises the code path inside `JaxbTagResolver` that calls this method; the simpler
RAML-only invocations in `command-api`/`command-handler` never reach it.

**Fix 3 — a plugin-scoped JAXB compat dependency, `stagingdlrm-event-processor/pom.xml` only:**

```xml
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>
    <version>2.3.1</version>
</dependency>
```

Added to the `messaging-client-generator-plugin`'s own `<dependencies>` block, alongside the parsson
fix — isolated to this one plugin execution's classloader realm, not a module compile dependency, since
the need is purely for `raml-parser` to find a class at code-generation time. `2.3.1` is confirmed to
contain `javax/xml/bind/SchemaOutputResolver.class` and is on both the local cache and Central. After
this addition, `generate-classpath-message-clients` succeeded for `event-processor` too, and the full
reactor (all 26 modules, all unit tests) built green.

### FR10 — The generated-artefact inventory must not shrink

**Status: (a) for "the build produces its expected artefacts" — confirmed by the full green build (FR9)
— but the requirement's premise still needs a correction before it is used as an acceptance check.**

FR10 asks that the parity story's derived inventory assertion still pass. **There is no such
assertion.** The parity checklist's BC-21 rows are ⚪ ⚪ 🟡 🟡: the `catalog-generation-plugin` and
`messaging-client-generator-plugin` tests were authored, ran green on J17, then were removed
(2026-09-04 → 2026-09-07); `pojo-generation-plugin` and `rest-client-generator-plugin` were never
instrumented. Its Gaps section states it: *"BC-21 now has no test at all, for any of its four generator
families."* So FR10 has nothing to keep passing.

**Checked further, against the real M11 target rather than the M9 proxy this was first assessed
against: the inventory does *not* actually shrink after all.** Diffing two cached core-domain jars
(17.104.4, the previous pin, against 25.104.0-M9, first used as a stand-in for the then-unobtainable
M11) initially showed:

| Artefact | Entry-list diff (vs M9) | Content diff |
|---|---|---|
| `common-core-domain` | **identical** (422 entries both) | `core/common/definitions.json` **byte-identical**; 19 definitions both sides, same keys |
| `criminal-court-public-model` | **422 → 420** | two schemas absent at M9: `json/schema/global/defendantFineAccountNumber.json`, `json/schema/global/deletedJudicialResults.json` |

**Re-run against the real `25.104.0-M11` jar once it became resolvable: zero difference on either
artefact.** Both `defendantFineAccountNumber.json` and `deletedJudicialResults.json` are present again
at M11 — the apparent loss was specific to M9, an intermediate milestone, not a property of the actual
upgrade target (see FR19 for the full re-check). So the mechanism below (`stagingdlrm-domain-event`'s
`CLASSPATH`-wide POJO scan picking up whatever core-domain schemas are on the classpath) is still worth
recording as a *general* risk for any future core-domain bump, but **this specific upgrade does not
trigger it** — recorded here as a corrected finding, not left as a live warning it no longer is:

`stagingdlrm-domain-event` generates POJOs via its `pojo-generation-schema` execution
(`pom.xml:117–176`), `<sourceDirectory>CLASSPATH</sourceDirectory>` with `<include>**/*.json</include>`,
and its plugin dependency block (`:178–199`) puts `common-core-domain` and `criminal-court-public-model`
on that classpath — generating a POJO for every JSON schema it finds there, including third-party ones.
A future core-domain bump that genuinely removes a schema *would* shrink this repo's generated-type
count without any code change here; this one, empirically, does not.

**Design:**

1. **Scope any inventory check to this repo's own schema resources**, i.e. the ~33 schemas under
   `stagingdlrm-domain-*/src/main/resources/json/schema/**` and `stagingdlrm-datatypes-common`'s, and
   never to the `CLASSPATH`-wide set. The parity checklist reached the same conclusion for its own
   reasons — *"a hard-coded count or manifest there would be exactly the 'maintenance burden' the risk
   notes warn against"*.
2. **If the build fails on a missing core-domain schema, that is FR19/BC-15, not FR10/BC-21.** The two
   named schemas are the concrete instances. See FR19 for why neither affects this repo.
3. The one BC-21 premise that *was* verified true for this repo is `catalog-generation-plugin`'s: the
   parity story decompiled `generator-io-utils`' `FileTreeScanner` and found it genuinely bundles and
   calls `org.reflections.Reflections` (`ResourcesScanner`, `ConfigurationBuilder`). So the
   `reflections` 0.9.10 → 0.10.2 risk is real for the two `catalog-generation-plugin` executions
   (`stagingdlrm-domain-value-schema/pom.xml:23–53`, `stagingdlrm-datatypes-common/pom.xml:14–25`).
   The observable symptom would be a `META-INF/schema_catalog.json` with fewer entries than there are
   `.json` files under the module's `sourceDirectory` — cheap to eyeball on the first successful
   build, and worth eyeballing given nothing tests it.
4. For `messaging-client-generator-plugin` the parity story went further and **refuted** the premise:
   it decompiled every class in that generator's dependency chain (`messaging-client-generator`,
   `generators-commons`, `generators-subscription`, `generator-core`) and found **zero** references to
   `org.reflections`. So no inventory check is warranted there.

**(b), because no generator can be executed in this sandbox** — see FR9.

### FR11 — Move the build to the Java 25 track

**Status: (a) verifiable here — the exact target shape was read off the reference's current file, not
inferred.**

`azure-pipelines.yaml`, three edits. The reference context's `azure-pipelines.yaml` on its own
`team/25.104.x` is the settled end state (its upgrade landed and produced a QA image), so it was read
directly rather than reconstructed from the requirements:

| Line | From | To | Evidence |
|---|---|---|---|
| `azure-pipelines.yaml:24` | `ref: 'main'` | **`ref: 'wildfly40'`** | reference `azure-pipelines.yaml:24` |
| `azure-pipelines.yaml:29` | `identifier -equals centos8-j17` | **`identifier -equals ubuntu-j25`** | reference `:29` |
| after `azure-pipelines.yaml:50` | *(absent)* | **`aksDeployBranch: 'wildfly40'`** as a fourth parameter to `context-validation.yaml` | reference `:52` |

**Take `ubuntu-j25`, not `ubuntu-j25-postgres`.** The reference's upgrade commit `122a5a8f` first set
`ubuntu-j25-postgres`, and a follow-up commit `c834ff3a` ("azure-pipelines: image changed to
ubuntu-j25") reverted it to `ubuntu-j25`. Verified with `git log -L29,29:azure-pipelines.yaml`. FR11's
figure is the corrected one; the intermediate value is a trap for anyone diffing only the upgrade
commit.

**`aksDeployBranch` is a new parameter, not an edited one** — this repo's yaml currently passes only
`repo`, `sonarQubeType`, `serviceName`, `itTestFolder` (`:47–50`).

**Do not add `LANG`/`LC_ALL`.** The reference carries `LANG: 'en_GB.UTF-8'` / `LC_ALL: 'en_GB.UTF-8'`
in its `variables` block and this repo does not, which invites a copy. Checked with
`git log -L35,36:azure-pipelines.yaml`: both were present in the reference's **initial commit**
(`6ce72a4a`) and its CI bootstrap (`08fe2c08`) — they predate its upgrade entirely and are not part of
it. Adding them here would be scope creep on a guess. Recorded as a low-confidence watch item in
Cross-cutting instead, since a locale change between `centos8-j17` and `ubuntu-j25` agents is real but
this repo's only locale-sensitive code is `MessageFormat` in log messages.

**Out-of-scope observation, recorded because a reviewer will see it.** `azure-pipelines.yaml:32` sets
`sonarqubeProject: "uk.gov.moj.cpp.staging.dlrm:staging-dlrm-parent"`, but the reactor's actual
coordinates are `uk.gov.moj.cpp.stagingdlrm:stagingdlrm-parent` (`pom.xml:11–12`) — the dots and
hyphens do not match. Sonar analysis is therefore landing under a project key that does not correspond
to the build. Pre-existing, unrelated to the upgrade, **not fixed by this story** (it would change
where historical Sonar data lives), raised as a follow-up.

### FR12 — `jacoco` must be at 0.8.14 or later

**Status: (a) verifiable here — and the requirement's premise is wrong for this chain. No local
override is needed.**

FR12 states "the parent's 0.8.12 does not handle JDK 25 bytecode; every migrated context on the fleet
tracker has needed a local override". Traced through the cached parent chain:

| POM | `plugins.jacoco.version` |
|---|---|
| `parent-pom 17.10.12` (grandparent, J17 chain) | `0.8.8` |
| `parent-pom 25.104.0-M2` (grandparent, J25 chain) — **`:134`** | **`0.8.14`** |
| `platform-libraries-parent-pom 25.104.0-M11` — **`:41`** | **`0.8.14`** |

Both levels of the J25 chain already pin **0.8.14**, and `parent-pom` binds
`jacoco-maven-plugin` at `${plugins.jacoco.version}` (`:521–523`). The probe corroborates it
empirically: after the parent bump the build's first failure was
`Failed to execute goal org.jacoco:jacoco-maven-plugin:**0.8.14**:prepare-agent` — i.e. **0.8.14 is
what M10 actually selects**, with no local override present anywhere in this repo.

**Design: add nothing.** Verify instead:

```
JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn -o -N help:evaluate -Dexpression=plugins.jacoco.version -DforceStdout
```

must print `0.8.14` or later. Only if it does not should a local `<plugins.jacoco.version>` property go
in the root `<properties>`. A local override that merely restates the inherited value is a future
maintenance trap: it silently pins this repo behind the platform the next time jacoco is bumped for a
JDK reason.

**Sandbox note, so the next person is not confused.** The jacoco 0.8.14 artefacts were **not** in this
sandbox's cache and had to be fetched from Central (`jacoco-maven-plugin`, `org.jacoco.agent`
including its `runtime` classifier, `org.jacoco.core`, `org.jacoco.report`, `asm`/`asm-commons`/
`asm-tree` 9.9, `file-management` 3.2.0, `commons-io` 2.19.0). All are public and downloaded cleanly —
they are not part of the Artifactory blocker. Their `_remote.repositories` markers had to be removed
for offline resolution to accept them, which is a local-cache artefact of this sandbox and has no
bearing on the design or on a CI build.

### FR13 — Assess `jboss-deployment-structure.xml`, and check it against BC-12

**Status: (a) verifiable here. Treated as the investigation the requirements' design note 5 asked
for, and it is now settled — empirically, from a built WAR.**

This was flagged as possibly the repo's biggest deploy risk: a `jboss-deployment-structure.xml` that
disables the `jaxrs` subsystem, combined with M10's new fleet-wide `packagingExcludes` stripping
bundled RESTEasy, would leave a WAR with no JAX-RS runtime at all. Four findings, in the order that
dismantles the risk.

**Finding 1 — the file that exists does exclude `jaxrs`.** `stagingdlrm-event/stagingdlrm-event-listener/src/main/webapp/WEB-INF/jboss-deployment-structure.xml`,
read in full:

```xml
<jboss-deployment-structure>
    <deployment>
        <exclude-subsystems>
            <subsystem name="jaxrs"/>
        </exclude-subsystems>
        <exclusions>
            <module name="com.fasterxml.jackson.core.jackson-annotations"/>
            <module name="com.fasterxml.jackson.core.jackson-core"/>
            <module name="com.fasterxml.jackson.core.jackson-databind"/>
            <module name="org.jboss.resteasy.resteasy-jackson-provider" />
            <module name="org.jboss.resteasy.resteasy-jackson2-provider" />
        </exclusions>
    </deployment>
</jboss-deployment-structure>
```

It is the only such file in the repo, and on the face of it exactly the flagged shape.

**Finding 2 — it does not reach the artefact. Verified from the built WAR, not reasoned about.**
`service-parent-pom` configures `maven-war-plugin` with a `webResources` entry that copies
`jboss-deployment-structure.xml` from `${project.build.directory}/shared-archive-resources` into
`WEB-INF` (M10 `:117–127`; identical in 17.104.1), and those resources come from the
`service-common-resources` bundle pulled in by `maven-remote-resources-plugin`. `webResources`
**overlay** `src/main/webapp`, so the framework's copy wins. Confirmed by unzipping the WAR already
present in the working tree from a J17 build:

```
unzip -p stagingdlrm-event/stagingdlrm-event-listener/target/stagingdlrm-event-listener-17.104.26-DLRMJ25-SNAPSHOT.war \
         WEB-INF/jboss-deployment-structure.xml
```

returns the framework's file — `urn:jboss:deployment-structure:1.2` with a bare **`<deployment/>`** —
**not** the module's jaxrs-excluding one. Cross-checked against the bundle itself:
`service-common-resources-17.10.1.jar` ships `jboss-deployment-structure.xml` whose entire body is
`<deployment/>`.

**So the file at `…/event-listener/src/main/webapp/WEB-INF/jboss-deployment-structure.xml` is dead
configuration today, on J17, and has been for as long as the framework has injected its own. It has
never affected a deployment.**

**Finding 3 — the WAR it lives in is not deployed either.** `docker/Dockerfile_stagingdlrm-service:16`
`ADD`s exactly one artefact into `standalone/deployments/`:
`stagingdlrm-service-${version}.war`. And `stagingdlrm-service/pom.xml:14–43` consumes the five
component modules by **`<classifier>classes</classifier>`** — their attached classes jars, not their
WARs. `dlrm-flow-reference.md` §3.1 lists five WAR-packaged modules, which is true of the *build*; only
`stagingdlrm-service.war` is a *deployment*. Verified the same way:
`stagingdlrm-service-17.104.26-DLRMJ25-SNAPSHOT.war`'s `WEB-INF/jboss-deployment-structure.xml` is
also the framework's bare `<deployment/>`.

**Finding 4 — BC-12's interaction therefore cannot arise in this repo.** The deployed WAR keeps the
`jaxrs` subsystem enabled, so M10's `packagingExcludes` (`service-parent-pom-25.104.0-M10.pom:100`,
stripping `resteasy-core-*`, `resteasy-core-spi-*`, `resteasy-jackson-provider-*`,
`resteasy-multipart-provider-*`, `resteasy-servlet-initializer-*`, `resteasy-jaxb-provider-*`,
`jakarta.ws.rs-api-*`, `spring-webmvc-*` from `WEB-INF/lib`) does exactly what it is meant to: removes
duplicates that the container supplies. Concretely, today's `stagingdlrm-service.war` bundles
`resteasy-multipart-provider-3.15.1.Final.jar`, `javax.json-1.1.4.jar` and `javax.json-api-1.0.jar` in
`WEB-INF/lib` (listed from the built WAR); on M10 the first is stripped by `packagingExcludes` and the
other two are replaced by the jakarta equivalents that arrive transitively.

**Design, in four parts:**

1. **Amend nothing.** The existing file needs no jaxrs change, because it has no effect.
2. **Add no `jboss-deployment-structure.xml` to any other WAR.** None of `command-api`,
   `command-handler`, `event-processor`, `query-api` has one, and all four already receive the
   framework's `<deployment/>`.
3. **Do not copy the reference's root-level file.** `cpp-context-prosecution-casefile`'s upgrade
   commit `122a5a8f` added `jboss-deployment-structure.xml` at its repo root. Checked: it is
   **byte-for-byte the same `<deployment/>` document that `service-common-resources` already ships**,
   it sits at the repo root where no `maven-war-plugin` will ever look at it, and
   `grep -rn 'jboss-deployment-structure'` across that repo finds nothing referencing it. It is
   vestigial. FR13's "the reference *added* one at repo root" is true and is not a reason to do the
   same.
4. **Optional tidy-up, and it is genuinely optional:** delete
   `stagingdlrm-event/stagingdlrm-event-listener/src/main/webapp/WEB-INF/jboss-deployment-structure.xml`.
   It is inert, it misleads (it is the single most alarming-looking file in the repo with respect to
   BC-12), and `stagingdlrm-event-listener` has **zero Java files** and no `src/raml`, so it has no
   JAX-RS anything to exclude a subsystem for. Deleting it changes no artefact — verified, since the
   file already does not appear in the built WAR. Keep it out of this PR if the diff is already large;
   it belongs with the empty-read-side clean-up the parity checklist already handed to the owners as a
   separate non-parity tidy-up.

### FR14 — Establish where this repo's container image comes from

**Status: (a) verifiable here — settled. The requirement's own premise is wrong.**

FR14 says "There is no `Dockerfile` at this repo's root, so the fleet's item has no obvious target
here — but that must be **confirmed, not assumed**". Confirmed, and the conclusion is different from
either pattern the requirement offers:

```
find . -iname 'Dockerfile*' -not -path '*/target/*'
  → ./docker/Dockerfile_stagingdlrm-service
```

**This repo ships a Dockerfile, at `docker/Dockerfile_stagingdlrm-service`** — the *same* pattern as
the reference (`docker/Dockerfile_prosecutioncasefile-service`), not the `support`/`system-id-mapper`/
`notification` "no Dockerfile at all" pattern. FR14 reconciles three facts and reaches "no obvious
target"; the missing fourth fact is that the file exists under `docker/`, which is why a root-level
`find` misses it.

**And the fleet's actual edit — "Dockerfile base → Ubuntu 24.04, remove the RHEL `yum` lines" — has no
target inside it either.** Read in full, all 34 lines:

- `:1–3` — the base image is **parameterised**: `ARG baseImageUri` / `ARG baseImageTag` /
  `FROM ${baseImageUri}:${baseImageTag}`. There is no hardcoded base to change; the `wildfly40`
  pipeline track supplies it. `:34` `LABEL base_image ${baseImageTag}` just records it.
- **No `yum`, no `apt-get`, no `dnf`, no `RUN` that installs anything** — the whole file is `ADD` of
  seven Maven artefacts, `COPY` of one script, `chown`/`chmod`, and two `mkdir`s. Nothing
  distro-specific to strip.
- The seven `ADD`s are all `${mavenArtifactBaseUrl}`-relative and version-parameterised
  (`${version}`, `${eventstore_version}`, `${framework_version}`), so they follow the pom
  automatically.

**Design: no change to `docker/Dockerfile_stagingdlrm-service`.** FR14 says explicitly "Do not create a
`Dockerfile` to satisfy a checklist" — the corollary here is: do not *edit* one to satisfy a checklist
either.

**The one real risk, and it is a path risk, not a content risk.** FR14 records that
`context-validation.yaml`'s image step has `dockerfilePath` defaulting to `'Dockerfile'` and gates only
on a repo-name exclusion list that does not exclude this repo. This repo's `azure-pipelines.yaml`
passes **no** `dockerfilePath` (`:45–50`). Since the file is at `docker/Dockerfile_stagingdlrm-service`
and not at `Dockerfile`, the image step either already relies on the template deriving the path from
`serviceName: 'stagingdlrm'` (the naming is consistent with that:
`Dockerfile_${serviceName}-service`) or it has never worked. **Which of the two cannot be determined
from this repo** — `pipelines/context-validation.yaml` lives in `hmcts/cpp-azure-devops-templates`,
and specifically on its **`wildfly40`** branch, which FR11 is switching to. That is a **(b)** within
this **(a)** item, and it is the concrete reason FR21's follow-up PR should be budgeted: if the image
build fails after merge, `dockerfilePath` is the first thing to check, and passing
`dockerfilePath: 'docker/Dockerfile_stagingdlrm-service'` explicitly is the fix. Read the `wildfly40`
branch of the templates repo before the merge build rather than after it — github.com is reachable, so
this is checkable, just not from inside this repo.

**Related, and in scope elsewhere:** `docker/scripts/liquibase.sh:35` runs
`java -jar stagingdlrm-viewstore-liquibase.jar … update` and `set -e`-aborts the whole init script on
failure — which is what makes FR18 a deploy blocker rather than a warning. See FR18.

### FR15 — `stagingdlrm-azure-functions` moves to Java 25

**Status: (a) — builds clean and its own unit tests pass on real JDK 25, after two more real fixes
found by actually testing (below). The deploy-side question (whether Azure's Windows Functions stack
advertises Java 25) remains (b) — needs Azure credentials this sandbox does not have. Carries the
design's most substantive correction.**

The requirements and ADR decision 4 enumerate six pom items and name item 3
(`azure-functions-maven-plugin` `1.24.0` → a version accepting `javaVersion 25`) as "the
highest-uncertainty item in the epic". That item was investigated properly — Maven Central is
reachable, so the plugin and its runtime model were downloaded and decompiled. **The uncertainty is
real but it is not where the ADR put it, and there is a seventh item nobody had recorded that is a
hard compile-time coupling.**

#### The seventh item, and it is the important one: `utilities-core` is pinned to a javax-era build

`stagingdlrm-azure-functions/pom.xml` declares `uk.gov.justice.utils:utilities-core` **twice**:

| Lines | Form |
|---|---|
| `:74–79` | `<version>17.3.1</version>`, `<scope>compile</scope>` |
| `:136–138` | **no version** — takes the BOM-managed one |

Maven takes the **first** declaration, so today the func-app compiles against `utilities-core:17.3.1`.
That is not cosmetic. `StagingDlrmCommandHelper` builds every payload through the framework helper
`uk.gov.justice.services.messaging.JsonObjects` (`:5–8`, `import static …JsonObjects.createArrayBuilder`
/ `createObjectBuilder`, plus `import uk.gov.justice.services.messaging.JsonObjects` at `:8`), and
`TimerTriggerJavaTest:4` and `StagingDlrmCommandHelperTest:17` use it too. Decompiled both candidate
versions from the local cache:

| `utilities-core` | `JsonObjects` signatures | `javax/json` refs in bytecode | `jakarta/json` refs |
|---|---|---|---|
| **`17.3.1`** (the pin) | `javax.json.JsonObjectBuilder createObjectBuilder(javax.json.JsonObject)`, `Optional<javax.json.JsonObject> getJsonObject(javax.json.JsonObject, String...)`, … | **256** | **0** |
| **`25.104.0-M12`** (BOM-managed on M10) | `jakarta.json.JsonObjectBuilder createObjectBuilder()`, `jakarta.json.JsonArrayBuilder createArrayBuilder()`, `Optional<jakarta.json.JsonObject> getJsonObject(jakarta.json.JsonObject, String...)`, … | **0** | **378** |

**So FR5's rename of `StagingDlrmCommandHelper`'s imports to `jakarta.json.*` cannot compile while
`utilities-core` is pinned at `17.3.1`** — `createObjectBuilder()` would return a
`javax.json.JsonObjectBuilder` being assigned to a `jakarta.json.JsonObjectBuilder`. This is a hard
coupling between the func-app's jakarta migration and a version pin that neither the requirements nor
ADR decision 4 mention.

**Fix: delete the pinned declaration at `:74–79`.** The unversioned one at `:136–138` already exists,
so the module keeps `utilities-core` and simply takes the BOM's 25.104.x build. This also clears a
pre-existing Maven warning the probe surfaced:
`'dependencies.dependency.(groupId:artifactId:type:classifier)' must be unique: uk.gov.justice.utils:utilities-core:jar -> version 17.3.1 vs (?)`.
The duplicate is not harmless housekeeping — it is the thing selecting the wrong namespace.

`uk.gov.justice.services:test-utils-common` is pinned the same way (`:80–85`, `2.4.1`, test scope).
Checked: its bytecode has **zero** `javax.json`/`jakarta.json` references, and the func-app tests use
only `ReflectionUtils.setField` from it, so it is namespace-neutral and does not block the migration.
**This document originally recommended bumping it to the BOM-managed `25.104.0-M5` "for consistency" —
that recommendation was wrong, and testing it for real is what caught it: `25.104.0-M5` removed the
`uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils` class entirely, and every test in
this module that uses `setField` failed to compile.** Kept at `2.4.1`. The lesson generalises: a
namespace-neutral dependency being *safe* to bump is not the same as it being *necessary* to bump, and
an opportunistic "while we're here" version change carries its own risk independent of the jakarta
migration.

#### Item 3, the "highest-uncertainty item", investigated

Downloaded from Central and decompiled:

- **Latest plugin is `1.42.0`** (Central `maven-metadata.xml`, `lastUpdated 20260518042527`), versus
  `1.24.0` pinned here. Latest `azure-functions-java-library` is **`3.3.0`** (`20260429000913`) versus
  `3.1.0` pinned.
- **`1.24.0` and `1.42.0` are both compiled to Java 8 bytecode** (`javap -v` on `PackageMojo.class`
  → `major version: 52` in each). So **nothing about the plugin's own execution requires the bump** —
  it runs fine on JDK 25 as it stands.
- **The `package` goal — the only goal this repo binds** (`pom.xml:207–214`, `<goal>package</goal>`,
  execution id `package-functions`) — **never reads `<runtime><javaVersion>`.** `PackageMojo`'s
  bytecode references `getRuntimeClasspathElements` and `RuntimeException` and nothing runtime-model
  related; only `AbstractFunctionMojo` exposes `getRuntimeConfiguration()`, a plain parameter getter.
  **So `<javaVersion>25</javaVersion>` cannot fail the build.** FR15's item 2 is build-inert.
- **The deploy-side validation lives in `azure-toolkit-appservice-lib`, and it is dynamic.** Plugin
  `1.42.0` delegates via `ConfigParser` → `RuntimeConfiguration.getJavaVersion()` →
  `RuntimeConfig.javaVersion(String)`; the model is
  `com.microsoft.azure.toolkit.lib.appservice.model.FunctionAppWindowsRuntime` in
  `azure-toolkit-appservice-lib:0.56.0` (resolved via the plugin's parent
  `azure-maven-plugins:1.45.0`, which pins `azure-toolkit-libs:0.56.0`). Decompiled:

  | Class | Statically-declared runtimes |
  |---|---|
  | `FunctionAppWindowsRuntime` | **`FUNCTION_JAVA17`, `FUNCTION_JAVA11`, `FUNCTION_JAVA8`** — `"Java 17"`, `"Java 11"`, `"Java 1.8"`. No 21. **No 25.** |
  | `FunctionAppLinuxRuntime` | `FUNCTION_JAVA21`, `FUNCTION_JAVA17`, `FUNCTION_JAVA11`, `FUNCTION_JAVA8`. **No 25.** |

  `fromJavaVersionUserText(String)` normalises then calls `fromJavaVersion(JavaVersion)`, which is
  `getAllRuntimes().stream().filter(…).findFirst().orElse(**null**)` — **it returns `null` on no
  match**, it does not throw a helpful error. But `getAllRuntimes()` first calls
  `FunctionAppRuntime.tryLoadingAllRuntimes()`, and `loadAllFunctionAppWindowsRuntimes(…)` is invoked
  from `FunctionsServiceSubscription` off the live Azure stack API
  (`$.stackSettings.windowsRuntimeSettings.runtimeVersion`). **The static three are only an offline
  seed.**

**What that adds up to, precisely.** `javaVersion 25` is not statically rejected by any plugin version;
whether it deploys depends on whether the subscription's *Windows* Functions stack advertises Java 25.
This app is `<os>windows</os>` (`pom.xml:189`) on a dedicated App Service Plan
(`as-ste-ccp0101-dlrm`, `:185`), which is the configuration `dlrm-flow-reference.md` §2 describes, so
the Linux-only Java 21 entry is no help. **That question requires an authenticated Azure call
(`az functionapp list-runtimes --os windows`, or the plugin's own `deploy` goal) and cannot be answered
from this sandbox — no Azure credentials, and `<auth><type>azure_cli</type></auth>` (`:187`).**

#### Item 1 is a no-op as written, for a reason worth knowing

FR15 item 1 says `maven.compiler.source`/`target` 17 → 25. **Those two properties
(`pom.xml:14–15`) are dead — they have no effect on this build.** The parent configures
`maven-compiler-plugin` from `${compiler.release}` / `${compiler.source}` / `${compiler.target}`, not
from `maven.compiler.*`. Verified against the effective POM:

```
mvn -o -pl stagingdlrm-azure-functions help:effective-pom
  → maven-compiler-plugin 3.10.1: <release>17</release><source>17</source><target>17</target>
```

— 17 arriving from `parent-pom 17.10.12`'s `compiler.*`, and the module's own `maven.compiler.*`
appearing nowhere in the plugin configuration. On the M10 chain `compiler.*` becomes **25** and this
module compiles at 25 **automatically, whether or not anyone edits it**. The same dead pair exists in
`stagingdlrm-testharness/pom.xml:13–14`.

So item 1's design is: **delete both properties from both modules** (they mislead, and the next person
will "fix" the func-app's JDK by editing a property that does nothing), and know that the *real* lever
— should the fallback be needed — is `compiler.release`/`compiler.source`/`compiler.target`. See FR16.

#### The six ADR items, restated as they actually apply

| ADR decision 4 item | Design |
|---|---|
| 1. `maven.compiler.source`/`target` 17→25 | **Delete both** (`:14–15`) — dead properties; the parent chain moves this module to 25 on its own |
| 2. plugin `<runtime><javaVersion>` 17→25 | `pom.xml:190`. Build-inert. **Parameterise it**: `<javaVersion>${functionapp.java.version}</javaVersion>` with `<functionapp.java.version>25</functionapp.java.version>` in `<properties>`, so FR16's fallback is a one-token flip |
| 3. `azure-functions-maven-plugin` → a version accepting `javaVersion 25` | **Not required for the build** (both versions are Java-8 bytecode; `package` never reads `javaVersion`). Bump `1.24.0` → **`1.42.0`** anyway, for the newest runtime model — **as a literal version string in both declaration sites, not a property.** This document originally recommended collapsing both sites onto `${azure.functions.maven.plugin.version}`; tested for real, that broke `maven-enforcer-plugin`'s `RequirePluginVersions` rule (`-X` debug confirmed the property resolved correctly to `1.42.0` everywhere, and the rule still reported "missing valid version" — a real limitation for a plugin only one leaf module declares, with no ancestor `pluginManagement` providing a merge-visible fallback). Reverted to the module's original convention: a literal string in both places |
| 4. `azure-functions-java-library` `3.1.0` → newer | → **`3.3.0`** via the existing `:18` property (which *is* used, at `:27`) |
| 5. `javax:javaee-api:8.0` migrated or dropped | **Drop it** (`:44–48`). Not migrated — see below |
| 6. `org.glassfish:javax.json:1.0.2` → parsson | `:49–53` → `org.eclipse.parsson:parsson`, **version dropped** (BOM-managed 1.1.7) |
| **7. (new) `utilities-core:17.3.1`** | **Delete the pinned declaration at `:74–79`.** Hard blocker on the jakarta rename — evidence above |

**Why item 5 is *drop*, not *migrate*.** The func-app is a standalone JAR; `javaee-api:8.0` was there
to supply `javax.ws.rs` and `javax.json` at compile time. After migration both come from real,
bundled implementations rather than an umbrella API jar: `jakarta.ws.rs-api` arrives transitively with
`resteasy-client` (see FR17), and `jakarta.json-api` arrives transitively with parsson (verified:
`parsson-1.1.7.pom` declares `jakarta.json:jakarta.json-api` as its first dependency). Pulling in
`jakarta.jakartaee-api:11.0.0` instead would put the *entire* Jakarta EE 11 API surface — CDI, EJB,
JPA, JMS, Servlet — on a classpath that has no container to implement any of it, which is how you get
a JAR that compiles and then `NoClassDefFoundError`s. Adding `jakarta.json:jakarta.json-api`
explicitly alongside parsson is also reasonable if an explicit API declaration is preferred to a
transitive one; either is correct, the umbrella is not.

**Runtime JSON-P provider, worth being explicit about since this module has no container.**
`org.glassfish:javax.json:1.0.2` supplied *both* the API and the implementation, and
`Json.createObjectBuilder()` / `Json.createReader()` resolve their provider by `ServiceLoader`.
`org.eclipse.parsson:parsson` is the correct replacement rather than `org.glassfish:jakarta.json`
(also managed, at `2.0.1`) for exactly the reason the M10 parent documents on its own `parsson.version`
property: the glassfish jakarta.json artefact is an OSGi bundle whose `ServiceLoader` registration is
suppressed. In a WAR the container's provider papers over that; in this standalone JAR nothing would.

**Verification status — updated after the cert-trust fix enabled a real build.** All seven pom edits
(the two corrected above included) were applied to the real working tree, and the module **compiles,
builds, and passes its own unit tests on real JDK 25** against the real M10 chain:

```
[INFO] stagingdlrm-azure-functions ........................ SUCCESS
...Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
```

*"The func-app compiles at release 25 against jakarta"* moves from **(b) unverified** to **(a),
genuinely confirmed** — including `StagingDlrmCommandHelper`'s renamed imports actually compiling
against the BOM-managed `utilities-core:25.104.0-M12`, and every RESTEasy 7/jakarta.ws.rs call site
compiling clean. **Deployment to Azure itself remains (b)** — this sandbox has no Azure credentials to
confirm the Windows Functions stack advertises Java 25 for real; see FR16.

### FR16 — The fallback is explicit, not implicit

**Status: (a) verifiable here for the mechanism; the decision to take it is deferred with a named
trigger.**

**The fallback is NOT being taken in this design.** Justification: the two things ADR decision 4 feared
would block Java 25 both turned out not to — `azure-functions-maven-plugin` does not validate
`javaVersion` at `package` time, and the runtime table is loaded dynamically from Azure rather than
from the plugin's static seed. The real blocker was `utilities-core:17.3.1`, and that is fixed by
deleting a duplicate declaration, not by staying on 17.

**Its trigger condition, made checkable rather than left to judgement.** Take the fallback if and only
if, with plugin `1.42.0`:

```
az functionapp list-runtimes --os windows | grep -i 'java'
```

does not list Java 25, **or** the `wildfly40`-track deploy of `fa-ste-ccp0101-dlrm` fails on the
runtime version. Both require Azure credentials this sandbox does not have — **(b)** — so this is a
gate for implementation, not for design.

**The mechanism, designed so that taking it is a two-line change.** ADR decision 4 permits a mixed
reactor because the module is a standalone JAR with no WildFly coupling. But note the trap: bytecode
compiled at release 25 (major 69) cannot load on an Azure Java 17 worker
(`UnsupportedClassVersionError`), so `<javaVersion>` and the compiler release are **coupled** — you
cannot flip one without the other. And FR15 established that `maven.compiler.*` is **not** the lever.
The fallback is therefore:

```xml
<properties>
    <functionapp.java.version>17</functionapp.java.version>   <!-- was 25 -->
    <compiler.release>17</compiler.release>
    <compiler.source>17</compiler.source>
    <compiler.target>17</compiler.target>
</properties>
```

local to `stagingdlrm-azure-functions/pom.xml`, with `<javaVersion>${functionapp.java.version}</javaVersion>`
at `:190`. Three `compiler.*` properties and one value — and the correct property names, which is the
part that would otherwise be discovered the hard way.

**What lands regardless, per FR16.** The jakarta rename (11 import lines in `StagingDlrmCommandHelper`,
3 in `TimerTriggerJava`, 2 in `EventGridMonitorHelper`, plus the three test classes), the parsson swap,
the `javaee-api` drop, the RESTEasy 7 bump, and the `utilities-core` un-pinning are correctness fixes
independent of the JDK. **One caveat the ADR does not state:** if the fallback *is* taken, the func-app
compiles at release 17 against `utilities-core:25.104.x` and `resteasy 7.0.0.Final` — both of which are
themselves built for a higher release. `--release 17` restricts the *JDK* API surface only, not
third-party classfiles, so this fails only if those artefacts' own bytecode is above major 61.
**Unverified here** (neither artefact could be resolved from the M10 chain in this sandbox) and worth a
`javap -v` check at implementation time if the fallback is taken.

**If taken, FR16 requires it recorded here and raised as a follow-up.** It is not taken, so the
recording is this section, and the follow-up raised instead is the Azure Windows Java-25 stack
confirmation (Cross-cutting).

### FR17 — BC-12's fleet fix must not be applied to the Function App

**Status: (a) verifiable here — and the carve-out is structural, which is a better answer than the
discipline the ADR asks for.**

The four artefacts, `stagingdlrm-azure-functions/pom.xml:86–105`, all `4.3.0.Final`, **none carrying a
`<scope>`** (so compile, the default) — verified fresh, and matching what the parity story's withdrawn
`Bc12RestEasyPackagingParityTest` asserted:

| Artefact | Lines |
|---|---|
| `org.jboss.resteasy:resteasy-client` | 86–90 |
| `org.jboss.resteasy:resteasy-jaxb-provider` | 91–95 |
| `org.jboss.resteasy:resteasy-jackson2-provider` | 96–100 |
| `org.jboss.resteasy:resteasy-multipart-provider` | 101–105 |

They must stay bundled: `StagingDlrmCommandHelper` builds a real JAX-RS `Client` via `ClientBuilder`
(`:24–26`, `:44–47`) to POST to `stagingdlrm-command-api`, and there is no container in Azure to supply
RESTEasy. Marking them `provided` compiles and then `NoClassDefFoundError`s at runtime.

**The carve-out is enforced by the build's own structure, not by anyone remembering it.** M10's
`packagingExcludes` (the mechanism that would strip them) is configured on
**`maven-war-plugin`** (`service-parent-pom-25.104.0-M10.pom:96–101`). `stagingdlrm-azure-functions` is
`<packaging>jar</packaging>` (`:11`) and never invokes `maven-war-plugin` at all. **So the fleet-wide
BC-12 fix is structurally incapable of touching this module.** The residual risk is narrower than ADR
decision 5 implies: not "someone runs the fleet sweep", but specifically "someone hand-edits these
four `<dependency>` elements to add `<scope>provided</scope>`".

That matters because the parity checklist flags BC-12 as *"arguably the single most concerning gap in
this checklist"* — a verified-concrete risk whose only guard was withdrawn. Narrowing the risk from a
sweep to a deliberate hand edit is the mitigation this design can offer; it does not restore the test.

**A version bump is now *required*, and this is the part FR17 does not anticipate.** RESTEasy
`4.3.0.Final` is the **javax.ws.rs** line. Once FR5 renames `StagingDlrmCommandHelper`'s and
`TimerTriggerJavaTest`'s / `StagingDlrmCommandHelperTest`'s imports to `jakarta.ws.rs.*`, RESTEasy 4
cannot satisfy them. Move all four to **`7.0.0.Final`**, the jakarta line and the version
`maven-common-bom 25.104.0-M7` manages (`resteasy.version` / `resteasy-client.version` /
`resteasy-multipart-provider.version`, all `7.0.0.Final`).

Availability checked against Central, because the local cache is incomplete
(`resteasy-client` and `resteasy-multipart-provider` are cached at 7.0.0.Final;
`resteasy-jaxb-provider` and `resteasy-jackson2-provider` are **not**):

| Artefact | 7.0.0.Final on Central? | Latest |
|---|---|---|
| `resteasy-client` | **yes** | 7.0.4.Final |
| `resteasy-jaxb-provider` | **yes** | 7.0.4.Final |
| `resteasy-jackson2-provider` | **yes** | 7.0.4.Final |
| `resteasy-multipart-provider` | **yes** | 7.0.4.Final |

All four exist and are reachable from this sandbox. **Keep the explicit `<version>7.0.0.Final</version>`
on each rather than dropping to BOM management**, for two reasons: `maven-common-bom` M7 manages only
`resteasy-client`, `-core`, `-core-spi`, `-jackson-provider` (note: *not* `-jackson2-provider`),
`-multipart-provider` and `-servlet-initializer`, so two of this module's four have no managed version;
and its managed `resteasy-multipart-provider` entry carries five `<exclusions>` (including
`commons-logging` and `jboss-logging`) aimed at WAR deployments, which a standalone JAR must not
inherit — it needs the transitives present. This module already declares `commons-logging:1.2`
explicitly at `:106–110`, which is consistent with that reading.

**AC10** — the four stay compile-scope and bundled, and the PR description states the exclusion
explicitly. Add to the AC10 statement the structural reason (jar packaging, `maven-war-plugin` never
runs) so a later reviewer can verify the claim rather than trust it.

**Verified for real, not just resolved:** once the cert-trust fix enabled a real build, all four
artefacts at `7.0.0.Final` compiled and the module's full unit-test suite (54 tests, including
`StagingDlrmCommandHelperTest`'s direct exercise of the RESTEasy `Client`) passed on real JDK 25.

### FR18 — Delete `liquibase.hub.mode` from `liquibase.properties`

**Status: (a) both halves now.** Deletion verified as before; AC8's second clause — restated below, since
"Liquibase 5 accepts the file" was never this chain's premise — has since been verified for real too:
the full IT run (FR22) executed this exact jar's Liquibase 4.10.0 against a real Postgres container as
part of `runIntegrationTests.sh`'s `runLiquibase` step, and it completed cleanly.

The file, `stagingdlrm-viewstore/stagingdlrm-viewstore-liquibase/src/main/resources/liquibase.properties`,
three lines, exactly as the parity story pinned it:

```
changelogFile: liquibase/stagingdlrm.xml
liquibase.hub.mode: off
liquibase.headless: true
```

**Design: delete line 2. Keep `liquibase.headless`.** Result:

```
changelogFile: liquibase/stagingdlrm.xml
liquibase.headless: true
```

**Correction to FR18's premise, and it changes what AC8 can assert.** FR18 and AC8 are built on
"Liquibase 5.0.3", where `liquibase.hub.mode` is an unknown-parameter failure. Traced through the
cached J25 chain:

- `liquibase.version` is **`4.10.0`** in `parent-pom 25.104.0-M2:87` — **the same value as
  `parent-pom 17.10.12:87`**. It is unchanged by the upgrade.
- `liquibase` appears **nowhere** in `service-parent-pom 25.104.0-M10`,
  `platform-libraries-parent-pom 25.104.0-M11`, `platform-libraries-bom 25.104.0-M11` or
  `common-bom 25.104.0-M5`; `maven-common-bom 25.104.0-M7:909–912` manages `liquibase-core` at
  `${liquibase.version}`, resolving to the same 4.10.0.
- The jar is built by `parent-pom`'s `liquibase-jar` profile (`25.104.0-M2:1446–1515`), activated by
  `<file><exists>src/main/resources/liquibase.properties</exists></file>`, which shades
  `liquibase-core:${liquibase.version}` with `Main-Class: liquibase.integration.commandline.LiquibaseCommandLine`.
  So `docker/scripts/liquibase.sh:35`'s `java -jar stagingdlrm-viewstore-liquibase.jar … update` runs
  the **embedded 4.10.0**, not a platform-supplied Liquibase 5.
- Confirmed on the current chain: `mvn -o help:evaluate -Dexpression=liquibase.version` → `4.10.0`.

**On 4.10.0 both keys are valid** — Hub existed then, and `liquibase.headless` is a supported global
property in 4.x. **So this repo is not, on the M10 chain, exposed to the BC-07 failure FR18
describes.** Two consequences, and neither is "skip the change":

1. **Do the deletion anyway.** Liquibase Hub is sunset; the property's only purpose was to silence Hub
   warnings in 4.1.0–4.17.2; it is inert on 4.10.0 and a hard failure on any 5.x. Removing a no-op
   costs nothing and removes a future landmine. BC-07's residual is precisely *"a per-context sweep of
   copied `liquibase.properties`"* — fixed in 15 framework repos, never swept here.
2. **AC8's second clause cannot be satisfied as written.** "Liquibase 5 accepts the file" is not
   checkable here and, more to the point, is not what this chain runs. Restate it as: *`liquibase.properties`
   contains no `liquibase.hub.mode`, and the effective `liquibase.version` on the branch is recorded.*
   **Also verify `liquibase.headless` against whatever Liquibase the branch actually resolves** — FR18
   flags it as lower confidence, and on 4.10.0 the answer is "valid", so the pragmatic action is to
   re-run the `help:evaluate` check if the platform later moves the pin.

**Do not be reassured by the empty changelog.** `liquibase/stagingdlrm.xml` is a bare
`<databaseChangeLog>` wrapper with **zero `<changeSet>` elements** (verified: 7 lines, no changesets) —
so this module currently applies nothing to `stagingdlrmviewstore`. That is irrelevant to BC-07, as the
parity checklist's BC-07 note already argues: the key is rejected at **config-parse time**, before
Liquibase opens the changelog. And the jar genuinely ships and genuinely runs —
`docker/Dockerfile_stagingdlrm-service:21` bakes it in, `docker/scripts/liquibase.sh:35` executes it
under `set -e`, so a failure there aborts the init script and line 42's `framework-system-liquibase`
never runs either.

**Not done here (deliberately):** the parity checklist offers *"drop the property, **or delete the
module outright** given nothing uses it"*. This design drops the property only. Deleting
`stagingdlrm-viewstore-liquibase` would remove a jar that `Dockerfile_stagingdlrm-service:21` and
`liquibase.sh:35` both name by filename, i.e. it is a coordinated change across the image and the init
script, and it belongs with the empty-read-side clean-up rather than inside a JDK upgrade. Same
reasoning as FR24's.

### FR19 — Check the missing core-domain fields before bumping `coredomain.version`

**Status: (a) — checked against the real M11 jar, not just the M9 proxy, and the answer changed for the
better.**

BC-15 is a **precondition** on the coredomain bump, not a follow-up (ADR decision 2). This check was
originally run against the cached `25.104.0-M9` as a declared proxy for the (at the time) unobtainable
M11; once the cert-trust fix made M11 resolvable for real, the same diff was re-run against the actual
target jar.

**What this repo actually consumes from core-domain.** Every `$ref` across
`stagingdlrm-domain/**`, `stagingdlrm-datatypes-common/**` and `stagingdlrm-command/**` was enumerated
(36 distinct targets). Thirty-three are `//cpp.moj.gov.uk/stagingdlrm/...` — this repo's own schemas.
**Exactly three reach into core-domain**, and all three into the same file:

```
//justice.gov.uk/domain/core/common/definitions.json#/definitions/date
//justice.gov.uk/domain/core/common/definitions.json#/definitions/date-time
//justice.gov.uk/domain/core/common/definitions.json#/definitions/uuid
```

**All three are present and unchanged.** `common-core-domain`'s
`core/common/definitions.json` is **byte-identical** between `17.104.4` and `25.104.0-M9` (`diff` →
no differences), with the same 19 definitions on both sides, and `date`, `date-time`, `uuid` all
present. The jar entry lists are identical too (422 entries each).

**`criminal-court-public-model` *had* lost two schemas at M9 — but not at the real M11 target.** The
M9 proxy showed 422 → 420 entries (missing `json/schema/global/defendantFineAccountNumber.json` and
`json/schema/global/deletedJudicialResults.json`); re-running the identical diff against the real
`25.104.0-M11` jar (`unzip -l | sort | diff`, both directions) produces **zero difference** — both
schemas are back, and the entry count matches `17.104.4` exactly. **The apparent shrink was an artefact
of M9 being an intermediate milestone, not a real characteristic of the actual M11 target** — a useful
general caution about using a lower cached milestone as a stand-in: it can make a *transient* absence
look like the destination's own behaviour. FR10's note about a possible inventory shrink should be read
with this in mind — at the actual M11 the reactor's generated-type count does **not** shrink for this
reason after all.

**Design:** proceed with the bump — already done (FR2) and confirmed clean by the full green build.

**This repo is the less exposed of the two DLRM contexts, as ADR decision 2 predicted.** It has no
compile-time use of core-domain Java types at all: `common-core-domain` is a compile dep of
`stagingdlrm-domain-value-schema` (`pom.xml:13–18`) for **schema resolution**, and both core-domain
artefacts appear only inside `pojo-generation-plugin` `<dependencies>` blocks. Contrast pcfdlrm, which
the ADR notes constructs `uk.gov.justice.core.courts.Defendant` and `ListHearingRequest` directly, so a
missing field is a compile failure there. Here it would be a quietly smaller generated set — which is
exactly why FR10 and FR19 have to be read together.

### FR20 — Resolve the `anonymise` module decision

**Status: (a) verifiable here. ADR decision 6 is CLOSED, with new evidence that separates its two
readings — and the answer is more specific than either.**

ADR decision 6 offered *mandated* (the framework dropped `stream-transformation-*` on 25.104.x, so
removal is forced) versus *incidental* (`defence`/`resulting`/`results` removed unused modules
opportunistically), with a default of retain-and-migrate. The cheap check the requirements' design
note 4 proposes — does `stream-transformation-tool-api` still resolve — was run, and then three more,
because the resolution answer alone is misleading.

**Check 1 — resolution. It resolves.** Both artefacts are in the local cache and are self-pinned by
this repo, not BOM-managed:

- `~/.m2/repository/uk/gov/justice/stream-transformation-tool-anonymise/7.0.0/` — jar + pom present
- `~/.m2/repository/uk/gov/justice/stream-transformation-tool-api/7.0.0/` — jar + pom present
- `~/.m2/repository/uk/gov/justice/stream-transformation-tool/7.0.0/` — the parent pom, present
- the version comes from **this repo's own** `pom.xml:37`
  (`stream-transformation-tool-api.version` = `7.0.0`) via its `<dependencyManagement>` at `:62–66`,
  and `grep stream-transformation` across the whole J25 parent chain returns **nothing** — it is not
  managed in `maven-common-bom` on *either* the 17 or the 25 line.

**So the "mandated" reading is refuted: nothing in the 25.104.x chain drops it, because nothing in the
chain ever managed it.** The version is entirely under this repo's control. `defence`/`resulting`/
`results` cannot have been forced by a resolution failure.

**Check 2 — but the library is javax-bound, which resolution does not tell you.** Decompiled
`stream-transformation-tool-anonymise-7.0.0.jar` (dated **28 May 2020**, class files at **major
version 52** — Java 8):

| Class | `javax.json` references |
|---|---|
| `EventAnonymiserTransformation` | yes — `Json`, `JsonObject`, `JsonObjectBuilder`, `JsonArray`, `JsonArrayBuilder`, `JsonString`, `JsonValue` |
| `EventAnonymiserService` | yes, same set |
| the other 16 classes | none |

`stream-transformation-tool-api-7.0.0.jar` has **zero** `javax.*` references, so `@Transformation` is
namespace-neutral. **`EventAnonymiserTransformation` — the class this repo's one Java file extends — is
javax-era.** There is no jakarta build of `stream-transformation-tool` in the cache and none is
BOM-managed on the 25 line.

**Check 3 — is it deployed? No.** This is what makes the answer safe.
`stagingdlrm-domain-transformation-anonymise` is **not** a dependency of `stagingdlrm-service`
(`stagingdlrm-service/pom.xml:13–76` lists command-api, command-handler, event-listener,
event-processor, query-api, event-sources, framework-management, service-component-config,
healthchecks — not this module), and `docker/Dockerfile_stagingdlrm-service` never mentions it. It runs
**out of container**, as an operational tool: `run-transformation.sh:21–29` launches
`event-tool-7.0.0-swarm.jar` (a WildFly Swarm uber-jar, downloaded at run time from
`${MAVEN_ARTIFACT_BASE_URL}`) with `-Devent.transformation.jar=<this module's jar>` against
`src/test/resources/standalone-ds.xml`. **It never touches WildFly 40 and never shares a classpath
with the migrated modules.**

**Check 4 — does it still compile at release 25?** Its entire source is 8 lines:

```java
package uk.gov.moj.cpp.stagingdlrm.domain.transformation.anonymise;
import uk.gov.justice.tools.eventsourcing.anonymization.EventAnonymiserTransformation;
import uk.gov.justice.tools.eventsourcing.transformation.api.annotation.Transformation;
@Transformation
public class StagingdlrmEventTransformation extends EventAnonymiserTransformation { }
```

**Zero `javax` imports** — it is in none of FR5's counts. It declares no methods and overrides nothing,
so it never names a `javax.json` type. `--release 25` restricts the *JDK* API surface, not third-party
classfiles, and javac reads major-52 classfiles happily. Its one transitive, `org.everit.json.schema`
at `provided`, is managed on the 25 chain (`maven-common-bom 25.104.0-M7:620–622`, `1.6.0`, cached).

#### Decision — retain the module, migrate nothing in it, raise a follow-up

Recorded with reasoning, per FR20 and AC11:

- **Retain.** Removal is not forced (check 1), the module is not deployed (check 3), and it still
  compiles (check 4). Deleting an anonymisation rule set on an assumption is the more expensive
  mistake — the default ADR decision 6 sets, now reached on evidence rather than by default.
- **Change nothing inside it.** No jakarta edit, no `stream-transformation-tool` bump. Its one Java
  file has no `javax` imports to sweep, and "migrating" it — renaming imports it does not have, or
  bumping to a jakarta build that does not exist — would break it against the javax-era
  `event-tool-7.0.0-swarm.jar` it is designed to be loaded by. **The upgrade-mechanics ADR's
  "retain and migrate" default, applied literally here, would be wrong**; the correct application is
  retain and leave alone. Also leave `pom.xml:37`'s `stream-transformation-tool-api.version` at
  `7.0.0` and the `<dependencyManagement>` entry at `:62–66` as they are.
- **Raise the real finding as a follow-up, not as this story's work.** The anonymisation capability is
  **latently unusable on a Jakarta EE 11 estate**: `EventAnonymiserTransformation` links `javax.json`,
  and the WildFly Swarm `event-tool` that hosts it is an EOL runtime with no JDK-25 story. That is a
  platform question (PEG-3296), exactly as ADR decision 6 anticipated — *"if so, the anonymisation
  capability is lost fleet-wide, which is a platform question, not a context one"* — and this evidence
  says it is that question, arriving by a different route than the ADR expected. Ask PEG-3296 for a
  Jakarta build of `stream-transformation-tool`, and whether the fleet's removals were directed.
- **The most likely explanation of `defence`/`resulting`/`results`**, offered as a hypothesis and
  labelled as one: the module is undeployed dead weight in every context, so it was dropped while
  those diffs were already large — the *incidental* reading, which check 1 leaves as the only one
  standing. It is not this design's to confirm.

### FR21 — Done is a QA Docker image, not a merged PR

**Status: (c) blocked here — structurally, not environmentally. This is the one item the cert-trust fix
does not change.**

**The image build cannot run before merge, by design.** `azure-pipelines.yaml:38–43` routes
`Build.Reason == 'PullRequest'` to `context-verify.yaml` (SonarQube only); `:44–50` routes
`IndividualCI` to `context-validation.yaml`, which is where `docker-build.yaml` pushes to
`crmdvrepo01.azurecr.io`. So no PR run of this story can produce or even attempt an image (ADR
decision 7).

**Design: plan the follow-up PR into the story from the start** (requirements design note 6; nine
fleet contexts sit at *"Merged — no Docker image produced (build failed)"*). Two things this design can
hand that PR, both from FR14's investigation:

1. **`dockerfilePath` is the first hypothesis.** The Dockerfile is at
   `docker/Dockerfile_stagingdlrm-service`; `context-validation.yaml`'s parameter defaults to
   `'Dockerfile'`; this repo passes no override. Either the template derives it from
   `serviceName: 'stagingdlrm'` (plausible — the filename is `Dockerfile_${serviceName}-service`) or
   the step has never worked. **Check the `wildfly40` branch of `hmcts/cpp-azure-devops-templates`
   before the merge build** — github.com is reachable, so this is answerable in advance rather than
   after a failed merge build. If the template does not derive it, pass
   `dockerfilePath: 'docker/Dockerfile_stagingdlrm-service'`.
2. **The Dockerfile itself needs no content change** — parameterised base image, no `yum`/`apt-get`,
   version-parameterised `ADD`s. See FR14. So a failure is a pipeline-wiring problem, not a Dockerfile
   problem, and the follow-up PR should look at `azure-pipelines.yaml` first.

**AC12** — record the published tag. Not obtainable here.

### FR22 — Integration tests must pass on the Java 25 stack

**Status: (a) — genuinely green, on a real WildFly 40 / JDK 25 container. Originally recorded as (c)
blocked; both blockers (Artifactory reachability, no local image) turned out to be resolvable in this
same sandbox.**

**Correction to the count:** the requirements say 4 IT classes. There are **3**:
`stagingdlrm-integration-test/src/test/java/uk/gov/moj/cpp/stagingdlrm/it/` contains
`CaseSubmissionProcessedIT.java`, `ReceiveCaseFileSubmissionIT.java`, `ReceiveErrorCaseSubmissionIT.java`.

**The WildFly 40 image: a real path exists and it is further along than the ADR records.** ADR
decision 6's consequences note says *"`cpp-developers-docker` was on the 26.1.3 image as of the
investigation report; the tracker records a `java-25` branch since. Confirm before relying on local
ITs."* Confirmed, in the local checkout at `~/hmcts/cpp-developers-docker`:

- The **default/`java-17` branch** is on `hmcts/wildfly:26.1.3.Finaljdk17_Camunda7.17_latest`
  (`containers/wildfly/Dockerfile:2`) — the state the brief describes.
- **`origin/java-25` exists and carries a real, complete WildFly 40 / JDK 25 Dockerfile**, head
  commit `7c99c3b` (2026-06-22, *"Fix Docmosis cache dir creation on WildFly 40 / JDK 25 image"*).
  Its `containers/wildfly/Dockerfile` is a deliberate two-stage build, and its own comment explains
  why:

  > `# quay.io/wildfly/wildfly:40.0.0.Final-jdk25 does not exist — WildFly only publishes jdk17/jdk21 tags.`
  > `# Stage 1: extract WildFly 40 installation from the official jdk21 image.`
  > `# Stage 2: use eclipse-temurin:25-jdk as the runtime and copy WildFly 40 in.`

  `FROM quay.io/wildfly/wildfly:40.0.0.Final-jdk21 AS wildfly-source` →
  `FROM eclipse-temurin:25-jdk-noble`, `COPY --from=wildfly-source /opt/jboss/wildfly`,
  `ENV JAVA_HOME=/opt/java/openjdk`, plus a `groupmod`/`usermod` to rename Temurin Noble's `ubuntu`
  user to `jboss`. It also carries `WILDFLY-32-MIGRATION.md` and a `config/postgresql-module.xml` the
  java-17 branch lacks. Sibling branches `origin/release/25.104.x` and
  `origin/dev/java-25-wildfly-40-upgrade-spike` carry the same work.
**It was built into a local image, for real, in this sandbox:**

```
az acr login --name crmdvrepo01          # az CLI was already authenticated
cd $CPP_DOCKER_DIR && git checkout java-25
docker compose build cpp-wildfly         # pulls crmdvrepo01.azurecr.io/hmcts/wildfly:40.0.0.Finaljdk25_Camunda7.24_latest
→ Image containers-cpp-wildfly Built
```

The base image (`40.0.0.Finaljdk25_Camunda7.24_latest`) is a private ACR image; `docker pull` needed
`az acr login`, which worked immediately on this box's already-authenticated `az` session. The
Dockerfile at this branch's current head (`daa1829`, further along than the `7c99c3b` this design first
found) has since moved past the `quay.io`/two-stage extraction approach to a single-stage build from
this published Camunda-baked base image directly.

**Then the actual integration-test run, not a hypothetical one:**

```
export CPP_DOCKER_DIR=~/hmcts/cpp-developers-docker
cd ~/hmcts/cpp-context-stagingdlrm && JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./runIntegrationTests.sh
```

First attempt failed at WAR deployment — a real WELD CDI error, not an environment problem, fixed by
FR4's `system.id-mapper.version` bump. Second attempt:

```
Tests run: 1, ... ReceiveErrorCaseSubmissionIT
Tests run: 17, ... ReceiveCaseFileSubmissionIT
Tests run: 1, ... CaseSubmissionProcessedIT
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Both things this document originally called blockers for AC13 — Artifactory reachability and "no
local WildFly 40 image" — were resolved in this same sandbox, on the same day.** `buildWars`'s full
reactor install (previously assumed blocked on `service-common-resources`/`framework-api-validator`/
`coredomain`) succeeded once the cert was trusted; the parity story's own "pre-existing"
`progression-query-api`/`pcfdlrm-command-api` gap turned out to be the same misdiagnosis (see FR4's
correction) and resolved too.

**AC13 — met.** All 3 IT classes pass, 19 tests, 0 failures, against a real WildFly 40 / JDK 25 /
Camunda 7.24 container.

### FR23 — `stagingdlrm-viewstore-persistence`'s dead coordinates

**Status: (a) verifiable here — reproduced exactly, plus two coordinates FR23 does not list.**

**FR23's four are confirmed, both by BOM reading and by an executed build.** Reading first:
`maven-common-bom` **`17.104.0`** manages **8** DeltaSpike artefacts (`deltaspike.version` = `1.9.6`:
`deltaspike-core-api`, `-core-impl`, `deltaspike-data-module-api`, `-impl`, `deltaspike-cdictrl-openejb`,
`deltaspike-test-control-module-api`, `-impl`, `deltaspike-cdictrl-api`) and
`org.hibernate:hibernate-entitymanager`. `maven-common-bom` **`25.104.0-M7`** — the version
`common-bom 25.104.0-M5` imports, which `service-parent-pom` M10 imports — contains **zero** matches
for `deltaspike` and **no** `hibernate-entitymanager`; its only Hibernate entry is `hibernate-core`.
And `uk.gov.justice.services:persistence-deltaspike` is cached only to `17.104.0`, while
`persistence-jpa` exists at `25.104.0-M5` — the relocation FR23 describes.

Executed, on the unmigrated tree against M10 under JDK 25, `mvn -o clean install` produced **seven**
`'dependencies.dependency.version' … is missing` errors across **three** POMs, before compiling
anything — FR23's "hard dependency-resolution failure" reproduced verbatim:

| Coordinate | File | Line | In FR23's list? |
|---|---|---|---|
| `uk.gov.justice.services:persistence-deltaspike` | `…viewstore-persistence/pom.xml` | 18 | yes |
| `org.apache.deltaspike.modules:deltaspike-test-control-module-api` | same | 99 | yes |
| `org.apache.deltaspike.modules:deltaspike-test-control-module-impl` | same | 104 | yes |
| `org.apache.deltaspike.cdictrl:deltaspike-cdictrl-openejb` | same | 109 | yes |
| `org.hibernate:hibernate-entitymanager` | same | 114 | yes |
| **`javax.json:javax.json-api`** | `…domain-event/pom.xml` | **24** | **no** |
| **`javax.annotation:javax.annotation-api`** | `…command-api/pom.xml` | **31** | **no** |

**The two additions matter** because FR23 presents itself as the complete list of what stops
`mvn clean install`, and a reader working only from it would fix five of seven and be surprised twice.
Note `deltaspike-cdictrl-api` is *not* present in this repo (FR23 hedges "where present" — it is not),
so the count is five in this module, not six.

**Design:**

| Site | Edit |
|---|---|
| `…viewstore-persistence/pom.xml:18–21` | **delete** `persistence-deltaspike`. Do **not** substitute `persistence-jpa` — see FR24 |
| `…viewstore-persistence/pom.xml:99–113` | **delete** all three `org.apache.deltaspike.*` test deps |
| `…viewstore-persistence/pom.xml:114–118` | **delete** `hibernate-entitymanager` |
| `…viewstore-persistence/src/test/resources/META-INF/apache-deltaspike_test-container.properties` | **delete the file.** The reference deleted this exact file and added a test `persistence.xml`; this repo adds nothing — see FR24 |
| `…domain-event/pom.xml:24–28` | `javax.json:javax.json-api` → **`jakarta.json:jakarta.json-api`**, `provided`, `<version>` dropped (BOM-managed `2.1.3`, `maven-common-bom 25.104.0-M7:220–223`; cached) |
| `…command-api/pom.xml:31–34` | `javax.annotation:javax.annotation-api` → **`jakarta.annotation:jakarta.annotation-api`**, `<version>` dropped (managed `3.0.0`, `…M7:195–198`; cached) |

Two more javax coordinates that do *not* fail resolution but should move with the rest, since they are
`javax.*` API jars on a Jakarta EE 11 build:

| Site | Edit | Why it did not fail |
|---|---|---|
| `…command-api/pom.xml:35–38` | `javax.xml.bind:jaxb-api` → **`jakarta.xml.bind:jakarta.xml.bind-api`** (managed `4.0.2`, `…M7:245–248`) | `javax.xml.bind:jaxb-api` is *still* managed on the 25 chain, in `maven-common-bom`'s explicitly-labelled *"Legacy javax APIs — only kept for libraries that cannot yet be migrated"* section (`:250–261`), with the comment *"needed by legacy libraries (e.g. `org.raml:raml-parser`) on Java 11+"*. Resolves; wrong namespace for this repo's own use |
| `…viewstore-persistence/pom.xml:26–29` | same swap — **or dropped with the module's trim, see FR24** | same |

And the three test-scope JSON-P provider declarations, which likewise resolve and are likewise wrong:

| Site | Edit |
|---|---|
| `…command-handler/pom.xml:58–62` | `org.glassfish:javax.json` (test) → **`org.eclipse.parsson:parsson`**, `<version>` dropped |
| `…event-listener/pom.xml:60–64` | same |
| `…domain-aggregate/pom.xml:46–50` | same |

`org.glassfish:javax.json` is **still managed** on the 25 chain at `1.1.4`
(`maven-common-bom 25.104.0-M7:649–653`, alongside `org.glassfish:jakarta.json` at `2.0.1`), so these
three resolve silently and supply a **javax** JSON-P implementation to tests whose code now imports
`jakarta.json` — a runtime `ServiceLoader` miss in the tests, not a compile error. **Parsson, not
`org.glassfish:jakarta.json`**, for the OSGi/`ServiceLoader` reason the M10 parent documents (FR9).

**AC14** — after these edits, `grep -rn 'deltaspike\|hibernate-entitymanager' --include='pom.xml' .`
returns only a stale comment (`…viewstore-persistence/pom.xml:28`, `<!-- Required by deltaspike -->`),
which should be deleted too, and the properties file is gone. Verified in the probe.

### FR24 — Prefer deletion over migration for `stagingdlrm-viewstore-persistence`; justify either way

**Status: (a) verifiable here. Decision: RETAIN the module, TRIM its dependencies to nothing but what
its two descriptors need.**

FR24 steers toward deletion and asks that the question be settled first — *"Establish first whether the
module is needed at all… a module deleted by accident is worse than a dependency list trimmed too
cautiously."* Settled, with the full file inventory:

```
stagingdlrm-viewstore/stagingdlrm-viewstore-persistence/
├── pom.xml
└── src/
    ├── main/resources/META-INF/beans.xml         ← FR8
    ├── main/resources/META-INF/persistence.xml   ← FR8
    └── test/resources/META-INF/apache-deltaspike_test-container.properties   ← FR23, deleted
```

**Zero `.java` files** (`find stagingdlrm-viewstore -name '*.java'` → 0), confirming both
`dlrm-flow-reference.md` §3.1's role line ("DeltaSpike/JPA persistence") is aspirational and the parity
story's independent finding. Its `persistence.xml` declares a unit with **no `<class>` entries**. The
parity checklist puts it beyond doubt: *"There is no viewstore"* —
`stagingdlrm-viewstore-persistence`, `stagingdlrm-event-listener` and `stagingdlrm-query-api` each
contain zero Java files, and the Liquibase changelog has zero changesets.

#### Why retain

**It is a real, consumed dependency of two deployed WARs.** Not orphaned:

- `stagingdlrm-event/stagingdlrm-event-listener/pom.xml:38–42`
- `stagingdlrm-query/stagingdlrm-query-api/pom.xml:29–33`

and both of those reach `stagingdlrm-service.war` via `<classifier>classes</classifier>`. **What the
module actually ships is its two `META-INF` descriptors**, and `persistence.xml` is what binds the
persistence unit `stagingdlrm-persistence-unit` to the JNDI data source `java:/DS.stagingdlrm`. That
data source is provisioned — `docker/scripts/liquibase.sh:21–35` runs migrations against
`${contextName}viewstore` on a real Postgres, and the framework's own event-buffer and event-tracking
Liquibase jars (`:21`, `:28`) target that same database. Deleting the module would remove
`persistence.xml` from the deployment and could break WildFly 40's deployment-time persistence-unit
validation — for a saving of one POM and two files.

**Deleting it is a bigger change than it looks**, and none of it is JDK-upgrade work: remove the module
from `stagingdlrm-viewstore/pom.xml`'s `<modules>`, remove both dependency declarations, and confirm
the framework does not require a persistence unit for `EVENT_LISTENER`/`QUERY_API` service components.
That last confirmation needs a WildFly 40 deploy, which FR22 establishes cannot happen here. **So
deletion cannot be validated in this sandbox and retention can — that asymmetry decides it.**

**It fits the precedent set one story earlier.** The parity story hit the same question about the same
cluster and reached the same shape of answer: it declined to test the empty read side, recorded the
`QUERY_API` zero-rule kbase as *dead configuration*, and *"handed to the owners as a separate,
non-parity tidy-up to be applied to both branches, at whatever point the empty read-side modules are
dealt with as a whole."* Retiring the read side — `viewstore-persistence`, `viewstore-liquibase`,
`event-listener`, `query-api`, the `QUERY_API` `kmodule.xml`, the empty changelog — is one coherent
piece of work with one owner. Doing a quarter of it inside a JDK upgrade gets the diff blamed for the
next read-side surprise.

#### The trim

`…viewstore-persistence/pom.xml` currently declares **1 `provided` + 3 compile + 15 test**
dependencies to support **zero** Java files and **zero** tests. The FR23 deletions remove 5. The
remaining test-scope block exists purely as scaffolding for tests that do not exist:
`junit-vintage-engine`, `junit-jupiter-api`, `mockito-core`, `hamcrest`, `guava-testlib`,
`test-utils-common`, `test-utils-persistence`, `test-utils-logging-log4j`, `org.everit.json.schema`,
`schema-service`, `openejb-core`, `openejb-server`, `commons-dbcp2`.

**Design: reduce the module to what its two descriptors need — which is nothing.** Delete the entire
`<dependencies>` block, including the `javaee-api` at `:13–17` (FR7 site 7) and the `jaxb-api` at
`:26–29`. A resources-only jar needs no dependencies at all; `utilities-core` at `:22–25` is unused
with no code to use it.

**Explicitly do NOT reproduce the reference's migration.** The reference swapped
`persistence-deltaspike` → `persistence-jpa` and replaced
`apache-deltaspike_test-container.properties` with a test `persistence.xml`. Both moves exist to keep
*its* viewstore tests running; the reference has entities and repositories. Doing the same here would
"faithfully reproduce scaffolding for nothing", in FR24's own words. **No `persistence-jpa`, no test
`persistence.xml`.**

Keep `<sonar.skip>`-style hygiene as-is; nothing else in the module changes beyond FR8's
`persistence.xml`/`beans.xml` namespace moves.

**AC15** — recorded: **retained, dependencies trimmed to none**, on the grounds that (i) two deployed
WARs consume it, (ii) its `persistence.xml` is the only thing binding `java:/DS.stagingdlrm` and that
database is really migrated at container start, (iii) deletion is unverifiable in this sandbox while
retention is verifiable, and (iv) the read-side retirement is a separate, already-identified piece of
work with a wider blast radius than this story should carry.

---

## Cross-cutting

### Commit shape

**One PR, but the changes are not independently landable and the ordering inside it matters.** FR5's
import rename and FR7's coordinate swap are two halves of one edit — neither compiles alone. Suggested
commits, each one reviewable:

1. **`pom.xml`: parent → M10, `coredomain` → M11** (FR2). Fails the build; that is expected and is the
   FR23 evidence.
2. **Dead coordinates and descriptors** (FR23, FR24, FR8) — the deltaspike/hibernate/javax-api
   deletions, the `viewstore-persistence` trim, all 10 `beans.xml`, `persistence.xml`. After this the
   reactor *reads*.
3. **The jakarta sweep** (FR5, FR6, FR7, FR9) — the scripted 75-line rename plus all 12 `javaee-api`
   sites plus the two generator fixes, together.
4. **The Function App** (FR15, FR16, FR17) — its seven pom items, sequenced early per requirements
   design note 3 so the fallback can be taken without re-planning.
5. **Pipeline and known defects** (FR11, FR18) — `azure-pipelines.yaml`'s three edits and the
   `liquibase.hub.mode` deletion.

Nothing for FR1a (no-op), FR12 (already inherited), FR13 (nothing to amend) or FR14 (nothing to edit) —
four requirements whose correct implementation is a deliberate absence of change, each with its
evidence above so a reviewer can see the absence was reasoned and not overlooked.

Three more commits landed after the five above, once the cert-trust fix let real builds run and surface
real defects no amount of model-validation could have found:

6. **Two Function App reverts** (FR15) — `azure-functions-maven-plugin`'s version back to a literal
   string (the property this design first tried broke `RequirePluginVersions`), and `test-utils-common`
   back to `2.4.1` (the BOM-managed bump this design first recommended removed a class several tests
   use).
7. **A plugin-scoped JAXB compat dependency** (FR9) — `javax.xml.bind:jaxb-api:2.3.1` in
   `stagingdlrm-event-processor`'s `messaging-client-generator-plugin` block, for a 2016-era transitive
   library's hardcoded old-package reference that only this module's combined RAML+YAML invocation
   exercises.
8. **`system.id-mapper.version` bump** (FR4) — `17.103.5` → `25.104.0-M11`, for a real CDI
   bean-discovery failure on a real WildFly 40 container.

### What the cert-trust fix changed

The single biggest correction in this document's history. Recorded here as a before/after table because
so many sections above changed status on the strength of one `keytool` command:

| Item | Recorded before the fix | Actual, after the fix |
|---|---|---|
| CPP internal Artifactory | "(c) blocked — unreachable" | **Reachable.** DNS resolves, TCP connects; the failure was an untrusted self-signed internal CA (`CN=<internal-root-ca>`), not a network block |
| FR2 (`coredomain` M11) | "(c) blocked — artefact unobtainable" | **(a) resolves and builds clean** |
| FR3 (parity gate) | "(c) blocked — cannot run tests" | **(a) genuinely green**: `AccessControlTest`, 4/4 |
| FR4 (interface pins) | "(b) mechanical — enforcer can't run offline" | **(a) — and a real bump was needed** that no enforcer check would have found (a runtime CDI failure); see below |
| FR9 (generator fixes) | "(a) for POM validity, (b) for code generation" | **(a) both** — code generation genuinely runs, and a third real defect was found and fixed |
| FR10 (inventory shrink) | "will shrink, for a BC-15 reason" | **Does not shrink** — that finding was itself an artefact of using the M9 proxy instead of the real M11 |
| FR15 (Function App) | "(b) — compilation never reached" | **(a) — compiles and its unit tests pass**, after reverting two of this design's own recommendations that broke on contact with a real build |
| FR19 (core-domain fields) | "(b) for M11, (a) for the mechanism, using M9 as a proxy" | **(a) — re-checked against the real M11 jar**: zero diff, not a two-schema shrink |
| FR21 (QA Docker image) | "(c) blocked" | **Still (c) blocked** — this is the one item that was never environmental; the image step structurally cannot run on a PR, in any environment |
| FR22 (integration tests) | "(c) blocked, no image" | **(a) — genuinely green**, 19/19, against a real WildFly 40 image built in this same sandbox |
| The parity story's own "pre-existing" gap (`progression-query-api`/`pcfdlrm-command-api`) | recorded as a real, `git stash`-verified environment gap | **Also just the same cert issue** — both resolve fine; flagged as a correction that story's own docs may need too (not made here, since it is not this story's document to edit) |

### Corrections to the stage-1 documents

Recorded here rather than by editing them, per the convention the parity-method ADR's decisions 7 and 8
established.

| Where | Says | Actually |
|---|---|---|
| `01-requirements.md` measured surface | 92 `javax` import lines / 40 files | **76 / 34** on `b23ea10` |
| FR7 | 9 modules + 2 plugin blocks (**11** sites) | 9 modules but **10** normal sites (testharness declares it twice) + 2 plugin = **12** |
| FR7 | plugin-internal at `pom.xml:105`, `:117` | **`:92–96`** and **`:104–108`** |
| FR12 | parent's jacoco is 0.8.12; every context needed a local override | M10 chain already pins **0.8.14** at two levels; **no override needed** |
| FR13 | the reference added a root `jboss-deployment-structure.xml` (implying this repo should assess adding one) | that file is a byte-copy of the one `service-common-resources` already injects, referenced by nothing. And this repo's existing file **never reaches any artefact** |
| FR14 | "There is no `Dockerfile` at this repo's root", target unclear | `docker/Dockerfile_stagingdlrm-service` **exists** — the reference's pattern — and needs **no change** (parameterised base, no `yum`) |
| FR15 item 1 | `maven.compiler.source`/`target` 17 → 25 | those properties are **dead**; `compiler.release`/`source`/`target` from the parent is the live lever, and it moves to 25 by itself |
| FR15 / ADR decision 4 | plugin version is the highest-uncertainty item | plugin is Java-8 bytecode and `package` never reads `javaVersion`; the real blocker is **`utilities-core:17.3.1`**, a javax-era `JsonObjects`, unlisted in either document |
| FR18 / AC8 | Liquibase 5.0.3 rejects `liquibase.hub.mode` | this chain ships **4.10.0**, unchanged from J17, where the key is valid. Delete it anyway; restate AC8 |
| FR22 | 4 IT classes | **3** |
| FR23 | four dead coordinates | **five** in that module, plus **two** more elsewhere (`javax.json-api`, `javax.annotation-api`) that also fail model-read |
| FR10 | the parity story's derived inventory assertion must still pass | **there is no such assertion** — all four BC-21 rows are ⚪/🟡 |
| FR10/FR19 (this document's own first pass) | the generated-artefact inventory *will* shrink by two, for a BC-15 reason, checked against the `25.104.0-M9` proxy | **Re-checked against the real `25.104.0-M11` jar: zero difference.** The shrink was an artefact of M9 being an intermediate milestone, not a property of the actual target |
| This document's own first pass, throughout | CPP internal Artifactory is unreachable from this sandbox; every `mvn` invocation must use `-o`; AC2/FR3/FR9's codegen/FR15's compile/AC13 are all "(c) blocked" | **Wrong.** An untrusted self-signed internal CA certificate, not network unreachability — see "What the cert-trust fix changed" above |
| This document's own first pass (FR4) | `pcfdlrm-command-api`/`progression-query-api` (and, following the parity story, this pair generally) are unresolvable in this sandbox | **Also the same cert-trust misdiagnosis** — both resolve fine once fixed; the parity story's own equivalent claim likely needs the same correction (not made here) |
| This document's own first pass (FR15) | recommended bumping `test-utils-common` to the BOM-managed `25.104.0-M5` "for consistency" | **Wrong — tested and reverted.** M5 removed the `ReflectionUtils`/`setField` class several tests use; kept at `2.4.1` |
| This document's own first pass (FR15) | recommended collapsing `azure-functions-maven-plugin`'s version onto a `${azure.functions.maven.plugin.version}` property | **Wrong — tested and reverted.** `RequirePluginVersions` does not reliably resolve a property-based version for a plugin only one leaf module declares; reverted to a literal string |
| `docs/j25-parity-checklist.md`, BC-20 note | *"the DRL has no explicit `package` statement, so Drools infers one from the resource's directory path"* | `command-migrate-case-submission-api.drl:1` **does** declare `package uk.gov.moj.cpp.stagingdlrm.command.api.accesscontrol`, matching `kmodule.xml`'s `packages` filter. The conclusion (rules load, no `system-doc-generator` gotcha) is right; the stated reason is wrong |

### Residual risk

Ranked by expected cost. FR3's gate itself is narrow, but note that this story's actual verification —
a full green build, a full green unit-test run, and a full green IT run against a real WildFly 40
container — now covers far more of the real system than the parity gate alone; several items below that
were "unverified here" in this document's first pass have since been confirmed by that real run.

1. **BC-12 on the Function App.** The parity checklist's own assessment stands — the most concerning
   gap, with no automated guard. FR17 narrows it from "a fleet sweep could break this" to "only a
   deliberate hand edit of four `<dependency>` elements could", because the module is jar-packaged and
   `maven-war-plugin` never runs there. The PR description must still state the carve-out (AC10), and
   should state the structural reason so a reviewer can check it rather than trust it.
2. **`resteasy-jaxb-provider` / `-jackson2-provider` at 7.0.0.Final — resolved, no longer a risk.**
   Originally recorded as "not in this sandbox's cache, transitive closure unverified" — both resolved
   and built clean once the cert-trust fix allowed a real online build, and the func-app's own unit
   tests (which exercise `StagingDlrmCommandHelper`'s RESTEasy client) passed. Still worth a first check
   if the module fails at runtime in Azure specifically, since this sandbox cannot exercise the real
   Azure Functions runtime.
3. **`azure-eventgrid:1.4.0`** (`stagingdlrm-event-processor/pom.xml:59–63`) — a 2019-era Azure SDK,
   hard-pinned, on the outbound path for every outcome event (`dlrm-flow-reference.md` §3.3, §2.6
   Paths 1 and 2). Its bytecode still was not inspected (only its `-sources` jar was checked), so this
   remains unverified in the specific sense of "does its own namespace exposure matter" — though the
   full IT suite's green run (which exercises this outbound path) is reassuring in practice, since
   `azure-eventgrid`'s stack is `azure-client-runtime` (Retrofit/OkHttp), not JAX-RS.
4. **The Azure Windows Functions Java-25 stack** (FR15). Still needs `az functionapp list-runtimes
   --os windows` with real Azure Functions permissions (this sandbox's `az` session is authenticated for
   ACR but was not used to check Functions runtimes) — the one thing about FR15 that a build, however
   real, cannot confirm. If Java 25 is absent for Windows, FR16's fallback triggers — a two-line change,
   but it must be caught before the deploy.
5. **Locale on `ubuntu-j25`.** Low confidence, recorded because the reference carries
   `LANG`/`LC_ALL: en_GB.UTF-8` and this repo does not. This repo's only locale-sensitive code is
   `MessageFormat` inside `LoggerHelper` log messages, so the exposure looks cosmetic. **Not** added —
   FR11 shows those vars predate the reference's upgrade, so copying them would be scope creep on a
   guess.

### Follow-ups to raise (not this story's work)

| Follow-up | Owner | Source |
|---|---|---|
| **Jakarta build of `stream-transformation-tool`.** `EventAnonymiserTransformation` links `javax.json`; its host `event-tool-7.0.0-swarm.jar` is WildFly Swarm (EOL). Anonymisation is latently unusable fleet-wide on Jakarta EE 11. Also ask whether the fleet's module removals were directed | PEG-3296 | FR20 |
| **`dockerfilePath` on the `wildfly40` template track.** Confirm the template derives `docker/Dockerfile_stagingdlrm-service` from `serviceName`, before the merge build | this story's follow-up image PR | FR14, FR21 |
| **Retire the empty read side** — `viewstore-persistence`, `viewstore-liquibase`, `event-listener`, `query-api`, the `QUERY_API` `kmodule.xml`, the empty changelog. One coherent change, one owner, both branches | read-side owners | FR13, FR18, FR24; already handed over by the parity checklist |
| **`sonarqubeProject` key mismatch.** `azure-pipelines.yaml:32` says `uk.gov.moj.cpp.staging.dlrm:staging-dlrm-parent`; the reactor is `uk.gov.moj.cpp.stagingdlrm:stagingdlrm-parent`. Pre-existing; fixing it moves historical Sonar data | context owners | FR11 |
| **Parity coverage for the two primary items.** BC-13 and DLRM-01 have zero coverage, so this upgrade lands with no test over either schema-validation stack. Both tests exist in git history (BC-13's at `a3fa641`) if reopened | a follow-on story | FR3, parity checklist Gaps |
| **Re-check the parity story's own checklist for the same cert-trust misdiagnosis.** Its Gaps section records `progression-query-api`/`pcfdlrm-command-api` as a `git stash`-verified "pre-existing" environment gap on J17; this story found the identical pair resolves fine once the internal CA is trusted. Worth confirming whether that story's J17-era claim holds independently, or was the same misdiagnosis found here | parity-story owners | FR4, FR9 |
| **Confirm Azure Windows Functions actually advertises Java 25** (`az functionapp list-runtimes --os windows`, real Functions-scoped credentials) before relying on FR15's build-time confirmation alone — a real deploy is the only thing this story's verification could not reach | whoever performs the Azure deploy | FR15, FR16 |

### Reproducing this document's checks

Everything asserted above was produced by one of six kinds of command, all re-runnable:

```bash
# 1. Source and pom inventory (no build needed)
grep -rE '^import (static )?javax\.' --include='*.java' . | grep -v '/target/'
grep -rn 'javax' --include='pom.xml' . | grep -v '/target/'
grep -rl 'bean-discovery-mode="all"' --include='beans.xml' . | grep -v '/target/' | wc -l

# 2. Platform-chain facts, straight out of the cached POMs
grep -n 'jacoco\|javaee-api.version\|compiler.release\|liquibase.version\|parsson' \
  ~/.m2/repository/uk/gov/moj/cpp/common/parent-pom/25.104.0-M2/parent-pom-25.104.0-M2.pom
grep -n 'deltaspike\|hibernate-entitymanager\|wildfly.version\|resteasy' \
  ~/.m2/repository/uk/gov/justice/maven-common-bom/25.104.0-M7/maven-common-bom-25.104.0-M7.pom

# 3. Bytecode namespace checks (FR15's utilities-core, FR20's anonymise, FR4's id-mapper-client,
#    FR9's raml-parser)
javap -v -p uk/gov/justice/services/messaging/JsonObjects.class | grep -c 'javax/json'
javap -v -p uk/gov/justice/tools/eventsourcing/anonymization/EventAnonymiserTransformation.class
javap -v -p uk/gov/moj/cpp/systemidmapper/client/SystemIdMapperClientProducer.class | grep -oE '(javax|jakarta)/(enterprise|inject)'
javap -c -p org/raml/parser/tagresolver/JaxbTagResolver.class | grep 'SchemaOutputResolver'

# 4. Artefact-existence and resolution checks, against Central and (once trusted) the real Artifactory
curl -s -o /dev/null -w '%{http_code}\n' https://repo1.maven.org/maven2/<path>
mvn dependency:get -Dartifact=<groupId>:<artifactId>:<version>       # real resolution, not just a 404 check

# 5. Built-artefact checks (FR13 — the decisive one)
unzip -p stagingdlrm-service/target/stagingdlrm-service-*.war WEB-INF/jboss-deployment-structure.xml

# 6. The real thing: a genuine online build and a genuine IT run
JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn clean install
az acr login --name crmdvrepo01 && (cd "$CPP_DOCKER_DIR" && git checkout java-25 && docker compose build cpp-wildfly)
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./runIntegrationTests.sh
```

**Historical note on the probe build.** Before the cert-trust fix, a throwaway `git archive`-based copy
(never touching the working tree) was used to test the mechanical migration offline, with
`coredomain.version` and `cpp.service-common-resources.version` temporarily substituted to cached lower
versions. That probe's positive result (a clean model construction) held up; its "blocked" conclusion
did not. Once the fix landed, every check in this document was re-run against the **real working tree**,
not a substitute — command 6 above is what actually happened, not a proxy for it.
