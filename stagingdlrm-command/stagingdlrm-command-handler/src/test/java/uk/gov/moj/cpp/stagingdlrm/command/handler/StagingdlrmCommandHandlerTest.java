package uk.gov.moj.cpp.stagingdlrm.command.handler;

import static java.util.UUID.fromString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.messaging.Envelope.envelopeFrom;
import static uk.gov.justice.services.test.utils.core.helper.EventStreamMockHelper.verifyAppendAndGetArgumentFrom;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;
import uk.gov.justice.services.core.aggregate.AggregateService;
import uk.gov.justice.services.core.enveloper.Enveloper;
import uk.gov.justice.services.eventsourcing.source.core.EventSource;
import uk.gov.justice.services.eventsourcing.source.core.EventStream;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.justice.services.messaging.JsonEnvelope;
import uk.gov.justice.services.messaging.Metadata;
import uk.gov.justice.services.test.utils.core.enveloper.EnveloperFactory;
import uk.gov.moj.cpp.stagingdlrm.aggregate.MigratedCaseSubmissionAggregate;
import uk.gov.moj.cpp.stagingdlrm.command.handler.service.CaseIdGenerator;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.CaseAlreadyProcessedAndExistsInProgressionCommand;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.stagingdlrm.domain.event.CaseAlreadyProcessedAndExistsInProgression;
import uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import javax.json.Json;
import javax.json.JsonObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StagingdlrmCommandHandlerTest {

    private static final UUID SUBMISSION_ID = fromString("11111111-2222-3333-4444-555555555555");

    private static final UUID ERROR_SUBMISSION_ID = fromString("99999999-8888-7777-6666-555555555555");

    private static final UUID PROCESSED_SUBMISSION_ID = fromString("77777777-6666-5555-4444-333333333333");

    private static final UUID PROGRESSION_CASE_ID = fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProducer().objectMapper();

    private static final JsonObjectToObjectConverter CONVERTER =
            new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());

    @InjectMocks
    private StagingdlrmCommandHandler stagingdlrmCommandHandler;

    @Mock
    private EventStream eventStream;
    @Mock
    private AggregateService aggregateService;
    @Mock
    private EventSource eventSource;

    @Mock
    private MigratedCaseSubmissionAggregate migratedCaseSubmissionAggregate;

    @Mock
    private Envelope<MigratedCaseSubmission> migratedCaseSubmissionEnvelope;

    @Mock
    private CaseIdGenerator caseIdGenerator;

    @Captor
    private ArgumentCaptor<java.util.stream.Stream<JsonEnvelope>> eventCaptor;

    @Spy
    private final Enveloper enveloper = EnveloperFactory.createEnveloperWithEvents(
            CaseAlreadyProcessedAndExistsInProgression.class, ErrorMigratedCaseSubmissionReceived.class, MigratedCaseSubmissionProcessed.class);

    @Test
    void shouldReceiveMigratedCaseSubmission() throws Exception {
        final MigratedCaseSubmission input = submission("json/handler/submission-with-materials.json");

        when(migratedCaseSubmissionEnvelope.payload()).thenReturn(input);
        when(eventSource.getStreamById(SUBMISSION_ID)).thenReturn(eventStream);
        when(aggregateService.get(eventStream, MigratedCaseSubmissionAggregate.class)).thenReturn(migratedCaseSubmissionAggregate);

        final ArgumentCaptor<MigratedCaseSubmission> migratedCaseSubmissionCaptor = forClass(MigratedCaseSubmission.class);

        stagingdlrmCommandHandler.receiveMigratedCaseSubmission(migratedCaseSubmissionEnvelope);

        verify(migratedCaseSubmissionAggregate).receiveMigratedCaseSubmission(migratedCaseSubmissionCaptor.capture());

        assertThat(serialise(migratedCaseSubmissionCaptor.getValue()), matchesWholePayload(
                fixture("json/handler/expected-captured-submission.json"),
                List.of("migratedCase.defendants[0].id",
                        "migratedCase.defendants[1].id")));
    }

    @Test
    void shouldReceiveErrorMigratedCaseSubmission() throws Exception {
        final ErrorMigratedCaseSubmission errorMigratedCaseSubmission = ErrorMigratedCaseSubmission
                .errorMigratedCaseSubmission()
                .withSubmissionId(ERROR_SUBMISSION_ID)
                .withPayload("sample text")
                .build();

        final Metadata metadata = Envelope.metadataBuilder()
                .withName("stagingdlrm.command.handler.receive-error-case-submission")
                .withId(UUID.randomUUID())
                .build();

        withRealAggregateStream(ERROR_SUBMISSION_ID);

        stagingdlrmCommandHandler.receiveErrorMigratedCaseSubmission(envelopeFrom(metadata, errorMigratedCaseSubmission));

        final List<JsonEnvelope> events = verifyAppendAndGetArgumentFrom(eventStream).toList();

        assertThat(events.size(), is(1));
        assertEvent(events.get(0),
                "stagingdlrm.events.error-migrated-case-submission-received",
                "json/handler/expected-error.json");
    }

    @Test
    void shouldRecordMigratedCaseSubmissionOutput() throws Exception {
        final MigratedCaseSubmissionProcessedOutput migratedCaseSubmissionProcessedOutput = MigratedCaseSubmissionProcessedOutput
                .migratedCaseSubmissionProcessedOutput()
                .withProcessingIsSuccessful(true)
                .withCaseId(PROCESSED_SUBMISSION_ID)
                .withSubmissionId(PROCESSED_SUBMISSION_ID)
                .withCaseUrn("caseUrn")
                .build();

        final Metadata metadata = Envelope.metadataBuilder()
                .withName("stagingdlrm.command.handler.record-submission-processing-output")
                .withId(UUID.randomUUID())
                .build();

        withRealAggregateStream(PROCESSED_SUBMISSION_ID);

        stagingdlrmCommandHandler.recordMigratedCaseSubmissionOutput(envelopeFrom(metadata, migratedCaseSubmissionProcessedOutput));

        final List<JsonEnvelope> events = verifyAppendAndGetArgumentFrom(eventStream).toList();

        assertThat(events.size(), is(1));
        assertEvent(events.get(0),
                "stagingdlrm.events.migrated-case-submission-processed",
                "json/handler/expected-processed.json");
    }

    @Test
    void shouldReceiveCaseAlreadyProcessed() throws Exception {
        final MigratedCaseSubmission input = submission("json/handler/submission-with-materials.json");

        final CaseAlreadyProcessedAndExistsInProgressionCommand command = CaseAlreadyProcessedAndExistsInProgressionCommand
                .caseAlreadyProcessedAndExistsInProgressionCommand()
                .withCaseId(PROGRESSION_CASE_ID)
                .withMigratedCaseSubmission(input)
                .build();

        final Metadata metadata = Envelope.metadataBuilder()
                .withName("stagingdlrm.command.handler.case-already-exists-in-progression")
                .withId(UUID.randomUUID())
                .build();

        withRealAggregateStream(SUBMISSION_ID);

        stagingdlrmCommandHandler.receiveCaseAlreadyProcessed(envelopeFrom(metadata, command));

        final List<JsonEnvelope> events = verifyAppendAndGetArgumentFrom(eventStream).toList();

        assertThat(events.size(), is(2));
        assertEvent(events.get(0),
                "stagingdlrm.events.case-already-processed-and-exists-in-progression",
                "json/handler/expected-case-already.json");
        assertEvent(events.get(1),
                "stagingdlrm.events.migrated-case-submission-processed",
                "json/handler/expected-processed-already.json");
    }

    private void withRealAggregateStream(final UUID streamId) throws uk.gov.justice.services.eventsourcing.source.core.exception.EventStreamException {
        when(eventSource.getStreamById(streamId)).thenReturn(eventStream);
        when(aggregateService.get(eventStream, MigratedCaseSubmissionAggregate.class)).thenReturn(new MigratedCaseSubmissionAggregate());
        when(eventStream.append(eventCaptor.capture())).thenReturn(1L);
    }

    private static void assertEvent(final JsonEnvelope event, final String expectedName, final String expectedFixture) {
        assertThat(event.metadata().name(), is(expectedName));
        assertThat(event.payloadAsJsonObject().toString(), matchesWholePayload(fixture(expectedFixture), List.of()));
    }

    private static MigratedCaseSubmission submission(final String fixtureName) {
        final JsonObject json = Json.createReader(
                new ByteArrayInputStream(fixture(fixtureName).getBytes(StandardCharsets.UTF_8))).readObject();
        return CONVERTER.convert(json, MigratedCaseSubmission.class);
    }

    private static String serialise(final Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (final JsonProcessingException e) {
            throw new AssertionError("Failed to serialise " + value, e);
        }
    }
}
