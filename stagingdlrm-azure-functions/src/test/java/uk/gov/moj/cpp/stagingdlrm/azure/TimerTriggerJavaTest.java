package uk.gov.moj.cpp.stagingdlrm.azure;


import static java.nio.charset.StandardCharsets.UTF_8;
import static uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.hamcrest.MatcherAssert.assertThat;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.emptyJson;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

import uk.gov.justice.services.messaging.JsonObjects;
import uk.gov.moj.cpp.stagingdlrm.azure.event.QueueMessage;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.EventGridMonitorHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.rest.StagingDlrmCommandHelper;
import uk.gov.moj.cpp.stagingdlrm.azure.storage.StorageCloudClient;
import uk.gov.moj.cpp.stagingdlrm.azure.validator.JsonSchemaValidator;

import java.io.ByteArrayInputStream;
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

    /** DD-43078 T2 AC11 — what the trigger hands the assembler, asserted whole. */
    @Captor
    private ArgumentCaptor<JsonObject> caseJsonObjectCaptor;

    @Captor
    private ArgumentCaptor<JsonObject> manifestJsonObjectCaptor;

    private static final String FIXTURES = "json/timer-trigger/";

    private static final String CASE_REFERENCE = "CASEREF-0001";

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
        final String queueMessage = "CASEREF-0001";
        final String caseJsonPayload = casePayload(CASE_REFERENCE);

        final String manifestJsonPayload = emptyJson();

        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder()
                .add("metadata", createObjectBuilder().add("numberOfMaterials", 2))
                .build();

        final List<String> listBlobNames = List.of("CASEREF-0001/test.pdf", "CASEREF-0001/test1.pdf", "CASEREF-0001/case.json", "CASEREF-0001/manifest.json");
        final Map<String, QueueMessage> messageMap = new HashMap<>();
        messageMap.put(queueMessage, new QueueMessage(queueMessage, 1L, listBlobNames));

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);
        when(storageCloudClient.downloadBlobContents(anyString(), eq("CASEREF-0001/case.json"))).thenReturn(caseJsonPayload);
        when(caseJsonSchemaValidator.validate(anyString(), eq(caseJsonPayload))).thenReturn(Set.of());
        when(storageCloudClient.downloadBlobContents(anyString(), eq("CASEREF-0001/manifest.json"))).thenReturn(manifestJsonPayload);
        when(manifestJsonSchemaValidator.validate(anyString(), eq(manifestJsonPayload))).thenReturn(Set.of());
        when(stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                caseJsonObjectCaptor.capture(),
                eq(List.of("CASEREF-0001/test.pdf", "CASEREF-0001/test1.pdf")),
                manifestJsonObjectCaptor.capture(),
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

        assertHandedToAssemblerWhole();
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

        final String caseJsonPayload = casePayload(CASE_REFERENCE);

        final String manifestJsonPayload = emptyJson();

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
                caseJsonObjectCaptor.capture(),
                eq(List.of(testFile, test1File)),
                manifestJsonObjectCaptor.capture(),
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

        assertHandedToAssemblerWhole();
        assertEquals(submissionId, stringArgumentCaptor.getValue());
        verify(storageCloudClient).deleteQueueMessage(queueMessage);
    }

    @Test
    void shouldDeleteMessageFromQueueWhenTimerTriggerReturnsClientErrorForMigratedCaseSubmission() {
        final String submissionId = UUID.randomUUID().toString();
        final String queueMessage = "XHIBIT/2026-05-20/CASEREF-0001/"+submissionId;
        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder().add("submissionId", submissionId).add("metadata", createObjectBuilder().add("numberOfMaterials", 2)).build();
        final JsonObject errorMigratedCaseSubmissionJsonObject = createObjectBuilder().add("submissionId", submissionId).build();

        final String caseJsonPayload = casePayload(CASE_REFERENCE);
        final JsonObject metaDataJsonObject = getMetaJsonObject();

        final String timerInfo = "timerInfo";

        final String responseString = fixture(FIXTURES + "error-response.json");

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
                eq("CASEREF-0001"),
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
    void shouldDeleteMessageFromQueueWhenTimerTriggerReturnsClientErrorForErrorMigratedCaseSubmission() {
        final String submissionId = UUID.randomUUID().toString();
        final String queueMessage = "XHIBIT/2026-05-20/CASEREF-0001/"+submissionId;
        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder()
                .add("submissionId", submissionId)
                .add("metadata", createObjectBuilder().add("numberOfMaterials", 2))
                .build();
        final JsonObject errorMigratedCaseSubmissionJsonObject = createObjectBuilder().add("submissionId", submissionId).build();

        final JsonObject caseJsonObject = readJson(casePayload(queueMessage));
        final JsonObject metaDataJsonObject = getMetaJsonObject();

        final String timerInfo = "timerInfo";

        final String responseString = emptyJson();

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

        verify(storageCloudClient).deleteQueueMessage(queueMessage);

    }


    @Test
    void shouldReturnTimerTriggerWhenCaseOrManifestJsonNotAvailable() {
        final String timerInfo = "timerInfo";
        final String queueMessage = "CASEREF-0001";
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
        final String queueMessage = "XHIBIT/2026-05-20/CASEREF-0001/"+submissionId;
        final JsonObject migratedCaseSubmissionJsonObject = createObjectBuilder().add("submissionId", submissionId).add("metadata", createObjectBuilder().add("numberOfMaterials", 2)).build();

        final JsonObject caseJsonObject = readJson(casePayload(queueMessage));
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

        verify(storageCloudClient).deleteQueueMessage(queueMessage);
    }

    private static Stream<Arguments> arguments() {
        return Stream.of(
                Arguments.of(List.of("CASEREF-0001/test.pdf", "CASEREF-0001/test1.pdf", "CASEREF-0001/case.json")),
                Arguments.of(List.of("CASEREF-0001/test.pdf", "CASEREF-0001/test1.pdf", "CASEREF-0001/manifest.json")),
                Arguments.of(List.of("CASEREF-0001/test.pdf", "CASEREF-0001/test1.pdf"))
        );
    }

    @ParameterizedTest
    @MethodSource("arguments")
    void shouldNotProcessTheMessageWhenManifestJsonFileNotExist(final List<String> listBlobNames) {
        final JsonObjectBuilder objectBuilder = createObjectBuilder();
        objectBuilder.add("content", "Test Content");

        final String timerInfo = "timerInfo";
        final String queueMessage = "CASEREF-0001";

        final Map<String, QueueMessage> messageMap = new HashMap<>();
        messageMap.put(queueMessage, new QueueMessage(queueMessage, 1L, listBlobNames));

        when(context.getLogger()).thenReturn(logger);
        when(storageCloudClient.receiveMessages()).thenReturn(messageMap);

        timerTrigger.run(timerInfo, context);

        verify(storageCloudClient, never()).downloadBlobContents(anyString(), anyString());
        verify(stagingDlrmCommandHelper, never()).generateMigratedCaseSubmissionPayload(
                any(JsonObject.class), anyList(), any(JsonObject.class), anyString(), anyString());
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

        final JsonObject caseJsonObject = readJson(casePayload("CASEREF-0002"));

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
                caseJsonObjectCaptor.capture(),
                eq(List.of()),
                manifestJsonObjectCaptor.capture(),
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

        assertHandedToAssemblerWhole("CASEREF-0002", getMetaJsonObject().toString());
        assertEquals(submissionId, stringArgumentCaptor.getValue());
        verify(storageCloudClient).deleteQueueMessage(queueMessage);
    }

    /**
     * DD-43078 FR2 / T2 AC11 — asserts whole the two payloads handed to
     * {@code generateMigratedCaseSubmissionPayload}, the hand-off from "read and validate the blobs"
     * to "assemble the command body".
     */
    private void assertHandedToAssemblerWhole() {
        assertHandedToAssemblerWhole(CASE_REFERENCE, emptyJson());
    }

    /**
     * Expected is the input fixture rebound to the same reference — which <i>is</i> the assertion,
     * since this hand-off copies the parsed blob through untouched.
     */
    private void assertHandedToAssemblerWhole(final String caseReference, final String expectedManifest) {
        assertThat("case payload handed to the assembler",
                caseJsonObjectCaptor.getValue().toString(),
                matchesWholePayload(casePayload(caseReference), List.of()));

        assertThat("manifest handed to the assembler",
                manifestJsonObjectCaptor.getValue().toString(),
                matchesWholePayload(expectedManifest, List.of()));
    }

    private static JsonObject readJson(final String json) {
        return JsonObjects.createReader(new ByteArrayInputStream(json.getBytes(UTF_8))).readObject();
    }

    private static String casePayload(final String prosecutorCaseReference) {
        return fixture(FIXTURES + "case.json",
                Map.of("CASE_REFERENCE", prosecutorCaseReference));
    }

    private JsonObject getMetaJsonObject() {
        return createObjectBuilder().add("content", "Test Content").build();
    }
}