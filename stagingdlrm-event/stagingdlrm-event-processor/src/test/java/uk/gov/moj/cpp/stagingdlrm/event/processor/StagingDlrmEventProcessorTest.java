package uk.gov.moj.cpp.stagingdlrm.event.processor;

import static java.util.UUID.fromString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.CASE_ID;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.CASE_URN;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.SUBMISSION_ID;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildCaseSubmissionProcessed;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildMetaData;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildMigratedCaseSubmissionReceived;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildMigratedCaseSubmissionReceivedWithMaterial;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.domain.MigratedGender.getValueFromCode;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;
import uk.gov.justice.services.core.sender.Sender;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.moj.cpp.stagingdlrm.event.processor.convertor.MigratedCaseConvertor;
import uk.gov.moj.cpp.stagingdlrm.event.processor.counter.ErrorMigratedCaseSubmissionReceivedCounter;
import uk.gov.moj.cpp.stagingdlrm.event.processor.counter.MigratedCaseSubmissionProcessedCounter;
import uk.gov.moj.cpp.stagingdlrm.event.processor.counter.MigratedCaseSubmissionReceivedCounter;
import uk.gov.moj.cpp.stagingdlrm.event.processor.domain.Outcome;
import uk.gov.moj.cpp.stagingdlrm.event.processor.service.SystemMapperService;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.Channel;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.CaseAlreadyProcessedAndExistsInProgressionCommand;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.cps.pcfdlrm.command.api.ReceiveMigratedCaseFile;
import uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonObject;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StagingDlrmEventProcessorTest {

    private static final UUID FIXED_CASE_ID = fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final UUID FIXED_SUBMISSION_ID = fromString("11111111-2222-3333-4444-555555555555");
    private static final String FIXED_CASE_URN = "TVL55117DFXXV";
    private static final String AZURE_LOCATION = "XHIBIT/20082025/case-identifier-0001/11111111-2222-3333-4444-555555555555";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProducer().objectMapper();
    private static final JsonObjectToObjectConverter CONVERTER =
            new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());

    @InjectMocks
    private StagingDlrmEventProcessor eventProcessor;

    @Mock
    private Sender sender;

    @Mock
    private Envelope<MigratedCaseSubmissionReceived> envelope;

    @Mock
    private Envelope<MigratedCaseSubmissionProcessed> migratedCaseSubmissionProcessedEnvelope;

    @Mock
    private Envelope<ErrorMigratedCaseSubmissionReceived> errorMigratedCaseSubmissionReceivedEnvelope;

    @Mock
    private EventGridService eventGridService;

    @Spy
    private MigratedCaseConvertor migratedCaseConvertor = new MigratedCaseConvertor();

    @Mock
    private SystemMapperService systemMapperService;

    @Captor
    private ArgumentCaptor<Outcome> outcomeEventArgumentCaptor;

    @Mock
    private MigratedCaseSubmissionReceivedCounter migratedCaseSubmissionReceivedCounter;

    @Mock
    private MigratedCaseSubmissionProcessedCounter migratedCaseSubmissionProcessedCounter;

    @Mock
    private ErrorMigratedCaseSubmissionReceivedCounter errorMigratedCaseSubmissionReceivedCounter;

    static Stream<Arguments> receiveScenarios() {
        return Stream.of(
                Arguments.of(
                        "FR2 maximal — every optional field populated, collections >= 2 (XHIBIT)",
                        "json/event-processor/xhibit/maximal-input.json",
                        "json/event-processor/xhibit/maximal-expected.json",
                        List.of("migratedCaseDetails.defendants[0].id",
                                "migratedCaseDetails.defendants[1].id",
                                "migratedCaseDetails.defendants[0].offences[0].offenceId",
                                "migratedCaseDetails.defendants[0].offences[1].offenceId",
                                "migratedCaseDetails.defendants[1].offences[0].offenceId",
                                "migratedCaseDetails.defendants[1].offences[1].offenceId")),
                Arguments.of(
                        "FR2 minimal — only schema-required fields present (XHIBIT)",
                        "json/event-processor/xhibit/minimal-input.json",
                        "json/event-processor/xhibit/minimal-expected.json",
                        List.of("migratedCaseDetails.defendants[0].id",
                                "migratedCaseDetails.defendants[0].offences[0].offenceId")),
                Arguments.of(
                        "FR2 branch — empty materials collection is forwarded as [] not omitted (XHIBIT)",
                        "json/event-processor/xhibit/empty-materials-input.json",
                        "json/event-processor/xhibit/empty-materials-expected.json",
                        List.of("migratedCaseDetails.defendants[0].id",
                                "migratedCaseDetails.defendants[0].offences[0].offenceId")),
                Arguments.of(
                        "FR2 branch — individual present without contactDetails (XHIBIT)",
                        "json/event-processor/xhibit/no-contact-details-input.json",
                        "json/event-processor/xhibit/no-contact-details-expected.json",
                        List.of("migratedCaseDetails.defendants[0].id",
                                "migratedCaseDetails.defendants[0].offences[0].offenceId")),
                Arguments.of(
                        "T5 — a valid LIBRA payload is forwarded whole, initiationCode O unchanged (LIBRA)",
                        "json/event-processor/libra/received-input.json",
                        "json/event-processor/libra/received-expected.json",
                        List.of("migratedCaseDetails.defendants[0].id",
                                "migratedCaseDetails.defendants[0].offences[0].offenceId")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("receiveScenarios")
    void shouldBuildReceiveMigratedCaseFileWholePayload(final String name,
                                                        final String inputFixture,
                                                        final String expectedFixture,
                                                        final List<String> exclusions) {
        final MigratedCaseSubmission submission =
                CONVERTER.convert(readJson(fixture(inputFixture)), MigratedCaseSubmission.class);
        when(envelope.payload()).thenReturn(received(submission));
        when(envelope.metadata()).thenReturn(buildMetaData("receive-migrated-case-file"));
        when(systemMapperService.getCaseIdForPtiURN(any()))
                .thenReturn(new SystemMapperService.CaseIdLookupResult(FIXED_CASE_ID, false));

        final ArgumentCaptor<Envelope<ReceiveMigratedCaseFile>> captor = ArgumentCaptor.forClass(Envelope.class);

        eventProcessor.handleMigratedCaseSubmissionReceived(envelope);

        verify(sender).send(captor.capture());
        assertEquals("pcfdlrm.receive-migrated-case-file", captor.getValue().metadata().name());
        assertThat(serialise(captor.getValue().payload()), matchesWholePayload(fixture(expectedFixture), exclusions));
        verify(migratedCaseSubmissionReceivedCounter).increment();
    }

    @Test
    void shouldPublishSuccessOutcomeWholePayload() {
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processedSuccess());
        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        assertThat(serialise(outcomeEventArgumentCaptor.getValue()),
                matchesWholePayload(fixture("json/event-processor/xhibit/outcome-success.json"), List.of()));
        verify(migratedCaseSubmissionProcessedCounter).increment();
    }

    @Test
    void shouldPublishErrorOutcomeWholePayload() {
        when(errorMigratedCaseSubmissionReceivedEnvelope.payload()).thenReturn(errorReceived());
        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleErrorMigratedCaseSubmissionReceived(errorMigratedCaseSubmissionReceivedEnvelope);

        assertThat(serialise(outcomeEventArgumentCaptor.getValue()),
                matchesWholePayload(fixture("json/event-processor/xhibit/outcome-error.json"), List.of()));
        verify(errorMigratedCaseSubmissionReceivedCounter).increment();
    }

    @Test
    void shouldHandleMigratedCaseSubmissionReceivedWhenSourceSystemXHIBIT() {
        final MigratedCaseSubmissionReceived migratedCaseSubmissionReceived = buildMigratedCaseSubmissionReceived(XHIBIT, "C50EX02");
        when(envelope.payload()).thenReturn(migratedCaseSubmissionReceived);
        when(envelope.metadata()).thenReturn(buildMetaData("receive-migrated-case-file"));
        when(systemMapperService.getCaseIdForPtiURN(any())).thenReturn(new SystemMapperService.CaseIdLookupResult(CASE_ID, false));
        final ArgumentCaptor<Envelope<ReceiveMigratedCaseFile>> captor = ArgumentCaptor.forClass(Envelope.class);

        eventProcessor.handleMigratedCaseSubmissionReceived(envelope);

        verify(sender).send(captor.capture());
        final ReceiveMigratedCaseFile captorPayload = captor.getValue().payload();
        assertEquals("pcfdlrm.receive-migrated-case-file", captor.getValue().metadata().name());
        assertEquals(uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.Channel.DLRM_MIGRATION, captorPayload.getChannel());
        assertEquals(SUBMISSION_ID, captorPayload.getSubmissionId());
        verify(migratedCaseSubmissionReceivedCounter).increment();
    }

    @Test
    void shouldForwardMaterialsWhenSubmissionCarriesThem() {
        final MigratedCaseSubmissionReceived migratedCaseSubmissionReceived = buildMigratedCaseSubmissionReceivedWithMaterial(XHIBIT, "B01LY01");
        when(envelope.payload()).thenReturn(migratedCaseSubmissionReceived);
        when(envelope.metadata()).thenReturn(buildMetaData("receive-migrated-case-file"));
        when(systemMapperService.getCaseIdForPtiURN(any())).thenReturn(new SystemMapperService.CaseIdLookupResult(CASE_ID, false));
        final ArgumentCaptor<Envelope<ReceiveMigratedCaseFile>> captor = ArgumentCaptor.forClass(Envelope.class);

        eventProcessor.handleMigratedCaseSubmissionReceived(envelope);

        verify(sender).send(captor.capture());
        final ReceiveMigratedCaseFile captorPayload = captor.getValue().payload();
        assertEquals(Channel.DLRM_MIGRATION.name(), captorPayload.getChannel().name());
        assertEquals(SUBMISSION_ID, captorPayload.getSubmissionId());
        assertEquals(1, captorPayload.getMaterials().size());
        assertEquals(CASE_ID, captorPayload.getMaterials().get(0).getCaseId());
        verify(migratedCaseSubmissionReceivedCounter).increment();
    }

    @Test
    void shouldTestMigratedGenderGetValueFromCode() {
        assertEquals("NOT_KNOWN", getValueFromCode(0));
        assertEquals("MALE", getValueFromCode(1));
        assertEquals("FEMALE", getValueFromCode(2));
        assertEquals("NOT_SPECIFIED", getValueFromCode(9));
        assertEquals("3", getValueFromCode(3));
        assertEquals("10", getValueFromCode(10));
        assertEquals("12", getValueFromCode(12));
    }

    @Test
    void shouldNotSendToEventGridWhenDuplicateSubmissionId() {
        final MigratedCaseSubmissionProcessed processed = buildCaseSubmissionProcessed(false, "Duplicate Submission ID");
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService, never()).sendEventToEventGrid(any());
        verify(migratedCaseSubmissionProcessedCounter, never()).increment();
        verify(errorMigratedCaseSubmissionReceivedCounter).increment();
    }

    @Test
    void shouldNotSendToEventGridWhenDuplicateSubmissionIdCaseInsensitive() {
        final MigratedCaseSubmissionProcessed processed = buildCaseSubmissionProcessed(false, "duplicate submission id");
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService, never()).sendEventToEventGrid(any());
    }

    @Test
    void shouldSendToEventGridWhenDuplicateDescriptionButProcessingWasSuccessful() {
        final MigratedCaseSubmissionProcessed processed = buildCaseSubmissionProcessed(true, "Duplicate Submission ID");
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);
        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService).sendEventToEventGrid(any());
        final Outcome outcome = outcomeEventArgumentCaptor.getValue();
        assertEquals(CASE_ID, outcome.caseId());
        assertEquals(CASE_URN, outcome.caseUrn());
    }

    @Test
    void shouldSendToEventGridWhenDescriptionIsNull() {
        final MigratedCaseSubmissionProcessed processed = buildCaseSubmissionProcessed(false, null);
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);
        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService).sendEventToEventGrid(any());
    }

    @Test
    void shouldIncrementMigratedCaseSubmissionReceivedCounterWhenJsonSchemaValidationFailedWithLongDescription() {
        final String description = "JSON schema validation has failed on {\"metadata\":{}} due to " +
                "{\"message\":\"#/migratedCase/caseDetails: #: only 1 subschema matches out of 2\"," +
                "\"violatedSchema\":\"http://cpp.moj.gov.uk/stagingdlrm/json/schemas/case-details.json\"," +
                "\"violation\":\"#/migratedCase/caseDetails\"}";
        final MigratedCaseSubmissionProcessed processed = buildCaseSubmissionProcessed(false, description);
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);
        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService).sendEventToEventGrid(any());
        verify(migratedCaseSubmissionReceivedCounter).increment();
        verify(errorMigratedCaseSubmissionReceivedCounter).increment();
        verify(migratedCaseSubmissionProcessedCounter, never()).increment();
    }

    @Test
    void shouldSendCaseAlreadyProcessedCommandWhenCaseExistsInProgression() {
        final MigratedCaseSubmissionReceived migratedCaseSubmissionReceived = buildMigratedCaseSubmissionReceived(XHIBIT, "C50EX02");
        when(envelope.payload()).thenReturn(migratedCaseSubmissionReceived);
        when(envelope.metadata()).thenReturn(buildMetaData("receive-migrated-case-file"));
        when(systemMapperService.getCaseIdForPtiURN(any()))
                .thenReturn(new SystemMapperService.CaseIdLookupResult(CASE_ID, true));

        final ArgumentCaptor<Envelope<CaseAlreadyProcessedAndExistsInProgressionCommand>> captor =
                ArgumentCaptor.forClass(Envelope.class);

        eventProcessor.handleMigratedCaseSubmissionReceived(envelope);

        verify(sender).send(captor.capture());
        assertEquals("stagingdlrm.command.handler.case-already-exists-in-progression", captor.getValue().metadata().name());
        final CaseAlreadyProcessedAndExistsInProgressionCommand payload = captor.getValue().payload();
        assertEquals(CASE_ID, payload.getCaseId());
        assertEquals(SUBMISSION_ID, payload.getMigratedCaseSubmission().getSubmissionId());
        verify(migratedCaseSubmissionReceivedCounter, never()).increment();
    }

    private static MigratedCaseSubmissionReceived received(final MigratedCaseSubmission submission) {
        return MigratedCaseSubmissionReceived.migratedCaseSubmissionReceived()
                .withMigratedCaseSubmission(submission)
                .build();
    }

    private static MigratedCaseSubmissionProcessed processedSuccess() {
        return MigratedCaseSubmissionProcessed.migratedCaseSubmissionProcessed()
                .withMigratedCaseSubmissionProcessed(MigratedCaseSubmissionProcessedOutput
                        .migratedCaseSubmissionProcessedOutput()
                        .withCaseId(FIXED_CASE_ID)
                        .withCaseUrn(FIXED_CASE_URN)
                        .withSubmissionId(FIXED_SUBMISSION_ID)
                        .withProcessingIsSuccessful(true)
                        .withDescription("Processed")
                        .build())
                .withAzureLocation(AZURE_LOCATION)
                .build();
    }

    private static ErrorMigratedCaseSubmissionReceived errorReceived() {
        return ErrorMigratedCaseSubmissionReceived.errorMigratedCaseSubmissionReceived()
                .withErrorMigratedCaseSubmission(ErrorMigratedCaseSubmission
                        .errorMigratedCaseSubmission()
                        .withPayload("{}")
                        .withSubmissionId(FIXED_SUBMISSION_ID)
                        .withErrorMessage("JSON schema validation has failed")
                        .withCaseUrn(FIXED_CASE_URN)
                        .withAzureLocation(AZURE_LOCATION)
                        .build())
                .build();
    }

    private static JsonObject readJson(final String json) {
        return Json.createReader(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).readObject();
    }

    private static String serialise(final Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (final com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("Failed to serialise " + value, e);
        }
    }
}
