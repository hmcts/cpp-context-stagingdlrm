package uk.gov.moj.cpp.stagingdlrm.command.handler;

import static javax.json.JsonValue.NULL;
import static uk.gov.justice.services.core.enveloper.Enveloper.toEnvelopeWithMetadataFrom;
import static uk.gov.justice.services.messaging.JsonEnvelope.envelopeFrom;

import uk.gov.justice.services.core.aggregate.AggregateService;
import uk.gov.justice.services.eventsourcing.source.core.EventSource;
import uk.gov.justice.services.eventsourcing.source.core.EventStream;
import uk.gov.justice.services.eventsourcing.source.core.exception.EventStreamException;
import uk.gov.justice.services.messaging.Envelope;
import uk.gov.justice.services.messaging.JsonEnvelope;
import uk.gov.moj.cpp.stagingdlrm.aggregate.MigratedCaseSubmissionAggregate;

import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.json.JsonValue;

public abstract class AbstractCommandHandler {

    @Inject
    protected EventSource eventSource;

    @Inject
    protected AggregateService aggregateService;


    protected void appendEventsToStream(final UUID streamId,
                                        final Envelope<?> envelope,
                                        final Function<MigratedCaseSubmissionAggregate, Stream<Object>> function) throws EventStreamException {
        EventStream eventStream = eventSource.getStreamById(streamId);
        final MigratedCaseSubmissionAggregate migratedCaseSubmissionAggregate = aggregateService.get(eventStream, MigratedCaseSubmissionAggregate.class);

        final Stream<Object> events = function.apply(migratedCaseSubmissionAggregate);
        final JsonEnvelope jsonEnvelope = JsonEnvelope.envelopeFrom(envelope.metadata(), JsonValue.NULL);

        eventStream.append(events.map(toEnvelopeWithMetadataFrom(jsonEnvelope)));
    }
}
