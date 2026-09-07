# J17→J25 parity checklist — stagingDLRM

> Deliverable of [DD-43192](https://tools.hmcts.net/jira/browse/DD-43192) (epic
> [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191)). Method, scope and legend are fixed by
> [`docs/pipeline/adrs/DD-43191-j25-parity-method.md`](pipeline/adrs/DD-43191-j25-parity-method.md)
> (as amended by its **decision 8** — the BC-11 correction this story's design incorporated from the
> start); this file records the *result* of applying that method to this repo, derived on 2026-09-04
> against [`docs/pipeline/DD-43191-DD-43192-j25-parity/02-design.md`](pipeline/DD-43191-DD-43192-j25-parity/02-design.md).
>
> **This is what the [DD-43192 upgrade stage](pipeline/DD-43191-DD-43192-j25-upgrade/00-input-brief.md)
> reads to know what its regression gate covers.** A row's 🟢 means: this test exists, ran on J17
> (`service-parent-pom 17.104.1`, JDK 17, `centos8-j17`), and passed — the exact command is given.
>
> Legend: 🟢 executed green on J17 · 🟡 authored, not executed · 📝 existing coverage annotated, no
> new test warranted · ⚪ Bucket B check recorded, no test · ⬜ N/A, no binding site in this repo.

## Bucket A — 9 items (8 catalogued + DLRM-01)

