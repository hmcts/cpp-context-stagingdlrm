package uk.gov.moj.cpp.stagingdlrm.azure;

import static java.lang.System.getenv;
import static java.util.Objects.isNull;
import static javax.ws.rs.core.Response.Status.Family.SUCCESSFUL;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;

import uk.gov.moj.cpp.stagingdlrm.azure.rest.StagingDlrmCommandHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.StorageCloudClient;
import uk.gov.moj.cpp.stagingdlrm.azure.validator.JsonSchemaValidator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.ws.rs.core.Response;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.TimerTrigger;
import com.networknt.schema.ValidationMessage;

/**
 * Azure Functions with Timer trigger.
 */
public class TimerTriggerJava {

    public static final String MIGRATED_CASE_SUBMISSION_PATH = "/stagingdlrm-command-api/command/api/rest/stagingdlrm/receive-migrated-case-submission";

    public static final String ERROR_MIGRATED_CASE_SUBMISSION_PATH = "/stagingdlrm-command-api/command/api/rest/stagingdlrm/receive-error-migrated-case-submission";

    private Boolean caseProcessingEnabled;

    private StorageCloudClient storageCloudClient;

    private StagingDlrmCommandHelper stagingDlrmCommandHelper;

    private ExecutionContext context;

    private String stagingDlrmMigratedCaseSubmissionContentType;

    private String stagingDlrmErrorMigratedCaseSubmissionContentType;

    private String stagingDlrmUserId;

    private String stagingDlrmBaseUri;

    private JsonSchemaValidator caseJsonSchemaValidator;

    private JsonSchemaValidator manifestJsonSchemaValidator;

    /**
     * This method gets triggered by a timer and retrieves messages from queue along with the content of blobs.
     *
     * @param timerInfo - String
     * @param context   - ExecutionContext
     */
    @FunctionName("TimerTriggerJava")
    public void run(
            @TimerTrigger(name = "timerInfo", schedule = "%TimerTriggerSchedule%") String timerInfo,
            final ExecutionContext context) {

        this.context = context;

        context.getLogger().log(Level.INFO, "Timer function executed at : {0}", LocalDateTime.now());

        setTimerTriggerJavaProperties();

        if (!caseProcessingEnabled) {
            context.getLogger().warning("The case migration is not enabled.");
            return;
        }

        final Map<String, List<String>> queueMessageWithListOfBlobNamesMap = storageCloudClient.receiveMessages();

        context.getLogger().log(Level.INFO, "Number of queue messages received: {0}", queueMessageWithListOfBlobNamesMap.size());

        queueMessageWithListOfBlobNamesMap.forEach(this::processQueueMessage);
    }

