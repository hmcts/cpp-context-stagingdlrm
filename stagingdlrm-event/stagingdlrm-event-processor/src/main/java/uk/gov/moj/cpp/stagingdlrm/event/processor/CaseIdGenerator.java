package uk.gov.moj.cpp.stagingdlrm.event.processor;

import java.util.UUID;

public class CaseIdGenerator {

    public static UUID generateCaseIdFromCaseUrnAndSubmissionId(final String caseUrn, final UUID submissionId) {
        final String normalizedUrn = caseUrn.trim();
        return UUID.nameUUIDFromBytes(normalizedUrn.concat(submissionId.toString()).getBytes());
    }
}