| Item | Weight | Seam | Test(s) | Status | J17 run evidence |
|---|---|---|---|---|---|
| **BC-13** | primary (see note — no test) | JSON-schema validation strictness (`org.json` 20231013→20251224, everit) at the schema-catalogue tier — `stagingdlrm-domain-value-schema` | None — see note below | ⚪ | Not applicable — see note below |
| **DLRM-01** | primary (see note — no test) | Jackson `ObjectMapper.readTree` parse behaviour (2.12.7→2.21.4) at the Function App gate — not in the 24-BC catalogue (parity-method ADR decision 6) | None — see note below | ⚪ | Not applicable — see note below |
| BC-11 | **corrected** (parity-method ADR decision 8); see note — no test | `JsonObjects.createObjectBuilder().add(key, null)` throws `NullPointerException` identically on J17 and J25 — a pre-existing latent-bug parity, not a J25 regression | None — see note below | ⚪ | Not applicable — see note below |
| BC-03 | high (coverage gap, not a live risk — see note) | Drools 7→10 allow/deny — `command-migrate-case-submission-api.drl`, 2 rules, previously only 1 covered | `AccessControlTest` — added `shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission` / `shouldNotAllowSystemUserForErrorMigrateCaseSubmission` alongside the pre-existing pair for the first rule | 🟢 | `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=AccessControlTest` → `Tests run: 4, Failures: 0` (2026-09-04) |
| BC-20 | low (cheap) | Drools harness rule-count gate — guards the vacuous-deny failure mode a zero-rule `KieBase` would produce | None — see note below. (Authored and run green on J17 2026-09-07 as `AccessControlRuleCountTest`, then removed the same day — the test's own mechanism turned out not to guard the risk it was named for) | ⚪ | Not applicable — see note below |
| BC-12 | medium | RESTEasy engine swap — the Function App's 4 compile-scope RESTEasy artifacts (no container to supply them) | None — see note below | ⚪ | Not applicable — see note below |
| BC-21 (catalog-generation-plugin) | medium | Codegen (`reflections` 0.9.10→0.10.2) — schema catalogue generation | None — see note below. (Authored and run green on J17 2026-09-04, then removed 2026-09-07 per decision — not "never written") | ⚪ | Not applicable — see note below |
| BC-21 (messaging-client-generator-plugin) | medium | Codegen — RAML-driven messaging client | None — see note below. (Authored and run green on J17 2026-09-04 as `Bc21MessagingClientGenerationParityTest`, asserting `stagingdlrm-command-handler`'s RAML-schema-count == `@Handles`-method-count on the generated remote client via reflection, then removed 2026-09-07 — the test's own stated `reflections` 0.9.10→0.10.2 premise turned out to be false for this generator) | ⚪ | Not applicable — see note below |
| BC-21 (pojo-generation-plugin) | medium | Codegen — POJO generation from JSON schema | Not instrumented — see note below | 🟡 | Not authored |
| BC-21 (rest-client-generator-plugin) | medium | Codegen — RAML-driven REST client | Not instrumented — see note below | 🟡 | Not authored |
| BC-07 | low (deploy blocker) | Liquibase 4→5 removed properties — `liquibase.properties` | None — a plain `Properties.load()` unit test was authored, then removed: it only reads the file's key set, which is true on both J17 and J25 and doesn't exercise Liquibase's own property-validation logic at all. Pinning the *real* risk (Liquibase 5 rejecting `liquibase.hub.mode`) needs Liquibase itself to run, which is IT-tier (needs Docker) — see the note below | ⚪ | Not applicable — see note below |
| BC-08 | thin | Jackson `'Z'` → `ZoneOffset.UTC` — the repo's only `ZonedDateTime` is in an event-processor **test helper** (`ObjectBuilder.buildMetaData`), not product code | None — no code change of any kind, including a comment. `ObjectBuilder.java` is otherwise untouched by this story; adding a javadoc note there would be noise on unrelated code, not a pin. The finding is recorded here instead | 📝 | N/A — checked, not assumed: `StagingDlrmEventProcessorTest` only ever uses this `Metadata` as a Mockito stub return value, never serialized through Jackson and never asserted on. There is **no incidental J17 coverage of BC-08 anywhere in this repo** — an earlier version of this row, and a code comment briefly added then removed, both claimed otherwise; corrected 2026-09-07 |

**BC-13 note — this repo's other primary item had no test even before DLRM-01's removal, below.**
`Bc13SchemaValidationParityTest` (8 tests: required/enum/anyOf/type accept+reject on `case-details.json`,
a 7-value numeric-literal table on `migrated-hearing.json`'s `durationMinutes`, one parse-vs-validation
pair) and its `ClasspathSchemaClient` `$ref` resolver were authored and run green on J17 (2026-09-04:
`Tests run: 8, Failures: 0`), then **removed 2026-09-07** on the same reasoning as BC-21's
catalog-generation-plugin test below: the schema `.json` files and their content are not changing during
the J25 upgrade, so there is no live scenario for an everit/`org.json`-strictness test to catch here
either. The parity-method ADR and both `00-input-brief.md`/`01-requirements.md` call BC-13 "primary," the
highest-novelty item in this repo's Bucket A — its removal alone would have left DLRM-01 as the sole
tested primary item; see the DLRM-01 note immediately below for why that is no longer true either.

**DLRM-01 note — this repo's last tested primary item, removed 2026-09-07.** 4 tests added to
`JsonSchemaValidatorTest` (malformed-JSON parse failure, array-payload rejection, duplicate-object-key
resolution, a 7-value numeric-literal table on the manifest schema's `documentType`) were authored and
run green on J17 (`Tests run: 9, Failures: 0`, 2026-09-04), then removed on direct instruction: **"this
is not part of the java 25 upgrades."** Recorded plainly: unlike BC-11 below, DLRM-01's Jackson-version
premise (2.12.7→2.21.4) was never actually confirmed to diverge on J25 in this repo — only the J17 side
was ever run, which is this story's own method (author-then-confirm-at-upgrade), not a completed
comparison. **With this removal, this repo's regression gate has no tested primary item at all** — both
BC-13 and DLRM-01, the two items every planning document in this story called "primary," now have zero
unit-level coverage.

**BC-11 note.** Unlike the earlier DD-43192 attempts, this pass's `00-input-brief.md` and
`01-requirements.md` were authored *from the start* against the fleet-wide guide's corrected finding
(parity-method ADR decision 8) — a `JsonObjectBuilder` null-value NPE parity, not a JSON-P
`ServiceLoader` provider collision. No classpath/`ServiceLoader` inventory test was built. The
`javax.json` coordinate inventory across the modules that declare it (`command-handler`,
`event-listener`, `domain-event`, `domain-aggregate`, `azure-functions`) remains true classpath fact but
is not, on its own, evidence of a behavioural difference. **The resulting test
(`StagingDlrmCommandHelperTest.generateErrorMigratedCaseSubmissionPayloadThrowsNpeParityWhenResponseStringIsNull`)
was itself removed 2026-09-07**, on the same instruction as DLRM-01 above: BC-11's own corrected finding
is classified **"Refuted / parity"** — it throws identically on J17 and J25 by definition, so, unlike
every other item in this table, it was never a candidate to diverge under the upgrade in the first
place. Its test was authored and run green (`Tests run: 13, Failures: 0`, 2026-09-04) before removal.

**BC-12 note — removed despite a verified, real risk, not because the risk was found to be absent.**
`Bc12RestEasyPackagingParityTest` (reads `stagingdlrm-azure-functions/pom.xml` directly, asserted exactly
4 `org.jboss.resteasy` deps with no `<scope>` element) was authored, run green on J17
(`mvn -o test -pl stagingdlrm-azure-functions -Dtest=Bc12RestEasyPackagingParityTest` →
`Tests run: 1, Failures: 0`, 2026-09-04), then removed 2026-09-07 on a direct instruction, **not** on the
same "nothing live to catch" reasoning as BC-13/BC-21's catalog test. Unlike those two, this risk was
verified concrete before removal: `StagingDlrmCommandHelper.java` genuinely builds a real JAX-RS
`Client` via `ClientBuilder` to POST to `stagingdlrm-command-api` — this Function App is a standalone
JAR with no container to supply RESTEasy at runtime, so it needs these 4 artifacts bundled. The
upgrade-mechanics ADR's own decision 5 documents a fleet-wide sweep (mark bundled RESTEasy `provided`)
that would compile cleanly if applied here and then fail at runtime in Azure with
`NoClassDefFoundError` — the ADR text itself says a build-time assertion exists "precisely so the
carve-out cannot be undone silently." Recorded here so the upgrade story does not assume this specific
regression is guarded against by a test — it is only guarded against by the ADR's decision 5 being read
and followed by whoever does the RESTEasy `provided` sweep.

**BC-07 note.** `liquibase/stagingdlrm.xml` (the changelog this module's `liquibase.properties` points
at) is **empty** — a bare `<databaseChangeLog>` wrapper, no `<changeSet>` elements at all — verified on
disk 2026-09-07. That is a separate, pre-existing fact from BC-07 itself and this story does not fix it
(FR15/FR18 — pin existing behaviour, don't change it): the `update` command this repo's own
`docker/scripts/liquibase.sh:35` runs against a real Postgres database as part of container startup
currently applies zero changesets against `${contextName}viewstore`.

That said, **the config file is genuinely deployed and executed, independent of the changelog being
empty.** `docker/Dockerfile_stagingdlrm-service:21` bakes `stagingdlrm-viewstore-liquibase.jar`
(bundling both `liquibase.properties` and the changelog) into the image, and `liquibase.sh` runs it via
`java -jar stagingdlrm-viewstore-liquibase.jar ... update`, aborting the whole init script on failure.
BC-07's actual risk — Liquibase 5 rejecting the unsupported `liquibase.hub.mode` key — fires at
config-parse time, before Liquibase ever looks at whether the changelog has changesets. An empty
changelog does not insulate this repo from that.

A `Properties.load()` unit test asserting the file's key set was authored and then **removed**: it is
true on both J17 and J25 regardless of Liquibase's own version, so it never actually exercised Liquibase's
property-validation logic — it only proved the file has three keys, not that Liquibase 5 would reject one
of them. A test that actually pins the J17-vs-J25 divergence needs Liquibase itself to run against this
properties file, which is IT-tier (needs `CPP_DOCKER_DIR`) per this story's own depth model, not a unit
test. Recorded here as a Bucket-B-style check (⚪) rather than authored-not-executed (🟡), since no
context-level unit test is possible here — the only version of a test that means anything for this BC
belongs at the IT tier, which this story does not execute (see the requirements' depth model).

**BC-03 note.** Per both the investigation report and the fleet-wide guide, BC-03 itself (Drools
recompilation silently flipping allow/deny) is **Refuted** — rules are unchanged and fail-closed. This
story's BC-03 row closes a genuine, pre-existing **coverage gap** (the second rule had never been tested
on any JDK) that happens to share the ticket number; it does not mitigate a live J25 risk.

**BC-20 note.** `AccessControlRuleCountTest` was built and ran green on J17 (`Tests run: 1, Failures: 0`,
2026-09-07), then **removed the same day** on re-verification of its own premise — a different kind of
finding from every other removal in this checklist: the test's assertion was true, but its actual
*mechanism* cannot detect the risk it claims to guard, in two compounding ways.

1. **The confirmed defect doesn't exist in this repo's current dependency.** BC-20's investigation
   describes `BaseDroolsAccessControlTest.setup()` (the shared harness `AccessControlTest` extends) being
   rewritten into a hand-rolled `kmodule.xml`/`.drl` loader with a missing `else` branch for `jar:`-resolved
   resources — silently building a zero-rule `KieBase`. Decompiling this repo's actually-resolved
   `access-control-test-utils:17.104.1` (`javap -p -c` on `BaseDroolsAccessControlTest.class`) shows its
   `setup()` is still the original, safe one-liner: `KieServices.get().getKieClasspathContainer()` →
   `newStatelessKieSession(...)`. The defective rewrite exists only in the platform's J25-line fork of
   this framework library (`cpp-platform-libraries`, out of this story's scope per FR18) — this repo
   hasn't pulled it in.
2. **Even if that dependency were bumped, this test wouldn't notice.** `AccessControlRuleCountTest` calls
   `KieServices.get().getKieClasspathContainer()` **directly**, entirely bypassing
   `BaseDroolsAccessControlTest`. `AccessControlTest` — the class actually exposed to BC-20's risk, since
   it extends the harness — builds its session through the harness's own (separate, potentially
   defective) code path instead. Testing one tells you nothing about the other: if
   `access-control-test-utils` is later bumped to the defective version, `AccessControlTest`'s deny
   assertions could start passing vacuously while `AccessControlRuleCountTest` keeps passing right
   alongside it, unaffected — a false "all clear," not a guard. The investigation report itself locates
   the real fix inside `BaseDroolsAccessControlTest.setup()` (a framework-level fix in
   `access-control-test-utils`), not a per-context test, for exactly this reason.

All 13 fleet PRs read for this story use this same direct-`getKieClasspathContainer()` pattern under this
same class name — so this may be a fleet-wide blind spot, not one specific to this repo's test. That
observation is recorded here for whoever next touches BC-20 fleet-wide; it is not this story's to fix
(`access-control-test-utils` is a framework repository, out of scope per FR18).

**BC-21 note.** None of the four generator plugin families that run in this repo carries a test any
more — each for its own distinct reason:

- `catalog-generation-plugin` (`stagingdlrm-domain-value-schema`) — a schema-file-count vs.
  catalogue-entry-count test was authored, and investigated in depth: the plugin's actual file-discovery
  class (`generator-io-utils`'s `FileTreeScanner`, decompiled to check) does genuinely bundle and call
  `org.reflections.Reflections` (`ResourcesScanner`, `ConfigurationBuilder`), so BC-21's premise is not
  unfounded here. **Decision: not needed** — the schema `.json` files this catalogue is generated from
  will still be present, under the same paths, through the J25 upgrade; there is no file-removal or
  file-relocation scenario for the generator to silently mishandle here, so a count-parity test has
  nothing live to guard against. Removed rather than kept as a speculative check.
- `messaging-client-generator-plugin` (`stagingdlrm-command-api`) — a RAML-schema-count vs.
  `@Handles`-method-count test (`Bc21MessagingClientGenerationParityTest`) was authored, ran green on J17
  (2026-09-04), and asserted a true fact (4 media types in `stagingdlrm-command-handler.messaging.raml`,
  each `!include`-ing one schema file, one `@Handles` method each). **Removed 2026-09-07 for a different
  reason than every other BC-21 sub-item:** re-verifying the test's own stated premise (the same
  `reflections` 0.9.10→0.10.2 scanning-contract risk as `catalog-generation-plugin`) by decompiling every
  class in this generator's actual dependency chain (`messaging-client-generator`, `generators-commons`,
  `generators-subscription`, `generator-core` — every jar the plugin pulls in) found **zero** bytecode
  references to `org.reflections` anywhere. Unlike `catalog-generation-plugin`, this generator reads the
  command-handler's RAML artifact via an **explicit** Maven dependency (`classifier=raml`, all
  transitives excluded) and parses it directly — no classpath reflection scan at all. The test's assertion
  was true, but the risk it claimed to guard against was never real for this generator; what it actually
  pinned was a RAML-parsing/JavaPoet codegen invariant unrelated to any J25 library bump. Removed because
  the premise didn't hold, not because the risk was found present-but-unimportant (contrast with
  `catalog-generation-plugin` above, where the reflections premise *was* confirmed true).
