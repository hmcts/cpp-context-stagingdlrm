package uk.gov.moj.cpp.stagingdlrm.command.handler.service;

import java.util.UUID;

public class CaseIdGenerator {

    public UUID generateCaseIdFromCaseUrnAndSubmissionId(final String caseUrn, final UUID submissionId) {
        final String normalizedUrn = caseUrn.trim();
        return UUID.nameUUIDFromBytes(normalizedUrn.concat(submissionId.toString()).getBytes());
    }
}


