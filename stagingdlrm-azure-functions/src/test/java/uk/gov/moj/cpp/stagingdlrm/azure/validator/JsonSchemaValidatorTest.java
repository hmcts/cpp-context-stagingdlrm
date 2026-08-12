package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import static java.util.Map.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.emptyJson;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.azure.functions.ExecutionContext;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DD-43078 FR9 — pins the Function App gate's contract: presence and declared JSON type of the
 * eight XHIBIT {@code caseDetails} properties, and nothing else.
 *
 * <p>Read off the three schemas it loads from {@code src/main/resources}.
 * {@code stagingdlrm.case-submission.json} requires {@code migratedCase} and is
 * {@code additionalProperties: false}, so the root is closed; {@code migrated-case.json} requires
 * {@code caseDetails} alone; {@code case-details.json} requires all 8 of its properties and declares
 * their types, but no patterns, lengths or enums. {@code hearings}, {@code defendants} and
 * {@code migrationSourceSystem} therefore reach the command API unvalidated — pinned here so that
 * adding a source-system-keyed schema shows up as a change to this contract.
 *
 * <p>DD-43086 FR3 adds a second, independent gate for LIBRA
 * ({@code libra.case-submission.json} → {@code libra-migrated-case.json} →
 * {@code libra-case-details.json}), authored from {@code libra-schema-impact.csv}'s
 * {@code funcapp_libra_action} column rather than by editing a copy of the XHIBIT schema — see the
 * FR3/AC3/AC4-tagged tests below. It matches the XHIBIT gate's {@code caseDetails}-only depth
 * (FR3a); {@code hearings}/{@code defendants} are unvalidated for LIBRA too. This story does not
 * yet wire schema *selection* by source system (FR4) — both validators are constructed directly.
 *
 * <p>Validation runs through the Function App's own {@link JsonSchemaValidator} (networknt), not
 * {@code test-utils-core}'s everit-backed matcher — right for the canonical schemas, wrong for the
 * validator in this process (DD-43078 finding F6).
 *
 * <p>Rows mutate the one valid fixture rather than carrying a fixture per scenario (ADR-001 §5).
 */
@ExtendWith(MockitoExtension.class)
class JsonSchemaValidatorTest {

    private static final String XHIBIT = "XHIBIT";
    private static final String LIBRA = "LIBRA";
    private static final String FIXTURES = "json/schema-validator/";

    private static final String VALID_CASE = FIXTURES + "case-submission-valid.json";
    private static final String VALID_LIBRA_CASE = FIXTURES + "case-submission-libra-valid.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ExecutionContext context;

    private JsonSchemaValidator caseValidator;
    private JsonSchemaValidator manifestValidator;
    private JsonSchemaValidator libraCaseValidator;

    @BeforeEach
    public void setup() {
        caseValidator = new JsonSchemaValidator(context, "stagingdlrm.case-submission.json");
        manifestValidator = new JsonSchemaValidator(context, "stagingdlrm.manifest.json");
        libraCaseValidator = new JsonSchemaValidator(context, "libra.case-submission.json");
    }

    private static String xhibit(final String fixturePath) {
        return fixture(fixturePath, of("SOURCE_SYSTEM", XHIBIT));
    }

    private static String libra(final String fixturePath) {
        return fixture(fixturePath, of("SOURCE_SYSTEM", LIBRA));
    }

    private Set<ValidationMessage> validateCase(final String payload) {
        return caseValidator.validate(UUID.randomUUID().toString(), payload);
    }

    private Set<ValidationMessage> validateLibraCase(final String payload) {
        return libraCaseValidator.validate(UUID.randomUUID().toString(), payload);
    }

