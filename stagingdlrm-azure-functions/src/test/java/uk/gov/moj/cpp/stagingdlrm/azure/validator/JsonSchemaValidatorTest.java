package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.microsoft.azure.functions.ExecutionContext;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The tests below FR6/DLRM-01 (see docs/j25-parity-checklist.md and
 * docs/pipeline/adrs/DD-43191-j25-parity-method.md decision 6) pin the Function App gate's
 * observed J17 parse behaviour ahead of the Jackson 2.12.7&rarr;2.21.4 bump behind
 * {@link JsonSchemaValidator}'s {@code ObjectMapper.readTree}. The gate is not source-system-keyed
 * on {@code team/25.104.x} (parity-method ADR decision 7), so these run against whichever source
 * system a fixture happens to use.
 */
@ExtendWith(MockitoExtension.class)
class JsonSchemaValidatorTest {

    @Mock
    private ExecutionContext context;

    private JsonSchemaValidator caseValidator;

    private JsonSchemaValidator manifestValidator;

    @BeforeEach
    public void setup() {
        // Only the failure-path tests (malformed JSON / array payload) exercise the logger, via
        // LoggerHelper.logSevere(context, ...); lenient() keeps Mockito's strict-stubs check
        // from flagging this as unused on the happy-path tests.
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

    // ------------------------------------------------------------------
    // DLRM-01 - Jackson-tier numeric-literal table (FR6, FR7).
    //
    // Separate from BC-13's everit/org.json table (stagingdlrm-domain-value-schema) per FR7 - a
    // shared table would hide which tier moved. Target field: manifest.files[0].documentType
    // ("type": "integer", no "maximum" - stagingdlrm.manifest.json), so this table's "accepted"
    // set differs from BC-13's for the same seven literals: BC-13 rejects the oversized integer
    // on "type" (BigInteger != Integer) before any bound is checked; here, with no "maximum"
    // configured, a JSON-schema-legal integer of any size is accepted once it parses.
    //
    // Observed on J17 (Jackson 2.12.7 + networknt json-schema-validator 1.0.83), 2026-09-04:
    //   0                      -> readTree OK (IntNode)                    -> ACCEPTED
    //   007                    -> JsonProcessingException: leading zeroes not allowed -> PARSE FAILURE
    //   01                     -> JsonProcessingException: leading zeroes not allowed -> PARSE FAILURE
    //   .5                     -> JsonProcessingException: unexpected character '.'   -> PARSE FAILURE
    //   10.0                   -> readTree OK (DoubleNode)  -> schema REJECTS ("number found, integer expected")
    //   1e3                    -> readTree OK (DoubleNode)  -> schema REJECTS ("number found, integer expected")
    //   12345678901234567890   -> readTree OK (BigIntegerNode) -> ACCEPTED (no "maximum" bound on documentType)
    //
    // This is the opposite of BC-13's outcome for the same oversized-integer literal, which is
    // exactly the "two tiers, two tables" split the parity-method ADR requires - see
    // docs/j25-parity-checklist.md.
    // ------------------------------------------------------------------

    private enum DlrmOutcome { PARSE_FAILURE, SCHEMA_REJECTED, ACCEPTED }

    private static Stream<Object[]> dlrm01NumericLiterals() {
        return Stream.of(
                new Object[]{"0", DlrmOutcome.ACCEPTED},
                new Object[]{"007", DlrmOutcome.PARSE_FAILURE},
                new Object[]{"01", DlrmOutcome.PARSE_FAILURE},
                new Object[]{".5", DlrmOutcome.PARSE_FAILURE},
                new Object[]{"10.0", DlrmOutcome.SCHEMA_REJECTED},
                new Object[]{"1e3", DlrmOutcome.SCHEMA_REJECTED},
                new Object[]{"12345678901234567890", DlrmOutcome.ACCEPTED}
        );
    }

    @ParameterizedTest(name = "DLRM-01 numeric literal \"{0}\"")
    @MethodSource("dlrm01NumericLiterals")
    void dlrm01NumericLiteralTable(final String literal, final DlrmOutcome outcome) {
        final String payload = manifestPayloadWithDocumentType(literal);

        switch (outcome) {
            case PARSE_FAILURE:
                // JsonSchemaValidator.validate() throws a bare RuntimeException for both the
                // array-payload rejection and a wrapped JsonProcessingException - checking the
                // cause type, not just RuntimeException, is what actually distinguishes "this
                // literal failed to parse" from "this payload was rejected for some other reason".
                final RuntimeException exception = assertThrows(RuntimeException.class,
                        () -> manifestValidator.validate(UUID.randomUUID().toString(), payload));
                assertTrue(exception.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException);
                break;
            case SCHEMA_REJECTED:
                assertEquals(1, manifestValidator.validate(UUID.randomUUID().toString(), payload).size());
                break;
            case ACCEPTED:
                assertEquals(0, manifestValidator.validate(UUID.randomUUID().toString(), payload).size());
                break;
            default:
                throw new IllegalStateException("Unhandled outcome: " + outcome);
        }
    }

    private static String manifestPayloadWithDocumentType(final String documentTypeLiteral) {
        return """
                {
                      "files" : [ {
                        "fileName" : "WitnessStatementDocument_1.pdf",
                        "fileType" : "8",
                        "documentType" : %s
                      } ],
                      "migrationSourceSystem" : {
                        "migrationSourceSystemName" : "XHIBIT",
                        "migrationSourceSystemCaseIdentifier" : "XHIBIT-HEARINGS"
                      }
                    }
                """.formatted(documentTypeLiteral);
    }

    // ------------------------------------------------------------------
    // FR6 - malformed JSON, and the array-payload rejection the validator performs before
    // schema validation.
    // ------------------------------------------------------------------

    @Test
    void malformedJsonIsAParseFailure() {
        // Unterminated object - Jackson's tokenizer must reject this before any schema
        // validation is attempted.
        final String malformed = "{ \"migrationSourceSystem\": ";

        final RuntimeException exception = assertThrows(RuntimeException.class,
                () -> manifestValidator.validate(UUID.randomUUID().toString(), malformed));
        // Observed on J17: Jackson reports early truncation as JsonEOFException, a
        // JsonParseException subtype - JsonSchemaValidator's catch (JsonProcessingException)
        // wraps it as the RuntimeException's cause without rethrowing it directly.
        assertTrue(exception.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException);
    }

    @Test
    void arrayPayloadIsRejectedBeforeSchemaValidation() {
        // JsonSchemaValidator.validate() checks jsonNode.isArray() itself, ahead of - and
        // independently of - whatever the loaded schema's own "type" constraint would say.
        final String arrayPayload = "[ { \"migrationSourceSystem\": {} } ]";

        final RuntimeException exception = assertThrows(RuntimeException.class,
                () -> manifestValidator.validate(UUID.randomUUID().toString(), arrayPayload));
        assertEquals("Json Schema validation failed", exception.getMessage());
    }

    @Test
    void duplicateObjectKeysResolveToTheLastValueWritten() throws Exception {
        // Jackson's default JsonNodeFactory applies last-value-wins for duplicate object keys -
        // no DeserializationFeature/JsonParser.Feature is configured anywhere in this module to
        // change that. documentType is legitimately duplicated here to observe which value wins.
        final String payload = """
                {
                      "files" : [ {
                        "fileName" : "WitnessStatementDocument_1.pdf",
                        "fileType" : "8",
                        "documentType" : 1,
                        "documentType" : 2
                      } ],
                      "migrationSourceSystem" : {
                        "migrationSourceSystemName" : "XHIBIT",
                        "migrationSourceSystemCaseIdentifier" : "XHIBIT-HEARINGS"
                      }
                    }
                """;

        final Set<ValidationMessage> validationMessages = manifestValidator.validate(UUID.randomUUID().toString(), payload);
        assertEquals(0, validationMessages.size());

        // JsonSchemaValidator itself only reports pass/fail; observe the parsed value directly
        // (same ObjectMapper defaults - no configuration - as JsonSchemaValidator uses) to pin
        // *which* value won, not just that the result still validates.
        final com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        assertEquals(2, node.at("/files/0/documentType").asInt());
    }
}