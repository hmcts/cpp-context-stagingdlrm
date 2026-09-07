# Design — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 2 artefact (design). Source: [`00-input-brief.md`](./00-input-brief.md) and
> [`01-requirements.md`](./01-requirements.md) — both re-authored against the fleet-wide corrected guide,
> the tracker, and the two architecture references (`dlrm-flow-reference.md`, `material-file-flow.md`)
> from the start of this pass, so BC-11's corrected mechanism (parity-method ADR decision 8) is a
> starting premise here, not a mid-implementation discovery. Every seam below was independently
> re-verified against the actual code on `team/25.104.x` at commit `e5b7517`.

## Per-item design

### BC-13 — built, verified, then withdrawn (see FR5)

**Module:** `stagingdlrm-domain/stagingdlrm-domain-value-schema` (zero Java — confirmed:
`src/main/resources/json/**` only, per the parity-method ADR decision 7, and true again now).

A full unit-level design was implemented and run green on J17 (2026-09-04): `ClasspathSchemaClient`
(an everit `SchemaClient` resolving `$ref`s via the module's own generated `META-INF/schema_catalog.json`,
since several schema ids don't match their file's own name — e.g. `prosecutor.json` → `pcf-prosecutor.json`)
plus `Bc13SchemaValidationParityTest` (required/enum/anyOf/type on `case-details.json`, a numeric-literal
table on `migrated-hearing.json`'s `durationMinutes`, one parse-vs-validation pair). **Removed 2026-09-07**
per FR5's revision: the schema `.json` files and their content are not changing during the J25 upgrade,
so there is nothing live for a strictness test to catch here. Both files are recoverable from git history
(commit `a3fa641` on this branch) if a future story reopens BC-13 — see `01-requirements.md` design note 1
before rebuilding rather than assuming the old design still applies.

### DLRM-01 (primary) — Jackson parse behaviour at the Function App gate

**Module:** `stagingdlrm-azure-functions`. **Seam:** `JsonSchemaValidator.validate()`
(`dlrm-flow-reference.md` §2.3 step 3d, §5) — Jackson's `objectMapper.readTree(payload)` first, an
explicit array-payload rejection **before** schema validation, then
`com.networknt.schema.JsonSchema.validate()` (hard-pinned 1.0.83, confirmed in `pom.xml:132` — not
exposed to J25).

**Extend the existing `JsonSchemaValidatorTest`**, not a new class — it already constructs both the case
and manifest validators against the real production schema resources and has a passing full-payload
fixture. Four additions:

1. **Malformed JSON** → wrapped `RuntimeException` (cause: `JsonProcessingException`).
2. **Array payload** (`"[]"`) → the specific `RuntimeException("Json Schema validation failed")`.
3. **Duplicate object keys** → Jackson's `readTree` resolves to the **last** value silently — pinned via
   the manifest's `documentType` field.
4. **Numeric-literal table on `stagingdlrm.manifest.json`'s `documentType`** (`"type": "integer"`, **no**
   `maximum` configured), same seven literals. This table stands alone per FR7's revision — BC-13's
   equivalent table existed briefly (2026-09-04 to 2026-09-07) and genuinely diverged from this one on
   several literals; that comparison is recorded in the checklist's "Notable J17 findings" as history,
   not carried forward as a live requirement.

**Source-system keying:** per the parity-method ADR decision 7, the gate is not source-system-keyed on
this branch — FR6's "both source systems" clause does not apply; a single gate is pinned once.

### BC-11 (corrected from the outset) — `JsonObjectBuilder` null-value NPE parity

