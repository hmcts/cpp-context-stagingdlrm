package uk.gov.moj.cpp.stagingdlrm.azure;


import static uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;
import static uk.gov.moj.cpp.stagingdlrm.azure.TimerTriggerJava.ERROR_MIGRATED_CASE_SUBMISSION_PATH;
import static uk.gov.moj.cpp.stagingdlrm.azure.TimerTriggerJava.MIGRATED_CASE_SUBMISSION_PATH;

import uk.gov.moj.cpp.stagingdlrm.azure.event.QueueMessage;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.EventGridMonitorHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.StagingDlrmCommandHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.StorageCloudClient;
import uk.gov.moj.cpp.stagingdlrm.azure.validator.JsonSchemaValidator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.core.Response;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimerTriggerJavaTest {

    @Mock
    private StorageCloudClient storageCloudClient;

    @Mock
    private StagingDlrmCommandHelper stagingDlrmCommandHelper;

    @Mock
    private ExecutionContext context;

    @Mock
    private JsonSchemaValidator caseJsonSchemaValidator;

    @Mock
    private JsonSchemaValidator manifestJsonSchemaValidator;

    @Mock
    private EventGridMonitorHelper eventGridMonitorHelper;

    @InjectMocks
    private TimerTriggerJava timerTrigger;

    @Captor
    private ArgumentCaptor<String> stringArgumentCaptor;

    private final Logger logger = Logger.getLogger(TimerTriggerJava.class.getName());

    private static final String stagingDlrmUserId = UUID.randomUUID().toString();

    @BeforeEach
    public void setup() {
        setField(stagingDlrmCommandHelper, "context", context);
        setField(timerTrigger, "caseProcessingEnabled", true);
        setField(timerTrigger, "storageCloudClient", storageCloudClient);
        setField(timerTrigger, "stagingDlrmMigratedCaseSubmissionContentType", "application/vnd.stagingdlrm.receive-migrated-case-submission+json");
        setField(timerTrigger, "stagingDlrmErrorMigratedCaseSubmissionContentType", "application/vnd.stagingdlrm.receive-error-migrated-case-submission+json");
        setField(timerTrigger, "stagingDlrmUserId", stagingDlrmUserId);
        setField(timerTrigger, "stagingDlrmBaseUri", "http://localhost:8080");
        setField(timerTrigger, "eventGridMonitorHelper", eventGridMonitorHelper);
    }

    @Test
    void shouldTestTimerTriggerSuccessfully() {
        final String timerInfo = "timerInfo";
        final String queueMessage = "28DI10000175";
        final JsonObject caseJsonObject = getCaseJsonObject(queueMessage);
        final String caseJsonPayload = getCaseJsonPayload();

        final String manifestJsonPayload = getManifestJsonPayload();
        final JsonObject manifestJsonObject = getManifestJsonObject();

        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder()
                .add("metadata", createObjectBuilder().add("numberOfMaterials", 2))
                .build();

        final List<String> listBlobNames = List.of("28DI10000175/test.pdf", "28DI10000175/test1.pdf", "28DI10000175/case.json", "28DI10000175/manifest.json");
        final Map<String, QueueMessage> messageMap = new HashMap<>();
        messageMap.put(queueMessage, new QueueMessage(queueMessage, 1L, listBlobNames));

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);
        when(storageCloudClient.downloadBlobContents(anyString(), eq("28DI10000175/case.json"))).thenReturn(caseJsonPayload);
        when(caseJsonSchemaValidator.validate(anyString(), eq(caseJsonPayload))).thenReturn(Set.of());
        when(storageCloudClient.downloadBlobContents(anyString(), eq("28DI10000175/manifest.json"))).thenReturn(manifestJsonPayload);
        when(manifestJsonSchemaValidator.validate(anyString(), eq(manifestJsonPayload))).thenReturn(Set.of());
        when(stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                eq(caseJsonObject),
                eq(List.of("28DI10000175/test.pdf", "28DI10000175/test1.pdf")),
                eq(manifestJsonObject),
                stringArgumentCaptor.capture(),
                eq(queueMessage)))
                .thenReturn(migratedCaseSubmissionJsonObject);
        when(stagingDlrmCommandHelper.sendPostCommandApi(
                eq("http://localhost:8080" + MIGRATED_CASE_SUBMISSION_PATH),
                eq(migratedCaseSubmissionJsonObject),
                eq("application/vnd.stagingdlrm.receive-migrated-case-submission+json"),
                eq(stagingDlrmUserId),
                anyString()))
                .thenReturn(Response.accepted().entity("").build());

        timerTrigger.run(timerInfo, context);

        assertNotNull(messageMap);
        verify(storageCloudClient).deleteQueueMessage(anyString());
    }

    @Test
    void shouldFetchTimerTriggerSuccessfully() {

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String queueMessage = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String path = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String testFile = path + "/test.pdf";
        final String test1File = path + "/test1.pdf";
        final String caseFile = path + "/case.json";
        final String manifestFile = path + "/manifest.json";

        final JsonObject caseJsonObject = getCaseJsonObject("28DI10000175");
        final String caseJsonPayload = getCaseJsonPayload();

        final String manifestJsonPayload = getManifestJsonPayload();
        final JsonObject manifestJsonObject = getManifestJsonObject();

        final String timerInfo = "timerInfo";

        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder()
                .add("submissionId", submissionId)
                .add("metadata", createObjectBuilder().add("numberOfMaterials", 2))
                .build();

        final Map<String, QueueMessage> messageMap = Map.of(queueMessage, new QueueMessage(queueMessage, 1L, List.of(testFile, test1File, caseFile, manifestFile)));

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);
        when(storageCloudClient.downloadBlobContents(submissionId, caseFile)).thenReturn(caseJsonPayload);
        when(caseJsonSchemaValidator.validate(submissionId, caseJsonPayload)).thenReturn(Set.of());
        when(storageCloudClient.downloadBlobContents(submissionId, manifestFile)).thenReturn(manifestJsonPayload);
        when(manifestJsonSchemaValidator.validate(submissionId, manifestJsonPayload)).thenReturn(Set.of());
        when(stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                eq(caseJsonObject),
                eq(List.of(testFile, test1File)),
                eq(manifestJsonObject),
                stringArgumentCaptor.capture(),
                eq(queueMessage)))
                .thenReturn(migratedCaseSubmissionJsonObject);
        when(stagingDlrmCommandHelper.sendPostCommandApi(
                "http://localhost:8080" + MIGRATED_CASE_SUBMISSION_PATH,
                migratedCaseSubmissionJsonObject,
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                stagingDlrmUserId,
                submissionId))
                .thenReturn(Response.accepted().entity("").build());

        timerTrigger.run(timerInfo, context);

        assertNotNull(messageMap);
        assertEquals(stringArgumentCaptor.getValue(), submissionId);
        verify(storageCloudClient).deleteQueueMessage(queueMessage);
    }

    @Test
    void shouldDeleteMessageFromQueueWhenTimerTriggerReturnsClientErrorForMigratedCaseSubmission() {
        final String submissionId = UUID.randomUUID().toString();
        final String queueMessage = "XHIBIT/2026-05-20/28DI10000175/"+submissionId;
        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder().add("submissionId", submissionId).add("metadata", createObjectBuilder().add("numberOfMaterials", 2)).build();
        final JsonObject errorMigratedCaseSubmissionJsonObject = createObjectBuilder().add("submissionId", submissionId).build();

        final String caseJsonPayload = getCaseJsonPayload();
        final JsonObject caseJsonObject = getCaseJsonObject(queueMessage);
        final JsonObject metaDataJsonObject = getMetaJsonObject();

        final String timerInfo = "timerInfo";

        final String responseString = """
                {"error":"Some Description"}
                """;

        final List<String> listBlobNames = List.of(queueMessage+"/test.pdf", queueMessage+"/test1.pdf", queueMessage+"/case.json", queueMessage+"/manifest.json");
        final Map<String, QueueMessage> messageMap = new HashMap<>();
        final QueueMessage message = new QueueMessage(queueMessage, 1L, listBlobNames);
        messageMap.put(queueMessage, message);

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);
        when(storageCloudClient.downloadBlobContents(submissionId, queueMessage + "/"+ "case.json")).thenReturn(caseJsonPayload);
        when(storageCloudClient.downloadBlobContents(submissionId, queueMessage + "/" + "manifest.json")).thenReturn(metaDataJsonObject.toString());
        when(caseJsonSchemaValidator.validate(submissionId, caseJsonPayload)).thenReturn(Set.of());
        when(stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(any(JsonObject.class),
                eq(List.of(queueMessage+"/test.pdf", queueMessage+"/test1.pdf")),
                eq(metaDataJsonObject), eq(submissionId), eq(queueMessage)))
                .thenReturn(migratedCaseSubmissionJsonObject);
        when(stagingDlrmCommandHelper.sendPostCommandApi(
                "http://localhost:8080" + MIGRATED_CASE_SUBMISSION_PATH,
                migratedCaseSubmissionJsonObject,
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                stagingDlrmUserId,
                submissionId))
                .thenReturn(Response.status(400).entity(responseString).build());
        when(stagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload(
                any(JsonObject.class),
                eq(submissionId),
                eq("28DI10000175"),
                eq(queueMessage),
                eq(responseString)))
                .thenReturn(errorMigratedCaseSubmissionJsonObject);
        when(stagingDlrmCommandHelper.sendPostCommandApi(
                "http://localhost:8080" + ERROR_MIGRATED_CASE_SUBMISSION_PATH,
                errorMigratedCaseSubmissionJsonObject,
                "application/vnd.stagingdlrm.receive-error-migrated-case-submission+json",
                stagingDlrmUserId,
                submissionId))
                .thenReturn(Response.accepted().entity("").build());


        timerTrigger.run(timerInfo, context);

        assertNotNull(messageMap);
        verify(storageCloudClient).deleteQueueMessage(queueMessage);

    }

    @Test
    void shouldTestTimerTriggerWhenCaseProcessingIsDisabled() {
        final String timerInfo = "timerInfo";

        setField(timerTrigger, "caseProcessingEnabled", false);

        when(context.getLogger()).thenReturn(logger);

        timerTrigger.run(timerInfo, context);

        verify(storageCloudClient, never()).receiveMessages();
    }

    @Test
    void shouldNotDeleteMessageFromQueueWhenTimerTriggerReturnsClientErrorForErrorMigratedCaseSubmission() {
        final String submissionId = UUID.randomUUID().toString();
        final String queueMessage = "XHIBIT/2026-05-20/28DI10000175/"+submissionId;
        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder()
                .add("submissionId", submissionId)
                .add("metadata", createObjectBuilder().add("numberOfMaterials", 2))
                .build();
        final JsonObject errorMigratedCaseSubmissionJsonObject = createObjectBuilder().add("submissionId", submissionId).build();

        final JsonObject caseJsonObject = getCaseJsonObject(queueMessage);
        final JsonObject metaDataJsonObject = getMetaJsonObject();

        final String timerInfo = "timerInfo";

        final String responseString = "{}";

        final List<String> listBlobNames = List.of(queueMessage+"/test.pdf", queueMessage+"/test1.pdf", queueMessage+"/case.json", queueMessage+"/manifest.json");
        final Map<String, QueueMessage> messageMap = new HashMap<>();
        final QueueMessage message = new QueueMessage(queueMessage, 1L, listBlobNames);
        messageMap.put(queueMessage, message);

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);
        when(storageCloudClient.downloadBlobContents(submissionId, queueMessage + "/" + "case.json")).thenReturn(caseJsonObject.toString());
        when(storageCloudClient.downloadBlobContents(submissionId, queueMessage + "/" + "manifest.json")).thenReturn(metaDataJsonObject.toString());
        when(stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                eq(caseJsonObject),
                eq(List.of(queueMessage+"/test.pdf", queueMessage+"/test1.pdf")),
                eq(metaDataJsonObject), stringArgumentCaptor.capture(), eq(queueMessage)))
                .thenReturn(migratedCaseSubmissionJsonObject);
        when(stagingDlrmCommandHelper.sendPostCommandApi(
                "http://localhost:8080" + MIGRATED_CASE_SUBMISSION_PATH,
                migratedCaseSubmissionJsonObject,
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                stagingDlrmUserId,
                submissionId))
                .thenReturn(Response.status(400).entity(responseString).build());
        when(stagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload(
                eq(migratedCaseSubmissionJsonObject), stringArgumentCaptor.capture(), eq(queueMessage), eq(queueMessage), eq(responseString)))
                .thenReturn(errorMigratedCaseSubmissionJsonObject);
        when(stagingDlrmCommandHelper.sendPostCommandApi(
                "http://localhost:8080" + ERROR_MIGRATED_CASE_SUBMISSION_PATH,
                errorMigratedCaseSubmissionJsonObject,
                "application/vnd.stagingdlrm.receive-error-migrated-case-submission+json",
                stagingDlrmUserId,
                submissionId))
                .thenReturn(Response.status(400).entity("{}").build());

        timerTrigger.run(timerInfo, context);

        assertNotNull(messageMap);
        verify(storageCloudClient).deleteQueueMessage(queueMessage);

    }


    @Test
    void shouldReturnTimerTriggerWhenCaseOrManifestJsonNotAvailable() {
        final String timerInfo = "timerInfo";
        final String queueMessage = "28DI10000175";
        final List<String> listBlobNames = List.of("test.pdf", "test1.pdf", "case.json", "manifest.json");
        final Map<String, QueueMessage> messageMap = new HashMap<>();
        messageMap.put(queueMessage, new QueueMessage(queueMessage, 1L, listBlobNames));

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);

        timerTrigger.run(timerInfo, context);

        verify(storageCloudClient, never()).downloadBlobContents(anyString(), anyString());
        verify(stagingDlrmCommandHelper, never()).generateMigratedCaseSubmissionPayload(any(JsonObject.class), anyList(), any(JsonObject.class), anyString(), anyString());
    }

    @Test
    void shouldDeleteMessageFromQueueWhenTimerTriggerReturnsServerError() {
        final String submissionId = UUID.randomUUID().toString();
        final String queueMessage = "XHIBIT/2026-05-20/28DI10000175/"+submissionId;
        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder().add("submissionId", submissionId).add("metadata", createObjectBuilder().add("numberOfMaterials", 2)).build();

        final JsonObject caseJsonObject = getCaseJsonObject(queueMessage);
        final JsonObject metaDataJsonObject = getMetaJsonObject();

        final String timerInfo = "timerInfo";

        final List<String> listBlobNames = List.of(queueMessage + "/test.pdf", queueMessage + "/test1.pdf", queueMessage + "/case.json", queueMessage + "/manifest.json");
        final Map<String, QueueMessage> messageMap = new HashMap<>();
        final QueueMessage message = new QueueMessage(queueMessage, 5L, listBlobNames);
        messageMap.put(queueMessage, message);


        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);
        when(storageCloudClient.downloadBlobContents(submissionId, queueMessage + "/" + "case.json")).thenReturn(caseJsonObject.toString());
        when(storageCloudClient.downloadBlobContents(submissionId, queueMessage + "/" + "manifest.json")).thenReturn(metaDataJsonObject.toString());
        when(stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(any(JsonObject.class), eq(List.of(queueMessage + "/test.pdf", queueMessage + "/test1.pdf")), eq(metaDataJsonObject), eq(submissionId), eq(queueMessage)))
                .thenReturn(migratedCaseSubmissionJsonObject);

        when(stagingDlrmCommandHelper.sendPostCommandApi(
                "http://localhost:8080" + MIGRATED_CASE_SUBMISSION_PATH,
                migratedCaseSubmissionJsonObject,
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                stagingDlrmUserId,
                submissionId))
                .thenReturn(Response.status(500).entity("{}").build());

        timerTrigger.run(timerInfo, context);

        assertNotNull(messageMap);
        verify(storageCloudClient).deleteQueueMessage(queueMessage);
    }

    private static Stream<Arguments> arguments() {
        return Stream.of(
                Arguments.of(List.of("28DI10000175/test.pdf", "28DI10000175/test1.pdf", "28DI10000175/case.json")),
                Arguments.of(List.of("28DI10000175/test.pdf", "28DI10000175/test1.pdf", "28DI10000175/manifest.json")),
                Arguments.of(List.of("28DI10000175/test.pdf", "28DI10000175/test1.pdf"))
        );
    }

    @ParameterizedTest
    @MethodSource("arguments")
    void shouldNotProcessTheMessageWhenManifestJsonFileNotExist(final List<String> listBlobNames) {
        final JsonObjectBuilder objectBuilder = createObjectBuilder();
        objectBuilder.add("content", "Test Content");

        final String timerInfo = "timerInfo";
        final String queueMessage = "28DI10000175";

        final Map<String, QueueMessage> messageMap = new HashMap<>();
        messageMap.put(queueMessage, new QueueMessage(queueMessage, 1L, listBlobNames));

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);

        timerTrigger.run(timerInfo, context);

        assertNotNull(messageMap);
        verify(storageCloudClient, never()).downloadBlobContents(anyString(), anyString());
    }

    @Test
    void shouldProcessTimerTriggerSuccessfullyWhenNoMaterialsAreAttached() {

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String queueMessage = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String path = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String caseFile = path + "/case.json";
        final String manifestFile = path + "/manifest.json";

        final JsonObject caseJsonObject = getCaseJsonObject("DL838641927");

        final JsonObject metaData = getMetaJsonObject();

        final String timerInfo = "timerInfo";

        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder()
                .add("submissionId", submissionId)
                .add("metadata", createObjectBuilder().add("numberOfMaterials", 0))
                .build();

        final Map<String, QueueMessage> messageMap = Map.of(queueMessage, new QueueMessage(queueMessage, 1L, List.of(caseFile, manifestFile)));

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);
        when(storageCloudClient.downloadBlobContents(submissionId, caseFile)).thenReturn(caseJsonObject.toString());
        when(storageCloudClient.downloadBlobContents(submissionId, manifestFile)).thenReturn(metaData.toString());
        when(stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                eq(caseJsonObject),
                eq(List.of()),
                eq(metaData),
                stringArgumentCaptor.capture(),
                eq(queueMessage)))
                .thenReturn(migratedCaseSubmissionJsonObject);
        when(stagingDlrmCommandHelper.sendPostCommandApi(
                "http://localhost:8080" + MIGRATED_CASE_SUBMISSION_PATH,
                migratedCaseSubmissionJsonObject,
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                stagingDlrmUserId,
                submissionId))
                .thenReturn(Response.accepted().entity("").build());

        timerTrigger.run(timerInfo, context);

        assertNotNull(messageMap);
        assertEquals(stringArgumentCaptor.getValue(), submissionId);
        verify(storageCloudClient).deleteQueueMessage(queueMessage);
    }

    private JsonObject getCaseJsonObject(String prosecutorCaseReference) {
        return createObjectBuilder()
                .add("migratedCase", createObjectBuilder()
                        .add("caseDetails", createObjectBuilder()
                                .add("prosecutorCaseReference", prosecutorCaseReference)
                                .build())).build();
    }

    private String getCaseJsonPayload() {
        return """
                {
                    "migratedCase": {
                        "caseDetails": {
                            "prosecutorCaseReference": "%s"
                        }
                    }
                }
                """.formatted("28DI10000175");
    }

    private JsonObject getMetaJsonObject() {
        return createObjectBuilder().add("content", "Test Content").build();
    }

    private JsonObject getManifestJsonObject() {
        return createObjectBuilder().build();
    }

    private String getManifestJsonPayload() {
        return """
                {}
                """;
    }
}