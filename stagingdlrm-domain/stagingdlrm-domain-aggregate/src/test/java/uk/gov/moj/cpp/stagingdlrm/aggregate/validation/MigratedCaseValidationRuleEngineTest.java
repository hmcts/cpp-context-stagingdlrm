package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import static java.util.Map.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.LIBRA;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;

import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;

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

    private static final JsonObjectToObjectConverter CONVERTER =
            new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());

    private final MigratedCaseValidationRuleEngine engine = new MigratedCaseValidationRuleEngine();

    @Test
    void aValidXhibitSubmissionPassesEveryRule() {
        assertThat(engine.validate(XHIBIT, load(VALID)), is(empty()));
    }

    @Test
    void anXhibitSubmissionMissingARelaxedFieldIsRejectedByItsRule() {
        final List<ValidationError> errors = engine.validate(XHIBIT, load(MISSING_DATE_RECEIVED));

        assertThat(errors, hasSize(1));
        assertThat(errors.get(0).jsonPath(), is("$.migratedCase.caseDetails.dateReceived"));
    }

    @Test
    void libraHasNoRulesRegisteredYetSoTheSameSubmissionPasses() {
        assertThat(engine.validate(LIBRA, load(MISSING_DATE_RECEIVED)), is(empty()));
    }

    private static MigratedCaseSubmission load(final String fixtureName) {
        final String json = fixture(fixtureName, of("SOURCE_SYSTEM", XHIBIT.name()));
        final JsonObject jsonObject =
                Json.createReader(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).readObject();
        return CONVERTER.convert(jsonObject, MigratedCaseSubmission.class);
    }
}
