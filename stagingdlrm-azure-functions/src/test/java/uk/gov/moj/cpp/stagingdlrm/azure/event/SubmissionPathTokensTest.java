package uk.gov.moj.cpp.stagingdlrm.azure.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * DD-43086 FR7 — {@code split}/{@code sourceSystem} are the single implementation both
 * EventGridTriggerJava's folder-name gate and TimerTriggerJava's schema selection call, so these
 * are the pure-function specs for that shared behaviour; {@code EventGridTriggerJavaTest} and
 * {@code TimerTriggerJavaTest} cover the two callers exercising it end to end.
 */
class SubmissionPathTokensTest {

    @ParameterizedTest(name = "split(\"{0}\") = {1}")
    @MethodSource("splitScenarios")
    void shouldSplitOnForwardSlash(final String path, final List<String> expectedTokens) {
        assertEquals(expectedTokens, SubmissionPathTokens.split(path));
    }

    static Stream<Arguments> splitScenarios() {
        return Stream.of(
                arguments("XHIBIT/Batch0001/case-1/submission-1", List.of("XHIBIT", "Batch0001", "case-1", "submission-1")),
                arguments("XHIBIT/Batch0001/case-1/submission-1/case.json",
                        List.of("XHIBIT", "Batch0001", "case-1", "submission-1", "case.json")),
                arguments("CASEREF-0001", List.of("CASEREF-0001")));
    }

    @ParameterizedTest(name = "sourceSystem(\"{0}\") = \"{1}\"")
    @MethodSource("sourceSystemScenarios")
    void shouldReturnTheFirstTokenLowerCased(final String path, final String expectedSourceSystem) {
        assertEquals(expectedSourceSystem, SubmissionPathTokens.sourceSystem(path));
    }

    static Stream<Arguments> sourceSystemScenarios() {
        return Stream.of(
                arguments("XHIBIT/Batch0001/case-1/submission-1", "xhibit"),
                arguments("LIBRA/Batch0001/case-1/submission-1", "libra"),
                arguments("MixedCase/Batch0001/case-1/submission-1", "mixedcase"),
                // EventGridTriggerJava's own gate deliberately does not trim the folder token itself
                // (it trims the *configured* dlrm_folder_name list instead) — this pins that
                // sourceSystem() doesn't silently start trimming and diverge from that behaviour.
                arguments(" XHIBIT/Batch0001/case-1/submission-1", " xhibit"));
    }
}