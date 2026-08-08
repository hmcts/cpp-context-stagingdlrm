package uk.gov.moj.cpp.stagingdlrm.azure;

import static java.lang.System.getenv;
import static java.util.Objects.isNull;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;

import uk.gov.justice.services.messaging.JsonObjects;
import uk.gov.moj.cpp.stagingdlrm.azure.event.QueueMessage;
import uk.gov.moj.cpp.stagingdlrm.azure.event.SubmissionPathTokens;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.EventGridMonitorHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.LoggerHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.StagingDlrmCommandHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.StorageCloudClient;
import uk.gov.moj.cpp.stagingdlrm.azure.validator.JsonSchemaValidator;
import uk.gov.moj.cpp.stagingdlrm.azure.validator.SourceSystemValidators;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    // DD-43086 FR4 — table-driven, keyed on the lower-cased source-system token. Adding a third
    // source system is one more entry in setSourceSystemValidators(), not a code branch.
    private static final String XHIBIT = "xhibit";

    private static final String LIBRA = "libra";

    private static final String CASE_URN = "caseUrn";

    private static final String DESCRIPTION = "description";

    private static final int DEFAULT_RETRY = 3;

    private Boolean caseProcessingEnabled;

    private StorageCloudClient storageCloudClient;

    private StagingDlrmCommandHelper stagingDlrmCommandHelper;

    private ExecutionContext context;

    private String stagingDlrmMigratedCaseSubmissionContentType;

    private String stagingDlrmErrorMigratedCaseSubmissionContentType;

    private String stagingDlrmUserId;

    private String stagingDlrmBaseUri;

    private Map<String, SourceSystemValidators> sourceSystemValidators;

    private EventGridMonitorHelper eventGridMonitorHelper;

    private LoggerHelper loggerHelper;

    private long retryCount;

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

        setTimerTriggerJavaProperties();

        loggerHelper.logInfo(context, "Timer function executed at : {0}", LocalDateTime.now());

        if (!caseProcessingEnabled) {
            loggerHelper.logInfo(context,"The case migration is not enabled.");
            return;
        }

        final Map<String, QueueMessage> queueMessageWithListOfBlobNamesMap = storageCloudClient.receiveMessages();

        loggerHelper.logInfo(context, "Number of queue messages received: {0}", queueMessageWithListOfBlobNamesMap.size());

        queueMessageWithListOfBlobNamesMap.forEach((message, queueMessage) -> processQueueMessage(queueMessage));

        loggerHelper.logInfo(context,"TimerTriggerJava processing complete.");
    }

    private void processQueueMessage(final QueueMessage message) {

        final long deliveryCount = message.deliveryCount();

        final String queueMessage = message.queueName();

        final String submissionId = extractSubmissionId(queueMessage);
        loggerHelper.logInfo(context, submissionId, "Extracted submissionId: {0}", submissionId);

        if (deliveryCount == 1) {
            loggerHelper.logInfo(context, submissionId, "Processing queue message: {0}", new Object[] {queueMessage});
        } else {
            loggerHelper.logInfo(context, submissionId, "Retrying for {0} times queue message: {1}", new Object[] {deliveryCount, queueMessage});
        }

        final List<String> metafiles = getMetaFile(message.listOfBlobNames());

        if (!(metafiles.contains(queueMessage +"/"+"case.json") && metafiles.contains(queueMessage + "/"+"manifest.json"))) {
            loggerHelper.logInfo(context, submissionId, "Case or Manifest file does not exist.");
            storageCloudClient.deleteQueueMessage(queueMessage);
            storageCloudClient.sendMessageToTheLogQueue(queueMessage);
            return;
        }

        // DD-43086 FR7 — the same shared helper EventGridTriggerJava's folder-name gate calls, so
        // this lookup provably keys on the value that gate already checked.
        final String sourceSystem = SubmissionPathTokens.sourceSystem(queueMessage);

        final SourceSystemValidators validators = sourceSystemValidators.get(sourceSystem);

        if (isNull(validators)) {
            loggerHelper.logSevere(context, submissionId, "No schema configured for source system: {0}", sourceSystem);
            storageCloudClient.deleteQueueMessage(queueMessage);
            storageCloudClient.sendMessageToTheLogQueue(queueMessage);
            return;
        }

        final List<String> materialFiles = getMaterialFiles(message.listOfBlobNames());
        loggerHelper.logInfo(context, submissionId, "Number of material files found: {0}", materialFiles.size());

        final String caseJsonContent = getJsonContent(submissionId, queueMessage +"/"+"case.json");

        final String manifestJsonContent = getJsonContent(submissionId, queueMessage +"/"+"manifest.json");

        final Set<ValidationMessage> caseValidationMessages = validators.caseValidator().validate(submissionId, caseJsonContent);

        final Set<ValidationMessage> manifestValidationMessages = validators.manifestValidator().validate(submissionId, manifestJsonContent);

        final List<String> baseUriArray = Arrays.stream(stagingDlrmBaseUri.split(",")).toList();

        if (caseValidationMessages.isEmpty() && manifestValidationMessages.isEmpty()) {

            final JsonObject caseJsonInput = getJsonObject(caseJsonContent);

            final JsonObject manifestJsonInput = getJsonObject(manifestJsonContent);

            final JsonObject migratedCaseJsonObject = caseJsonInput.getJsonObject("migratedCase");

            final JsonObject caseDetailsJsonObject = migratedCaseJsonObject.getJsonObject("caseDetails");

            final String caseUrn = caseDetailsJsonObject.getString("prosecutorCaseReference");
            loggerHelper.logInfo(context, submissionId, "Case URN: {0}", caseUrn);

            final JsonObject migratedCaseSubmissionJsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                    caseJsonInput, materialFiles, manifestJsonInput, submissionId, message.queueName());

            baseUriArray.forEach(baseUri ->
                    processBaseUriArray(message, baseUri, migratedCaseSubmissionJsonObject, materialFiles, submissionId, caseUrn));

        } else {

            loggerHelper.logInfo(context, submissionId, "Case validation messages: {0}", caseValidationMessages.size());

            loggerHelper.logInfo(context, submissionId, "Manifest validation messages: {0}", manifestValidationMessages.size());

            if (!caseValidationMessages.isEmpty()) {
                processClientError(message, caseValidationMessages, baseUriArray, caseJsonContent, submissionId);
            } else {
                processClientError(message, manifestValidationMessages, baseUriArray, manifestJsonContent, submissionId);
            }
        }
    }

    private void processBaseUriArray(final QueueMessage message, final String baseUri, final JsonObject migratedCaseSubmissionJsonObject, final List<String> materialFiles, final String submissionId, final String caseUrn) {
        final int numberOfMaterials = migratedCaseSubmissionJsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        loggerHelper.logInfo(context, submissionId, "Material files found: {0}, expected: {1}", new Object[] {materialFiles.size(), numberOfMaterials});

        if (materialFiles.size() == numberOfMaterials) {

            final String migratedCaseSubmissionUrl = getMigratedCaseSubmissionUrl(baseUri);
            loggerHelper.logInfo(context, submissionId, "Sending migrated case submission to: {0}", migratedCaseSubmissionUrl);

            try (final Response response = stagingDlrmCommandHelper.sendPostCommandApi(
                    migratedCaseSubmissionUrl,
                    migratedCaseSubmissionJsonObject,
                    stagingDlrmMigratedCaseSubmissionContentType,
                    stagingDlrmUserId,
                    submissionId)) {

                loggerHelper.logInfo(context, submissionId,"HTTP Status : {0}", response.getStatus());

                final String responseString = response.readEntity(String.class);

                loggerHelper.logInfo(context, submissionId, "Response : {0}", responseString);

                switch (response.getStatusInfo().getFamily()) {
                    case SUCCESSFUL -> processSuccessfulMessage(submissionId, message);
                    case SERVER_ERROR -> processServerError(message, caseUrn, "HTTP Status : " + response.getStatus() + " Response : " + responseString);
                    case CLIENT_ERROR -> processClientError(message, baseUri, responseString, migratedCaseSubmissionJsonObject, submissionId, caseUrn);
                    default -> loggerHelper.logInfo(context, submissionId,"Received error while calling : {0}",  migratedCaseSubmissionUrl);
                }
            }
        } else {
            final String errorMessage = "Mismatch material files found.";
            loggerHelper.logInfo(context, submissionId, errorMessage);
            processClientError(message, baseUri, errorMessage, migratedCaseSubmissionJsonObject, submissionId, caseUrn);
        }
    }

    private void processServerError(final QueueMessage message, final String caseUrn, final String responseString) {
        if(message.deliveryCount() > (retryCount + 1)) {
            setEventGridMonitorHelper();
            writeOutcome(message.queueName(), caseUrn, responseString);
            storageCloudClient.deleteQueueMessage(message.queueName());
            storageCloudClient.sendMessageToTheLogQueue(message.queueName());
        }
    }

    private void processClientError(final QueueMessage message, final Set<ValidationMessage> validationMessages, final List<String> baseUriArray, final String jsonContent, final String submissionId) {
        final Set<String> manifestValidationMessage = validationMessages.stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());

        final String errorMessage = String.join(", ", manifestValidationMessage);

        loggerHelper.logInfo(context, submissionId, "Validation error messages: "+ errorMessage);

        processClientError(message, baseUriArray, errorMessage, jsonContent, submissionId);
    }

    private String getJsonContent(final String submissionId, final String queueMessage) {
        return storageCloudClient.downloadBlobContents(submissionId, queueMessage);
    }

    private JsonObject getJsonObject(final String payload) {
        try(final JsonReader caseReader = JsonObjects.createReader(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))) {
            return caseReader.readObject();
        }
    }

    private void processClientError(final QueueMessage message, final List<String> baseUriArray, final String errorMessage, final String jsonContent, final String submissionId) {
        baseUriArray.forEach(baseUri -> {

            final String caseUrn = "";

            loggerHelper.logInfo(context, submissionId, "Recording message as error in the event log with stream id : "+ submissionId);

            final JsonObject errorMigratedCaseSubmissionJsonObject = stagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload(
                    jsonContent, submissionId, caseUrn, message.queueName(), errorMessage);

            generateErrorMigratedCaseSubmissionPayload(message, baseUri, caseUrn, errorMigratedCaseSubmissionJsonObject, submissionId);
        });
    }

    private void processClientError(final QueueMessage message, final String baseUri, final String responseString, final JsonObject migratedCaseSubmissionJsonObject, final String submissionId, final String caseUrn) {
        generateErrorMigratedCaseSubmissionPayload(message, baseUri,
                migratedCaseSubmissionJsonObject, submissionId, caseUrn, message.queueName(), responseString);
    }

    private void processSuccessfulMessage(final String submissionId, final QueueMessage message) {
        loggerHelper.logInfo(context, submissionId, "After successful processing of the message, deleting the message from queue : {0}", message.queueName());

        storageCloudClient.deleteQueueMessage(message.queueName());
    }

    private void writeOutcome(final String azureLocation, final String caseUrn, final String description) {

        final List<String> splitStr = getSplitStr(azureLocation);

        final String submissionId = extractSubmissionId(splitStr);
        loggerHelper.logInfo(context, submissionId, "Extracted submissionId: {0}", submissionId);

        final String migrationSourceSystemName = extractMigrationSourceSystemName(splitStr, azureLocation);
        loggerHelper.logInfo(context, submissionId, "Extracted migrationSourceSystemName: {0}", migrationSourceSystemName);

        final Map<String, Object> event = Map.of(
                "submissionId", submissionId,
                "migrationSourceSystemName", migrationSourceSystemName,
                "azureLocation", azureLocation,
                DESCRIPTION, description,
                CASE_URN, caseUrn,
                "success", "false");

        final String outcomeFile = "outcome/outcome-%s.json".formatted(submissionId);

        loggerHelper.logInfo(context, submissionId, "Writing {0}", outcomeFile);

        eventGridMonitorHelper.processEvent(event, migrationSourceSystemName, outcomeFile);

        loggerHelper.logInfo(context, submissionId, "Writing outcome.json to azureLocation: {0}", azureLocation);

        eventGridMonitorHelper.processEvent(event, azureLocation, "outcome.json");
    }

    private void generateErrorMigratedCaseSubmissionPayload(final QueueMessage message, final String baseUri, final JsonObject migratedCaseSubmissionJsonObject, final String submissionId, final String caseUrn, final String azureLocation, final String responseString) {

        loggerHelper.logInfo(context, submissionId, "Recording message as error in the event log with stream id : {0}", submissionId);

        final JsonObject errorMigratedCaseSubmissionJsonObject = stagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload(
                migratedCaseSubmissionJsonObject, submissionId, caseUrn, azureLocation, responseString);

        generateErrorMigratedCaseSubmissionPayload(message, baseUri, caseUrn, errorMigratedCaseSubmissionJsonObject, submissionId);
    }

    private void generateErrorMigratedCaseSubmissionPayload(final QueueMessage message, final String baseUri, final String caseUrn, final JsonObject errorMigratedCaseSubmissionJsonObject, final String submissionId) {
        final String errorMigratedCaseSubmissionUrl = getErrorMigratedCaseSubmissionUrl(baseUri);
        try (final Response errorMigratedCaseSubmissionResponse = stagingDlrmCommandHelper.sendPostCommandApi(
                errorMigratedCaseSubmissionUrl,
                errorMigratedCaseSubmissionJsonObject,
                stagingDlrmErrorMigratedCaseSubmissionContentType,
                stagingDlrmUserId,
                submissionId)) {

            loggerHelper.logInfo(context, submissionId, "HTTP Status : {0}", errorMigratedCaseSubmissionResponse.getStatus());
            final String errorResponseString = errorMigratedCaseSubmissionResponse.readEntity(String.class);
            switch (errorMigratedCaseSubmissionResponse.getStatusInfo().getFamily()) {
                case SUCCESSFUL -> {
                    loggerHelper.logInfo(context, submissionId, "Error submission accepted. Deleting message from queue: {0}", message.queueName());
                    storageCloudClient.deleteQueueMessage(message.queueName());
                }
                case SERVER_ERROR -> processServerError(message, caseUrn, "HTTP Status : " + errorMigratedCaseSubmissionResponse.getStatus() + " Response : " + errorResponseString);
                case CLIENT_ERROR -> processClientError(message, caseUrn, errorResponseString);
                default -> loggerHelper.logInfo(context, submissionId, "Received error while calling : {0}", errorMigratedCaseSubmissionUrl);
            }
        }
    }

    private void processClientError(final QueueMessage message, final String caseUrn, final String responseString) {
        setEventGridMonitorHelper();
        writeOutcome(message.queueName(), caseUrn, responseString);
        storageCloudClient.deleteQueueMessage(message.queueName());
        storageCloudClient.sendMessageToTheLogQueue(message.queueName());
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

    private void setLoggerHelper() {
        if (isNull(loggerHelper)) {
            this.loggerHelper = new LoggerHelper();
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
            storageCloudClient = new StorageCloudClient(context, getenv("AzureWebJobsStorage"), getenv("dlrm_queue"), getenv("dlrm_container"), getenv("dlrm_log_queue"));
        }
    }

    /**
     * DD-43086 FR4 — resolved once, cached, and keyed on the lower-cased source-system token
     * (FR1/FR7: the same token EventGridTriggerJava already gated on). The manifest validator
     * instance is shared across every entry (FR5) — only the case validator differs.
     */
    private void setSourceSystemValidators() {
        if (isNull(this.sourceSystemValidators)) {
            final JsonSchemaValidator manifestValidator = new JsonSchemaValidator(context, "stagingdlrm.manifest.json");

            this.sourceSystemValidators = Map.of(
                    XHIBIT, new SourceSystemValidators(
                            new JsonSchemaValidator(context, "stagingdlrm.case-submission.json"), manifestValidator),
                    LIBRA, new SourceSystemValidators(
                            new JsonSchemaValidator(context, "libra.case-submission.json"), manifestValidator));
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
        setSourceSystemValidators();
        setRetryCount();
        setLoggerHelper();
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

    private void setEventGridMonitorHelper() {
        if (isNull(eventGridMonitorHelper)) {
            eventGridMonitorHelper = new EventGridMonitorHelper(context, getenv("AzureWebJobsStorage"), getenv("dlrm_container"));
        }
    }

    private void setRetryCount() {
        if (retryCount == 0) {
            String retryCountEnv = getenv("retry_count");
            retryCount = isNull(retryCountEnv) ? DEFAULT_RETRY : Integer.parseInt(retryCountEnv);
        }
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
}
