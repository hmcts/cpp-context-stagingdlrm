package uk.gov.moj.cpp.stagingdlrm.event.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doNothing;

import uk.gov.moj.cpp.stagingdlrm.event.processor.domain.Outcome;

import java.util.List;
import java.util.UUID;

import com.microsoft.azure.eventgrid.EventGridClient;
import com.microsoft.azure.eventgrid.models.EventGridEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventGridServiceTest {

    @InjectMocks
    private EventGridService eventGridService;

    @Mock
    private EventGridClient eventGridClient;

    @Captor
    private ArgumentCaptor<String> stringArgumentCaptor;

    @Captor
    private ArgumentCaptor<List<EventGridEvent>> listArgumentCaptor;

    @Test
    void shouldSendEventToEventGridSuccessfully() {

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final UUID caseId = UUID.randomUUID();
        final String caseUrn = UUID.randomUUID().toString();
        final String description = "Test Description";
        final boolean success = false;
        final UUID submissionId = UUID.randomUUID();

        final String azureLocation = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId.toString());

        final Outcome outcome = new Outcome(caseId, submissionId, caseUrn, success, description, azureLocation);

        doNothing().when(eventGridClient).publishEvents(stringArgumentCaptor.capture(), listArgumentCaptor.capture());

        eventGridService.sendEventToEventGrid(outcome);

        List<EventGridEvent> eventGridEventList = listArgumentCaptor.getValue();

        assertEquals(1, eventGridEventList.size());

        final Outcome actual = (Outcome) eventGridEventList.get(0).data();

        assertEquals(outcome, actual);

    }

}