package uk.gov.moj.cpp.stagingdlrm.azure;

import static java.lang.System.getenv;
import static java.util.Objects.isNull;
import static java.util.logging.Level.INFO;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;

import uk.gov.moj.cpp.stagingdlrm.azure.event.EventGridEvent;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.BlobCloudStorage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

/**
 * Azure Functions with Event Grid trigger.
 */
public class EventGridMonitor {

    private BlobCloudStorage blobCloudStorage;

    private static final String CASE_URN = "caseUrn";
    private static final String DESCRIPTION = "description";
    private ExecutionContext context;

    /**
     * This function will be invoked when an event is received from Event Grid.
     */
    @FunctionName("EventGridMonitor")
    public void run(@EventGridTrigger(name = "eventGridEvent") EventGridEvent eventGridEvent, final ExecutionContext context) {

        this.context = context;
        context.getLogger().info("Event content: ");
        final Map<String, Object> event = eventGridEvent.getData();
        context.getLogger().log(INFO, "CaseUrn: {0}", event.get(CASE_URN));
        context.getLogger().log(INFO, "Success: {0}", event.get("success"));
        context.getLogger().log(INFO, "Case Id: {0}", event.get("caseId"));
        context.getLogger().log(INFO, "Submission Id: {0}", event.get("submissionId"));
        context.getLogger().log(INFO, "Description: {0}", event.get(DESCRIPTION));
        context.getLogger().log(INFO, "AzureLocation: {0}", event.get("azureLocation"));

        final String azureLocation = (String) event.get("azureLocation");

        final List<String> splitStr = getSplitStr(azureLocation);
        context.getLogger().log(INFO, "Split azure location tokens: {0}", splitStr);

        final String submissionId = extractSubmissionId(splitStr);
        context.getLogger().log(INFO, "Extracted submissionId: {0}", submissionId);

        final String migrationSourceSystemName = extractMigrationSourceSystemName(splitStr, azureLocation);
        context.getLogger().log(INFO, "Extracted migrationSourceSystemName: {0}", migrationSourceSystemName);

        final String outcomeFile = "outcome/outcome-%s.json".formatted(submissionId);
        context.getLogger().log(INFO, "Writing {0}", outcomeFile);
        processEvent(event, migrationSourceSystemName, outcomeFile);

        context.getLogger().log(INFO, "Writing outcome.json to azureLocation: {0}", azureLocation);
        processEvent(event, azureLocation, "outcome.json");

        context.getLogger().info("EventGridMonitor processing complete.");
    }

    private String extractSubmissionId(final List<String> splitStr) {
        return isNotEmpty(splitStr) && splitStr.size() == 4 ? splitStr.get(splitStr.size() - 1) : UUID.randomUUID().toString();
    }

    private  List<String> getSplitStr(final String queueMessage) {
        return Arrays.stream(queueMessage.split("/")).toList();
    }

    private String extractMigrationSourceSystemName(final List<String> splitStr, final String azureLocation) {
        return isNotEmpty(splitStr) && splitStr.size() == 4 ? splitStr.get(0) : azureLocation;
    }

    private void processEvent(final Map<String, Object> event, final String azureLocation, final String fileName) {

        final Path path = Path.of(azureLocation + File.separator + fileName);

        final byte[] bytes = generateOutcomeContent(event);

        setCaseStorageActiveBlobContainer();

        context.getLogger().log(INFO, "Uploading outcome to: {0}", path);
        blobCloudStorage.uploadToStorage(new ByteArrayInputStream(bytes), (long) bytes.length, path.toString());
        context.getLogger().log(INFO, "Successfully uploaded outcome to: {0}", path);
    }

    private byte[] generateOutcomeContent(final Map<String, Object> event) {
        String outcome = """
                {
                    "caseUrn": "%s",
                    "success": %s,
                    "description": "%s"
                }
                """;
        return outcome.formatted(event.get(CASE_URN), event.get("success"), event.get(DESCRIPTION))
                .getBytes();
    }

    private void setCaseStorageActiveBlobContainer() {
        if (isNull(blobCloudStorage)) {
            blobCloudStorage = new BlobCloudStorage(context, getenv("AzureWebJobsStorage"), getenv("dlrm_container"));
        }

    }
}
