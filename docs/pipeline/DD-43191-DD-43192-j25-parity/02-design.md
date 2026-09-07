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

### DLRM-01 (primary) — built, verified, then withdrawn (see FR6)

**Module:** `stagingdlrm-azure-functions`. **Seam (verified):** `JsonSchemaValidator.validate()`
(`dlrm-flow-reference.md` §2.3 step 3d, §5) — Jackson's `objectMapper.readTree(payload)` first, an
explicit array-payload rejection **before** schema validation, then
`com.networknt.schema.JsonSchema.validate()` (hard-pinned 1.0.83, confirmed in `pom.xml:132` — not
exposed to J25).

A full design was implemented directly on the existing `JsonSchemaValidatorTest` (not a new class — it
already constructs both the case and manifest validators against the real production schema resources
and has a passing full-payload fixture) and run green on J17 (2026-09-04): four additions —

1. **Malformed JSON** → wrapped `RuntimeException` (cause: `JsonProcessingException`).
2. **Array payload** (`"[]"`) → the specific `RuntimeException("Json Schema validation failed")`.
3. **Duplicate object keys** → Jackson's `readTree` resolves to the **last** value silently — pinned via
   the manifest's `documentType` field.
4. **Numeric-literal table on `stagingdlrm.manifest.json`'s `documentType`** (`"type": "integer"`, **no**
   `maximum` configured), same seven literals. BC-13's equivalent table existed briefly (2026-09-04 to
   2026-09-07) and genuinely diverged from this one on several literals; that comparison is recorded in
   the checklist's "Notable J17 findings" as history.

**Removed 2026-09-07** on direct instruction ("this is not part of the java 25 upgrades"): unlike
BC-13/BC-21's catalog test, this was not a risk-absent finding — the seam is real and code-verified, and
the Jackson version genuinely does move (2.12.7→2.21.4) behind this exact `readTree` call. The
`stagingdlrm.case-submission.json` / `stagingdlrm.manifest.json` validation the test targeted was, in
this case, only ever run once (J17) rather than compared before/after upgrade, so its withdrawal leaves
this seam recorded but unpinned — see `01-requirements.md`'s FR6 and `docs/j25-parity-checklist.md`'s
DLRM-01 note. **Source-system keying:** per the parity-method ADR decision 7, the gate is not
source-system-keyed on this branch regardless — a single gate would have been pinned once had the test
survived.

### BC-11 (corrected from the outset) — built, verified, then withdrawn (see FR8)

