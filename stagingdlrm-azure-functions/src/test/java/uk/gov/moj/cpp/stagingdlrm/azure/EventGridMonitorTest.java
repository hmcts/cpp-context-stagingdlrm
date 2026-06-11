package uk.gov.moj.cpp.stagingdlrm.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;

import uk.gov.moj.cpp.stagingdlrm.azure.event.EventGridEvent;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.BlobCloudStorage;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventGridMonitorTest {

    @Mock
    private ExecutionContext context;

    @Mock
    private BlobCloudStorage blobCloudStorage;

    @InjectMocks
    private EventGridMonitor eventGridMonitor;

    @Captor
    private ArgumentCaptor<InputStream> inputStreamArgumentCaptor;

    @Captor
    private ArgumentCaptor<String> stringArgumentCaptor;

    @Captor
    private ArgumentCaptor<Long> longArgumentCaptor;

    private final Logger logger = Logger.getLogger(EventGridMonitor.class.getName());

    @BeforeEach
    public void setup() {
        setField(eventGridMonitor, "blobCloudStorage", blobCloudStorage);
    }

    @Test
    void shouldTestTimerTriggerSuccessfully() {

        final String caseUrn = "28DI10239082";

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String azureLocation = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String expected = """
                {
                    "caseUrn": "%s",
                    "success": %s,
                    "description": "%s"
                }"""
                .formatted(caseUrn, false, "Failed to storage materials");

        when(context.getLogger()).thenReturn(logger);

        doNothing().when(blobCloudStorage).uploadToStorage(
                inputStreamArgumentCaptor.capture(), longArgumentCaptor.capture(), stringArgumentCaptor.capture());


        EventGridEvent eventGridEvent = new EventGridEvent(
                new Date(),
                Map.of("caseUrn", "28DI10239082", "success", "false", "description", "Failed to storage materials", "azureLocation", azureLocation));

        eventGridMonitor.run(eventGridEvent, context);

        final String content = new BufferedReader(new InputStreamReader(inputStreamArgumentCaptor.getValue()))
                .lines()
                .collect(Collectors.joining("\n"));

        final List<String> allValues = stringArgumentCaptor.getAllValues();

        assertEquals(expected, content);
        assertNotNull(longArgumentCaptor.getValue());
        assertEquals(migrationSourceSystemName + File.separator + "outcome" + File.separator + "outcome-"+submissionId+".json", allValues.get(0));
        assertEquals(azureLocation + File.separator + "outcome.json", allValues.get(1));
    }
}