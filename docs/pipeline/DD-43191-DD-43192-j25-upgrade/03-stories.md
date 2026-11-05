# Implementation tasks — DD-43192: Java 25 / WildFly 40 / Jakarta EE 11 upgrade of stagingDLRM

> Stage 3 artefact. Source: [`02-design.md`](./02-design.md). Task boundaries follow the design's
> Cross-cutting → Commit shape section (five commit-shaped clusters, plus three more added after
> implementation surfaced real defects), split further only where a cluster mixed genuinely independent
> verification work. Every verification status below is inherited from the design, not re-assessed —
> **(a) verifiable here**, **(b) mechanical only, effect unobservable in this sandbox**, **(c) blocked in
> this sandbox**. Where the design corrected a requirements-document premise or number, the correction is
> carried here; the original requirement's figure is not restated as current.
>
> **Update, 2026-09-10, same day as first written.** Almost every task below originally marked "(c)
> blocked in this sandbox" has since been re-verified as **(a), genuinely done** — a truststore fix (one
> `keytool` command, see T2 and `02-design.md`'s Environment section) turned out to be all that stood
> between this sandbox and a real online build. T2, T4, T5 and T11 carry the updated status and the real
> defects that surfaced once real builds actually ran; nothing below is left claiming "blocked" where it
> no longer is. **AC12 (the QA Docker image, T11) is the one exception** — that block is structural, by
> pipeline design, not environmental, and no fix in this sandbox changes it.

