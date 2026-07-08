package uk.gov.moj.cpp.stagingdlrm.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;

import uk.gov.moj.cpp.stagingdlrm.azure.event.EventGridEvent;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.EventGridMonitorHelper;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventGridMonitorTest {

    @Mock
    private ExecutionContext context;

    @Mock
    private EventGridMonitorHelper helper;

    @InjectMocks
    private EventGridMonitor eventGridMonitor;

    private final Logger logger = Logger.getLogger(EventGridMonitor.class.getName());

    @BeforeEach
    public void setup() {
        setField(eventGridMonitor, "helper", helper);
    }

    @Test
    void shouldProcessEventAndWriteOutcomeToCorrectLocations() {
        final String migrationSourceSystemName = "XHIBIT";
        final String batchIdentifier = "20082025";
        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();
        final String submissionId = UUID.randomUUID().toString();
        final String azureLocation = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final Map<String, Object> eventData = Map.of(
                "caseUrn", "28DI10239082",
                "success", "false",
                "description", "Failed to storage materials",
                "azureLocation", azureLocation
        );
        final EventGridEvent eventGridEvent = new EventGridEvent(new Date(), eventData);

        when(context.getLogger()).thenReturn(logger);

        eventGridMonitor.run(eventGridEvent, context);

        verify(helper).processEvent(eventData, migrationSourceSystemName, "outcome/outcome-" + submissionId + ".json");
        verify(helper).processEvent(eventData, azureLocation, "outcome.json");
    }

    @Test
    void shouldUseAzureLocationAsMigrationSourceSystemNameWhenItDoesNotHaveFourParts() {
        final String azureLocation = "XHIBIT/20082025";

        final Map<String, Object> eventData = Map.of(
                "caseUrn", "28DI10239082",
                "success", "false",
                "description", "Failed",
                "azureLocation", azureLocation
        );
        final EventGridEvent eventGridEvent = new EventGridEvent(new Date(), eventData);

        when(context.getLogger()).thenReturn(logger);

        final ArgumentCaptor<String> locationCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);

        eventGridMonitor.run(eventGridEvent, context);

        verify(helper, times(2)).processEvent(any(), locationCaptor.capture(), fileNameCaptor.capture());

        assertEquals(azureLocation, locationCaptor.getAllValues().get(0));
        assertTrue(fileNameCaptor.getAllValues().get(0).startsWith("outcome/outcome-"));
        assertTrue(fileNameCaptor.getAllValues().get(0).endsWith(".json"));

        assertEquals(azureLocation, locationCaptor.getAllValues().get(1));
        assertEquals("outcome.json", fileNameCaptor.getAllValues().get(1));
    }
}