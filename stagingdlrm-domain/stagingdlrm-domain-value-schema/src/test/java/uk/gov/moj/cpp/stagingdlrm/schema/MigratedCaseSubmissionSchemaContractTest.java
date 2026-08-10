package uk.gov.moj.cpp.stagingdlrm.schema;

import static java.util.Map.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static uk.gov.justice.services.test.utils.core.matchers.JsonSchemaValidationMatcher.failsValidationWithMessage;
import static uk.gov.justice.services.test.utils.core.matchers.JsonSchemaValidationMatcher.isValidForSchema;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.json.JSONObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MigratedCaseSubmissionSchemaContractTest {

    private static final String SCHEMA = "json/schema/migrated/migrated-case-submission.json";

    private static final String XHIBIT = "XHIBIT";

    private static final String BASE_FIXTURE = "json/schema-contract/case-submission-valid.json";

    private static final String CASE_DETAILS_MESSAGE = "#/migratedCase/caseDetails: #: only 1 subschema matches out of 2";

    @TempDir
    private Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptScenarios")
    void shouldAcceptValidPayload(final String name, final Consumer<JSONObject> mutation) throws IOException {
        assertThat(payload(mutation), isValidForSchema(SCHEMA));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectScenarios")
    void shouldRejectInvalidPayload(final String name, final Consumer<JSONObject> mutation, final String messageFragment) throws IOException {
        assertThat(payload(mutation), failsValidationWithMessage(SCHEMA, messageFragment));
    }

    static Stream<Arguments> acceptScenarios() {
        return Stream.of(
                Arguments.of("FR5 pin1 caseDetails.dateReceived present is accepted (XHIBIT)", identity()),
                Arguments.of("FR5 pin2 caseDetails.receiptType present is accepted (XHIBIT)", identity()),
                Arguments.of("FR5 pin3 caseDetails.receivingCourt present is accepted (XHIBIT)", identity()),
                Arguments.of("FR5 pin4 caseDetails.retrialIndicator present is accepted (XHIBIT)", identity()),
                Arguments.of("FR5 pin5 caseDetails.initiationCode 'O' is accepted (XHIBIT)", identity()),
                Arguments.of("FR5 pin6 caseDetails with dateOfCommittal only is accepted (XHIBIT)",
                        mutation(r -> caseDetails(r).remove("dateOfSending"))),
                Arguments.of("FR5 pin6 caseDetails with dateOfSending only is accepted (XHIBIT)",
                        mutation(r -> caseDetails(r).remove("dateOfCommittal"))),
                Arguments.of("FR5 pin7 hearings[*].durationMinutes present is accepted (XHIBIT)", identity()),
                Arguments.of("FR5 pin8 offences[*].prosecutorOffenceId present is accepted (XHIBIT)", identity()));
    }

    static Stream<Arguments> rejectScenarios() {
        return Stream.of(
                Arguments.of("FR5 pin1 caseDetails.dateReceived required — reject when absent (XHIBIT)",
                        mutation(r -> caseDetails(r).remove("dateReceived")),
                        CASE_DETAILS_MESSAGE),
                Arguments.of("FR5 pin2 caseDetails.receiptType required — reject when absent (XHIBIT)",
                        mutation(r -> caseDetails(r).remove("receiptType")),
                        CASE_DETAILS_MESSAGE),
                Arguments.of("FR5 pin3 caseDetails.receivingCourt required — reject when absent (XHIBIT)",
                        mutation(r -> caseDetails(r).remove("receivingCourt")),
                        CASE_DETAILS_MESSAGE),
                Arguments.of("FR5 pin4 caseDetails.retrialIndicator required — reject when absent (XHIBIT)",
                        mutation(r -> caseDetails(r).remove("retrialIndicator")),
                        CASE_DETAILS_MESSAGE),
                Arguments.of("FR5 pin5 caseDetails.initiationCode enum ['O'] — reject other values (XHIBIT)",
                        mutation(r -> caseDetails(r).put("initiationCode", "X")),
                        CASE_DETAILS_MESSAGE),
                Arguments.of("FR5 pin6 caseDetails anyOf[dateOfCommittal|dateOfSending] — reject when neither (XHIBIT)",
                        mutation(r -> { caseDetails(r).remove("dateOfCommittal"); caseDetails(r).remove("dateOfSending"); }),
                        CASE_DETAILS_MESSAGE),
                Arguments.of("FR5 pin7 hearings[*].durationMinutes required — reject when absent (XHIBIT)",
                        mutation(r -> hearing0(r).remove("durationMinutes")),
                        "#/migratedCase/hearings/0: required key [durationMinutes] not found"),
                Arguments.of("FR5 pin8 offences[*].prosecutorOffenceId required — reject when absent (XHIBIT)",
                        mutation(r -> offence0(r).remove("prosecutorOffenceId")),
                        "#/migratedCase/defendants/0/offences/0: required key [prosecutorOffenceId] not found"),
                Arguments.of("FR5 pin9 caseMarkers[*].markerTypeCode required — stays enforced (XHIBIT)",
                        mutation(r -> caseDetails(r).getJSONArray("caseMarkers").getJSONObject(0).remove("markerTypeCode")),
                        CASE_DETAILS_MESSAGE),
                Arguments.of("FR5 pin10 selfDefinedInformation.gender required — stays enforced (XHIBIT)",
                        mutation(r -> defendant0(r).getJSONObject("individual").getJSONObject("selfDefinedInformation").remove("gender")),
                        "#/migratedCase/defendants/0/individual/selfDefinedInformation: required key [gender] not found"),
                Arguments.of("FR5 pin11 offences[*].offenceDateCode maximum 6 — stays enforced (XHIBIT)",
                        mutation(r -> offence0(r).put("offenceDateCode", 7)),
                        "#/migratedCase/defendants/0/offences/0/offenceDateCode: 7.0 is not less or equal to 6"));
    }

    private String payload(final Consumer<JSONObject> mutation) throws IOException {
        final JSONObject root = new JSONObject(fixture(BASE_FIXTURE, of("SOURCE_SYSTEM", XHIBIT)));
        mutation.accept(root);
        final Path file = Files.createTempFile(tempDir, "payload", ".json");
        Files.writeString(file, root.toString());
        return file.toAbsolutePath().toString();
    }

    private static Consumer<JSONObject> identity() {
        return root -> {
        };
    }

    private static Consumer<JSONObject> mutation(final Consumer<JSONObject> mutation) {
        return mutation;
    }

    private static JSONObject caseDetails(final JSONObject root) {
        return root.getJSONObject("migratedCase").getJSONObject("caseDetails");
    }

    private static JSONObject hearing0(final JSONObject root) {
        return root.getJSONObject("migratedCase").getJSONArray("hearings").getJSONObject(0);
    }

    private static JSONObject defendant0(final JSONObject root) {
        return root.getJSONObject("migratedCase").getJSONArray("defendants").getJSONObject(0);
    }

    private static JSONObject offence0(final JSONObject root) {
        return defendant0(root).getJSONArray("offences").getJSONObject(0);
    }
}
