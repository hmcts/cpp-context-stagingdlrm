package uk.gov.moj.cpp.stagingdlrm.event.processor.service;

import static java.util.Optional.ofNullable;
import static java.util.UUID.randomUUID;
import static javax.json.Json.createObjectBuilder;
import static uk.gov.justice.services.core.annotation.Component.EVENT_PROCESSOR;
import static uk.gov.justice.services.messaging.Envelope.envelopeFrom;
import static uk.gov.justice.services.messaging.JsonEnvelope.metadataBuilder;

import uk.gov.justice.services.core.annotation.ServiceComponent;
import uk.gov.justice.services.core.requester.Requester;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.justice.services.messaging.JsonEnvelope;

import java.util.Optional;
import java.util.UUID;

import javax.inject.Inject;
import javax.json.JsonObject;

public class ProgressionService {

    private static final String CASE_ID_FIELD = "caseId";
    private static final String PROGRESSION_CASE_DETAILS = "progression.query.prosecutioncase";

    @Inject
    @ServiceComponent(EVENT_PROCESSOR)
    private Requester requester;

    public Optional<JsonObject> getProsecutionCaseDetails(final UUID caseId) {
        final JsonObject query = createObjectBuilder().add(CASE_ID_FIELD, caseId.toString()).build();
        final Envelope<JsonObject> envelope = envelopeFrom(
                metadataBuilder().withId(randomUUID()).withName(PROGRESSION_CASE_DETAILS).build(),
                query);
        return ofNullable(requester.requestAsAdmin(envelope, JsonObject.class).payload());
    }
}
