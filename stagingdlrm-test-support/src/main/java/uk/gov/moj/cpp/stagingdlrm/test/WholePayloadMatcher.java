package uk.gov.moj.cpp.stagingdlrm.test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import org.skyscreamer.jsonassert.comparator.CustomComparator;

/**
 * Compares a whole JSON payload against an expected fixture in {@link JSONCompareMode#STRICT} mode,
 * with anchored, enumerated exclusions.
 *
 * <p>Three differences from {@code cpp-context-results}' {@code JsonMatcher} are the point of the
 * class (ADR-001 §1): an exclusion matches a path by exact string equality, not an unanchored regex
 * {@code find()} that would exclude every path containing the token at any depth; wildcards are
 * rejected at construction; and an exclusion matching nothing fails the test rather than lingering
 * as a hole in the assertion.
 */
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
