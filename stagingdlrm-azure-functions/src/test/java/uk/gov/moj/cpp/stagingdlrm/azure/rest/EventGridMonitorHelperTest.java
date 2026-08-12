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
     * DD-43086 LIBRA03/AC7 (FR8 confirm) — for a Function-App-level rejection the case is never
     * parsed, so {@code caseUrn} arrives as an explicit empty string. The written outcome file is
     * asserted <b>whole</b>: {@code caseUrn: ""} (an empty string, not null and not a missing key),
     * {@code success: false}, the populated {@code description}, and nothing else — and it is written
     * under the submission-derived LIBRA path, not a configured constant.
     */
    @Test
    void shouldWriteWholeOutcomeWithEmptyCaseUrnForFunctionAppLevelRejection() throws Exception {
        final String libraLocation = "LIBRA/batch1/CASEREF-0001/submission1";
        final Map<String, Object> event = Map.of(
                "caseUrn", "",
                "success", "false",
                "description", "LIBRA case failed schema validation at the Function App gate"
        );
        when(context.getLogger()).thenReturn(logger);

        eventGridMonitorHelper.processEvent(event, libraLocation, FILE_NAME);

        final ArgumentCaptor<InputStream> inputStreamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(blobCloudStorage).uploadToStorage(inputStreamCaptor.capture(), anyLong(),
                eq(libraLocation + File.separator + FILE_NAME));

        final String uploadedContent = new String(inputStreamCaptor.getValue().readAllBytes(), UTF_8);
        final JsonObject json = Json.createReader(new StringReader(uploadedContent)).readObject();
        assertEquals(3, json.size(), () -> "outcome should carry exactly caseUrn, success, description: " + json);
        assertEquals("", json.getString("caseUrn"),
                () -> "caseUrn must be an explicit empty string, not null or missing: " + json);
        assertFalse(json.getBoolean("success"));
        assertEquals("LIBRA case failed schema validation at the Function App gate", json.getString("description"));
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