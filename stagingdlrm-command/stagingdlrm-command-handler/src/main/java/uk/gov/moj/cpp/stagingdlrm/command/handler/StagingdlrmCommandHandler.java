package uk.gov.moj.cpp.stagingdlrm.command.handler;

import static java.util.UUID.randomUUID;
import static uk.gov.justice.services.core.annotation.Component.COMMAND_HANDLER;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.CaseDetails.caseDetails;
import static uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCase.migratedCase;

import uk.gov.justice.services.core.annotation.Handles;
import uk.gov.justice.services.core.annotation.ServiceComponent;
import uk.gov.justice.services.eventsourcing.source.core.exception.EventStreamException;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.moj.cpp.stagingdlrm.command.handler.service.CaseIdGenerator;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Defendant;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCase;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Offence;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@ServiceComponent(COMMAND_HANDLER)
public class StagingdlrmCommandHandler extends AbstractCommandHandler {

    @Inject
    private CaseIdGenerator caseIdGenerator;

    @Handles("stagingdlrm.command.handler.receive-migrated-case-submission")
    public void receiveMigratedCaseSubmission(final Envelope<MigratedCaseSubmission> envelope) throws EventStreamException {

        final MigratedCaseSubmission payload = envelope.payload();

        final UUID submissionId = payload.getSubmissionId();

        final MigratedCase migratedCase = payload.getMigratedCase();

        final List<Defendant> defendantListWithNewIds = getDefendantsWithNewIds(migratedCase.getDefendants());

        final MigratedCaseSubmission migratedCaseSubmission = MigratedCaseSubmission.migratedCaseSubmission()
                .withValuesFrom(payload)
                .withMigratedCase(migratedCase()
                        .withValuesFrom(migratedCase)
                        .withDefendants(defendantListWithNewIds)
                        .withCaseDetails(caseDetails()
                                .withValuesFrom(migratedCase.getCaseDetails())
                                .build())
                        .withHearings(migratedCase.getHearings())
                        .build())
                .build();

        appendEventsToStream(submissionId, envelope,
                migratedCaseSubmissionAggregate -> migratedCaseSubmissionAggregate.receiveMigratedCaseSubmission(migratedCaseSubmission));
    }

    @Handles("stagingdlrm.command.handler.receive-error-migrated-case-submission")
    public void receiveErrorMigratedCaseSubmission(final Envelope<ErrorMigratedCaseSubmission> envelope) throws EventStreamException {
        final ErrorMigratedCaseSubmission errorMigratedCaseSubmission = envelope.payload();

        final UUID submissionId = errorMigratedCaseSubmission.getSubmissionId();

        appendEventsToStream(submissionId, envelope,
                migratedCaseSubmissionAggregate -> migratedCaseSubmissionAggregate.receiveErrorMigratedCaseSubmission(errorMigratedCaseSubmission));
    }

    @Handles("stagingdlrm.command.handler.record-submission-processing-output")
    public void recordMigratedCaseSubmissionOutput(final Envelope<MigratedCaseSubmissionProcessedOutput> envelope) throws EventStreamException {
        final MigratedCaseSubmissionProcessedOutput payload = envelope.payload();
        appendEventsToStream(payload.getSubmissionId(), envelope, migratedCaseSubmissionAggregate -> migratedCaseSubmissionAggregate.recordMigratedCaseSubmissionOutput(payload));
    }



    private List<Defendant> getDefendantsWithNewIds(final List<Defendant> migratedDefendants) {
        return migratedDefendants.stream()
                .map(defendant -> Defendant.defendant()
                        .withValuesFrom(defendant)
                        .withOffences((defendant.getOffences()))
                        .withId(randomUUID())
                        .build()
                )
                .toList();
    }


  }
