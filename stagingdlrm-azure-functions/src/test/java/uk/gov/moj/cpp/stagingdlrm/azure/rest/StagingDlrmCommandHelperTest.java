package uk.gov.moj.cpp.stagingdlrm.azure.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static uk.gov.justice.services.test.utils.common.reflection.ReflectionUtils.setField;
import static uk.gov.moj.cpp.stagingdlrm.azure.TimerTriggerJava.MIGRATED_CASE_SUBMISSION_PATH;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import uk.gov.justice.services.messaging.JsonObjects;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
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

    private final Logger logger = Logger.getLogger(StagingDlrmCommandHelper.class.getName());

    @BeforeEach
    public void setup() {
        when(context.getLogger()).thenReturn(logger);
        setField(stagingDlrmCommandHelper, "client", client);
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadSuccessfully() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                buildDefaultMigratedCaseSubmissionPayload(),
                List.of("28DI9534352/test.pdf", "28DI9534352/test1.pdf"),
                buildDefaultMetaDataPayload(), submissionId, "28DI9534352");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals("LIBRA", migrationSourceSystem.getString("migrationSourceSystemName"));
        assertNotNull(migrationSourceSystem.getString("migrationSourceSystemCaseIdentifier"));
        assertEquals(2, materials.size());
        assertEquals(2, numberOfMaterials);
        assertEquals(5, materials.getJsonObject(0).getInt("documentType"));
        assertEquals("1", materials.getJsonObject(0).getString("fileType"));
        assertNotNull(jsonObject.getString("submissionId"));
        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadSuccessfullyWhenNoMaterialsAreAttached() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject migratedCaseSubmissionJsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                buildDefaultMigratedCaseSubmissionPayload(),
                List.of(),
                buildMetaDataPayloadWithoutMaterials(), submissionId, "28DI9534352");

        final JsonArray materials = migratedCaseSubmissionJsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = migratedCaseSubmissionJsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = migratedCaseSubmissionJsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals("LIBRA", migrationSourceSystem.getString("migrationSourceSystemName"));
        assertNotNull(migrationSourceSystem.getString("migrationSourceSystemCaseIdentifier"));
        assertNull(materials);
        assertEquals(0, numberOfMaterials);
        assertNotNull(migratedCaseSubmissionJsonObject.getString("submissionId"));
        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
    }

    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadWithSubmissionIdPassedSuccessfully() {

        final String migrationSourceSystemName = "XHIBIT";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String queueMessage = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String path = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String testFile = path + "/test.pdf";
        final String test1File = path + "/test1.pdf";

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                buildDefaultMigratedCaseSubmissionPayload(),
                List.of(testFile, test1File),
                buildXHIBITMetaDataPayload(), submissionId, queueMessage);

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals(migrationSourceSystemName, migrationSourceSystem.getString("migrationSourceSystemName"));
        assertNotNull(migrationSourceSystem.getString("migrationSourceSystemCaseIdentifier"));
        assertEquals(2, materials.size());
        assertEquals(2, numberOfMaterials);
        assertEquals(5, materials.getJsonObject(0).getInt("documentType"));
        assertEquals("1", materials.getJsonObject(0).getString("fileType"));
        assertEquals(testFile, materials.getJsonObject(0).getString("azureLocation"));
        assertEquals(test1File, materials.getJsonObject(1).getString("azureLocation"));

        assertNotNull(jsonObject.getString("submissionId"));
        assertEquals(submissionId, jsonObject.getString("submissionId"));
        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("defendants"));
        assertNotNull(migratedCaseJsonObject.getJsonArray("hearings"));
    }

    @Test
    void shouldGenerateMigratedCaseWhenFileExistInBlobButNotInMetadataFile() {

        final String migrationSourceSystemName = "LIBRA";

        final String batchIdentifier = "20082025";

        final String migrationSourceSystemCaseIdentifier = UUID.randomUUID().toString();

        final String submissionId = UUID.randomUUID().toString();

        final String queueMessage = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String path = "%s/%s/%s/%s".formatted(migrationSourceSystemName, batchIdentifier, migrationSourceSystemCaseIdentifier, submissionId);

        final String testFile = path + "/test.pdf";
        final String test1File = path + "/test1.pdf";

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                buildDefaultMigratedCaseSubmissionPayload(),
                List.of(testFile, test1File),
                buildMetaDataPayloadWithoutMaterials(), submissionId, queueMessage);

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
                buildDefaultMigratedCaseSubmissionPayload(),
                List.of("28DI9534352/test.pdf", "28DI9534352/test1.pdf"),
                buildMetaDataWithMigrationSourceSystemIsEmpty(), submissionId, "28DI9534352");

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
                buildMigratedCaseSubmissionPayloadEmpty(),
                List.of("28DI9534352/test.pdf", "28DI9534352/test1.pdf"),
                buildMetaDataWithMigrationSourceSystemIsEmpty(), submissionId, "28DI9534352");

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
                buildDefaultMigratedCaseSubmissionPayload(),
                List.of(),
                buildMetaDataIsEmpty(), submissionId, "28DI9534352");

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
                buildMigratedCaseSubmissionPayloadEmpty(),
                List.of("28DI9534352/test.pdf", "28DI9534352/test1.pdf"),
                buildDefaultMetaDataPayload(), submissionId, "28DI9534352");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals("LIBRA", migrationSourceSystem.getString("migrationSourceSystemName"));
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
     * Regression test for {@code StagingDlrmCommandHelper.buildMigratedCaseJsonBuilder}: {@code hearings}
     * and {@code defendants} are optional in a gate-valid migrated case (only {@code caseDetails} is
     * unconditionally required), but both used to be handed to {@code JsonObjectBuilder.add} without the
     * {@code nonNull} guard {@code migrationSourceSystem} gets. A case omitting either threw
     * {@code NullPointerException}; now the assembler simply omits the absent keys.
     */
    @Test
    void shouldGenerateMigratedCaseSubmissionPayloadWhenHearingsAndDefendantsAreAbsent() {

        final String submissionId = UUID.randomUUID().toString();

        final JsonObject jsonObject = stagingDlrmCommandHelper.generateMigratedCaseSubmissionPayload(
                buildMigratedCaseSubmissionPayloadWithoutHearingsAndDefendants(),
                List.of(),
                buildDefaultMetaDataPayload(), submissionId, "TEST-LOCATION");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        assertNotNull(migratedCaseJsonObject.getJsonObject("caseDetails"));
        assertNull(migratedCaseJsonObject.getJsonArray("hearings"));
        assertNull(migratedCaseJsonObject.getJsonArray("defendants"));
    }

    @Test
    void shouldGenerateErrorMigratedCaseSubmissionPayloadSuccessfully() {
        final String submissionId = UUID.randomUUID().toString();
        final String responseString = """
                {"error":"Some Description"}
                """;
        final JsonObject errorMigratedCaseSubmissionPayload = stagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload(
                buildDefaultMigratedCaseSubmissionPayload(),
                submissionId, "28DI9534352", "28DI9534352", responseString);

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
                buildDefaultMigratedCaseSubmissionPayload(),
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
                buildDefaultMigratedCaseSubmissionPayload(),
                List.of(),
                buildDefaultMetaDataPayload(), submissionId, "28DI9534352");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals("LIBRA", migrationSourceSystem.getString("migrationSourceSystemName"));
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
                buildDefaultMigratedCaseSubmissionPayload(),
                List.of("28DI9534352/test.pdf", "28DI9534352/test1.pdf"),
                buildMetaDataPayloadWhenFileTypeIsMissing(), submissionId, "28DI9534352");

        final JsonArray materials = jsonObject.getJsonArray("materials");

        final JsonObject migratedCaseJsonObject = jsonObject.getJsonObject("migratedCase");

        final JsonObject migrationSourceSystem = migratedCaseJsonObject.getJsonObject("migrationSourceSystem");

        final int numberOfMaterials = jsonObject.getJsonObject("metadata").getInt("numberOfMaterials");

        assertEquals("LIBRA", migrationSourceSystem.getString("migrationSourceSystemName"));
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



    private JsonObject buildDefaultMigratedCaseSubmissionPayload() {
        String caseSubmissionJson = """
                {
                  "migratedCase": {
                    "caseDetails": {
                      "originatingOrganisation": "cps",
                      "summonsCode": "summonsCode",
                      "caseId": "51cac7fb-387c-4d19-9c80-8963fa8cf222",
                      "initiationCode": "H",
                      "prosecutorCaseReference": "TVL55117DFXXV",
                      "prosecutor": {
                        "prosecutingAuthority": "GAEAA01"
                      }
                    },
                    "hearings": [
                      {
                        "courtHearingLocation": "C50EX01",
                        "courtRoom": "01",
                        "dateOfHearing": "2024-11-20",
                        "timeOfHearing": "10:05:01.001",
                        "durationMinutes": 120,
                        "weekCommencingDate": "",
                        "hearingType": "FHG",
                        "listedDefendants": [
                          "a9860e1a-8695-4fd4-8046-c1c4fe6c7f80"
                        ]
                      }
                    ],
                    "defendants": [
                      {
                        "documentationLanguage": "W",
                        "hearingLanguage": "W",
                        "individual": {
                          "personalInformation": {
                            "title": "Baron",
                            "forename": "John",
                            "surname": "Smith",
                            "address": {
                              "address1": "armagaddon house",
                              "postcode": "M60 1NW"
                            },
                            "contactDetails": {}
                          },
                          "selfDefinedInformation": {
                            "ethnicity": "white",
                            "gender": "MALE"
                          }
                        },
                        "offences": [
                          {
                            "offenceCode": "CA03013",
                            "offenceSequenceNumber": 1,
                            "offenceCommittedDate": "2018-09-10",
                            "offenceDateCode": 1,
                            "offenceLocation": "Croydon",
                            "offenceWording": "TV Licence not paid",
                            "chargeDate": "2015-04-04"
                          }
                        ]
                      },
                      {
                        "documentationLanguage": "W",
                        "hearingLanguage": "W",
                        "individual": {
                          "personalInformation": {
                            "title": "Baron",
                            "forename": "John",
                            "surname": "Smith",
                            "address": {
                              "address1": "armagaddon house",
                              "postcode": "M60 1NW"
                            },
                            "contactDetails": {}
                          },
                          "selfDefinedInformation": {
                            "ethnicity": "white",
                            "gender": "MALE"
                          }
                        },
                        "offences": [
                          {
                            "offenceCode": "DA03014",
                            "offenceSequenceNumber": 1,
                            "offenceCommittedDate": "2018-09-10",
                            "offenceDateCode": 1,
                            "offenceLocation": "Croydon",
                            "offenceWording": "TV Licence not paid",
                            "chargeDate": "2015-04-04"
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(caseSubmissionJson.getBytes());
        final JsonReader reader = JsonObjects.createReader(byteArrayInputStream);
        return reader.readObject();
    }

    private JsonObject buildMigratedCaseSubmissionPayloadEmpty() {
        String caseSubmissionJson = """
                {}
                """;

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(caseSubmissionJson.getBytes());
        final JsonReader reader = JsonObjects.createReader(byteArrayInputStream);
        return reader.readObject();
    }

    private JsonObject buildMigratedCaseSubmissionPayloadWithoutHearingsAndDefendants() {
        String caseSubmissionJson = """
                {
                  "migratedCase": {
                    "caseDetails": {
                      "originatingOrganisation": "cps",
                      "summonsCode": "summonsCode",
                      "caseId": "51cac7fb-387c-4d19-9c80-8963fa8cf222",
                      "initiationCode": "H",
                      "prosecutorCaseReference": "TESTREF001",
                      "prosecutor": {
                        "prosecutingAuthority": "GAEAA01"
                      }
                    }
                  }
                }
                """;

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(caseSubmissionJson.getBytes());
        final JsonReader reader = JsonObjects.createReader(byteArrayInputStream);
        return reader.readObject();
    }

    private JsonObject buildDefaultMetaDataPayload() {
        final String manifest = """
                {
                  "files": [
                    {
                      "fileName": "test.pdf",
                      "fileType": "1",
                      "documentType": 5
                    },
                    {
                      "fileName": "test1.pdf",
                      "fileType": "2",
                      "documentType": 1
                    }
                  ],
                   "migrationSourceSystem" : {
                      "migrationSourceSystemName": "LIBRA",
                      "migrationSourceSystemCaseIdentifier": "LIB-1234567890"
 
                    }
                }
                """;
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(manifest.getBytes());
        final JsonReader metadataReader = JsonObjects.createReader(byteArrayInputStream);
        return metadataReader.readObject();
    }

    private JsonObject buildMetaDataPayloadWithoutMaterials() {
        final String manifest = """
                {
                   "migrationSourceSystem" : {
                      "migrationSourceSystemName": "LIBRA",
                      "migrationSourceSystemCaseIdentifier": "LIB-1234567890"
 
                    }
                }
                """;
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(manifest.getBytes());
        final JsonReader metadataReader = JsonObjects.createReader(byteArrayInputStream);
        return metadataReader.readObject();
    }

    private JsonObject buildXHIBITMetaDataPayload() {
        final String manifest = """
                {
                  "files": [
                    {
                      "fileName": "test.pdf",
                      "fileType": "1",
                      "documentType": 5
                    },
                    {
                      "fileName": "test1.pdf",
                      "fileType": "2",
                      "documentType": 1
                    }
                  ],
                   "migrationSourceSystem" : {
                      "migrationSourceSystemName": "XHIBIT",
                      "migrationSourceSystemCaseIdentifier": "XH-1234567890"
 
                    }
                }
                """;
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(manifest.getBytes());
        final JsonReader metadataReader = JsonObjects.createReader(byteArrayInputStream);
        return metadataReader.readObject();
    }

    private JsonObject buildMetaDataPayloadWhenFileTypeIsMissing() {
        final String manifest = """
                {
                  "files": [
                    {
                      "fileName": "test.pdf"
                    },
                    {
                      "fileName": "test1.pdf"
                    }
                  ],
                   "migrationSourceSystem" : {
                      "migrationSourceSystemName": "LIBRA",
                      "migrationSourceSystemCaseIdentifier": "LIB-1234567890"
 
                    }
                }
                """;
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(manifest.getBytes());
        final JsonReader metadataReader = JsonObjects.createReader(byteArrayInputStream);
        return metadataReader.readObject();
    }

    private JsonObject buildMetaDataWithMigrationSourceSystemIsEmpty() {
        final String manifest = """
                {
                  "files": [
                    {
                      "fileName": "test.pdf",
                      "fileType": "1",
                      "documentType": 5
                    },
                    {
                      "fileName": "test1.pdf",
                      "fileType": "2",
                      "documentType": 1
                    }
                  ]
                }
                """;
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(manifest.getBytes());
        final JsonReader metadataReader = JsonObjects.createReader(byteArrayInputStream);
        return metadataReader.readObject();
    }

    private JsonObject buildMetaDataIsEmpty() {
        final String manifest = """
                {
                }
                """;
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(manifest.getBytes());
        final JsonReader metadataReader = JsonObjects.createReader(byteArrayInputStream);
        return metadataReader.readObject();
    }
}