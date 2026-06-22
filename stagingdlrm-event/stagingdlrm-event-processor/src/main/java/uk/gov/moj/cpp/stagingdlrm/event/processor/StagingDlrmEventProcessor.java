package uk.gov.moj.cpp.stagingdlrm.event.processor;


import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNullElse;
import static uk.gov.justice.services.core.annotation.Component.EVENT_PROCESSOR;
import static uk.gov.justice.services.messaging.Envelope.envelopeFrom;
import static uk.gov.justice.services.messaging.Envelope.metadataFrom;
import static uk.gov.moj.cpp.stagingdlrm.event.processor.convertor.MigratedCaseConvertor.buildMaterials;
import static uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.CaseAlreadyProcessedAndExistsInProgressionCommand.caseAlreadyProcessedAndExistsInProgressionCommand;

import uk.gov.justice.services.core.annotation.Handles;
import uk.gov.justice.services.core.annotation.ServiceComponent;
import uk.gov.justice.services.core.sender.Sender;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.justice.services.messaging.Metadata;
import uk.gov.moj.cpp.stagingdlrm.event.processor.convertor.MigratedCaseConvertor;
import uk.gov.moj.cpp.stagingdlrm.event.processor.counter.ErrorMigratedCaseSubmissionReceivedCounter;
import uk.gov.moj.cpp.stagingdlrm.event.processor.counter.MigratedCaseSubmissionProcessedCounter;
import uk.gov.moj.cpp.stagingdlrm.event.processor.counter.MigratedCaseSubmissionReceivedCounter;
import uk.gov.moj.cpp.stagingdlrm.event.processor.domain.Outcome;
import uk.gov.moj.cpp.stagingdlrm.event.processor.service.SystemMapperService;
import uk.gov.moj.cpp.stagingdlrm.event.processor.service.SystemMapperService.CaseIdLookupResult;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.Channel;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.CaseAlreadyProcessedAndExistsInProgressionCommand;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCase;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedMaterial;
import uk.gov.moj.cps.pcfdlrm.command.api.ReceiveMigratedCaseFile;
import uk.gov.moj.stagingdlrm.domain.event.ErrorMigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceComponent(EVENT_PROCESSOR)
public class StagingDlrmEventProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(StagingDlrmEventProcessor.class);

    private static final String PCF_DLRM_RECEIVE_MIGRATED_CASE_FILE = "pcfdlrm.receive-migrated-case-file";
    private static final String STAGINGDLRM_COMMAND_HANDLER_CASE_ALREADY_EXISTS_IN_PROGRESSION = "stagingdlrm.command.handler.case-already-exists-in-progression";

    @Inject
    private EventGridService eventGridService;

    @Inject
    private Sender sender;

    @Inject
    private MigratedCaseConvertor migratedCaseConvertor;

    @Inject
    private SystemMapperService systemMapperService;

    @Inject
    private MigratedCaseSubmissionReceivedCounter migratedCaseSubmissionReceivedCounter;

    @Inject
    private MigratedCaseSubmissionProcessedCounter migratedCaseSubmissionProcessedCounter;

    @Inject
    private ErrorMigratedCaseSubmissionReceivedCounter errorMigratedCaseSubmissionReceivedCounter;

    @Handles("stagingdlrm.events.migrated-case-submission-received")
    public void handleMigratedCaseSubmissionReceived(final Envelope<MigratedCaseSubmissionReceived> envelope) {
        LOGGER.info("'stagingdlrm.events.migrated-case-submission-received' received ");
        MigratedCaseSubmissionReceived payload = envelope.payload();

        final Metadata metadata = metadataFrom(envelope.metadata())
                .withName(PCF_DLRM_RECEIVE_MIGRATED_CASE_FILE)
                .build();

        final Channel channel = requireNonNullElse(
                payload.getMigratedCaseSubmission().getChannel(),
                Channel.DLRM_MIGRATION);
        final List<MigratedMaterial> materials = payload.getMigratedCaseSubmission().getMaterials();
        final MigratedCase migratedCase = payload.getMigratedCaseSubmission().getMigratedCase();
        final UUID submissionId = payload.getMigratedCaseSubmission().getSubmissionId();
        final CaseIdLookupResult lookupResult = systemMapperService.getCaseIdForPtiURN(
                migratedCase.getCaseDetails().getProsecutorCaseReference());
        final UUID caseId = lookupResult.getCaseId();

        if (lookupResult.isCaseAlreadyProcessedAndExistsInProgression()) {
            LOGGER.info("Case already processed and exists in progression for submissionId: {}", submissionId);
            final Metadata caseAlreadyProcessedMetadata = metadataFrom(envelope.metadata())
                    .withName(STAGINGDLRM_COMMAND_HANDLER_CASE_ALREADY_EXISTS_IN_PROGRESSION)
                    .build();
            final CaseAlreadyProcessedAndExistsInProgressionCommand command = caseAlreadyProcessedAndExistsInProgressionCommand()
                    .withCaseId(caseId)
                    .withMigratedCaseSubmission(payload.getMigratedCaseSubmission())
                    .build();
            sender.send(envelopeFrom(caseAlreadyProcessedMetadata, command));
            return;
        }

        final ReceiveMigratedCaseFile.Builder builder = ReceiveMigratedCaseFile.receiveMigratedCaseFile()
                .withSubmissionId(submissionId)
                .withChannel(uk.gov.moj.cpp.prosecution.casefile.dlrm.json.schemas.Channel.valueOf(channel.name()))
                .withMigratedCaseDetails(migratedCaseConvertor.buildMigratedCasedetails(migratedCase, caseId));

        if (nonNull(materials)) {
            builder.withMaterials(buildMaterials(materials, caseId));
        }

        final Envelope<ReceiveMigratedCaseFile> commandEnvelope = envelopeFrom(metadata, builder.build());
        LOGGER.info("Sending to pcf dlrm envelope- {} ", commandEnvelope.payload());
        sender.send(commandEnvelope);

        migratedCaseSubmissionReceivedCounter.increment();
    }


    @Handles("stagingdlrm.events.migrated-case-submission-processed")
    public void handleMigratedCaseSubmissionProcessed(final Envelope<MigratedCaseSubmissionProcessed> envelope) {

        LOGGER.info("'stagingdlrm.events.migrated-case-submission-processed' received.");

        final MigratedCaseSubmissionProcessedOutput migratedCaseSubmissionProcessed = envelope.payload()
                .getMigratedCaseSubmissionProcessed();

        final String azureLocation = envelope.payload().getAzureLocation();

        final UUID caseId = migratedCaseSubmissionProcessed.getCaseId();
        final String caseUrn = migratedCaseSubmissionProcessed.getCaseUrn();
        final String description = migratedCaseSubmissionProcessed.getDescription();
        final Boolean processingIsSuccessful = migratedCaseSubmissionProcessed.getProcessingIsSuccessful();
        final UUID submissionId = migratedCaseSubmissionProcessed.getSubmissionId();

        sendEventToGrid(azureLocation, caseId, submissionId, caseUrn, description, processingIsSuccessful);
    }

    private static boolean isDuplicateSubmissionId(String description, Boolean processingIsSuccessful) {
        final String DUPLICATE_SUBMISSION_ID = "Duplicate Submission ID";
        return nonNull(description) && !processingIsSuccessful
                && DUPLICATE_SUBMISSION_ID.equalsIgnoreCase(description);
    }

    @Handles("stagingdlrm.events.error-migrated-case-submission-received")
    public void handleErrorMigratedCaseSubmissionReceived(final Envelope<ErrorMigratedCaseSubmissionReceived> envelope) {

        LOGGER.info("'stagingdlrm.events.error-migrated-case-submission-received' received.");

        final ErrorMigratedCaseSubmission errorMigratedCaseSubmissionReceived = envelope.payload()
                .getErrorMigratedCaseSubmission();

        final String caseUrn = errorMigratedCaseSubmissionReceived.getCaseUrn();
        final String description = errorMigratedCaseSubmissionReceived.getErrorMessage();
        final Boolean processingIsSuccessful = false;
        final UUID submissionId = errorMigratedCaseSubmissionReceived.getSubmissionId();
        final String azureLocation = errorMigratedCaseSubmissionReceived.getAzureLocation();

        sendEventToGrid(azureLocation, null, submissionId, caseUrn, description, processingIsSuccessful);
    }

    private void sendEventToGrid(final String azureLocation, final UUID caseId, final UUID submissionId, final String caseUrn, final String description, final Boolean processingIsSuccessful) {
        LOGGER.info("Case Id : {}", caseId);
        LOGGER.info("Submission Id : {}", submissionId);
        LOGGER.info("Case Urn : {}", caseUrn);
        LOGGER.info("Description : {}", description);
        LOGGER.info("Success : {}", processingIsSuccessful);
        LOGGER.info("AzureLocation : {}", azureLocation);

        if (!isDuplicateSubmissionId(description, processingIsSuccessful)) {
            eventGridService.sendEventToEventGrid(
                    new Outcome(caseId, submissionId, caseUrn, processingIsSuccessful, description, azureLocation));
        }

        if (processingIsSuccessful) {
            migratedCaseSubmissionProcessedCounter.increment();
        } else {
            errorMigratedCaseSubmissionReceivedCounter.increment();
        }
    }

}
