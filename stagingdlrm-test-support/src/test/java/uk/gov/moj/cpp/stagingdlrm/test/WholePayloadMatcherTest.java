package uk.gov.moj.cpp.stagingdlrm.test;

import static java.util.List.of;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

import java.util.List;

import org.hamcrest.StringDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WholePayloadMatcherTest {

    private static final String EXPECTED = """
            {
              "submissionId": "S-1",
              "caseDetails": { "defendantId": "CD-1", "receiptType": "R" },
              "other":       { "defendantId": "OT-1" }
            }
            """;

    private static boolean matches(final String expected, final String actual,
                                   final List<String> exclusions) {
        return matchesWholePayload(expected, exclusions).matches(actual);
    }

    /** Asserts rejection and returns what the matcher told the reader — the {@code assertThat} path. */
    private static String mismatchFor(final String expected, final String actual,
                                      final List<String> exclusions) {
        final WholePayloadMatcher matcher = matchesWholePayload(expected, exclusions);

        assertFalse(matcher.matches(actual), "matcher should have rejected this payload");

        final StringDescription description = new StringDescription();
        matcher.describeMismatch(actual, description);
        return description.toString();
    }

    @Test
    @DisplayName("T1 AC6 rejects a wildcard exclusion at construction, naming FR2")
    void shouldRejectWildcardExclusion() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> matchesWholePayload(EXPECTED, of("caseDetails.*")));

        assertTrue(error.getMessage().contains("FR2"), error.getMessage());
        assertTrue(error.getMessage().contains("caseDetails.*"), error.getMessage());
    }

    @Test
    @DisplayName("matches an identical payload")
    void shouldMatchIdenticalPayload() {
        assertTrue(matches(EXPECTED, EXPECTED, of()));
    }

    /** The Matcher contract: returning false rather than throwing is what allows composition. */
    @Test
    @DisplayName("is a well-behaved Matcher — a mismatch returns false and is composable")
    void shouldReturnFalseRatherThanThrowOnMismatch() {
        final String actual = EXPECTED.replace("CD-1", "CHANGED");

        assertFalse(matchesWholePayload(EXPECTED, of()).matches(actual));
        assertTrue(not(matchesWholePayload(EXPECTED, of())).matches(actual));
    }

    @Test
    @DisplayName("describeTo names the comparison mode and the exclusions")
    void shouldDescribeItself() {
        final StringDescription description = new StringDescription();
        matchesWholePayload(EXPECTED, of("caseDetails.defendantId")).describeTo(description);

        assertTrue(description.toString().contains("STRICT"), description.toString());
        assertTrue(description.toString().contains("caseDetails.defendantId"), description.toString());
    }

    @Test
    @DisplayName("T1 AC10 STRICT — an extra field in actual fails")
    void shouldFailOnExtraFieldInActual() {
        final String actual = """
                {
                  "submissionId": "S-1",
                  "caseDetails": { "defendantId": "CD-1", "receiptType": "R" },
                  "other":       { "defendantId": "OT-1" },
                  "sneaked":     "in"
                }
                """;

        assertTrue(mismatchFor(EXPECTED, actual, of()).contains("sneaked"),
                mismatchFor(EXPECTED, actual, of()));
    }

    @Test
    @DisplayName("T1 AC10 STRICT — a missing field in actual fails")
    void shouldFailOnMissingFieldInActual() {
        final String actual = """
                {
                  "submissionId": "S-1",
                  "caseDetails": { "defendantId": "CD-1" },
                  "other":       { "defendantId": "OT-1" }
                }
                """;

        assertTrue(mismatchFor(EXPECTED, actual, of()).contains("receiptType"),
                mismatchFor(EXPECTED, actual, of()));
    }

    @Test
    @DisplayName("a payload that is not JSON at all is reported, not thrown")
    void shouldReportUnparseablePayload() {
        assertTrue(mismatchFor(EXPECTED, "not json", of()).contains("could not be compared as JSON"),
                mismatchFor(EXPECTED, "not json", of()));
    }

    /**
     * DD-43078 T1 AC7, the anchoring test. {@code cpp-context-results}' {@code JsonMatcher} matches
     * exclusions as unanchored regexes, so a bare token excludes that field at any depth. Here the
     * sibling {@code other.defendantId} is still compared, and still fails.
     */
    @Test
    @DisplayName("T1 AC7 an exact-path exclusion does not leak to a same-named field elsewhere")
    void shouldExcludeOnlyTheExactPath() {
        final String actual = """
                {
                  "submissionId": "S-1",
                  "caseDetails": { "defendantId": "CHANGED", "receiptType": "R" },
                  "other":       { "defendantId": "ALSO-CHANGED" }
                }
                """;

        // caseDetails.defendantId is excluded, so its changed value is tolerated...
        // ...but other.defendantId is not excluded, so the comparison still fails on it.
        final String mismatch = mismatchFor(EXPECTED, actual, of("caseDetails.defendantId"));

        assertTrue(mismatch.contains("other.defendantId"), mismatch);
        assertFalse(mismatch.contains("caseDetails.defendantId"), mismatch);
    }

    /**
     * DD-43078 T1 AC7/AC8 — a bare field name is not a path. The reference would have excluded both
     * {@code defendantId} fields; here it matches nothing and is reported stale.
     */
    @Test
    @DisplayName("T1 AC7 a bare field name matches no path and is reported as stale")
    void shouldNotTreatABareFieldNameAsAPath() {
        final String mismatch = mismatchFor(EXPECTED, EXPECTED, of("defendantId"));

        assertTrue(mismatch.contains("matched no path"), mismatch);
        assertTrue(mismatch.contains("defendantId"), mismatch);
    }

    @Test
    @DisplayName("T1 AC8 an exclusion that matched no path fails the test")
    void shouldFailOnStaleExclusion() {
        final String mismatch = mismatchFor(EXPECTED, EXPECTED, of("caseDetails.renamedLastSprint"));

        assertTrue(mismatch.contains("matched no path"), mismatch);
        assertTrue(mismatch.contains("caseDetails.renamedLastSprint"), mismatch);
    }

    @Test
    @DisplayName("T1 AC9 an excluded path with a different value passes")
    void shouldPassWhenExcludedPathHasADifferentValue() {
        final String actual = """
                {
                  "submissionId": "S-1",
                  "caseDetails": { "defendantId": "REGENERATED", "receiptType": "R" },
                  "other":       { "defendantId": "OT-1" }
                }
                """;

        assertTrue(matches(EXPECTED, actual, of("caseDetails.defendantId")));
    }

    @Test
    @DisplayName("T1 AC9 an excluded path absent from actual fails — presence is still enforced")
    void shouldFailWhenExcludedPathIsAbsentFromActual() {
        final String actual = """
                {
                  "submissionId": "S-1",
                  "caseDetails": { "receiptType": "R" },
                  "other":       { "defendantId": "OT-1" }
                }
                """;

        assertTrue(mismatchFor(EXPECTED, actual, of("caseDetails.defendantId")).contains("defendantId"),
                mismatchFor(EXPECTED, actual, of("caseDetails.defendantId")));
    }
}