**Module:** `stagingdlrm-azure-functions`. **Seam (verified):**
`StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload` (`dlrm-flow-reference.md` §2.4,
§5) — `createObjectBuilder().add("errorMessage", responseString)`, using
`uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder` (the exact framework helper the
parity-method ADR's decision 8 names), with `responseString` reachable as null on the error path
(§2.6's Path 3 — a direct outcome write when the error POST itself gets a 4xx).

**Design:** one focused test on `StagingDlrmCommandHelperTest` (extend the existing class): call
`generateErrorMigratedCaseSubmissionPayload(...)` with `responseString == null` and assert
`NullPointerException`. No classpath/`ServiceLoader` inventory test — per ADR decision 8, that would pin
the wrong, superseded mechanism.

### BC-03 — close the access-control branch gap

**Verified:** the DRL declares exactly 2 rules; `AccessControlTest` covers only
`stagingdlrm.receive-migrated-case-submission`'s allow and deny paths.
`stagingdlrm.receive-error-migrated-case-submission` (the rule gating the error path
`dlrm-flow-reference.md` §2.5/§2.6 traces) has never been tested on any JDK. BC-03 itself
(Drools-recompilation-flips-allow/deny) is **Refuted** in both source documents — this closes a genuine,
pre-existing coverage gap that shares the ticket number, not a J25-risk mitigation.

**Design:** add `shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission` /
`shouldNotAllowSystemUserForErrorMigrateCaseSubmission` to `AccessControlTest`, mirroring the existing
pair exactly.

### BC-20 — prove the rule harness is not vacuous

**Verified:** `kmodule.xml` declares `kbase name="COMMAND_API"
packages="uk.gov.moj.cpp.stagingdlrm.command.api.accesscontrol"`; the DRL has no explicit `package`
statement, so Drools infers one from the resource's directory path, which matches the kbase's `packages`
filter (confirmed indirectly: the existing allow/deny tests only make sense if the rules are genuinely
loaded and firing). This repo does **not** have the "`packages` names the resource folder, DRL declares a
different `package`" gotcha the fleet-wide guide's `system-doc-generator` entry warns about.

**Design:** `AccessControlRuleCountTest` — named to match the fleet-wide convention (confirmed in all
13 fleet PRs read for this story), not a bespoke BC-numbered name — loading
`KieServices.get().getKieClasspathContainer().getKieBase("COMMAND_API").getKiePackages()` (a
`StatelessKieSession` does not expose the `KieBase`) and asserting the summed rule count equals exactly
**2**, named by rule name.

### BC-12 — pin the Function App's RESTEasy packaging expectation

**Verified fresh:** `stagingdlrm-azure-functions/pom.xml` declares exactly 4 `org.jboss.resteasy`
artifacts, all `4.3.0.Final`, no `<scope>` (compile, the default) — the Function App is a standalone JAR
(`dlrm-flow-reference.md` §2: "runs outside the WildFly/JMS stack"), not a WAR, so the fleet-wide
"exclude bundled RESTEasy" fix does **not** apply here.

**Design:** a JUnit test parses `pom.xml` directly (DOM) and asserts exactly 4 `org.jboss.resteasy`
`<dependency>` elements, none carrying a `<scope>` element. Version is deliberately not asserted.

### BC-21 — pin the generated-artefact inventory by contract, not manifest

- **`catalog-generation-plugin`** — **not instrumented.** A schema-file-count vs. catalogue-entry-count
  test was authored and investigated: the plugin's file-discovery class (`generator-io-utils`'s
  `FileTreeScanner`, decompiled to check) genuinely bundles and calls `org.reflections.Reflections`, so
  the premise isn't unfounded. Decided against keeping it: the schema `.json` files this catalogue is
  generated from will still be present, under the same paths, through the J25 upgrade — there is no
  file-removal/relocation scenario for the generator to silently mishandle here, so a count-parity test
  has nothing live to guard against.
- **`messaging-client-generator-plugin`** (`stagingdlrm-command-api`) — the generated
  `RemoteCommandApi2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand` carries one `@Handles`
  method per JSON schema under `stagingdlrm-command-handler`'s own `src/raml/json/schema/**` (4 and 4,
  confirmed). Assert the count match via reflection.
- **`pojo-generation-plugin`** — **not instrumented**: its `sourceDirectory` is `CLASSPATH`-wide,
  scanning third-party jars this repo doesn't own.
- **`rest-client-generator-plugin`** — **not instrumented**, for an environment reason: needs
  `pcfdlrm-command-api` and `progression-query-api` RAML artifacts this offline sandbox has never
  resolved (confirmed via a clean `git stash`).

### BC-07 — the Liquibase property set has no meaningful unit-level pin

**Verified fresh:** exactly three keys — `changelogFile`, `liquibase.hub.mode`, `liquibase.headless` — no
`searchPath`. `liquibase/stagingdlrm.xml` (the changelog `changelogFile` points at) is **empty** — no
`<changeSet>` elements — a separate, pre-existing fact this story does not fix. The properties file is
nonetheless genuinely deployed and executed: `docker/Dockerfile_stagingdlrm-service` bakes
`stagingdlrm-viewstore-liquibase.jar` into the image, and `docker/scripts/liquibase.sh` runs
`java -jar ... update` against a real Postgres database as part of container startup, aborting the whole
init script on failure.

**Design reconsidered:** a `java.util.Properties.load()` unit test asserting the key set was authored and
then removed. It is true on both J17 and J25 regardless of Liquibase's own version — it proves the file
has three keys, not that Liquibase 5 would reject one of them (BC-07's actual risk). The only test that
would mean anything here needs Liquibase itself to run against this properties file, which is IT-tier
(needs `CPP_DOCKER_DIR`) per this story's own depth model. Recorded in the checklist as a Bucket-B-style
check (⚪), not authored-not-executed (🟡) — there is no unit-tier version of this pin worth writing in
the meantime.

### BC-08 — record, do not touch the code at all

**Verified fresh:** the only `ZonedDateTime` in this repo is `stagingdlrm-event-processor`'s test helper
`ObjectBuilder.buildMetaData` — test scope, no main-code carrier anywhere. Checked further: it is never
serialized through Jackson in this repo's own tests either (`StagingDlrmEventProcessorTest` only ever
uses it as a Mockito stub return value) — no incidental J17 coverage exists to annotate. Design: no code
change of any kind. `ObjectBuilder.java` is otherwise untouched by this story, and a javadoc note there
would be noise on unrelated code rather than a pin. Record the finding and its reasoning in
`docs/j25-parity-checklist.md` only.

## Cross-cutting

- **No production code changes** — test, fixture, or documentation only (FR15/FR18/AC9). Neither
  `stagingdlrm-domain-value-schema` nor `stagingdlrm-viewstore-liquibase` carries a pom change any more
  either: both were given test-scope JUnit 5 (the latter also `com.github.everit-org.json-schema`) for a
  test that was subsequently removed from each, and the dependency additions were reverted along with it
  — both modules are zero-Java, zero-test-dependency, exactly as they were before this story.
- **`docs/j25-parity-checklist.md`** is written fresh against this design, including the BC-11
  correction, the BC-13 "format" gap, and the two uninstrumented BC-21 generator families.
- **ADR decision 8** (parity-method ADR) is the standing record of the BC-11 correction, already
  present on this branch before this design was written, mirrored in `cpp-context-prosecution-casefile-dlrm`.
