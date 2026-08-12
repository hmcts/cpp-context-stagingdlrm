package uk.gov.moj.cpp.stagingdlrm.azure.event;

import java.util.List;

/**
 * DD-43086 FR7 — the one place that tokenizes a migration submission path
 * ({@code folder/batch/case/submissionId}, whether the fuller blob path EventGridTriggerJava sees
 * or the queue message TimerTriggerJava re-reads) and derives its source-system token.
 *
 * <p>Before this, both classes split the path with their own inline {@code split("/")} and lower-cased
 * token 0 independently — harmless while the two copies agreed, but nothing stopped them drifting.
 * EventGridTriggerJava's folder-name gate and TimerTriggerJava's schema-selection lookup now call
 * the same method, so the value schema selection keys on is provably the value the gate already
 * checked.
 */
public final class SubmissionPathTokens {

    private SubmissionPathTokens() {
    }

    public static List<String> split(final String path) {
        return List.of(path.split("/"));
    }

    /** Token 0, lower-cased — the folder name / source system, whichever the caller calls it. */
    public static String sourceSystem(final String path) {
        return split(path).get(0).toLowerCase();
    }
}