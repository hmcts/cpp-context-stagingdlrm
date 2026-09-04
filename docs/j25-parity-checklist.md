# J17→J25 parity checklist — stagingDLRM

> Deliverable of [DD-43192](https://tools.hmcts.net/jira/browse/DD-43192) (epic
> [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191)). Method, scope and legend are fixed by
> [`docs/pipeline/adrs/DD-43191-j25-parity-method.md`](pipeline/adrs/DD-43191-j25-parity-method.md);
> this file records the *result* of applying that method to this repo. One row per item in the
> 24-BC catalogue ([`docs/analysis/j25-upgrade/j25-behavioural-change-investigation-report.md`](analysis/j25-upgrade/j25-behavioural-change-investigation-report.md))
> plus DLRM-01, which is not in that catalogue (parity-method ADR decision 6).
>
> **This is what the [DD-43192 upgrade stage](pipeline/DD-43191-DD-43192-j25-upgrade/00-input-brief.md)
> reads to know what its regression gate covers.** A row's 🟢 means: this test exists, ran on J17
> (`service-parent-pom 17.104.1`, JDK 17, `centos8-j17`), and passed — the exact command is given.
>
> Legend: 🟢 executed green on J17 · 🟡 authored, not executed · 📝 existing coverage annotated, no
> new test warranted · ⚪ Bucket B check recorded, no test · ⬜ N/A, no binding site in this repo.

## Bucket A — 9 items (8 catalogued + DLRM-01)

> BC-21 is one item on the ADR's Bucket A table, split into 4 rows below (one per generator plugin
> family) so the status column doesn't overstate coverage — see the BC-21 note after the table.