- `pojo-generation-plugin`'s `pojo-generation-schema` execution in `stagingdlrm-domain-event` scans the
  *entire test classpath* (`sourceDirectory: CLASSPATH`), including `common-core-domain` and
  `criminal-court-public-model` — third-party jars this repo doesn't own. A hard-coded count or manifest
  there would be exactly the "maintenance burden" 01-requirements.md's risk notes warn against. Left to
  its large existing incidental coverage instead.
- `rest-client-generator-plugin` (in `stagingdlrm-event-processor`) needs `pcfdlrm-command-api` and
  `progression-query-api` RAML artifacts that are not resolvable offline in this environment;
  authored-not-instrumented for that reason — see Gaps below.

## Bucket B — 4 items, recorded check only (no context-level test; framework-owned)

| Item | Status | What was checked | Result | Why no test |
|---|---|---|---|---|
| BC-14 | ⚪ | Every `META-INF/beans.xml` in this repo declares `bean-discovery-mode="all"` explicitly | Confirmed | The upgrade story's own FR8 (preserving `bean-discovery-mode="all"`) is what keeps this repo unaffected by CDI 4's discovery-mode default change; there is no context-level code to bind a test to |
| BC-15 | ⚪ | Core-domain field availability is a precondition on the `coredomain.version` bump the upgrade story performs, not a behaviour this repo's code exercises today | Not applicable to pin at the parity stage | Framework/platform-owned (PEG-3296); revisit if the upgrade story's core-domain bump surfaces a missing field |
| BC-16 | ⚪ | `/internal/metrics/*` — searched this repo for any custom metrics endpoint or override | None found; this repo relies entirely on the framework's own healthcheck/metrics wiring (`stagingdlrm-healthchecks` only supplies `StagingdlrmIgnoredHealthcheckNamesProvider`) | Framework-owned; no context-level binding site |
| BC-17 | ⚪ | `stream_error` hash/identity — searched for any custom stream-error handling | None found; this repo uses the framework's event-sourcing error handling as-is | Framework-owned; no context-level binding site |

