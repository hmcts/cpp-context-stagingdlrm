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
| BC-03 | **Refuted** — no parity claim | Drools 7→10 allow/deny — `command-migrate-case-submission-api.drl` | No BC-03 pin, because there is nothing to pin: the guide refutes BC-03 (rules unchanged, fail-closed) and attaches only the residual guard "keep `*RulesTest` green", which the pre-existing tests already satisfied. The `AccessControlTest` additions are booked under **BC-20** below (parity value) and as a standalone coverage-gap fix (their real justification) | 📝 | Refuted fleet-wide; see the BC-03 note below |
| BC-20 | low (cheap) | Drools harness rule-count gate — guards the vacuous-deny failure mode a zero-rule `KieBase` would produce | `AccessControlTest`'s allow assertions, incl. `shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission` added by this story. They run through `BaseDroolsAccessControlTest` — the code path BC-20's defect lives in — so COMMAND_API cannot silently drop to 0 rules (the mechanism the guide's own evidence log names). **COMMAND_API only**; the harness-level rule-count fix is framework-owned, and QUERY_API is a separate finding. A standalone `AccessControlRuleCountTest` was authored and run green 2026-09-07 then removed — it bypassed the harness, so it guarded nothing (see note) | 🟢 | `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=AccessControlTest` → `Tests run: 4, Failures: 0` (2026-09-07) |
| BC-12 | medium | RESTEasy engine swap — the Function App's 4 compile-scope RESTEasy artifacts (no container to supply them) | None — see note below | ⚪ | Not applicable — see note below |
| BC-21 (catalog-generation-plugin) | medium | Codegen (`reflections` 0.9.10→0.10.2) — schema catalogue generation | None — see note below. (Authored and run green on J17 2026-09-04, then removed 2026-09-07 per decision — not "never written") | ⚪ | Not applicable — see note below |
| BC-21 (messaging-client-generator-plugin) | medium | Codegen — RAML-driven messaging client | None — see note below. (Authored and run green on J17 2026-09-04 as `Bc21MessagingClientGenerationParityTest`, asserting `stagingdlrm-command-handler`'s RAML-schema-count == `@Handles`-method-count on the generated remote client via reflection, then removed 2026-09-07 — the test's own stated `reflections` 0.9.10→0.10.2 premise turned out to be false for this generator) | ⚪ | Not applicable — see note below |
| BC-21 (pojo-generation-plugin) | medium | Codegen — POJO generation from JSON schema | Not instrumented — see note below | 🟡 | Not authored |
| BC-21 (rest-client-generator-plugin) | medium | Codegen — RAML-driven REST client | Not instrumented — see note below | 🟡 | Not authored |
| BC-07 | low (deploy blocker) | Liquibase 4→5 removed properties — `stagingdlrm-viewstore-liquibase`'s `liquibase.properties` | **None, by decision — this repo has no viewstore.** A key-set pin was authored and run green (3 tests, 2026-09-07), then removed: testing the migration config of a database that does not exist is scaffolding, not coverage. See note below | ⚪ | Not applicable — see note below |
| BC-08 | thin (seam exists, never fed) | Jackson `'Z'` → `ZoneOffset.UTC`. **Corrected 2026-09-07:** the repo's only main-code `ZonedDateTime` is `MigratedMaterial.receivedDateTime` (generated from `migrated-material.json`), carried onto the outbound pcfdlrm payload at `MigratedCaseConvertor.java:323`. Earlier revisions of this row claimed the only `ZonedDateTime` was an event-processor test helper — wrong | None. No J17 behaviour to pin: the Function App (`StagingDlrmCommandHelper`) is the sole producer of this payload and **never sets `receivedDateTime`** — optional in the schema, absent from every fixture — so the converter copies null to null and no `ZonedDateTime` ever crosses a Jackson boundary | 📝 | N/A — no value to serialize. See note below |

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

**There is no viewstore.** Verified on disk 2026-09-07, and this is the reason BC-07 carries no test:

- `liquibase/stagingdlrm.xml` — a bare `<databaseChangeLog>` wrapper, **zero `<changeSet>` elements**
- `stagingdlrm-viewstore-persistence` — **zero Java files**; its `persistence.xml` declares a
  persistence unit with **no `<class>` entries**, i.e. no entities
- `stagingdlrm-event-listener` — **zero Java files**
- `stagingdlrm-query-api` — **zero Java files**

The read side of this context is empty scaffolding inherited from the CPP context template. No table is
ever created, nothing is persisted, nothing is queried. (Note that the architecture docs' description of
the event listener "persisting to the view store" and the query API "reading from the view store"
describes the template, not this repo's code.)

**Decision: no Liquibase test.** A `Properties.load()` key-set pin was authored and run green (3 tests)
on 2026-09-07, then removed. Pinning the migration configuration of a database that does not exist adds a
test-scope dependency and a maintenance obligation to a module that packages two inert resource files.

**Residual risk, recorded for the upgrade story.** The jar is still built and still executed:
`docker/Dockerfile_stagingdlrm-service:21` bakes it into the image and `docker/scripts/liquibase.sh:35`
runs `java -jar stagingdlrm-viewstore-liquibase.jar … update`, aborting the init script on failure (so
line 42's `framework-system-liquibase` would not run either). Liquibase 5 rejects `liquibase.hub.mode` at
config-parse time, before it opens the changelog — an empty changelog does not insulate against that.
**The fix is deletion, not a test:** drop the property, or delete the module outright given nothing uses
it. That is the upgrade story's FR18.

**BC-08 note.** The seam is real but unfed, and that distinction matters for the upgrade story.

- **It exists:** `MigratedMaterial.receivedDateTime` is a `ZonedDateTime` in generated main code, and
  `MigratedCaseConvertor:323` copies it onto pcfdlrm's `MigratedMaterial` — structurally the same
  outbound-payload seam the parity-method ADR rates as **pcfdlrm's primary BC-08 item**. That ADR's
  side-by-side table records stagingDLRM as having "none (one test helper)"; that is wrong and should be
  read against this row.
- **It is never fed:** the Function App never populates `receivedDateTime`. It is optional in
  `migrated-material.json` and appears in no fixture in this repo. Null in, null out.
- **So the exposure is data-dependent, not structural.** If a LIBRA or XHIBIT extract begins supplying
  `receivedDateTime`, BC-08 becomes live at this converter with nothing pinning it. The cheap guard, if
  wanted later, is a round-trip assertion over `MigratedCaseConvertor.buildMaterials` with a populated
  `ZonedDateTime` — the event-processor module already has the test dependencies for it.
- The other nine `java.time` fields in generated POJOs are `LocalDate`, which carries no zone and cannot
  drift. `MigratedCaseConvertor` uses `LocalDate.parse` in main code for the same reason.

**BC-03 note.** Per both the investigation report and the fleet-wide guide, BC-03 itself (Drools
recompilation silently flipping allow/deny) is **Refuted** — rules are unchanged and fail-closed. There
is therefore no BC-03 parity pin in this repo, and an earlier revision of this checklist was wrong to
present the `AccessControlTest` additions as one (corrected 2026-09-07).

Those two tests are still worth having, for two reasons that are not BC-03:

1. **A pre-existing coverage gap.** `command-migrate-case-submission-api.drl` declares two rules and only
   the first had ever been tested on any JDK. The second guards the live
   `POST /receive-error-migrated-case-submission` endpoint. This is the tests' real justification and it
   is JDK-independent.
2. **BC-20's zero-rule guard** — see the BC-20 row above. This is the parity item they serve, and the
   guide itself pairs it with BC-03's Refuted verdict ("keep `*RulesTest` green; pair with BC-20
   rule-count assertion so a zero-rule load can't masquerade as a flip").

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

**`COMMAND_API` is incidentally guarded anyway.** The fleet-wide guide's own BC-20 evidence entry notes
that "any `*RulesTest` with an allow assertion (`assertSuccessfulOutcome`) already fails on a 0-rule
load". `AccessControlTest` has two such allow assertions — the pre-existing one and
`shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission` added by this story's BC-03 row — both routed
through `BaseDroolsAccessControlTest`, i.e. through the exact code path BC-20's defect would live in. So
the kbase that carries this repo's rules cannot silently drop to zero without a red test, which is what
BC-20 exists to guarantee. That, rather than the withdrawn `AccessControlRuleCountTest`, is what
actually covers BC-20 here. **`QUERY_API` is a different story — see the next section.**

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
| BC-01, BC-02, BC-04, BC-05, BC-06, BC-24 | The persistence cluster. **This repo has no viewstore at all** (confirmed 2026-09-07): `stagingdlrm-viewstore-persistence`, `stagingdlrm-event-listener` and `stagingdlrm-query-api` each contain **zero Java files**; `persistence.xml` declares a persistence unit with **no `<class>` entries**; the Liquibase changelog has **no changesets**. No `@Entity`, no repository, no query handler, no table. Nothing to bind a Hibernate/JPA parity test to. |
| BC-09, BC-10 | No Activiti in this repo (confirmed against `docs/architecture/dlrm-flow-reference.md`'s module map — no workflow-engine dependency). |
| BC-18 | No `ActiveMQConnectionFactory` usage in this repo. |
| BC-19 | SJP-specific; this repo has no SJP code path. |
| BC-22 | No Apache Tika usage in this repo. |
| BC-23 | No Quartz usage in this repo. |

## Corrections to source documents

**BC-11** — `docs/analysis/j25-upgrade/j25-behavioural-change-investigation-report.md` still carries the
original, uncorrected provider-collision hypothesis for BC-11. (An earlier revision of this row also
named a story-directory copy at
`docs/pipeline/DD-43191-DD-43192-j25-parity/j25-behavioural-change-investigation-report.md`; no such
copy exists — both source documents live only under `docs/analysis/j25-upgrade/`. Corrected
2026-09-07.) The fleet-wide
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

## Zero-rule kbase — `QUERY_API` (finding, not a parity assertion)

**Recorded 2026-09-07.** `stagingdlrm-query/stagingdlrm-query-api/src/main/resources/META-INF/kmodule.xml`
declares:

```xml
<kbase name="QUERY_API" packages="rules" default="true">
    <ksession name="QUERY_API_SESSION" default="true" type="stateless"/>
</kbase>
```

The module ships **no `.drl` file at all** (the repo's only DRL is the command-api one), and **no
access-control test**. `getKieBase("QUERY_API")` therefore resolves to **zero rules**, and nothing
asserts otherwise.

This is very close to the gotcha the fleet-wide guide records against `cpp-context-system-doc-generator`,
where a `packages="rules"` attribute naming the *resource folder* rather than the DRL *package
namespace* produced a 0-rule kbase. Here the mismatch is more basic — there is no DRL to name.

**Disposition — follow the guide, which is explicit about this case:** treat a both-branches-0 kbase as
a finding, *not* a parity assertion. Specifically:

- **Do not** commit a `> 0` rule-count guard for this kbase — it would be red on the J17 source of
  truth, which is not what a parity pin means.
- **Do not** "fix" the kmodule on the J25 branch only — that would manufacture a J17→J25 divergence.
- It is **parity-neutral**: identical on both runtimes, so it is not a J25 regression and not this
  story's to fix (FR15/FR18 — pin existing behaviour, don't change it).

**Which of the two possible causes it is, settled:** the kbase declaration is **dead configuration**,
not a missing guard. `stagingdlrm-query-api` contains **zero Java files** — there is no query handler to
guard, and no viewstore behind it to read (see the BC-07 note). The `kmodule.xml` is template scaffolding
that came with the context, like the rest of this repo's read side. Nothing is unprotected.

Handed to the owners as a separate, non-parity tidy-up to be applied to **both** branches, at whatever
point the empty read-side modules are dealt with as a whole.

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
- **BC-07 carries no test, and the residual risk is a deletion, not a gap in coverage.** This repo has
  no viewstore (no entities, no changesets, no listener or query code), so there is nothing for the
  Liquibase config to migrate. The jar is nonetheless still built and executed at container startup, and
  Liquibase 5 would reject `liquibase.hub.mode` at config-parse time — the upgrade story removes the
  property or the module under FR18. See the BC-07 note above.
- **Final status distribution across the 9 Bucket A items (12 rows, BC-21 split four ways):** 🟢 1
  (BC-20), 📝 2 (BC-03, BC-08), ⚪ 7 (BC-13, DLRM-01, BC-11, BC-12, BC-21 catalog-generation-plugin,
  BC-21 messaging-client-generator-plugin, BC-07), 🟡 2 (BC-21 pojo-generation-plugin,
  rest-client-generator-plugin).

  One test class survives: the `AccessControlTest` additions (2 tests, booked under **BC-20** — not
  BC-03, which is Refuted and has nothing to pin).

  Of the seven ⚪ rows, five (BC-13, BC-21 catalog-generation-plugin, BC-21 messaging-client-generator-plugin,
  and — on the specific grounds that its finding is "Refuted / parity," never a J25 candidate — BC-11)
  were removed because no live upgrade risk was found to pin (messaging-client-generator-plugin's case is
  distinct again within that group: its stated risk was checked and found never to have applied to this
  generator at all, not merely judged unimportant); **BC-12 and DLRM-01 are the exceptions** — real,
  verified-or-plausible risks, removed by explicit decision rather than because the risk was absent.
