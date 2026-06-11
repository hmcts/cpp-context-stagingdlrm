package uk.gov.moj.cpp.stagingdlrm.it;

import static java.util.stream.IntStream.range;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClientProvider.newPrivateJmsMessageConsumerClientProvider;
import static uk.gov.moj.cpp.stagingdlrm.helper.FileUtil.getStringFromResource;
import static uk.gov.moj.cpp.stagingdlrm.helper.FileUtil.readJson;
import static uk.gov.moj.cpp.stagingdlrm.helper.MigratedCaseSubmissionEventHelper.sendMigratedCaseFileProcessedEvent;
import static uk.gov.moj.cpp.stagingdlrm.helper.MigratedCaseSubmissionEventHelper.verifyPrivateEvents;
import static uk.gov.moj.cpp.stagingdlrm.helper.QueueUtil.retrieveMessageBody;
import static uk.gov.moj.cpp.stagingdlrm.helper.WiremockTestHelper.createCommonMockEndpoints;
import static uk.gov.moj.cpp.stagingdlrm.stub.PcfdlrmStub.verifyReceiveCaseFileRequested;

import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;
import uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClient;
import uk.gov.moj.cpp.stagingdlrm.helper.AbstractTestHelper;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Defendant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.json.JsonArray;
import javax.json.JsonObject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


class ReceiveCaseFileSubmissionIT extends AbstractTestHelper {

    private static final JmsMessageConsumerClient consumerClient = newPrivateJmsMessageConsumerClientProvider(CONTEXT)
            .withEventNames("stagingdlrm.events.migrated-case-submission-received")
            .getMessageConsumerClient();

    private final JmsMessageConsumerClient consumerClientForDupicated = newPrivateJmsMessageConsumerClientProvider(CONTEXT)
            .withEventNames("stagingdlrm.events.migrated-case-submission-processed")
            .getMessageConsumerClient();

    private final JmsMessageConsumerClient duplicatedReceivedConsumer = newPrivateJmsMessageConsumerClientProvider(CONTEXT)
            .withEventNames("stagingdlrm.events.duplicate-migrated-case-submission-received")
            .getMessageConsumerClient();


