package uk.gov.moj.cpp.stagingdlrm.azure;

import static java.util.Objects.isNull;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;

import uk.gov.moj.cpp.stagingdlrm.azure.event.EventGridEvent;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.EventGridMonitorHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.LoggerHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

import static java.lang.System.getenv;

/**
 * Azure Functions with Event Grid trigger.
 */
public class EventGridMonitor {

    private static final String CASE_URN = "caseUrn";
    private static final String DESCRIPTION = "description";
    private ExecutionContext context;

    private EventGridMonitorHelper helper;
    private LoggerHelper loggerHelper;

    /**
     * This function will be invoked when an event is received from Event Grid.
     */
    @FunctionName("EventGridMonitor")
    public void run(@EventGridTrigger(name = "eventGridEvent") EventGridEvent eventGridEvent, final ExecutionContext context) {

        this.context = context;
        setLoggerHelper();

        final Map<String, Object> event = eventGridEvent.getData();
        final String azureLocation = (String) event.get("azureLocation");
        final List<String> splitStr = getSplitStr(azureLocation);
        final String submissionId = extractSubmissionId(splitStr);
        final String migrationSourceSystemName = extractMigrationSourceSystemName(splitStr, azureLocation);

        loggerHelper.logInfo(context, submissionId, "Event content: ");
        loggerHelper.logInfo(context, submissionId, "CaseUrn: {0}", event.get(CASE_URN));
        loggerHelper.logInfo(context, submissionId, "Success: {0}", event.get("success"));
        loggerHelper.logInfo(context, submissionId, "Case Id: {0}", event.get("caseId"));
        loggerHelper.logInfo(context, submissionId, "Submission Id: {0}", event.get("submissionId"));
        loggerHelper.logInfo(context, submissionId, "Description: {0}", event.get(DESCRIPTION));
        loggerHelper.logInfo(context, submissionId, "AzureLocation: {0}", azureLocation);
        loggerHelper.logInfo(context, submissionId, "Split azure location tokens: {0}", splitStr);
        loggerHelper.logInfo(context, submissionId, "Extracted migrationSourceSystemName: {0}", migrationSourceSystemName);

        setEventGridMonitorHelper();

        final String outcomeFile = "outcome/outcome-%s.json".formatted(submissionId);

        loggerHelper.logInfo(context, submissionId, "Writing {0}", outcomeFile);

        helper.processEvent(event, migrationSourceSystemName, outcomeFile);

        loggerHelper.logInfo(context, submissionId, "Writing outcome.json to azureLocation: {0}", azureLocation);
        helper.processEvent(event, azureLocation, "outcome.json");

        loggerHelper.logInfo(context, submissionId, "EventGridMonitor processing complete.");
    }

    private String extractSubmissionId(final List<String> splitStr) {
        return isNotEmpty(splitStr) && splitStr.size() == 4 ? splitStr.get(splitStr.size() - 1) : UUID.randomUUID().toString();
    }

    private List<String> getSplitStr(final String queueMessage) {
        return Arrays.stream(queueMessage.split("/")).toList();
    }

    private String extractMigrationSourceSystemName(final List<String> splitStr, final String azureLocation) {
        return isNotEmpty(splitStr) && splitStr.size() == 4 ? splitStr.get(0) : azureLocation;
    }

    private void setEventGridMonitorHelper() {
        if (isNull(helper)) {
            helper = new EventGridMonitorHelper(context, getenv("AzureWebJobsStorage"), getenv("dlrm_container"));
        }
    }

    private void setLoggerHelper() {
        if (isNull(loggerHelper)) {
            loggerHelper = new LoggerHelper();
        }
    }
}