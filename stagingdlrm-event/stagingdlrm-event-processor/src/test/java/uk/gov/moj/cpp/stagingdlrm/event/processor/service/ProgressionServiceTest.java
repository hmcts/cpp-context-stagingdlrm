package uk.gov.moj.cpp.stagingdlrm.event.processor.service;

import static java.util.UUID.randomUUID;
import static javax.json.Json.createObjectBuilder;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.messaging.Envelope.envelopeFrom;
import static uk.gov.justice.services.messaging.JsonEnvelope.metadataBuilder;

import uk.gov.justice.services.core.requester.Requester;
import uk.gov.justice.services.messaging.Envelope;

import java.util.Optional;
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
class ProgressionServiceTest {

    @Mock
    private Requester requester;

    @InjectMocks
    private ProgressionService progressionService;

    @Captor
    private ArgumentCaptor<Envelope<JsonObject>> envelopeCaptor;

    @Test
    void shouldCallProgressionWithCaseIdWhenGettingProsecutionCaseDetails() {
        final UUID caseId = randomUUID();
        final JsonObject responsePayload = createObjectBuilder()
                .add("prosecutionCase", createObjectBuilder().add("caseStatus", "ACTIVE").build())
                .build();
        final Envelope<JsonObject> responseEnvelope = envelopeFrom(
                metadataBuilder().withId(randomUUID()).withName("progression.query.prosecutioncase").build(),
                responsePayload);

        when(requester.requestAsAdmin(any(Envelope.class), eq(JsonObject.class))).thenReturn(responseEnvelope);

        final Optional<JsonObject> result = progressionService.getProsecutionCaseDetails(caseId);

        verify(requester).requestAsAdmin(envelopeCaptor.capture(), eq(JsonObject.class));
        final Envelope<JsonObject> capturedEnvelope = envelopeCaptor.getValue();
        assertThat(capturedEnvelope.metadata().name(), is("progression.query.prosecutioncase"));
        assertThat(capturedEnvelope.payload().getString("caseId"), is(caseId.toString()));
        assertTrue(result.isPresent());
        assertThat(result.get(), is(responsePayload));
    }

    @Test
    void shouldReturnEmptyWhenProgressionReturnsNullPayload() {
        final UUID caseId = randomUUID();
        final Envelope<JsonObject> responseEnvelope = envelopeFrom(
                metadataBuilder().withId(randomUUID()).withName("progression.query.prosecutioncase").build(),
                null);

        when(requester.requestAsAdmin(any(Envelope.class), eq(JsonObject.class))).thenReturn(responseEnvelope);

        final Optional<JsonObject> result = progressionService.getProsecutionCaseDetails(caseId);

        assertTrue(result.isEmpty());
    }
}
