package uk.gov.moj.cpp.stagingdlrm.azure.rest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;
import static uk.gov.moj.cpp.stagingdlrm.azure.TimerTriggerJava.MIGRATED_CASE_SUBMISSION_PATH;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.emptyJson;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

import uk.gov.justice.services.messaging.JsonObjects;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonWriter;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StagingDlrmCommandHelperTest {

    @InjectMocks
    private StagingDlrmCommandHelper stagingDlrmCommandHelper;

    @Mock
    private ExecutionContext context;

    @Mock
    private Response response;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Client client;

    private static final String stagingDlrmUserId = UUID.randomUUID().toString();

    /** DD-43078 FR1 — every fixture binds the baseline explicitly via {@code {{SOURCE_SYSTEM}}}. */
    private static final String XHIBIT = "XHIBIT";

    /** Fixed, so the comparison has two non-deterministic values to exclude rather than three. */
    private static final String FIXED_SUBMISSION_ID = "11111111-2222-3333-4444-555555555555";

    private static final String LOCATION = "batch-0001";

    private static final String CASE_IDENTIFIER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private static final String FIXTURES = "json/command-helper/";

    private final Logger logger = Logger.getLogger(StagingDlrmCommandHelper.class.getName());

    @BeforeEach
    public void setup() {
        when(context.getLogger()).thenReturn(logger);
        setField(stagingDlrmCommandHelper, "client", client);
    }

    /**
     * Backs the single pin ({@link #shouldGenerateMigratedCaseSubmissionPayloadSuccessfully}) that
     * the assembler copies {@code caseDetails}, {@code hearings} and {@code defendants} through
     * unmodified. Built for structural variety rather than volume — one of each shape a copy could
     * mangle: four-deep nesting, a two-element object array, a scalar array, ints, an empty string,
     * an empty object, an array inside an array element.
     *
     * <p>Every other test uses {@link #minimalCaseInput()}: the assembler reads only eight names and
     * copies the subtrees opaquely, so a realistic case file elsewhere is scenery that has to be
     * duplicated into an expected fixture.
     */
    private static JsonObject caseInput() {
        return readJson(fixture(FIXTURES + "case-structural.json"));
    }

    /** The smallest input still carrying all three subtrees the assembler copies. */
    private static JsonObject minimalCaseInput() {
        return readJson(fixture(FIXTURES + "case-minimal.json"));
    }

    private static JsonObject manifest(final String name) {
        return readJson(fixture(FIXTURES + name, of("SOURCE_SYSTEM", XHIBIT)));
    }

    private static String expected(final String name) {
        return fixture(FIXTURES + name, of("SOURCE_SYSTEM", XHIBIT));
    }

    private static JsonObject readJson(final String json) {
        return JsonObjects.createReader(new ByteArrayInputStream(json.getBytes(UTF_8))).readObject();
    }

    private static String asString(final JsonObject jsonObject) {
        final StringWriter stringWriter = new StringWriter();
        try (JsonWriter jsonWriter = JsonObjects.createWriter(stringWriter)) {
            jsonWriter.write(jsonObject);
        }
        return stringWriter.toString();
    }

    /**
     * DD-43078 FR2 / T2 AC10 — this payload is the body POSTed to
     * {@code /receive-migrated-case-submission}, an outbound boundary, so it is asserted whole.
     * Exclusions are the only non-deterministic values, listed per element rather than as a bare
     * {@code "id"} token that would also swallow any {@code id} added later.
     */
    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadSuccessfully() {

        final JsonObject payload = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                caseInput(),
                List.of(LOCATION + "/test.pdf", LOCATION + "/test1.pdf"),
                manifestWithMaterials(),
                FIXED_SUBMISSION_ID, LOCATION);

        assertThat(asString(payload), matchesWholePayload(
                expected("expected-with-materials.json"),
                List.of("metadata.id",        // UUID.randomUUID() in the assembler
                        "materials[0].id",    // UUID.randomUUID() per material
                        "materials[1].id")));
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadSuccessfullyWhenNoMaterialsAreAttached() {

        final JsonObject payload = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                minimalCaseInput(),
                List.of(),
                manifestWithoutMaterials(),
                FIXED_SUBMISSION_ID, LOCATION);

        assertThat(asString(payload), matchesWholePayload(
                expected("expected-without-materials.json"),
                List.of("metadata.id")));      // UUID.randomUUID() in the assembler

        // "materials" is absent, not an empty array — pinned above too, but easy to miss in a fixture.
        assertNull(payload.getJsonArray("materials"));
    }

    /** DD-43078 T2 AC10 — caller-supplied submissionId, azureLocation carrying the full blob path. */
    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadWithSubmissionIdPassedSuccessfully() {

        final String path = "%s/%s/%s/%s".formatted(XHIBIT, "20082025", CASE_IDENTIFIER, FIXED_SUBMISSION_ID);

        final JsonObject payload = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                minimalCaseInput(),
                List.of(path + "/test.pdf", path + "/test1.pdf"),
                manifestWithMaterials(), FIXED_SUBMISSION_ID, path);

        assertThat(asString(payload), matchesWholePayload(
                expected("expected-with-caller-submission-id.json"),
                List.of("metadata.id",        // UUID.randomUUID() in the assembler
                        "materials[0].id",    // UUID.randomUUID() per material
                        "materials[1].id")));
    }

    @Test
    void shouldGenerateMigratedCaseWhenFileExistInBlobButNotInMetadataFile() {

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String queueMessage = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String path = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String testFile = path + "/test.pdf";
        final String test1File = path + "/test1.pdf";

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                minimalCaseInput(),
                List.of(testFile, test1File),
                manifestWithoutMaterials(), submissionId, queueMessage);

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals(migrationSourceSystemName, migrationSourceSystem.getString("migrationSourceSystemName"));
        assertNotNull(migrationSourceSystem.getString("migrationSourceSystemCaseIdentifier"));
        assertNull(materials);
        assertEquals(0, numberOfMaterials);

        assertNotNull(jsonObject.getString("submissionId"));
        assertEquals(submissionId, jsonObject.getString("submissionId"));
        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadWhenMigrationSourceSystemIsEmpty() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                minimalCaseInput(),
                List.of("batch-0001/test.pdf", "batch-0001/test1.pdf"),
                buildMetaDataWithMigrationSourceSystemIsEmpty(), submissionId, "batch-0001");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals(2, materials.size());
        assertEquals(2, numberOfMaterials);
        assertEquals(5, materials.getJsonObject(0).getInt("documentType"));
        assertEquals("1", materials.getJsonObject(0).getString("fileType"));
        assertNotNull(jsonObject.getString("submissionId"));
        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
        assertNull(migratedCaseJsonObject.getJsonObject("migrationSourceSystem"));
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadWhenMigratedCaseSubmissionPayloadEmptyAndMigrationSourceSystemIsEmpty() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                emptyPayload(),
                List.of("batch-0001/test.pdf", "batch-0001/test1.pdf"),
                buildMetaDataWithMigrationSourceSystemIsEmpty(), submissionId, "batch-0001");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals(2, materials.size());
        assertEquals(2, numberOfMaterials);
        assertEquals(5, materials.getJsonObject(0).getInt("documentType"));
        assertEquals("1", materials.getJsonObject(0).getString("fileType"));
        assertNotNull(jsonObject.getString("submissionId"));
        assertNull(migratedCaseJsonObject.getJsonObject("migratedCase"));
        assertNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNull(migratedCaseJsonObject.getJsonArray("hearings"));
        assertNull(migratedCaseJsonObject.getJsonObject("migratedCase"));
        assertNull(migratedCaseJsonObject.getJsonObject("migrationSourceSystem"));

    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadWhenMetadataIsEmpty() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                minimalCaseInput(),
                List.of(),
                emptyPayload(), submissionId, "batch-0001");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertNull(materials);
        assertEquals(0, numberOfMaterials);
        assertNotNull(jsonObject.getString("submissionId"));
        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
        assertNull(migratedCaseJsonObject.getJsonObject("migrationSourceSystem"));
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadWhenMigratedCaseSubmissionPayloadEmpty() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                emptyPayload(),
                List.of("batch-0001/test.pdf", "batch-0001/test1.pdf"),
                manifestWithMaterials(), submissionId, "batch-0001");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals("XHIBIT", migrationSourceSystem.getString("migrationSourceSystemName"));
        assertNotNull(migrationSourceSystem.getString("migrationSourceSystemCaseIdentifier"));
        assertEquals(2, materials.size());
        assertEquals(2, numberOfMaterials);
        assertEquals(5, materials.getJsonObject(0).getInt("documentType"));
        assertEquals("1", materials.getJsonObject(0).getString("fileType"));
        assertNotNull(jsonObject.getString("submissionId"));
        assertNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNull(migratedCaseJsonObject.getJsonArray("hearings"));
    }

    /**
     * Regression test for the unguarded copies that used to be in
     * {@code StagingDlrmCommandHelper.buildMigratedCaseJsonBuilder}: {@code hearings} and
     * {@code defendants} are optional in the func app's own {@code migrated-case.json} (and in the
     * LIBRA gate too — only {@code caseDetails} is unconditionally required by both), but both used
     * to be handed to {@code JsonObjectBuilder.add} without the {@code nonNull} guard
     * {@code migrationSourceSystem} gets three lines below. A gate-valid case file omitting either
     * threw {@code NullPointerException} in production (Azure Functions worker stack trace); now
     * fixed to omit the absent arrays instead, matching {@code expected-no-hearings.json}.
     */
    @Test
    void shouldAssemblePayloadWhenHearingsAndDefendantsAreAbsent() {

        final JsonObject payload = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                readJson(fixture(FIXTURES + "case-no-hearings.json")),
                List.of(),
                manifestWithoutMaterials(),
                FIXED_SUBMISSION_ID, LOCATION);

        assertThat(asString(payload), matchesWholePayload(
                expected("expected-no-hearings.json"),
                List.of("metadata.id")));      // UUID.randomUUID() in the assembler
    }

    @Test
    void shouldGenerateErrorMigratedCaseSubmissionPayloadSuccessfully() {
        final String submissionId = UUID.randomUUID().toString();
        final String responseString = fixture(FIXTURES + "error-response.json");
        final JsonObject errorMigratedCaseSubmissionPayload = stagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload(
                minimalCaseInput(),
                submissionId, "batch-0001", "batch-0001", responseString);

        assertEquals(submissionId, errorMigratedCaseSubmissionPayload.getString("submissionId"));
        assertNotNull(errorMigratedCaseSubmissionPayload.getString("payload"));
    }

    @Test
    void shouldSendPostCommandApiSuccessfully() {

        final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.add("Content-type", "application/json");
        headers.add("CJSCPPUID", stagingDlrmUserId);

        when(context.getLogger())
                .thenReturn(Logger.getLogger(StagingDlrmCommandHelper.class.getName()));
        when(client.target(anyString()).request().headers(headers).post(any()))
                .thenReturn(response);
        when(response.getStatus()).thenReturn(202);

        try (Response response = stagingDlrmCommandHelper.sendPostCommandApi(
                "http://localhost:8080" + MIGRATED_CASE_SUBMISSION_PATH,
                minimalCaseInput(),
                "application/vnd.stagingdlrm.receive-migrated-case-submission+json",
                stagingDlrmUserId,
                UUID.randomUUID().toString())) {

            assertEquals(202, response.getStatus());
        }
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadWhenMaterialIsEmpty() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                minimalCaseInput(),
                List.of(),
                manifestWithMaterials(), submissionId, "batch-0001");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals("XHIBIT", migrationSourceSystem.getString("migrationSourceSystemName"));
        assertNotNull(migrationSourceSystem.getString("migrationSourceSystemCaseIdentifier"));
        assertEquals(2, materials.size());
        assertEquals(2, numberOfMaterials);
        assertEquals(5, materials.getJsonObject(0).getInt("documentType"));
        assertEquals("1", materials.getJsonObject(0).getString("fileType"));
        assertNotNull(jsonObject.getString("submissionId"));
        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
        assertNull(materials.getJsonObject(0).get("azureLocation"));
        assertNull(materials.getJsonObject(0).get("fileName"));
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionMetaDataPayloadWhenFileTypeIsMissing() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                minimalCaseInput(),
                List.of("batch-0001/test.pdf", "batch-0001/test1.pdf"),
                buildMetaDataPayloadWhenFileTypeIsMissing(), submissionId, "batch-0001");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals("XHIBIT", migrationSourceSystem.getString("migrationSourceSystemName"));
        assertNotNull(migrationSourceSystem.getString("migrationSourceSystemCaseIdentifier"));
        assertEquals(2, materials.size());
        assertEquals(2, numberOfMaterials);
        assertNull(materials.getJsonObject(0).get("documentType"));
        assertNull(materials.getJsonObject(0).get("fileType"));
        assertNotNull(jsonObject.getString("submissionId"));
        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
    }



    private JsonObject emptyPayload() {
        return readJson(emptyJson());
    }

    private JsonObject manifestWithMaterials() {
        return manifest("manifest-with-materials.json");
    }

    private JsonObject manifestWithoutMaterials() {
        return manifest("manifest-without-materials.json");
    }

    private JsonObject buildMetaDataPayloadWhenFileTypeIsMissing() {
        return manifest("manifest-without-file-type.json");
    }

    private JsonObject buildMetaDataWithMigrationSourceSystemIsEmpty() {
        return readJson(fixture(FIXTURES + "manifest-without-source-system.json"));
    }
}