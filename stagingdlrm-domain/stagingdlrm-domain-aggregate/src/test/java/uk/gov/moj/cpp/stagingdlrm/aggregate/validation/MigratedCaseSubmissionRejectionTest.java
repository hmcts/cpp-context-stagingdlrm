package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import static java.util.Map.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static uk.gov.moj.cpp.stagingdlrm.aggregate.MigratedCaseSubmissionAggregate.VALIDATION_FAILED;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;

import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;

import uk.gov.moj.cpp.stagingdlrm.aggregate.MigratedCaseSubmissionAggregate;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigratedCaseValidationError;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionProcessed;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionReceived;
import uk.gov.moj.stagingdlrm.domain.event.MigratedCaseSubmissionRejected;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.json.Json;
import javax.json.JsonObject;

import org.junit.jupiter.api.Test;

class MigratedCaseSubmissionRejectionTest {

    private static final String VALID = "json/aggregate/xhibit/submission-without-materials.json";

    private static final String MISSING_DATE_RECEIVED = "json/aggregate/xhibit/submission-missing-date-received.json";

    private static final JsonObjectToObjectConverter CONVERTER =
            new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());

    @Test
    void anXhibitRuleViolationAppendsRejectedAndProcessedFailureButNeverReceived() {
        final List<Object> events =
                new MigratedCaseSubmissionAggregate().receiveMigratedCaseSubmission(load(MISSING_DATE_RECEIVED)).toList();

        assertThat(events, hasSize(2));
        assertThat(events.get(0), instanceOf(MigratedCaseSubmissionRejected.class));
        assertThat(events.get(1), instanceOf(MigratedCaseSubmissionProcessed.class));

        final MigratedCaseSubmissionRejected rejected = (MigratedCaseSubmissionRejected) events.get(0);
        assertThat(rejected.getValidationErrors(), hasSize(1));
        final MigratedCaseValidationError error = rejected.getValidationErrors().get(0);
        assertThat(error.getJsonPath(), is("$.migratedCase.caseDetails.dateReceived"));

        final MigratedCaseSubmissionProcessed processed = (MigratedCaseSubmissionProcessed) events.get(1);
        assertThat(processed.getMigratedCaseSubmissionProcessed().getProcessingIsSuccessful(), is(false));
        assertThat(processed.getMigratedCaseSubmissionProcessed().getDescription(), is(VALIDATION_FAILED));

        for (final Object event : events) {
            assertThat(event, is(not(instanceOf(MigratedCaseSubmissionReceived.class))));
        }
    }

    @Test
    void aValidXhibitSubmissionIsReceivedNotRejected() {
        final List<Object> events =
                new MigratedCaseSubmissionAggregate().receiveMigratedCaseSubmission(load(VALID)).toList();

        assertThat(events, contains(instanceOf(MigratedCaseSubmissionReceived.class)));
    }

    private static MigratedCaseSubmission load(final String fixtureName) {
        final String json = fixture(fixtureName, of("SOURCE_SYSTEM", XHIBIT.name()));
        final JsonObject jsonObject =
                Json.createReader(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).readObject();
        return CONVERTER.convert(jsonObject, MigratedCaseSubmission.class);
    }
}
