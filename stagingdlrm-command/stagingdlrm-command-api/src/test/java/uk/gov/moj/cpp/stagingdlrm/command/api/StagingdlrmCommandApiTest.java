package uk.gov.moj.cpp.stagingdlrm.command.api;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.verify;
import static uk.gov.justice.services.test.utils.core.messaging.JsonEnvelopeBuilder.envelope;
import static uk.gov.justice.services.test.utils.core.messaging.MetadataBuilderFactory.metadataWithDefaults;
import static uk.gov.moj.cpp.stagingdlrm.command.api.util.JsonUtil.readJsonResource;

import uk.gov.justice.services.core.sender.Sender;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.justice.services.messaging.JsonEnvelope;

import javax.json.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StagingdlrmCommandApiTest {

    @Mock
    private Sender sender;

    @InjectMocks
    private StagingdlrmCommandApi stagingdlrmCommandApi;

    @Captor
    private ArgumentCaptor<Envelope<JsonObject>> envelopeCaptor;


    @Test
    void shouldHandleReceiveMigratedCaseSubmission() {

        final JsonObject payload = readJsonResource("receive-migrated-case-submission.json");

        final JsonEnvelope envelope = envelope()
                .with(metadataWithDefaults().withName("stagingdlrm.receive-migrated-case-submission"))
                .withPayloadFrom(payload)
                .build();

        stagingdlrmCommandApi.receiveMigratedCaseSubmission(envelope);

        verify(sender).send(envelopeCaptor.capture());
        final Envelope<JsonObject> resultEnvelope = envelopeCaptor.getValue();

        assertThat(resultEnvelope.metadata().name(), is("stagingdlrm.command.handler.receive-migrated-case-submission"));
        assertThat(resultEnvelope.payload(), is(payload));
    }

    @Test
    void shouldHandleReceiveMigratedCaseSubmissionWithWeekCommencingDate() {

        final JsonObject payload = readJsonResource("receive-migrated-case-submission-with-wcd.json");

        final JsonEnvelope envelope = envelope()
                .with(metadataWithDefaults().withName("stagingdlrm.receive-migrated-case-submission"))
                .withPayloadFrom(payload)
                .build();

        stagingdlrmCommandApi.receiveMigratedCaseSubmission(envelope);

        verify(sender).send(envelopeCaptor.capture());
        final Envelope<JsonObject> resultEnvelope = envelopeCaptor.getValue();

        assertThat(resultEnvelope.metadata().name(), is("stagingdlrm.command.handler.receive-migrated-case-submission"));
        assertThat(resultEnvelope.payload(), is(payload));
    }

    @Test
    void shouldHandleReceiveErrorMigratedCaseSubmission() {
        final JsonObject payload = readJsonResource("receive-error-migrated-case-submission.json");
        final JsonEnvelope envelope = envelope()
                .with(metadataWithDefaults().withName("stagingdlrm.receive-error-migrated-case-submission"))
                .withPayloadFrom(payload)
                .build();
        stagingdlrmCommandApi.receiveErrorMigratedCaseSubmission(envelope);
        verify(sender).send(envelopeCaptor.capture());
        final Envelope<JsonObject> resultEnvelope = envelopeCaptor.getValue();
        assertThat(resultEnvelope.metadata().name(), is("stagingdlrm.command.handler.receive-error-migrated-case-submission"));
        assertThat(resultEnvelope.payload(), is(payload));

    }

}