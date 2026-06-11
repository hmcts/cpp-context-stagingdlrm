package uk.gov.moj.cpp.stagingdlrm.aggregate;

import static java.util.stream.Stream.builder;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.match;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.otherwiseDoNothing;
import static uk.gov.justice.domain.aggregate.matcher.EventSwitcher.when;
import static uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput.migratedCaseSubmissionProcessedOutput;
import static uk.gov.moj.stagingdlrm.domain.event.DuplicatedMigratedCaseSubmissionReceived.duplicatedMigratedCaseSubmissionReceived;
import static uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived.errorMigratedCaseSubmissionReceived;
import static uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed.migratedCaseSubmissionProcessed;
import static uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived.migratedCaseSubmissionReceived;

import uk.gov.justice.domain.aggregate.Aggregate;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.stagingdlrm.domain.event.DuplicatedMigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;

import java.io.Serial;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public class MigratedCaseSubmissionAggregate implements Aggregate {

    @Serial
    private static final long serialVersionUID = 7697816029253916169L;
    public static final String DUPLICATE_SUBMISSION_ID = "Duplicate Submission ID";

    private UUID submissionId;

    private MigratedCaseSubmissionProcessedOutput migratedCaseSubmissionProcessedOutput;

    private boolean isCaseSubmissionDuplicated;

    private final Map<UUID, String> azureLocation = new HashMap<>();

    @Override
    public Object apply(final Object event) {
        return match(event).with(
                when(MigratedCaseSubmissionReceived.class)
                        .apply(e -> {
                            this.submissionId = e.getMigratedCaseSubmission().getSubmissionId();
                            this.azureLocation.put(this.submissionId, e.getMigratedCaseSubmission().getAzureLocation());
                        }),
                when(MigratedCaseSubmissionProcessed.class)
                        .apply(e -> this.migratedCaseSubmissionProcessedOutput = e.getMigratedCaseSubmissionProcessed()),
                when(DuplicatedMigratedCaseSubmissionReceived.class)
                        .apply(e -> isCaseSubmissionDuplicated = true),
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
            final UUID caseId = this.migratedCaseSubmissionProcessedOutput.getCaseId();
            builder.add(migratedCaseSubmissionProcessed()
                    .withMigratedCaseSubmissionProcessed(migratedCaseSubmissionProcessedOutput()
                            .withCaseId(caseId)
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

    public MigratedCaseSubmissionProcessedOutput getMigratedCaseSubmissionProcessedOutput() {
        return migratedCaseSubmissionProcessedOutput;
    }

    public boolean isCaseSubmissionDuplicated() {
        return isCaseSubmissionDuplicated;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }
}
