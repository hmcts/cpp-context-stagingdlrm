package uk.gov.moj.cpp.stagingdlrm.aggregate;

import static java.util.stream.Stream.builder;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.match;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.otherwiseDoNothing;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.when;
import static uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput.migratedCaseSubmissionProcessedOutput;
import static uk.gov.moj.stagingdlrm.domain.event.CaseAlreadyProcessedAndExistsInProgression.caseAlreadyProcessedAndExistsInProgression;
import static uk.gov.moj.stagingdlrm.domain.event.DuplicatedMigratedCaseSubmissionReceived.duplicatedMigratedCaseSubmissionReceived;
import static uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived.errorMigratedCaseSubmissionReceived;
import static uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed.migratedCaseSubmissionProcessed;
import static uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived.migratedCaseSubmissionReceived;

import uk.gov.justice.domain.aggregate.Aggregate;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.CaseAlreadyProcessedAndExistsInProgressionCommand;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;

import java.io.Serial;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public class MigratedCaseSubmissionAggregate implements Aggregate {

    @Serial
    private static final long serialVersionUID = 7697896029253916169L;
    public static final String DUPLICATE_SUBMISSION_ID = "Duplicate Submission ID";
    public static final String CASE_ALREADY_EXISTS_IN_PROGRESSION = "Case Already exists in progression";

    private UUID submissionId;

    private final Map<UUID, String> azureLocation = new HashMap<>();

    @Override
    public Object apply(final Object event) {
        return match(event).with(
                when(MigratedCaseSubmissionReceived.class)
                        .apply(e -> {
                            this.submissionId = e.getMigratedCaseSubmission().getSubmissionId();
                            this.azureLocation.put(this.submissionId, e.getMigratedCaseSubmission().getAzureLocation());
                        }),
                otherwiseDoNothing());
    }

    public Stream<Object> receiveErrorMigratedCaseSubmission(final ErrorMigratedCaseSubmission errorMigratedCaseSubmission) {
        return apply(Stream.of(errorMigratedCaseSubmissionReceived()
                .withErrorMigratedCaseSubmission(errorMigratedCaseSubmission)
                .build()));
    }

    public Stream<Object> receiveMigratedCaseSubmission(final MigratedCaseSubmission migratedCaseSubmission) {
        if (migratedCaseSubmission.getSubmissionId().equals(submissionId)){
            final Stream.Builder<Object> builder = builder();
             builder.add(duplicatedMigratedCaseSubmissionReceived().withDuplicateMigratedCaseSubmission(migratedCaseSubmission).build());
            final String azureFileLocation = azureLocation.get(submissionId);
            final String caseUrn = migratedCaseSubmission.getMigratedCase().getCaseDetails().getProsecutorCaseReference();
            builder.add(migratedCaseSubmissionProcessed()
                    .withMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedOutput()
                            .withSubmissionId(submissionId)
                            .withCaseUrn(caseUrn)
                            .withProcessingIsSuccessful(false)
                            .withDescription(DUPLICATE_SUBMISSION_ID)
                            .build())
                    .withAzureLocation(azureFileLocation)
                    .build());
            return apply(builder.build());
        }
        return apply(Stream.of(migratedCaseSubmissionReceived().withMigratedCaseSubmission(migratedCaseSubmission).build()));
    }

    public Stream<Object> recordMigratedCaseSubmissionOutput(final MigratedCaseSubmissionProcessedOutput payload) {
        final UUID submittedSubmissionId = payload.getSubmissionId();
        final String azureFileLocation = azureLocation.get(submittedSubmissionId);
        return apply(Stream.of(migratedCaseSubmissionProcessed()
                .withMigratedCaseSubmissionProcessed(payload)
                .withAzureLocation(azureFileLocation)
                .build()));
    }

    public Stream<Object> receiveCaseAlreadyProcessed(final CaseAlreadyProcessedAndExistsInProgressionCommand command) {
        final MigratedCaseSubmission migratedCaseSubmission = command.getMigratedCaseSubmission();
        final UUID caseId = command.getCaseId();
        final Stream.Builder<Object> builder = builder();
        builder.add(caseAlreadyProcessedAndExistsInProgression().withMigratedCaseSubmission(migratedCaseSubmission).build());
        final String caseUrn = migratedCaseSubmission.getMigratedCase().getCaseDetails().getProsecutorCaseReference();
        builder.add(migratedCaseSubmissionProcessed()
                .withMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedOutput()
                        .withCaseId(caseId)
                        .withSubmissionId(migratedCaseSubmission.getSubmissionId())
                        .withCaseUrn(caseUrn)
                        .withProcessingIsSuccessful(false)
                        .withDescription(CASE_ALREADY_EXISTS_IN_PROGRESSION)
                        .build())
                .withAzureLocation(azureLocation.get(migratedCaseSubmission.getSubmissionId()))
                .build());
        return apply(builder.build());
    }
}
