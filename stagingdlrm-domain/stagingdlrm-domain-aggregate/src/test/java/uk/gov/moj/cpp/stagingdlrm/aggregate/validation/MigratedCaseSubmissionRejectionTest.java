package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import static java.util.Map.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;

import uk.gov.moj.cpp.stagingdlrm.aggregate.MigratedCaseSubmissionAggregate;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionRejected;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.json.Json;
import javax.json.JsonObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MigratedCaseSubmissionRejectionTest {

    private static final String SUBMISSION = "json/aggregate/xhibit/submission-without-materials.json";

    private static final String EXPECTED_REJECTED = "json/aggregate/xhibit/expected-rejected.json";

    private static final String EXPECTED_PROCESSED_REJECTED = "json/aggregate/xhibit/expected-processed-rejected.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProducer().objectMapper();

    private static final JsonObjectToObjectConverter CONVERTER =
            new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());

    @BeforeEach
    void registerAlwaysFailingRule() {
        final ValidationError error = new ValidationError("$", "test-only always-failing rule");
        MigratedCaseValidationRuleEngine.registerRuleForTest(XHIBIT, input -> List.of(error));
    }

    @AfterEach
    void resetRules() {
        MigratedCaseValidationRuleEngine.resetTestRules();
    }

    @Test
    void aFailedRuleAppendsRejectedAndProcessedFailureButNeverReceived() {
        final List<Object> events =
                new MigratedCaseSubmissionAggregate().receiveMigratedCaseSubmission(loadSubmission()).toList();

        assertThat(events.size(), is(2));
        assertThat(events.get(0), instanceOf(MigratedCaseSubmissionRejected.class));
        assertThat(events.get(1), instanceOf(MigratedCaseSubmissionProcessed.class));

        assertThat(serialise(events.get(0)),
                matchesWholePayload(fixture(EXPECTED_REJECTED, of("SOURCE_SYSTEM", XHIBIT.name())), List.of()));
        assertThat(serialise(events.get(1)),
                matchesWholePayload(fixture(EXPECTED_PROCESSED_REJECTED, of("SOURCE_SYSTEM", XHIBIT.name())), List.of()));

        for (final Object event : events) {
            assertThat(event, is(not(instanceOf(MigratedCaseSubmissionReceived.class))));
        }
    }

    private static MigratedCaseSubmission loadSubmission() {
        final String json = fixture(SUBMISSION, of("SOURCE_SYSTEM", XHIBIT.name()));
        final JsonObject jsonObject =
                Json.createReader(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).readObject();
        return CONVERTER.convert(jsonObject, MigratedCaseSubmission.class);
    }

    private static String serialise(final Object event) {
        try {
            return OBJECT_MAPPER.writeValueAsString(event);
        } catch (final JsonProcessingException e) {
            throw new AssertionError("Failed to serialise " + event, e);
        }
    }
}