    private static final JsonObjectToObjectConverter jsonObjectToObjectConverter = new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());
    public static final String MIGRATION_SOURCE_SYSTEM_NAME_NOT_FOUND = "migrationSourceSystemName] not found";
    public static final String TELEPHONE_NUMBER_BUSINESS_STRING_INVALID_TELEPHONE_DOES_NOT_MATCH_PATTERN = "telephoneNumberBusiness: string [INVALID_TELEPHONE] does not match pattern";
    public static final String EMAIL_ADDRESS_1_STRING_INVALID_EMAIL_DOES_NOT_MATCH_PATTERN = "emailAddress1: string [INVALID_EMAIL] does not match pattern";
    public static final String DUPLICATE_SUBMISSION_ID = "Duplicate Submission ID";

    @BeforeAll
    static void setUp() {
        createCommonMockEndpoints();
    }


    @Test
    void shouldAcceptCaseFileSubmissionRequest() {
        final String submissionId = UUID.randomUUID().toString();

        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission.json").replace("SUBMISSION_ID", submissionId);

        final JsonObject jsonPayload = readJson(payload);

        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);

        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");

        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("LIBRA");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertThat(commandMigrateCaseDetails.getJsonObject("migrationSourceSystem"), is(messageMigrateCaseDetails.getJsonObject("migrationSourceSystem")));
        assertThat(commandMigrateCaseDetails.getJsonObject("materials"), is(messageMigrateCaseDetails.getJsonObject("materials")));
        assertThat(commandMigrateCaseDetails.getJsonArray("hearings"), is(messageMigrateCaseDetails.getJsonArray("hearings")));

    }

    @Test
    void shouldNotAcceptCaseFileSubmissionRequest() {
        String submissionId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission.json").replace("SUBMISSION_ID", submissionId);
        final String realCaseUrn = readJson(payload).getJsonObject("migratedCase")
                .getJsonObject("caseDetails")
                .getString("prosecutorCaseReference");

        makePostCall(getWriteUrl("/receive-migrated-case-submission"), "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);
        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());

        final UUID caseId = UUID.randomUUID();
        final String caseUrn = UUID.randomUUID().toString();

        sendMigratedCaseFileProcessedEvent(
                UUID.fromString(submissionId), caseId, caseUrn, true, "Processed");

        verifyPrivateEvents(
                consumerClientForDupicated, caseId, UUID.fromString(submissionId),
                caseUrn, true, "Processed");

        makePostCall(getWriteUrl("/receive-migrated-case-submission"), "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);

        assertTrue(retrieveMessageBody(duplicatedReceivedConsumer).isPresent());

        verifyPrivateEvents(
                consumerClientForDupicated, caseId, UUID.fromString(submissionId),
                realCaseUrn, false, DUPLICATE_SUBMISSION_ID);
    }

    @Test
    void shouldRaiseBadRequest() {
        String submissionId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-bad-request.json").replace("SUBMISSION_ID", submissionId);
        Assertions.assertDoesNotThrow(() ->

        makePostCall(getWriteUrl("/receive-migrated-case-submission"), "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload,400, MIGRATION_SOURCE_SYSTEM_NAME_NOT_FOUND,
                TELEPHONE_NUMBER_BUSINESS_STRING_INVALID_TELEPHONE_DOES_NOT_MATCH_PATTERN,
                EMAIL_ADDRESS_1_STRING_INVALID_EMAIL_DOES_NOT_MATCH_PATTERN)

        );
    }

    @Test
    void shouldAcceptCaseFileSubmissionRequestFromXhibit() {
        String submissionId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-from-xhibit.json")
                .replace("SUBMISSION_ID", submissionId);


        final JsonObject jsonPayload = readJson(payload);
        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);
        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");

        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("XHIBIT");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertThat(commandMigrateCaseDetails.getJsonObject("migrationSourceSystem"), is(messageMigrateCaseDetails.getJsonObject("migrationSourceSystem")));
        assertThat(commandMigrateCaseDetails.getJsonObject("materials"), is(messageMigrateCaseDetails.getJsonObject("materials")));
        assertThat(commandMigrateCaseDetails.getJsonArray("hearings"), is(messageMigrateCaseDetails.getJsonArray("hearings")));
        assertThat(migratedDefendantList.get(0).getIndividual().getCustodyStatus(), is(messageDefendantList.get(0).getIndividual().getCustodyStatus()));
        assertThat(migratedDefendantList.get(0).getIndividual().getCustodyTimeLimit(), is(messageDefendantList.get(0).getIndividual().getCustodyTimeLimit()));
        assertThat(migratedDefendantList.get(1).getOffences().size(), is(messageDefendantList.get(1).getOffences().size()));
        assertThat(commandMigrateCaseDetails.get("receiptType"), is(messageMigrateCaseDetails.get("receiptType")));
    }

    @Test
    void shouldAcceptCaseFileSubmissionRequestFromXhibitWhenGenderHearingLanguageNotMatchCP() {
        String submissionId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-from-xhibit-gender.json")
                .replace("SUBMISSION_ID", submissionId);


        final JsonObject jsonPayload = readJson(payload);
        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);
        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");

        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("XHIBIT");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertThat(migratedDefendantList.get(0).getIndividual().getSelfDefinedInformation().getGender(), is(messageDefendantList.get(0).getIndividual().getSelfDefinedInformation().getGender()));
        assertThat(migratedDefendantList.get(0).getHearingLanguage(), is(messageDefendantList.get(0).getHearingLanguage()));
        assertThat(migratedDefendantList.get(0).getDocumentationLanguage(), is(messageDefendantList.get(0).getDocumentationLanguage()));
        assertThat(commandMigrateCaseDetails.getJsonObject("materials"), is(messageMigrateCaseDetails.getJsonObject("materials")));
        assertThat(commandMigrateCaseDetails.get("receiptType"), is(messageMigrateCaseDetails.get("receiptType")));
        assertThat(migratedDefendantList.get(0).getOffences().get(0).getConvictionDate(), is(messageDefendantList.get(0).getOffences().get(0).getConvictionDate()));

    }

    @Test
    void shouldAcceptCaseFileSubmissionRequestWithMultipleHearing() {
        final String submissionId = UUID.randomUUID().toString();

        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-with-multiple-hearing.json")
                .replace("SUBMISSION_ID", submissionId);

        final JsonObject jsonPayload = readJson(payload);

        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);

        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");


        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("LIBRA");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertEquals(2, messageMigrateCaseDetails.getJsonArray("hearings").size());
        assertThat(commandMigrateCaseDetails.getJsonArray("hearings"), is(messageMigrateCaseDetails.getJsonArray("hearings")));
        assertThat(commandMigrateCaseDetails.getJsonObject("materials"), is(messageMigrateCaseDetails.getJsonObject("materials")));
    }


    @Test
    void shouldAcceptCaseFileSubmissionRequestWithoutMaterials() {
        final String submissionId = UUID.randomUUID().toString();

        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-without-materials.json")
                .replace("SUBMISSION_ID", submissionId);

        final JsonObject jsonPayload = readJson(payload);

        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);

        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");


        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("LIBRA");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertEquals(2, messageMigrateCaseDetails.getJsonArray("hearings").size());
        assertThat(commandMigrateCaseDetails.getJsonArray("hearings"), is(messageMigrateCaseDetails.getJsonArray("hearings")));
        assertNull(migratedCaseSubmission.getJsonObject("materials"));

    }

    @Test
    void shouldAcceptCaseFileSubmissionRequestXHIBITWithCaseMarker() {
        String submissionId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-from-xhibit-with-casemarker.json")
                .replace("SUBMISSION_ID", submissionId);


        final JsonObject jsonPayload = readJson(payload);
        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);
        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");

        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("XHIBIT");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertThat(commandMigrateCaseDetails.getJsonObject("migrationSourceSystem"), is(messageMigrateCaseDetails.getJsonObject("migrationSourceSystem")));
        assertThat(commandMigrateCaseDetails.getJsonObject("caseMarkers"), is(messageMigrateCaseDetails.getJsonObject("caseMarkers")));

    }

    @Test
    void shouldAcceptCaseFileSubmissionRequestXHIBITWithPlea() {
        String submissionId = UUID.randomUUID().toString();
        String pleaId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-from-xhibit-with-plea.json")
                .replace("SUBMISSION_ID", submissionId)
                .replace("PLEA_ID", pleaId);


        final JsonObject jsonPayload = readJson(payload);
        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);
        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");

        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("XHIBIT");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertThat(commandMigrateCaseDetails.getJsonObject("migrationSourceSystem"), is(messageMigrateCaseDetails.getJsonObject("migrationSourceSystem")));
        assertThat(commandMigrateCaseDetails.getJsonObject("plea"), is(messageMigrateCaseDetails.getJsonObject("plea")));

    }

    @Test
    void shouldAcceptCaseFileSubmissionRequestXHIBITWithVerdict() {
        String submissionId = UUID.randomUUID().toString();
        String pleaId = UUID.randomUUID().toString();
        String verdictId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-from-xhibit-with-verdict.json")
                .replace("SUBMISSION_ID", submissionId)
                .replace("PLEA_ID", pleaId)
                .replace("VERDICT_ID", verdictId);


        final JsonObject jsonPayload = readJson(payload);
        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);
        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");

        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("XHIBIT");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertThat(commandMigrateCaseDetails.getJsonObject("migrationSourceSystem"), is(messageMigrateCaseDetails.getJsonObject("migrationSourceSystem")));
        assertThat(commandMigrateCaseDetails.getJsonObject("verdict"), is(messageMigrateCaseDetails.getJsonObject("verdict")));

    }

    @Test
    void shouldAcceptCaseFileSubmissionRequestWhenDateOfCommittalIsMissing() {
        String submissionId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-from-xhibit-missing-doc.json")
                .replace("SUBMISSION_ID", submissionId);


        final JsonObject jsonPayload = readJson(payload);
        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);
        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");

        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("XHIBIT");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertThat(commandMigrateCaseDetails.getJsonObject("migrationSourceSystem"), is(messageMigrateCaseDetails.getJsonObject("migrationSourceSystem")));
        assertThat(commandMigrateCaseDetails.getJsonObject("dateOfSending"), is(messageMigrateCaseDetails.getJsonObject("2024-08-23")));
        assertNull(commandMigrateCaseDetails.getJsonObject("dateOfCommittal"));
    }

    @Test
    void shouldAcceptCaseFileSubmissionRequestWhenSendingCourtIsMissing() {
        String submissionId = UUID.randomUUID().toString();
        final String payload = getStringFromResource("stagingdlrm.receive-migrated-case-submission-from-xhibit-missing-sending-court.json")
                .replace("SUBMISSION_ID", submissionId);


        final JsonObject jsonPayload = readJson(payload);
        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);
        final Optional<JsonObject> message = retrieveMessageBody(consumerClient);

        assertTrue(message.isPresent());
        final JsonObject migratedCaseSubmission = message.get().getJsonObject("migratedCaseSubmission");
        final JsonObject messageMigrateCaseDetails = migratedCaseSubmission.getJsonObject("migratedCase");
        final JsonObject commandMigrateCaseDetails = jsonPayload.getJsonObject("migratedCase");
        final JsonArray commandMigrateDefendantDetails = commandMigrateCaseDetails.getJsonArray("defendants");
        final JsonArray messageMigrateDefendantDetails = messageMigrateCaseDetails.getJsonArray("defendants");

        final List<Defendant> migratedDefendantList = getDefendantList(commandMigrateDefendantDetails);

        final List<Defendant> messageDefendantList = getDefendantList(messageMigrateDefendantDetails);

        final List<String> stringList = new ArrayList<>();
        stringList.add(submissionId);
        stringList.add("DLRM_MIGRATION");
        stringList.add("XHIBIT");
        verifyReceiveCaseFileRequested(stringList);

        assertThat(commandMigrateCaseDetails.get("caseId"), is(messageMigrateCaseDetails.get("caseId")));
        commonDefendantMatches(migratedDefendantList, messageDefendantList);
        assertNull(commandMigrateCaseDetails.getJsonObject("sendingCourt"));

    }

    private List<Defendant> getDefendantList(final JsonArray jsonValues) {
        return range(0, jsonValues.size())
                .mapToObj(i -> jsonObjectToObjectConverter.convert(jsonValues.getJsonObject(i), Defendant.class))
                .toList();

    }


    private void commonDefendantMatches(final List<Defendant> migratedDefendantList, final List<Defendant> messageDefendantList) {
        assertThat(migratedDefendantList.get(0).getProsecutorDefendantId(), is(messageDefendantList.get(0).getProsecutorDefendantId()));
        assertThat(migratedDefendantList.get(1).getProsecutorDefendantId(), is(messageDefendantList.get(1).getProsecutorDefendantId()));
        assertNotNull(messageDefendantList.get(0).getId());
        assertNotNull(messageDefendantList.get(1).getId());
        assertThat(migratedDefendantList.get(0).getOffences().get(0).getOffenceCode(), is(messageDefendantList.get(0).getOffences().get(0).getOffenceCode()));
        assertThat(migratedDefendantList.get(0).getOffences().get(0).getOffenceWording(), is(messageDefendantList.get(0).getOffences().get(0).getOffenceWording()));
        assertThat(migratedDefendantList.get(0).getOffences().get(0).getOffenceCommittedDate(), is(messageDefendantList.get(0).getOffences().get(0).getOffenceCommittedDate()));
        assertThat(migratedDefendantList.get(0).getOffences().get(0).getOffenceSequenceNumber(), is(messageDefendantList.get(0).getOffences().get(0).getOffenceSequenceNumber()));
        assertThat(migratedDefendantList.get(0).getOffences().size(), is(messageDefendantList.get(0).getOffences().size()));

    }
}

