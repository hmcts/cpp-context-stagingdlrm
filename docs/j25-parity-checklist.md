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
| **BC-13** | primary | JSON-schema validation strictness (`org.json` 20231013→20251224, everit) at the schema-catalogue tier — `stagingdlrm-domain-value-schema` | `Bc13SchemaValidationParityTest` (8 tests: required/enum/anyOf/type accept+reject on `case-details.json`, a 7-value numeric-literal table on `migrated-hearing.json`'s `durationMinutes`, one parse-vs-validation pair) + `SchemaCatalogGenerationParityTest` (BC-21 half, below), via `ClasspathSchemaClient` — resolves `$ref`s from the module's own generated `META-INF/schema_catalog.json`, not a hand-written URI map (necessary: several schema ids do not match their file's own name) | 🟢 | `mvn -o test -pl stagingdlrm-domain/stagingdlrm-domain-value-schema -Dtest=Bc13SchemaValidationParityTest,SchemaCatalogGenerationParityTest` → `Tests run: 9, Failures: 0` (2026-09-04) |
| **DLRM-01** | primary | Jackson `ObjectMapper.readTree` parse behaviour (2.12.7→2.21.4) at the Function App gate — not in the 24-BC catalogue (parity-method ADR decision 6) | 4 tests added to the existing `JsonSchemaValidatorTest`: malformed-JSON parse failure, array-payload rejection (before schema validation runs), duplicate-object-key resolution (Jackson keeps the LAST value silently), and a 7-value numeric-literal table on the manifest schema's `documentType` (no `maximum`, unlike BC-13's field) | 🟢 | `mvn -o test -pl stagingdlrm-azure-functions -Dtest=JsonSchemaValidatorTest` → `Tests run: 9, Failures: 0` (2026-09-04) |
| BC-11 | **corrected from the outset** (parity-method ADR decision 8) | `JsonObjects.createObjectBuilder().add(key, null)` throws `NullPointerException` identically on J17 and J25 — a pre-existing latent-bug parity, not a J25 regression | `StagingDlrmCommandHelperTest.generateErrorMigratedCaseSubmissionPayloadThrowsNpeParityWhenResponseStringIsNull` — pins the real call site (`StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload`'s `.add("errorMessage", responseString)`, `responseString` reachable as null on the error path per `docs/architecture/dlrm-flow-reference.md` §2.6 Path 3) | 🟢 | `mvn -o test -pl stagingdlrm-azure-functions -Dtest=StagingDlrmCommandHelperTest` → `Tests run: 13, Failures: 0` (2026-09-04) |
| BC-03 | high (coverage gap, not a live risk — see note) | Drools 7→10 allow/deny — `command-migrate-case-submission-api.drl`, 2 rules, previously only 1 covered | `AccessControlTest` — added `shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission` / `shouldNotAllowSystemUserForErrorMigrateCaseSubmission` alongside the pre-existing pair for the first rule | 🟢 | `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=AccessControlTest` → `Tests run: 4, Failures: 0` (2026-09-04) |
| BC-20 | low (cheap) | Drools harness rule-count gate — guards the vacuous-deny failure mode a zero-rule `KieBase` would produce | `AccessControlRuleCountTest` — loads `KieServices.get().getKieClasspathContainer().getKieBase("COMMAND_API").getKiePackages()` directly (a `StatelessKieSession` does not expose the `KieBase`) and asserts the exact 2-rule name set. Named to match the fleet-wide convention (confirmed in all 13 fleet PRs read for this story, e.g. `system-id-mapper`#27), rather than a bespoke BC-numbered name | 🟢 | `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=AccessControlRuleCountTest` → `Tests run: 1, Failures: 0` (2026-09-07) |
| BC-12 | medium | RESTEasy engine swap — the Function App's 4 compile-scope RESTEasy artifacts (no container to supply them) | `Bc12RestEasyPackagingParityTest` — reads `stagingdlrm-azure-functions/pom.xml` directly, asserts exactly 4 `org.jboss.resteasy` deps and no `<scope>` (i.e. compile); version is deliberately not pinned | 🟢 | `mvn -o test -pl stagingdlrm-azure-functions -Dtest=Bc12RestEasyPackagingParityTest` → `Tests run: 1, Failures: 0` (2026-09-04) |
| BC-21 (catalog-generation-plugin) | medium | Codegen (`reflections` 0.9.10→0.10.2) — schema catalogue generation | `SchemaCatalogGenerationParityTest` (`stagingdlrm-domain-value-schema`) — asserts the generator's *contract* (schema-file-count == catalogue-entry-count), computed both ways at test time | 🟢 | `mvn -o test -pl stagingdlrm-domain/stagingdlrm-domain-value-schema -Dtest=SchemaCatalogGenerationParityTest` → `Tests run: 1, Failures: 0` (2026-09-04) |
| BC-21 (messaging-client-generator-plugin) | medium | Codegen (`reflections` 0.9.10→0.10.2) — RAML-driven messaging client | `Bc21MessagingClientGenerationParityTest` (`stagingdlrm-command-api`) — asserts `stagingdlrm-command-handler`'s RAML-schema-count == `@Handles`-method-count on the generated remote client, via reflection | 🟢 | `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=Bc21MessagingClientGenerationParityTest` → `Tests run: 1, Failures: 0` (2026-09-04) |
| BC-21 (pojo-generation-plugin) | medium | Codegen — POJO generation from JSON schema | Not instrumented — see note below | 🟡 | Not authored |
| BC-21 (rest-client-generator-plugin) | medium | Codegen — RAML-driven REST client | Not instrumented — see note below | 🟡 | Not authored |
| BC-07 | low (deploy blocker) | Liquibase 4→5 removed properties — `liquibase.properties` | None — a plain `Properties.load()` unit test was authored, then removed: it only reads the file's key set, which is true on both J17 and J25 and doesn't exercise Liquibase's own property-validation logic at all. Pinning the *real* risk (Liquibase 5 rejecting `liquibase.hub.mode`) needs Liquibase itself to run, which is IT-tier (needs Docker) — see the note below | ⚪ | Not applicable — see note below |
| BC-08 | thin | Jackson `'Z'` → `ZoneOffset.UTC` — the repo's only `ZonedDateTime` is in an event-processor **test helper** (`ObjectBuilder.buildMetaData`), not product code | None — no code change of any kind, including a comment. `ObjectBuilder.java` is otherwise untouched by this story; adding a javadoc note there would be noise on unrelated code, not a pin. The finding is recorded here instead | 📝 | N/A — checked, not assumed: `StagingDlrmEventProcessorTest` only ever uses this `Metadata` as a Mockito stub return value, never serialized through Jackson and never asserted on. There is **no incidental J17 coverage of BC-08 anywhere in this repo** — an earlier version of this row, and a code comment briefly added then removed, both claimed otherwise; corrected 2026-09-07 |

**BC-11 note.** Unlike the earlier DD-43192 attempts, this pass's `00-input-brief.md` and
`01-requirements.md` were authored *from the start* against the fleet-wide guide's corrected finding
(parity-method ADR decision 8) — a `JsonObjectBuilder` null-value NPE parity, not a JSON-P
`ServiceLoader` provider collision. No classpath/`ServiceLoader` inventory test was built. The
`javax.json` coordinate inventory across the modules that declare it (`command-handler`,
`event-listener`, `domain-event`, `domain-aggregate`, `azure-functions`) remains true classpath fact but
is not, on its own, evidence of a behavioural difference.

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

**BC-21 note.** Two of the four generator plugin families that run in this repo are instrumented directly
(🟢 above); two are not (🟡):

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

- **BC-13's "format" constraint class has no binding site authored in this repo.** `case-details.json`
  has no `"format"` keyword of its own; the only format-bearing definitions this schema set reaches are
  inside `common-core-domain`'s `definitions.json` (date/uuid), which is framework-owned. Recorded as a
  gap rather than asserted against a schema this repo doesn't own.
- **`stagingdlrm-event-processor`'s `rest-client-generator-plugin` execution is not instrumented.**
  It depends on `pcfdlrm-command-api` and `progression-query-api` RAML-classified artifacts that
  are not resolvable in this offline development environment. BC-21's contract is pinned for the
  other 2 of 4 generator families that run in this repo; this is the acknowledged remainder.
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
- **Integration-tier items: none of this repo's Bucket A items land at the IT tier *as unit-executable
  work*.** BC-07 is the one item whose only meaningful test is IT-tier; it is recorded as a check (⚪),
  not a 🟡-authored-not-executed unit test, for the reason above. Every other item is 🟢 or 📝, except
  the two BC-21 generator families explained above.