## N/A — 12 items, no binding site in this repo

| Item(s) | Reason |
|---|---|
| BC-01, BC-02, BC-04, BC-05, BC-06, BC-24 | The persistence cluster. `stagingdlrm-viewstore-persistence` contains **zero Java files** (confirmed 2026-09-04) — no `@Entity`, no repository, only `persistence.xml` and `beans.xml`. Nothing to bind a Hibernate/JPA parity test to. |
| BC-09, BC-10 | No Activiti in this repo (confirmed against `docs/architecture/dlrm-flow-reference.md`'s module map — no workflow-engine dependency). |
| BC-18 | No `ActiveMQConnectionFactory` usage in this repo. |
| BC-19 | SJP-specific; this repo has no SJP code path. |
| BC-22 | No Apache Tika usage in this repo. |
| BC-23 | No Quartz usage in this repo. |

## Corrections to source documents

**BC-11** — both `docs/analysis/j25-upgrade/j25-behavioural-change-investigation-report.md` and the
story-directory copy at `docs/pipeline/DD-43191-DD-43192-j25-parity/j25-behavioural-change-investigation-report.md`
(itself byte-identical to the analysis copy except for one added provenance note) still carry the
original, uncorrected provider-collision hypothesis for BC-11. The fleet-wide
`Parity+Testing+Java17+-_+Java25.pdf`'s 2026-08-26 correction is what this story's BC-11 row is built
against; per instruction, the investigation report is left as-is, not edited.

No other corrections were found for this repo's own executed runs — every other item's behaviour, once
verified fresh against the code on 2026-09-04, matched what both source documents describe.

## Notable J17 findings

> **Neither half of these findings is backed by an executing test any more.** Both
> `Bc13SchemaValidationParityTest` and DLRM-01's numeric-literal table in `JsonSchemaValidatorTest` were
> removed 2026-09-07 (see the BC-13 and DLRM-01 notes above). The observations below were genuinely run
> and true on J17 when recorded (2026-09-04); kept purely as historical context — no live test currently
> pins any of this.

- **BC-13's and DLRM-01's tables diverge sharply on the identical literals `007`/`01`/`.5`.** At the
  BC-13 tier, `org.json` 20231013 parses these leniently (`007`/`01` → `Integer`, within the schema's
  `maximum`, ACCEPT; `.5` → `BigDecimal`, REJECTed on type). At the DLRM-01 tier, Jackson's default
  `ObjectMapper` **rejects all three at parse time** (`Invalid numeric value: Leading zeroes not
  allowed` / `Unexpected character ('.'...)`) — a `JsonProcessingException`, not a schema-validation
  outcome. Two different libraries, two completely different failure *shapes* for the same input.
- **The oversized literal (`12345678901234567890`) is rejected at BC-13 but accepted at DLRM-01**, for
  the same underlying reason each time: BC-13's `durationMinutes` has `"maximum": 99999` and everit
  rejects the literal on **type** (`BigInteger` ≠ `Integer`) before any bound is considered; DLRM-01's
  `documentType` has **no** `maximum` configured, so the same oversized value satisfies `"type":
  "integer"` and is accepted.
- **`10.0` and `1e3` reject identically at both tiers, but for different underlying checks.** Both
  parse successfully as non-integral node/value types at each tier (everit: `BigDecimal`; Jackson:
  `DoubleNode`) and both tiers' `"type": "integer"` check inspects the parsed type rather than whether
  the numeric value happens to be whole.

## Gaps

- **BC-12 has no test at all, and the risk it guarded is real** — see the dedicated BC-12 note above.
  Arguably the single most concerning gap in this checklist: unlike every other ⚪ row, this one was not
  removed because the risk was found absent — it was verified concrete (a real runtime dependency, a
  fleet-wide sweep the upgrade-mechanics ADR itself documents as dangerous here) and removed anyway by
  explicit decision. The upgrade stage has no automated guard against this specific regression; only the
  ADR's decision 5, read and followed by whoever performs the RESTEasy `provided` sweep, prevents it.
- **BC-13 has no test at all** — see the dedicated BC-13 note above. Its own primary item, deliberately
  dropped, not merely narrowed (its "format"-constraint sub-case was a gap even while the rest of the
  test existed; that distinction no longer matters now the whole test is gone). Unlike BC-12, this one
  *was* removed because the risk was judged not to apply.
- **DLRM-01 has no test at all** — see the dedicated DLRM-01 note above. This repo's *other* declared
  primary item, removed on the instruction that it isn't part of the Java 25 upgrade. **With this and
  BC-13 both gone, this story's regression gate has no tested primary item.**
- **BC-11 has no test at all** — see the BC-11 note above. Removed on the same instruction as DLRM-01.
  Distinct from every other removal in this checklist: BC-11's corrected finding is itself classified
  "Refuted / parity" (identical behaviour on J17 and J25), so its test was arguably never pinning a J25
  upgrade risk in the first place, only a pre-existing, JDK-independent NPE contract.
- **BC-21 now has no test at all, for any of its four generator families** — see the BC-21 note above.
  `messaging-client-generator-plugin`'s test was one removal: its assertion was true but its stated risk
  (reflections scanning-contract change) was verified false for this generator after the fact, a
  different flavour of gap from the other three families' reasons (schema files not moving; third-party
  classpath scan; RAML artifacts unresolvable offline). `stagingdlrm-event-processor`'s
  `rest-client-generator-plugin` execution specifically needs `pcfdlrm-command-api` and
  `progression-query-api` RAML-classified artifacts that are not resolvable in this offline development
  environment.
