# User Stories — DD-43192: J17→J25 behavioural-parity tests for stagingDLRM

> Stage 3 artefact (stories). Source: [`01-requirements.md`](./01-requirements.md) and
> [`02-design.md`](./02-design.md).
>
> **All stories below are sub-slices of the single Jira story [DD-43192](https://tools.hmcts.net/jira/browse/DD-43192)** —
> per `00-input-brief.md`'s decision table, one Jira key covers this whole stage, so the items below
> are labelled `DD-43192.A`–`DD-43192.J` for sprint-planning and traceability purposes only; they are
> not separate Jira tickets and should not be created as such.
>
> **Status: all Done.** This is a retrospective decomposition of work already delivered on branch
> `DD-43192-j25-parity-tests` ([PR #51](https://github.com/hmcts/cpp-context-stagingdlrm/pull/51)
> against `team/25.104.x`), written to make the single story's scope legible as independently
> reviewable increments and to give the sibling DD-43194 (pcfdlrm) pipeline a concrete slicing
> precedent — see decision 6 of the parity-method ADR on why pcfdlrm's own primary item (BC-08) will
> not slice the same way. Each story cites the actual green J17 run recorded in
> [`docs/j25-parity-checklist.md`](../../j25-parity-checklist.md) as its evidence of "done", in place
> of a sprint board link.

## Sequencing note

Per `01-requirements.md`'s design-stage note 5 ("sequence the two primary items first... if the story
has to be cut, it should be cut from the back"), stories are ordered with BC-13 and DLRM-01 first. That
ordering was followed during actual delivery, not just planned - see `02-design.md`.

---

## DD-43192.A — Pin schema-validation strictness at the catalogue tier (BC-13, primary)

**As a** developer who will shortly move stagingDLRM to Java 25,
**I want** the `org.json`/everit schema-catalogue validator's current behaviour pinned for the
migrated-case-submission schema set — every numeric-literal edge case, one accept/reject pair per
constraint class, and the parse-vs-validation distinction —
**so that** the J25 upgrade's `org.json` 20231013→20251224 bump has a regression gate that would
actually fail if catalogue-tier validation strictness shifted underneath it.

### Acceptance criteria
- [x] A numeric-literal table exists for `0`, `007`, `01`, `.5`, `10.0`, `1e3`,
  `12345678901234567890`, each with a named expected outcome (not a bare "does not throw"). — FR5.1, AC3
- [x] The accept path for a valid payload, and the reject path with its validation message, are
  pinned for each of type / enum / required / format / `anyOf`. — FR5.2
- [x] Parse failure and validation failure are two distinct, separately-asserted outcomes. — FR5.3
- [x] The test is authored against, and executed on, J17 — not authored-only. — FR1
- [x] The test names BC-13 explicitly. — FR2, AC8
- [x] No `javax`→`jakarta` change, no J25-conditional branch. — FR4

### Delivered
`ClasspathSchemaClient` + `MigratedCaseSubmissionSchemaParityTest` (15 tests),
`stagingdlrm-domain-value-schema` — a module with zero prior Java on this branch. See
`02-design.md`'s BC-13 section for the `$ref`-resolution design and why the numeric table targets
`durationMinutes`.

**Evidence:** `mvn -o test -pl stagingdlrm-domain/stagingdlrm-domain-value-schema -Dtest=MigratedCaseSubmissionSchemaParityTest` → `Tests run: 15, Failures: 0` (2026-09-04).

---

## DD-43192.B — Pin parse behaviour at the Function App gate (DLRM-01, primary)

**As a** developer who will shortly move stagingDLRM to Java 25,
**I want** the Function App's Jackson-backed `ObjectMapper.readTree` parse behaviour pinned separately
from the catalogue tier's,
**so that** the Jackson 2.12.7→2.21.4 bump behind the gate — which uses a different, hard-pinned
schema-validation library with different upgrade exposure — has its own regression gate rather than
sharing one that would hide which library actually moved.

### Acceptance criteria
- [x] A separate numeric-literal table exists for the same seven values as DD-43192.A, over the
  gate's own parser — not a shared table. — FR6, FR7
- [x] Malformed JSON, the array-payload rejection performed before schema validation, and duplicate
  object keys are all pinned. — FR6
- [x] The gate is pinned as it stands on this branch — not keyed by source system (parity-method ADR
  decision 7 supersedes the original "both source systems" framing). — FR6 (as amended)
- [x] The test is executed on J17. — FR1
- [x] The test names DLRM-01 explicitly. — FR2, AC8

### Delivered
Additions to the pre-existing `JsonSchemaValidatorTest`, `stagingdlrm-azure-functions`. See
`02-design.md`'s DLRM-01 section for why one class (not two) and why `documentType` (not
`durationMinutes`) is the numeric-table target, plus the full seven-literal divergence table against
DD-43192.A's outcomes.

**Evidence:** `mvn -o test -pl stagingdlrm-azure-functions -Dtest=JsonSchemaValidatorTest` → `Tests run: 15, Failures: 0` (module total 68/68) (2026-09-04).

---

## DD-43192.C — Pin JSON-P provider resolution across the affected modules (BC-11)

**As a** developer who will shortly move stagingDLRM to Java 25,
**I want** exactly one JSON-P provider's resolvability, and its identity, pinned on every module's
classpath that carries `javax.json`,
**so that** the glassfish→Parsson `ServiceLoader` collision risk the upgrade introduces has a
regression gate that catches an ambiguous resolution, not just "a provider resolved to something".

### Acceptance criteria
- [x] Each of the five affected modules (`stagingdlrm-command-handler`, `stagingdlrm-event-listener`,
  `stagingdlrm-domain-event`, `stagingdlrm-domain-aggregate`, `stagingdlrm-azure-functions`) asserts
  exactly one resolvable JSON-P provider, and names it. — FR8, AC5
- [x] The assertion is a genuine count (classpath-resource count), not merely "a factory call
  succeeded" — the report notes that a collision still lets one provider win, so a
  succeeds-without-erroring test proves nothing on its own. — FR8

### Delivered
`Bc11JsonProviderParityTest`, one copy per module (`uk.gov.moj.cpp.stagingdlrm.parity` package),
javadoc tailored to each module's actual classpath makeup rather than copy-pasted verbatim. See
`02-design.md`'s BC-11 section for the mid-flight design correction from a `ServiceLoader` count (which
J17 shows is zero) to a classpath-resource count.

