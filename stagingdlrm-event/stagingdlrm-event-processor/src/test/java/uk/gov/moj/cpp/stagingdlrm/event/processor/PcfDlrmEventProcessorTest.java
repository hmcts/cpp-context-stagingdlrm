package uk.gov.moj.cpp.stagingdlrm.event.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.createMigratedCaseFileProcessedPublicEvent;

import uk.gov.justice.services.core.sender.Sender;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.justice.services.messaging.JsonEnvelope;

import java.util.UUID;

import javax.json.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PcfDlrmEventProcessorTest {

    @Mock
    private Sender sender;

    @InjectMocks
    private PcfDlrmEventProcessor pcfDlrmEventProcessor;

    @Captor
    private ArgumentCaptor<Envelope<JsonObject>> envelopeArgumentCaptor;

    @Test
    void shouldHandleRecordSubmissionProcessingOutput() {

        final UUID caseId = UUID.randomUUID();
        final String caseUrn = UUID.randomUUID().toString();
        final String description = "Test Description";
        final Boolean processingIsSuccessful = false;
        final UUID submissionId = UUID.randomUUID();

        final JsonEnvelope jsonEnvelope = createMigratedCaseFileProcessedPublicEvent(submissionId, caseId, caseUrn, processingIsSuccessful, description);

        pcfDlrmEventProcessor.handleRecordSubmissionProcessingOutput(jsonEnvelope);

        verify(sender).send(envelopeArgumentCaptor.capture());

        final Envelope<JsonObject> jsonObjectEnvelope = envelopeArgumentCaptor.getValue();

        final JsonObject payload = jsonObjectEnvelope.payload();
        assertEquals(caseId.toString(), payload.getString("caseId"));
        assertEquals(submissionId.toString(), payload.getString("submissionId"));
        assertEquals(caseUrn, payload.getString("caseUrn"));
        assertEquals(processingIsSuccessful, payload.getBoolean("processingIsSuccessful"));
        assertEquals(description, payload.getString("description"));
    }

}