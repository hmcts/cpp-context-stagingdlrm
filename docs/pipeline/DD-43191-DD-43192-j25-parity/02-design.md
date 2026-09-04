# Design — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 2 artefact (design). Source: [`01-requirements.md`](./01-requirements.md), the parity-method
> ADR (as amended by its **decision 8**, added by this design), and
> [`Parity+Testing+Java17+-_+Java25.pdf`](./Parity+Testing+Java17+-_+Java25.pdf) — the fleet-wide,
> empirically-corrected guide. Every seam below was re-verified against the actual code on
> `team/25.104.x` on 2026-09-04, not assumed from the requirements text or any prior attempt at this
> story.

## Why this design starts with a correction, not a class list

Re-deriving this story from scratch (not reusing any earlier DD-43192 implementation) surfaced that
**BC-11's own definition had moved since `01-requirements.md` was written.** The original hypothesis
(FR8: a JSON-P `ServiceLoader` provider collision, provable by a classpath-resource count) was
superseded on 2026-08-26 by fleet-wide empirical work: the real, verified BC-11 mechanism is that
`uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder().add(key, null)` throws
`NullPointerException` identically on J17/glassfish and J25/Parsson — a pre-existing latent-bug parity,
not a J25 regression. This is recorded as **decision 8** in `docs/pipeline/adrs/DD-43191-j25-parity-method.md`
(amended in both DLRM repos, per CLAUDE.md's mirroring rule, as part of this design).

The other 8 items were re-verified fresh against the current code and the PDF's corresponding catalogue
entries and needed no correction (evidence in the per-item sections below). One item (BC-13's "format"
constraint class) turned out to have no binding site in this repo's own schema authorship — recorded as
a gap, not fabricated.

## Instrument choice (requirements note 3)

One instrument for every build-time-assertion item (BC-11, BC-12, BC-21 ×2, BC-07): a plain JUnit 5 test
class, no `maven-enforcer` rule, no script. Rationale, unchanged from the reasoning `mvn test` already
gives every other item in this story: it runs in the same `mvn test` pass as the unit-tier items, needs
no new plugin, and its failure output is a normal stack trace rather than an enforcer report a developer
has to learn to read. `maven-enforcer` is already used elsewhere in this reactor for dependency-version
rules; reusing it here would mean two different failure-reporting idioms for the same story.

## Per-item design

### BC-13 (primary) — schema-validation strictness at the catalogue tier

**Module:** `stagingdlrm-domain/stagingdlrm-domain-value-schema` (zero Java today — confirmed:
`src/main/resources/json/**` only, per ADR decision 7).

**The seam.** `catalog-generation-plugin` already generates `META-INF/schema_catalog.json` from this
module's own `src/main/resources/json/schema/**` at `generate-sources` (verified: 2 groups —
`json/schema/` baseLocation, 18 schemas; `json/schema/migrated/` baseLocation, 12 schemas; 30 total,
matching the 30 `.json` files on disk). Each catalog entry pairs a schema's **declared `id`** with its
**`location`** relative to its group's `baseLocation` — and several ids do **not** match their file's own
name (`http://.../prosecutor.json` → `pcf-prosecutor.json`; `.../week-commencing-date.json` →
`migrated-week-commencing-date.json`; `.../listed-defendant.json` → `migrated-listed-defendant.json`).
Any `$ref` resolver that guesses a classpath path from the URI's filename (rather than reading the
catalogue) will silently fail to load `case-details.json` or `migrated-hearing.json` — both of which
`$ref` a mismatched id.

**Design: `ClasspathSchemaClient` reads the generated catalogue, not a hand-written URI map.**
Implements everit's `SchemaClient` (`InputStream get(String url)`):

1. On construction, reads `META-INF/schema_catalog.json` off the test classpath (the module's own build
   already produces it — no new plugin execution needed) and builds `id → classpath path`
   (`group.baseLocation + schema.location`) for every entry across every group.
2. Adds one further entry by hand:
   `http://justice.gov.uk/domain/core/common/definitions.json → json/schema/definitions.json` — this
   file is **not** in this module's own catalogue; it is bundled inside the `common-core-domain` compile
   dependency (verified: `json/schema/definitions.json` at that exact path inside
   `common-core-domain-17.104.4.jar`). Framework-owned, not this repo's to catalogue, but needed to fully
   resolve `case-details.json`'s `dateReceived`/`dateOfSending`/`dateOfCommittal` `$ref`s.
