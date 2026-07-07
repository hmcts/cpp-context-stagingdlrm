package uk.gov.moj.cpp.stagingdlrm.azure.storage;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;

import uk.gov.moj.cpp.stagingdlrm.azure.event.QueueMessage;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponseBase;
import com.azure.core.http.rest.Response;
import com.azure.core.http.rest.SimpleResponse;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.azure.storage.queue.models.QueueProperties;
import com.azure.storage.queue.models.SendMessageResult;
import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageCloudClientTest {

    @Mock
    private QueueClient queueClient;

    @Mock
    private QueueClient dlrmLogQueueClient;

    @Mock
    private BlobContainerClient blobContainerClient;

    private final Logger logger = Logger.getLogger(StorageCloudClient.class.getName());

    @Mock
    private ExecutionContext context;

    @Mock
    private SimpleResponse<SendMessageResult> simpleResponse;

    @InjectMocks
    private StorageCloudClient storageCloudClient;

    @BeforeEach
    public void setup() {
        setField(storageCloudClient, "queueClient", queueClient);
        setField(storageCloudClient, "dlrmLogQueueClient", dlrmLogQueueClient);
        setField(storageCloudClient, "blobContainerClient", blobContainerClient);
        setField(storageCloudClient, "batchSizePerMin", 10);
        setField(storageCloudClient, "visibility", 1L);
        setField(storageCloudClient, "queueVisibilityTimeoutSeconds", 300);
    }

    @Test
    void shouldTestDownloadBlobContent() {

        String blobName = "blob.txt";
        String content = "Content";

        BlobClient blobClient = mock(BlobClient.class);
        BinaryData binaryData = BinaryData.fromString(content);

        when(context.getLogger()).thenReturn(logger);
        when(blobContainerClient.getBlobClient(blobName)).thenReturn(blobClient);
        when(blobClient.downloadContent()).thenReturn(binaryData);

        String downloadContent = storageCloudClient.downloadBlobContents(null, blobName);

        verify(blobContainerClient).getBlobClient(blobName);
        verify(blobClient).downloadContent();
        assertEquals(content, downloadContent);

    }

    @Test
    void shouldTestReceiveMessagesNoContent() {

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), 0);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        final Map<String, QueueMessage> messages = storageCloudClient.receiveMessages();

        assertTrue(messages.isEmpty());
    }

    @Test
    void shouldTestReceiveMessagesWithContent() {
        int messageCount = 1;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(300), Duration.ofSeconds(50), null))
                .thenReturn(new PagedIterable<>(() -> {
                    QueueMessageItem queueMessageItem = new QueueMessageItem();
                    queueMessageItem.setBody(BinaryData.fromString("queueMessage"));
                    return new PagedResponseBase<>(null, 200, null, List.of(queueMessageItem), null, null);
                }));

        when(blobContainerClient.listBlobsByHierarchy(any(String.class), any(ListBlobsOptions.class),eq(null)))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("test.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }));

       final Map<String, QueueMessage> resultMap =  storageCloudClient.receiveMessages();

       assertEquals(new QueueMessage("queueMessage", 0L, List.of("test.json")), resultMap.get("queueMessage"));

    }

    @Test
    void shouldTestReceiveMessagesWithContentWithPrefix() {
        int messageCount = 1;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(300), Duration.ofSeconds(50), null))
                .thenReturn(new PagedIterable<>(() -> {
                    QueueMessageItem queueMessageItem = new QueueMessageItem();
                    queueMessageItem.setBody(BinaryData.fromString("queueMessage"));
                    return new PagedResponseBase<>(null, 200, null, List.of(queueMessageItem), null, null);
                }));

        when(blobContainerClient.listBlobsByHierarchy(any(String.class), any(ListBlobsOptions.class),eq(null)))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("/abc/test.json");
                    blobItem.setIsPrefix(true);
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("test.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }));

        final Map<String, QueueMessage> resultMap =  storageCloudClient.receiveMessages();

        assertEquals(new QueueMessage("queueMessage", 0L, List.of("test.json")), resultMap.get("queueMessage"));

    }

    @Test
    void shouldReceive10MessageCountWhenQueueReturnsMoreThan10MessageCount() {
        int messageCountInQueue = 15;

        int messageCountConfigured = 10;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCountInQueue);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCountConfigured, Duration.ofSeconds(300), Duration.ofSeconds(50), null))
                .thenReturn(new PagedIterable<>(() -> {
                    QueueMessageItem queueMessageItem = new QueueMessageItem();
                    queueMessageItem.setBody(BinaryData.fromString("queueMessage"));
                    return new PagedResponseBase<>(null, 200, null, List.of(queueMessageItem), null, null);
                }));

        when(blobContainerClient.listBlobsByHierarchy(any(String.class), any(ListBlobsOptions.class),eq(null)))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("test.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }));

        final Map<String, QueueMessage> resultMap =  storageCloudClient.receiveMessages();

        assertEquals(new QueueMessage("queueMessage", 0L, List.of("test.json")), resultMap.get("queueMessage"));

        verify(queueClient).receiveMessages(messageCountConfigured, Duration.ofSeconds(300), Duration.ofSeconds(50), null);

    }

    @Test
    void shouldTestSendMessageToTheQueue() {
        final String message = "Test message";
        final String submissionId = UUID.randomUUID().toString();

        when(context.getLogger()).thenReturn(logger);
        when(queueClient.sendMessageWithResponse(any(BinaryData.class), eq(null), eq(Duration.ofDays(1L)), eq(null), eq(Context.NONE)))
                .thenReturn(simpleResponse);

        final Response<SendMessageResult> response = storageCloudClient.sendMessageToTheQueue(submissionId, message);

        assertEquals(simpleResponse, response);
        verify(queueClient).sendMessageWithResponse(any(BinaryData.class), eq(null), eq(Duration.ofDays(1L)), eq(null), eq(Context.NONE));
    }

    @Test
    void shouldDeleteQueueMessageWhenMessageFound() {
        final String message = "queueMessage";

        int messageCount = 1;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(300), Duration.ofSeconds(50), null))
                .thenReturn(new PagedIterable<>(() -> {
                    QueueMessageItem queueMessageItem = new QueueMessageItem();
                    queueMessageItem.setBody(BinaryData.fromString("queueMessage"));
                    return new PagedResponseBase<>(null, 200, null, List.of(queueMessageItem), null, null);
                }));

        when(blobContainerClient.listBlobsByHierarchy(any(String.class), any(ListBlobsOptions.class),eq(null)))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("test.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }));

        storageCloudClient.receiveMessages();

        when(queueClient.deleteMessageWithResponse(eq(null), eq(null), any(Duration.class), any(Context.class)))
                .thenReturn(new SimpleResponse<>(null, 204, null, null));

        storageCloudClient.deleteQueueMessage(message);

        verify(queueClient).deleteMessageWithResponse(eq(null), eq(null), any(Duration.class), any(Context.class));
    }

    @Test
    void shouldDeleteQueueMessageWhenMessageNotFound() {
        final String message = "testMessage";

        int messageCount = 1;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(300), Duration.ofSeconds(50), null))
                .thenReturn(new PagedIterable<>(() -> {
                    QueueMessageItem queueMessageItem = new QueueMessageItem();
                    queueMessageItem.setBody(BinaryData.fromString("queueMessage"));
                    return new PagedResponseBase<>(null, 200, null, List.of(queueMessageItem), null, null);
                }));

        when(blobContainerClient.listBlobsByHierarchy(any(String.class), any(ListBlobsOptions.class),eq(null)))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("test.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }));

        storageCloudClient.receiveMessages();

        storageCloudClient.deleteQueueMessage(message);

        verify(queueClient, never()).deleteMessageWithResponse(anyString(), anyString(), any(Duration.class), any(Context.class));
    }

    @Test
    void shouldReturnDlrmContainer() {
        final String dlrmContainer = "my-dlrm-container";
        final StorageCloudClient client = new StorageCloudClient(context, "connectionString", "queueName", dlrmContainer, "logQueueName");

        assertEquals(dlrmContainer, client.getDlrmContainer());
    }

    @Test
    void shouldReceiveMultipleMessagesFromQueue() {
        int messageCount = 2;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);
        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(300), Duration.ofSeconds(50), null))
                .thenReturn(new PagedIterable<>(() -> {
                    QueueMessageItem item1 = new QueueMessageItem();
                    item1.setBody(BinaryData.fromString("message1"));
                    QueueMessageItem item2 = new QueueMessageItem();
                    item2.setBody(BinaryData.fromString("message2"));
                    return new PagedResponseBase<>(null, 200, null, List.of(item1, item2), null, null);
                }));

        when(blobContainerClient.listBlobsByHierarchy(any(String.class), any(ListBlobsOptions.class), eq(null)))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("file1.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("file2.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }));

        final Map<String, QueueMessage> resultMap = storageCloudClient.receiveMessages();

        assertEquals(2, resultMap.size());
        assertEquals(new QueueMessage("message1", 0L, List.of("file1.json")), resultMap.get("message1"));
        assertEquals(new QueueMessage("message2", 0L, List.of("file2.json")), resultMap.get("message2"));
    }

    @Test
    void shouldPopulateDeliveryCountFromQueueMessageItem() {
        int messageCount = 1;
        long dequeueCount = 3L;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);
        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(300), Duration.ofSeconds(50), null))
                .thenReturn(new PagedIterable<>(() -> {
                    QueueMessageItem queueMessageItem = new QueueMessageItem();
                    queueMessageItem.setBody(BinaryData.fromString("queueMessage"));
                    queueMessageItem.setDequeueCount(dequeueCount);
                    return new PagedResponseBase<>(null, 200, null, List.of(queueMessageItem), null, null);
                }));

        when(blobContainerClient.listBlobsByHierarchy(any(String.class), any(ListBlobsOptions.class), eq(null)))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("test.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }));

        final Map<String, QueueMessage> resultMap = storageCloudClient.receiveMessages();

        assertEquals(new QueueMessage("queueMessage", dequeueCount, List.of("test.json")), resultMap.get("queueMessage"));
    }

    @Test
    void shouldNotDeleteQueueMessageFromQueueAfterItHasBeenRemovedFromLocalCache() {
        final String message = "queueMessage";
        int messageCount = 1;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);
        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(300), Duration.ofSeconds(50), null))
                .thenReturn(new PagedIterable<>(() -> {
                    QueueMessageItem queueMessageItem = new QueueMessageItem();
                    queueMessageItem.setBody(BinaryData.fromString("queueMessage"));
                    return new PagedResponseBase<>(null, 200, null, List.of(queueMessageItem), null, null);
                }));

        when(blobContainerClient.listBlobsByHierarchy(any(String.class), any(ListBlobsOptions.class), eq(null)))
                .thenReturn(new PagedIterable<>(() -> {
                    final BlobItem blobItem = new BlobItem();
                    blobItem.setName("test.json");
                    return new PagedResponseBase<>(null, 200, null, List.of(blobItem), null, null);
                }));

        when(queueClient.deleteMessageWithResponse(eq(null), eq(null), any(Duration.class), any(Context.class)))
                .thenReturn(new SimpleResponse<>(null, 204, null, null));

        storageCloudClient.receiveMessages();
        storageCloudClient.deleteQueueMessage(message);
        storageCloudClient.deleteQueueMessage(message);

        verify(queueClient).deleteMessageWithResponse(eq(null), eq(null), any(Duration.class), any(Context.class));
    }

}


