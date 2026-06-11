package uk.gov.moj.cpp.stagingdlrm.azure.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;

import uk.gov.moj.cpp.stagingdlrm.azure.storage.BlobCloudStorage;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.logging.Logger;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventGridMonitorHelperTest {

    private static final String CONNECTION_STRING = "UseDevelopmentStorage=true";
    private static final String CONTAINER_REFERENCE = "test-container";
    private static final String AZURE_LOCATION = "some/azure/location";
    private static final String FILE_NAME = "outcome.json";

    @Mock
    private ExecutionContext context;

    @Mock
    private BlobCloudStorage blobCloudStorage;

    private final Logger logger = Logger.getLogger(EventGridMonitorHelper.class.getName());

    private EventGridMonitorHelper eventGridMonitorHelper;

    @BeforeEach
    void setup() {
        eventGridMonitorHelper = new EventGridMonitorHelper(context, CONNECTION_STRING, CONTAINER_REFERENCE);
        setField(eventGridMonitorHelper, "blobCloudStorage", blobCloudStorage);
    }

    @Test
    void shouldUploadOutcomeToCorrectPath() {
        final Map<String, Object> event = Map.of(
                "caseUrn", "URN:12345",
                "success", true,
                "description", "Processed successfully"
        );
        when(context.getLogger()).thenReturn(logger);

        eventGridMonitorHelper.processEvent(event, AZURE_LOCATION, FILE_NAME);

        final String expectedPath = AZURE_LOCATION + File.separator + FILE_NAME;
        verify(blobCloudStorage).uploadToStorage(any(InputStream.class), anyLong(), eq(expectedPath));
    }

    @Test
    void shouldUploadCorrectOutcomeJsonContentWhenSuccessful() throws Exception {
        final Map<String, Object> event = Map.of(
                "caseUrn", "URN:12345",
                "success", true,
                "description", "Processed successfully"
        );
        when(context.getLogger()).thenReturn(logger);

        eventGridMonitorHelper.processEvent(event, AZURE_LOCATION, FILE_NAME);

        final ArgumentCaptor<InputStream> inputStreamCaptor = ArgumentCaptor.forClass(InputStream.class);
        final ArgumentCaptor<Long> sizeCaptor = ArgumentCaptor.forClass(Long.class);
        verify(blobCloudStorage).uploadToStorage(inputStreamCaptor.capture(), sizeCaptor.capture(), any());

        final String uploadedContent = new String(inputStreamCaptor.getValue().readAllBytes());
        assertTrue(uploadedContent.contains("\"caseUrn\": \"URN:12345\""));
        assertTrue(uploadedContent.contains("\"success\": true"));
        assertTrue(uploadedContent.contains("\"description\": \"Processed successfully\""));
        assertEquals((long) uploadedContent.getBytes().length, sizeCaptor.getValue());
    }

    @Test
    void shouldUploadCorrectOutcomeJsonContentWhenFailed() throws Exception {
        final Map<String, Object> event = Map.of(
                "caseUrn", "URN:99999",
                "success", false,
                "description", "Processing failed"
        );
        when(context.getLogger()).thenReturn(logger);

        eventGridMonitorHelper.processEvent(event, AZURE_LOCATION, FILE_NAME);

        final ArgumentCaptor<InputStream> inputStreamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(blobCloudStorage).uploadToStorage(inputStreamCaptor.capture(), anyLong(), any());

        final String uploadedContent = new String(inputStreamCaptor.getValue().readAllBytes());
        assertTrue(uploadedContent.contains("\"caseUrn\": \"URN:99999\""));
        assertTrue(uploadedContent.contains("\"success\": false"));
        assertTrue(uploadedContent.contains("\"description\": \"Processing failed\""));
    }

    @Test
    void shouldNotReinitializeBlobCloudStorageIfAlreadyPresent() {
        final Map<String, Object> event = Map.of(
                "caseUrn", "URN:12345",
                "success", true,
                "description", "Processed"
        );
        when(context.getLogger()).thenReturn(logger);

        eventGridMonitorHelper.processEvent(event, AZURE_LOCATION, FILE_NAME);
        eventGridMonitorHelper.processEvent(event, AZURE_LOCATION, FILE_NAME);

        verify(blobCloudStorage, times(2)).uploadToStorage(any(), anyLong(), any());
    }
}