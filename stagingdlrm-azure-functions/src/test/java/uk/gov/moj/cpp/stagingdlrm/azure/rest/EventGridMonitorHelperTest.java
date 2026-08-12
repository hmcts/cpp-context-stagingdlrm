package uk.gov.moj.cpp.stagingdlrm.azure.rest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.io.StringReader;
import java.util.Map;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonObject;

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

        final String uploadedContent = new String(inputStreamCaptor.getValue().readAllBytes(), UTF_8);
        final JsonObject json = Json.createReader(new StringReader(uploadedContent)).readObject();
        assertEquals("URN:12345", json.getString("caseUrn"));
        assertTrue(json.getBoolean("success"));
        assertEquals("Processed successfully", json.getString("description"));
        assertEquals(uploadedContent.getBytes(UTF_8).length, sizeCaptor.getValue());
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

        final String uploadedContent = new String(inputStreamCaptor.getValue().readAllBytes(), UTF_8);
        final JsonObject json = Json.createReader(new StringReader(uploadedContent)).readObject();
        assertEquals("URN:99999", json.getString("caseUrn"));
        assertFalse(json.getBoolean("success"));
        assertEquals("Processing failed", json.getString("description"));
    }

    @Test
    void shouldProduceValidJsonWhenDescriptionContainsSpecialCharacters() throws Exception {
        final Map<String, Object> event = Map.of(
                "caseUrn", "URN:12345",
                "success", false,
                "description", "HTTP 500 \"internal error\"\nwith newline"
        );
        when(context.getLogger()).thenReturn(logger);

        eventGridMonitorHelper.processEvent(event, AZURE_LOCATION, FILE_NAME);

        final ArgumentCaptor<InputStream> inputStreamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(blobCloudStorage).uploadToStorage(inputStreamCaptor.capture(), anyLong(), any());

        final String uploadedContent = new String(inputStreamCaptor.getValue().readAllBytes(), UTF_8);
        final JsonObject json = Json.createReader(new StringReader(uploadedContent)).readObject();
        assertEquals("HTTP 500 \"internal error\"\nwith newline", json.getString("description"));
        assertEquals("URN:12345", json.getString("caseUrn"));
    }

    /**
     * DD-43086 FR8/AC7 — a Function App-level validation failure rejects the payload before it is
     * ever parsed, so {@code caseUrn} is empty by construction (not absent from the map — see
     * {@code TimerTriggerJava.processClientError(QueueMessage, List, String, String, String)},
     * which passes {@code caseUrn = ""}). The outcome file must still be usable: valid JSON,
     * {@code success=false}, the validation error in {@code description}, and an explicit empty
     * (not null, not missing) {@code caseUrn} — asserted whole, not just "some content exists".
     */
    @Test
    void shouldUploadUsableOutcomeContentWhenCaseUrnIsAbsentByConstruction() throws Exception {
        final Map<String, Object> event = Map.of(
                "caseUrn", "",
                "success", "false",
                "description", "$.migratedCase.caseDetails.initiationCode: is missing but it is required"
        );
        when(context.getLogger()).thenReturn(logger);

        eventGridMonitorHelper.processEvent(event, "LIBRA/Batch0001/case-1/submission-1", FILE_NAME);

        final ArgumentCaptor<InputStream> inputStreamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(blobCloudStorage).uploadToStorage(inputStreamCaptor.capture(), anyLong(), any());

        final String uploadedContent = new String(inputStreamCaptor.getValue().readAllBytes(), UTF_8);
        final JsonObject json = Json.createReader(new StringReader(uploadedContent)).readObject();
        assertEquals(Map.of(
                "caseUrn", "",
                "success", false,
                "description", "$.migratedCase.caseDetails.initiationCode: is missing but it is required"
        ).keySet(), json.keySet(), () -> "outcome file must contain exactly these three fields, no more, no fewer: " + uploadedContent);
        assertEquals("", json.getString("caseUrn"));
        assertFalse(json.getBoolean("success"));
        assertEquals("$.migratedCase.caseDetails.initiationCode: is missing but it is required", json.getString("description"));
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