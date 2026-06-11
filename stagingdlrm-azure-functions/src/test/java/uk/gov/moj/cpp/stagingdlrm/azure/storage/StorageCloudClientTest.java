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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private BlobContainerClient blobContainerClient;

    private final Logger logger = Logger.getLogger(StorageCloudClient.class.getName());

    @Mock
    private ExecutionContext context;

    @Mock
    private SimpleResponse<SendMessageResult> simpleResponse;

    @Mock
    private SendMessageResult sendMessageResult;

    @InjectMocks
    private StorageCloudClient storageCloudClient;

    @BeforeEach
    public void setup() {
        when(context.getLogger()).thenReturn(logger);
        setField(storageCloudClient, "queueClient", queueClient);
        setField(storageCloudClient, "blobContainerClient", blobContainerClient);
        setField(storageCloudClient, "batchSizePerMin", 10);
        setField(storageCloudClient, "visibility", 1L);
    }

    @Test
    void shouldTestDownloadBlobContent() {

        String blobName = "blob.txt";
        String content = "Content";

        BlobClient blobClient = mock(BlobClient.class);
        BinaryData binaryData = BinaryData.fromString(content);

        when(blobContainerClient.getBlobClient(blobName)).thenReturn(blobClient);
        when(blobClient.downloadContent()).thenReturn(binaryData);

        String downloadContent = storageCloudClient.downloadBlobContents(blobName);

        verify(blobContainerClient).getBlobClient(blobName);
        verify(blobClient).downloadContent();
        assertEquals(content, downloadContent);

    }

    @Test
    void shouldTestReceiveMessagesNoContent() {

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), 0);

        when(queueClient.getProperties()).thenReturn(queueProperties);
        when(context.getLogger()).thenReturn(logger);

        final Map<String, List<String>> messages = storageCloudClient.receiveMessages();

        assertTrue(messages.isEmpty());
    }

    @Test
    void shouldTestReceiveMessagesWithContent() {
        int messageCount = 1;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(30), Duration.ofSeconds(50), null))
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

       final Map<String, List<String>> resultMap =  storageCloudClient.receiveMessages();

       assertEquals(resultMap.get("queueMessage"), List.of("test.json"));

    }

    @Test
    void shouldTestReceiveMessagesWithContentWithPrefix() {
        int messageCount = 1;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(30), Duration.ofSeconds(50), null))
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

        final Map<String, List<String>> resultMap =  storageCloudClient.receiveMessages();

        assertEquals(resultMap.get("queueMessage"), List.of("test.json"));

    }

    @Test
    void shouldReceive10MessageCountWhenQueueReturnsMoreThan10MessageCount() {
        int messageCountInQueue = 15;

        int messageCountConfigured = 10;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCountInQueue);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCountConfigured, Duration.ofSeconds(30), Duration.ofSeconds(50), null))
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

        final Map<String, List<String>> resultMap =  storageCloudClient.receiveMessages();

        assertEquals(resultMap.get("queueMessage"), List.of("test.json"));

        verify(queueClient).receiveMessages(messageCountConfigured, Duration.ofSeconds(30), Duration.ofSeconds(50), null);

    }

    @Test
    void shouldTestSendMessageToTheQueue() {
        final String message = "Test message";

        when(simpleResponse.getValue()).thenReturn(sendMessageResult);
        
        when(queueClient.sendMessageWithResponse(any(BinaryData.class), eq(null), eq(Duration.ofDays(1L)), eq(null), eq(Context.NONE)))
                .thenReturn(simpleResponse);

        final Response<SendMessageResult> response = storageCloudClient.sendMessageToTheQueue(message);

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

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(30), Duration.ofSeconds(50), null))
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

        verify(queueClient).deleteMessage(eq(null), eq(null));
    }

    @Test
    void shouldDeleteQueueMessageWhenMessageNotFound() {
        final String message = "testMessage";

        int messageCount = 1;

        final QueueProperties queueProperties = new QueueProperties(new HashMap<>(), messageCount);

        when(queueClient.getProperties()).thenReturn(queueProperties);

        when(context.getLogger()).thenReturn(logger);

        when(queueClient.receiveMessages(messageCount, Duration.ofSeconds(30), Duration.ofSeconds(50), null))
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

        verify(queueClient, never()).deleteMessage(anyString(), anyString());
    }

}


