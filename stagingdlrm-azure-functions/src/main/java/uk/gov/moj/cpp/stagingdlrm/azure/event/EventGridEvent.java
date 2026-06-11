package uk.gov.moj.cpp.stagingdlrm.azure.event;


import java.util.Date;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class EventGridEvent {

    private final Date eventTime;
    private final Map<String, Object> data;

    @JsonCreator
    public EventGridEvent(@JsonProperty("eventTime") final Date eventTime, @JsonProperty("data") final Map<String, Object> data) {
        this.eventTime = eventTime;
        this.data = data;
    }

    public Date getEventTime() {
        return eventTime;
    }

    public Map<String, Object> getData() {
        return data;
    }
}
