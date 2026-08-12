package uk.gov.moj.cpp.stagingdlrm.azure.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * DD-43086 LIBRA01/FR7 — pins the contract of the new shared source-system token helper.
 *
 * <p>No prior suite exists to extend: {@code SubmissionPathTokens} is a new stateless utility whose
 * whole reason to exist is that the source-system token derivation was hand-duplicated across
 * {@code EventGridTriggerJava}, {@code TimerTriggerJava} and {@code EventGridMonitor}. FR4's schema
 * selection (LIBRA03) must key on exactly the value the FR1 folder gate (LIBRA01) checked, so the
 * two contracts pinned here are load-bearing:
 *
 * <ul>
 *   <li>{@code split} returns the {@code /}-delimited tokens in order — the same split the gate and
 *       the timer both do today;</li>
 *   <li>{@code sourceSystem} returns the first token <b>lower-cased only, never trimmed</b>
 *       ({@code 02-design.md} §"FR7"): the folder gate compares {@code tokens.get(0)} without
 *       trimming, so trimming here would be a silent behaviour change (NFR1), and lower-casing is
 *       what lines the value up with the lower-cased FR4 map keys and the lower-cased FR1 folder
 *       list.</li>
 * </ul>
 */
class SubmissionPathTokensTest {

    private static final String XHIBIT_PATH = "XHIBIT/batch1/CASEREF-0001/submission1";
    private static final String LIBRA_PATH = "LIBRA/batch1/CASEREF-0001/submission1";

    @Test
    @DisplayName("LIBRA01/FR7 split returns the slash-delimited path tokens in order")
    void shouldSplitPathIntoOrderedTokens() {
        assertEquals(List.of("LIBRA", "batch1", "CASEREF-0001", "submission1"),
                SubmissionPathTokens.split(LIBRA_PATH),
                "split must return the four path tokens in order");
    }

    @Test
    @DisplayName("LIBRA01/FR7 split of a single-token path is a single-element list")
    void shouldSplitSingleTokenPath() {
        assertEquals(List.of("CASEREF-0001"), SubmissionPathTokens.split("CASEREF-0001"));
    }

    static Stream<Arguments> sourceSystemScenarios() {
        return Stream.of(
                arguments("LIBRA01/FR7 the XHIBIT first token is returned lower-cased",
                        XHIBIT_PATH, "xhibit"),
                arguments("LIBRA01/FR7 the LIBRA first token is returned lower-cased",
                        LIBRA_PATH, "libra"),
                arguments("LIBRA01/FR7 an already-lower-cased token is returned unchanged",
                        "libra/batch1/CASEREF-0001/submission1", "libra"),
                // Pins the deliberate no-trim contract: the gate never trimmed the token, so neither may this.
                arguments("LIBRA01/FR7 the token is lower-cased but NOT trimmed",
                        " LIBRA/batch1/CASEREF-0001/submission1", " libra"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sourceSystemScenarios")
    void shouldDeriveSourceSystem(final String scenario, final String path, final String expected) {
        assertEquals(expected, SubmissionPathTokens.sourceSystem(path), scenario);
    }
}