3. `get(url)` looks the id up and returns
   `Thread.currentThread().getContextClassLoader().getResourceAsStream(path)`; a miss throws with the
   requesting id, not a generic 404-style everit error, since a class-loader miss here means the
   catalogue and this test have drifted, not that the payload is invalid.

**Test fixture: `case-details.json` (type, enum, required, anyOf) + `migrated-hearing.json` (the numeric
table).** Both are self-contained once the resolver above is in place:

| Constraint class | Field | Accept | Reject |
|---|---|---|---|
| `required` | any of `case-details.json`'s 8 required fields | all present | omit `prosecutorCaseReference` |
| `enum` | `initiationCode` (`case-details.json`, only `"O"` valid) | `"O"` | `"X"` |
| `anyOf` | neither `dateOfCommittal` nor `dateOfSending` (`case-details.json`) | either present | neither present |
| `type` | `retrialIndicator` (`case-details.json`, boolean) | `false` | `"false"` (string) |

**Numeric-literal table — `migrated-hearing.json`'s `durationMinutes`** (`"type": "integer", "maximum":
99999`, confirmed on disk): `0`, `007`, `01`, `.5`, `10.0`, `1e3`, `12345678901234567890`, each with a
**named** expected outcome (ACCEPT/REJECT + reason), not a bare "does not throw" — per FR5/AC3. This is
everit + `org.json` underneath (the BC-13 seam), separate from DLRM-01's table (FR7 — different parser,
different exposure).

**Parse vs. validation distinction (FR5.3):** a schema-loading failure (a genuinely malformed schema
resource — not exercised by production payloads) is asserted as a distinct outcome from a well-formed
payload failing a validation constraint, using two different fixtures rather than one test conflating
both.

**Gap, recorded not fabricated:** `case-details.json` has no `"format"` keyword of its own; the only
`format`-bearing definitions this schema set reaches are inside `common-core-domain`'s `definitions.json`
(date/uuid), which is framework-owned. FR5's "format" constraint class has no binding site authored in
this repo — recorded as a gap in the checklist, not tested against a schema this repo doesn't own.

### DLRM-01 (primary) — Jackson parse behaviour at the Function App gate

**Module:** `stagingdlrm-azure-functions`. **Seam:** `JsonSchemaValidator.validate()` (verified,
`.../azure/validator/JsonSchemaValidator.java`): `objectMapper.readTree(payload)` first (Jackson —
DLRM-01's exposure), an explicit array-payload rejection **before** schema validation
(`jsonNode.isArray()` → `RuntimeException`), then `com.networknt.schema.JsonSchema.validate()` (hard-
pinned 1.0.83, confirmed in `pom.xml:132` — not exposed to J25).

**Extend the existing `JsonSchemaValidatorTest`**, not a new class — it already has a working
`@BeforeEach` constructing both the case and manifest validators against the real production schema
resources (`stagingdlrm.case-submission.json`, `stagingdlrm.manifest.json`), and a passing full-payload
fixture proving those resources resolve correctly end-to-end. Extending it keeps FR6's four pins next to
the coverage they extend rather than duplicating the harness setup:

1. **Malformed JSON** → asserts the wrapped `RuntimeException` (cause: `JsonProcessingException`).
2. **Array payload** (`"[]"`) → asserts the specific `RuntimeException("Json Schema validation failed")`
   thrown before schema validation runs at all.
3. **Duplicate object keys** → Jackson's `readTree` resolves to the **last** value silently (no
   exception); pin that behaviour explicitly via the manifest's `documentType` field, not "does not
   throw".
4. **Numeric-literal table on `stagingdlrm.manifest.json`'s `documentType`** (`"type": "integer"`, **no**
   `maximum` — confirmed on disk, unlike BC-13's `durationMinutes`): the same seven literals as BC-13,
   each with a named outcome. This is where FR7's "separate tables, separate parsers" matters most: an
   oversized integer literal (`12345678901234567890`) is expected to **accept** here (no bound to trip)
   while BC-13's everit tier **rejects** the same literal on type grounds — two different libraries, two
   opposite outcomes for the same input, which is exactly why a shared table would hide which tier moved.

**Source-system keying:** per ADR decision 7, the gate is not source-system-keyed on this branch — FR6's
"both source systems" clause does not apply; a single gate is pinned once.

### BC-11 (corrected) — `JsonObjectBuilder` null-value NPE parity

**Module:** `stagingdlrm-azure-functions`. **Seam (verified):**
`StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload` —

```java
import static uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder;
...
errorMigratedCaseSubmissionJsonBuilder.add("errorMessage", responseString);
```

`responseString` is a parameter threaded from `TimerTriggerJava`'s error path and is reachable as null.
This is the exact framework helper the corrected finding names, at a real call site with a nullable
value — not a hypothetical.

**Design:** one focused test on `StagingDlrmCommandHelperTest` (existing test class — extend it):
call `generateErrorMigratedCaseSubmissionPayload(...)` with `responseString == null` and assert it throws
`NullPointerException` — pinning the parity (it throws identically pre- and post-upgrade), not
"succeeds". No classpath/`ServiceLoader` inventory test is written; per ADR decision 8, that would pin
the wrong mechanism. The `javax.json` coordinate inventory across the 5 modules (still true classpath
fact) is noted in the checklist as background, explicitly labelled **not** the BC-11 assertion.

### BC-03 — close the access-control branch gap

**Module:** `stagingdlrm-command/stagingdlrm-command-api`. **Verified:** the DRL
(`command-migrate-case-submission-api.drl`) declares exactly 2 rules; `AccessControlTest` (existing)
covers only `stagingdlrm.receive-migrated-case-submission`'s allow and deny paths.
`stagingdlrm.receive-error-migrated-case-submission` has never been tested on any JDK. Per the PDF, BC-03
itself (the Drools-recompilation-flips-allow/deny hypothesis) is **Refuted** fleet-wide — this is not
about mitigating a J25 risk, it is closing a genuine, pre-existing coverage gap that happens to share the
ticket number (both the original report and the PDF agree BC-03 is refuted; `01-requirements.md`'s FR9
already frames this correctly as "a genuine J17 coverage fix as well as a parity pin").

**Design:** add `shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission` /
`shouldNotAllowSystemUserForErrorMigrateCaseSubmission` to `AccessControlTest`, mirroring the existing
pair exactly (same `createActionFor`/mock shape), targeting
`"stagingdlrm.receive-error-migrated-case-submission"`.

### BC-20 — prove the rule harness is not vacuous

**Verified:** `kmodule.xml` declares `kbase name="COMMAND_API" packages="uk.gov.moj.cpp.stagingdlrm.command.api.accesscontrol"`;
the DRL has no explicit `package` statement, so Drools infers one from the resource's directory path —
which does match the kbase's `packages` filter (confirmed indirectly: the existing allow/deny tests only
make sense if the rules are genuinely loaded and firing, which they demonstrably are). This repo does
**not** have the "`packages` names the resource folder, DRL declares a different `package`" gotcha the
PDF's `system-doc-generator` entry warns about — recorded as a checked-and-clear note, not assumed.

**Design:** `Bc20RuleHarnessParityTest` in `command-api`, loading
`KieServices.get().getKieClasspathContainer().getKieBase("COMMAND_API").getKiePackages()` directly
(per the PDF's reusable lesson — a `StatelessKieSession` does not expose the `KieBase`) and asserting the
summed rule count equals exactly **2**, named by rule name, not just `> 0` — a stronger pin than the
minimum FR10 asks for, and cheap since the rule names are already known and stable.

### BC-12 — pin the Function App's RESTEasy packaging expectation

**Verified fresh:** `stagingdlrm-azure-functions/pom.xml` declares exactly 4 `org.jboss.resteasy`
artifacts (`resteasy-client`, `resteasy-jaxb-provider`, `resteasy-jackson2-provider`,
`resteasy-multipart-provider`), all `4.3.0.Final`, no `<scope>` element (compile, the default) — unlike a
WAR, the Function App has no container to supply these at runtime, so the fleet-wide "exclude bundled
RESTEasy" J25 fix pattern does **not** apply here; applying it would be the wrong fix for this module and
produce a runtime `NoClassDefFoundError` in Azure (per the upgrade-mechanics ADR decision 5).

**Design:** a single JUnit test parses `stagingdlrm-azure-functions/pom.xml` directly (as XML, via
`javax.xml`/DOM — no Maven model dependency needed for four dependency elements) and asserts exactly 4
`org.jboss.resteasy` `<dependency>` elements, none carrying a `<scope>` element. Version is deliberately
**not** asserted — the upgrade story's Jakarta-REST engine swap will legitimately move it; pinning the
version would fail this test for the wrong reason at upgrade time.

### BC-21 — pin the generated-artefact inventory by contract, not manifest

Two of the four generator families that run in this repo have a clean, low-maintenance contract
assertion; the other two do not have one worth building (per the requirements' own risk note against a
maintenance-burden manifest):

- **`catalog-generation-plugin`** (`stagingdlrm-domain-value-schema`) — verified: `META-INF/schema_catalog.json`
  already has exactly 30 schema entries across its 2 groups, matching the 30 `.json` files under
  `src/main/resources/json/schema/**` on disk. Assert **schema-file-count == catalog-entry-count**, computed
  from both sides at test time (a `Files.walk` count and a parsed-catalog count) rather than a hard-coded
  `30` — the contract survives a future schema being added or removed; a literal count would not.
- **`messaging-client-generator-plugin`** (`stagingdlrm-command-api`) — verified: the generated
  `RemoteCommandApi2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand` carries exactly 4
  `@Handles`-annotated methods, one per JSON schema under `stagingdlrm-command-handler`'s own
  `src/raml/json/schema/**` (also 4, confirmed on disk). Assert **RAML-schema-count ==
  `@Handles`-method-count** via reflection on the generated class, same contract-not-manifest shape.
- **`pojo-generation-plugin`** (`stagingdlrm-domain-event`) — **not instrumented.** Verified: its
  `sourceDirectory` is `CLASSPATH`-wide, scanning `common-core-domain` and
  `criminal-court-public-model` as well as this repo's own schemas — a count here would fail for
  third-party jars this repo does not own, exactly the maintenance burden the requirements warn against.
  Left to its large existing incidental coverage (the module fails to compile if a referenced generated
  type goes missing).
- **`rest-client-generator-plugin`** (`stagingdlrm-event-processor`) — **not instrumented**, for an
  environment reason, not a design one: it needs `pcfdlrm-command-api` and `progression-query-api` RAML
  artifacts this offline sandbox has never resolved (confirmed: same failure on a clean `git stash`).
  Recorded as a gap with the exact missing coordinates, not silently skipped.

### BC-07 — pin the Liquibase property set

**Verified fresh:** `stagingdlrm-viewstore-liquibase/src/main/resources/liquibase.properties` contains
exactly three keys — `changelogFile`, `liquibase.hub.mode`, `liquibase.headless` — no `searchPath` (the
PDF's other named offender is simply absent here, not a gap in this repo's testing). Design: one test
loading the file as `java.util.Properties`, asserting the key set is exactly
`{changelogFile, liquibase.hub.mode, liquibase.headless}` and their J17 values, so an unsupported key
added later fails in `mvn test` rather than a K8s pre-install job.

### BC-08 — annotate, do not author

**Verified fresh:** the only `ZonedDateTime` in this repo is
`stagingdlrm-event-processor`'s test helper `ObjectBuilder.buildMetaData` — test scope, not product
code; no main-code carrier exists anywhere in the repo (a fresh repo-wide grep, not inherited from any
prior finding). Design: a javadoc annotation on the helper naming BC-08 and why no new test is
warranted; the existing `StagingDlrmEventProcessorTest` suite already exercises it incidentally.

## Cross-cutting

- **No production code changes.** Every item above is test, fixture, pom-test-dependency, or
  documentation only, matching FR15/FR18/AC9.
- **`stagingdlrm-domain-value-schema`'s `pom.xml`** needs test-scope dependencies it does not currently
  have (the module has zero Java today): `com.github.everit-org.json-schema:org.everit.json.schema` and
  JUnit 5 (`junit-jupiter-api`, `junit-jupiter-engine`). Both coordinates are already proven resolvable
  offline in this environment — `stagingdlrm-viewstore-persistence`'s `pom.xml` already declares the
  same everit coordinate (unused there, but proof the parent BOM manages a version for it without one
  being pinned locally).
- **`docs/j25-parity-checklist.md`** is written fresh against this design (FR17/AC7), including the
  BC-11 correction, the BC-13 "format" gap, and the two uninstrumented BC-21 generator families.