    static Stream<Arguments> acceptedPayloads() {
        return Stream.of(
                arguments("FR9 a complete XHIBIT case submission is accepted (XHIBIT)",
                        VALID_CASE),
                arguments("FR9 undeclared properties and unconstrained values pass — the gate "
                                + "enforces presence only (XHIBIT)",
                        FIXTURES + "case-submission-undeclared-fields.json"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedPayloads")
    void shouldAcceptPayload(final String scenario, final String fixturePath) {
        assertEquals(Set.of(), validateCase(xhibit(fixturePath)),
                () -> scenario + " — expected no validation messages");
    }

    /**
     * Each row breaks exactly one constraint the gate does not declare, so a gate that starts
     * enforcing one fails the row naming it. Contrast {@link #shouldEnforceADeclaredType()}.
     */
    @ParameterizedTest(name = "FR9 the gate does not enforce {0} (XHIBIT)")
    @MethodSource("unenforcedConstraints")
    void shouldNotEnforceConstraintsItDoesNotDeclare(final String unenforced,
                                                     final Consumer<ObjectNode> mutation) throws Exception {
        final Set<ValidationMessage> messages = validateCase(validPayloadWith(mutation));

        assertEquals(Set.of(), messages, () -> "the gate must not enforce " + unenforced + ": " + messages);
    }

    static Stream<Arguments> unenforcedConstraints() {
        return Stream.of(
                arguments("an initiationCode enum — the canonical schema's enum is absent here",
                        (Consumer<ObjectNode>) migratedCase ->
                                caseDetails(migratedCase).put("initiationCode", "NOT-IN-THE-CANONICAL-ENUM")),
                arguments("a dateReceived format — declared as a bare string, no date format",
                        (Consumer<ObjectNode>) migratedCase ->
                                caseDetails(migratedCase).put("dateReceived", "not-even-a-date")),
                arguments("undeclared caseDetails properties — additionalProperties is true below the root",
                        (Consumer<ObjectNode>) migratedCase -> caseDetails(migratedCase)
                                .put("informant", "a field the canonical schema does not declare")
                                .put("prosecutorCosts", 42)),
                arguments("anything inside hearings — the gate never descends past caseDetails",
                        (Consumer<ObjectNode>) migratedCase -> migratedCase.putArray("hearings")
                                .addObject()
                                .put("thisIsNotAHearing", true)
                                .put("durationMinutes", "sixty minutes, as a string")),
                arguments("anything inside defendants or offences",
                        (Consumer<ObjectNode>) migratedCase -> migratedCase.putArray("defendants")
                                .addObject()
                                .putArray("offences")
                                .addObject()
                                .put("offenceDateCode", 9999)));
    }

    /**
     * The other edge of the contract: {@code retrialIndicator} is declared boolean and that <b>is</b>
     * enforced. Without it the rows above would read as "nothing below the root is validated".
     */
    @Test
    @DisplayName("FR9 the gate does enforce a declared type — retrialIndicator must be a boolean")
    void shouldEnforceADeclaredType() throws Exception {
        final Set<ValidationMessage> messages = validateCase(validPayloadWith(
                migratedCase -> caseDetails(migratedCase).put("retrialIndicator", "not-a-boolean")));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("retrialIndicator"),
                () -> messages.toString());
    }

    private static ObjectNode caseDetails(final ObjectNode migratedCase) {
        return (ObjectNode) migratedCase.get("caseDetails");
    }

    /** The valid payload with one mutation applied to its {@code migratedCase}. */
    private static String validPayloadWith(final Consumer<ObjectNode> mutation) throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(xhibit(VALID_CASE));
        mutation.accept((ObjectNode) payload.get("migratedCase"));
        return MAPPER.writeValueAsString(payload);
    }

    /** The 8 {@code required} properties of the func-app's own {@code case-details.json}. */
    @ParameterizedTest(name = "FR9 caseDetails.{0} is required by the gate (XHIBIT)")
    @ValueSource(strings = {
            "prosecutorCaseReference",
            "originatingOrganisation",
            "initiationCode",
            "prosecutor",
            "dateReceived",
            "retrialIndicator",
            "receiptType",
            "receivingCourt"
    })
    void shouldRejectCaseSubmissionMissingARequiredCaseDetailsProperty(final String property)
            throws Exception {

        final ObjectNode payload = (ObjectNode) MAPPER.readTree(xhibit(VALID_CASE));
        final ObjectNode caseDetails =
                (ObjectNode) payload.get("migratedCase").get("caseDetails");
        assertTrue(caseDetails.has(property),
                "fixture no longer carries " + property + " — the row proves nothing");
        caseDetails.remove(property);

        final Set<ValidationMessage> messages = validateCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> "expected exactly one message, got " + messages);
        assertTrue(messages.iterator().next().getMessage().contains(property),
                () -> "message should name the missing property: " + messages);
    }