| Item | Weight | Seam | Test(s) | Status | J17 run evidence |
|---|---|---|---|---|---|
| **BC-13** | primary | JSON-schema validation strictness (`org.json` 20231013→20251224, everit) at the schema-catalogue tier — `stagingdlrm-domain-value-schema` | `MigratedCaseSubmissionSchemaParityTest` (15 tests: 7-value numeric-literal table, accept path, 5 reject paths — one per constraint class type/enum/required/format/anyOf, parse-vs-validation distinction) | 🟢 | `mvn -o test -pl stagingdlrm-domain/stagingdlrm-domain-value-schema -Dtest=MigratedCaseSubmissionSchemaParityTest` → `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` (module total 17/17 with `SchemaCatalogGenerationParityTest`) (2026-09-04) |
| **DLRM-01** | primary | Jackson `ObjectMapper.readTree` parse behaviour (2.12.7→2.21.4) at the Function App gate — not in the 24-BC catalogue (decision 6) | `JsonSchemaValidatorTest` additions — 7-value numeric-literal table (separate from BC-13's per FR7), malformed-JSON parse failure, array-payload rejection, duplicate-object-key resolution | 🟢 | `mvn -o test -pl stagingdlrm-azure-functions -Dtest=JsonSchemaValidatorTest` → `Tests run: 15, Failures: 0` (module total 68/68 with `BC12RestEasyPackagingParityTest` and `Bc11JsonProviderParityTest`) (2026-09-04) |
| BC-11 | high | JSON-P provider resolution — `javax.json` across 5 modules (command-handler, event-listener, domain-event, domain-aggregate, azure-functions); 3 distinct GAVs, not the brief's claimed 6 (re-derived per ADR decision 7) | `Bc11JsonProviderParityTest` — same 3-test shape in each of the 5 modules, javadoc tailored per module's actual classpath makeup (`domain-event`'s glassfish provider only reaches its test classpath via this story's own `test-utils-core` addition — called out explicitly rather than presented as pre-existing) | 🟢 | `mvn -o test -pl stagingdlrm-domain/stagingdlrm-domain-event,stagingdlrm-domain/stagingdlrm-domain-aggregate,stagingdlrm-command/stagingdlrm-command-handler,stagingdlrm-event/stagingdlrm-event-listener,stagingdlrm-azure-functions -Dtest=Bc11JsonProviderParityTest` → `Tests run: 3` in each of the 5 modules, all green (2026-09-04) |
| BC-03 | high | Drools 7→10 allow/deny — `command-migrate-case-submission-api.drl`, 2 rules, previously only 1 covered | `AccessControlTest` — added `shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission` / `shouldNotAllowSystemUserForErrorMigrateCaseSubmission` alongside the pre-existing pair for the first rule | 🟢 | `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=AccessControlTest` → `Tests run: 4, Failures: 0` (2026-09-04) |
| BC-20 | low (cheap) | Drools harness rule-count gate — guards the vacuous-deny failure mode a zero-rule KieBase would produce | `Bc20RuleHarnessParityTest` — extends `BaseDroolsAccessControlTest` and reads `kSession.getKieBase().getKiePackages()` directly, asserting the exact `{"Command - Rule for Migrate Case Submission", "Command - Rule for Error Migrate Case Submission"}` name set. Deliberately interrogates the harness's own `kSession` rather than a second, independently-built `KieContainer` — a container this test constructed itself would keep passing even if the harness's own rule-loading path (the thing BC-20 exists to guard) silently started producing zero rules | 🟢 | `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=Bc20RuleHarnessParityTest` → `Tests run: 1, Failures: 0` (2026-09-04) |
| BC-12 | medium | RESTEasy engine swap — the Function App's 4 compile-scope RESTEasy artifacts (no container to supply them) | `BC12RestEasyPackagingParityTest` — reads `stagingdlrm-azure-functions/pom.xml` directly, asserts exactly 4 `org.jboss.resteasy` deps and no `<scope>` (i.e. compile); version is deliberately not pinned (the upgrade story's Jakarta-REST swap will move it legitimately, per the upgrade-mechanics ADR decision 5 — pinning it would fail this test for the wrong reason) | 🟢 | `mvn -o test -pl stagingdlrm-azure-functions -Dtest=BC12RestEasyPackagingParityTest` → `Tests run: 1, Failures: 0` (2026-09-04) |
| BC-21 (catalog-generation-plugin) | medium | Codegen (`reflections` 0.9.10→0.10.2) — schema catalogue generation | `SchemaCatalogGenerationParityTest` (`stagingdlrm-domain-value-schema`) — asserts the generator's *contract* (schema-file-count↔catalogue-entry-count), not a literal manifest | 🟢 | `mvn -o test -pl stagingdlrm-domain/stagingdlrm-domain-value-schema -Dtest=SchemaCatalogGenerationParityTest` → `Tests run: 2, Failures: 0` (2026-09-04) |
| BC-21 (messaging-client-generator-plugin) | medium | Codegen (`reflections` 0.9.10→0.10.2) — RAML-driven messaging client | `Bc21MessagingClientGenerationParityTest` (`stagingdlrm-command-api`) — asserts RAML-media-type-count↔`@Handles`-method-count | 🟢 | `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=Bc21MessagingClientGenerationParityTest` → `Tests run: 1, Failures: 0` (2026-09-04) |
| BC-21 (pojo-generation-plugin) | medium | Codegen — POJO generation from JSON schema | Not instrumented — see note below | 🟡 | Not authored |
| BC-21 (rest-client-generator-plugin) | medium | Codegen — RAML-driven REST client | Not instrumented — see note below | 🟡 | Not authored |
| BC-07 | low (deploy blocker) | Liquibase 4→5 removed properties — `liquibase.properties` | `LiquibasePropertiesParityTest` — pins the exact key set (`changelogFile`, `liquibase.hub.mode`, `liquibase.headless`) and their J17 values | 🟢 | `mvn -o test -pl stagingdlrm-viewstore/stagingdlrm-viewstore-liquibase` → `Tests run: 2, Failures: 0` (2026-09-04) |
| BC-08 | thin | Jackson `'Z'` → `ZoneOffset.UTC` — the repo's only `ZonedDateTime` is in an event-processor **test helper** (`ObjectBuilder.buildMetaData`), not product code | Annotated in place (Javadoc naming BC-08 and FR14) rather than a new test — authoring one would assert the fixture, not the product | 📝 | N/A — no new test; existing `StagingDlrmEventProcessorTest` coverage (`shouldHandleMigratedCaseSubmissionReceivedWhenSourceSystemLIBRA` et al.) already exercises the annotated helper |

**BC-21 note.** Two of the four generator plugin families that run in this repo are instrumented directly
(🟢 above); two are not (🟡):