- **BC-20 has no test at all, for a third distinct flavour of reason** — see the dedicated BC-20 note
  above. Its assertion was true, but its mechanism (`getKieClasspathContainer()` called directly) neither
  encounters the confirmed defect (this repo's dependency doesn't carry it) nor would notice it if a
  future dependency bump introduced it (the test bypasses `BaseDroolsAccessControlTest` entirely, so it
  can't observe a defect confined to that class's own loading path). **With this gone, BC-03 is the only
  Bucket A item left carrying a 🟢 in this checklist.**
- **`mvn clean install -DskipITs` (AC2) could not be run for the full reactor in this environment,
  for the same reason** — `stagingdlrm-event-processor` (and its two dependents, `stagingdlrm-service`
  and `stagingdlrm-testharness`) need `uk.gov.moj.cpp.progression:progression-query-api:jar:raml:17.0.297`,
  which this offline environment's local repository has never downloaded. What *was* run and is evidence
  toward AC2: `mvn -o clean install -DskipITs -pl '!stagingdlrm-event/stagingdlrm-event-processor,!stagingdlrm-service,!stagingdlrm-testharness'`
  → `BUILD SUCCESS`, all 22 remaining reactor modules, every new and pre-existing test executing and
  none skipped (2026-09-04). Verified via a clean `git stash` that this artifact-resolution failure is
  pre-existing on `team/25.104.x` and not introduced by this story.
