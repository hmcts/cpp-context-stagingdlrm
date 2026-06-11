package uk.gov.moj.cpp.stagingdlrm.azure;

import static java.lang.System.getenv;
import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.INFO;

import uk.gov.moj.cpp.stagingdlrm.azure.event.EventGridSchema;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.StorageCloudClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.azure.core.http.rest.Response;
import com.azure.storage.queue.models.SendMessageResult;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BlobInput;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

/**
 * Azure Functions with Event Grid trigger.
 */
public class EventGridTriggerJava {

    private StorageCloudClient storageCloudClient;

    private String folderName;

    private String batchName;

    /**
     * This method is triggered by Event Grid and process the file.
     * @param eventGridSchema - EventGridSchema
     * @param context - ExecutionContext
     * @param content - Content
     */
    @FunctionName("EventGridTriggerJava")
    public void run(@EventGridTrigger(name = "eventGridEvent") EventGridSchema eventGridSchema,
                    final ExecutionContext context,
                    @BlobInput(name = "file", dataType = "binary", path = "{data.url}", connection = "AzureWebJobsStorage") final byte[] content) {

        context.getLogger().info("EventGridTriggerJava function triggered.");

        if (isNull(content)) {
            context.getLogger().severe("Received content is empty.");
            return;
        }

        context.getLogger().log(INFO, "Received content of size: {0} bytes", content.length);

        setStorageCloudClient(context);

        final Map<String, Object> data = eventGridSchema.getData();

        final String url = (String) data.get("url");

        context.getLogger().log(INFO, "URL : {0}", url);

        final int startIndex = url.indexOf(storageCloudClient.getDlrmContainer());

        final String message = url.substring(startIndex + storageCloudClient.getDlrmContainer().length() + 1);

        context.getLogger().log(INFO, "Extracted blob path from URL: {0}", message);

        final List<String> tokens = List.of(message.split("/"));

        context.getLogger().log(INFO, "Parsed tokens: {0}", tokens);

        if (tokens.size() < 4) {
            context.getLogger().log(INFO,"Received invalid number of tokens. {0}", tokens);
            return;
        }

        requireNonNull(folderName, "dlrm_folder_name env var not configured.");

        if (!folderName.trim().equalsIgnoreCase(tokens.get(0))) {
            context.getLogger().log(INFO, "Received invalid dlrm folder name : {0}", tokens.get(0));
            return;
        }

        context.getLogger().log(INFO, "Folder name validated: {0}", tokens.get(0));

        requireNonNull(batchName, "dlrm_batch_name env var not configured.");

        final List<String> batchNames = Arrays.stream(batchName.split(","))
                .map(s -> s.trim().toLowerCase())
                .toList();

        boolean validBatchName = validateBatchNames(batchNames, tokens.get(1).toLowerCase());

        if (!validBatchName) {
            context.getLogger().log(INFO, "Received invalid dlrm batch name : {0}", tokens.get(1));
            return;
        }

        context.getLogger().log(INFO, "Batch name validated: {0}", tokens.get(1));

        final String queueMessage = "%s/%s/%s/%s".formatted(tokens.get(0), tokens.get(1), tokens.get(2), tokens.get(3));

        context.getLogger().log(INFO, "Sending message to the queue : {0}", queueMessage);

        Response<SendMessageResult> response = storageCloudClient.sendMessageToTheQueue(queueMessage);

        context.getLogger().info("Message %s expires at %s".formatted(response.getValue().getMessageId(), response.getValue().getExpirationTime()));

        context.getLogger().log(INFO, "The message sent successfully to the queue : {0}", queueMessage);

        context.getLogger().info("EventGridTriggerJava processing complete.");
    }

    private boolean validateBatchNames(final List<String> batchNames, String token) {

        if (batchNames.get(0).equalsIgnoreCase("*")) {
            return true;
        }

        return batchNames.contains(token);
    }

    private void setStorageCloudClient(final ExecutionContext context) {
        if(isNull(storageCloudClient)) {
            storageCloudClient = new StorageCloudClient(context, getenv("AzureWebJobsStorage"), getenv("dlrm_queue"), getenv("dlrm_container"));
        }

        if (isNull(folderName)) {
            folderName = getenv("dlrm_folder_name");
        }

        if (isNull(batchName)) {
            batchName = getenv("dlrm_batch_name");
        }
    }

}