    /**
     * DD-43086 FR3/AC3 — the LIBRA normalised schema's own contract, authored from
     * {@code libra-schema-impact.csv}'s {@code funcapp_libra_action} column (00-input-brief.md),
     * <b>not</b> by copying and editing the XHIBIT {@code case-details.json}. Matches the XHIBIT
     * gate's {@code caseDetails}-only depth (FR3a) — {@code hearings}/{@code defendants} remain
     * unvalidated here too.
     */
    @DisplayName("FR3/AC3 a complete LIBRA case submission is accepted (LIBRA)")
    @Test
    void shouldAcceptLibraPayload() {
        assertEquals(Set.of(), validateLibraCase(libra(VALID_LIBRA_CASE)),
                () -> "expected no validation messages");
    }

    /**
     * AC3's fixture must genuinely omit these — {@code additionalProperties: false} on
     * {@code libra-case-details.json} means a fixture that still carried one would only pass by
     * accident of the field being allowed, not proof the omission works.
     */
    @ParameterizedTest(name = "FR3/AC3 the LIBRA fixture genuinely omits {0} (fixture sanity check)")
    @ValueSource(strings = {
            "dateReceived", "receiptType", "receivingCourt", "retrialIndicator",
            "dateOfCommittal", "dateOfSending", "sendingCourt"
    })
    void libraFixtureShouldOmitXhibitOnlyFields(final String omittedProperty) throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(VALID_LIBRA_CASE));
        final ObjectNode caseDetails = (ObjectNode) payload.get("migratedCase").get("caseDetails");

        assertFalse(caseDetails.has(omittedProperty),
                () -> "fixture carries " + omittedProperty + " — the omission rows below would prove nothing");
    }

    /**
     * AC4 — the same payload that satisfies LIBRA fails XHIBIT's schema, proving the two schemas
     * are genuinely distinct rather than one silently accepting everything.
     */
    @DisplayName("FR3/AC4 the LIBRA fixture fails XHIBIT's schema — the two are genuinely distinct")
    @Test
    void shouldRejectLibraPayloadAgainstXhibitSchema() {
        final Set<ValidationMessage> messages = validateCase(libra(VALID_LIBRA_CASE));

        // XHIBIT requires 4 caseDetails properties LIBRA's fixture deliberately omits.
        assertEquals(4, messages.size(), () -> messages.toString());
    }

    /** The 4 {@code required} properties of {@code libra-case-details.json} (FR3's "require" column). */
    @ParameterizedTest(name = "FR3 caseDetails.{0} is required by the LIBRA gate (LIBRA)")
    @ValueSource(strings = {
            "prosecutorCaseReference",
            "originatingOrganisation",
            "initiationCode",
            "prosecutor"
    })
    void shouldRejectLibraCaseSubmissionMissingARequiredCaseDetailsProperty(final String property)
            throws Exception {

        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(VALID_LIBRA_CASE));
        final ObjectNode caseDetails = (ObjectNode) payload.get("migratedCase").get("caseDetails");
        assertTrue(caseDetails.has(property),
                "fixture no longer carries " + property + " — the row proves nothing");
        caseDetails.remove(property);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> "expected exactly one message, got " + messages);
        assertTrue(messages.iterator().next().getMessage().contains(property),
                () -> "message should name the missing property: " + messages);
    }

    /**
     * FR3's "require" column also reaches inside {@code prosecutor} — {@code prosecutingAuthority}
     * is optional in the shared {@code pcf-prosecutor.json} but required in
     * {@code libra-prosecutor.json}.
     */
    @DisplayName("FR3 prosecutor.prosecutingAuthority is required by the LIBRA gate (LIBRA)")
    @Test
    void shouldRejectLibraCaseSubmissionMissingProsecutingAuthority() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(VALID_LIBRA_CASE));
        final ObjectNode prosecutor = (ObjectNode)
                payload.get("migratedCase").get("caseDetails").get("prosecutor");
        prosecutor.remove("prosecutingAuthority");

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("prosecutingAuthority"),
                () -> messages.toString());
    }

    /**
     * FR3's "declare" column ({@code summonsCode}, {@code informant}, {@code writtenChargePostingDate},
     * {@code cpsOrganisation}, {@code caseMarkers}) is optional, not required — declared only so
     * {@code additionalProperties: false} does not reject it.
     */
    @ParameterizedTest(name = "FR3 caseDetails.{0} is declared but optional in the LIBRA gate (LIBRA)")
    @ValueSource(strings = {"summonsCode", "informant", "writtenChargePostingDate", "cpsOrganisation", "caseMarkers"})
    void shouldAcceptLibraCaseSubmissionMissingADeclaredOptionalProperty(final String property) throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(VALID_LIBRA_CASE));
        final ObjectNode caseDetails = (ObjectNode) payload.get("migratedCase").get("caseDetails");
        assertTrue(caseDetails.has(property),
                "fixture no longer carries " + property + " — the row proves nothing");
        caseDetails.remove(property);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> property + " must be optional: " + messages);
    }

    /** additionalProperties: false is the mechanism that makes FR3's "omit" column load-bearing. */
    @ParameterizedTest(name = "FR3 an omitted field ({0}) present in a LIBRA payload is rejected (LIBRA)")
    @ValueSource(strings = {
            "dateReceived", "receiptType", "receivingCourt", "retrialIndicator",
            "dateOfCommittal", "dateOfSending", "sendingCourt"
    })
    void shouldRejectLibraCaseSubmissionCarryingAnOmittedField(final String omittedProperty) throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(VALID_LIBRA_CASE));
        final ObjectNode caseDetails = (ObjectNode) payload.get("migratedCase").get("caseDetails");
        caseDetails.put(omittedProperty, "some-value");

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(),
                () -> omittedProperty + " must be rejected as undeclared: " + messages);
    }

    /** The root is {@code additionalProperties: false}, unlike every level beneath it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedPayloads")
    void shouldRejectPayload(final String scenario, final String fixturePath, final int expectedMessages) {
        assertEquals(expectedMessages, validateCase(xhibit(fixturePath)).size(),
                () -> scenario + " — " + validateCase(xhibit(fixturePath)));
    }

    static Stream<Arguments> rejectedPayloads() {
        return Stream.of(
                arguments("FR9 the root is closed — a sibling of migratedCase is rejected, "
                                + "even though undeclared properties inside caseDetails are not",
                        FIXTURES + "case-submission-extra-root-property.json", 1));
    }

    @DisplayName("FR9 an empty case payload is rejected — migratedCase is required")
    @Test
    void shouldRejectEmptyCasePayload() {
        assertEquals(1, validateCase(emptyJson()).size());
    }

    @DisplayName("FR9 an empty manifest is rejected")
    @Test
    void shouldRejectEmptyManifest() {
        final Set<ValidationMessage> messages =
                manifestValidator.validate(UUID.randomUUID().toString(), emptyJson());

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("manifestPayloads")
    void shouldValidateManifest(final String scenario, final String fixturePath, final int expectedMessages) {
        final Set<ValidationMessage> messages =
                manifestValidator.validate(UUID.randomUUID().toString(), xhibit(fixturePath));

        assertEquals(expectedMessages, messages.size(), () -> scenario + " — " + messages);
    }

    static Stream<Arguments> manifestPayloads() {
        return Stream.of(
                arguments("FR9 a manifest carrying files is accepted (XHIBIT)",
                        FIXTURES + "manifest-with-files.json", 0),
                arguments("FR9 a manifest with no files is accepted (XHIBIT)",
                        FIXTURES + "manifest-without-files.json", 0));
    }

    @DisplayName("FR1 every fixture in this class binds its source system explicitly")
    @ParameterizedTest(name = "{0} fails unbound")
    @ValueSource(strings = {
            VALID_CASE,
            VALID_LIBRA_CASE,
            FIXTURES + "manifest-with-files.json"
    })
    void shouldFailWhenSourceSystemIsNotBound(final String fixturePath) {
        final AssertionError error =
                assertThrows(AssertionError.class, () -> fixture(fixturePath));

        assertTrue(error.getMessage().contains("Unresolved placeholder"), error.getMessage());
    }
}
