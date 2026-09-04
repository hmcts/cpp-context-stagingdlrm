# Design — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 2 artefact (design). Source: [`00-input-brief.md`](./00-input-brief.md) and
> [`01-requirements.md`](./01-requirements.md) — both re-authored against the fleet-wide corrected guide,
> the tracker, and the two architecture references (`dlrm-flow-reference.md`, `material-file-flow.md`)
> from the start of this pass, so BC-11's corrected mechanism (parity-method ADR decision 8) is a
> starting premise here, not a mid-implementation discovery. Every seam below was independently
> re-verified against the actual code on `team/25.104.x` at commit `e5b7517`.

## Per-item design

### BC-13 (primary) — schema-validation strictness at the catalogue tier

**Module:** `stagingdlrm-domain/stagingdlrm-domain-value-schema` (zero Java today — confirmed:
`src/main/resources/json/**` only, per the parity-method ADR decision 7). This is **not** the same
schema set the Function App validates against — `dlrm-flow-reference.md` §6 lists the func-app's own
flat, separately-maintained copies under `stagingdlrm-azure-functions/src/main/resources/`; DLRM-01
(below) covers those.

**The seam.** `catalog-generation-plugin` generates `META-INF/schema_catalog.json` from this module's own
`src/main/resources/json/schema/**` at `generate-sources` (verified: 2 groups — `json/schema/`
baseLocation, 18 schemas; `json/schema/migrated/` baseLocation, 12 schemas; 30 total, matching the 30
`.json` files on disk). Each catalog entry pairs a schema's **declared `id`** with its **`location`**
relative to its group's `baseLocation` — and several ids do **not** match their file's own name
(`http://.../prosecutor.json` → `pcf-prosecutor.json`; `.../week-commencing-date.json` →
`migrated-week-commencing-date.json`; `.../listed-defendant.json` → `migrated-listed-defendant.json`).
Any `$ref` resolver that guesses a classpath path from the URI's filename (rather than reading the
catalogue) will silently fail to load `case-details.json` or `migrated-hearing.json` — both of which
`$ref` a mismatched id.

**Design: `ClasspathSchemaClient` reads the generated catalogue, not a hand-written URI map.**
Implements everit's `SchemaClient` (`InputStream get(String url)`):

1. On construction, reads `META-INF/schema_catalog.json` off the test classpath and builds
   `id → classpath path` (`group.baseLocation + schema.location`) for every entry across every group.
2. Adds one further entry by hand:
   `http://justice.gov.uk/domain/core/common/definitions.json → json/schema/definitions.json` — bundled
   inside the `common-core-domain` compile dependency (verified inside
   `common-core-domain-17.104.4.jar`), not this module's own catalogue, but needed to fully resolve
   `case-details.json`'s date `$ref`s.
3. `get(url)` looks the id up and returns a classloader resource stream; a miss throws naming the
   requesting id.

**Test fixture: `case-details.json` (type, enum, required, anyOf) + `migrated-hearing.json` (the numeric
table).** `case-details.json` covers required (`prosecutorCaseReference`, `originatingOrganisation`,
`initiationCode`, `prosecutor`, `dateReceived`, `retrialIndicator`, `receiptType`, `receivingCourt`) —
the exact required-field set `dlrm-flow-reference.md` §6 also lists — enum (`initiationCode` only
accepts `"O"`), anyOf (`dateOfCommittal` or `dateOfSending`), type (`retrialIndicator` boolean).

**Numeric-literal table — `migrated-hearing.json`'s `durationMinutes`** (`"type": "integer", "maximum":
99999`, confirmed on disk): `0`, `007`, `01`, `.5`, `10.0`, `1e3`, `12345678901234567890`, each with a
named expected outcome (FR5/AC3).

**Parse vs. validation distinction (FR5.3):** a syntactically malformed document fails during `org.json`'s
own parse, asserted as a distinct outcome from a well-formed payload failing a schema constraint.

**Gap, recorded not fabricated:** `case-details.json` has no `"format"` keyword of its own; the only
`format`-bearing definitions this schema set reaches are inside `common-core-domain`'s `definitions.json`
(date/uuid), framework-owned.

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
   `maximum` — confirmed on disk, unlike BC-13's `durationMinutes`), same seven literals. Per FR7, this
   table and BC-13's are asserted separately and are expected to diverge on several literals.

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

**Design:** `Bc20RuleHarnessParityTest`, loading
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

- **`catalog-generation-plugin`** — schema-file-count on disk == catalogue-entry-count, computed both
  ways at test time.
- **`messaging-client-generator-plugin`** (`stagingdlrm-command-api`) — the generated
  `RemoteCommandApi2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand` carries one `@Handles`
  method per JSON schema under `stagingdlrm-command-handler`'s own `src/raml/json/schema/**` (4 and 4,
  confirmed). Assert the count match via reflection.
- **`pojo-generation-plugin`** — **not instrumented**: its `sourceDirectory` is `CLASSPATH`-wide,
  scanning third-party jars this repo doesn't own.
- **`rest-client-generator-plugin`** — **not instrumented**, for an environment reason: needs
  `pcfdlrm-command-api` and `progression-query-api` RAML artifacts this offline sandbox has never
  resolved (confirmed via a clean `git stash`).

### BC-07 — pin the Liquibase property set

**Verified fresh:** exactly three keys — `changelogFile`, `liquibase.hub.mode`, `liquibase.headless` — no
`searchPath`. Design: load as `java.util.Properties`, assert the exact key set and J17 values.

### BC-08 — annotate, do not author

**Verified fresh:** the only `ZonedDateTime` in this repo is `stagingdlrm-event-processor`'s test helper
`ObjectBuilder.buildMetaData` — test scope, no main-code carrier anywhere. Design: a javadoc annotation
naming BC-08 and why no new test is warranted.

## Cross-cutting

- **No production code changes** — test, fixture, pom-test-dependency, or documentation only
  (FR15/FR18/AC9).
- **`stagingdlrm-domain-value-schema`'s and `stagingdlrm-viewstore-liquibase`'s `pom.xml`** each need
  test-scope JUnit 5 added (both modules have zero Java today); `stagingdlrm-domain-value-schema` also
  needs `com.github.everit-org.json-schema:org.everit.json.schema` — already proven resolvable offline
  (`stagingdlrm-viewstore-persistence`'s `pom.xml` already declares the same coordinate, unused there,
  with no local version pin, proving the parent BOM manages one).
- **`docs/j25-parity-checklist.md`** is written fresh against this design, including the BC-11
  correction, the BC-13 "format" gap, and the two uninstrumented BC-21 generator families.
- **ADR decision 8** (parity-method ADR) is the standing record of the BC-11 correction, already
  present on this branch before this design was written, mirrored in `cpp-context-prosecution-casefile-dlrm`.
