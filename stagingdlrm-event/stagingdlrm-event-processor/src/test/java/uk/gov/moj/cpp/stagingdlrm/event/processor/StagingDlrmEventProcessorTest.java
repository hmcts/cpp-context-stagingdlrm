package uk.gov.moj.cpp.stagingdlrm.event.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.CASE_ID;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.CASE_URN;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.DESCRIPTION;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.SUBMISSION_ID;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildCaseSubmissionProcessed;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildErrorMigratedCaseSubmissionReceived;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildMetaData;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildMigratedCaseSubmissionReceived;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.ObjectBuilder.buildMigratedCaseSubmissionReceivedWithMaterial;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.domain.MigratedGender.getValueFromCode;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.LIBRA;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;

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
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedMaterial;
import uk.gov.moj.cps.pcfdlrm.command.api.ReceiveMigratedCaseFile;
import uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StagingDlrmEventProcessorTest {

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
    private MigratedMaterial material;

    @Mock
    private EventGridService eventGridService;

    @Mock
    private MigratedCaseConvertor migratedCaseConvertor;

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


    @Test
    void shouldHandleMigratedCaseSubmissionReceivedWhenSourceSystemLIBRA() {

        final MigratedCaseSubmissionReceived migratedCaseSubmissionReceived = buildMigratedCaseSubmissionReceivedWithMaterial(LIBRA, "B01LY01");
        when(envelope.payload()).thenReturn(migratedCaseSubmissionReceived);
        when(envelope.metadata()).thenReturn(buildMetaData("receive-migrated-case-file"));
        when(systemMapperService.getCaseIdForPtiURN(any())).thenReturn(new SystemMapperService.CaseIdLookupResult(CASE_ID, false));
        ArgumentCaptor<Envelope<ReceiveMigratedCaseFile>> captor = ArgumentCaptor.forClass(Envelope.class);

        eventProcessor.handleMigratedCaseSubmissionReceived(envelope);

        verify(sender).send(captor.capture());

        final ReceiveMigratedCaseFile captorPayload = captor.getValue().payload();

        assertEquals("pcfdlrm.receive-migrated-case-file", captor.getValue().metadata().name());
        assertEquals(Channel.DLRM_MIGRATION.name(), captorPayload.getChannel().name());
        assertEquals(SUBMISSION_ID, captorPayload.getSubmissionId());

        verify(migratedCaseSubmissionReceivedCounter).increment();
    }

    @Test
    void shouldHandleMigratedCaseSubmissionProcessed() {

        final MigratedCaseSubmissionProcessed migratedCaseSubmissionProcessed = buildCaseSubmissionProcessed(true);

        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(migratedCaseSubmissionProcessed);

        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        final Outcome outcome = outcomeEventArgumentCaptor.getValue();

        assertEquals(CASE_ID, outcome.caseId());
        assertEquals(CASE_URN, outcome.caseUrn());
        assertEquals(SUBMISSION_ID, outcome.submissionId());
        assertTrue(outcome.success());
        assertEquals(DESCRIPTION, outcome.description());

        verify(migratedCaseSubmissionProcessedCounter).increment();
    }

    @Test
    void shouldHandleMigratedCaseSubmissionReceivedWhenSourceSystemXHIBIT() {

        final MigratedCaseSubmissionReceived migratedCaseSubmissionReceived = buildMigratedCaseSubmissionReceived(XHIBIT, "C50EX02");
        when(envelope.payload()).thenReturn(migratedCaseSubmissionReceived);
        when(envelope.metadata()).thenReturn(buildMetaData("receive-migrated-case-file"));
        when(systemMapperService.getCaseIdForPtiURN(any())).thenReturn(new SystemMapperService.CaseIdLookupResult(CASE_ID, false));
        ArgumentCaptor<Envelope<ReceiveMigratedCaseFile>> captor = ArgumentCaptor.forClass(Envelope.class);

        eventProcessor.handleMigratedCaseSubmissionReceived(envelope);

        verify(sender).send(captor.capture());

        final ReceiveMigratedCaseFile captorPayload = captor.getValue().payload();

        assertEquals("pcfdlrm.receive-migrated-case-file", captor.getValue().metadata().name());
        assertEquals(uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.Channel.DLRM_MIGRATION, captorPayload.getChannel());
        assertEquals(SUBMISSION_ID, captorPayload.getSubmissionId());

        verify(migratedCaseSubmissionReceivedCounter).increment();
    }

    @Test
    void shouldTestMigratedGenderGetValueFromCode() {
        // Test valid gender codes
        assertEquals("NOT_KNOWN", getValueFromCode(0));
        assertEquals("MALE", getValueFromCode(1));
        assertEquals("FEMALE", getValueFromCode(2));
        assertEquals("NOT_SPECIFIED", getValueFromCode(9));

        assertEquals("3", getValueFromCode(3));
        assertEquals("10", getValueFromCode(10));
        assertEquals("12", getValueFromCode(12));

    }

    @Test
    void shouldHandleErrorMigratedCaseSubmissionReceived() {

        final ErrorMigratedCaseSubmissionReceived errorMigratedCaseSubmissionReceived = buildErrorMigratedCaseSubmissionReceived();

        when(errorMigratedCaseSubmissionReceivedEnvelope.payload()).thenReturn(errorMigratedCaseSubmissionReceived);

        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleErrorMigratedCaseSubmissionReceived(errorMigratedCaseSubmissionReceivedEnvelope);

        final Outcome outcome = outcomeEventArgumentCaptor.getValue();

        assertEquals(CASE_URN, outcome.caseUrn());
        assertEquals(SUBMISSION_ID, outcome.submissionId());
        assertFalse(outcome.success());
        assertEquals(DESCRIPTION, outcome.description());

        verify(errorMigratedCaseSubmissionReceivedCounter).increment();
    }

    @Test
    void shouldNotSendToEventGridWhenDuplicateSubmissionId() {
        final MigratedCaseSubmissionProcessed processed =
                buildCaseSubmissionProcessed(false, "Duplicate Submission ID");
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService, never()).sendEventToEventGrid(any());
        verify(migratedCaseSubmissionProcessedCounter, never()).increment();
        verify(errorMigratedCaseSubmissionReceivedCounter, never()).increment();
    }

    @Test
    void shouldNotSendToEventGridWhenDuplicateSubmissionIdCaseInsensitive() {
        final MigratedCaseSubmissionProcessed processed =
                buildCaseSubmissionProcessed(false, "duplicate submission id");
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService, never()).sendEventToEventGrid(any());
    }

    @Test
    void shouldSendToEventGridWhenDuplicateDescriptionButProcessingWasSuccessful() {
        final MigratedCaseSubmissionProcessed processed =
                buildCaseSubmissionProcessed(true, "Duplicate Submission ID");
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);
        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService).sendEventToEventGrid(any());
        final Outcome outcome = outcomeEventArgumentCaptor.getValue();
        assertEquals(CASE_ID, outcome.caseId());
        assertEquals(CASE_URN, outcome.caseUrn());
        assertTrue(outcome.success());
    }

    @Test
    void shouldSendToEventGridWhenDescriptionIsNull() {
        final MigratedCaseSubmissionProcessed processed =
                buildCaseSubmissionProcessed(false, null);
        when(migratedCaseSubmissionProcessedEnvelope.payload()).thenReturn(processed);
        doNothing().when(eventGridService).sendEventToEventGrid(outcomeEventArgumentCaptor.capture());

        eventProcessor.handleMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedEnvelope);

        verify(eventGridService).sendEventToEventGrid(any());
        final Outcome outcome = outcomeEventArgumentCaptor.getValue();
        assertFalse(outcome.success());
    }

    @Test
    void shouldSendCaseAlreadyProcessedCommandWhenCaseExistsInProgression() {
        final MigratedCaseSubmissionReceived migratedCaseSubmissionReceived = buildMigratedCaseSubmissionReceived(XHIBIT, "C50EX02");
        when(envelope.payload()).thenReturn(migratedCaseSubmissionReceived);
        when(envelope.metadata()).thenReturn(buildMetaData("receive-migrated-case-file"));
        when(systemMapperService.getCaseIdForPtiURN(any()))
                .thenReturn(new SystemMapperService.CaseIdLookupResult(CASE_ID, true));

        @SuppressWarnings("unchecked")
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
}