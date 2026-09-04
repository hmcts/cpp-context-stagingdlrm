package uk.gov.moj.cpp.stagingdlrm.schema;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * BC-13 parity test (see docs/pipeline/adrs/DD-43191-j25-parity-method.md decision 6, and
 * docs/j25-parity-checklist.md).
 *
 * <p>Pins the J17 behaviour of the schema-catalogue tier's validation stack -
 * {@code org.json:json:20231013} + {@code com.github.everit-org.json-schema:1.6.0} - for the
 * {@code migrated-case-submission.json} schema set, ahead of the DD-43191 Java 25 upgrade's
 * {@code org.json} 20231013&rarr;20251224 bump. Authored from scratch: neither {@code SchemaMatchers}
 * nor {@code MigratedCaseSubmissionSchemaContractTest}, named in the original requirements, exist on
 * {@code team/25.104.x} (parity-method ADR decision 7) - this module has zero Java on this branch.
 *
 * <p>J17 idiom only: {@code javax}-era dependencies, no {@code jakarta}, no J25-conditional
 * branches - the upgrade story migrates this file like any other source file.
 */
class MigratedCaseSubmissionSchemaParityTest {

    private static final String SCHEMA_ID =
            "http://cpp.moj.gov.uk/stagingdlrm/migrated/json/schemas/migrated-case-submission.json";

    private Schema schema;

    @BeforeEach
    void loadSchema() {
        final JSONObject rawSchema = new JSONObject(new JSONTokener(
                Thread.currentThread().getContextClassLoader().getResourceAsStream("json/schema/migrated/migrated-case-submission.json")));
        this.schema = SchemaLoader.builder()
                .schemaJson(rawSchema)
                .httpClient(new ClasspathSchemaClient())
                .resolutionScope(SCHEMA_ID)
                .build()
                .load()
                .build();
    }

    // ------------------------------------------------------------------
    // FR5.1 - BC-13 numeric-literal table, pinned against the everit/org.json tier.
    //
    // Target field: migratedCase.hearings[0].durationMinutes ("type": "integer",
    // "maximum": 99999 in migrated-hearing.json) - a required field, so every literal below
    // is substituted unquoted into an otherwise-valid payload.
    //
    // Observed on J17 (org.json 20231013 + everit 1.6.0), 2026-09-04 - see
    // docs/j25-parity-checklist.md for how this compares with DLRM-01's Jackson-tier table for
    // the *same* seven literals:
    //   0                      -> org.json parses as Integer 0                      -> ACCEPTED
    //   007                    -> org.json parses as Integer 7 (leading zero dropped)-> ACCEPTED
    //   01                     -> org.json parses as Integer 1 (leading zero dropped)-> ACCEPTED
    //   .5                     -> org.json parses as BigDecimal 0.5                  -> REJECTED (not an integer)
    //   10.0                   -> org.json parses as BigDecimal 10.0                 -> REJECTED (BigDecimal, not Integer, despite being integral)
    //   1e3                    -> org.json parses as BigDecimal 1E+3                 -> REJECTED (not an integer)
    //   12345678901234567890   -> org.json parses as BigInteger                      -> REJECTED (BigInteger, not Integer - fails "type" before "maximum" is even reached)
    // ------------------------------------------------------------------

    private static Stream<NumericLiteralCase> numericLiterals() {
        return Stream.of(
                new NumericLiteralCase("0", Outcome.ACCEPTED, null),
                new NumericLiteralCase("007", Outcome.ACCEPTED, null),
                new NumericLiteralCase("01", Outcome.ACCEPTED, null),
                new NumericLiteralCase(".5", Outcome.REJECTED, "expected type: Integer, found: BigDecimal"),
                new NumericLiteralCase("10.0", Outcome.REJECTED, "expected type: Integer, found: BigDecimal"),
                new NumericLiteralCase("1e3", Outcome.REJECTED, "expected type: Integer, found: BigDecimal"),
                new NumericLiteralCase("12345678901234567890", Outcome.REJECTED, "expected type: Integer, found: BigInteger")
        );
    }

    @ParameterizedTest(name = "BC-13 numeric literal \"{0}\"")
    @MethodSource("numericLiterals")
    void numericLiteralTable(final NumericLiteralCase testCase) {
        final JSONObject payload = new JSONObject(
                new JSONTokener(validPayloadWithDurationMinutesLiteral(testCase.literal)));

        if (testCase.outcome == Outcome.ACCEPTED) {
            schema.validate(payload);
        } else {
            final ValidationException exception = assertThrows(ValidationException.class, () -> schema.validate(payload));
            assertTrue(exception.getMessage().contains(testCase.expectedMessageFragment));
        }
    }

