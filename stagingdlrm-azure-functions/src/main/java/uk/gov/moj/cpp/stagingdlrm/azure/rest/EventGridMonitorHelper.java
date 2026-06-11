package uk.gov.moj.cpp.stagingdlrm.azure.rest;

import static java.util.Objects.isNull;

import uk.gov.moj.cpp.stagingdlrm.azure.storage.BlobCloudStorage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import com.microsoft.azure.functions.ExecutionContext;

public class EventGridMonitorHelper {

    private static final String CASE_URN = "caseUrn";

    private static final String DESCRIPTION = "description";

    private final ExecutionContext context;

    private BlobCloudStorage blobCloudStorage;

    private final String connectionString;
    private final String containerReference;
    private final LoggerHelper loggerHelper;

    public EventGridMonitorHelper(ExecutionContext context, final String connectionString, final String containerReference) {
        this.context = context;
        this.connectionString = connectionString;
        this.containerReference = containerReference;
        this.loggerHelper = new LoggerHelper();
    }

    public void processEvent(final Map<String, Object> event, final String azureLocation, final String fileName) {

        final String submissionId = (String) event.get("submissionId");

        final Path path = Path.of(azureLocation + File.separator + fileName);

        final byte[] bytes = generateOutcomeContent(event);

        setCaseStorageActiveBlobContainer();

        loggerHelper.logInfo(context, submissionId, "Uploading outcome to: {0}", path);

        blobCloudStorage.uploadToStorage(new ByteArrayInputStream(bytes), (long) bytes.length, path.toString());

        loggerHelper.logInfo(context, submissionId, "Successfully uploaded outcome to: {0}", path);
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
            blobCloudStorage = new BlobCloudStorage(context, connectionString, containerReference);
        }
    }
}