**Evidence:** `mvn -o test -pl <all 5 modules> -Dtest=Bc11JsonProviderParityTest` → `Tests run: 3` per module, all green (2026-09-04).

---

## DD-43192.D — Close the access-control branch gap (BC-03)

**As a** developer relying on this repo's access-control test suite as a safety net through the J25
upgrade,
**I want** both rules in `command-migrate-case-submission-api.drl` — not just the first — covered by
an allow case and a deny case,
**so that** the Drools 7→10 upgrade can't silently flip the untested rule's outcome without a test
noticing.

### Acceptance criteria
- [x] `stagingdlrm.receive-error-migrated-case-submission` has a passing allow case and a passing
  deny case, matching the existing pattern for the first rule. — FR9, AC4
- [x] This is recorded as a genuine coverage fix, not only a parity pin — the second rule had never
  been tested on any JDK. — FR9

### Delivered
Two new test methods on the existing `AccessControlTest`, `stagingdlrm-command-api`.

**Evidence:** `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=AccessControlTest` → `Tests run: 4, Failures: 0` (2026-09-04).

---

## DD-43192.E — Prove the Drools rule harness is not vacuous (BC-20)

**As a** developer relying on `AccessControlTest`'s deny assertions to mean something,
**I want** a hard non-zero (and exact) rule-count assertion against the harness's own knowledge base,
**so that** a J25 zero-rule load can't present as a passing deny test, indistinguishable from a
genuine BC-03 allow/deny flip.

### Acceptance criteria
- [x] The command-API knowledge base's loaded rule set is asserted directly, against the same
  `KieSession` every other test in the class depends on — not a second, independently-built
  `KieContainer`. — FR10, AC4
- [x] The assertion does not derive a kbase name from the ksession name (kbase `COMMAND_API` ≠
  ksession `COMMAND_API_SESSION` is the fleet convention, not an exception). — FR10

### Delivered
`Bc20RuleHarnessParityTest`, extending `BaseDroolsAccessControlTest` directly,
`stagingdlrm-command-api`. **This story's design was corrected once, at code review** — see
`02-design.md`'s BC-20 section for the full account of the first (wrong-container) version and why it
would not have caught its own target failure mode.

**Evidence:** `mvn -o test -pl stagingdlrm-command/stagingdlrm-command-api -Dtest=Bc20RuleHarnessParityTest` → `Tests run: 1, Failures: 0` (2026-09-04).

---

## DD-43192.F — Pin the Function App's RESTEasy packaging expectation (BC-12)

**As a** developer who might otherwise apply the fleet-wide RESTEasy `provided` + `packagingExcludes`
fix uniformly across all contexts,
**I want** the Function App's requirement to keep bundling its own RESTEasy jars recorded as a
build-time assertion,
**so that** applying that fix here by habit turns into a caught test failure instead of a runtime
`NoClassDefFoundError` in Azure.

### Acceptance criteria
- [x] Exactly four `org.jboss.resteasy` dependencies are asserted, at compile scope (no `<scope>`
  element). — FR11
- [x] The assertion is a build-time fact read from the module's own `pom.xml`, not a test that boots a
  container. — FR11, parity-method ADR decision 2

### Delivered
`BC12RestEasyPackagingParityTest`, `stagingdlrm-azure-functions`. Version is deliberately not pinned —
see `02-design.md`'s BC-12 section for why.

