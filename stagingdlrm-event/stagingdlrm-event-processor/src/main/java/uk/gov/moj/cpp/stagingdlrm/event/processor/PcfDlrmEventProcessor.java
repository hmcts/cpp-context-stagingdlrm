package uk.gov.moj.cpp.stagingdlrm.event.processor;

import static uk.gov.justice.services.core.annotation.Component.EVENT_PROCESSOR;
import static uk.gov.justice.services.messaging.JsonEnvelope.metadataFrom;

import uk.gov.justice.services.core.annotation.Handles;
import uk.gov.justice.services.core.annotation.ServiceComponent;
import uk.gov.justice.services.core.sender.Sender;
import uk.gov.justice.services.messaging.JsonEnvelope;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceComponent(EVENT_PROCESSOR)
public class PcfDlrmEventProcessor {

    @Inject
    private Sender sender;

    private static final Logger LOGGER = LoggerFactory.getLogger(PcfDlrmEventProcessor.class);

    private static final String STAGINGDLRM_COMMAND_HANDLER_RECORD_SUBMISSION_PROCESSING_OUTPUT = "stagingdlrm.command.handler.record-submission-processing-output";

    @Handles("public.pcfdlrm.migrated-case-file-processed")
    public void handleRecordSubmissionProcessingOutput(final JsonEnvelope envelope) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("public.pcfdlrm.migrated-case-file-processed {}", envelope.toObfuscatedDebugString());
        }

        sender.send(JsonEnvelope.envelopeFrom(metadataFrom(envelope.metadata())
                .withName(STAGINGDLRM_COMMAND_HANDLER_RECORD_SUBMISSION_PROCESSING_OUTPUT), envelope.payloadAsJsonObject()));
    }


}
