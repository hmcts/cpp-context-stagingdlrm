package uk.gov.moj.cpp.stagingdlrm.azure.storage;

import static java.lang.System.getenv;
import static java.util.Objects.isNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

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
    private BlobContainerClient blobContainerClient;
    private final String dlrmContainer;
    private final String connectionString;
    private final String queueName;
    private Long visibility;
    private final ExecutionContext context;

    public StorageCloudClient(final ExecutionContext context, final String connectionString, final String queueName, final String dlrmContainer) {
        queueMessageItems = new ArrayList<>();
        this.dlrmContainer = dlrmContainer;
        this.connectionString = connectionString;
        this.queueName = queueName;
        this.context = context;
    }

    /**
     * This method fetches number of message as configured from the queue container and then gathers list of blob name into a map.
     *
     * @return map of queue container with list of blob name
     */
    public Map<String, List<String>> receiveMessages() {
        setStorageCloudClientProperties();
        int count = numberOfMessageFetchFromQueue();

        if (count == 0) {
            context.getLogger().info("No messages in the queue.");
            return new HashMap<>();
        }

        context.getLogger().log(Level.INFO, "The number of messages fetched from the queue : {0}", count);

        queueClient
                .receiveMessages(count, Duration.ofSeconds(30), Duration.ofSeconds(50), null)
                .forEach(queueMessageItems::add);

        final Map<String, List<String>> queueMessageWithListOfBlobNamesMap = new HashMap<>();

        queueMessageItems.forEach(queueMessageItem -> {
            final String queueMessage = queueMessageItem.getBody().toString();
            final List<String> listOfBlobNames = listFiles(blobContainerClient, queueMessage);
            queueMessageWithListOfBlobNamesMap.put(queueMessage, listOfBlobNames);
        });

        context.getLogger().log(Level.INFO, "The messages fetched from the queue : {0}", queueMessageWithListOfBlobNamesMap.keySet());

        return queueMessageWithListOfBlobNamesMap;
    }

    /**
     * This method send the message with the visibility time into the queue container.
     *
     * @param message - String
     */
    public Response<SendMessageResult> sendMessageToTheQueue(String message) {
        setStorageCloudClientProperties();
        context.getLogger().log(Level.INFO, "Sending message to queue: {0}, visibility: {1} day(s)", new Object[]{message, visibility});
        final Response<SendMessageResult> response = queueClient.sendMessageWithResponse(
                BinaryData.fromString(message),
                null,
                Duration.ofDays(visibility),
                null,
                Context.NONE);
        context.getLogger().log(Level.INFO, "Message sent to queue with id: {0}", response.getValue().getMessageId());
        return response;
    }

    /**
     * Downloads the content of a blob
     *
     * @param blobName - String
     * @return The download content of the blob as a String,or an empty string if there's an error.
     */
    public String downloadBlobContents(String blobName) {
        context.getLogger().log(Level.INFO, "Downloading blob: {0}", blobName);
        final String content = blobContainerClient.getBlobClient(blobName).downloadContent().toString();
        context.getLogger().log(Level.INFO, "Successfully downloaded blob: {0}", blobName);
        return content;
    }

    /**
     * Deletes a specific message from the queue
     *
     * @param message - String
     */
    public void deleteQueueMessage(String message) {
        context.getLogger().log(Level.INFO, "Deleting queue message: {0}", message);
        queueMessageItems.forEach(queueMessageItem -> {
            final String queueMessage = queueMessageItem.getBody().toString();
            if (queueMessage.equals(message)) {
                final String messageId = queueMessageItem.getMessageId();
                final String popReceipt = queueMessageItem.getPopReceipt();
                context.getLogger().log(Level.INFO, "Deleting message with id: {0}", messageId);
                queueClient.deleteMessage(messageId, popReceipt);
                context.getLogger().log(Level.INFO, "Successfully deleted message with id: {0}", messageId);
            }
        });

        queueMessageItems.removeIf(queueMessage -> queueMessage.getBody().toString().equals(message));
        context.getLogger().log(Level.INFO, "Queue message removed from local cache: {0}", message);
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

    private void setStorageCloudClientProperties() {
        setQueueClient();
        setBlobContainerClient();
        setBatchSizePerMin();
        setVisibility();
    }
}