- `pojo-generation-plugin`'s `pojo-generation-schema` execution in `stagingdlrm-domain-event` scans the
  *entire test classpath* (`sourceDirectory: CLASSPATH`), including `common-core-domain` and
  `criminal-court-public-model` — an observed 536 generated types outside this repo's own schema set,
  driven by third-party jars this repo doesn't own. A hard-coded count or manifest there would be exactly
  the "maintenance burden" the requirements' risk notes warn against, and would fail for reasons unrelated
  to this repo's own code. That generator family is left to its large existing incidental coverage instead
  (every class `ObjectBuilder` and the generated domain-event types reference must resolve, or the whole
  module fails to compile — a real, if implicit, check) rather than a marked-green test.
- `rest-client-generator-plugin` (in `stagingdlrm-event-processor`) needs `pcfdlrm-command-api` and
  `progression-query-api` RAML artifacts that are not resolvable offline in this environment;
  authored-not-instrumented for that reason — see Gaps below.

## Bucket B — 4 items, recorded check only (no context-level test; framework-owned)

| Item | Status | What was checked | Result | Why no test |
|---|---|---|---|---|
| BC-14 | ⚪ | Every `META-INF/beans.xml` in this repo (10 files across all WAR/JAR modules) declares `bean-discovery-mode="all"` explicitly | Confirmed — all 10 set it explicitly, none rely on the CDI-version-dependent implicit default | The upgrade story's FR8 (preserving `bean-discovery-mode="all"`) is what keeps this repo unaffected by CDI 4's discovery-mode default change; there is no context-level code to bind a test to |
| BC-15 | ⚪ | Core-domain field availability is a precondition on the `coredomain.version` (`17.104.4`) bump the upgrade story performs, not a behaviour this repo's code exercises today | Not applicable to pin at the parity stage | Framework/platform-owned (PEG-3296); revisit if the upgrade story's core-domain bump surfaces a missing field |
| BC-16 | ⚪ | `/internal/metrics/*` — searched this repo for any custom metrics endpoint or override | None found; this repo relies entirely on the framework's own healthcheck/metrics wiring (`stagingdlrm-healthchecks` only supplies `StagingdlrmIgnoredHealthcheckNamesProvider`) | Framework-owned; no context-level binding site |
| BC-17 | ⚪ | `stream_error` hash/identity — searched for any custom stream-error handling | None found; this repo uses the framework's event-sourcing error handling as-is | Framework-owned; no context-level binding site |

## N/A — 12 items, no binding site in this repo

| Item(s) | Reason |
|---|---|
| BC-01, BC-02, BC-04, BC-05, BC-06, BC-24 | The persistence cluster. `stagingdlrm-viewstore-persistence` contains **zero Java files** — no `@Entity`, no repository, only `persistence.xml` and `beans.xml` (confirmed 2026-09-04). Nothing to bind a Hibernate/JPA parity test to. |
| BC-09, BC-10 | No Activiti in this repo. |
| BC-18 | No `ActiveMQConnectionFactory` usage in this repo. |
| BC-19 | SJP-specific; this repo has no SJP code path. |
| BC-22 | No Apache Tika usage in this repo. |
| BC-23 | No Quartz usage in this repo. |

## Corrections to the investigation report

None found for stagingDLRM's own executed runs during this story — decision 4 of the parity-method
ADR expects at least one such correction is common across the fleet, but the report's BC-13 entry had
already corrected itself before this story started: it explicitly refutes the 24-BC catalogue's own
originally-guessed trigger values (`12345678901234567890`, `1e3`, `10.0`) as byte-identical across
`org.json` 20231013→20251224, in favour of the real, narrower trigger the report verified by executing
the real call shape - numeric literals with a leading zero before or instead of a decimal point
(`007`, `01`, `.5`), previously silently coerced to a `Number`, now preserved as a `String`. This
repo's BC-13 table (`durationMinutes`, `"type": "integer"`) is built to, and is consistent with, that
corrected finding - see below for exactly how it lands on an integer-typed field, which is not quite
the "007/01/.5 all flip" framing a first read of the finding might suggest.

## Notable J17 findings, and how they relate to the report's BC-13 correction

These are not further corrections - decision 4 expects the report to be checked against a real J17
run, and here every one of the 9 Bucket A items agrees with what the report says (once its own
self-correction, above, is taken into account) - but they are exactly the kind of precision decision 4
asks the checklist to record rather than leave to inference:

- **BC-13's numeric-literal table does not flip uniformly for an integer-typed field.** The report's
  finding is framework-wide, for `type:integer`/`type:number` generally; this repo's target field
  (`durationMinutes`, `"type": "integer", "maximum": 99999`) narrows what actually flips *in verdict*:
  - `007`/`01` — J17: `org.json` coerces to `Integer` → **ACCEPT**. J25 (per the report): preserved as
    `String` → **REJECT**. This is a genuine ACCEPT→REJECT flip, and is exactly what this repo's table
    pins for these two literals.
  - `.5` — J17: parses as `BigDecimal` → **REJECT** already (`"type": "integer"` never accepts a
    non-integral `BigDecimal`, independently of the J25 change). J25: preserved as `String` → still
    **REJECT**. The *verdict* does not flip for an integer-typed field; only the rejection reason
    changes, from `expected type: Integer, found: BigDecimal` to (expected, not yet observable on this
    J17 branch) `found: String`. If the upgrade story's regression gate asserts on this test's message
    text rather than just its verdict, that message-fingerprint change is expected and is not itself a
    regression.
  - `10.0`, `1e3`, `12345678901234567890` — the report found these byte-identical on both `org.json`
    versions; this repo's table pins them as REJECT on J17 for the same underlying reason (parsed Java
    type is `BigDecimal`/`BigInteger`, never `Integer`), and no flip is expected on J25.
- **BC-11's "exactly one provider" is not what it looks like.** A literal reading of FR8 suggests
  asserting `ServiceLoader.load(JsonProvider.class)` finds one entry. Observed on J17: it finds
  **zero** — `javax.json.spi.JsonProvider.provider()` instead resolves via its internal
  hard-coded-default-class-name fallback to `org.glassfish.json.JsonProviderImpl`. Both facts are
  now pinned (`Bc11JsonProviderParityTest`, via a classpath-resource count rather than a
  `ServiceLoader` count), because a J25 Parsson jar that *does* register properly via `ServiceLoader`
  would change which resolution path wins, not just how many entries exist.
- **everit's "integer" type check is stricter than the schema's numbers suggest.** `10.0` and
  `1e3` are mathematically integral but fail BC-13's numeric-literal table with
  `expected type: Integer, found: BigDecimal` — everit checks the parsed Java type
  (`org.json` never narrows a decimal literal back to `Integer`), not the numeric value. The
  DLRM-01 table shows the opposite shape for the same two literals at the Jackson tier: `number
  found, integer expected`, a differently-worded but same-root-cause rejection — evidence FR7's
  "two tiers, two tables" requirement is not a formality.
- **BC-13's oversized-integer literal (`12345678901234567890`) and DLRM-01's are rejected/accepted
  for opposite reasons.** BC-13 (everit) rejects it on **type** (`BigInteger` ≠ `Integer`) before
  any bound is even considered. DLRM-01 (networknt, on a field with no `maximum` configured)
  **accepts** it — a `BigInteger` value satisfies `"type": "integer"` there. Two different
  libraries, two different exposure profiles, exactly as the ADR's decision 6 predicts.

## Gaps

- **`stagingdlrm-event-processor`'s `rest-client-generator-plugin` execution is not instrumented.**
  It depends on `pcfdlrm-command-api` and `progression-query-api` RAML-classified artifacts that
  are not resolvable in this offline development environment. BC-21's contract is pinned for the
  other 2 of 4 generator families that run in this repo (catalog-generation, messaging-client-
  generation); this is the acknowledged remainder, not a silent omission.
- **`mvn clean install -DskipITs` (AC2) could not be run for the full reactor in this environment,
  for the same reason** — `stagingdlrm-event-processor` (and its two dependents, `stagingdlrm-service`
  and `stagingdlrm-testharness`) need `uk.gov.moj.cpp.progression:progression-query-api:jar:raml:17.0.297`,
  which this offline environment's local repository has never downloaded. What *was* run and is
  evidence toward AC2: `mvn -o clean install -DskipITs -pl '!stagingdlrm-event/stagingdlrm-event-processor,!stagingdlrm-service,!stagingdlrm-testharness'`
  → `BUILD SUCCESS`, all 18 remaining reactor modules, 68 tests in `stagingdlrm-azure-functions`
  alone and none skipped anywhere (2026-09-04). This is not a defect introduced by this story — the
  same artifact resolution failure occurs on a clean `git stash` of every change in this PR — and CI's
  `centos8-j17` agent has artifactory access this sandbox does not.
- **Integration-tier items: none in this repo's Bucket A.** All 9 items land at the unit/component
  or build-time-assertion tier (per the depth model), so there is no 🟡-authored-not-executed row
  in this story — everything above is 🟢 or 📝.