- **BC-07's only meaningful pin is IT-tier, and this story does not execute IT-tier items.** A
  context-level unit test would only prove the properties file has three keys — true regardless of
  Liquibase's version, so it cannot actually catch the J17→J25 divergence. The real check needs Liquibase
  itself to run against `liquibase.properties`, which needs `CPP_DOCKER_DIR` (per this story's depth
  model, same reason no other IT-tier item is executed here). Recorded as ⚪ rather than 🟡, since there
  is no unit-level version of this test worth authoring in the meantime — see the BC-07 note above.
- **Final status distribution across the 9 Bucket A items (12 rows, BC-21 split four ways):** 🟢 1
  (BC-03), 📝 1 (BC-08), ⚪ 8 (BC-13, DLRM-01, BC-11, BC-12, BC-21 catalog-generation-plugin,
  BC-21 messaging-client-generator-plugin, BC-20, BC-07), 🟡 2 (BC-21 pojo-generation-plugin,
  rest-client-generator-plugin). Of the eight ⚪ rows, five (BC-13, BC-21 catalog-generation-plugin,
  BC-21 messaging-client-generator-plugin, BC-07, and — on the specific grounds that its finding is
  "Refuted / parity," never a J25 candidate — BC-11) were removed because no live upgrade risk was found
  to pin (messaging-client-generator-plugin's case is distinct again within that group: its stated risk
  was checked and found never to have applied to this generator at all, not merely judged unimportant);
  **BC-12 and DLRM-01 are the exceptions** — real, verified-or-plausible risks, removed by explicit
  decision rather than because the risk was absent; **BC-20 is a fourth, distinct flavour again** — its
  risk is real fleet-wide, but its own test's mechanism cannot detect it in this repo, whether or not the
  underlying dependency is ever bumped (see its dedicated note above). With this removal, **BC-03's two
  added tests in `AccessControlTest` are the only 🟢 this story leaves behind anywhere in the repo** —
  every other Bucket A item is either 📝, ⚪, or 🟡.
