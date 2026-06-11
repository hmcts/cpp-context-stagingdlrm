package uk.gov.moj.cpp.stagingdlrm.azure.storage;

import static java.lang.System.getenv;
import static java.util.Objects.isNull;

import uk.gov.moj.cpp.stagingdlrm.azure.event.QueueMessage;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.LoggerHelper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueServiceClientBuilder;
import com.azure.storage.queue.models.QueueMessageItem;
import com.azure.storage.queue.models.SendMessageResult;
import com.microsoft.azure.functions.ExecutionContext;

public class StorageCloudClient {

    private Integer batchSizePerMin;
    private final List<QueueMessageItem> queueMessageItems;
    private QueueClient queueClient;
    private QueueClient dlrmLogQueueClient;
    private BlobContainerClient blobContainerClient;
    private final String dlrmContainer;
    private final String connectionString;
    private final String queueName;
    private final String dlrmLogQueueName;
    private Long visibility;
    private Integer queueVisibilityTimeoutSeconds;
    private final ExecutionContext context;
    private final LoggerHelper loggerHelper;

    private static final int DEFAULT_QUEUE_VISIBILITY_TIMEOUT_SECONDS = 300;

    public StorageCloudClient(final ExecutionContext context, final String connectionString, final String queueName, final String dlrmContainer, final String dlrmLogQueueName) {
        queueMessageItems = new ArrayList<>();
        this.dlrmContainer = dlrmContainer;
        this.connectionString = connectionString;
        this.queueName = queueName;
        this.dlrmLogQueueName = dlrmLogQueueName;
        this.context = context;
        this.loggerHelper = new LoggerHelper();
    }

    /**
     * This method fetches number of message as configured from the queue container and then gathers list of blob name into a map.
     *
     * @return map of queue container with list of blob name
     */
    public Map<String, QueueMessage> receiveMessages() {
        setStorageCloudClientProperties();
        int count = numberOfMessageFetchFromQueue();

        if (count == 0) {
            return new HashMap<>();
        }

        loggerHelper.logInfo(context, "The number of messages fetched from the queue : {0}", count);

        loggerHelper.logInfo(context, "Queue visibility timeout set to {0} seconds", queueVisibilityTimeoutSeconds);

        queueClient
                .receiveMessages(count, Duration.ofSeconds(queueVisibilityTimeoutSeconds), Duration.ofSeconds(50), null)
                .forEach(queueMessageItems::add);

        final Map<String, QueueMessage> queueMessageWithListOfBlobNamesMap = new HashMap<>();

        queueMessageItems.forEach(queueMessageItem -> {
            final String queueMessage = queueMessageItem.getBody().toString();
            final long dequeueCount = queueMessageItem.getDequeueCount();
            final List<String> listOfBlobNames = listFiles(blobContainerClient, queueMessage);
            queueMessageWithListOfBlobNamesMap.put(queueMessage, new QueueMessage(queueMessage, dequeueCount, listOfBlobNames));
        });

        loggerHelper.logInfo(context, "The messages fetched from the queue : {0}", queueMessageWithListOfBlobNamesMap.keySet());

        return queueMessageWithListOfBlobNamesMap;
    }

    /**
     * This method send the message with the visibility time into the queue container.
     *
     * @param message - String
     */
    public Response<SendMessageResult> sendMessageToTheQueue(String submissionId, String message) {
        setStorageCloudClientProperties();
        loggerHelper.logInfo(context, submissionId, "Sending message to queue: {0}, visibility: {1} day(s)", new Object[]{message, visibility});
        final Response<SendMessageResult> response = queueClient.sendMessageWithResponse(
                BinaryData.fromString(message),
                null,
                Duration.ofDays(visibility),
                null,
                Context.NONE);
        loggerHelper.logInfo(context, submissionId, "Message sent to queue with status code : {0}", response.getStatusCode());
        return response;
    }

    /**
     * This method send the message with the visibility time into the queue container.
     *
     * @param message - String
     */
    public void sendMessageToTheLogQueue(String message) {
        setStorageCloudClientProperties();
        loggerHelper.logInfo(context, "Sending message to queue: {0}, visibility: {1} day(s)", new Object[]{message, visibility});
        final Response<SendMessageResult> response = dlrmLogQueueClient.sendMessageWithResponse(
                BinaryData.fromString(message),
                null,
                Duration.ofDays(visibility),
                null,
                Context.NONE);
        loggerHelper.logInfo(context, "Message sent to queue with status code : {0}", response.getStatusCode());
    }