## T1 — root `pom.xml` / `git log`: FR1 + FR1a — parity-PR gate, already satisfied; sweep is a no-op
- FR1 (do not start before the parity PR merges): **(a), already satisfied.** `git log` on
  `team/25.104.x` shows `a9473ef` (the parity PR, "DD-43192: J17 parity tests for the Java 25 upgrade
  (stagingDLRM) (#53)") as an ancestor of every upgrade-stage commit up to `b23ea10` (HEAD). The branch
  was still `service-parent-pom 17.104.1` at `a9473ef`, which is what makes the parity runs J17
  evidence.
- FR1a (migrate the parity tests' imports as part of the sweep): **(a), verified no-op.** The parity PR
  touched exactly one source file,
  `stagingdlrm-command/stagingdlrm-command-api/src/test/java/uk/gov/moj/cpp/stagingdlrm/command/api/accesscontrol/AccessControlTest.java`
  (+28 lines). Its import list carries **zero `javax.*` imports** — only `java.util.*`,
  `org.junit.jupiter`, `org.mockito`, `org.kie.api.runtime.ExecutionResults`, and framework types. There
  is nothing for T4's sweep to touch in this file; it will simply be recompiled and re-run unchanged.
  The "authored once, not twice" payoff the single-branch layout was chosen for did not actually get
  exercised, because every parity test that *did* carry `javax.json` imports (in
  `stagingdlrm-azure-functions`, `stagingdlrm-domain-value-schema`, `stagingdlrm-viewstore-liquibase`)
  was built, run green on J17, then reverted whole-file to its pre-parity-story state before this branch
  reached this stage.
- Also note from FR1: the project's own version line already moved independently of the parent pin
  (`f2ef772`/PR #54 took it `17.104.26-DLRMJ25-SNAPSHOT` → `25.104.26-…`, then `b23ea10` →
  `25.104.27-DLRMJ25-SNAPSHOT`). That is not FR2's work and must not be re-done in T2.
- Acceptance: FR1, FR1a, AC1.

## T2 — root `pom.xml`: FR2 + FR4 + FR19 — platform milestones, interface pins, the core-domain field check
- FR2, `pom.xml:8`: bump the parent `uk.gov.moj.cpp.common:service-parent-pom` `17.104.1` →
  `25.104.0-M10`. **(a) — verified.** `JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn -o -N validate`
  returns `BUILD SUCCESS` against the cached chain (`service-parent-pom 25.104.0-M10` →
  `platform-libraries-parent-pom 25.104.0-M11` → `parent-pom 25.104.0-M2`, with `common-bom
  25.104.0-M5` and `maven-common-bom 25.104.0-M7` imported).
- FR2, `pom.xml:38`: bump `<coredomain.version>17.104.4</coredomain.version>` →
  `<coredomain.version>25.104.0-M11</coredomain.version>`. **(a) — verified for real.** Originally
  recorded as "(c) blocked — Artifactory unreachable"; that was a certificate-trust misdiagnosis
  (`CN=<internal-root-ca>`, a self-signed internal CA, untrusted by a freshly-installed JDK 25), not network
  unreachability. Once imported into JDK 25's cacerts, `25.104.0-M11` resolved on the first try and the
  full reactor built and tested clean against it.
- FR4 (interface pins — `pcfdlrm.version` `pom.xml:39` `17.103.24`, `system.id-mapper.version`
  `pom.xml:40` `17.103.5`, `progression.version` `pom.xml:41` `17.0.297`): **(a) — and one pin needed to
  move further than any enforcer check alone would have shown.** `RequireLatestMojInterfaceRule` ran
  online (once the cert was trusted) and passed, finding nothing to bump — but that rule only checks
  metadata freshness, not Jakarta compatibility. Deploying to a real WildFly 40 container failed with a
  WELD CDI error (`SystemIdMapperClient` unsatisfied): `system.id-mapper.version` `17.103.5`'s CDI
  producer is `javax.enterprise`/`javax.inject`-annotated (decompiled to confirm), invisible to a
  Jakarta EE 11 container. **Bumped to `25.104.0-M11`** (jakarta-native, already cached); redeployed
  clean. `pcfdlrm.version`/`progression.version` checked for the same exposure and left unchanged — both
  are consumed only as `<classifier>raml</classifier>` generator inputs, not shipped CDI-bean jars, so
  the generated code's namespace comes from the (jakarta-native) generator, not the pin's age.
- FR19 (check missing core-domain fields before the coredomain bump): **(a) — checked against the real
  M11 jar, and the answer changed.** Originally run against the cached `25.104.0-M9` as a proxy: all 36
  `$ref` targets across `stagingdlrm-domain/**`, `stagingdlrm-datatypes-common/**`,
  `stagingdlrm-command/**` were enumerated; exactly three reach into core-domain (all into
  `core/common/definitions.json#/definitions/{date,date-time,uuid}`, byte-identical at every version
  checked), and `criminal-court-public-model` appeared to lose two schemas at M9
  (`defendantFineAccountNumber.json`, `deletedJudicialResults.json`). **Re-run against the real M11 jar:
  zero difference on either artefact** — both schemas are present again at M11; the apparent loss was an
  artefact of M9 being an intermediate milestone, not a property of the real target. Neither schema is
  `$ref`-ed by this repo either way, so the practical conclusion (proceed with the bump) was unaffected —
  but the "will shrink" framing this was feeding into FR10 does not hold at the actual target.
- Acceptance: FR2, FR4, FR19, AC1, AC2 (met — full reactor builds and tests green).

## T3 — `stagingdlrm-viewstore-persistence` + `stagingdlrm-domain-event` + `stagingdlrm-command-api` + `stagingdlrm-command-handler` + `stagingdlrm-event-listener` + `stagingdlrm-domain-aggregate`: FR8 + FR23 + FR24 — dead coordinates, descriptors, and the persistence-module decision
- FR23 (dead coordinates — a hard dependency-resolution failure, reproduced verbatim in the probe: seven
  `'dependencies.dependency.version' … is missing` errors across three POMs before anything compiles).
  **Corrected count: this is five dead coordinates in `stagingdlrm-viewstore-persistence`, not the
  requirement's four, plus two more elsewhere the requirement does not list at all — seven in total.**
  **(a) — verified, reproduced, and fixed in the probe.**
  - `…viewstore-persistence/pom.xml:18` — delete `uk.gov.justice.services:persistence-deltaspike`.
    Do **not** substitute `persistence-jpa` — see FR24.
  - `…viewstore-persistence/pom.xml:99–113` — delete all three `org.apache.deltaspike.*` test
    dependencies (`deltaspike-test-control-module-api`/`-impl` at `:99`/`:104`,
    `deltaspike-cdictrl-openejb` at `:109`). `deltaspike-cdictrl-api` is not present in this repo, so it
    is five dead coordinates in this module, not six.
  - `…viewstore-persistence/pom.xml:114–118` — delete `org.hibernate:hibernate-entitymanager`.
  - `…viewstore-persistence/src/test/resources/META-INF/apache-deltaspike_test-container.properties` —
    delete the file outright. Do not add a test `persistence.xml` in its place (that is the reference's
    move, for a module with entities to test — see FR24).
  - **`…domain-event/pom.xml:24–28`** (not in FR23's original list): `javax.json:javax.json-api` →
    `jakarta.json:jakarta.json-api`, `provided`, drop the `<version>` (BOM-managed `2.1.3`,
    `maven-common-bom 25.104.0-M7:220–223`).
  - **`…command-api/pom.xml:31–34`** (not in FR23's original list): `javax.annotation:javax.annotation-api`
    → `jakarta.annotation:jakarta.annotation-api`, drop the `<version>` (managed `3.0.0`, `…M7:195–198`).
  - Two more `javax.*` coordinates that resolve silently but are the wrong namespace and should move
    with the rest: `…command-api/pom.xml:35–38` and `…viewstore-persistence/pom.xml:26–29`,
    `javax.xml.bind:jaxb-api` → `jakarta.xml.bind:jakarta.xml.bind-api` (managed `4.0.2`, `…M7:245–248`)
    — the viewstore-persistence site is dropped altogether under this module's trim, below.
  - Three test-scope JSON-P provider declarations that also resolve silently against the wrong
    (`javax`) namespace: `…command-handler/pom.xml:58–62`, `…event-listener/pom.xml:60–64`,
    `…domain-aggregate/pom.xml:46–50` — `org.glassfish:javax.json` (test) → `org.eclipse.parsson:parsson`,
    `<version>` dropped. Not `org.glassfish:jakarta.json` — same OSGi/`ServiceLoader`-suppression reason
    as FR9.
  - Verification: post-edit, `grep -rn 'deltaspike\|hibernate-entitymanager' --include='pom.xml' .`
    returns only a stale comment at `…viewstore-persistence/pom.xml:28` (`<!-- Required by deltaspike -->`,
    delete it too), and the properties file is gone. AC14 met in the probe.
- FR24 (retain-or-delete `stagingdlrm-viewstore-persistence`): **(a) — decided: RETAIN, trim dependencies
  to nothing.** Reasoning: the module ships only `beans.xml` + `persistence.xml`, zero `.java` files,
  zero tests, but it is a real, consumed dependency of two deployed WARs
  (`stagingdlrm-event/stagingdlrm-event-listener/pom.xml:38–42`,
  `stagingdlrm-query/stagingdlrm-query-api/pom.xml:29–33`, both reaching `stagingdlrm-service.war` via
  `<classifier>classes</classifier>`), and its `persistence.xml` binds the persistence unit
  `stagingdlrm-persistence-unit` to the real, migrated JNDI data source `java:/DS.stagingdlrm`.
  Deletion would need removing the module from `stagingdlrm-viewstore/pom.xml`'s `<modules>`, removing
  both dependency declarations, and confirming WildFly 40 does not require a persistence unit for
  `EVENT_LISTENER`/`QUERY_API` — the last of which needs a WildFly 40 deploy this sandbox cannot do
  (FR22/T11). Deletion is unverifiable here; retention is verifiable here — that asymmetry decides it.
  **The trim:** delete the module's entire `<dependencies>` block (1 `provided` + 3 compile + 15 test,
  supporting zero Java files and zero tests), including the `javaee-api` at `:13–17` (FR7 site 7) and
  the `jaxb-api` at `:26–29`. A resources-only jar needs no dependencies. Explicitly do **not**
  reproduce the reference's `persistence-deltaspike` → `persistence-jpa` swap or its test
  `persistence.xml` — those exist to support entities and repositories this module does not have.
- FR8 (CDI/persistence descriptors): **(a) — verified.** All 10 `beans.xml` (`command-api`,
  `command-handler`, `domain-aggregate`, `domain-event`, `event-listener`, `event-processor`,
  `healthchecks`, `query-api`, `service`, `viewstore-persistence`, all at
  `src/main/resources/META-INF/beans.xml`) are byte-identical apart from attribute order and are
  replaced with one canonical Jakarta-namespace body (`https://jakarta.ee/xml/ns/jakartaee`,
  `beans_4_0.xsd`, `version="4.0"`, `cdi.api.version` `4.1.0` per `maven-common-bom 25.104.0-M7:32`).
  **`bean-discovery-mode="all"` is preserved explicitly on all 10** — losing it would flip CDI 4.0's
  default for an empty `beans.xml` to `annotated` and silently empty this repo's interceptor chains
  (BC-14's *Refuted* verdict in the parity checklist depends on this, in its own words: *"The upgrade
  story's own FR8 … is what keeps this repo unaffected"*). Verified post-rewrite:
  `grep -rl 'bean-discovery-mode="all"' --include='beans.xml' . | wc -l` → **10**;
  `grep -rl 'xmlns.jcp.org' --include='beans.xml' .` → **empty**. AC6 met.
  `persistence.xml` (`stagingdlrm-viewstore-persistence/src/main/resources/META-INF/persistence.xml`) —
  the single file, JPA 1.0 → 3.2 namespace (`persistence-api.version` `3.2.0`,
  `maven-common-bom 25.104.0-M7:43`; `https://jakarta.ee/xml/ns/persistence`,
  `persistence_3_2.xsd`, `version="3.2"`), unit body unchanged (same unit name
  `stagingdlrm-persistence-unit`, same `org.hibernate.jpa.HibernatePersistenceProvider` FQCN — unchanged
  in Hibernate 6 — same `<jta-data-source>java:/DS.stagingdlrm</jta-data-source>`, still no `<class>`
  entries). The brief's "`@Inject EntityManager` → `@PersistenceContext(unitName)`" item is confirmed
  N/A: zero `.java` files in `stagingdlrm-viewstore`, zero `javax.persistence` imports anywhere, zero
  `@Entity`.
- Acceptance: FR8, FR23 (revised — 7 coordinates, not 4), FR24, AC6, AC14, AC15.

## T4 — repo-wide `*.java` + all `pom.xml` + `stagingdlrm-command-api`/`stagingdlrm-event-processor` generator blocks: FR5 + FR6 + FR7 + FR9 + FR10 — the jakarta sweep
- FR5 (migrate only the five Jakarta namespaces): **(a) — scripted rewrite executed and counted.**
  **Corrected inventory for this branch (`b23ea10`): 76 `javax` import lines across 34 files, not the
  requirements' "92 across 40 files" measured at `main`.** By package: `javax.json.*` 37 →
  `jakarta.json.*` (func-app main+test, command-api, command-handler, event-processor main+test,
  integration-test); `javax.ws.rs.core.*` 17 + `javax.ws.rs.client.*` 5 (22 total) →
  `jakarta.ws.rs.*` (func-app main+test, integration-test); `javax.inject.Inject` 11 →
  `jakarta.inject.Inject` (command-api, command-handler ×2, event-processor ×6);
  `javax.annotation.PostConstruct` 4 → `jakarta.annotation.PostConstruct` (`EventGridService:9`, three
  `…Counter` classes at `:3` each); `javax.enterprise.inject.Specializes` 1 →
  `jakarta.enterprise.inject.Specializes` (`StagingdlrmIgnoredHealthcheckNamesProvider:11`).
  Mechanism: five anchored, package-explicit `perl` rules (never a blanket `javax.` → `jakarta.`
  replace) — matching `^import (static )?javax\.json\.`, `javax\.ws\.`, `javax\.inject\.`,
  `javax\.annotation\.PostConstruct;` (whole line, not a prefix), `javax\.enterprise\.`. Result: 75 of
  76 lines moved; the one survivor is `javax.net.ssl.SSLContext` — see FR6. Also confirmed empty: any
  fully-qualified `javax.` reference outside an import line, and any `javax` string in any
  `*.xml`/`*.yaml`/`*.raml`/`*.json`/`*.properties` resource. **FR5 must land in the same commit as
  FR7** — renaming imports without the `javaee-api` swap leaves the jakarta APIs off the classpath;
  swapping the coordinate without the rename leaves the javax APIs off it.
  **Behaviour must not move**: `StagingDlrmCommandHelper` (11 import lines; builds its payload through
  `uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder`, `StagingDlrmCommandHelper.java:5–8`,
  not `Json.createObjectBuilder` directly) and `EventGridMonitorHelper` (2 import lines,
  `javax.json.Json`/`javax.json.JsonWriter`) carry no control-flow change from the rename — none of
  `dlrm-flow-reference.md` §2.6's four outcome-write paths can change from a rename alone.
- FR6 (do not rename JDK `javax.*`): **(a) — verified.** The complete JDK-namespace `javax.*` surface in
  this repo is one line: `stagingdlrm-azure-functions/src/main/java/uk/gov/moj/cpp/stagingdlrm/azure/rest/StagingDlrmCommandHelper.java:23`,
  `import javax.net.ssl.SSLContext;` — sitting inside the import block (lines 18–29) between
  `javax.json.JsonWriter` (`:22`) and `javax.ws.rs.client.Client` (`:24`), exactly where a
  sort-and-replace would take it out by accident. It backs the trust-all TLS client
  (`org.apache.http.ssl.SSLContexts`/`TrustAllStrategy`, imported at `:32–34`). `javax.crypto`,
  `javax.xml.parsers`, `javax.naming` were checked and do not appear anywhere in the repo. AC4's grep
  (`grep -rE '^import (static )?javax\.' --include='*.java' . | grep -v '/target/'`) must return
  **exactly one line** — this one — not zero; write the check as "returns only JDK packages," never as
  "returns nothing."
- FR7 (swap `javaee-api` for the Jakarta equivalent everywhere, including plugin `<dependencies>`):
  **(a) — verified; 12 sites, not the requirement's 11.** `javax:javaee-api` is *still managed* at
  `8.0.1` on the J25 chain (`common-bom 25.104.0-M5:534–538`, "pinned for Activiti 5.x compatibility"),
  so normal-dependency sites resolve silently and only fail once FR5's renamed imports have nothing to
  compile against; plugin-internal sites take `${javaee-api.version}`, which the parent chain itself
  moves `8.0.1` → `11.0.0`, a version that does not exist under the `javax` groupId — so they fail late,
  at `generate-sources`, in a message that never names `javaee-api`. Target coordinate:
  `jakarta.platform:jakarta.jakartaee-api`, managed at `11.0.0` (`maven-common-bom 25.104.0-M7:178–182`).
  Normal dependencies drop their `<version>` entirely; plugin `<dependencies>` must keep an explicit
  `<version>${javaee-api.version}</version>` (plugin dependency resolution does not consult
  `<dependencyManagement>`) — matching the M10 parent's own idiom for its generator plugin blocks.
  All 12 sites:
  1. `stagingdlrm-command/stagingdlrm-command-api/pom.xml:20–24` — `provided`, no version — swap groupId+artifactId.
  2. `stagingdlrm-command/stagingdlrm-command-handler/pom.xml:19–23` — swap.
  3. `stagingdlrm-event/stagingdlrm-event-listener/pom.xml:18–22` — swap.
  4. `stagingdlrm-event/stagingdlrm-event-processor/pom.xml:19–23` — swap.
  5. `stagingdlrm-query/stagingdlrm-query-api/pom.xml:18–22` — swap.
  6. `stagingdlrm-healthchecks/pom.xml:13–17` — swap.
  7. `stagingdlrm-viewstore/stagingdlrm-viewstore-persistence/pom.xml:13–17` — swap, but see T3/FR24: this site is deleted entirely under the module's trim.
  8. `stagingdlrm-azure-functions/pom.xml:44–48` (compile, explicit `<version>8.0</version>` at `:47`) — **drop entirely**, see T5/FR15.
  9. `stagingdlrm-testharness/pom.xml:27–31` (`<dependencyManagement>`, explicit `8.0` at `:30`) — **delete the whole entry**, not just its version — a `<dependencyManagement>` entry cannot drop its version the way a dependency can (the probe failed with `'dependencies.dependency.version' … is missing @ line 47` when this was tried).
  10. `stagingdlrm-testharness/pom.xml:50–53` — swap, stays unversioned, picks up the BOM version now that site 9 is gone.
  11. `stagingdlrm-event/stagingdlrm-event-processor/pom.xml:92–96` — `messaging-client-generator-plugin` `<dependencies>` — swap, keep `${javaee-api.version}`. **Line-number correction: the requirement cites `pom.xml:105`; on `b23ea10` it is `92–96`.**
  12. `stagingdlrm-event/stagingdlrm-event-processor/pom.xml:104–108` — `rest-client-generator-plugin` `<dependencies>` — swap, keep `${javaee-api.version}`. **Line-number correction: the requirement cites `pom.xml:117`; on `b23ea10` it is `104–108`.** This is the same edit as FR9's fix 2, below.
  Verification: post-migration, `grep -rn 'javax' --include='pom.xml' .` → no output, full reactor
  constructs cleanly on the M10 chain under JDK 25. AC5 met.
- FR9 (the two recorded generator fixes, plus a third real one found by testing): **(a) — both recorded
  fixes confirmed necessary by reading the M10 parent's profile-scoped plugin blocks, and confirmed to
  actually work: code generation genuinely runs.** `stagingdlrm-event-processor` was originally recorded
  as unbuildable here (`progression-query-api:jar:raml:17.0.297` "unresolvable" — a gap inherited from
  the parity story); that was the same certificate-trust misdiagnosis as everything else, and both it
  and `pcfdlrm-command-api:jar:raml` resolve fine. **Fix 1 — parsson in
  `messaging-client-generator-plugin`'s plugin deps, two sites**
  (`stagingdlrm-command/stagingdlrm-command-api/pom.xml:85–102`,
  `stagingdlrm-event/stagingdlrm-event-processor/pom.xml:76–99`) — confirmed by real generated output:
  `RemoteEventProcessor2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand.java` landed in
  `target/generated-sources`. **Fix 2 — the jakartaee-api swap in `rest-client-generator-plugin`**
  (`stagingdlrm-event-processor/pom.xml:104–108`, same edit as FR7 site 12) — confirmed the same way:
  `RemoteEventProcessor2PcfdlrmCommandApi.java` and `RemoteEventProcessor2ProgressionQueryApi.java` both
  generated. **Fix 3 (new, found only by running real code generation) — a plugin-scoped JAXB compat
  dependency.** `messaging-client-generator-plugin`'s `generate-classpath-message-clients` goal failed
  on `event-processor` specifically (not on `command-api`/`command-handler`'s simpler RAML-only
  invocations of the same plugin) with `A required class was missing ... javax/xml/bind/SchemaOutputResolver`.
  Decompiled `org.raml:raml-parser:0.8.18` (a transitive via `generator-raml-parser`): its
  `JaxbTagResolver` class hardcodes a call to
  `javax.xml.bind.JAXBContext.generateSchema(javax.xml.bind.SchemaOutputResolver)` using the literal old
  JAXB package — a 2016-era library nobody has updated for Jakarta EE. Fixed by adding
  `javax.xml.bind:jaxb-api:2.3.1` to `stagingdlrm-event-processor/pom.xml`'s
  `messaging-client-generator-plugin` `<dependencies>` block only (confirmed to contain
  `SchemaOutputResolver.class`) — a plugin-execution-scoped compat shim, not a module dependency.
- FR10 (generated-artefact inventory must not shrink): **(a) — confirmed by the full green build, and it
  does not shrink after all.** There is still no parity-story-derived inventory assertion to keep
  passing (the parity checklist's BC-21 rows are all withdrawn-or-never-instrumented, ⚪⚪🟡🟡). The
  BC-15 shrink concern raised against the `25.104.0-M9` proxy (two `criminal-court-public-model` schemas
  missing) does **not** hold at the real `25.104.0-M11` target — re-run against the actual M11 jar, the
  entry-list diff is empty (see T2/FR19). Design guidance for any future inventory check remains sound
  regardless: scope it to this repo's own ~33 schemas under
  `stagingdlrm-domain-*/src/main/resources/json/schema/**` and `stagingdlrm-datatypes-common`'s, never
  to the `CLASSPATH`-wide set, since a future core-domain bump genuinely could shrink it even though this
  one did not.
- Acceptance: FR5 (revised — 76/34, not 92/40), FR6, FR7 (revised — 12 sites, not 11; corrected line
  numbers), FR9 (met, including the newly-found Fix 3), FR10 (met — no assertion to protect, and no
  shrink at the real target), AC4, AC5.

## T5 — `stagingdlrm-azure-functions`: FR15 + FR16 + FR17 — the Function App to Java 25
- FR15 (move to Java 25): **(b) mechanical only for the pom edits — applied and the module's POM reads
  cleanly on M10 under JDK 25 in the probe, but compilation was never reached in this sandbox** (the
  reactor stops at the root module's missing internal artefacts, T11). **Seven items, not the ADR's
  six** — the design found a hard compile-time coupling nobody had recorded:
  1. `pom.xml:14–15` — `maven.compiler.source`/`target` 17→25 is a **no-op as written**: these
     properties have no effect on this build (verified via
     `mvn -o -pl stagingdlrm-azure-functions help:effective-pom`, which shows `maven-compiler-plugin`
     configured from `${compiler.release}`/`${compiler.source}`/`${compiler.target}`, not
     `maven.compiler.*`). **Delete both** rather than "bump" them — they mislead. The same dead pair
     exists at `stagingdlrm-testharness/pom.xml:13–14`; delete there too.
  2. `pom.xml:190` — plugin `<runtime><javaVersion>` 17→25. **Build-inert**: `PackageMojo` (the only
     goal this module binds, `pom.xml:207–214`) never reads `<runtime><javaVersion>`. **Parameterise
     it**: `<javaVersion>${functionapp.java.version}</javaVersion>` with
     `<functionapp.java.version>25</functionapp.java.version>` in `<properties>`, so FR16's fallback is
     a one-token flip.
  3. `azure-functions-maven-plugin` `1.24.0` → a version accepting `javaVersion 25`: **not required for
     the build** — decompiled, both `1.24.0` and the latest `1.42.0` are Java-8 bytecode (major 52) and
     `package` never validates `<javaVersion>`; the deploy-side validation lives in
     `azure-toolkit-appservice-lib:0.56.0`'s `FunctionAppWindowsRuntime`, which loads its runtime table
     dynamically from the live Azure stack API, not from the plugin's static seed (which only lists
     Java 17/11/8 for Windows). Bump `1.24.0` → `1.42.0` anyway for the newer runtime model — **as a
     literal version string in both declaration sites, not a property.** First attempt collapsed both
     onto `${azure.functions.maven.plugin.version}`; tested for real, that broke
     `maven-enforcer-plugin`'s `RequirePluginVersions` rule (`-X` debug confirmed the property resolved
     to `1.42.0` correctly everywhere, and the rule still reported "missing valid version" — it does not
     reliably resolve a property-based version for a plugin only one leaf module declares). Reverted to
     a literal string in both places.
  4. `azure-functions-java-library` `3.1.0` → `3.3.0` via the existing `:18` property (already used at
     `:27`).
  5. `pom.xml:44–48` — `javax:javaee-api:8.0` — **drop entirely**, do not migrate to
     `jakarta.jakartaee-api`. After migration, `jakarta.ws.rs-api` arrives transitively with
     `resteasy-client` (T5/FR17) and `jakarta.json-api` arrives transitively with parsson
     (`parsson-1.1.7.pom` declares `jakarta.json:jakarta.json-api` as its first dependency); pulling in
     the full `jakarta.jakartaee-api:11.0.0` umbrella (CDI, EJB, JPA, JMS, Servlet) onto a classpath
     with no container to implement any of it is exactly how you get a `NoClassDefFoundError` JAR.
  6. `pom.xml:49–53` — `org.glassfish:javax.json:1.0.2` → `org.eclipse.parsson:parsson`, version
     dropped (BOM-managed `1.1.7`). Not `org.glassfish:jakarta.json` — same OSGi/`ServiceLoader`
     suppression reason as T4/FR9; in this standalone JAR there is no container to paper over it.
  7. **(new, unlisted in the requirements or ADR decision 4) `pom.xml:74–79` — delete the pinned
     `<version>17.3.1</version>`, `<scope>compile</scope>` declaration of
     `uk.gov.justice.utils:utilities-core`.** Maven takes the *first* of the module's two declarations
     of this coordinate (the unversioned one at `:136–138` is the second), so today the func-app
     compiles against `utilities-core:17.3.1` — decompiled and confirmed to expose
     `javax.json.JsonObjectBuilder createObjectBuilder(javax.json.JsonObject)` (256 `javax/json`
     bytecode references, 0 `jakarta/json`), against the BOM-managed `25.104.0-M12`'s
     `jakarta.json.JsonObjectBuilder createObjectBuilder()` (0 `javax/json`, 378 `jakarta/json`).
     `StagingDlrmCommandHelper` (`:5–8`) and both test classes call this helper directly, so **FR5's
     rename of `StagingDlrmCommandHelper`'s imports to `jakarta.json.*` cannot compile while this pin
     stands** — deleting it is what actually clears the "highest-uncertainty item in the epic" the ADR
     misidentified as item 3. (Companion pin `uk.gov.justice.services:test-utils-common:2.4.1`,
     `pom.xml:80–85`, is confirmed namespace-neutral — zero `javax.json`/`jakarta.json` references.
     First attempt recommended bumping it to the BOM-managed `25.104.0-M5` "for consistency"; tested for
     real, that broke test compilation — M5 removed the `ReflectionUtils`/`setField` class several tests
     use. Kept at `2.4.1`.)
- FR16 (explicit fallback): **(a) for the mechanism — confirmed unneeded, since FR15 now builds and
  tests green on real JDK 25. (b) remains for the trigger decision itself — still needs Azure
  credentials this sandbox does not have.** **The fallback is NOT being taken.** Both things ADR
  decision 4 feared would block Java 25 (plugin validation, static runtime table) turned out not to;
  the real blocker (item 7 above) was a duplicate-dependency deletion, not a JDK-version problem, and is
  now fixed and verified. Trigger condition, made checkable rather than left to judgement: take the
  fallback if and only if, with plugin `1.42.0`, `az functionapp list-runtimes --os windows | grep -i 'java'`
  does not list Java 25, **or** the `wildfly40`-track deploy of `fa-ste-ccp0101-dlrm` fails on the
  runtime version — both still unverifiable here (this sandbox's `az` session is authenticated for ACR,
  not checked against Functions runtimes). If triggered, the two-line mechanism is: in
  `stagingdlrm-azure-functions/pom.xml`, set `<functionapp.java.version>17</functionapp.java.version>`
  and add local
  `<compiler.release>17</compiler.release>`/`<compiler.source>17</compiler.source>`/`<compiler.target>17</compiler.target>`
  properties (the *real* levers, per item 1 above) — everything else (jakarta rename, parsson,
  `javaee-api` drop, RESTEasy 7, `utilities-core` un-pin) lands regardless, being JDK-independent
  correctness fixes.
- FR17 (BC-12's fleet fix must not be applied to the Function App): **(a) — verified, the carve-out
  is structural, and the module builds/tests green with all four artefacts at 7.0.0.Final.** The
  artefacts, all
  `4.3.0.Final`, no `<scope>` (so compile, the default): `resteasy-client` `pom.xml:86–90`,
  `resteasy-jaxb-provider` `:91–95`, `resteasy-jackson2-provider` `:96–100`,
  `resteasy-multipart-provider` `:101–105`. **Must be bumped to `7.0.0.Final`** (not left at
  `4.3.0.Final`) — RESTEasy 4 is the `javax.ws.rs` line and cannot satisfy `StagingDlrmCommandHelper`'s
  and `TimerTriggerJavaTest`'s/`StagingDlrmCommandHelperTest`'s post-FR5 `jakarta.ws.rs.*` imports; 7.0.0.Final
  is what `maven-common-bom 25.104.0-M7` manages for `resteasy.version`/`resteasy-client.version`/
  `resteasy-multipart-provider.version`, and all four artefacts are confirmed present on Central at that
  version. **Keep the explicit `<version>7.0.0.Final</version>` on each** rather than dropping to BOM
  management — `maven-common-bom` M7 manages only `resteasy-client`/`-core`/`-core-spi`/
  `-jackson-provider` (not `-jackson2-provider`)/`-multipart-provider`/`-servlet-initializer`, so two of
  this module's four have no managed version, and the BOM's managed `resteasy-multipart-provider` entry
  carries five WAR-oriented exclusions this standalone JAR must not inherit. **The carve-out is enforced
  structurally, not just by memory**: M10's `packagingExcludes` (the mechanism that would strip these
  four) is configured on `maven-war-plugin`; `stagingdlrm-azure-functions` is
  `<packaging>jar</packaging>` (`pom.xml:11`) and never invokes `maven-war-plugin`, so the fleet-wide
  BC-12 fix cannot touch this module by any automated sweep — only a deliberate hand-edit of these four
  `<dependency>` elements to add `<scope>provided</scope>` could break it. AC10: state this structural
  reason explicitly in the PR description, not just the fact of the exclusion, so a reviewer can verify
  the claim rather than trust it.
- Acceptance: FR15 (met — 7 items, not the ADR's 6; real blocker was `utilities-core`, not the plugin;
  builds and tests green on real JDK 25), FR16 (fallback designed, not needed), FR17 (met, verified
  green), AC9 (deploy-side confirmation still needs Azure credentials), AC10.

## T6 — `azure-pipelines.yaml` + `stagingdlrm-viewstore-liquibase`: FR11 + FR18 — pipeline track and the liquibase defect
- FR11 (move the build to `ubuntu-j25`/`wildfly40`): **(a) — verified, target shape read directly off
  the reference's own settled `azure-pipelines.yaml` on `team/25.104.x`, not reconstructed.** Three
  edits: `azure-pipelines.yaml:24` `ref: 'main'` → `ref: 'wildfly40'`; `azure-pipelines.yaml:29`
  `identifier -equals centos8-j17` → `identifier -equals ubuntu-j25` (**take `ubuntu-j25`, not
  `ubuntu-j25-postgres`** — the reference's own upgrade commit `122a5a8f` set the latter first and a
  follow-up commit `c834ff3a` reverted it, confirmed with `git log -L29,29:azure-pipelines.yaml`); add
  `aksDeployBranch: 'wildfly40'` as a new fourth parameter after `azure-pipelines.yaml:50` (this repo's
  yaml currently passes only `repo`, `sonarQubeType`, `serviceName`, `itTestFolder`). **Do not add
  `LANG`/`LC_ALL: 'en_GB.UTF-8'`** — checked with `git log -L35,36:azure-pipelines.yaml` on the
  reference, both predate its upgrade entirely (present since its initial commit and CI bootstrap);
  copying them here would be scope creep on a guess, recorded instead as a low-confidence watch item
  (this repo's only locale-sensitive code is `MessageFormat` in log messages). Also flagged, not fixed:
  `azure-pipelines.yaml:32`'s `sonarqubeProject: "uk.gov.moj.cpp.staging.dlrm:staging-dlrm-parent"`
  does not match the reactor's actual coordinates `uk.gov.moj.cpp.stagingdlrm:stagingdlrm-parent`
  (`pom.xml:11–12`) — pre-existing, unrelated to the upgrade, not fixed by this story (raised as a
  follow-up).
- FR18 (delete `liquibase.hub.mode: off`): **(a) both halves.** Deletion verified as before; AC8's
  second clause — restated below, since "Liquibase 5 accepts the file" was never this chain's real
  premise — has since been verified for real too: the full IT run (T11/FR22) executed this exact jar's
  Liquibase 4.10.0 against a real Postgres container and it completed cleanly. File:
  `stagingdlrm-viewstore/stagingdlrm-viewstore-liquibase/src/main/resources/liquibase.properties`, three
  lines. Delete line 2 (`liquibase.hub.mode: off`); keep `liquibase.headless: true`. **Correction: this
  chain ships Liquibase `4.10.0`, not 5.0.3** — `liquibase.version` is `4.10.0` in both
  `parent-pom 17.10.12:87` and `parent-pom 25.104.0-M2:87` (unchanged by the upgrade), confirmed with
  `mvn -o help:evaluate -Dexpression=liquibase.version`; `docker/scripts/liquibase.sh:35`'s
  `java -jar stagingdlrm-viewstore-liquibase.jar … update` runs this embedded 4.10.0, built by
  `parent-pom`'s `liquibase-jar` profile. On 4.10.0 both `liquibase.hub.mode` and `liquibase.headless`
  are valid keys — Hub existed and `headless` is a supported global property — **so this repo is not,
  on the M10 chain, actually exposed to the failure FR18 describes.** Delete the key anyway: Hub is
  sunset, the property is an inert no-op on 4.10.0 and a hard failure on any 5.x, and BC-07's residual
  is precisely "a per-context sweep of copied `liquibase.properties`," fixed in 15 framework repos and
  never swept here. **AC8 cannot be satisfied as written** ("Liquibase 5 accepts the file" — this chain
  does not run Liquibase 5); restate it as: `liquibase.properties` contains no `liquibase.hub.mode`, and
  the effective `liquibase.version` on the branch (`4.10.0`) is recorded. The changelog itself
  (`liquibase/stagingdlrm.xml`, 7 lines, zero `<changeSet>` elements) is irrelevant to this — the key is
  rejected at config-parse time, before the changelog is opened — and
  `docker/Dockerfile_stagingdlrm-service:21`/`docker/scripts/liquibase.sh:35` (`set -e`) is what makes
  this a genuine deploy blocker rather than a warning. Not done here, deliberately: deleting the whole
  `stagingdlrm-viewstore-liquibase` module (the parity checklist's alternative) — that is a coordinated
  change across the Docker image and init script and belongs with the empty-read-side clean-up, not
  inside this upgrade (same reasoning as T3/FR24).
- Acceptance: FR11, FR18 (revised — AC8 restated; chain ships Liquibase 4.10.0 not 5.0.3), AC7, AC8.

## T7 — root `pom.xml`: FR12 — jacoco, no change, verify only
- FR12 states the parent pins jacoco at 0.8.12 and every migrated fleet context needed a local
  override. **The premise is wrong for this chain — no override needed. (a) — verified.** Both
  `parent-pom 25.104.0-M2:134` and `platform-libraries-parent-pom 25.104.0-M11:41` already pin
  **0.8.14**, and `parent-pom` binds `jacoco-maven-plugin` at `${plugins.jacoco.version}` (`:521–523`).
  The probe corroborates this empirically — after the T2 parent bump, the build's first failure was
  resolving the `0.8.14` plugin stack itself (all public artefacts, fetched cleanly from Central: the
  `jacoco` 0.8.14 stack, `asm` 9.9, `file-management` 3.2.0, `commons-io` 2.19.0).
  **Design: add nothing.** Verify with
  `JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn -o -N help:evaluate -Dexpression=plugins.jacoco.version -DforceStdout`
  → must print `0.8.14` or later. Only add a local `<plugins.jacoco.version>` property if it does not —
  a same-value local override is a future maintenance trap that silently pins this repo behind the
  platform's next jacoco bump.
- Acceptance: FR12 (revised — no override needed), AC1 (no change to make).

## T8 — `stagingdlrm-event-listener` WAR + `stagingdlrm-service`: FR13 — jboss-deployment-structure.xml / BC-12, settled empirically, no change
- **(a) — verified from a built WAR, not reasoned about; settled.** This was flagged as possibly the
  repo's biggest deploy risk. Four findings dismantle it in order:
  1. `stagingdlrm-event/stagingdlrm-event-listener/src/main/webapp/WEB-INF/jboss-deployment-structure.xml`
     does exclude the `jaxrs` subsystem, on the face of it exactly the flagged shape.
  2. **It never reaches the artefact.** `service-parent-pom`'s `maven-war-plugin` `webResources`
     configuration (M10 `:117–127`, identical in 17.104.1) overlays a copy of
     `jboss-deployment-structure.xml` from `service-common-resources` (via
     `maven-remote-resources-plugin`) into `WEB-INF`, and the framework's copy wins. Confirmed by
     unzipping a J17-built WAR already in the working tree
     (`stagingdlrm-event-listener-17.104.26-DLRMJ25-SNAPSHOT.war`): the deployed
     `WEB-INF/jboss-deployment-structure.xml` is the framework's bare `<deployment/>`
     (`urn:jboss:deployment-structure:1.2`), byte-identical to
     `service-common-resources-17.10.1.jar`'s own copy — **not** the module's jaxrs-excluding file. It
     has been dead configuration for as long as the framework has injected its own.
  3. **The WAR it lives in is not deployed either.** `docker/Dockerfile_stagingdlrm-service:16` `ADD`s
     only `stagingdlrm-service-${version}.war`; `stagingdlrm-service/pom.xml:14–43` consumes the five
     component modules by `<classifier>classes</classifier>` (their classes jars), not their WARs. Of
     `dlrm-flow-reference.md` §3.1's five WAR-packaged modules, only `stagingdlrm-service.war` is an
     actual deployment, and its own `WEB-INF/jboss-deployment-structure.xml` is also the framework's
     bare `<deployment/>`.
  4. **So BC-12's interaction cannot arise in this repo.** The deployed WAR keeps `jaxrs` enabled, so
     M10's `packagingExcludes` (`service-parent-pom-25.104.0-M10.pom:100`) does exactly what it is
     meant to — removes duplicates the container supplies. Verified: today's
     `stagingdlrm-service.war` bundles `resteasy-multipart-provider-3.15.1.Final.jar`,
     `javax.json-1.1.4.jar`, `javax.json-api-1.0.jar` in `WEB-INF/lib`; on M10 the first is stripped and
     the other two replaced transitively by jakarta equivalents.
- **Design: amend nothing.** Add no `jboss-deployment-structure.xml` to `command-api`, `command-handler`,
  `event-processor`, or `query-api` — none has one, all four already get the framework's `<deployment/>`.
  **Do not copy the reference's root-level file** — `cpp-context-prosecution-casefile`'s commit
  `122a5a8f` added one at repo root that is byte-for-byte the same `<deployment/>` document
  `service-common-resources` already ships, referenced by nothing, and sits where no
  `maven-war-plugin` will ever look — it is vestigial, not a pattern to follow. **Optional, genuinely
  optional tidy-up**: delete the dead `stagingdlrm-event-listener` file (it is inert, and the single
  most alarming-looking file in the repo with respect to BC-12) — leave it for the empty-read-side
  clean-up (T3/FR24's follow-up) if the diff is already large.
- Acceptance: FR13, AC7.

## T9 — `docker/Dockerfile_stagingdlrm-service` + `azure-pipelines.yaml`: FR14 — container image source, settled, no content change
- **(a) — verified; settled, and the requirement's own premise ("no Dockerfile at repo root") is
  wrong.** `find . -iname 'Dockerfile*' -not -path '*/target/*'` →
  `./docker/Dockerfile_stagingdlrm-service`. This repo ships a Dockerfile, at `docker/`, the same
  pattern as the reference (`docker/Dockerfile_prosecutioncasefile-service`) — not the
  no-Dockerfile-at-all pattern of `support`/`system-id-mapper`/`notification`. Read in full (34 lines):
  the base image is fully parameterised (`ARG baseImageUri`/`ARG baseImageTag`, `:1–3`; `wildfly40`
  pipeline track supplies it); there is no `yum`/`apt-get`/`dnf`/any package-installing `RUN` — the
  whole file is version-parameterised `ADD`s of seven Maven artefacts plus one `COPY` and some
  `chown`/`chmod`/`mkdir`. Nothing distro-specific to strip.
  **Design: no change to `docker/Dockerfile_stagingdlrm-service`.**
  **The one real risk is a path risk, not a content risk, and it cannot be settled from this repo.**
  `context-validation.yaml`'s image step's `dockerfilePath` parameter defaults to `'Dockerfile'`; this
  repo's `azure-pipelines.yaml` (`:45–50`) passes no `dockerfilePath` override at all. Since the actual
  file is at `docker/Dockerfile_stagingdlrm-service`, either the `wildfly40`-branch template derives the
  path from `serviceName: 'stagingdlrm'` (plausible — filename pattern is
  `Dockerfile_${serviceName}-service`) or the image step has never worked for this repo. **Which of the
  two is true cannot be determined from this repo** — `context-validation.yaml` lives in
  `hmcts/cpp-azure-devops-templates` on its `wildfly40` branch. Read that branch before the merge build
  (github.com is reachable, so this is checkable in advance); if the template does not derive it, pass
  `dockerfilePath: 'docker/Dockerfile_stagingdlrm-service'` explicitly. This is the concrete reason
  T11/FR21's follow-up PR should be budgeted for — a first-time image-build failure here is a
  pipeline-wiring problem, not a Dockerfile problem.
- Acceptance: FR14 (revised — Dockerfile exists at `docker/`, needs no content change), AC7.

## T10 — `stagingdlrm-domain-transformations/stagingdlrm-domain-transformation-anonymise`: FR20 — anonymise decision, CLOSED, no code touched
- **(a) — verified; ADR decision 6 CLOSED: retain the module, migrate nothing inside it, raise a
  follow-up.** Four checks, in order:
  1. **Resolution: it resolves.** `stream-transformation-tool-anonymise:7.0.0`,
     `stream-transformation-tool-api:7.0.0`, `stream-transformation-tool:7.0.0` are all in the local
     cache; the version comes entirely from this repo's own `pom.xml:37`/`:62–66`
     `<dependencyManagement>`, not from any platform BOM — `grep stream-transformation` across the
     whole J25 parent chain returns nothing on either the 17 or 25 line. **This refutes the "mandated"
     reading** (the ADR's hypothesis that the framework dropped `stream-transformation-*` support) —
     nothing in the chain ever managed it, so nothing in the chain can have dropped it.
  2. **But the library is javax-bound.** Decompiled `stream-transformation-tool-anonymise-7.0.0.jar`
     (dated 28 May 2020, major version 52 / Java 8): `EventAnonymiserTransformation` and
     `EventAnonymiserService` both reference `javax.json.{Json,JsonObject,JsonObjectBuilder,JsonArray,
     JsonArrayBuilder,JsonString,JsonValue}`. `stream-transformation-tool-api-7.0.0.jar` (the
     `@Transformation` annotation) has zero `javax.*` references. No jakarta build of
     `stream-transformation-tool` exists in the cache or on either BOM line.
  3. **Is it deployed? No.** `stagingdlrm-domain-transformation-anonymise` is not a dependency of
     `stagingdlrm-service` (`stagingdlrm-service/pom.xml:13–76` lists nine other modules, not this one)
     and `docker/Dockerfile_stagingdlrm-service` never mentions it. It runs out of container, via
     `run-transformation.sh:21–29`, which launches `event-tool-7.0.0-swarm.jar` (a WildFly Swarm
     uber-jar downloaded at run time) against `src/test/resources/standalone-ds.xml` — it never touches
     WildFly 40 and never shares a classpath with the migrated modules.
  4. **Does it still compile at release 25? Yes.** Its entire source is 8 lines — one class extending
     `EventAnonymiserTransformation`, annotated `@Transformation`, overriding nothing, importing no
     `javax` type itself. `--release 25` restricts only the JDK API surface; javac reads major-52
     classfiles happily. Its one transitive, `org.everit.json.schema:1.6.0` (`provided`), is
     BOM-managed on the 25 chain and cached.
- **Decision: RETAIN, change nothing inside the module.** No jakarta rename (its one file has no
  `javax` imports), no `stream-transformation-tool` version bump — leave `pom.xml:37`'s
  `stream-transformation-tool-api.version` at `7.0.0` and the `<dependencyManagement>` entry at
  `:62–66` untouched. Applying the ADR's "retain and migrate" default literally here would be wrong —
  there is nothing in this one file to migrate, and bumping the dependency to a jakarta build that does
  not exist would break it against the javax-era `event-tool-7.0.0-swarm.jar` it is designed to load
  inside.
- **Raise as a follow-up, not this story's work**: the anonymisation capability is latently unusable on
  a Jakarta EE 11 estate — `EventAnonymiserTransformation` links `javax.json` and its WildFly Swarm host
  is an EOL runtime with no JDK-25 story. Ask PEG-3296 for a Jakarta build of
  `stream-transformation-tool`, and whether the fleet's other removals of this module
  (`defence`/`resulting`/`results`) were directed or, per check 1, merely incidental dead-weight
  clean-up while those diffs were already large.
- Acceptance: FR20, AC11.

## T11 — Full verification: FR2's coredomain M11 resolution + FR3 + FR21 + FR22 (AC2, AC9, AC12, AC13)

**Rewritten in full.** This task was originally titled "Environment-blocked verification" and recorded
everything below as "(c) blocked — needs Artifactory access and a WildFly 40 image". That diagnosis was
wrong: the "unreachable" Artifactory was actually an untrusted self-signed internal CA certificate
(`CN=<internal-root-ca>`) in a freshly-installed JDK 25's cacerts — fixed with one `keytool -importcert`
command — and the WildFly 40 image was one `az acr login` + `docker compose build` away. Once both were
done, in this same sandbox, on the same day, everything below is genuinely verified, except the one
item that was never environmental in the first place (AC12).

**(a) done and verified for real, not against a substitute:**
- **AC2 — `mvn clean install` on JDK 25 for the full reactor: met.** `JAVA_HOME=<jdk25> mvn clean
  install` (online, real `service-parent-pom:25.104.0-M10` / `coredomain:25.104.0-M11`) →
  `BUILD SUCCESS` across all 26 modules, every unit test passing.
- **FR3 — the parity gate, green on real Java 25: met.** `AccessControlTest`: `Tests run: 4, Failures: 0`.
  Still worth remembering while reading this green result: it is 4 assertions over 2 Drools rules in one
  WAR's access control — it does not, by itself, cover the Function App, the schema catalogue, the
  JSON-P provider, or the RESTEasy packaging. What actually covers most of that ground here is the full
  green build and full green IT run alongside it, not this one test class.
- **Two further internal artefacts** that originally looked unresolvable —
  `uk.gov.moj.cpp.common:service-common-resources:jar:25.104.0-M2` and
  `uk.gov.justice.framework-api:framework-api-validator:jar:25.104.0-M12` — **both resolve fine** once
  the cert is trusted.
- **A third real defect, found only by running the actual build** (T4/FR9): `event-processor`'s
  `messaging-client-generator-plugin` execution failed on a 2016-era `raml-parser` library's hardcoded
  old-JAXB reference; fixed with a plugin-scoped `javax.xml.bind:jaxb-api:2.3.1` dependency.
- **A fourth real defect, found only by actually deploying to WildFly 40** (T2/FR4): `SystemIdMapperClient`
  WELD-failed to resolve because `system.id-mapper.version`'s CDI producer was still `javax.enterprise`/
  `javax.inject`-annotated; fixed by bumping to `25.104.0-M11`.
- **Two more of this design's own recommendations, tested and reverted** (T5/FR15):
  `azure-functions-maven-plugin`'s version had to stay a literal string, not the property this design
  first tried; `test-utils-common` had to stay at `2.4.1`, not the BOM-managed bump first recommended.
- **AC13 / FR22 — the 3 IT classes on the Java 25 stack: met.** A real WildFly 40 / JDK 25 / Camunda
  7.24 image was built (`az acr login --name crmdvrepo01` unlocked pulling
  `crmdvrepo01.azurecr.io/hmcts/wildfly:40.0.0.Finaljdk25_Camunda7.24_latest` from
  `cpp-developers-docker`'s `java-25` branch), and `./runIntegrationTests.sh` passed completely:
  `ReceiveCaseFileSubmissionIT` (17), `CaseSubmissionProcessedIT` (1), `ReceiveErrorCaseSubmissionIT`
  (1) — 19 tests, 0 failures. This also exercised, for real: `buildWars`'s full reactor install,
  Liquibase 4.10.0 against a real Postgres (T6/FR18), and WAR deployment to a real Jakarta EE 11
  container (which is what caught the `system.id-mapper.version` defect above).
- **The parity story's own "pre-existing" gap, also corrected.** Its checklist recorded
  `progression-query-api`/`pcfdlrm-command-api` as unresolvable on J17, verified via `git stash`. The
  identical pair resolves fine here too — the same cert misdiagnosis, not a genuine artefact gap.
  Flagged as a follow-up for that story's own docs (not amended here).

**(c) still blocked — genuinely, structurally, not environmentally:**
- **AC12 / FR21 — QA Docker image.** This is the one item the cert-trust fix does not touch.
  `azure-pipelines.yaml:38–43` routes `Build.Reason == 'PullRequest'` to `context-verify.yaml`
  (SonarQube only); only `IndividualCI`/merge builds reach `context-validation.yaml`, where
  `docker-build.yaml` pushes to `crmdvrepo01.azurecr.io`. **No sandbox, however capable, can produce this
  before a real merge build** — it is a pipeline-design fact, not a resource gap. Budget the follow-up
  pipeline/image PR from the start regardless (nine fleet contexts already learned this the expensive
  way); T9's `dockerfilePath` question is the first thing to check if that build fails.
- **AC9's deploy-side half (Azure Functions on real Azure).** This sandbox's `az` session is
  authenticated for ACR (which is all the WildFly image build needed) but was not used against Azure
  Functions runtimes — `az functionapp list-runtimes --os windows` still needs to be run for real before
  FR16's fallback question is fully closed, even though the build/test half of AC9 is now met.

- Acceptance: FR2 (met — coredomain resolves and builds clean), FR3 (met), FR4 (met — real bump
  required and applied), FR9 (met — code generation genuinely runs), FR15 (met — builds/tests green),
  FR21 (still blocked, structurally), FR22 (met), AC2 (met), AC9 (build/test half met, Azure-deploy half
  still open), AC12 (still blocked), AC13 (met).

## Out of scope
- Any change to `cpp-context-prosecution-casefile-dlrm` — DD-43194, its own pipeline and its own stage-3
  artefact.
- Writing new parity tests, or restoring any of the parity story's withdrawn tests (BC-13, DLRM-01,
  BC-11, BC-12, the BC-21 generator tests) — a follow-on story, not this one (T11).
- A production release — the fleet tracker shows only `support` has gone that far.
- Opportunistic dependency bumps beyond what the enforcer requires (T2/FR4) — including not
  pre-emptively matching `platform-libraries-parent-pom` M11's `progression.version`.
- The "material-client decoupling" PR the fleet standard includes — verified not applicable, neither
  DLRM repo has that dependency.
- Retiring the empty read side (`stagingdlrm-viewstore-persistence`, `stagingdlrm-viewstore-liquibase`,
  `stagingdlrm-event-listener`, `stagingdlrm-query-api`, the `QUERY_API` `kmodule.xml`, the empty
  Liquibase changelog) — a separate, already-identified piece of work with a wider blast radius than
  this upgrade, raised as a follow-up in T3, T6 and T8.
- Fixing the `sonarqubeProject` key mismatch in `azure-pipelines.yaml:32` (T6) or the `dockerfilePath`
  question definitively (T9) — both raised as follow-ups, neither resolvable from inside this repo.
- Refactoring, reformatting, or test cleanup unrelated to the upgrade.
