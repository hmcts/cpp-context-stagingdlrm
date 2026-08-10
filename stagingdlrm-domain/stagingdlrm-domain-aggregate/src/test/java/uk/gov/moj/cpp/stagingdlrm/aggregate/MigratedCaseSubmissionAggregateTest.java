package uk.gov.moj.cpp.stagingdlrm.aggregate;

import static java.util.Map.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

import uk.gov.justice.services.common.converter.JsonObjectToObjectConverter;
import uk.gov.justice.services.common.converter.jackson.ObjectMapperProducer;

import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.CaseAlreadyProcessedAndExistsInProgressionCommand;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.ErrorMigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmissionProcessedOutput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonObject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MigratedCaseSubmissionAggregateTest {

    private static final String XHIBIT = "XHIBIT";

    private static final String XHIBIT_JSON_PATH = "json/aggregate/xhibit/";

    private static final UUID WITH_MATERIALS_SUBMISSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final UUID STANDALONE_CASE_ID = UUID.fromString("a4391788-f829-4514-a344-61f1d5d9690c");

    private static final UUID PROGRESSION_CASE_ID = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private static final UUID ERROR_SUBMISSION_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProducer().objectMapper();

    private static final JsonObjectToObjectConverter CONVERTER =
            new JsonObjectToObjectConverter(new ObjectMapperProducer().objectMapper());

    @ParameterizedTest(name = "{index} => {0}")
    @MethodSource("submissionScenarios")
    void shouldAppendExpectedEventsWholePayload(final String name, final SubmissionScenario scenario) {
        scenario.runAndAssert(new MigratedCaseSubmissionAggregate());
    }

    static Stream<Arguments> submissionScenarios() {
        return Stream.of(
                Arguments.of(
                        "FR2 migrated-case-submission-received carries the whole submission with materials (XHIBIT)",
                        scenario(XHIBIT)
                                .receiveSubmission(XHIBIT_JSON_PATH + "submission-with-materials.json",
                                        expect(XHIBIT_JSON_PATH + "expected-received-with-materials.json"))),
                Arguments.of(
                        "FR2 migrated-case-submission-received carries the whole submission without materials (XHIBIT)",
                        scenario(XHIBIT)
                                .receiveSubmission(XHIBIT_JSON_PATH + "submission-without-materials.json",
                                        expect(XHIBIT_JSON_PATH + "expected-received-without-materials.json"))),
                Arguments.of(
                        "FR2 recording a processing output appends migrated-case-submission-processed (XHIBIT)",
                        scenario(XHIBIT)
                                .recordProcessingOutput(standaloneOutput(),
                                        expect(XHIBIT_JSON_PATH + "expected-processed.json"))),
                Arguments.of(
                        "FR2 a repeat submission is flagged duplicate then processed unsuccessfully (XHIBIT)",
                        scenario(XHIBIT)
                                .receiveSubmission(XHIBIT_JSON_PATH + "submission-with-materials.json",
                                        expect(XHIBIT_JSON_PATH + "expected-received-with-materials.json"))
                                .recordProcessingOutput(duplicateOutput(),
                                        expect(XHIBIT_JSON_PATH + "expected-processed-intermediate.json"))
                                .receiveSubmission(XHIBIT_JSON_PATH + "submission-with-materials.json",
                                        expect(XHIBIT_JSON_PATH + "expected-duplicated.json"),
                                        expect(XHIBIT_JSON_PATH + "expected-processed-duplicate.json"))),
                Arguments.of(
                        "FR2 a case already in progression is recorded then processed unsuccessfully (XHIBIT)",
                        scenario(XHIBIT)
                                .receiveSubmission(XHIBIT_JSON_PATH + "submission-without-materials.json",
                                        expect(XHIBIT_JSON_PATH + "expected-received-without-materials.json"))
                                .caseAlreadyProcessed(XHIBIT_JSON_PATH + "submission-without-materials.json", PROGRESSION_CASE_ID,
                                        expect(XHIBIT_JSON_PATH + "expected-already-processed.json"),
                                        expect(XHIBIT_JSON_PATH + "expected-processed-already.json"))),
                Arguments.of(
                        "FR2 an error submission appends error-migrated-case-submission-received (XHIBIT)",
                        scenario(XHIBIT)
                                .receiveError(errorSubmission(),
                                        expect(XHIBIT_JSON_PATH + "expected-error.json"))));
    }

    @Test
    void aggregateInputFixtureRoundTripsUnchanged() {
        final String json = fixture(XHIBIT_JSON_PATH + "submission-with-materials.json", of("SOURCE_SYSTEM", XHIBIT));

        final MigratedCaseSubmission submission = CONVERTER.convert(readJson(json), MigratedCaseSubmission.class);

        assertThat(serialise(submission), matchesWholePayload(json, List.of()));
    }

    private static MigratedCaseSubmissionProcessedOutput standaloneOutput() {
        return MigratedCaseSubmissionProcessedOutput
                .migratedCaseSubmissionProcessedOutput()
                .withCaseId(STANDALONE_CASE_ID)
                .withSubmissionId(STANDALONE_CASE_ID)
                .withCaseUrn("TVL55117DFXXV")
                .withProcessingIsSuccessful(true)
                .withDescription("Processed")
                .build();
    }

    private static MigratedCaseSubmissionProcessedOutput duplicateOutput() {
        return MigratedCaseSubmissionProcessedOutput
                .migratedCaseSubmissionProcessedOutput()
                .withCaseId(WITH_MATERIALS_SUBMISSION_ID)
                .withSubmissionId(WITH_MATERIALS_SUBMISSION_ID)
                .withCaseUrn("TVL55117DFXXV")
                .withProcessingIsSuccessful(true)
                .withDescription("Processed")
                .build();
    }

    private static ErrorMigratedCaseSubmission errorSubmission() {
        return ErrorMigratedCaseSubmission
                .errorMigratedCaseSubmission()
                .withSubmissionId(ERROR_SUBMISSION_ID)
                .withPayload("sample text")
                .build();
    }

    private static SubmissionScenario scenario(final String sourceSystem) {
        return new SubmissionScenario(sourceSystem);
    }

    private static Expectation expect(final String fixtureName, final String... excludedPaths) {
        return new Expectation(fixtureName, List.of(excludedPaths));
    }

    private static JsonObject readJson(final String json) {
        return Json.createReader(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).readObject();
    }

    private static String serialise(final Object event) {
        try {
            return OBJECT_MAPPER.writeValueAsString(event);
        } catch (final JsonProcessingException e) {
            throw new AssertionError("Failed to serialise " + event, e);
        }
    }

    private record Expectation(String fixtureName, List<String> excludedPaths) {
    }

    private record Step(Function<MigratedCaseSubmissionAggregate, Stream<Object>> invocation,
                        List<Expectation> expectations) {
    }

    private static final class SubmissionScenario {

        private final String sourceSystem;
        private final List<Step> steps = new ArrayList<>();

        private SubmissionScenario(final String sourceSystem) {
            this.sourceSystem = sourceSystem;
        }

        private SubmissionScenario receiveSubmission(final String submissionFixture, final Expectation... expectations) {
            steps.add(new Step(
                    aggregate -> aggregate.receiveMigratedCaseSubmission(loadSubmission(submissionFixture)),
                    List.of(expectations)));
            return this;
        }

        private SubmissionScenario recordProcessingOutput(final MigratedCaseSubmissionProcessedOutput output,
                                                          final Expectation... expectations) {
            steps.add(new Step(
                    aggregate -> aggregate.recordMigratedCaseSubmissionOutput(output),
                    List.of(expectations)));
            return this;
        }

        private SubmissionScenario caseAlreadyProcessed(final String submissionFixture, final UUID caseId,
                                                        final Expectation... expectations) {
            steps.add(new Step(
                    aggregate -> aggregate.receiveCaseAlreadyProcessed(
                            CaseAlreadyProcessedAndExistsInProgressionCommand
                                    .caseAlreadyProcessedAndExistsInProgressionCommand()
                                    .withCaseId(caseId)
                                    .withMigratedCaseSubmission(loadSubmission(submissionFixture))
                                    .build()),
                    List.of(expectations)));
            return this;
        }

        private SubmissionScenario receiveError(final ErrorMigratedCaseSubmission error,
                                                final Expectation... expectations) {
            steps.add(new Step(
                    aggregate -> aggregate.receiveErrorMigratedCaseSubmission(error),
                    List.of(expectations)));
            return this;
        }

        private void runAndAssert(final MigratedCaseSubmissionAggregate aggregate) {
            for (final Step step : steps) {
                final List<Object> events = step.invocation().apply(aggregate).toList();

                assertThat("number of appended events", events.size(), is(step.expectations().size()));

                for (int i = 0; i < events.size(); i++) {
                    final Expectation expectation = step.expectations().get(i);
                    assertThat(serialise(events.get(i)),
                            matchesWholePayload(loadExpected(expectation.fixtureName()), expectation.excludedPaths()));
                }
            }
        }

        private MigratedCaseSubmission loadSubmission(final String submissionFixture) {
            return CONVERTER.convert(readJson(bind(submissionFixture)), MigratedCaseSubmission.class);
        }

        private String loadExpected(final String expectedFixture) {
            return bind(expectedFixture);
        }

        private String bind(final String fixtureName) {
            if (carriesSourceSystem(fixtureName)) {
                return fixture(fixtureName, of("SOURCE_SYSTEM", requireSourceSystem()));
            }
            return fixture(fixtureName);
        }

        private String requireSourceSystem() {
            if (sourceSystem == null) {
                throw new AssertionError("Scenario did not bind a source system (DD-43078 FR1)");
            }
            return sourceSystem;
        }

        private static boolean carriesSourceSystem(final String fixtureName) {
            try (InputStream in = SubmissionScenario.class.getClassLoader().getResourceAsStream(fixtureName)) {
                if (in == null) {
                    throw new AssertionError("Fixture not found on the test classpath: " + fixtureName);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("{{SOURCE_SYSTEM}}");
            } catch (final IOException e) {
                throw new AssertionError("Failed to read fixture " + fixtureName, e);
            }
        }
    }
}