    private enum Outcome { ACCEPTED, REJECTED }

    private static final class NumericLiteralCase {
        private final String literal;
        private final Outcome outcome;
        private final String expectedMessageFragment;

        private NumericLiteralCase(final String literal, final Outcome outcome, final String expectedMessageFragment) {
            this.literal = literal;
            this.outcome = outcome;
            this.expectedMessageFragment = expectedMessageFragment;
        }

        @Override
        public String toString() {
            return literal;
        }
    }

    // ------------------------------------------------------------------
    // FR5.2 - accept path, and the reject path with its validation message, for each
    // constraint class the schema uses: type, enum, required, format, anyOf.
    // ------------------------------------------------------------------

    @Test
    void acceptsAValidMigratedCaseSubmission() {
        final JSONObject payload = new JSONObject(new JSONTokener(validPayload()));

        schema.validate(payload);
    }

    @Test
    void rejectsWrongType() {
        // durationMinutes must be "type": "integer" - a string is a type-constraint violation.
        final JSONObject payload = new JSONObject(new JSONTokener(
                validPayload().replace("\"durationMinutes\": 60", "\"durationMinutes\": \"60\"")));

        final ValidationException exception = assertThrows(ValidationException.class, () -> schema.validate(payload));

        // Observed on J17: a non-Number value fails the broader "expected type: Number" check
        // before the integer-specific "expected type: Integer, found: BigDecimal/BigInteger"
        // message the numeric-literal table above observes for non-integral Number values.
        assertTrue(exception.getMessage().contains("durationMinutes"));
        assertTrue(exception.getMessage().contains("expected type: Number, found: String"));
    }

    @Test
    void rejectsValueOutsideEnum() {
        // migrationSourceSystemName is "enum": ["LIBRA", "XHIBIT"].
        final JSONObject payload = new JSONObject(new JSONTokener(
                validPayload().replace("\"migrationSourceSystemName\": \"LIBRA\"", "\"migrationSourceSystemName\": \"UNKNOWN_SYSTEM\"")));

        final ValidationException exception = assertThrows(ValidationException.class, () -> schema.validate(payload));

        assertTrue(exception.getMessage().contains("UNKNOWN_SYSTEM"));
        assertTrue(exception.getMessage().contains("enum"));
    }

    @Test
    void rejectsMissingRequiredProperty() {
        // caseDetails.prosecutorCaseReference is required.
        final JSONObject payload = new JSONObject(new JSONTokener(
                validPayload().replace("\"prosecutorCaseReference\": \"TVL55117DFXXV\",", "")));

        final ValidationException exception = assertThrows(ValidationException.class, () -> schema.validate(payload));

        // Observed on J17: case-details.json combines plain "required" keywords with an
        // "anyOf" (decision 6's dateOfCommittal/dateOfSending pair), so everit models the whole
        // subschema as a combined (allOf-like) schema. The top-level message here is
        // "only 1 subschema matches out of 2" - the actual required-key violation is nested in a
        // causing exception, which is exactly why getAllMessages() (not getMessage()) is the
        // right assertion surface for this schema shape.
        final String allMessages = String.join(" | ", exception.getAllMessages());
        assertTrue(allMessages.contains("prosecutorCaseReference"));
        assertTrue(allMessages.contains("required"));
    }

    @Test
    void rejectsValueFailingFormatConstraint() {
        // migrated-material.json#receivedDateTime is "format": "date-time".
        final JSONObject payload = new JSONObject(new JSONTokener(
                validPayload().replace("\"receivedDateTime\": \"2024-01-15T10:00:00Z\"", "\"receivedDateTime\": \"not-a-date-time\"")));

        final ValidationException exception = assertThrows(ValidationException.class, () -> schema.validate(payload));

        assertTrue(exception.getMessage().contains("not-a-date-time"));
        assertTrue(exception.getMessage().contains("date-time"));
    }

