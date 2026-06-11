package uk.gov.moj.cpp.stagingdlrm.command.api;

import static uk.gov.justice.services.core.annotation.Component.COMMAND_API;
import static uk.gov.justice.services.core.enveloper.Enveloper.envelop;

import uk.gov.justice.services.core.annotation.Handles;
import uk.gov.justice.services.core.annotation.ServiceComponent;
import uk.gov.justice.services.core.sender.Sender;
import uk.gov.justice.services.messaging.JsonEnvelope;

import javax.inject.Inject;

@ServiceComponent(COMMAND_API)
public class StagingdlrmCommandApi {

    @Inject
    private Sender sender;

    @Handles("stagingdlrm.receive-migrated-case-submission")
    public void receiveMigratedCaseSubmission(final JsonEnvelope envelope) {
        sender.send(envelop(envelope.payloadAsJsonObject())
                .withName("stagingdlrm.command.handler.receive-migrated-case-submission")
                .withMetadataFrom(envelope));
    }

    @Handles("stagingdlrm.receive-error-migrated-case-submission")
    public void receiveErrorMigratedCaseSubmission(final JsonEnvelope envelope) {
        sender.send(envelop(envelope.payloadAsJsonObject())
                .withName("stagingdlrm.command.handler.receive-error-migrated-case-submission")
                .withMetadataFrom(envelope));
    }
}