**Evidence:** `mvn -o test -pl stagingdlrm-azure-functions -Dtest=BC12RestEasyPackagingParityTest` → `Tests run: 1, Failures: 0` (2026-09-04).

---

## DD-43192.G — Pin the generated-artefact inventory as a contract, not a manifest (BC-21)

**As a** developer relying on generated schema catalogues and messaging clients continuing to be
produced correctly after the `reflections` 0.9.10→0.10.2 scanning-contract change,
**I want** the generator's output pinned as a count-derived-from-source contract,
**so that** a silently smaller generated set fails at build time rather than surfacing later as a
missing bean.

### Acceptance criteria
- [x] The set of generated types the build is expected to produce is asserted as a contract (a count,
  or the presence of the types a named schema should yield), not a hard-coded literal list. — FR12
- [x] Instrumented for at least the generator families this repo's own schema/RAML sources drive
  directly. — FR12

### Delivered
`SchemaCatalogGenerationParityTest` (`catalog-generation-plugin`, `stagingdlrm-domain-value-schema`)
and `Bc21MessagingClientGenerationParityTest` (`messaging-client-generator-plugin`,
`stagingdlrm-command-api`) — 2 of the 4 generator families that run in this repo. The other two
(`pojo-generation-plugin`'s CLASSPATH-wide scan; `rest-client-generator-plugin`, blocked on an
offline-unavailable artifact) are recorded as explicit gaps in the checklist, not silently dropped —
see `02-design.md`'s BC-21 section for the reasoning behind leaving them uninstrumented.

**Evidence:** `mvn -o test -pl stagingdlrm-domain/stagingdlrm-domain-value-schema,stagingdlrm-command/stagingdlrm-command-api -Dtest=SchemaCatalogGenerationParityTest,Bc21MessagingClientGenerationParityTest` → both green (2026-09-04).

---

## DD-43192.H — Pin the Liquibase property set (BC-07)

**As a** developer who will run this branch's Liquibase migrations against version 5 in the upgrade
story,
**I want** the exact `liquibase.properties` key set and values pinned now,
**so that** a property Liquibase 5 has removed is caught in `mvn test`, not in a K8s deploy job.

### Acceptance criteria
- [x] The exact key set (`changelogFile`, `liquibase.hub.mode`, `liquibase.headless`) and their
  values are asserted. — FR13

### Delivered
`LiquibasePropertiesParityTest`, `stagingdlrm-viewstore-liquibase` — a module with zero prior Java on
this branch.

**Evidence:** `mvn -o test -pl stagingdlrm-viewstore/stagingdlrm-viewstore-liquibase` → `Tests run: 2, Failures: 0` (2026-09-04).

---

## DD-43192.I — Annotate the BC-08 test-helper coverage (BC-08)

**As a** developer reviewing this repo's parity coverage,
**I want** the repo's sole `ZonedDateTime` usage (a test helper, not product code) explicitly recorded
as already-covered rather than silently absent,
**so that** a reviewer doesn't mistake the lack of a new BC-08 test for a coverage gap.

### Acceptance criteria
- [x] Annotated in place with the BC-08 identifier and FR14's rationale, rather than authoring a new
  test around a test helper. — FR14

### Delivered
A Javadoc block on `ObjectBuilder.buildMetaData`, `stagingdlrm-event-processor`, naming BC-08 and
pointing at the existing `StagingDlrmEventProcessorTest` coverage that already exercises it. No test
execution evidence applies — this story adds no test.

---

## DD-43192.J — Publish the parity checklist (cross-cutting deliverable)

**As a** developer starting the DD-43192 upgrade stage,
**I want** one document that names every one of the 24 catalogued behavioural changes plus DLRM-01,
its status, the test that pins it, and the exact command/result behind every green row,
**so that** I know precisely what this story's regression gate covers before I touch a single
version number.

### Acceptance criteria
- [x] Covers every BC-01..BC-24 plus DLRM-01 with a legend mark — no item silently absent. — FR17, AC7
- [x] Records the exact command and result for every 🟢 row. — FR17, AC7
- [x] Any J17 run that contradicts the investigation report's claims is recorded with both the
  report's claim and the observed behaviour. — FR1 (decision 4), AC10

### Delivered
`docs/j25-parity-checklist.md`.

**Evidence:** self-referential — the document's own run-evidence column *is* the evidence, cross-checked against a full reactor build (`mvn -o clean install -DskipITs`, 18/21 modules, `BUILD SUCCESS`, zero failures) recorded in its Gaps section.

---

## Cut line, if this story had needed to be cut

Per the parity-method ADR's consequence "if a parity story must be cut, cut from the back of its
Bucket A table — the primary items carry the novelty; the tail is small and well understood": the cut
line would have fallen after DD-43192.B, then progressively DD-43192.H, .F, .E in that order, with
DD-43192.A, .B and .J retained regardless. In the event, no cut was needed — all ten stories above
were delivered in the one PR.
