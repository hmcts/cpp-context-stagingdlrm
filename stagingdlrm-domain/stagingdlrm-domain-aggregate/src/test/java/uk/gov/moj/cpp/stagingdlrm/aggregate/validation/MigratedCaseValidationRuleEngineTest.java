package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import static java.util.Map.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.LIBRA;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;

import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;

import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.json.Json;
import javax.json.JsonObject;

import org.junit.jupiter.api.Test;

class MigratedCaseValidationRuleEngineTest {

    private static final String VALID = "json/aggregate/xhibit/submission-without-materials.json";

    private static final String MISSING_DATE_RECEIVED = "json/aggregate/xhibit/submission-missing-date-received.json";

    private static final String LIBRA_VALID = "json/aggregate/libra/submission-valid.json";

    private static final JsonObjectToObjectConverter CONVERTER =
            new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());

    private final MigratedCaseValidationRuleEngine engine = new MigratedCaseValidationRuleEngine();

    @Test
    void aValidXhibitSubmissionPassesEveryRule() {
        assertThat(engine.validate(XHIBIT, load(VALID, XHIBIT)), is(empty()));
    }

    @Test
    void anXhibitSubmissionMissingARelaxedFieldIsRejectedByItsRule() {
        final List<ValidationError> errors = engine.validate(XHIBIT, load(MISSING_DATE_RECEIVED, XHIBIT));

        assertThat(errors, hasSize(1));
        assertThat(errors.get(0).jsonPath(), is("$.migratedCase.caseDetails.dateReceived"));
    }

    @Test
    void aValidLibraSubmissionPassesEveryRule() {
        assertThat(engine.validate(LIBRA, load(LIBRA_VALID, LIBRA)), is(empty()));
    }

    @Test
    void aLibraSubmissionWithInitiationCodeSPassesEveryRule() {
        assertThat(engine.validate(LIBRA,
                        load("json/aggregate/libra/submission-valid-initiation-code-s.json", LIBRA)),
                is(empty()));
    }

    @Test
    void aLibraInitiationCodeSIsNotPermittedForXhibit() {
        final MigratedCaseSubmission libraCoded =
                load("json/aggregate/libra/submission-valid-initiation-code-s.json", XHIBIT);

        assertThat(engine.validate(XHIBIT, libraCoded).stream().map(ValidationError::jsonPath).toList(),
                hasItem("$.migratedCase.caseDetails.initiationCode"));
    }

    @Test
    void aLibraSubmissionMissingCourtRoomIdIsRejectedAndXhibitIsUnaffected() {
        assertLibraRejectedXhibitUnaffected(
                "json/aggregate/libra/submission-missing-court-room-id.json",
                "$.migratedCase.hearings[*].courtRoomId");
    }

    @Test
    void aLibraSubmissionMissingDateOfHearingIsRejectedAndXhibitIsUnaffected() {
        assertLibraRejectedXhibitUnaffected(
                "json/aggregate/libra/submission-missing-date-of-hearing.json",
                "$.migratedCase.hearings[*].dateOfHearing");
    }

    @Test
    void aLibraSubmissionMissingTimeOfHearingIsRejectedAndXhibitIsUnaffected() {
        assertLibraRejectedXhibitUnaffected(
                "json/aggregate/libra/submission-missing-time-of-hearing.json",
                "$.migratedCase.hearings[*].timeOfHearing");
    }

    @Test
    void aLibraSubmissionMissingDefendantAddressIsAccepted() {
        assertThat(engine.validate(LIBRA,
                        load("json/aggregate/libra/submission-missing-defendant-address.json", LIBRA)),
                is(empty()));
    }

    @Test
    void aLibraSubmissionWithADisallowedInitiationCodeIsRejected() {
        final List<ValidationError> errors =
                engine.validate(LIBRA, load("json/aggregate/libra/submission-invalid-initiation-code.json", LIBRA));

        assertThat(errors, hasSize(1));
        assertThat(errors.get(0).jsonPath(), is("$.migratedCase.caseDetails.initiationCode"));
    }

    @Test
    void anXhibitSubmissionWithADisallowedInitiationCodeIsRejected() {
        final List<ValidationError> errors =
                engine.validate(XHIBIT, load("json/aggregate/xhibit/submission-invalid-initiation-code.json", XHIBIT));

        assertThat(errors, hasSize(1));
        assertThat(errors.get(0).jsonPath(), is("$.migratedCase.caseDetails.initiationCode"));
    }

    @Test
    void aLibraInitiationCodeIsNotPermittedForXhibit() {
        final MigratedCaseSubmission libraCoded =
                load("json/aggregate/libra/submission-valid.json", XHIBIT);
        assertThat(engine.validate(XHIBIT, libraCoded).stream().map(ValidationError::jsonPath).toList(),
                hasItem("$.migratedCase.caseDetails.initiationCode"));

        final MigratedCaseSubmission xhibitCoded =
                load("json/aggregate/xhibit/submission-without-materials.json", LIBRA);
        assertThat(engine.validate(LIBRA, xhibitCoded).stream().map(ValidationError::jsonPath).toList(),
                hasItem("$.migratedCase.caseDetails.initiationCode"));
    }

    private void assertLibraRejectedXhibitUnaffected(final String fixtureName, final String expectedJsonPath) {
        final MigratedCaseSubmission submission = load(fixtureName, LIBRA);

        final List<ValidationError> libraErrors = engine.validate(LIBRA, submission);
        assertThat(libraErrors, hasSize(1));
        assertThat(libraErrors.get(0).jsonPath(), is(expectedJsonPath));

        final List<String> xhibitPaths = engine.validate(XHIBIT, submission).stream()
                .map(ValidationError::jsonPath)
                .toList();
        assertThat(xhibitPaths, not(hasItem(expectedJsonPath)));
    }

    private static MigratedCaseSubmission load(final String fixtureName, final MigrationSourceSystemName sourceSystem) {
        final String json = fixture(fixtureName, of("SOURCE_SYSTEM", sourceSystem.name()));
        final JsonObject jsonObject =
                Json.createReader(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).readObject();
        return CONVERTER.convert(jsonObject, MigratedCaseSubmission.class);
    }
}
