package uk.gov.moj.cpp.stagingdlrm.it;

import static uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClientProvider.newPrivateJmsMessageConsumerClientProvider;
import static uk.gov.moj.cpp.stagingdlrm.helper.AbstractTestHelper.CONTEXT;
import static uk.gov.moj.cpp.stagingdlrm.helper.MigratedCaseSubmissionEventHelper.sendMigratedCaseFileProcessedEvent;
import static uk.gov.moj.cpp.stagingdlrm.helper.MigratedCaseSubmissionEventHelper.verifyPrivateEvents;

import uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClient;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CaseSubmissionProcessedIT {

    private static final String STAGINGDLRM_EVENTS_MIGRATED_CASE_SUBMISSION_PROCESSED = "stagingdlrm.events.migrated-case-submission-processed";

    private final JmsMessageConsumerClient stagingdlrmConsumer = newPrivateJmsMessageConsumerClientProvider(CONTEXT)
            .withEventNames(STAGINGDLRM_EVENTS_MIGRATED_CASE_SUBMISSION_PROCESSED)
            .getMessageConsumerClient();

    @Test
    void shouldRegisterCaseSubmissionProcessed() {
        final UUID caseId = UUID.randomUUID();
        final String caseUrn = UUID.randomUUID().toString();
        final String description = "Test Description";
        final Boolean processingIsSuccessful = false;
        final UUID submissionId = UUID.randomUUID();

        sendMigratedCaseFileProcessedEvent(submissionId, caseId, caseUrn, processingIsSuccessful, description);

        verifyPrivateEvents(stagingdlrmConsumer, caseId, submissionId, caseUrn, processingIsSuccessful, description);
    }
}