    private void processQueueMessage(final String queueMessage, final List<String> listOfBlobNames) {

        context.getLogger().log(Level.INFO, "Processing queue message: {0}", queueMessage);

        final List<String> metafiles = getMetaFile(listOfBlobNames);

        if (!(metafiles.contains(queueMessage +"/"+"case.json") && metafiles.contains(queueMessage + "/"+"manifest.json"))) {
            context.getLogger().info("Case or Manifest file does not exist.");
            return;
        }

        final String submissionId = extractSubmissionId(queueMessage);
        context.getLogger().log(Level.INFO, "Extracted submissionId: {0}", submissionId);

        final List<String> materialFiles = getMaterialFiles(listOfBlobNames);
        context.getLogger().log(Level.INFO, "Number of material files found: {0}", materialFiles.size());

        final String caseJsonContent = getJsonContent(queueMessage +"/"+"case.json");

        final String manifestJsonContent = getJsonContent(queueMessage +"/"+"manifest.json");

        final Set<ValidationMessage> caseValidationMessages = caseJsonSchemaValidator.validate(caseJsonContent);

        final Set<ValidationMessage> manifestValidationMessages = manifestJsonSchemaValidator.validate(manifestJsonContent);

        context.getLogger().log(Level.INFO, "Case validation messages: {0}", caseValidationMessages.size());
        context.getLogger().log(Level.INFO, "Manifest validation messages: {0}", manifestValidationMessages.size());

        final List<String> baseUriArray = Arrays.stream(stagingDlrmBaseUri.split(",")).toList();

        if (caseValidationMessages.isEmpty() && manifestValidationMessages.isEmpty()) {

            final JsonObject caseJsonInput = getJsonObject(caseJsonContent);

            final JsonObject manifestJsonInput = getJsonObject(manifestJsonContent);

            final JsonObject migratedCaseJsonObject = caseJsonInput.getJsonObject("migratedCase");

            final JsonObject caseDetailsJsonObject = migratedCaseJsonObject.getJsonObject("caseDetails");

            final String caseUrn = caseDetailsJsonObject.getString("prosecutorCaseReference");
            context.getLogger().log(Level.INFO, "Case URN: {0}", caseUrn);

            final JsonObject migratedCaseSubmissionJsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                    caseJsonInput, materialFiles, manifestJsonInput, submissionId, queueMessage);

            baseUriArray.forEach(baseUri ->
                    processBaseUriArray(queueMessage, baseUri, migratedCaseSubmissionJsonObject, materialFiles, submissionId, caseUrn));

        } else {

            if (!caseValidationMessages.isEmpty()) {
                processClientError(queueMessage, caseValidationMessages, baseUriArray, caseJsonContent, submissionId);
            } else {
                processClientError(queueMessage, manifestValidationMessages, baseUriArray, manifestJsonContent, submissionId);
            }
        }
    }

    private void processBaseUriArray(final String queueMessage, final String baseUri, final JsonObject migratedCaseSubmissionJsonObject, final List<String> materialFiles, final String submissionId, final String caseUrn) {
        final int numberOfMaterials = migratedCaseSubmissionJsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        context.getLogger().log(Level.INFO, "Material files found: {0}, expected: {1}", new Object[]{materialFiles.size(), numberOfMaterials});

        if (materialFiles.size() == numberOfMaterials) {

            final String migratedCaseSubmissionUrl = getMigratedCaseSubmissionUrl(baseUri);
            context.getLogger().log(Level.INFO, "Sending migrated case submission to: {0}", migratedCaseSubmissionUrl);

            try (final Response response = stagingDlrmCommandHelper.sendPostCommandApi(
                    migratedCaseSubmissionUrl,
                    migratedCaseSubmissionJsonObject,
                    stagingDlrmMigratedCaseSubmissionContentType,
                    stagingDlrmUserId)) {

                context.getLogger().info("HTTP Status : " + response.getStatus());

                final String responseString = response.readEntity(String.class);

                context.getLogger().log(Level.INFO, "Response : {0}", responseString);

                switch (response.getStatusInfo().getFamily()) {
                    case SUCCESSFUL -> processSuccessfulMessage(queueMessage);
                    case CLIENT_ERROR ->
                            processClientError(queueMessage, baseUri, responseString, migratedCaseSubmissionJsonObject, submissionId, caseUrn);
                    default ->
                            context.getLogger().info("Received error while calling : " + migratedCaseSubmissionUrl);
                }
            }
        } else {
            final String errorMessage = "Mismatch material files found.";
            context.getLogger().info(errorMessage);
            processClientError(queueMessage, baseUri, errorMessage, migratedCaseSubmissionJsonObject, submissionId, caseUrn);
        }
    }

    private void processClientError(final String queueMessage, final Set<ValidationMessage> validationMessages, final List<String> baseUriArray, final String jsonContent, final String submissionId) {
        final Set<String> manifestValidationMessage = validationMessages.stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());

        final String errorMessage = String.join(", ", manifestValidationMessage);

        context.getLogger().info(errorMessage);

        processClientError(queueMessage, baseUriArray, errorMessage, jsonContent, submissionId);
    }

    private String getJsonContent(final String queueMessage) {
        return storageCloudClient.downloadBlobContents(queueMessage);
    }

    private JsonObject getJsonObject(final String payload) {
        try(final JsonReader caseReader = Json.createReader(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))) {
            return caseReader.readObject();
        }
    }

    private void processClientError(final String queueMessage, final List<String> baseUriArray, final String errorMessage, final String jsonContent, final String submissionId) {
        baseUriArray.forEach(baseUri -> {

            context.getLogger().log(Level.INFO, "Recording message as error in the event log with stream id : {0}", submissionId);

            final JsonObject errorMigratedCaseSubmissionJsonObject = stagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload(
                    jsonContent, submissionId, "", queueMessage, errorMessage);

            generateErrorMigratedCaseSubmissionPayload(queueMessage, baseUri, errorMigratedCaseSubmissionJsonObject);
        });
    }

    private void processClientError(final String queueMessage, final String baseUri, final String responseString, final JsonObject migratedCaseSubmissionJsonObject, final String submissionId, final String caseUrn) {
        generateErrorMigratedCaseSubmissionPayload(queueMessage, baseUri,
                migratedCaseSubmissionJsonObject, submissionId, caseUrn, queueMessage, responseString);
    }

    private void processSuccessfulMessage(final String queueMessage) {
        context.getLogger().log(Level.INFO, "After successful processing of the message, deleting the message from queue : {0}", queueMessage);

        storageCloudClient.deleteQueueMessage(queueMessage);
    }

    private void generateErrorMigratedCaseSubmissionPayload(final String queueMessage, final String baseUri, final JsonObject migratedCaseSubmissionJsonObject, final String submissionId, final String caseUrn, final String azureLocation, final String responseString) {

        context.getLogger().log(Level.INFO, "Recording message as error in the event log with stream id : {0}", submissionId);

        final JsonObject errorMigratedCaseSubmissionJsonObject = stagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload(
                migratedCaseSubmissionJsonObject, submissionId, caseUrn, azureLocation, responseString);

        generateErrorMigratedCaseSubmissionPayload(queueMessage, baseUri, errorMigratedCaseSubmissionJsonObject);
    }

    private void generateErrorMigratedCaseSubmissionPayload(final String queueMessage, final String baseUri, final JsonObject errorMigratedCaseSubmissionJsonObject) {
        final String errorMigratedCaseSubmissionUrl = getErrorMigratedCaseSubmissionUrl(baseUri);
        try (final Response errorMigratedCaseSubmissionResponse = stagingDlrmCommandHelper.sendPostCommandApi(
                errorMigratedCaseSubmissionUrl,
                errorMigratedCaseSubmissionJsonObject,
                stagingDlrmErrorMigratedCaseSubmissionContentType,
                stagingDlrmUserId)) {

            context.getLogger().info("HTTP Status : " + errorMigratedCaseSubmissionResponse.getStatus());

            if (errorMigratedCaseSubmissionResponse.getStatusInfo().getFamily() == SUCCESSFUL) {
                context.getLogger().log(Level.INFO, "Error submission accepted. Deleting message from queue: {0}", queueMessage);
                storageCloudClient.deleteQueueMessage(queueMessage);
            } else {

                final String errorResponseString = errorMigratedCaseSubmissionResponse.readEntity(String.class);

                context.getLogger().log(Level.INFO, "Response : {0}", errorResponseString);
            }
        }
    }

    private String extractSubmissionId(final String queueMessage) {
        final List<String> splitStr = Arrays.stream(queueMessage.split("/")).toList();
        return isNotEmpty(splitStr) && splitStr.size() == 4 ? splitStr.get(splitStr.size() - 1) : UUID.randomUUID().toString();
    }

    private void setCaseProcessingEnabled() {
        if(isNull(this.caseProcessingEnabled)) {
            this.caseProcessingEnabled = "true".equals(getenv("case_processing_enabled"));
        }
    }

    private void setStagingDlrmCommandHelper() {
        if (isNull(stagingDlrmCommandHelper)) {
            this.stagingDlrmCommandHelper = new StagingDlrmCommandHelper(context);
        }
    }

    private List<String> getMaterialFiles(final List<String> listOfBlobNames) {
        return listOfBlobNames.stream().filter(name -> !name.endsWith(".json")).toList();
    }

    private List<String> getMetaFile(final List<String> listOfBlobNames) {
        return listOfBlobNames.stream().filter(name -> name.endsWith(".json")).toList();
    }

    private void setStorageCloudClient() {
        if(isNull(this.storageCloudClient)) {
            storageCloudClient = new StorageCloudClient(context, getenv("AzureWebJobsStorage"), getenv("dlrm_queue"), getenv("dlrm_container"));
        }
    }

    private void setJsonSchemaValidator() {
        if(isNull(this.caseJsonSchemaValidator)) {
            final String jsonSchema = "stagingdlrm.case-submission.json";
            this.caseJsonSchemaValidator = new JsonSchemaValidator(context, jsonSchema);
        }

        if(isNull(this.manifestJsonSchemaValidator)) {
            final String jsonSchema = "stagingdlrm.manifest.json";
            this.manifestJsonSchemaValidator = new JsonSchemaValidator(context, jsonSchema);
        }
    }

    private void setTimerTriggerJavaProperties() {
        setCaseProcessingEnabled();
        setStorageCloudClient();
        setStagingDlrmCommandHelper();
        setStagingDlrmBaseUri();
        setStagingDlrmUserId();
        setStagingDlrmMigratedCaseSubmissionContentType();
        setStagingDlrmErrorMigratedCaseSubmissionContentType();
        setJsonSchemaValidator();
    }

    private void setStagingDlrmUserId() {
        if (isNull(stagingDlrmUserId)) {
            stagingDlrmUserId = getenv("staging_dlrm_uid");
        }
    }

    private String getMigratedCaseSubmissionUrl(String baseUri) {
        return baseUri + MIGRATED_CASE_SUBMISSION_PATH;
    }

    private void setStagingDlrmBaseUri() {
        if (isNull(stagingDlrmBaseUri)) {
            stagingDlrmBaseUri = getenv("staging_dlrm_base_uri");
        }
    }

    private String getErrorMigratedCaseSubmissionUrl(String baseUri) {
        return baseUri + ERROR_MIGRATED_CASE_SUBMISSION_PATH;
    }

    private void setStagingDlrmMigratedCaseSubmissionContentType() {
        if (isNull(stagingDlrmMigratedCaseSubmissionContentType)) {
            stagingDlrmMigratedCaseSubmissionContentType = getenv("staging_dlrm_content_type");
        }
    }

    private void setStagingDlrmErrorMigratedCaseSubmissionContentType() {
        if (isNull(stagingDlrmErrorMigratedCaseSubmissionContentType)) {
            stagingDlrmErrorMigratedCaseSubmissionContentType = getenv("staging_dlrm_error_content_type");
        }
    }
}
