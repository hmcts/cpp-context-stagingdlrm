package uk.gov.moj.cpp.stagingdlrm.helper;

import static java.util.UUID.randomUUID;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static uk.gov.moj.cpp.stagingdlrm.helper.QueueUtil.retrieveMessage;
import static uk.gov.moj.cpp.stagingdlrm.helper.QueueUtil.sendPublicEvent;

import uk.gov.justice.services.common.converter.StringToJsonObjectConverter;
import uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClient;
import uk.gov.justice.services.messaging.JsonEnvelope;

import java.util.Optional;
import java.util.UUID;

import javax.json.JsonObject;

public class MigratedCaseSubmissionEventHelper {

    public static final String PUBLIC_PCFDLRM_MIGRATED_CASE_FILE_PROCESSED = "public.pcfdlrm.migrated-case-file-processed";

    public static void sendMigratedCaseFileProcessedEvent(final UUID submissionId, final UUID caseId,
            final String caseUrn, final Boolean processingIsSuccessful, final String description) {

        final String jsonData = """
                {
                   "submissionId": "%s",
                   "caseId": "%s",
                   "caseUrn": "%s",
                   "processingIsSuccessful": %s,
                   "description": "%s"
                }
                """.formatted(submissionId, caseId, caseUrn, processingIsSuccessful, description);

        sendPublicEvent(
                PUBLIC_PCFDLRM_MIGRATED_CASE_FILE_PROCESSED,
                new StringToJsonObjectConverter().convert(jsonData),
                JsonEnvelope.metadataBuilder()
                        .withId(randomUUID())
                        .withName(PUBLIC_PCFDLRM_MIGRATED_CASE_FILE_PROCESSED)
                        .withUserId(randomUUID().toString())
                        .build());
    }

    public static void verifyPrivateEvents(final JmsMessageConsumerClient consumer,
            final UUID caseId, final UUID submissionId, final String caseUrn,
            final Boolean processingIsSuccessful, final String description) {

        final Optional<JsonEnvelope> envelopeStream = retrieveMessage(consumer);
        final Optional<JsonEnvelope> jsonEnvelope = envelopeStream.stream().findFirst();

        assertThat(jsonEnvelope.isPresent(), is(true));

        final JsonObject jsonObject = jsonEnvelope.get().payloadAsJsonObject();
        final JsonObject migratedCaseSubmissionProcessed = jsonObject.getJsonObject("migratedCaseSubmissionProcessed");

        assertThat(migratedCaseSubmissionProcessed.getString("caseId"), is(caseId.toString()));
        verifyPrivateEvents(submissionId, caseUrn, processingIsSuccessful, description, migratedCaseSubmissionProcessed);
    }

    private static void verifyPrivateEvents(final UUID submissionId, final String caseUrn, final Boolean processingIsSuccessful, final String description, final JsonObject migratedCaseSubmissionProcessed) {
        assertThat(migratedCaseSubmissionProcessed.getString("submissionId"), is(submissionId.toString()));
        assertThat(migratedCaseSubmissionProcessed.getString("caseUrn"), is(caseUrn));
        assertThat(migratedCaseSubmissionProcessed.getString("description"), is(description));
        assertThat(migratedCaseSubmissionProcessed.getBoolean("processingIsSuccessful"), is(processingIsSuccessful));
    }

    public static void verifyPrivateEvents(final JmsMessageConsumerClient consumer,
                                           final UUID submissionId, final String caseUrn,
                                           final Boolean processingIsSuccessful, final String description) {

        final Optional<JsonEnvelope> envelopeStream = retrieveMessage(consumer);
        final Optional<JsonEnvelope> jsonEnvelope = envelopeStream.stream().findFirst();

        assertThat(jsonEnvelope.isPresent(), is(true));

        final JsonObject jsonObject = jsonEnvelope.get().payloadAsJsonObject();
        final JsonObject migratedCaseSubmissionProcessed = jsonObject.getJsonObject("migratedCaseSubmissionProcessed");

        verifyPrivateEvents(submissionId, caseUrn, processingIsSuccessful, description, migratedCaseSubmissionProcessed);
    }
}
