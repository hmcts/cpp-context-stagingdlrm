package uk.gov.moj.cpp.stagingdlrm.command.handler;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.withJsonPath;
import static java.util.UUID.fromString;
import static java.util.UUID.nameUUIDFromBytes;
import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.messaging.Envelope.envelopeFrom;
import static uk.gov.justice.services.test.utils.core.helper.EventStreamMockHelper.verifyAppendAndGetArgumentFrom;
import static uk.gov.justice.services.test.utils.core.matchers.JsonEnvelopeMatcher.jsonEnvelope;
import static uk.gov.justice.services.test.utils.core.matchers.JsonEnvelopeMetadataMatcher.metadata;
import static uk.gov.justice.services.test.utils.core.matchers.JsonEnvelopeStreamMatcher.streamContaining;

import uk.gov.justice.services.core.aggregate.AggregateService;
import uk.gov.justice.services.core.enveloper.Enveloper;
import uk.gov.justice.services.eventsourcing.source.core.EventSource;
import uk.gov.justice.services.eventsourcing.source.core.EventStream;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.justice.services.messaging.JsonEnvelope;
import uk.gov.justice.services.messaging.Metadata;
import uk.gov.justice.services.test.utils.core.enveloper.EnveloperFactory;
import uk.gov.justice.services.test.utils.core.matchers.JsonEnvelopePayloadMatcher;
import uk.gov.moj.cpp.stagingdlrm.aggregate.MigratedCaseSubmissionAggregate;
import uk.gov.moj.cpp.stagingdlrm.command.handler.service.CaseIdGenerator;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;

import java.util.UUID;
import java.util.stream.Stream;

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

    @Mock(answer = RETURNS_DEEP_STUBS)
    private MigratedCaseSubmission migratedCaseSubmission;

    @Mock
    private CaseIdGenerator caseIdGenerator;

    @Captor
    private ArgumentCaptor<java.util.stream.Stream<uk.gov.justice.services.messaging.JsonEnvelope>> eventCaptor;

    @Spy
    private final Enveloper enveloper = EnveloperFactory.createEnveloperWithEvents(
            ErrorMigratedCaseSubmissionReceived.class, MigratedCaseSubmissionProcessed.class);


    @Test
    void shouldReceiveMigratedCaseSubmission() throws Exception {
        final String urn = "caseURN";

        final UUID submissionId = fromString("f0d12b0f-5a44-4ba4-9429-accd7aa9be56");

        final UUID caseId = nameUUIDFromBytes(urn.concat(submissionId.toString()).getBytes());

        when(migratedCaseSubmissionEnvelope.payload()).thenReturn(migratedCaseSubmission);
        when(migratedCaseSubmission.getMigratedCase().getCaseDetails().getProsecutorCaseReference()).thenReturn(urn);
        when(migratedCaseSubmission.getSubmissionId()).thenReturn(submissionId);
        when(eventSource.getStreamById(submissionId)).thenReturn(eventStream);
        when(aggregateService.get(eventStream, MigratedCaseSubmissionAggregate.class)).thenReturn(migratedCaseSubmissionAggregate);
        ArgumentCaptor<MigratedCaseSubmission> migratedCaseSubmissionCaptor = forClass(MigratedCaseSubmission.class);

        stagingdlrmCommandHandler.receiveMigratedCaseSubmission(migratedCaseSubmissionEnvelope);

        verify(migratedCaseSubmissionAggregate).receiveMigratedCaseSubmission(migratedCaseSubmissionCaptor.capture());
//        assertThat("The Case Id should match", migratedCaseSubmissionCaptor.getValue().getMigratedCase().getCaseDetails().getCaseId().equals(caseId)); // to think
    }

    @Test
    void shouldReceiveErrorMigratedCaseSubmission() throws Exception {

        final UUID submissionId = UUID.randomUUID();

        final String payload = "sample text";

        final ErrorMigratedCaseSubmission errorMigratedCaseSubmission = ErrorMigratedCaseSubmission
                .errorMigratedCaseSubmission()
                .withSubmissionId(submissionId)
                .withPayload(payload)
                .build();

        final Metadata metadata = Envelope
                .metadataBuilder()
                .withName("stagingdlrm.command.handler.receive-error-case-submission")
                .withId(randomUUID())
                .build();

        final Envelope<ErrorMigratedCaseSubmission> errorMigratedCaseSubmissionEnvelope = envelopeFrom(metadata, errorMigratedCaseSubmission);

        when(eventSource.getStreamById(submissionId)).thenReturn(eventStream);

        when(aggregateService.get(eventStream, MigratedCaseSubmissionAggregate.class)).thenReturn(new MigratedCaseSubmissionAggregate());

        when(eventStream.append(eventCaptor.capture())).thenReturn(1L);

        stagingdlrmCommandHandler.receiveErrorMigratedCaseSubmission(errorMigratedCaseSubmissionEnvelope);

        final Stream<JsonEnvelope> envelopeStream = verifyAppendAndGetArgumentFrom(eventStream);

        assertThat(envelopeStream, streamContaining(
                        jsonEnvelope(
                                metadata()
                                        .withName("stagingdlrm.events.error-migrated-case-submission-received"),
                                JsonEnvelopePayloadMatcher.payload().isJson(allOf(
                                                withJsonPath("$.errorMigratedCaseSubmission", notNullValue()),
                                                withJsonPath("$.errorMigratedCaseSubmission.submissionId", is(submissionId.toString())),
                                                withJsonPath("$.errorMigratedCaseSubmission.payload", is(payload))
                                        )
                                )
                        )
                )
        );
    }

    @Test
    void shouldRecordMigratedCaseSubmissionOutput() throws Exception {

        final UUID submissionId = UUID.randomUUID();

        final MigratedCaseSubmissionProcessedOutput migratedCaseSubmissionProcessedOutput = MigratedCaseSubmissionProcessedOutput
                .migratedCaseSubmissionProcessedOutput()
                .withProcessingIsSuccessful(true)
                .withCaseId(submissionId)
                .withSubmissionId(submissionId)
                .withCaseUrn("caseUrn")
                .build();

        final Metadata metadata = Envelope
                .metadataBuilder()
                .withName("stagingdlrm.command.handler.record-submission-processing-output")
                .withId(randomUUID())
                .build();

        final Envelope<MigratedCaseSubmissionProcessedOutput> outputEnvelope = envelopeFrom(metadata, migratedCaseSubmissionProcessedOutput);

        when(eventSource.getStreamById(submissionId)).thenReturn(eventStream);

        when(aggregateService.get(eventStream, MigratedCaseSubmissionAggregate.class)).thenReturn(new MigratedCaseSubmissionAggregate());

        when(eventStream.append(eventCaptor.capture())).thenReturn(1L);

        stagingdlrmCommandHandler.recordMigratedCaseSubmissionOutput(outputEnvelope);

        final Stream<JsonEnvelope> envelopeStream = verifyAppendAndGetArgumentFrom(eventStream);

        assertThat(envelopeStream, streamContaining(
                        jsonEnvelope(
                                metadata()
                                        .withName("stagingdlrm.events.migrated-case-submission-processed"),
                                JsonEnvelopePayloadMatcher.payload().isJson(allOf(
                                                withJsonPath("$.migratedCaseSubmissionProcessed", notNullValue()),
                                                withJsonPath("$.migratedCaseSubmissionProcessed.submissionId", is(submissionId.toString())),
                                                withJsonPath("$.migratedCaseSubmissionProcessed.caseId", is(submissionId.toString()))
                                        )
                                )
                        )
                )
        );
    }
}