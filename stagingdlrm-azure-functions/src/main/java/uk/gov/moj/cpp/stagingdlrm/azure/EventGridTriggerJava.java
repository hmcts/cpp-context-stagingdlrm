package uk.gov.moj.cpp.stagingdlrm.azure;

import static java.lang.System.getenv;
import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;

import uk.gov.moj.cpp.stagingdlrm.azure.event.EventGridSchema;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.LoggerHelper;
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

    private LoggerHelper loggerHelper;

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

        setLoggerHelper();

        loggerHelper.logInfo(context, "EventGridTriggerJava function triggered.");

        if (isNull(content) || content.length == 0) {
            loggerHelper.logInfo(context, "Received content is empty.");
            return;
        }

        setStorageCloudClient(context);

        final Map<String, Object> data = eventGridSchema.getData();

        final String url = (String) data.get("url");

        final int startIndex = url.indexOf(storageCloudClient.getDlrmContainer());

        final String message = url.substring(startIndex + storageCloudClient.getDlrmContainer().length() + 1);

        final List<String> tokens = List.of(message.split("/"));

        final String submissionId = tokens.get(tokens.size() - 2);

        loggerHelper.logInfo(context, submissionId, "Received content of size: {0} bytes", content.length);

        loggerHelper.logInfo(context, submissionId, "URL : {0}", new Object[]{url});

        loggerHelper.logInfo(context, submissionId, "Extracted blob path from URL: {0}", new Object[]{message});

        loggerHelper.logInfo(context, submissionId, "Parsed tokens: {0}", tokens);

        if (tokens.size() < 4) {
            loggerHelper.logInfo(context, submissionId, "Received invalid number of tokens. {0}", tokens);
            return;
        }

        requireNonNull(folderName, "dlrm_folder_name env var not configured.");

        if (!folderName.trim().equalsIgnoreCase(tokens.get(0))) {
            loggerHelper.logInfo(context, submissionId, "Received invalid dlrm folder name : {0}", tokens.get(0));
            return;
        }

        loggerHelper.logInfo(context, submissionId, "Folder name validated: {0}", tokens.get(0));

        requireNonNull(batchName, "dlrm_batch_name env var not configured.");

        final List<String> batchNames = Arrays.stream(batchName.split(","))
                .map(s -> s.trim().toLowerCase())
                .toList();

        boolean validBatchName = validateBatchNames(batchNames, tokens.get(1).toLowerCase());

        if (!validBatchName) {
            loggerHelper.logInfo(context, submissionId, "Received invalid dlrm batch name : {0}", tokens.get(1));
            return;
        }

        loggerHelper.logInfo(context, submissionId, "Batch name validated: {0}", tokens.get(1));

        final String queueMessage = "%s/%s/%s/%s".formatted(tokens.get(0), tokens.get(1), tokens.get(2), tokens.get(3));

        loggerHelper.logInfo(context, submissionId, "Sending message to the queue : {0}", queueMessage);

        Response<SendMessageResult> response = storageCloudClient.sendMessageToTheQueue(submissionId, queueMessage);

        loggerHelper.logInfo(context, submissionId, "Message %s expires at %s".formatted(response.getValue().getMessageId(), response.getValue().getExpirationTime()));

        loggerHelper.logInfo(context, submissionId, "The message sent successfully to the queue : {0}", queueMessage);

        loggerHelper.logInfo(context, submissionId, "EventGridTriggerJava processing complete.");
    }

    private boolean validateBatchNames(final List<String> batchNames, String token) {

        if (batchNames.get(0).equalsIgnoreCase("*")) {
            return true;
        }

        return batchNames.contains(token);
    }

    private void setStorageCloudClient(final ExecutionContext context) {
        if(isNull(storageCloudClient)) {
            storageCloudClient = new StorageCloudClient(context, getenv("AzureWebJobsStorage"), getenv("dlrm_queue"), getenv("dlrm_container"), getenv("dlrm_log_queue"));
        }

        if (isNull(folderName)) {
            folderName = getenv("dlrm_folder_name");
        }

        if (isNull(batchName)) {
            batchName = getenv("dlrm_batch_name");
        }
    }

    private void setLoggerHelper() {
        if (isNull(loggerHelper)) {
            loggerHelper = new LoggerHelper();
        }
    }
}