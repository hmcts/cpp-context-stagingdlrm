package uk.gov.moj.cpp.stagingdlrm.event.processor;

import static java.util.List.of;
import static java.util.UUID.fromString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.createMigratedCaseFileProcessedPublicEvent;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

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

    private static final UUID SUBMISSION_ID = fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CASE_ID = fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final String CASE_URN = "TVL55117DFXXV";

    @Mock
    private Sender sender;

    @InjectMocks
    private PcfDlrmEventProcessor pcfDlrmEventProcessor;

    @Captor
    private ArgumentCaptor<Envelope<JsonObject>> envelopeArgumentCaptor;

    @Test
    void shouldForwardRecordSubmissionProcessingOutputWholePayload() {
        final JsonEnvelope jsonEnvelope = createMigratedCaseFileProcessedPublicEvent(SUBMISSION_ID, CASE_ID, CASE_URN, false, "Test Description");

        pcfDlrmEventProcessor.handleRecordSubmissionProcessingOutput(jsonEnvelope);

        verify(sender).send(envelopeArgumentCaptor.capture());
        final Envelope<JsonObject> forwarded = envelopeArgumentCaptor.getValue();

        assertEquals("stagingdlrm.command.handler.record-submission-processing-output", forwarded.metadata().name());
        assertThat(forwarded.payload().toString(),
                matchesWholePayload(fixture("json/pcf-dlrm/record-submission-processing-output.json"), of()));
    }
}