    @Test
    void rejectsWhenAnyOfIsNotSatisfied() {
        // case-details.json requires dateOfCommittal OR dateOfSending ("anyOf"). The valid
        // fixture supplies dateOfSending only; drop it so neither branch is satisfied.
        final JSONObject payload = new JSONObject(new JSONTokener(
                validPayload().replace("\"dateOfSending\": \"2024-01-20\",", "")));

        final ValidationException exception = assertThrows(ValidationException.class, () -> schema.validate(payload));

        // Same combined-schema shape as rejectsMissingRequiredProperty: the top-level message is
        // "only 1 subschema matches out of 2"; getAllMessages() surfaces both anyOf branches'
        // required-key violations, confirming neither dateOfCommittal nor dateOfSending is present.
        final String allMessages = String.join(" | ", exception.getAllMessages());
        assertTrue(allMessages.contains("dateOfCommittal"));
        assertTrue(allMessages.contains("dateOfSending"));
    }

    // ------------------------------------------------------------------
    // FR5.3 - parse failure vs validation failure are two different, distinctly-observable
    // outcomes. This is where BC-13's "uncaught exception becomes an HTTP 500" shape lands,
    // if it lands anywhere in this repo.
    // ------------------------------------------------------------------

    @Test
    void malformedJsonIsAParseFailureNotAValidationFailure() {
        // Unterminated object - org.json's tokenizer must reject this before everit ever sees
        // a JSONObject to validate.
        final String malformed = "{ \"migratedCase\": ";

        assertThrows(JSONException.class, () -> new JSONObject(new JSONTokener(malformed)));
    }

    @Test
    void syntacticallyValidButSchemaInvalidJsonIsAValidationFailureNotAParseFailure() {
        // Valid JSON syntax (parses cleanly), invalid against the schema (misses everything
        // required) - the two failure modes must be distinguishable.
        final JSONObject payload = new JSONObject(new JSONTokener("{}"));

        final ValidationException exception = assertThrows(ValidationException.class, () -> schema.validate(payload));

        // All four top-level required properties are reported missing - confirms this failed on
        // schema content, not on JSON syntax.
        final String allMessages = String.join(" | ", exception.getAllMessages());
        assertTrue(allMessages.contains("migratedCase"));
        assertTrue(allMessages.contains("metadata"));
        assertTrue(allMessages.contains("submissionId"));
        assertTrue(allMessages.contains("azureLocation"));
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private static String validPayloadWithDurationMinutesLiteral(final String durationMinutesLiteral) {
        return validPayload().replace("\"durationMinutes\": 60", "\"durationMinutes\": " + durationMinutesLiteral);
    }

    private static String validPayload() {
        return """
                {
                  "migratedCase": {
                    "caseDetails": {
                      "prosecutorCaseReference": "TVL55117DFXXV",
                      "originatingOrganisation": "G94DV00",
                      "prosecutor": {
                        "prosecutingAuthority": "GAEAA01"
                      },
                      "initiationCode": "O",
                      "dateReceived": "2024-01-15",
                      "dateOfSending": "2024-01-20",
                      "retrialIndicator": false,
                      "receiptType": "Commital for sentence",
                      "sendingCourt": "B01LY00",
                      "receivingCourt": "C50EX00"
                    },
                    "hearings": [
                      {
                        "courtHearingLocation": "B01LY01",
                        "hearingType": "TRI",
                        "durationMinutes": 60,
                        "listedDefendants": [
                          {
                            "prosecutorDefendantId": "LIBRA-defendant-id-1",
                            "listedOffences": [
                              "LIBRA-offence-id-1"
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
                        "offences": [
                          {
                            "prosecutorOffenceId": "LIBRA-offence-id-1",
                            "offenceCode": "CA03013",
                            "offenceSequenceNumber": 1,
                            "offenceDateCode": 1,
                            "offenceCommittedDate": "2018-09-10",
                            "offenceWording": "TV Licence not paid"
                          }
                        ]
                      }
                    ],
                    "migrationSourceSystem": {
                      "migrationSourceSystemName": "LIBRA",
                      "migrationSourceSystemCaseIdentifier": "This is from Libra"
                    }
                  },
                  "materials": [
                    {
                      "id": "3f4b1c2d-1234-4a12-9abc-1234567890ab",
                      "azureLocation": "azure/location/path",
                      "fileName": "file1.pdf",
                      "fileType": "PDF",
                      "documentType": 1,
                      "receivedDateTime": "2024-01-15T10:00:00Z"
                    }
                  ],
                  "metadata": {
                    "id": "3f4b1c2d-1234-4a12-9abc-1234567890ac",
                    "numberOfMaterials": 1
                  },
                  "submissionId": "3f4b1c2d-1234-4a12-9abc-1234567890ad",
                  "azureLocation": "azure/case/location"
                }
                """;
    }
}
