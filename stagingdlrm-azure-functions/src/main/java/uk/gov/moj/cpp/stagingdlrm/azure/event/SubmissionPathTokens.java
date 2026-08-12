package uk.gov.moj.cpp.stagingdlrm.azure.event;

import java.util.Arrays;
import java.util.List;

/**
 * Shared, stateless derivation of the source-system token from a submission blob path
 * (DD-43086 FR7).
 *
 * <p>The source-system token is validated by {@code EventGridTriggerJava}'s folder gate and
 * re-extracted in {@code TimerTriggerJava}; centralising the split here guarantees the value the
 * schema selection (FR4) keys on is provably the value the gate checked.
 */
public final class SubmissionPathTokens {

    private SubmissionPathTokens() {
    }

    public static List<String> split(final String path) {
        return Arrays.stream(path.split("/")).toList();
    }

    /**
     * First path token, lower-cased. No trim — preserves the exact behaviour the folder gate
     * already relies on (it never trimmed {@code tokens.get(0)}).
     */
    public static String sourceSystem(final String path) {
        return split(path).get(0).toLowerCase();
    }
}