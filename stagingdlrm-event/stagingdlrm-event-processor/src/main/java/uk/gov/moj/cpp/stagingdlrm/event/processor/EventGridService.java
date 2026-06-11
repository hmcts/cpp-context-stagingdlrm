package uk.gov.moj.cpp.stagingdlrm.event.processor;

import uk.gov.justice.services.common.configuration.Value;
import uk.gov.moj.cpp.stagingdlrm.event.processor.domain.Outcome;

import java.util.List;
import java.util.UUID;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import com.microsoft.azure.eventgrid.EventGridClient;
import com.microsoft.azure.eventgrid.TopicCredentials;
import com.microsoft.azure.eventgrid.implementation.EventGridClientImpl;
import com.microsoft.azure.eventgrid.models.EventGridEvent;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventGridService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventGridService.class);

    @Inject
    @Value(key = "dlrmEventGridTopicHost", defaultValue = "localhost")
    private String eventgridTopicHost;

    @Inject
    @Value(key = "dlrmEventGridTopicKey", defaultValue = "test_key")
    private String eventgridTopicKey;

    @Inject
    @Value(key = "eventGridTopicProtocol", defaultValue = "http")
    private String eventgridTopicProtocol;

    @Inject
    @Value(key = "eventGridTopicPort", defaultValue = "8080")
    private String eventgridTopicPort;

    private EventGridClient eventGridClient;

    @PostConstruct
    public void setup() {
        LOGGER.info("Event Grid Topic Host is {}", eventgridTopicHost);
        this.eventGridClient = new EventGridClientImpl(new TopicCredentials(eventgridTopicKey));
    }

    public void sendEventToEventGrid(final Outcome outcome) {
        if ("localhost".equalsIgnoreCase(eventgridTopicHost)) {
            LOGGER.info("Cannot send event to {}", eventgridTopicHost);
            return;
        }

        final List<EventGridEvent> eventGridEvents = List.of(new EventGridEvent(
                UUID.randomUUID().toString(),
                String.format("OutcomeEvent%s", outcome.submissionId()),
                outcome,
                "OutcomeEventType",
                DateTime.now(),
                "1.0"));

        final String eventGridEndpoint = "%s://%s:%s/".formatted(eventgridTopicProtocol, eventgridTopicHost, eventgridTopicPort);

        eventGridClient.publishEvents(eventGridEndpoint, eventGridEvents);

        LOGGER.info("Publishing Outcome event to the Event Grid - {} ", eventGridEvents);

    }

}
