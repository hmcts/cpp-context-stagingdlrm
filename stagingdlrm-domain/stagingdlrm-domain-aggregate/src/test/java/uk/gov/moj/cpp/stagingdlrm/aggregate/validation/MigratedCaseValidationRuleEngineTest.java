package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import static java.util.Map.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MigratedCaseValidationRuleEngineTest {

    private static final String SUBMISSION = "json/aggregate/xhibit/submission-without-materials.json";

    private static final JsonObjectToObjectConverter CONVERTER =
            new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());

    private final MigratedCaseValidationRuleEngine engine = new MigratedCaseValidationRuleEngine();

    @AfterEach
    void resetRules() {
        MigratedCaseValidationRuleEngine.resetTestRules();
    }

    @Test
    void anEmptyMapValidatesEverySourceSystemAsClean() {
        final MigratedCaseSubmission submission = loadSubmission();

        assertThat(engine.validate(XHIBIT, submission), is(empty()));
        assertThat(engine.validate(LIBRA, submission), is(empty()));
    }

    @Test
    void aRegisteredRuleAppliesOnlyToItsSourceSystem() {
        final ValidationError error = new ValidationError("$", "test-only always-failing rule");
        MigratedCaseValidationRuleEngine.registerRuleForTest(XHIBIT, input -> List.of(error));

        final MigratedCaseSubmission submission = loadSubmission();

        assertThat(engine.validate(XHIBIT, submission), contains(error));
        assertThat(engine.validate(LIBRA, submission), is(empty()));
    }

    private static MigratedCaseSubmission loadSubmission() {
        final String json = fixture(SUBMISSION, of("SOURCE_SYSTEM", XHIBIT.name()));
        final JsonObject jsonObject =
                Json.createReader(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).readObject();
        return CONVERTER.convert(jsonObject, MigratedCaseSubmission.class);
    }
}
