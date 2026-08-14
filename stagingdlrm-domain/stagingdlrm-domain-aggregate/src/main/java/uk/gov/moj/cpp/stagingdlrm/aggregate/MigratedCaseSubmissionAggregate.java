package uk.gov.moj.cpp.stagingdlrm.aggregate;

import static java.util.stream.Stream.builder;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.match;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.otherwiseDoNothing;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.when;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigratedCaseValidationError.migratedCaseValidationError;
import static uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput.migratedCaseSubmissionProcessedOutput;
import static uk.gov.moj.stagingdlrm.domain.event.CaseAlreadyProcessedAndExistsInProgression.caseAlreadyProcessedAndExistsInProgression;
import static uk.gov.moj.stagingdlrm.domain.event.DuplicatedMigratedCaseSubmissionReceived.duplicatedMigratedCaseSubmissionReceived;
import static uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived.errorMigratedCaseSubmissionReceived;
import static uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed.migratedCaseSubmissionProcessed;
import static uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived.migratedCaseSubmissionReceived;
import static uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionRejected.migratedCaseSubmissionRejected;

import uk.gov.justice.domain.aggregate.Aggregate;
import uk.gov.moj.cpp.stagingdlrm.aggregate.validation.MigratedCaseValidationRuleEngine;
import uk.gov.moj.cpp.stagingdlrm.aggregate.validation.ValidationError;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigratedCaseValidationError;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.CaseAlreadyProcessedAndExistsInProgressionCommand;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;

import java.io.Serial;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public class MigratedCaseSubmissionAggregate implements Aggregate {

    @Serial
    private static final long serialVersionUID = 7697896029253916169L;
    public static final String DUPLICATE_SUBMISSION_ID = "Duplicate Submission ID";
    public static final String CASE_ALREADY_EXISTS_IN_PROGRESSION = "Case Already exists in progression";
    public static final String VALIDATION_FAILED = "Migrated case submission rejected by validation rule(s)";

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
            builder.add(processedFailure(submissionId, caseUrn, DUPLICATE_SUBMISSION_ID, azureFileLocation, null));
            return apply(builder.build());
        }

        final MigrationSourceSystemName sourceSystem =
                migratedCaseSubmission.getMigratedCase().getMigrationSourceSystem().getMigrationSourceSystemName();
        final List<ValidationError> validationErrors =
                new MigratedCaseValidationRuleEngine().validate(sourceSystem, migratedCaseSubmission);
        if (!validationErrors.isEmpty()) {
            final Stream.Builder<Object> builder = builder();
            builder.add(migratedCaseSubmissionRejected()
                    .withMigratedCaseSubmission(migratedCaseSubmission)
                    .withValidationErrors(toEventErrors(validationErrors))
                    .build());
            final String caseUrn = migratedCaseSubmission.getMigratedCase().getCaseDetails().getProsecutorCaseReference();
            builder.add(processedFailure(migratedCaseSubmission.getSubmissionId(), caseUrn, VALIDATION_FAILED,
                    migratedCaseSubmission.getAzureLocation(), null));
            return apply(builder.build());
        }

        return apply(Stream.of(migratedCaseSubmissionReceived().withMigratedCaseSubmission(migratedCaseSubmission).build()));
    }

    private static List<MigratedCaseValidationError> toEventErrors(final List<ValidationError> validationErrors) {
        return validationErrors.stream()
                .map(error -> migratedCaseValidationError()
                        .withJsonPath(error.jsonPath())
                        .withMessage(error.message())
                        .build())
                .toList();
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
        builder.add(processedFailure(migratedCaseSubmission.getSubmissionId(), caseUrn, CASE_ALREADY_EXISTS_IN_PROGRESSION,
                azureLocation.get(migratedCaseSubmission.getSubmissionId()), caseId));
        return apply(builder.build());
    }

    private static Object processedFailure(final UUID submissionId,
                                           final String caseUrn,
                                           final String description,
                                           final String azureLocation,
                                           final UUID caseId) {
        final var output = migratedCaseSubmissionProcessedOutput()
                .withSubmissionId(submissionId)
                .withCaseUrn(caseUrn)
                .withProcessingIsSuccessful(false)
                .withDescription(description);
        if (caseId != null) {
            output.withCaseId(caseId);
        }
        return migratedCaseSubmissionProcessed()
                .withMigratedCaseSubmissionProcessed(output.build())
                .withAzureLocation(azureLocation)
                .build();
    }
}