    /**
     * Downloads the content of a blob
     *
     * @param blobName - String
     * @return The download content of the blob as a String,or an empty string if there's an error.
     */
    public String downloadBlobContents(String submissionId, String blobName) {
        loggerHelper.logInfo(context, submissionId, "Downloading blob: {0}", new Object[]{blobName});
        final String content = blobContainerClient.getBlobClient(blobName).downloadContent().toString();
        loggerHelper.logInfo(context, submissionId, "Successfully downloaded blob: {0}", new Object[]{blobName});
        return content;
    }

    /**
     * Deletes a specific message from the queue
     *
     * @param message - String
     */
    public void deleteQueueMessage(String message) {
        loggerHelper.logInfo(context, "Deleting queue message: {0}", new Object[]{message});
        queueMessageItems.forEach(queueMessageItem -> {
            final String queueMessage = queueMessageItem.getBody().toString();
            if (queueMessage.equals(message)) {
                final String messageId = queueMessageItem.getMessageId();
                final String popReceipt = queueMessageItem.getPopReceipt();
                loggerHelper.logInfo(context, "Deleting message with id: {0}", new Object[]{message});
                final Response<Void> deleteResponse = queueClient.deleteMessageWithResponse(messageId, popReceipt, Duration.ofSeconds(30), Context.NONE);
                if (deleteResponse.getStatusCode() == 204) {
                    loggerHelper.logInfo(context, "Successfully deleted message: {0}, status: {1}", new Object[]{message, deleteResponse.getStatusCode()});
                } else {
                    loggerHelper.logInfo(context, "Unexpected response when deleting message: {0}, status: {1}", new Object[]{message, deleteResponse.getStatusCode()});
                }
            }
        });

        queueMessageItems.removeIf(queueMessage -> queueMessage.getBody().toString().equals(message));
        loggerHelper.logInfo(context, "Queue message removed from local cache: {0}", new Object[]{message});
    }

    private List<String> listFiles(BlobContainerClient blobContainerClient, String prefix) {
        final List<String> blobNames = new ArrayList<>();
        for (BlobItem blobItem : blobContainerClient.listBlobsByHierarchy("/", new ListBlobsOptions().setPrefix(prefix), null)) {
            if (blobItem.isPrefix()) {
                return listFiles(blobContainerClient, blobItem.getName());
            } else {
                blobNames.add(blobItem.getName());
            }
        }
        return blobNames;
    }

    private int numberOfMessageFetchFromQueue() {
        int count = queueClient.getProperties().getApproximateMessagesCount();

        if (count == 0) {
            return 0;
        }

        if (count > batchSizePerMin) {
            count = batchSizePerMin;
        }
        return count;
    }

    public String getDlrmContainer() {
        return dlrmContainer;
    }

    private void setQueueClient() {
        if (isNull(queueClient)) {
            queueClient = new QueueServiceClientBuilder()
                    .connectionString(connectionString)
                    .buildClient()
                    .getQueueClient(queueName);
        }
    }

    private void setDlrmLogQueueClient() {
        if (isNull(dlrmLogQueueClient)) {
            dlrmLogQueueClient = new QueueServiceClientBuilder()
                    .connectionString(connectionString)
                    .buildClient()
                    .getQueueClient(dlrmLogQueueName);
        }
    }

    private void setBlobContainerClient() {
        if (isNull(blobContainerClient)) {
            blobContainerClient = new BlobContainerClientBuilder().connectionString(connectionString).containerName(dlrmContainer).buildClient();
        }
    }

    private void setBatchSizePerMin() {
        if (isNull(batchSizePerMin)) {
            batchSizePerMin = Integer.parseInt(getenv("batch_size_per_min"));
        }
    }

    private void setVisibility() {
        if (isNull(visibility)) {
            visibility = Long.parseLong(getenv("visibility_time_in_days"));
        }
    }

    private void setQueueVisibilityTimeoutSeconds() {
        if (isNull(queueVisibilityTimeoutSeconds)) {
            final String envValue = getenv("queue_visibility_timeout_seconds");
            queueVisibilityTimeoutSeconds = isNull(envValue) ? DEFAULT_QUEUE_VISIBILITY_TIMEOUT_SECONDS : Integer.parseInt(envValue);
        }
    }

    private void setStorageCloudClientProperties() {
        setQueueClient();
        setDlrmLogQueueClient();
        setBlobContainerClient();
        setBatchSizePerMin();
        setVisibility();
        setQueueVisibilityTimeoutSeconds();
    }
}