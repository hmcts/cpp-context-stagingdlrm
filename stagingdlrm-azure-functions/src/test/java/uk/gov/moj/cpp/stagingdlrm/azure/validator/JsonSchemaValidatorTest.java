package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.lenient;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.ExecutionContext;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JsonSchemaValidatorTest {

    @Mock
    private ExecutionContext context;

    private JsonSchemaValidator caseValidator;

    private JsonSchemaValidator manifestValidator;

    @BeforeEach
    public void setup() {
        // lenient(): only the parse-failure/array-rejection paths (added by the DLRM-01 parity tests
        // below) actually call context.getLogger() - stubbing it here once, leniently, avoids repeating
        // the stub in every one of those tests without tripping strict-stubbing on the tests that don't.
        lenient().when(context.getLogger()).thenReturn(Logger.getLogger(JsonSchemaValidatorTest.class.getName()));

        final String caseJsonSchema = "stagingdlrm.case-submission.json";
        caseValidator = new JsonSchemaValidator(context, caseJsonSchema);

        final String manifestJsonSchema = "stagingdlrm.manifest.json";
        manifestValidator = new JsonSchemaValidator(context, manifestJsonSchema);
    }

    @Test
    void validatePayloadSuccessfully() {

        String payload = """
                {
                    "migratedCase": {
                      "caseDetails": {
                        "originatingOrganisation": "G94DV00",
                        "summonsCode": "summonsCode",
                        "initiationCode": "O",
                        "prosecutorCaseReference": "TVL55117DFXXV",
                        "dateReceived": "2024-01-15",
                        "prosecutor": {
                          "prosecutingAuthority": "GAEAA01"
                        },
                        "sendingCourt": "C50EX00",
                        "receivingCourt": "B01LY00",
                        "dateOfSending": "2024-08-23",
                        "dateOfCommittal": "2024-01-15",
                        "receiptType": "Commital for sentence",
                        "retrialIndicator": false
                      },
                      "hearings": [
                        {
                          "courtHearingLocation": "B01LY01",
                          "dateOfHearing": "2024-11-10",
                          "timeOfHearing": "10:05:01",
                          "durationMinutes": 60,
                          "courtRoomId": 60,
                          "hearingType": "",
                          "listedDefendants": [
                            {
                              "prosecutorDefendantId": "LIBRA-defendant-id-1",
                              "listedOffences": [
                                "LIBRA-offence-id-1"
                              ]
                            },
                            {
                              "prosecutorDefendantId": "LIBRA-defendant-id-2",
                              "listedOffences": [
                                "LIBRA-offence-id-2"
                              ]
                            }
                          ]
                        }
                      ],
                      "defendants": [
                        {
                          "prosecutorDefendantId": "LIBRA-defendant-id-1",
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
                              "ethnicity": "W1",
                              "gender": 1
                            }
                          },
                          "offences": [
                            {
                              "prosecutorOffenceId": "LIBRA-offence-id-1",
                              "offenceCode": "CA03013",
                              "offenceSequenceNumber": 1,
                              "offenceCommittedDate": "2018-09-10",
                              "offenceDateCode": 1,
                              "offenceLocation": "Croydon",
                              "offenceWording": "TV Licence not paid",
                              "chargeDate": "2015-04-04",
                              "laidDate": "2015-04-04"
                            }
                          ],
                          "postingDate": "2018-09-10"
                        },
                        {
                          "prosecutorDefendantId": "LIBRA-defendant-id-2",
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
                              "ethnicity": "W1",
                              "gender": 2
                            }
                          },
                          "offences": [
                            {
                              "prosecutorOffenceId": "LIBRA-offence-id-2",
                              "offenceCode": "DA03014",
                              "offenceSequenceNumber": 1,
                              "offenceCommittedDate": "2018-09-10",
                              "offenceDateCode": 1,
                              "offenceLocation": "Croydon",
                              "offenceWording": "TV Licence not paid",
                              "chargeDate": "2015-04-04"
                            }
                          ],
                          "postingDate": "2018-09-10"
                        }
                      ],
                      "migrationSourceSystem": {
                        "migrationSourceSystemName": "LIBRA",
                        "migrationSourceSystemCaseIdentifier": "This is from Libra"
                      }
                    }
                  }
                """;

        final Set<ValidationMessage> validationMessages = caseValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(0, validationMessages.size());

    }

    @Test
    void validateEmptyCasePayload() {
        String payload = """
                {
                   }
                """;

        final Set<ValidationMessage> validationMessages = caseValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(1, validationMessages.size());
    }

    @Test
    void validateEmptyManifestPayload() {
        String payload = """
                {
                   }
                """;

        final Set<ValidationMessage> validationMessages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(1, validationMessages.size());
    }

    @Test
    void validateManifestPayloadWithFiles() {
        String payload = """
                {
                      "files" : [ {
                        "fileName" : "WitnessStatementDocument_1.pdf",
                        "fileType" : "8",
                        "documentType" : 1
                      }, {
                        "fileName" : "WitnessStatementDocument_2.pdf",
                        "fileType" : "8",
                        "documentType" : 1
                      } ],
                      "migrationSourceSystem" : {
                        "migrationSourceSystemName" : "XHIBIT",
                        "migrationSourceSystemCaseIdentifier" : "XHIBIT-HEARINGS"
                      }
                    }
                """;

        final Set<ValidationMessage> validationMessages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(0, validationMessages.size());
    }

    @Test
    void validateManifestPayloadWithoutFiles() {
        String payload = """
                {
                      "migrationSourceSystem" : {
                        "migrationSourceSystemName" : "XHIBIT",
                        "migrationSourceSystemCaseIdentifier" : "XHIBIT-HEARINGS"
                      }
                    }
                """;

        final Set<ValidationMessage> validationMessages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(0, validationMessages.size());
    }

    // -- DLRM-01 parity tests (see docs/j25-parity-checklist.md) ------------------------------------
    // Pins the Jackson ObjectMapper.readTree parse behaviour (2.12.7->2.21.4) behind this gate - the
    // Function App's own hard-pinned com.networknt:json-schema-validator:1.0.83 does not move on J25;
    // its exposure is entirely in the Jackson parse step that runs before that validator ever sees the
    // payload. This is a separate table over a separate parser from BC-13's everit/org.json tier
    // (Bc13SchemaValidationParityTest, stagingdlrm-domain-value-schema) - per FR7, a shared table would
    // hide which tier actually moved.

    @Test
    void validateMalformedJsonPayloadFailsToParse() {
        final String payload = "{ \"migratedCase\": ";

        final RuntimeException exception = assertThrows(RuntimeException.class,
                () -> caseValidator.validate(UUID.randomUUID().toString(), payload),
                "DLRM-01 parity test: syntactically invalid JSON must fail during Jackson's own parse on J17");
        assertInstanceOf(JsonProcessingException.class, exception.getCause(),
                "DLRM-01 parity test: the wrapped cause must be Jackson's own parse exception, not some other failure");
    }

    @Test
    void validateArrayPayloadIsRejectedBeforeSchemaValidationRuns() {
        final String payload = "[]";

        final RuntimeException exception = assertThrows(RuntimeException.class,
                () -> caseValidator.validate(UUID.randomUUID().toString(), payload),
                "DLRM-01 parity test: an array payload must be rejected by this gate's own explicit check, before com.networknt ever runs");
        assertEquals("Json Schema validation failed", exception.getMessage(),
                "DLRM-01 parity test: pin the exact message this gate's array-payload guard throws");
    }

    @Test
    void validateDuplicateObjectKeyResolvesToTheLastValueSilently() {
        // documentType is declared twice in the same object: first a value that would fail the schema's
        // "type": "integer" check, then a valid integer. Jackson's default readTree keeps the LAST
        // occurrence with no exception - if it kept the first instead, this payload would fail
        // validation on a type mismatch rather than pass with zero messages.
        final String payload = """
                {
                      "files" : [ {
                        "fileName" : "WitnessStatementDocument_1.pdf",
                        "fileType" : "8",
                        "documentType" : "not-an-integer",
                        "documentType" : 5
                      } ],
                      "migrationSourceSystem" : {
                        "migrationSourceSystemName" : "XHIBIT",
                        "migrationSourceSystemCaseIdentifier" : "XHIBIT-HEARINGS"
                      }
                    }
                """;

        final Set<ValidationMessage> validationMessages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(0, validationMessages.size(),
                "DLRM-01 parity test: Jackson must resolve the duplicate documentType key to its LAST (valid) value on J17");
    }

    /**
     * Numeric-literal table for the manifest schema's {@code documentType} ({@code "type": "integer"},
     * <b>no</b> {@code maximum} - confirmed on disk, unlike BC-13's {@code durationMinutes}). Each
     * literal has a named expected outcome, per FR5/AC3 - and several of these outcomes are the
     * <i>opposite</i> of BC-13's table for the identical literal, which is exactly why FR7 forbids a
     * single shared table: {@code 007}/{@code 01}/{@code .5} are ACCEPTed by org.json's lenient parse
     * at the BC-13 tier but FAIL TO PARSE ENTIRELY here - Jackson's default {@code ObjectMapper}
     * rejects a leading zero and a bare-decimal literal as invalid JSON syntax, not as a schema
     * rejection. Observed on J17, 2026-09-04.
     */
    @Test
    void pinsTheNumericLiteralTableForDocumentType() {
        assertParseFails("007", "Jackson's default ObjectMapper rejects a leading-zero integer literal as invalid JSON syntax");
        assertParseFails("01", "Jackson's default ObjectMapper rejects a leading-zero integer literal as invalid JSON syntax");
        assertParseFails(".5", "Jackson's default ObjectMapper rejects a bare-decimal literal (no leading digit) as invalid JSON syntax");

        assertAccepted("0", "an ordinary integer literal parses as IntNode and satisfies \"type\": \"integer\"");
        assertAccepted("12345678901234567890", "documentType has no maximum configured, so an oversized-but-integral literal (BigIntegerNode) is accepted");

        assertRejected("10.0", "Jackson parses a decimal-point literal as a non-integral DoubleNode even though it is mathematically whole - com.networknt reports \"number found, integer expected\"");
        assertRejected("1e3", "Jackson parses scientific notation as a non-integral DoubleNode - com.networknt reports \"number found, integer expected\"");
    }

    private void assertParseFails(final String numericLiteral, final String reason) {
        final String payload = manifestPayloadWithDocumentType(numericLiteral);
        final RuntimeException exception = assertThrows(RuntimeException.class,
                () -> manifestValidator.validate(UUID.randomUUID().toString(), payload),
                "DLRM-01 parity test: literal '" + numericLiteral + "' was expected to FAIL TO PARSE on J17 (" + reason + ")");
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    private void assertAccepted(final String numericLiteral, final String reason) {
        final String payload = manifestPayloadWithDocumentType(numericLiteral);
        final Set<ValidationMessage> messages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        if (!messages.isEmpty()) {
            fail("DLRM-01 parity test: literal '" + numericLiteral + "' was expected to be ACCEPTED on J17 (" + reason
                    + ") but was rejected: " + messages);
        }
    }

    private void assertRejected(final String numericLiteral, final String reason) {
        final String payload = manifestPayloadWithDocumentType(numericLiteral);
        final Set<ValidationMessage> messages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        if (messages.isEmpty()) {
            fail("DLRM-01 parity test: literal '" + numericLiteral + "' was expected to be REJECTED on J17 (" + reason + ") but validated cleanly");
        }
    }

    private static String manifestPayloadWithDocumentType(final String documentTypeLiteral) {
        return "{"
                + "\"files\": [ { \"fileName\": \"f.pdf\", \"fileType\": \"8\", \"documentType\": " + documentTypeLiteral + " } ],"
                + "\"migrationSourceSystem\": { \"migrationSourceSystemName\": \"XHIBIT\", \"migrationSourceSystemCaseIdentifier\": \"id\" }"
                + "}";
    }
}