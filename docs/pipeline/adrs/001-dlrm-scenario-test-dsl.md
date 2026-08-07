# ADR-001: Scenario-test DSL and whole-payload assertion convention for the DLRM test suites

## Status

**Accepted 2026-08-06** — at the stage 2 gate on
[DD-43078](https://tools.hmcts.net/jira/browse/DD-43078), whose gate decision Q1 approved §2's
new test-scoped module. Both DD-43078 and DD-43099 may start stage 5 against this convention.

The §2 fallback (duplication into each module's `src/test/java`, FR3/AC2 re-scoped per-module) is
**not taken for `cpp-context-stagingdlrm`**. DD-43099 makes that call independently for its own repo.

## Date

2026-08-06

## Scope — two stories, two repos, one convention

| Story | Repo | Artefacts |
|---|---|---|
| [DD-43078](../DD-43067-DD-43078-test-hardening/01-requirements.md) | `cpp-context-stagingdlrm` | this repo |
| [DD-43099](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/tree/main/docs/pipeline/DD-43067-DD-43099-pcfdlrm-test-hardening) | `cpp-context-prosecution-casefile-dlrm` | that repo |

This ADR is **single-homed here and linked from DD-43099** — never copied. A second copy would
drift the moment the decision changed, and nobody could tell which was current. Changes to it are
made here and take effect in both stories.

## Context

DD-43078 and DD-43099 harden the test suites of the two DLRM repos ahead of the LIBRA schema
relaxation (DD-43081). The two are independently deliverable — no shared code, no ordering
constraint — and will be done by different people in parallel.

Three requirements — worded identically in both stories — force a shared convention rather than two
local ones:

- **FR2** — every accepted command, appended domain event and outbound payload is asserted as a
  *complete* payload against a fixture, with non-deterministic values excluded by an explicit,
  enumerated list.
- **FR3** — the source system must be scenario *data*, not control flow: adding LIBRA later must
  not need a parallel test class, a copied fixture tree, or an `if` inside a test body.
- **FR4** — adopt a scenario-stream DSL in the spirit of `HearingFinancialResultsAggregateNCESTest`
  / `HearingFinancialResultAggregateTestSteps` in `cpp-context-results`, *where it earns its place*.

Without a settled convention the two repos would invent two dialects, and DD-43081/DD-43086 would
then extend both. Requirements note 1 asks for this decision to be recorded once, here.

Relevant facts established during design:

- Neither repo has a shared test-support module. `cpp-context-results` has one (`test-utilities`,
  a plain JAR carrying `TestUtilities` and `JsonMatcher`); the DLRM repos have per-module
  `ObjectBuilder` helpers instead (`stagingdlrm-event-processor`,
  `pcfdlrm-domain-aggregate/.../builder/ObjectBuilder.java`).
- `uk.gov.justice.services:test-utils-core` is **already a test dependency of nearly every module
  in both repos**, and ships `JsonSchemaValidationMatcher` with `isValidForSchema`,
  `isNotValidForSchema` and `failsValidationWithMessage`.
- The framework validates command payloads with **everit** (`org.everit.json.schema`) —
  `microservice-framework/core` `SchemaCatalogAwareJsonSchemaValidator` / `FileBasedJsonSchemaValidator`.
  `JsonSchemaValidationMatcher` uses the same library, so a schema pin written with it exercises
  the production validator rather than a lookalike.
- `cpp-context-results`' `JsonMatcher` excludes paths by compiling each entry to a regex with
  `*` → `.*?` and matching with `find()` — i.e. an unanchored substring match. `"materialId"`
  silently excludes *every* path containing that token, at any depth.

## Decision

### 1. Whole-payload comparison is JSONassert STRICT with anchored, enumerated exclusions

Each repo gets a `WholePayloadMatcher` ported from `cpp-context-results`' `JsonMatcher`, with three
deliberate changes:

- Exclusion entries are **exact JSON paths, compared by string equality** (`List.contains`, not the
  reference's unanchored regex `find()`). Because wildcards are rejected at construction, no regex
  is needed at all. Array elements are addressed individually (`defendants[0].defendantId`).
- An exclusion that **never matched any path** fails the test. Stale exclusions survive
  refactoring otherwise, and each one is a hole in FR2.
- An excluded path must still be **present** in the actual payload (this behaviour is inherited
  and kept) — the value is unasserted, the field's existence is not.

`JSONCompareMode.STRICT` is retained: an unexpected extra field in the actual payload is a failure.
That is what makes AC6 (drop a field → at least one test fails) hold in both directions.

Every exclusion carries an inline comment saying why it is non-deterministic. No exclusion is
added to make a test pass.

### 2. The support code lives in a new test-scoped module per repo

`stagingdlrm-test-support` and `pcfdlrm-test-support` — plain JARs, consumed with
`<scope>test</scope>`, holding:

```
FixtureLoader        # classpath fixture → String, with {{PARAM}} substitution
WholePayloadMatcher  # the comparison above
```

Both are written out in full in the [appendix](#appendix--the-support-classes-in-full), so
implementing them needs no access to `cpp-context-results`.

A third class, `Comparison` (a builder bundling excluded paths and fixture parameters), was
specified here and implemented, then **dropped before merge** — see the appendix note. Call sites
pass the exclusion list to `matchesWholePayload` directly.

Rationale: four to five consumers per repo (aggregate, command-handler, event-processor,
azure-functions, integration-test in stagingdlrm; aggregate, command-handler, event-processor,
integration-test in pcfdlrm) — too many to duplicate, and a `test-jar` attached to an existing
module creates a reactor ordering dependency the module boundary does not otherwise imply.

**No `Scenario`/`StepDef` classes** — see §3 for why the step-sequencing layer is deferred.

**This is a build change, not a production change.** No `src/main` file in any deployable module is
touched, no runtime artefact gains a dependency, and the new modules are not in any WAR. NFR1 ("no
production code changes") is read as satisfied; the design records it explicitly so the gate can
disagree if it wants to.

Existing `ObjectBuilder` helpers stay where they are. They build POJOs for tests that legitimately
do not need a fixture; they are not migrated wholesale.

### 3. Scenario rows now; step sequencing deferred

The reference pattern bundles two separable things: **parameterised scenario rows with
whole-payload assertions**, and a **`Scenario`/`StepDef` chain** that sequences several commands
against one aggregate. They have very different cost/benefit here, so they are decided separately.

**Counted, not assumed** — commands actually issued per test method:

| Suite | Tests | Command invocations | Multi-command tests |
|---|---|---|---|
| `MigratedCaseFileAggregateTest` (pcfdlrm) | 39 | 40 (`receiveMigratedCaseFile` ×37, `materialAddedPostProcessing` ×2, `acceptMigratedCase` ×1) | ~3 |
| `MigratedCaseSubmissionAggregateTest` (stagingdlrm) | 7 | 9 | 2 |

Both suites are **multi-variant, not multi-step**: essentially one command per test, varied by
input. Roughly five test methods across both repos would use step chaining, against ~400 lines of
ported infrastructure per repo.

**Decision: adopt the rows, defer the chain.** Scenarios are `@ParameterizedTest` +
`@MethodSource` rows; the few multi-command tests issue their commands as plain sequential calls
with a whole-payload assertion after each. If those tests become awkward to read, the
`Scenario`/`StepDef` layer is added then — the fixture layout and comparison semantics below are
unchanged by that, so it is an additive change, not a rewrite.

```java
@ParameterizedTest(name = "{index} => {0}")
@MethodSource("submissionScenarios")
void shouldHandleSubmission(final String name, final SubmissionScenario scenario) {
    final List<Object> events = scenario.run(new MigratedCaseSubmissionAggregate());
    scenario.assertExpectations(events);
}

static Stream<Arguments> submissionScenarios() {
    return Stream.of(
        Arguments.of("FR5 initiationCode 'O' is accepted (XHIBIT)",
            scenario()
                .withSourceSystem(XHIBIT)
                .withSubmission("json/aggregate/initiation-code-o/submission.json")
                .expectEvents("MigratedCaseSubmissionReceived")
                .expectPayload(
                    "MigratedCaseSubmissionReceived",
                    "json/aggregate/initiation-code-o/expected-received.json",
                    List.of("migratedCaseSubmission.migratedCase.defendants[0].defendantId")))  // minted per run
    );
}
```

The scenario object is a small per-suite builder living beside the test it serves, not a shared
abstraction — it names that suite's inputs (`withSubmission`, `withSourceSystem`) and delegates all
comparison to the shared `WholePayloadMatcher`. Two suites with different inputs do not share one.

Conventions:

- **Scenario name** = `"<FR or AC ref> <plain-English behaviour> (<source system>)"`. The FR/AC ref
  is what makes the FR5 pin list auditable at review; the source system is always stated even when
  it is the XHIBIT baseline (FR1).
- **A row** supplies one input fixture and declares expected event names *and* expected payloads.
  Declaring names without payloads is allowed only for events another row asserts whole.
- **A test body is two lines** — run, then assert. Any branching belongs in the scenario data.
- Rows are **not applied everywhere.** Suites with a single input and a single assertion — most
  `*ValidationRuleTest` classes in pcfdlrm — keep their present form and gain only the
  whole-payload matcher where they assert a payload. FR4 makes this a means, not a target.

### 4. Source system is a fixture parameter

Fixtures carry `"migrationSourceSystemName": "{{SOURCE_SYSTEM}}"`. `withSourceSystem(XHIBIT)` binds
it through the existing `{{PARAM}}` substitution. A scenario **must** call `withSourceSystem(...)`;
the run fails if it does not, so no scenario can pass by defaulting (FR1).

Where a source system needs a genuinely different payload rather than a different enum value, the
scenario row names its own fixture. Both routes are scenario data, so AC2 holds either way.

### 5. Fixture layout

```
src/test/resources/json/<component-slug>/<document>-<scenario>.json
```

`<component-slug>` is the unit under test, one directory per test class — `command-helper`,
`timer-trigger`, `schema-validator`, `aggregate`, `handler`, `event-processor`. `<document>` is the
payload the file *is* (`case`, `manifest`, `case-submission`, or `expected` for an outcome), and
`<scenario>` is what makes this variant different. So `command-helper/manifest-without-materials.json`
and `schema-validator/case-submission-extra-root-property.json`.

**Superseded: one directory per scenario.** This ADR first specified
`json/<component>/<scenario-slug>/<input>.json`, with input and expected in a shared directory so a
scenario was added or deleted as a unit. Applied to T2 it produced 28 files, 9 of them directories
holding a single `input.json`, and 6 whose entire content was `{}` — the directory level carried the
scenario name while every file inside was called the same thing, so nothing was greppable and every
tab in an editor read `input.json`. Flattening puts the scenario in the filename where it can be
scanned, sorted and grepped, at the cost of input and expected no longer being adjacent — which the
`<document>` prefix recovers, since a directory listing groups them anyway.

Existing integration-test fixtures keep their present flat layout and names — re-pointing them at
XHIBIT (FR10) is in scope, moving them is not.

### 6. Schema accept/reject pins use the framework's own validator

FR5's accepted-and-rejected pins are written with `test-utils-core`'s
`JsonSchemaValidationMatcher` (`isValidForSchema` / `isNotValidForSchema` /
`failsValidationWithMessage`) against the committed canonical schema. No new dependency, and the
pin exercises everit — the validator the command API actually uses — so a pin that passes means the
runtime accepts, not merely that a second validator agrees.

## Options considered

**Where the support code lives:**

| Option | Pros | Cons |
|---|---|---|
| **Shared test-scoped module per repo (chosen)** | One dialect per repo; 4–5 consumers each; no new third-party dependency | Two new Maven modules |
| Duplicate support classes into each module's `src/test/java` | No build change at all | 4–5 copies per repo drift immediately; the FR3 property silently degrades |
| `test-jar` from an existing module (e.g. `*-domain-aggregate`) | No new module | Reactor ordering coupling that the module boundary does not justify; awkward for `stagingdlrm-azure-functions`, which shares no framework stack |
| One shared library published across both repos | Single dialect, genuinely | New published artefact, versioning and release process — far beyond a test-hardening story |

**How much of the reference pattern to port:**

| Option | Pros | Cons |
|---|---|---|
| **Rows + whole-payload comparison, step chaining deferred (chosen)** | Delivers FR2/FR3/AC2 in full; ~400 fewer lines of infrastructure per repo; the chain stays available as an additive change | The ~5 multi-command tests read as sequential calls rather than declarative chains |
| Port `HearingFinancialResultAggregateTestSteps` wholesale | Matches the named reference exactly; multi-command tests read declaratively | ~400 lines per repo of infrastructure justified by ~5 test methods; the rest of the suite is single-command and gains nothing from it |
| No shared scenario mechanism at all — plain JUnit plus the matcher | Least code | Loses AC2: adding a source system becomes a code change in test bodies, not a data change |

**How to compare payloads:**

| Option | Pros | Cons |
|---|---|---|
| **JSONassert STRICT with anchored exclusions (chosen)** | Symmetric — catches added *and* dropped fields; readable diffs; exclusions are auditable | Requires the anchoring fix over the reference implementation |
| Assert whole payloads with `assertEquals` on generated POJOs | No JSON machinery | Generated POJO `equals` compares everything including minted UUIDs, with no exclusion mechanism; failure messages are unreadable at this payload size |

## Consequences

**Easier**

- Adding a LIBRA scenario in DD-43081/DD-43086 is a `Arguments.of(...)` row plus two fixtures.
- A dropped or added field anywhere in an outbound payload fails a test (AC6), because STRICT mode
  is symmetric and exclusions cannot be broadened by accident.
- The FR5 pin list is greppable — scenario names carry the FR reference.

**Harder**

- Fixture count rises substantially. Each scenario is a directory of two-plus files, and the
  payloads are large. This is the accepted cost of FR2.
- A legitimate payload change now touches every expected fixture that contains the field. That is
  the intended friction, but it makes DD-43081 a larger diff than it would otherwise be.
- The ~5 multi-command tests express their sequence as ordinary sequential calls rather than a
  declarative chain. Acceptable at that count; revisit if it grows.
- Two repos hold two copies of the support code. They will drift. Accepted for this story; if a
  third consumer appears, revisit the shared-library option. Deferring the chain shrinks what can
  drift by roughly two thirds.

**Follow-up**

- DD-43099's design doc and PR description link to this ADR by URL rather than restating it.
- Neither story needs `cpp-context-results` cloned or reachable — the appendix is the source for
  T1/T7. The repo remains worth reading if §3 is ever revisited, since
  `HearingFinancialResultAggregateTestSteps` is the step-chaining reference.
- If the gate rejects the new-module decision (section 2), the fallback is duplication into each
  module's `src/test/java`, and FR3/AC2 should be re-scoped to "per module" rather than "per repo".
- **Revisit §3 if step chaining is asked for twice.** The trigger is the multi-command test count
  growing past roughly ten in either repo, or a reviewer finding the sequential form unreadable.
  Adding `Scenario`/`StepDef` later is additive — fixture layout, comparison semantics and the
  source-system parameter are all unchanged by it.

## Compliance notes

Fixtures must contain no real case data, defendant names, URNs or court references — the plugin's
hard rule on PII in artefacts applies to test fixtures. Existing fixtures already use synthetic
values (`TVL55117DFXXV`, `LIBRA-offence-id-1`); new fixtures follow the same pattern, and any
fixture copied from an environment is scrubbed before commit.

---

## Appendix — the support classes in full

The three classes below are the whole of `*-test-support`. They are written out here so that
**neither story needs `cpp-context-results` cloned, or reachable, to implement T1/T7** — the ADR is
the source, and the differences from the reference implementation are already applied rather than
described. Adjust the package name per repo (`uk.gov.moj.cpp.stagingdlrm.test` /
`uk.gov.moj.cpp.pcfdlrm.test`).

> **Do not add a Maven dependency on `uk.gov.moj.cpp.results:test-utilities` instead of copying
> these.** That artefact depends on `results-domain-common` — a *domain* module of an unrelated
> bounded context — which would put results' domain classes on the test classpath of both DLRM
> repos and couple their builds to results' release cycle. Copy the ~150 lines.

**Only dependency needed:** `org.skyscreamer:jsonassert`, test scope. The version is managed in
`maven-common-bom` (`jsonassert.version` = 1.5.0), so the module declares groupId, artifactId and
scope only. Hamcrest is already on the test classpath in both repos. pcfdlrm already has jsonassert
in `pcfdlrm-integration-test`; stagingdlrm has it nowhere yet.

### `FixtureLoader`

```java
public final class FixtureLoader {

    private FixtureLoader() {
    }

    /** The empty JSON object — use instead of committing another one-line {@code {}} fixture. */
    public static String emptyJson() {
        return "{}";
    }

    public static String fixture(final String path) {
        return fixture(path, Map.of());
    }

    public static String fixture(final String path, final Map<String, String> parameters) {
        try (InputStream in = FixtureLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("Fixture not found on the test classpath: " + path);
            }
            String payload = new String(in.readAllBytes(), UTF_8);

            for (final Map.Entry<String, String> parameter : parameters.entrySet()) {
                final String token = "{{" + parameter.getKey() + "}}";
                if (!payload.contains(token)) {
                    throw new AssertionError(
                            "Parameter " + token + " was supplied but does not appear in " + path);
                }
                payload = payload.replace(token, parameter.getValue());
            }

            final int unresolved = payload.indexOf("{{");
            if (unresolved >= 0) {
                throw new AssertionError("Unresolved placeholder in " + path + ": "
                        + payload.substring(unresolved, min(unresolved + 40, payload.length())));
            }
            return payload;
        } catch (final IOException e) {
            throw new AssertionError("Failed to read fixture " + path, e);
        }
    }
}
```

Three deliberate differences from `TestUtilities.payloadAsString`:

- **UTF-8, not `defaultCharset()`.** Fixtures are UTF-8 on disk regardless of the platform locale;
  the reference reads them with whatever the JVM default happens to be. The charset is named in the
  loader, not enforced by a test — a test that proves it needs the JVM run under a non-UTF-8
  `file.encoding`, which is not worth a per-module surefire override.
- **A supplied parameter that appears nowhere in the fixture fails.** Otherwise a renamed
  placeholder silently stops being substituted.
- **An unresolved `{{…}}` left in the payload fails.** This is the FR1 guard: a fixture whose
  `{{SOURCE_SYSTEM}}` was never bound cannot reach the comparison and quietly pass.

`emptyJson()` exists so the "nothing in it" scenario does not need a file. Six fixtures whose whole
content was `{}` had accumulated across `stagingdlrm-azure-functions` before it was added.

### `Comparison` — specified, then dropped before merge

This ADR originally specified a `Comparison` builder bundling excluded paths and fixture
parameters, mirroring the reference's nested class. It was implemented, and then removed during
review of T1/T2: **nothing used it.** Every call site passes its exclusion list straight to
`matchesWholePayload(expected, List.of(...))` and its parameters straight to
`fixture(path, Map.of(...))`, because the two are consumed by different classes at different
moments — there is no point in the flow where one object carrying both is convenient. The builder
only earns its place alongside the `Scenario`/`StepDef` chain deferred in §3, which needs to carry
both through a row; revisit it then, not before.

The one principle it existed to enforce survives, and survives more strongly:

- **No default exclusions.** The reference pre-loads four (`gobAccountNumber`,
  `oldGobAccountNumber`, `materialId`, `notificationId`) into every comparison, so a test can
  exclude fields without saying so — exactly the invisible over-broad exclusion FR2 forbids. With
  the builder gone the exclusion list is a literal at the assertion, so there is nowhere for a
  default to hide. `WholePayloadMatcher` additionally fails any exclusion that matched no path, so
  an exclusion that is not doing visible work breaks the build.

### `WholePayloadMatcher`

```java
public class WholePayloadMatcher extends TypeSafeMatcher<String> {

    private final String expectedJson;
    private final List<String> excludedPaths;
    private final Set<String> matchedExclusions = new HashSet<>();

    /**
     * Why the last match failed, rendered by {@link #describeMismatchSafely}. Held rather than
     * thrown: throwing from a predicate keeps JSONassert's diff but breaks the {@code Matcher}
     * contract — no composing with {@code not(...)}, and both describe methods unreachable.
     */
    private String mismatchReason;

    private WholePayloadMatcher(final String expectedJson, final List<String> excludedPaths) {
        excludedPaths.forEach(WholePayloadMatcher::rejectWildcard);
        this.expectedJson = expectedJson;
        this.excludedPaths = List.copyOf(excludedPaths);
    }

    public static WholePayloadMatcher matchesWholePayload(final String expectedJson,
                                                          final List<String> excludedPaths) {
        return new WholePayloadMatcher(expectedJson, excludedPaths);
    }

    private static void rejectWildcard(final String path) {
        if (path.contains("*")) {
            throw new IllegalArgumentException(
                    "Wildcard exclusions are not permitted (DD-43078 FR2) — list each path explicitly. Got: "
                            + path);
        }
    }

    @Override
    protected boolean matchesSafely(final String actualJson) {
        matchedExclusions.clear();
        mismatchReason = null;

        final JSONCompareResult result;
        try {
            result = JSONCompare.compareJSON(expectedJson, actualJson, new ExactPathExclusionComparator());
        } catch (final JSONException e) {
            // One side is not parseable as JSON at all, so there is no diff to report.
            mismatchReason = "could not be compared as JSON: " + e.getMessage();
            return false;
        }
        if (result.failed()) {
            mismatchReason = result.getMessage();
            return false;
        }

        final List<String> unused = excludedPaths.stream()
                .filter(path -> !matchedExclusions.contains(path))
                .toList();
        if (!unused.isEmpty()) {
            mismatchReason = "exclusion(s) matched no path in the payload — correct or remove them; "
                    + "a stale exclusion is a hole in the assertion: " + unused;
            return false;
        }
        return true;
    }

    @Override
    public void describeTo(final Description description) {
        description.appendText("JSON equal to the expected fixture (STRICT");
        if (!excludedPaths.isEmpty()) {
            description.appendText(", excluding ").appendValue(excludedPaths);
        }
        description.appendText(")");
    }

    /** JSONassert's per-field diff, not a dump of the whole actual payload. */
    @Override
    protected void describeMismatchSafely(final String item, final Description mismatch) {
        if (mismatchReason == null) {
            mismatch.appendText("was ").appendValue(item);
        } else {
            mismatch.appendText(mismatchReason);
        }
    }

    /**
     * STRICT comparison in which an excluded path has its value skipped. Paths match by exact string
     * equality — no regex, no wildcards, no prefix matching.
     *
     * <p>Presence is still enforced, just not here: JSONassert reports a key missing from the actual
     * payload before {@code compareValues} is reached, so a skipped value cannot smuggle in a
     * skipped key.
     */
    private class ExactPathExclusionComparator extends CustomComparator {

        ExactPathExclusionComparator() {
            super(JSONCompareMode.STRICT);
        }

        @Override
        public void compareValues(final String jsonPath,
                                  final Object expectedValue,
                                  final Object actualValue,
                                  final JSONCompareResult result) throws JSONException {
            if (excludedPaths.contains(jsonPath)) {
                matchedExclusions.add(jsonPath);
            } else {
                super.compareValues(jsonPath, expectedValue, actualValue, result);
            }
        }
    }
}
```

This is where §1's decisions become code, and the differences from `JsonMatcher` are the point of
the class:

- **Exact string equality on the path, not a regex `find()`.** The reference converts each exclusion
  to a regex (`*` → `.*?`) and matches with `find()`, which is an *unanchored substring* match:
  `"materialId"` excludes every path containing that token at any depth. Because wildcards are
  forbidden, no regex is needed at all — `List.contains` is simpler, faster, and cannot
  over-match by accident.
- **Wildcards rejected at construction**, with a message naming FR2, so the failure explains itself.
- **An exclusion that matched nothing fails the test.** Stale exclusions survive refactoring
  otherwise, and each one is an unasserted field nobody is tracking.
- **It is a well-behaved `Matcher`.** A mismatch returns `false` and reports itself through
  `describeMismatchSafely`; it does not throw from `matchesSafely`. Throwing is tempting — it puts
  JSONassert's per-field diff straight in front of the reader — but it makes the matcher
  uncomposable (`not(...)` blows up rather than negating) and leaves both describe methods
  unreachable. Holding the reason in a field keeps the diff *and* the contract.

Two things to know when writing scenarios against it:

- **Paths are JSONassert's own format** — `migratedCase.defendants[0].defendantId`, with array
  elements indexed individually and no leading dot at the root. Address each element separately;
  that is the intended cost of forbidding wildcards.
- **An excluded path must still carry a value in the expected fixture.** STRICT mode compares key
  sets before values, so a path absent from the expected JSON fails as a mismatch rather than being
  skipped. Put a placeholder value there; the exclusion means *this value is not asserted*, not
  *this field is optional*.
