# Stories — DD-43078: stagingDLRM test hardening

> Stage 3 artefact. Sources: [`00-input-brief.md`](./00-input-brief.md),
> [`01-requirements.md`](./01-requirements.md), [`02-design.md`](./02-design.md) (gated 2026-08-06),
> [ADR-001](../adrs/001-dlrm-scenario-test-dsl.md) (**Accepted** 2026-08-06).
>
> Every claim below about a file, class, line count or existing assertion was **checked against the
> working tree** at `dev/libra1-ns1` (`e707c0b7`). Where the design's description of the code does not
> match what is there, it is called out in [Findings](#findings--verified-state-of-the-code) rather
> than silently corrected.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) — stagingDLRM test hardening (size **M**) |
| Repo | `cpp-context-stagingdlrm` · branch `dev/libra1-ns1` |
| Sibling | [DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening) — PCFDLRM half, parallel, no ordering constraint |
| Production changes | none — see [What "no production change" means](#what-no-production-change-means) |
| Sub-stories | 6 (T1–T6, matching the design's task IDs) + 1 review gate (G1) |

DD-43078 stays **one Jira story**. T1–T6 are its sub-tasks: each is independently reviewable and
mergeable behind its own PR, but none delivers user-visible value alone — the value (a suite that
fails when DD-43081 changes payload shape) exists only once T1 plus at least one assertion sub-story
has landed. They are sized and traced separately so the story can split across a sprint boundary
without losing the FR mapping.

---

## Findings — verified state of the code

Checked facts, not restatements of the design. Five change what a sub-story has to do; they are
marked **[scope-affecting]**.

### F1 — [scope-affecting] `verifyReceiveCaseFileRequested` asserts nothing at all

`stagingdlrm-integration-test/src/test/java/uk/gov/moj/cpp/stagingdlrm/stub/PcfdlrmStub.java:34-42`:

```java
public static void verifyReceiveCaseFileRequested(final List<String> expectedValues) {
    await().atMost(10, SECONDS).pollInterval(5, SECONDS).until(() -> {
        RequestPatternBuilder requestPatternBuilder = postRequestedFor(urlPathMatching(RECEIVE_MIGRATE_CASE_FILE));
        expectedValues.forEach(
                expectedValue -> requestPatternBuilder.withRequestBody(containing(expectedValue))
        );
        return true;                       // <-- WireMock.verify(...) is never called
    });
}
```

The `RequestPatternBuilder` is constructed, decorated and discarded. The awaitility condition is the
constant `true`. **All 12 call sites in `ReceiveCaseFileSubmissionIT` assert nothing.**

This contradicts the design's canary premise directly — the design says flipping
`stringList.add("LIBRA")` to `"XHIBIT"` at line 105 "is the canary". There is no assertion to flip.
**The forwarded `pcfdlrm.receive-migrated-case-file` payload is not asserted at IT level today, at
all.** Fixing `PcfdlrmStub` is a **prerequisite of T4**, not a consequence of it; once fixed, the
first run of the re-pointed base journey is where the canary actually lands.

Note the asymmetry: `verifyReceiveCaseFileNotRequestedFor` in the same file *is* real — a genuine
`findAll(...).isEmpty()` assertion. The negative path works; the positive path is hollow.

### F2 — [scope-affecting] A large fraction of the IT assertions are vacuous `null == null`

In `ReceiveCaseFileSubmissionIT`, `commandMigrateCaseDetails` is **not** `caseDetails` — it is
`jsonPayload.getJsonObject("migratedCase")`, whose only children are `caseDetails`, `hearings`,
`defendants`, `migrationSourceSystem`. So these read `null` on both sides and pass unconditionally:

| Line(s) | Assertion | Why vacuous |
|---|---|---|
| 107, 210, 252, 296, 338, 377, 417, 459, 497, 535 | `.get("caseId")` | no `caseId` under `migratedCase` |
| 110, 213, 257, 300, 341 | `.getJsonObject("materials")` | `materials` is at submission root |
| 218, 258 | `.get("receiptType")` | lives at `migratedCase.caseDetails.receiptType` |
| 380 | `.getJsonObject("caseMarkers")` | under `caseDetails`, **and is an array** |
| 420 | `.getJsonObject("plea")` | at `defendants[0].offences[0].plea` |
| 462 | `.getJsonObject("verdict")` | at `defendants[0].offences[0].verdict` |
| 500 | `assertThat(cmd.getJsonObject("dateOfSending"), is(msg.getJsonObject("2024-08-23")))` | **a date literal used as a JSON key** — both sides null |
| 501, 537 | `assertNull(...)` on `dateOfCommittal` / `sendingCourt` | under `caseDetails`; pass for the wrong reason |

Genuinely asserted today: the `migrationSourceSystem` object, the `hearings` array, and defendant
fields via `commonDefendantMatches`. Everything above is decoration.

### F3 — [scope-affecting] `MigratedCaseConvertor` is mocked in `StagingDlrmEventProcessorTest`

`StagingDlrmEventProcessorTest:72` declares `@Mock private MigratedCaseConvertor migratedCaseConvertor;`,
and `StagingDlrmEventProcessor:111` is the **only** producer of
`ReceiveMigratedCaseFile.migratedCaseDetails`. With the convertor mocked, that field is `null` in
every captured payload — so the design's "keep the captors, assert the payload whole" would assert
the largest and most FR2-relevant sub-object as `null`.

T6 must use a real `MigratedCaseConvertor` (plain construction or `@Spy` — it is a CDI bean with no
injected collaborators), or stub it from the same fixture the expected payload is built from.
**Keeping the mock is what does not work.**

### F4 — [scope-affecting] The 11 FR5 pins span **five** schema files, not one

A sibling class is structural, not conditional on the class "getting unwieldy":

| Pin | File |
|---|---|
| `dateReceived`, `receiptType`, `receivingCourt`, `retrialIndicator`, `initiationCode` enum, `anyOf[dateOfCommittal\|dateOfSending]` | `case-details.json` |
| `hearings[*].durationMinutes` | `migrated/migrated-hearing.json` |
| `offences[*].prosecutorOffenceId`, `offences[*].offenceDateCode` | `migrated/migrated-offence.json` |
| `caseMarkers[*].markerTypeCode` | `case-marker.json` |
| `selfDefinedInformation.gender` | `self-defined-information.json` |

**Resolution:** validate all 11 against the single root
`json/schema/migrated/migrated-case-submission.json`, which `$ref`s into all five and is the
in-module analogue of the production validation surface. Keeps the pins in one suite, in the module
gate decision Q3 chose, and exercises the composite the runtime actually validates.

### F5 — [scope-affecting] `StagingDlrmCommandHelperTest` is missing from the design's scope

**654 lines, 12 tests** — the largest test class in the repo. It covers
`StagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload()`, which assembles the exact JSON
body POSTed to `/receive-migrated-case-submission`. Its assertions are the weakest here:

```java
assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
```

`caseDetails` could be `{}` and the test passes. The design's FR9 row names only
`JsonSchemaValidatorTest`, `EventGridTriggerJavaTest` and `TimerTriggerJavaTest`. **Added to T2.**

### F6 — The Function App validates with **networknt**, not everit

`JsonSchemaValidator` imports `com.networknt.schema.*`; the pom declares
`com.networknt:json-schema-validator:1.0.83` at compile scope. ADR-001 §6 and the design's "shared
foundations" talk about everit and `JsonSchemaValidationMatcher` — correct for the **canonical**
schemas (T5), **wrong for the func-app gate**. T2 must pin the gate through the func-app's own
`JsonSchemaValidator`, or it tests a validator that never runs in that process.

### F7 — hamcrest and `test-utils-core` reach the func-app only transitively

*(Corrected from the first draft of this section, which claimed they were absent.)* They resolve —
`mvn dependency:tree -pl stagingdlrm-azure-functions`:

```
uk.gov.justice.services:test-utils-framework-persistence:test
  └─ uk.gov.justice.utils:test-utils-core:17.104.0:test
       └─ org.hamcrest:hamcrest:2.2:test
```

So ADR-001's "hamcrest is already on the test classpath in both repos" **holds**. The real point is
narrower: they arrive through a *persistence* test artefact the func-app has no obvious use for, so
the chain is fragile. **T1 declares `org.hamcrest:hamcrest` at compile scope inside
`stagingdlrm-test-support`** so `WholePayloadMatcher`'s own dependency travels with it, rather than
relying on that route or editing six poms.

`org.skyscreamer:jsonassert` is confirmed **absent from every pom in this repo** — the ADR is right.

### F8 — T5's classpath assumption is **verified true**

The design asked T5 to confirm before building around it. Confirmed:

- `catalog-generation-plugin` runs at `generate-sources` in the module's pom.
- `target/classes/META-INF/schema_catalog.json` and `target/classes/json/schema/**` are both present
  after a build — on the module's own test classpath.
- The catalog maps `.../json/schemas/prosecutor.json` → `pcf-prosecutor.json`, so `case-details.json`'s
  `$ref` resolves despite the filename mismatch.
- `common-core-domain` ships its own catalog with `definitions.json`, so `#/definitions/date` resolves.
- `JsonSchemaValidationMatcher.getJsonSchemaFor` delegates to the catalog-aware
  `SchemaCatalogResolver` — no network resolution.

T5 proceeds as designed on this point; the "verify first" caveat is discharged.

### F9 — The aggregate gap is real; the design overstates one detail

236 lines, **7 tests**. Five use `RETURNS_DEEP_STUBS` (lines 52, 79, 98, 130, 187); **three** of them
assert nothing but `getSubmissionId()`. Design finding 2 holds for those three — but
`shouldRaiseCaseAlreadyProcessedAndExistsInProgressionEvents` asserts five real fields and
`receiveErrorMigratedCaseSubmission` asserts two, so "all five tests would pass" is true only of the
three. Command invocations count **10**, not 9. ADR-001 §3's conclusion is unaffected.

### F10 — The command-handler suite is weaker than described

253 lines, **4 tests**, 5 lines containing `assert`. Only `shouldReceiveMigratedCaseSubmission` uses
the mocked aggregate; the other three construct a real one. And that test has **zero assertions** —
its only one is commented out at line 107:

```java
verify(migratedCaseSubmissionAggregate).receiveMigratedCaseSubmission(captor.capture());
//        assertThat("The Case Id should match", captor.getValue()...); // to think
```

(Even uncommented it is a no-op — `assertThat(String, boolean)` with an `.equals()` result is not a
matcher assertion.) So **nothing anywhere in this repo asserts the enveloped payload of
`stagingdlrm.events.migrated-case-submission-received`** — the event the whole downstream chain hangs
off. Bigger than the design records, and T3's first target. The design's "keep the mocked aggregate"
applies to that one test only.

### F11 — No Event Grid coverage at IT level, and no stub for it

`grep -rn "EventGrid\|Outcome" stagingdlrm-integration-test/src/test/` returns nothing; the stub
package holds only `PcfdlrmStub`, `ProgressionStub`, `SystemIdMapperStub`. FR10 lists "the terminal
4xx rejection with its outcome file" as a journey to keep, but the outcome-file half does not exist
to keep — `shouldRaiseBadRequest` asserts HTTP 400 plus four message fragments. T4 asserts the 4xx
and its message; the outcome payload stays at unit level (T6). Building IT Event Grid infrastructure
is new surface that FR10's representative-depth cap and NFR2 argue against. **Recorded, not built.**

### F12 — `ReceiveErrorCaseSubmissionIT`'s only assertion is vacuous

Line 44: `assertThat(envelope, is(notNullValue()))` where `envelope` is `Optional<JsonObject>`. An
`Optional` is never null; an *empty* one passes. The test proves the POST returned without throwing.

### F13 — Two LIBRA-only unit scenarios exist, and requirements do not clearly cover them

*Out of scope* excludes "any LIBRA scenario at either test layer", but AC7 is IT-scoped. At unit
level:

- `StagingDlrmEventProcessorTest.shouldHandleMigratedCaseSubmissionReceivedWhenSourceSystemLIBRA:92`
- `JsonSchemaValidatorTest.validatePayloadSuccessfully` — inline payload carries
  `"migrationSourceSystemName": "LIBRA"` (line 153) and LIBRA identifiers throughout
- `StagingDlrmCommandHelperTest` hardcodes `"LIBRA"` at lines 78 and 108 (F5)

**Decision needed at T2/T6 review**, recorded rather than assumed. Recommended: re-point at XHIBIT via
`{{SOURCE_SYSTEM}}`, since the LIBRA half is DD-43081's to add back as rows. This is a deliberate
reduction in LIBRA coverage and should be a decision, not a side effect.

### F14 — `dlrm_batch_name` already supports comma lists and `*`; `dlrm_folder_name` does not

`EventGridTriggerJava:93-97` splits `batchName` on `,`, trims, lower-cases; `validateBatchNames:119`
returns true when the first entry is `*`. `folderName:84` is a single `trim().equalsIgnoreCase()`.
`EventGridTriggerJavaTest` already covers 7 path cases including the comma list and the wildcard. So
FR9's path work is mostly a **refactor into rows** so the future comma-separated *folder* list is a
row — not new coverage. Sized accordingly.

### F15 — Counts confirmed and corrected

| Design claim | Verified |
|---|---|
| `MigratedCaseSubmissionAggregateTest` 236 lines | yes — 7 tests, 26 assert lines |
| `StagingdlrmCommandHandlerTest` 253 lines | yes — 4 tests, 5 assert lines |
| `MigratedCaseConvertorTest` 123 lines, 5 tests | yes — 14 assert lines |
| `StagingDlrmEventProcessorTest` 286 lines | yes — 11 tests, 32 assert lines |
| `PcfDlrmEventProcessorTest` 59 lines, "thin" | 1 test, but asserts **all 5** fields. Whole-payload adds symmetry (catches an *added* field), not missing coverage |
| `domain-value-schema` has no `src/test` | yes — `src/main/resources` only |
| ITs: 3 classes, 11 XHIBIT + 3 LIBRA fixtures | 3 classes yes; **12** fixtures contain XHIBIT, 3 LIBRA, 3 neither — 18 total |
| The 3 LIBRA fixtures | yes — base, `-with-multiple-hearing`, `-without-materials` |
| FR5 CSV filter gives 17 `relax-*` rows | yes — 17 of 165; the 9/3/5 split reproduces exactly |
| `case-details.json`: enum `["O"]`, 8 required, `anyOf`, `additionalProperties: false` | yes, all four |
| Func-app `case-details.json`: 8 properties all required, `additionalProperties: true`, no patterns/lengths/enums | yes, all four |
| `MigratedCaseValidationRules` / `MigratedCaseSubmissionRejected` absent | yes — no match in the tree |
| `ObjectBuilder` parameterises on `MigrationSourceSystemName` | yes — 5 builders. But `buildCaseDetails():158` does **not**, so FR5-relevant caseDetails fields are hardcoded there |
| `MigratedCaseConvertor` is field-by-field | **emphatically** — 341 lines, 23 builder methods, **128** `.with*(...)` calls. The existing test asserts ~8 |

### F16 — The entry schema the runtime validates is duplicated in `stagingdlrm-command-api`

`stagingdlrm-command-api/src/raml/json/schema/stagingdlrm.receive-migrated-case-submission.json` is a
near-copy of the value-schema module's `migrated/migrated-case-submission.json` (it omits `channel`;
otherwise identical). Both `$ref` into the value-schema module, so gate decision Q3's locality
argument holds — but the duplicated *entry* schema is a drift risk no story currently owns.
**Recorded for the epic; no work here.**

### F17 — ADR-001's appendix did not compile as written (fixed)

Found while implementing T1. `JSONCompare.compareJSON(String, String, JSONComparator)` throws
checked `JSONException`, and `TypeSafeMatcher.matchesSafely` cannot declare one — so the appendix's
`WholePayloadMatcher` fails to compile:

```
unreported exception org.json.JSONException; must be caught or declared to be thrown
```

Fixed in both places: the shipped class catches it and rethrows as `AssertionError` ("payload could
not be compared as JSON"), which is the right handling — a payload that will not parse is a test
failure, not a comparison result. **[ADR-001's appendix has been corrected](../adrs/001-dlrm-scenario-test-dsl.md#wholepayloadmatcher)**
so DD-43099's T7 does not hit the same wall.

### F18 — The func-app gate's **root is closed**; only the levels beneath it are open

Found while writing T2. The three schemas behave differently at each level:

| Level | Schema | Unknown properties |
|---|---|---|
| root | `stagingdlrm.case-submission.json` | **rejected** — `additionalProperties: false` |
| `migratedCase` | `migrated-case.json` | accepted |
| `caseDetails` | `case-details.json` | accepted |

So "the gate pins that unknown fields pass" — as the design's FR9 row and this document's original
T2 AC5 both put it — is true at two levels and **false at the third**. A sibling of `migratedCase`
is rejected. Both behaviours are now pinned as separate scenarios, and T2 AC5 is corrected above.

Consequence worth carrying to DD-43086: adding anything at the submission root (a batch marker, a
correlation id) is a **breaking change to the gate**, while adding fields inside `caseDetails` is not.

### F19 — `assertNotNull(messageMap)` asserted nothing, in three journeys

`TimerTriggerJavaTest` asserted that a local variable the test had itself just constructed was
non-null:

```java
final Map<String, QueueMessage> messageMap = new HashMap<>();
messageMap.put(queueMessage, new QueueMessage(queueMessage, 1L, listBlobNames));
...
timerTrigger.run(timerInfo, context);
assertNotNull(messageMap);          // true regardless of anything the trigger did
```

Same family as F1 and F2 — a green assertion that constrains nothing. Replaced in T2 by whole-payload
assertions on the two JSON payloads actually handed to `StagingDlrmCommandHelper`. Counts as a fourth
latent test defect predating this story, alongside F1, F2 and F12.

---

## Sequence

```
ADR-001 (Accepted 2026-08-06)
   |
   v
  T1  stagingdlrm-test-support                       S
   |
   +--> T2  Function App                             M    <- taken first (owner's request)
   +--> T3  Aggregate + command-handler              M    <- largest gap (F9, F10)
   +--> T4  Integration tests                        M+   <- carries the canary (F1)
   +--> T5  Schema-contract pins                     M    <- the FR5 / AC3 sub-story
   +--> T6  Convertor + event processors             M    <- highest-value FR2 target (F3)
                     |
                     v
                    G1  AC6 deliberate-break gate (review step, not a task)
```

T2–T6 are mutually independent once T1 lands. The order above keeps T3 and T4 early enough that
neither the largest gap nor the canary is scheduled last.

## Status — 2026-08-06

| Task | State | Evidence |
|---|---|---|
| **T1** | **done** | `stagingdlrm-test-support` in the reactor; 18 tests; no WAR depends on it; no `results-domain-common` on any test classpath |
| **T2** | **done** | `stagingdlrm-azure-functions`: **78 tests** (was 54), 1 `@Disabled` — see [Handover](#handover--picking-up-t3-onwards); 19 fixtures; 0 inline JSON text blocks; 0 stray `LIBRA` literals |
| T3–T6 | **not started** | — |

`mvn clean install` is green across all 27 modules with T1 and T2 in place. See
[Handover](#handover--picking-up-t3-onwards) before starting T3.

Both new mechanisms were proved to **bite**, not merely to pass:

- dropping `summonsCode` from an expected fixture fails with `Unexpected: summonsCode`;
- dropping a supplied parameter's placeholder from a fixture fails with the token and path named.

Not proved to bite: the UTF-8 read. Proving it needs the test JVM run under a non-UTF-8
`file.encoding`, and the `maven-surefire-plugin` override that did so was the only such override in
27 modules — it also silently replaced jacoco's `argLine`, costing the module its coverage report.
Removed as not worth its cost; AC3 is now a plain non-ASCII round-trip, and the guarantee rests on
`FixtureLoader` naming `UTF_8` explicitly.

---

## T1 — Shared test-support module

**Size:** S (0.5–1 day) · **Depends on:** ADR-001 (Accepted)

> As a **developer hardening any stagingDLRM test suite**,
> I want **one place that loads a fixture, substitutes scenario parameters and compares a whole JSON
> payload with anchored, enumerated exclusions**,
> so that **the six suites assert payloads the same way, and a wildcard or stale exclusion cannot
> quietly open a hole in FR2**.

### Scope

| Artefact | Note |
|---|---|
| `stagingdlrm-test-support/pom.xml` | new top-level module, added to root `<modules>`, packaging `jar` |
| `src/main/java/uk/gov/moj/cpp/stagingdlrm/test/FixtureLoader.java` | from ADR-001 appendix |
| `.../test/WholePayloadMatcher.java` | from ADR-001 appendix |
| `src/test/java/.../FixtureLoaderTest.java` | the substitution and unresolved-placeholder guards are contracts and must be tested |
| `src/test/java/.../WholePayloadMatcherTest.java` | the matcher's behaviour is a contract and must be tested |

Declares `org.skyscreamer:jsonassert` (test) and `org.hamcrest:hamcrest` at **compile** scope, so the
matcher's dependency travels with it rather than relying on the func-app's fragile transitive route
(F7). Consumers gaining a test-scope dependency: `domain-aggregate`, `domain-value-schema`,
`command-handler`, `event-processor`, `azure-functions`, `integration-test` — each pom edit may land
with that module's sub-story instead.

### Acceptance criteria

1. Module exists, is listed in the root `pom.xml`, packaged as a JAR, and `mvn clean install` builds the reactor green.
2. No deployable artefact gains it: no WAR depends on it, it appears in no `stagingdlrm-service` assembly, and every consumer declares `<scope>test</scope>`.
3. `FixtureLoader.fixture(path)` decodes as **UTF-8** — a non-ASCII round-trip asserts the content survives the load. (The stronger "regardless of platform default" form needed a surefire `file.encoding` override; see the note above for why it was dropped.)
4. `FixtureLoader` throws `AssertionError` when a supplied parameter key appears nowhere in the fixture; the message names the token and the path.
5. `FixtureLoader` throws `AssertionError` when any `{{…}}` remains unresolved, with the offending fragment in the message. **This is the FR1 guard.**
6. `WholePayloadMatcher` rejects any exclusion containing `*` at construction with an `IllegalArgumentException` naming FR2.
7. Exclusion matching is **exact string equality**: a test proves excluding `defendants[0].defendantId` does **not** exclude `defendants[1].defendantId` and does not exclude `caseDetails.defendantId`.
8. An exclusion matching no path fails the test, with a message telling the reader to correct or remove it.
9. An excluded path **absent** from the actual payload fails; present with a different value passes.
10. `JSONCompareMode.STRICT`: a test proves an *extra* field fails, and a test proves a *missing* field fails.
11. ~~`Comparison` carries **no default exclusions** and `withPathsExcluded` accumulates across chained calls.~~ **Withdrawn** — `Comparison` was dropped before merge (no call site needed it; see ADR-001 appendix). The no-default-exclusions principle is now structural: exclusion lists are literals at the assertion, and AC8 already fails any exclusion that matched no path.
12. No dependency on `uk.gov.moj.cpp.results:test-utilities` anywhere; `mvn dependency:tree` shows no `results-domain-common` on any test classpath.

**Traceability:** FR2, FR3, NFR1 · AC1, AC2, AC8 · gate decision Q1 · ADR-001 §1, §2, §5

ACs 3, 7, 8 and 10 are the deliberate divergences from `cpp-context-results`' `JsonMatcher`
(design finding 1). Dropping any of them to simplify the port means FR2 is not met.

---

## T2 — Function App: fixtures, path rows, presence-and-declared-type gate contract (FR9)

**Size:** M (2–3 days) · **Depends on:** T1 · **Taken first at the story owner's request**

> As a **developer who will later add LIBRA to the Function App gate**,
> I want **payload assembly and path/schema validation asserted whole against fixtures, with source
> system and folder/batch configuration as scenario data**,
> so that **a comma-separated folder list and source-system-keyed schema selection are new rows, not
> a rewrite, and the payload POSTed to the command API cannot change shape unnoticed**.

### Scope

Shares no test code or fixtures with the WildFly-side modules, so it is finishable and reviewable in
isolation. The module has **no `src/test/resources` at all** — this creates it.

| File | Now | Change |
|---|---|---|
| `validator/JsonSchemaValidatorTest.java` | 224 lines, 5 tests, inline text blocks; success payload is **LIBRA** (line 153) | lift to `src/test/resources/json/schema-validator/`; convert to rows; baseline to XHIBIT via `{{SOURCE_SYSTEM}}` |
| `rest/StagingDlrmCommandHelperTest.java` | **654 lines, 12 tests**; `assertNotNull` on `caseDetails`/`defendants`/`hearings`; `"LIBRA"` at 78, 108 | **added to scope (F5)** — whole-payload assertion of the assembled submission, `submissionId` the only exclusion |
| `EventGridTriggerJavaTest.java` | 240 lines, 7 tests, already covers comma batch list, `*`, wrong folder, wrong batch, short token list, null body (F14) | refactor the 7 into `@ParameterizedTest` rows over (name, url, folderName, batchName, expected) |
| `TimerTriggerJavaTest.java` | 504 lines, 9 tests, `assertNotNull(messageMap)` + `verify(...)` | whole-payload assertion on the payload handed to `StagingDlrmCommandHelper`; leave queue/blob verifies |
| `src/test/resources/json/<component-slug>/**` | **does not exist** | created |
| `src/main/resources/case-details.json` | 8 properties, all required, `additionalProperties: true`, no patterns/lengths/enums | **not modified** (NFR1) |
| `pom.xml` | networknt at compile; hamcrest/`test-utils-core` transitive only (F7) | adds `stagingdlrm-test-support` at test scope |

### Acceptance criteria

1. No JSON payload remains an inline Java text block in any func-app test.
2. `src/test/resources/json/<component-slug>/<document>-<scenario>.json` exists, one directory per test class, the scenario in the filename (ADR-001 §5 — the one-directory-per-scenario form it first specified is superseded there).
3. Every func-app fixture carrying a source system uses `"migrationSourceSystemName": "{{SOURCE_SYSTEM}}"`, and every row binds it explicitly. An unbound placeholder fails the run (T1 AC5).
4. `JsonSchemaValidatorTest` proves, as 8 separate reject rows, that each of the 8 `caseDetails` properties the gate declares `required` fails validation when absent.
5. It proves a payload carrying properties **not** declared in the func-app `case-details.json` (e.g. `sendingCourt`, `dateOfSending`, `summonsCode`) validates — pinning `additionalProperties: true`. **And separately that the root is closed** — a sibling of `migratedCase` is *rejected* (see F18; the original wording of this AC was wrong).
6. It proves the gate enforces **nothing it does not declare**: `initiationCode` outside `["O"]` **passes**, and a `defendants[]`/`hearings[]`/`offences[]` sub-object with invalid content **passes** — the gate never descends.
7. Validation is exercised through the func-app's own `JsonSchemaValidator` (**networknt**), not `JsonSchemaValidationMatcher`; `test-utils-core` is not added to this pom (**F6**).
8. `EventGridTriggerJavaTest`'s path validation is one `@ParameterizedTest` + `@MethodSource`; adding a comma-separated **folder** list case is one `Arguments.of(...)` with no test-method-body change.
9. Rows pin today's `dlrm_batch_name` behaviour explicitly: comma list matches any member, `*` matches anything, matching is case-insensitive, whitespace is trimmed.
10. `StagingDlrmCommandHelperTest`'s three payload-generation tests assert the assembled submission **whole** against a fixture, generated `submissionId` the only exclusion, commented. No `assertNotNull` remains as the sole assertion for `caseDetails`, `defendants` or `hearings`.
11. `TimerTriggerJavaTest` asserts the payload passed to `generateMigratedCaseSubmissionPayload` whole for at least the success and no-materials journeys.
12. `mvn test -pl stagingdlrm-azure-functions` passes with no more than a 20% wall-clock increase (NFR2).

**Open question for review (F13):** the func-app's XHIBIT/LIBRA mix. Recommended — re-point at XHIBIT
via `{{SOURCE_SYSTEM}}`; DD-43081 adds LIBRA rows back. **Confirm at PR review** — this is a
deliberate reduction in LIBRA coverage.

**Traceability:** FR1, FR2, FR3, FR4, FR9, NFR1, NFR2 · AC1, AC2, AC6, AC8 · F5, F6, F13, F14

---

## T3 — Aggregate and command handler: de-mock, rows, whole payloads

**Size:** M (2–3 days) · **Depends on:** T1

> As a **developer relaxing `case-details.json`**,
> I want **the aggregate and command-handler suites to run against fixture-deserialised submissions
> and assert every appended domain event whole**,
> so that **the aggregate cannot drop a field it carries forward, and the enveloped
> `migrated-case-submission-received` event — which nothing currently asserts — is pinned**.

### Scope

| File | Now | Change |
|---|---|---|
| `MigratedCaseSubmissionAggregateTest.java` | 236 lines, 7 tests, 10 command invocations; 5 use `RETURNS_DEEP_STUBS` (52, 79, 98, 130, 187); 3 assert only `getSubmissionId()` | fixture-deserialised `MigratedCaseSubmission`; 7 scenarios as `@MethodSource` rows; whole-payload assert each appended event |
| `StagingdlrmCommandHandlerTest.java` | 253 lines, 4 tests, 5 assert lines; `shouldReceiveMigratedCaseSubmission` has **zero** assertions (F10) | assert each appended `JsonEnvelope` payload whole; add the missing `migrated-case-submission-received` assertion |
| `domain-aggregate/src/test/resources/json/aggregate/**` | does not exist | created |
| `command-handler/src/test/resources/json/handler/**` | does not exist | created |
| `MigratedCaseSubmissionAggregate.java` | 133 lines, 4 command methods | **not modified** (NFR1) |

The two multi-command journeys are sequential calls with a whole-payload assertion after each, per
ADR-001 §3 — no `Scenario`/`StepDef` layer:

- `shouldRaiseDuplicateMigratedCaseSubmissionReceived`: receive → recordOutput → receive (3)
- `shouldRaiseCaseAlreadyProcessedAndExistsInProgressionEvents`: receive → receiveCaseAlreadyProcessed (2)

Only the command-handler's `shouldReceiveMigratedCaseSubmission` keeps a mocked aggregate; the other
three already construct a real one (F10).

### Acceptance criteria

1. No `RETURNS_DEEP_STUBS` remains; every submission under test is fixture-deserialised.
2. All 7 aggregate scenarios are rows in `@ParameterizedTest(name = "{index} => {0}")` + `@MethodSource`; each body is at most two lines and branches on nothing.
3. Scenario names follow ADR-001 §3: `"<FR/AC ref> <plain-English behaviour> (<source system>)"`.
4. Every appended domain event is asserted whole against a fixture — all of `MigratedCaseSubmissionReceived`, `DuplicatedMigratedCaseSubmissionReceived`, `MigratedCaseSubmissionProcessed`, `CaseAlreadyProcessedAndExistsInProgression`, `ErrorMigratedCaseSubmissionReceived`.
5. Every exclusion is an exact path, listed individually with an inline comment naming why it is non-deterministic. A `*` in any exclusion list fails T1 AC6.
6. Defendant identifiers minted in the command-handler and regenerated in the event-processor are excluded **per element** (`...defendants[0].defendantId`), never as a bare token or prefix.
7. `shouldReceiveMigratedCaseSubmission` asserts the appended `stagingdlrm.events.migrated-case-submission-received` envelope payload whole. The commented-out line 107 is **deleted, not restored**.
8. All four command-handler tests assert their appended envelope whole; no `withJsonPath` spot check remains as a class's only payload assertion.
9. Every scenario binds `withSourceSystem(...)` explicitly, baseline `XHIBIT`; omitting it fails (T1 AC5).
10. Adding a source system is proved to be data: a throwaway `Arguments.of(...)` row for a second source system compiles and runs with no test-body change. Demonstrated at review, not committed.
11. `mvn test -pl stagingdlrm-domain/stagingdlrm-domain-aggregate,stagingdlrm-command/stagingdlrm-command-handler` passes; neither module leaves the default `mvn test` run (NFR2).

**Traceability:** FR1, FR2, FR3, FR4, NFR1, NFR2 · AC1, AC2, AC6, AC8 · design finding 2, F9, F10

---

## T4 — Integration tests: XHIBIT baseline and real boundary assertions (FR10)

**Size:** M+ (2–3 days **plus contingency** — see the canary note) · **Depends on:** T1

> As a **team about to relax the shared schema**,
> I want **every IT journey to run as XHIBIT and the pcfdlrm boundary payload asserted whole and for
> real**,
> so that **the XHIBIT baseline is proved before the relaxation, and a behavioural difference between
> the two source systems surfaces now rather than in production**.

### Scope

| File | Now | Change |
|---|---|---|
| `stub/PcfdlrmStub.java` | 52 lines; `verifyReceiveCaseFileRequested` **never calls `verify(...)`** (F1) | **fix first** — make it a real WireMock verification, then replace the `List<String> contains` API with a whole-payload comparison of the captured body |
| `stagingdlrm.receive-migrated-case-submission.json` | base fixture, **LIBRA** | re-point to XHIBIT (convert, do not duplicate) |
| `…-with-multiple-hearing.json` | LIBRA | re-point to XHIBIT |
| `…-without-materials.json` | LIBRA | re-point to XHIBIT |
| `ReceiveCaseFileSubmissionIT.java` | 632 lines, **17** tests; `stringList.add("LIBRA")` at lines **104, 293, 335** (three sites, not one); ~20 vacuous assertions (F2) | flip the three literals; delete the vacuous assertions and replace with whole-payload comparison; fix the `getJsonObject("2024-08-23")` typo at line 500 |
| `ReceiveErrorCaseSubmissionIT.java` | 47 lines, 1 test, `assertThat(Optional, is(notNullValue()))` (F12) | assert the envelope is **present** and its payload whole |
| `CaseSubmissionProcessedIT.java` | 34 lines, delegates to `MigratedCaseSubmissionEventHelper` (5 real assertions) | whole-payload assertion of `migrated-case-submission-processed` |
| `helper/MigratedCaseSubmissionEventHelper.java` | 82 lines, field-by-field | swap for `WholePayloadMatcher` |
| Journeys | 17 + 1 + 1 | **no new journeys** (FR10, NFR2) |

### Acceptance criteria

1. `grep -rl LIBRA stagingdlrm-integration-test/src/test/resources/` returns nothing.
2. `grep -rn '"LIBRA"' stagingdlrm-integration-test/src/test/java/` returns nothing — the three literals at 104, 293, 335 are gone.
3. No IT journey resolves `migrationSourceSystemName` to LIBRA at runtime, verified by the assertion on the forwarded payload, not by fixture inspection alone.
4. `PcfdlrmStub.verifyReceiveCaseFileRequested` performs a real WireMock verification: a deliberately wrong expected value makes it **fail**. Demonstrated once at review — the F1 fix, and the precondition for AC7 meaning anything.
5. The forwarded `pcfdlrm.receive-migrated-case-file` body is asserted **whole** against a fixture in at least the successful-submission journey, with an enumerated, commented exclusion list.
6. Every assertion F2 identifies as `null == null` is removed or corrected to the real path. The `is(msg.getJsonObject("2024-08-23"))` at line 500 no longer exists in any form.
7. The three FR10 journeys are present and green: successful submission to the pcfdlrm call; terminal 4xx with its message asserted; processing-output/outcome-publication.
8. `ReceiveErrorCaseSubmissionIT` asserts `Optional.isPresent()` and the payload whole; the `notNullValue()` on an `Optional` is gone (F12).
9. IT class count unchanged (3) and total `@Test` count has not increased — no unit scenario ported into the IT layer.
10. `mvn verify -P stagingdlrm-integration-test` passes with no more than a 10% wall-clock increase (NFR2).
11. **The canary is recorded either way** — re-pointing the base fixture either changes no other result, recorded as "no behavioural difference found" in the PR, or it changes one, in which case it is raised **on epic DD-43067 the same day**, before merge.

**Sizing note.** AC11 is why this carries contingency. Because of F1, the base journey has both never
run as XHIBIT *and* never had its forwarded payload asserted — this is the first time that path is
actually checked. Treat a surprise as expected.

**Recorded gap (not scoped here):** no Event Grid stub exists in the IT module and no IT asserts an
outcome file (F11). The outcome half is covered at unit level by T6.

**Traceability:** FR1, FR2, FR10, NFR1, NFR2 · AC1, AC6, AC7, AC8 · F1, F2, F11, F12

---

## T5 — Schema-contract suite: the 11 FR5 pins

**Size:** M (2 days) · **Depends on:** T1

> As a **reviewer of DD-43081's schema relaxation**,
> I want **each constraint the relaxation will remove to have an accept case and a reject case, with
> the rejection message pinned, in the same module as the schema**,
> so that **the diff that relaxes a constraint and the test that must change with it land in the same
> PR under the same reviewer, rather than as a red build two modules away**.

### Scope

The module has **no `src/test` at all** and exactly one dependency (`common-core-domain`, compile).
This gives it its first tests and first test-scope dependencies — accepted at the gate (Q3).

| Artefact | Note |
|---|---|
| `stagingdlrm-domain-value-schema/pom.xml` | adds JUnit 5, `test-utils-core`, `stagingdlrm-test-support`, hamcrest — all test scope |
| `src/test/java/.../MigratedCaseSubmissionSchemaContractTest.java` | the 11 pins as rows over (name, fixture, expected outcome, message fragment) |
| `src/test/resources/json/schema/<scenario-slug>/` | ADR-001 §5 |
| `src/main/resources/json/schema/case-details.json` | **not modified** — DD-43081 owns the relaxation |

The 11 pins, derived from `libra-schema-impact.csv` (`change_type` starting `relax-`: **17 rows,
verified**), reduced by the two FR5 caveats:

| # | Pin | Schema file (F4) | Scenarios |
|---|---|---|---|
| 1 | `caseDetails.dateReceived` required | `case-details.json` | accept + reject |
| 2 | `caseDetails.receiptType` required | `case-details.json` | accept + reject |
| 3 | `caseDetails.receivingCourt` required | `case-details.json` | accept + reject |
| 4 | `caseDetails.retrialIndicator` required | `case-details.json` | accept + reject |
| 5 | `caseDetails.initiationCode` `enum: ["O"]` | `case-details.json` | accept + reject |
| 6 | `anyOf[dateOfCommittal\|dateOfSending]` (2 CSV rows, 1 constraint) | `case-details.json` | accept per branch + reject (neither) |
| 7 | `hearings[*].durationMinutes` required | `migrated/migrated-hearing.json` | accept + reject |
| 8 | `offences[*].prosecutorOffenceId` required | `migrated/migrated-offence.json` | accept + reject |
| 9 | `caseMarkers[*].markerTypeCode` required — **stays** | `case-marker.json` | reject only |
| 10 | `selfDefinedInformation.gender` required — **stays** | `self-defined-information.json` | reject only |
| 11 | `offences[*].offenceDateCode` `maximum: 6` — **stays** | `migrated/migrated-offence.json` | reject only |

No pin for the 5 over-reported rows (`hearings[*].weekCommencingDate.startDate`,
`personalInformation.address.address1`, three under `parentGuardianInformation`) — optional parents,
nothing changes (FR5 caveat b, verified).

**Derive from the CSV, not from FR5's prose** — the prose omits pins 7 and 8, and both are
`already_flowing` to pcfdlrm with `assumed_flowing` to Progression, carrying the guard sentence.

**Structure (F4):** validate all 11 against the root
`json/schema/migrated/migrated-case-submission.json`. If the class becomes unwieldy, split by
container (`caseDetails` / `hearings` / `defendants`), not by schema file.

**Classpath prerequisite: already verified (F8)** — no need to spend the first hour on it.

### Acceptance criteria

1. The module has a `src/test/java` tree and `mvn test -pl stagingdlrm-domain/stagingdlrm-domain-value-schema` runs and passes.
2. All 11 pins exist as rows; each row name carries its FR reference and source system, so `grep FR5` lists them.
3. Pins 1–8 each have an **accept** scenario (valid XHIBIT payload validates) and a **reject** scenario. Pin 6 has two accepts, one per `anyOf` branch.
4. Pins 9–11 each have a **reject** scenario asserting the constraint is still enforced. If one goes red after DD-43081, that story relaxed something FR9/FR10 said it would not.
5. Every reject uses `failsValidationWithMessage(...)` with the fragment pinned, **not** bare `isNotValidForSchema` — the message lands in the outcome file and a 4xx is terminal.
6. Validation runs through `JsonSchemaValidationMatcher` against the **committed** canonical schema, exercising everit — the validator `SchemaCatalogAwareJsonSchemaValidator` uses in production (ADR-001 §6).
7. Every scenario states `migrationSourceSystemName` explicitly; baseline `XHIBIT` (FR1).
8. No file under `src/main/` is modified — verified by `git diff --stat` (NFR1).
9. Every row is traceable to a specific `relax-*` CSV row, and the 5 no-pin rows are listed in a comment with the reason, so a reader can see all 17 accounted for.
10. Adding pin 12 later is one `Arguments.of(...)` plus two fixtures, no test-body change (AC2).

**FR5a is not delivered here.** Per gate decision Q2, if DD-43081 FR14a has not landed at stage 5 the
rows carry to DD-43081 and DD-43078 closes without them. **The carry is not symmetric**: DD-43081
AC6a covers the LIBRA-accept half; the **XHIBIT-unchanged half has no corresponding AC there**.
Adding it — or raising it with that story's owner — is **open, and a blocker on closure, not on
delivery**.

**Traceability:** FR1, FR2, FR4, FR5, (FR5a carried), NFR1 · AC1, AC2, **AC3**, AC8 · Q2, Q3 · F4, F8

---

## T6 — Convertor and event processors: whole-payload outbound assertions

**Size:** M (2–3 days) · **Depends on:** T1

> As a **developer whose schema no longer guarantees a field is present**,
> I want **the 128-call mapping in `MigratedCaseConvertor`, the pcfdlrm REST payload and both Event
> Grid outcomes asserted whole**,
> so that **the likeliest silent-drop site is the most tightly pinned, and the uploader's only
> feedback channel cannot change shape unnoticed**.

### Scope

| File | Now | Change |
|---|---|---|
| `convertor/MigratedCaseConvertorTest.java` | 123 lines, 5 tests, 14 assert lines; asserts ~8 of the mapping's outputs | **highest-value FR2 target** — `@ParameterizedTest` table of (name, input fixture, expected `MigratedCaseDetails` fixture, exclusions) |
| `StagingDlrmEventProcessorTest.java` | 286 lines, 11 tests; **`MigratedCaseConvertor` mocked at line 72** (F3) | keep the captors, use a **real** convertor; whole-payload assert the captured `ReceiveMigratedCaseFile` and `Outcome` |
| `PcfDlrmEventProcessorTest.java` | 59 lines, 1 test; already asserts all 5 fields (F15) | whole-payload assert — adds symmetry (an *added* field now fails) |
| `ObjectBuilder.java` | 256 lines; 5 builders parameterised; `buildCaseDetails():158` is **not** | stays (ADR-001 §2), not migrated wholesale |
| `src/test/resources/json/event-processor/**` | does not exist | created |
| `MigratedCaseConvertor.java` (341 lines, 23 builders, **128** `.with*` calls) | | **not modified** (NFR1) |

`Outcome` is a 6-component record (`caseId`, `submissionId`, `caseUrn`, `success`, `description`,
`azureLocation`). Current tests assert 4–5 of 6; **`azureLocation` is never asserted.**

### Acceptance criteria

1. `MigratedCaseConvertorTest` is a `@ParameterizedTest` + `@MethodSource` table; every row asserts the produced `MigratedCaseDetails` whole against a fixture.
2. The table reaches every one of the convertor's 23 builder methods at least once — including `buildAllocationDecision`, `buildVerdict`, `buildPlea`, `buildAlcoholRelatedOffence`, `buildIndividualAliases`, `buildParentGuardianInformation`, `buildWeekCommencing`, `buildCaseMarkers`, none of which the current 5 tests exercise.
3. `StagingDlrmEventProcessorTest` uses a **real** `MigratedCaseConvertor` in every test capturing a `ReceiveMigratedCaseFile`, so `migratedCaseDetails` is populated and actually asserted (F3).
4. The captured `ReceiveMigratedCaseFile` is asserted whole in at least the XHIBIT success journey, with regenerated defendant UUIDs excluded per element, each commented.
5. Both Event Grid `Outcome` payloads are asserted whole — success and failure paths — covering all 6 components including `azureLocation` (FR8).
6. `PcfDlrmEventProcessorTest` asserts the `record-submission-processing-output` payload whole.
7. No exclusion is a bare field name or prefix; each is a full JSONassert path (T1 AC7).
8. `ObjectBuilder` is unchanged apart from additions new rows need; not deleted, not migrated wholesale.
9. `mvn test -pl stagingdlrm-event/stagingdlrm-event-processor` passes.
10. **FR8's missing wiring is recorded, not built.** `MigratedCaseValidationRules` and `MigratedCaseSubmissionRejected` do not exist (verified). This covers `EventGridService.sendEventToEventGrid(Outcome)` for success and error, and the omission goes in the PR description and at closure.

**Open question for review (F13):** `shouldHandleMigratedCaseSubmissionReceivedWhenSourceSystemLIBRA:92`
is a pre-existing LIBRA unit scenario. Recommended: re-point at XHIBIT, let DD-43081 add it back.

**Traceability:** FR1, FR2, FR3, FR4, FR8, NFR1, NFR2 · AC1, AC2, AC6, AC8 · F3, F13, F15

---

## G1 — Review gate: the deliberate-break demonstration (not a task)

**AC6 stays a review step.** Folding it into a task turns the only real proof FR2 landed into a
checkbox. At the stage 6 gate, demonstrated live and uncommitted:

1. Drop a field from an outbound payload in `MigratedCaseConvertor` → a `MigratedCaseConvertorTest` row fails.
2. Add an unexpected field → a test fails (STRICT is symmetric — ADR-001 §1).
3. Break an FR5 pin's accept fixture → the T5 row fails with the pinned message.
4. Break `PcfdlrmStub`'s expected value → the IT fails. **This is the F1 fix proving itself** (T4 AC4).
5. Remove a `withSourceSystem(...)` from a scenario → the run fails on an unresolved `{{SOURCE_SYSTEM}}` (T1 AC5).
6. Add a stale exclusion → the test fails with the "matched no path" message (T1 AC8).

None is committed. If any passes, FR2/FR1 is not met for that suite and the story does not close.

---

## What "no production change" means

Confirmed against the plan, since AC8 will be read literally at review.

**Holds.** No existing production source file changes. No schema, RAML, event contract, JMS
subscription, `system-id-mapper` interaction or Azure resource change. No event added, renamed or
re-routed. Nothing is deployed, so stage 8 is a no-op beyond a green build. **DD-43078 relaxes no
schema** — `case-details.json` is read by T5's pins, not edited.

**Three things that are not `src/test`, flagged so review is not surprised:**

1. **`stagingdlrm-test-support` has a `src/main`.** ADR-001 §2 rejected `test-jar` packaging, so it is
   a plain JAR and its three classes live in its own `src/main/java`. Non-deployable, test scope only,
   no WAR depends on it — but a grep for "does this touch `src/main`" will hit it.
2. **POM changes.** Parent gains a `<module>`; six modules gain test-scope dependencies;
   `org.skyscreamer:jsonassert` enters the repo for the first time. Build files, not runtime code —
   and the reason Q1 went to the gate.
3. **G1's demonstration** edits production code live at the gate and is not committed.

---

## Traceability

### FR/NFR → sub-story → story AC

| Req | Sub-story | Story AC | Notes |
|---|---|---|---|
| **FR1** XHIBIT baseline | T1 (guard), T2–T6 | AC1, AC7 | Enforced mechanically by `FixtureLoader`'s unresolved-placeholder failure (T1 AC5), not by convention. Three pre-existing LIBRA unit scenarios need a decision — F13 |
| **FR2** whole payloads | T1 (mechanism), T2–T6 | AC1, AC6 | T6 (128 mapping calls) highest-value; T3 closes the largest gap |
| **FR3** source system is data | T1, T2, T3, T5, T6 | AC2 | Not applicable to T4 — ITs are XHIBIT-only by FR10 |
| **FR4** scenario DSL where it earns its place | T2, T3, T5, T6 | AC2 | Rows only; step chaining deferred (ADR-001 §3). T4 stays plain JUnit |
| **FR5** pin the `relax-*` constraints | **T5** | **AC3** | 11 pins: 8 accept+reject, 3 reject-only, 5 no-pin, from 17 verified CSV rows |
| **FR5a** newly-accepted fields | *carried to DD-43081* | — | Q2. **XHIBIT-unchanged half has no AC in DD-43081** — open action, closure blocker |
| FR5b, FR6, FR7 | — | AC4, AC5 (moved) | Moved to DD-43099 |
| **FR8** outcome path | **T6** | AC1, AC6 | Both `Outcome` payloads whole, all 6 components. Missing wiring recorded, not built. No IT-level outcome coverage — F11 |
| **FR9** func-app | **T2** | AC1, AC2 | Presence-only gate pinned; path validation as rows. `StagingDlrmCommandHelperTest` added (F5). networknt, not everit (F6) |
| **FR10** ITs XHIBIT-only | **T4** | **AC7**, AC1 | 3 fixtures converted; no new journeys; `PcfdlrmStub` fixed first (F1) |
| **NFR1** no production changes | T1 (the one build change), all | AC8 | One new test-scoped module, approved (Q1). `git diff --stat` over `src/main` empty at closure |
| **NFR2** runtime per layer | T1, T2 (12), T3 (11), T4 (10), T6 | AC8 | In-memory JSONassert; unit suites stay in `mvn test`; ITs gain no journeys |

### Story AC → coverage

| AC | Covered by | Verified how |
|---|---|---|
| **AC1** every scenario asserts ≥1 complete payload | T2 (10, 11), T3 (4, 5), T4 (5, 8), T5 (3), T6 (1, 4, 5, 6) | suite review + no `*` in any exclusion list |
| **AC2** new source system is data only | T1 (11), T2 (8), T3 (10), T5 (10), T6 (1) | T3 AC10 demonstrates it with a throwaway row |
| **AC3** accept and reject per FR5 field | **T5 (3, 4)** | 11 rows, grep-able by FR reference |
| AC4, AC5 | — | Moved to DD-43099 |
| **AC6** a dropped field makes a test fail | **G1** + T1 (10) | six live experiments, none committed |
| **AC7** no IT journey resolves to LIBRA | **T4 (1, 2, 3)** | `grep -rl LIBRA` over IT sources and resources. Only meaningful once T4 AC4 (F1) is done |
| **AC8** build green, no production file changed | T1 (1, 2), T2 (12), T3 (11), T4 (10), T5 (8), T6 (9) | full build + `git diff --stat -- '*/src/main/*'` empty |

---

## Closure checklist

- [ ] G1's six experiments demonstrated, all six failed as intended (AC6).
- [ ] `git diff --stat` against the merge base shows no change under any module's `src/main/` (NFR1, AC8).
- [ ] FR8 omission recorded: `MigratedCaseValidationRules` / `MigratedCaseSubmissionRejected` absent, not created.
- [ ] F11 recorded: the IT layer has no Event Grid stub and asserts no outcome file.
- [ ] **The FR5a carry closed on both sides** — the XHIBIT-unchanged AC added to DD-43081's requirements, or raised and accepted by that owner. While it stays open the gap is visible only from DD-43078's documents, which is the failure mode FR5 exists to prevent, one level up.
- [ ] T4 AC11's canary outcome written down — "no behavioural difference found", or a link to the issue raised on DD-43067.
- [ ] The F13 decision recorded in the T2 and T6 PR descriptions, whichever way it went.
- [ ] F1, F2, F12, F19 and F16 surfaced to the epic — **four** latent test defects predating this story (a stub that never verifies, ~20 vacuous IT assertions, a `notNullValue()` on an `Optional`, and `assertNotNull` on a test's own local variable), plus a schema duplication no story owns.
- [ ] F18 carried to DD-43086: the func-app gate's **root** is `additionalProperties: false`, so adding any property at the submission root is a breaking change to the gate.
- [ ] The two T2 fixture deviations accepted or rejected at review (see [Handover](#handover--picking-up-t3-onwards)).

## Handover — picking up T3 onwards

T1 and T2 are done and the reactor is green. What the next developer needs that is not obvious from
the diff:

**There is one `@Disabled` test in the suite, and it is a real production defect — not a flake.**
`StagingDlrmCommandHelperTest.shouldAssemblePayloadWhenHearingsAndDefendantsAreAbsent`, disabled as
`DD-XXXXX` (**the Jira key still needs filling in — raise the defect and replace the placeholder in
the `@Disabled` reason before this leaves review**).

`StagingDlrmCommandHelper.buildMigratedCaseJsonBuilder` (lines 177–181) copies `caseDetails`,
`hearings` and `defendants` into the outbound payload, guarding only the *parent* with `nonNull`:

```java
if (nonNull(migratedCaseJsonObject)) {
    migratedCaseJsonBuilder.add("caseDetails", migratedCaseJsonObject.getJsonObject("caseDetails"));
    migratedCaseJsonBuilder.add("hearings",    migratedCaseJsonObject.getJsonArray("hearings"));   // no guard
    migratedCaseJsonBuilder.add("defendants",  migratedCaseJsonObject.getJsonArray("defendants")); // no guard
}
```

`migrated-case.json` requires `caseDetails` **only**, so a case file that passes the Function App's
own gate with no `hearings` reaches this line, `getJsonArray` returns `null`, and JSON-P throws
`NullPointerException: Value in JsonObject's name/value pair cannot be null`. Verified against the
real class. `migrationSourceSystem`, three lines below, *does* get its `nonNull` guard.

Impact: the submission fails inside the Function App, before the POST. No
`error-migrated-case-submission` is raised either — that path is downstream of the failure — so no
outcome file is written and the uploader polls Blob Storage indefinitely. Confirm queue
redelivery/dead-lettering behaviour as part of the fix.

Latent rather than live: XHIBIT extracts appear always to populate both arrays, and it needs a
gate-valid payload missing one. Higher risk for LIBRA, whose field set is still being derived.

Fix shape: `nonNull` guard on each, matching `migrationSourceSystem`. Decide whether an absent array
is omitted or emitted as `[]` — check pcfdlrm and the canonical schema. `expected-no-hearings.json`
currently encodes *omitted*; update it if you choose `[]`. Then drop the `@Disabled`.

**Also unrecorded, found alongside it:** `CaseIdGenerator:9` derives the CPP Case File UUID with a
bare `getBytes()` — platform default charset — so the same URN could mint different UUIDs on JVMs
with different `file.encoding`. Latent only because URNs are ASCII today. `CaseIdGeneratorTest:25`
and `StagingdlrmCommandHandlerTest:95` compute the expected UUID the same way, so they would move
with the bug rather than catch it. Not in T3–T6 scope; raise separately if it is worth fixing.

**Use the T1 machinery; do not reinvent it.** `FixtureLoader.fixture(path, params)` +
`WholePayloadMatcher.matchesWholePayload(expected, exclusions)`.
`stagingdlrm-test-support` is already in the reactor; each consuming module still needs its own
`<scope>test</scope>` dependency added — only `stagingdlrm-azure-functions` has one so far. T2's
`StagingDlrmCommandHelperTest` is the worked example of the whole pattern.

**Generating an expected fixture.** Do not hand-write it. Write a throwaway test that dumps the real
payload, save that as the fixture, then delete the generator — then *prove the assertion bites* by
deleting one field from the fixture and watching it fail. Both T1 and T2 were verified this way and
T3–T6 should be too; it is the same discipline G1 formalises.

**Non-deterministic values are excluded per element, with a comment.** `metadata.id`,
`materials[0].id`, `materials[1].id` — never a bare `"id"`. A bare token matches nothing (exact-path
matching) and then fails as a stale exclusion, which is the intended friction.

**Start T3 with `StagingdlrmCommandHandlerTest.shouldReceiveMigratedCaseSubmission`** — F10. It has
zero assertions, so nothing in this repo currently pins the enveloped
`migrated-case-submission-received` payload, and the whole downstream chain hangs off that event.

**Start T4 by fixing `PcfdlrmStub`** — F1. Until `verifyReceiveCaseFileRequested` performs a real
WireMock verification, flipping the fixtures to XHIBIT proves nothing and AC7 is unverifiable.

**The `block-pii` hook will fire on you.** It matches CJS-URN-shaped identifiers, and several are
scattered through the remaining test suites as `azureLocation` values and case references. T1/T2
replaced every one in the files they touched with clearly synthetic values rather than setting
`CPP_HOOKS_DISABLE=1`. **Do the same** — do not disable the hook. Note it inspects Edit/Write content
only, so a scripted in-place edit via Bash slips past it; that is a gap, not a licence.

### Two deviations to confirm at review

1. **T2's 8 missing-property reject rows derive from one fixture** by removing a named property,
   rather than carrying 8 near-identical fixture directories as ADR-001 §5 implies. The scenario data
   is the property name. Adding a ninth required property is one `@ValueSource` entry, which serves
   AC2 better than the letter of §5. Flagged in the test's javadoc.
2. **`command-helper/` shares three case inputs across twelve scenarios** rather than one per
   scenario. `case-structural.json` backs the single pass-through pin; `case-minimal.json` serves
   every test whose subject is the material/metadata logic; `case-no-hearings.json` is the defect
   reproducer's input. Same trade-off, same reasoning.

Neither was waved through silently; both should be an explicit yes or no at the T2 PR.

## Compliance

Every fixture created by T2–T6 follows ADR-001's compliance note: **no real case data, defendant
names, URNs or court references.** Existing fixtures use synthetic values (`TVL55117DFXXV`, `C50EX02`,
`armagaddon house`); new fixtures follow the same pattern. Anything derived from an environment
payload is scrubbed before commit.

---

## Provenance

This artefact merges two independently-produced stage 3 drafts: one written inline, one by the
`story-writer` agent working from the same inputs without sight of the first. The findings section is
substantially the agent's — it read the code where the first draft trusted the design's description of
it. F7 is corrected here: the agent reported hamcrest and `test-utils-core` as absent from the
func-app test classpath; `mvn dependency:tree` shows they resolve transitively via
`test-utils-framework-persistence`. The recommendation it drew from that finding still stands for a
different reason, recorded in F7.
