package uk.gov.moj.cpp.stagingdlrm.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;

import uk.gov.moj.cpp.stagingdlrm.azure.event.EventGridSchema;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.StorageCloudClient;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import com.azure.core.http.rest.Response;
import com.azure.storage.queue.models.SendMessageResult;
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
class EventGridTriggerJavaTest {

    @Mock
    private static StorageCloudClient storageCloudClient;

    @Mock
    private ExecutionContext context;

    @Mock
    private Response<SendMessageResult> response;

    @Mock
    private SendMessageResult sendMessageResult;

    @Captor
    private ArgumentCaptor<String> stringArgumentCaptor;

    @InjectMocks
    private EventGridTriggerJava eventGridTriggerJava;

    private final Logger logger = Logger.getLogger(EventGridTriggerJava.class.getName());

    @BeforeEach
    public void setup() {
        setField(eventGridTriggerJava, "storageCloudClient", storageCloudClient);
        setField(eventGridTriggerJava, "folderName", " XHIBIT");
        setField(eventGridTriggerJava, "batchName", " batch0001,batch0002");
    }

    @Test
    void shouldTestRunSuccessfully() {

        final String caseUrn = "T00123456789";

        final String subscriptionId = UUID.randomUUID().toString();

        final String url = "https://stedlrmsa.blob.core.windows.net/dlrmcontainer/XHIBIT/Batch0001/" + caseUrn + "/" +subscriptionId + "/test1.json";

        final Map<String, Object> data = new HashMap<>();

        data.put("url", url);

        final EventGridSchema eventGridSchema = new EventGridSchema(new Date(), data);

        when(context.getLogger()).thenReturn(logger);

        when(storageCloudClient.getDlrmContainer()).thenReturn("dlrmcontainer");

        when(storageCloudClient.sendMessageToTheQueue(stringArgumentCaptor.capture())).thenReturn(response);

        when(response.getValue()).thenReturn(sendMessageResult);

        eventGridTriggerJava.run(eventGridSchema, context, new byte[]{});

        assertEquals("XHIBIT/Batch0001/"+caseUrn+"/"+subscriptionId, stringArgumentCaptor.getValue());

    }

    @Test
    void shouldExtractPathSuccessfully() {

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "Batch0001";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String url = "https://stedlrmsa.blob.core.windows.net/dlrmcontainer/%s/%s/%s/%s/test1.json"
                .formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String expected = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final Map<String, Object> data = Map.of("url", url);

        final EventGridSchema eventGridSchema = new EventGridSchema(new Date(), data);

        when(context.getLogger()).thenReturn(logger);

        when(storageCloudClient.getDlrmContainer()).thenReturn("dlrmcontainer");

        when(storageCloudClient.sendMessageToTheQueue(stringArgumentCaptor.capture())).thenReturn(response);

        when(response.getValue()).thenReturn(sendMessageResult);

        eventGridTriggerJava.run(eventGridSchema, context, new byte[]{});

        assertEquals(expected, stringArgumentCaptor.getValue());

    }

    @Test
    void shouldReturnWhenContentIsNull() {

        final EventGridSchema eventGridSchema = new EventGridSchema(new Date(), new HashMap<>());

        when(context.getLogger()).thenReturn(logger);

        eventGridTriggerJava.run(eventGridSchema, context, null);

        verify(storageCloudClient, never()).sendMessageToTheQueue(anyString());
    }

    @Test
    void shouldReturnWhenTokenIsEmpty() {

        final String url = "https://stedlrmsa.blob.core.windows.net/dlrmcontainer/test1.json";

        final Map<String, Object> data = Map.of("url", url);

        final EventGridSchema eventGridSchema = new EventGridSchema(new Date(), data);

        when(context.getLogger()).thenReturn(logger);

        when(storageCloudClient.getDlrmContainer()).thenReturn("dlrmcontainer");

        eventGridTriggerJava.run(eventGridSchema, context, new byte[]{});

        verify(storageCloudClient, never()).sendMessageToTheQueue(anyString());
    }

    @Test
    void shouldReturnWhenMigrationSourceSystemNameIsDifferent() {

        final String migrationSourceSystemName = "XHIBIT1";

        final String batchIdentifier = "Batch0001";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String url = "https://stedlrmsa.blob.core.windows.net/dlrmcontainer/%s/%s/%s/%s/test1.json"
                .formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final Map<String, Object> data = Map.of("url", url);

        final EventGridSchema eventGridSchema = new EventGridSchema(new Date(), data);

        when(context.getLogger()).thenReturn(logger);

        when(storageCloudClient.getDlrmContainer()).thenReturn("dlrmcontainer");

        eventGridTriggerJava.run(eventGridSchema, context, new byte[]{});

        verify(storageCloudClient, never()).sendMessageToTheQueue(anyString());
    }

    @Test
    void shouldExtractPathSuccessfullyWhenBatchNameIsSetAsWildcard() {

        setField(eventGridTriggerJava, "batchName", "*");

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "Batch0003";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String url = "https://stedlrmsa.blob.core.windows.net/dlrmcontainer/%s/%s/%s/%s/test1.json"
                .formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final Map<String, Object> data = Map.of("url", url);

        final EventGridSchema eventGridSchema = new EventGridSchema(new Date(), data);

        final String message = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        when(context.getLogger()).thenReturn(logger);

        when(storageCloudClient.getDlrmContainer()).thenReturn("dlrmcontainer");

        when(storageCloudClient.sendMessageToTheQueue(message)).thenReturn(response);

        when(response.getValue()).thenReturn(sendMessageResult);

        eventGridTriggerJava.run(eventGridSchema, context, new byte[]{});

        verify(storageCloudClient).sendMessageToTheQueue(message);
    }

    @Test
    void shouldReturnWhenBatchIdentifierIsDifferent() {

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "Batch0003";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String url = "https://stedlrmsa.blob.core.windows.net/dlrmcontainer/%s/%s/%s/%s/test1.json"
                .formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final Map<String, Object> data = Map.of("url", url);

        final EventGridSchema eventGridSchema = new EventGridSchema(new Date(), data);

        when(context.getLogger()).thenReturn(logger);

        when(storageCloudClient.getDlrmContainer()).thenReturn("dlrmcontainer");

        eventGridTriggerJava.run(eventGridSchema, context, new byte[]{});

        verify(storageCloudClient, never()).sendMessageToTheQueue(anyString());
    }
}