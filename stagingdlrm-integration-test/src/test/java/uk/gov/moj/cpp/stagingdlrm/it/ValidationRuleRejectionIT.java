package uk.gov.moj.cpp.stagingdlrm.it;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClientProvider.newPrivateJmsMessageConsumerClientProvider;
import static uk.gov.moj.cpp.stagingdlrm.helper.FileUtil.getStringFromResource;
import static uk.gov.moj.cpp.stagingdlrm.helper.FileUtil.readJson;
import static uk.gov.moj.cpp.stagingdlrm.helper.MigratedCaseSubmissionEventHelper.verifyPrivateEvents;
import static uk.gov.moj.cpp.stagingdlrm.helper.QueueUtil.retrieveMessageBody;
import static uk.gov.moj.cpp.stagingdlrm.helper.WiremockTestHelper.createCommonMockEndpoints;
import static uk.gov.moj.cpp.stagingdlrm.stub.PcfdlrmStub.verifyReceiveCaseFileNotRequestedFor;
import static uk.gov.moj.cpp.stagingdlrm.stub.PcfdlrmStub.verifyReceiveCaseFileRequested;

import uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClient;
import uk.gov.moj.cpp.stagingdlrm.helper.AbstractTestHelper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ValidationRuleRejectionIT extends AbstractTestHelper {

    private static final String VALIDATION_FAILED = "Migrated case submission rejected by validation rule(s)";
    private static final String XHIBIT_BASE = "xhibit/stagingdlrm.receive-migrated-case-submission-from-xhibit.json";
    private static final String XHIBIT_URN = "TVL55117DFXXV";
    private static final String LIBRA_BASE = "libra/stagingdlrm.receive-migrated-case-submission-from-libra.json";
    private static final String LIBRA_URN = "LIBRA55117D";

    private final JmsMessageConsumerClient receivedConsumer = newPrivateJmsMessageConsumerClientProvider(CONTEXT)
            .withEventNames("stagingdlrm.events.migrated-case-submission-received")
            .getMessageConsumerClient();

    private final JmsMessageConsumerClient rejectedConsumer = newPrivateJmsMessageConsumerClientProvider(CONTEXT)
            .withEventNames("stagingdlrm.events.migrated-case-submission-rejected")
            .getMessageConsumerClient();

    private final JmsMessageConsumerClient processedConsumer = newPrivateJmsMessageConsumerClientProvider(CONTEXT)
            .withEventNames("stagingdlrm.events.migrated-case-submission-processed")
            .getMessageConsumerClient();

    @BeforeEach
    void setUp() {
        createCommonMockEndpoints();
    }

    static Stream<Arguments> rejectionScenarios() {
        return Stream.of(
                arguments("XHIBIT missing dateReceived", XHIBIT_BASE, XHIBIT_URN,
                        mutator(payload -> removeCaseDetailsFields(payload, "dateReceived")),
                        "$.migratedCase.caseDetails.dateReceived"),
                arguments("XHIBIT missing receiptType", XHIBIT_BASE, XHIBIT_URN,
                        mutator(payload -> removeCaseDetailsFields(payload, "receiptType")),
                        "$.migratedCase.caseDetails.receiptType"),
                arguments("XHIBIT missing receivingCourt", XHIBIT_BASE, XHIBIT_URN,
                        mutator(payload -> removeCaseDetailsFields(payload, "receivingCourt")),
                        "$.migratedCase.caseDetails.receivingCourt"),
                arguments("XHIBIT missing retrialIndicator", XHIBIT_BASE, XHIBIT_URN,
                        mutator(payload -> removeCaseDetailsFields(payload, "retrialIndicator")),
                        "$.migratedCase.caseDetails.retrialIndicator"),
                arguments("XHIBIT missing both dateOfCommittal and dateOfSending", XHIBIT_BASE, XHIBIT_URN,
                        mutator(payload -> removeCaseDetailsFields(payload, "dateOfCommittal", "dateOfSending")),
                        "$.migratedCase.caseDetails"),
                arguments("LIBRA missing hearing courtRoomId", LIBRA_BASE, LIBRA_URN,
                        mutator(payload -> removeFirstHearingField(payload, "courtRoomId")),
                        "$.migratedCase.hearings[*].courtRoomId"),
                arguments("LIBRA missing hearing dateOfHearing", LIBRA_BASE, LIBRA_URN,
                        mutator(payload -> removeFirstHearingField(payload, "dateOfHearing")),
                        "$.migratedCase.hearings[*].dateOfHearing"),
                arguments("LIBRA missing hearing timeOfHearing", LIBRA_BASE, LIBRA_URN,
                        mutator(payload -> removeFirstHearingField(payload, "timeOfHearing")),
                        "$.migratedCase.hearings[*].timeOfHearing"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectionScenarios")
    void shouldRejectByRuleAndNotForward(final String description, final String baseFixture, final String caseUrn,
                                         final Function<String, String> mutator, final String expectedJsonPath) {
        final String submissionId = UUID.randomUUID().toString();
        final String payload = mutator.apply(getStringFromResource(baseFixture)).replace("SUBMISSION_ID", submissionId);

        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);

        final Optional<JsonObject> rejected = retrieveMessageBody(rejectedConsumer);
        assertTrue(rejected.isPresent());
        final JsonArray validationErrors = rejected.get().getJsonArray("validationErrors");
        assertThat(validationErrors.size(), is(1));
        assertThat(validationErrors.getJsonObject(0).getString("jsonPath"), is(expectedJsonPath));

        verifyPrivateEvents(processedConsumer, UUID.fromString(submissionId), caseUrn, false, VALIDATION_FAILED);

        verifyReceiveCaseFileNotRequestedFor(submissionId);
    }

    @Test
    void shouldAcceptValidLibraSubmissionAndForwardIt() {
        final String submissionId = UUID.randomUUID().toString();
        final String payload = getStringFromResource(LIBRA_BASE).replace("SUBMISSION_ID", submissionId);

        makePostCall(
                getWriteUrl("/receive-migrated-case-submission"),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                payload);

        assertTrue(retrieveMessageBody(receivedConsumer).isPresent());
        verifyReceiveCaseFileRequested(List.of(submissionId, "DLRM_MIGRATION", "LIBRA"));
    }

    private static Function<String, String> mutator(final Function<String, String> function) {
        return function;
    }

    private static String removeCaseDetailsFields(final String payload, final String... fields) {
        final JsonObject root = readJson(payload);
        final JsonObject migratedCase = root.getJsonObject("migratedCase");
        final JsonObject caseDetails = copyWithout(migratedCase.getJsonObject("caseDetails"), Set.of(fields));
        return put(root, "migratedCase", put(migratedCase, "caseDetails", caseDetails)).toString();
    }

    private static String removeFirstHearingField(final String payload, final String field) {
        final JsonObject root = readJson(payload);
        final JsonObject migratedCase = root.getJsonObject("migratedCase");
        final JsonArray hearings = withoutFieldOnFirstElement(migratedCase.getJsonArray("hearings"), field);
        return put(root, "migratedCase", put(migratedCase, "hearings", hearings)).toString();
    }

    private static JsonArray withoutFieldOnFirstElement(final JsonArray array, final String field) {
        final JsonArrayBuilder builder = Json.createArrayBuilder();
        builder.add(copyWithout(array.getJsonObject(0), Set.of(field)));
        for (int i = 1; i < array.size(); i++) {
            builder.add(array.getJsonObject(i));
        }
        return builder.build();
    }

    private static JsonObject copyWithout(final JsonObject object, final Set<String> keys) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        object.forEach((key, value) -> {
            if (!keys.contains(key)) {
                builder.add(key, value);
            }
        });
        return builder.build();
    }

    private static JsonObject put(final JsonObject object, final String key, final JsonObject value) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        object.forEach((existingKey, existingValue) -> {
            if (!existingKey.equals(key)) {
                builder.add(existingKey, existingValue);
            }
        });
        builder.add(key, value);
        return builder.build();
    }

    private static JsonObject put(final JsonObject object, final String key, final JsonArray value) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        object.forEach((existingKey, existingValue) -> {
            if (!existingKey.equals(key)) {
                builder.add(existingKey, existingValue);
            }
        });
        builder.add(key, value);
        return builder.build();
    }
}