**Module:** `stagingdlrm-azure-functions`. **Seam (verified):**
`StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload` (`dlrm-flow-reference.md` §2.4,
§5) — `createObjectBuilder().add("errorMessage", responseString)`, using
`uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder` (the exact framework helper the
parity-method ADR's decision 8 names), with `responseString` reachable as null on the error path
(§2.6's Path 3 — a direct outcome write when the error POST itself gets a 4xx).

One focused test was added to `StagingDlrmCommandHelperTest` (extending the existing class): call
`generateErrorMigratedCaseSubmissionPayload(...)` with `responseString == null` and assert
`NullPointerException`. Ran green on J17 (2026-09-04). No classpath/`ServiceLoader` inventory test was
ever built — per ADR decision 8, that would have pinned the wrong, superseded mechanism.

**Removed 2026-09-07**, same instruction as DLRM-01 ("this is not part of the java 25 upgrades"). Distinct
reasoning from DLRM-01, though: BC-11's corrected finding is itself classified "Refuted / parity" — the
NPE is a pre-existing, JDK-independent contract, not a J25-introduced divergence — so this test was
arguably never a candidate to catch an upgrade-caused behaviour shift in the first place, only a
latent-bug parity fact. See `01-requirements.md`'s FR8 and the checklist's BC-11 note.

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

### BC-20 — built, verified, then withdrawn: the mechanism can't detect its own risk (see FR10)

**Verified:** `kmodule.xml` declares `kbase name="COMMAND_API"
packages="uk.gov.moj.cpp.stagingdlrm.command.api.accesscontrol"`; the DRL has no explicit `package`
statement, so Drools infers one from the resource's directory path, which matches the kbase's `packages`
filter (confirmed indirectly: the existing allow/deny tests only make sense if the rules are genuinely
loaded and firing). This repo does **not** have the "`packages` names the resource folder, DRL declares a
different `package`" gotcha the fleet-wide guide's `system-doc-generator` entry warns about.

`AccessControlRuleCountTest` — named to match the fleet-wide convention (confirmed in all 13 fleet PRs
read for this story), not a bespoke BC-numbered name — was built loading
`KieServices.get().getKieClasspathContainer().getKieBase("COMMAND_API").getKiePackages()` (a
`StatelessKieSession` does not expose the `KieBase`) and asserting the summed rule count equals exactly
**2**, named by rule name. Ran green on J17 (2026-09-07).

**Removed the same day**, on re-verifying the test's own premise rather than trusting the fleet
convention it copied. BC-20's confirmed mechanism is a hand-rolled `kmodule.xml`/`.drl` loader inside
`BaseDroolsAccessControlTest.setup()` (the shared harness `AccessControlTest` extends) that silently
builds a zero-rule `KieBase` for `jar:`-resolved resources. Two things this test missed: (1) decompiling
this repo's actually-resolved `access-control-test-utils:17.104.1` shows `setup()` is still the original
safe one-liner (`getKieClasspathContainer()`) — the defective rewrite is confined to a J25-line fork of
that framework library this repo hasn't pulled in; (2) `AccessControlRuleCountTest` called
`getKieClasspathContainer()` **directly**, bypassing `BaseDroolsAccessControlTest` entirely — so even if
that dependency were bumped to the defective version, this test's own mechanism would never observe it,
because it doesn't exercise the harness's loading path at all. The design flaw: testing Drools's own
classpath-container API tells you nothing about a defect confined to a separate, hand-rolled loader
inside the test harness. A test worth keeping here would need to observe rule counts *through*
`BaseDroolsAccessControlTest` itself, not around it — not attempted in this pass. See
`01-requirements.md`'s FR10 and the checklist's BC-20 note.

### BC-12 — built, verified, then withdrawn by decision despite the risk being real (see FR11)

**Verified fresh:** `stagingdlrm-azure-functions/pom.xml` declares exactly 4 `org.jboss.resteasy`
artifacts, all `4.3.0.Final`, no `<scope>` (compile, the default) — the Function App is a standalone JAR
(`dlrm-flow-reference.md` §2: "runs outside the WildFly/JMS stack"), not a WAR, so the fleet-wide
"exclude bundled RESTEasy" fix does **not** apply here. `StagingDlrmCommandHelper` genuinely builds a
JAX-RS `Client` via `ClientBuilder` to POST to `stagingdlrm-command-api` — live production code, so this
isn't a theoretical exposure.

A JUnit test parsing `pom.xml` directly (DOM), asserting exactly 4 `org.jboss.resteasy` `<dependency>`
elements with none carrying a `<scope>` element, was built and run green on J17 (2026-09-04). **Removed
2026-09-07 on direct instruction** — unlike BC-13/BC-21's catalog test, this was not because the risk was
found absent; it was confirmed concrete first (see above and `docs/j25-parity-checklist.md`'s BC-12
note). The upgrade-mechanics ADR's decision 5 is now the only safeguard against the fleet-wide RESTEasy
`provided` sweep being wrongly applied to this module.

### BC-21 — none of the four generator families carries a test any more, each for its own reason

- **`catalog-generation-plugin`** — **not instrumented.** A schema-file-count vs. catalogue-entry-count
  test was authored and investigated: the plugin's file-discovery class (`generator-io-utils`'s
  `FileTreeScanner`, decompiled to check) genuinely bundles and calls `org.reflections.Reflections`, so
  the premise isn't unfounded. Decided against keeping it: the schema `.json` files this catalogue is
  generated from will still be present, under the same paths, through the J25 upgrade — there is no
  file-removal/relocation scenario for the generator to silently mishandle here, so a count-parity test
  has nothing live to guard against.
- **`messaging-client-generator-plugin`** (`stagingdlrm-command-api`) — built, verified, then withdrawn
  for a different reason from every other BC-21 sub-item. The generated
  `RemoteCommandApi2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand` carries one `@Handles`
  method per JSON schema under `stagingdlrm-command-handler`'s own `src/raml/json/schema/**` (4 and 4,
  confirmed) — a test asserting this count match via reflection (`Bc21MessagingClientGenerationParityTest`)
  was built and ran green on J17 (2026-09-04). **Removed 2026-09-07** after re-verifying the test's own
  stated premise — the same `reflections` 0.9.10→0.10.2 scanning-contract risk as
  `catalog-generation-plugin` — by decompiling every class in this generator's actual dependency chain
  (`messaging-client-generator`, `generators-commons`, `generators-subscription`, `generator-core`): zero
  references to `org.reflections` anywhere. This generator reads the command-handler's RAML artifact via
  an **explicit** Maven dependency (`classifier=raml`, transitives excluded) and parses it directly — no
  classpath reflection scan. The assertion itself was true (confirmed against the real RAML file: 4 media
  types, each `!include`-ing one schema), but the risk it was framed around was never real for this
  generator, so it wasn't pinning a J25-upgrade risk at all.
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
