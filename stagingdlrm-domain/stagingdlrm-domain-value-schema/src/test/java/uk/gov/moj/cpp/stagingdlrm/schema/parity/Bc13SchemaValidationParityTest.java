package uk.gov.moj.cpp.stagingdlrm.schema.parity;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * BC-13 parity test (see docs/j25-parity-checklist.md). Pins the J17 behaviour of everit +
 * {@code org.json} 20231013 schema validation for the seams the {@code org.json} 20231013&rarr;20251224
 * bump moves - the everit/{@code org.json} consolidation, not the Function App's networknt/Jackson gate
 * (that is DLRM-01, a separate table over a separate parser - see {@code JsonSchemaValidatorTest} in
 * {@code stagingdlrm-azure-functions}; the two tiers are asserted separately on purpose, per the
 * parity-method ADR's DLRM-01 addendum).
 *
 * <p>This module had zero Java before this story ({@code src/main/resources/json/**} only) - see
 * the parity-method ADR decision 7 - so this test is authored from scratch against the schema
 * catalogue, via {@link ClasspathSchemaClient}, rather than by extending an existing helper.
 *
 * <p><b>Gap, recorded not fabricated:</b> {@code case-details.json} has no {@code "format"} keyword of
 * its own; the only format-bearing definitions this schema set reaches are inside
 * {@code common-core-domain}'s {@code definitions.json} (date/uuid), which is framework-owned. The
 * requirements' "format" constraint class therefore has no binding site authored in this repo and is
 * not asserted here - see the BC-13 row in {@code docs/j25-parity-checklist.md}.
 */
class Bc13SchemaValidationParityTest {

    private static final String CASE_DETAILS_SCHEMA = "json/schema/case-details.json";
    private static final String MIGRATED_HEARING_SCHEMA = "json/schema/migrated/migrated-hearing.json";

    private static Schema loadSchema(final String classpathResource) {
        final JSONObject schemaJson = readJsonResource(classpathResource);
        return SchemaLoader.load(schemaJson, new ClasspathSchemaClient());
    }

    private static JSONObject readJsonResource(final String classpathResource) {
        try (final InputStream inputStream = requireResource(classpathResource)) {
            return new JSONObject(new String(inputStream.readAllBytes(), UTF_8));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream requireResource(final String classpathResource) {
        final InputStream inputStream =
                Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource);
        if (inputStream == null) {
            throw new IllegalStateException("Classpath resource not found: " + classpathResource);
        }
        return inputStream;
    }

    // -- case-details.json: required / enum / anyOf / type -----------------------------------------

    private static JSONObject validCaseDetails() {
        return new JSONObject()
                .put("prosecutorCaseReference", "TVL55117DFXXV")
                .put("originatingOrganisation", "G94DV00")
                .put("initiationCode", "O")
                .put("prosecutor", new JSONObject().put("prosecutingAuthority", "GAEAA01"))
                .put("dateReceived", "2024-01-15")
                .put("retrialIndicator", false)
                .put("receiptType", "Commital for sentence")
                .put("receivingCourt", "B01LY00")
                .put("dateOfCommittal", "2024-01-15");
    }

    @Test
    void acceptsAValidCaseDetailsPayload() {
        final Schema schema = loadSchema(CASE_DETAILS_SCHEMA);

        assertDoesNotThrow(() -> schema.validate(validCaseDetails()),
                "BC-13 parity test: a fully-populated, schema-conformant payload must validate on J17");
    }

    @Test
    void rejectsWhenARequiredFieldIsMissing() {
        final Schema schema = loadSchema(CASE_DETAILS_SCHEMA);
        final JSONObject payload = validCaseDetails();
        payload.remove("prosecutorCaseReference");

        final ValidationException exception =
                assertThrows(ValidationException.class, () -> schema.validate(payload),
                        "BC-13 parity test: omitting a required field must be rejected on J17");
        assertTrue(exception.getAllMessages().stream().anyMatch(message -> message.contains("prosecutorCaseReference")),
                "BC-13 parity test: the rejection message should name the missing field, not just fail generically");
    }

    @Test
    void rejectsAnInvalidEnumValue() {
        final Schema schema = loadSchema(CASE_DETAILS_SCHEMA);
        final JSONObject payload = validCaseDetails().put("initiationCode", "X");

        assertThrows(ValidationException.class, () -> schema.validate(payload),
                "BC-13 parity test: initiationCode only accepts the enum value \"O\" on J17");
    }

    @Test
    void rejectsWhenNeitherAnyOfBranchIsSatisfied() {
        final Schema schema = loadSchema(CASE_DETAILS_SCHEMA);
        final JSONObject payload = validCaseDetails();
        payload.remove("dateOfCommittal");
        // dateOfSending was never added - neither anyOf branch (dateOfCommittal, dateOfSending) is present

        assertThrows(ValidationException.class, () -> schema.validate(payload),
                "BC-13 parity test: at least one of dateOfCommittal/dateOfSending must be present on J17");
    }

    @Test
    void acceptsTheOtherAnyOfBranch() {
        final Schema schema = loadSchema(CASE_DETAILS_SCHEMA);
        final JSONObject payload = validCaseDetails();
        payload.remove("dateOfCommittal");
        payload.put("dateOfSending", "2024-08-23");

        assertDoesNotThrow(() -> schema.validate(payload),
                "BC-13 parity test: dateOfSending alone must satisfy the anyOf on J17, symmetrically with dateOfCommittal");
    }

    @Test
    void rejectsTheWrongTypeForABooleanField() {
        final Schema schema = loadSchema(CASE_DETAILS_SCHEMA);
        final JSONObject payload = validCaseDetails().put("retrialIndicator", "false");

        assertThrows(ValidationException.class, () -> schema.validate(payload),
                "BC-13 parity test: retrialIndicator is boolean-typed; a string \"false\" must be rejected on J17");
    }

    // -- parse failure vs validation failure --------------------------------------------------------

    @Test
    void aMalformedPayloadFailsToParseRatherThanFailingValidation() {
        // Not schema-related at all: a syntactically broken JSON document must fail during org.json's
        // own parse, distinctly from a well-formed payload that fails a schema constraint (the two
        // tests above). This is BC-13's "uncaught exception becomes an HTTP 500" shape - see FR5.3 in
        // 01-requirements.md - so the distinction must be asserted, not inferred.
        final String malformedJson = "{ \"prosecutorCaseReference\": ";

        assertThrows(JSONException.class, () -> new JSONObject(malformedJson),
                "BC-13 parity test: a syntactically invalid document must fail at parse time, before any schema is even consulted");
    }

    // -- numeric-literal table: migrated-hearing.json's durationMinutes (type: integer, maximum: 99999) --

    private static JSONObject validHearingWithDurationMinutesLiteral(final String numericLiteral) {
        final String json = "{"
                + "\"courtHearingLocation\": \"B01LY01\","
                + "\"hearingType\": \"FirstApp\","
                + "\"durationMinutes\": " + numericLiteral + ","
                + "\"listedDefendants\": ["
                + "  { \"prosecutorDefendantId\": \"LIBRA-defendant-id-1\", \"listedOffences\": [\"LIBRA-offence-id-1\"] }"
                + "]"
                + "}";
        return new JSONObject(json);
    }

    /**
     * One row per numeric literal, each with a named expected outcome - per FR5/AC3, "does not throw"
     * is not an acceptable assertion on its own. The literal is substituted as raw JSON text (not built
     * via {@code JSONObject.put(int)}) so that {@code org.json}'s own tokener - not this test - decides
     * what Java type each literal parses to; that parsed type is what everit's {@code "type": "integer"}
     * check actually inspects.
     */
    @Test
    void pinsTheNumericLiteralTableForDurationMinutes() {
        final Schema schema = loadSchema(MIGRATED_HEARING_SCHEMA);

        assertAccepted(schema, "0", "an ordinary integer literal parses as Integer and is within the maximum");
        assertAccepted(schema, "007", "org.json 20231013 leniently parses a leading-zero literal as Integer");
        assertAccepted(schema, "01", "org.json 20231013 leniently parses a leading-zero literal as Integer");
        assertRejected(schema, ".5", "org.json parses a decimal literal as BigDecimal, not Integer - a type mismatch, independent of the value being non-integral");
        assertRejected(schema, "10.0", "org.json parses a decimal-point literal as BigDecimal even though it is mathematically integral - everit checks the parsed Java type, not the numeric value");
        assertRejected(schema, "1e3", "org.json parses scientific notation as BigDecimal - a type mismatch, not a range failure");
        assertRejected(schema, "12345678901234567890", "org.json parses an oversized literal as BigInteger, not Integer - rejected on type before the maximum is even considered");
    }

    private static void assertAccepted(final Schema schema, final String numericLiteral, final String reason) {
        final JSONObject payload = validHearingWithDurationMinutesLiteral(numericLiteral);
        try {
            schema.validate(payload);
        } catch (final ValidationException e) {
            fail("BC-13 parity test: literal '" + numericLiteral + "' was expected to be ACCEPTED on J17 (" + reason
                    + ") but was rejected: " + e.getAllMessages());
        }
    }

    private static void assertRejected(final Schema schema, final String numericLiteral, final String reason) {
        final JSONObject payload = validHearingWithDurationMinutesLiteral(numericLiteral);
        assertThrows(ValidationException.class, () -> schema.validate(payload),
                "BC-13 parity test: literal '" + numericLiteral + "' was expected to be REJECTED on J17 (" + reason + ")");
    }
}
