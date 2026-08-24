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
 * eight {@code caseDetails} properties, and nothing else.
 *
 * <p>Read off the three schemas it loads from {@code src/main/resources}.
 * {@code stagingdlrm.case-submission.json} requires {@code migratedCase} and is
 * {@code additionalProperties: false}, so the root is closed; {@code migrated-case.json} requires
 * {@code caseDetails} alone; {@code case-details.json} requires all 8 of its properties and declares
 * their types, but no patterns, lengths or enums. {@code hearings}, {@code defendants} and
 * {@code migrationSourceSystem} therefore reach the command API unvalidated — pinned here so that
 * adding a source-system-keyed schema shows up as a change to this contract.
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

    /**
     * DD-43086 LIBRA02 — a well-formed LIBRA case that omits the six fields LIBRA never supplies
     * ({@code receiptType}, {@code receivingCourt}, {@code dateReceived}, {@code retrialIndicator},
     * {@code dateOfSending}, {@code dateOfCommittal}) and carries the {@code declare} fields the
     * closed LIBRA {@code caseDetails} must accept but not require.
     */
    private static final String LIBRA_VALID_CASE = FIXTURES + "libra-case-submission-valid.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ExecutionContext context;

    private JsonSchemaValidator caseValidator;
    private JsonSchemaValidator manifestValidator;

    /**
     * DD-43086 LIBRA02 — the new, fully independent LIBRA case-submission schema: a single file,
     * {@code libra.case-submission.json}. {@code migratedCase} (case details, prosecutor, case
     * markers, defendants, hearings, officer in case, and everything each of those reaches) is
     * fully inlined — there is no {@code libra-migrated-case.json}, nor any other
     * {@code libra-*.json} file, any more. Each of {@code migratedCase}'s four properties is
     * factored out as its own root-level {@code definitions} entry ({@code caseDetails},
     * {@code defendant} — the {@code defendants[]} item schema, {@code hearing} — the
     * {@code hearings[]} item schema, {@code officerInCase}), {@code $ref}-ed from
     * {@code properties.migratedCase}, alongside {@code date}/{@code phone}/{@code email} (reused
     * many times within the graph). JSON Pointer refs resolve against the document root, so
     * {@code definitions} lives one level above {@code properties.migratedCase}, not nested
     * inside it. The XHIBIT chain above is untouched.
     */
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
     * DD-43086 LIBRA02/AC3 — a LIBRA case that omits the six fields LIBRA never supplies passes the
     * new LIBRA schema. This is the whole point of the parallel chain: those six fields are
     * {@code omit} in {@code libra-schema-impact.csv}, absent from {@code libra-case-details.json}'s
     * {@code properties}, and (because that object is {@code additionalProperties: false}) would be
     * rejected if sent — but their <i>absence</i> is fine.
     */
    @Test
    @DisplayName("LIBRA02/AC3 a LIBRA case omitting receiptType/receivingCourt/dateReceived/"
            + "retrialIndicator/dateOfSending/dateOfCommittal passes the LIBRA schema (LIBRA)")
    void shouldAcceptLibraCaseOmittingXhibitOnlyFields() {
        assertEquals(Set.of(), validateLibraCase(libra(LIBRA_VALID_CASE)),
                () -> "the LIBRA schema must accept a LIBRA payload without the XHIBIT-only fields: "
                        + validateLibraCase(libra(LIBRA_VALID_CASE)));
    }

    /**
     * DD-43086 LIBRA02/AC4 — the same payload fails the existing XHIBIT schema, proving the two
     * schemas are genuinely distinct and the selection (LIBRA03) is doing real work. XHIBIT's
     * {@code case-details.json} requires {@code dateReceived}, {@code retrialIndicator},
     * {@code receiptType} and {@code receivingCourt}; the LIBRA payload omits all four, so exactly
     * four required-property messages are expected. Asserted whole so a change to either schema
     * that quietly narrowed this gap would fail here.
     */
    @Test
    @DisplayName("LIBRA02/AC4 the same LIBRA payload fails the XHIBIT schema — the four XHIBIT-required "
            + "fields it omits are each reported, plus the DD-43180 initiationCode enum (XHIBIT)")
    void shouldRejectLibraCaseAgainstXhibitSchema() {
        final Set<ValidationMessage> messages = validateCase(libra(LIBRA_VALID_CASE));

        final Set<String> reported =
                messages.stream().map(ValidationMessage::getMessage).collect(java.util.stream.Collectors.toSet());

        assertTrue(reported.stream().anyMatch(m -> m.contains("dateReceived")), () -> reported.toString());
        assertTrue(reported.stream().anyMatch(m -> m.contains("retrialIndicator")), () -> reported.toString());
        assertTrue(reported.stream().anyMatch(m -> m.contains("receiptType")), () -> reported.toString());
        assertTrue(reported.stream().anyMatch(m -> m.contains("receivingCourt")), () -> reported.toString());
        assertTrue(reported.stream().anyMatch(m -> m.contains("initiationCode")),
                () -> "DD-43180: the LIBRA initiationCode 'C' is outside the XHIBIT enum [O]: " + reported);
    }

    @Test
    void shouldRejectXhibitCaseSubmissionWithInitiationCodeOutsideEnum() throws Exception {
        final Set<ValidationMessage> messages = validateCase(validPayloadWith(
                migratedCase -> caseDetails(migratedCase).put("initiationCode", "C")));

        assertEquals(1, messages.size(), messages::toString);
        assertTrue(messages.iterator().next().getMessage().contains("initiationCode"),
                messages::toString);
    }

    @Test
    void shouldRejectLibraCaseSubmissionWithInitiationCodeOutsideEnum() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        ((ObjectNode) payload.get("migratedCase").get("caseDetails")).put("initiationCode", "O");

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), messages::toString);
        assertTrue(messages.iterator().next().getMessage().contains("initiationCode"),
                messages::toString);
    }

    @Test
    @DisplayName("LIBRA02 the gate enforces the LIBRA workbook's constraints at caseDetails depth — "
            + "informant maxLength:92 (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithACaseDetailsPropertyViolatingADeclaredConstraint() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode caseDetails = (ObjectNode) payload.get("migratedCase").get("caseDetails");
        caseDetails.put("informant", "X".repeat(93));

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    /**
     * DD-43086 LIBRA02 — {@code caseDetails.prosecutor} was extended, during implementation, to
     * {@code additionalProperties: false} (matching the workbook's own {@code prosecutor}
     * definition exactly), reversing the earlier deliberate choice to mirror
     * {@code pcf-prosecutor.json}'s {@code additionalProperties: true}. The workbook's
     * {@code prosecutor} declares only {@code prosecutingAuthority}, so this is now the only
     * property accepted.
     */
    @Test
    @DisplayName("LIBRA02 caseDetails.prosecutor rejects an undeclared sibling property — the object "
            + "is closed (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithAnUndeclaredProsecutorProperty() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode prosecutor = (ObjectNode) payload.get("migratedCase").get("caseDetails").get("prosecutor");
        prosecutor.put("somePropertyTheSchemaDoesNotDeclare", "value");

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    /**
     * DD-43086 LIBRA02 — {@code caseDetails.caseMarkers[]} was missing
     * {@code additionalProperties: false} entirely (an oversight, not a deliberate choice — the
     * workbook's own {@code caseMarkers} definition is closed), found and fixed by an audit of
     * every object-type schema in this file against the workbook.
     */
    @Test
    @DisplayName("LIBRA02 caseDetails.caseMarkers[] rejects an undeclared sibling property — the object "
            + "is closed (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithAnUndeclaredCaseMarkerProperty() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode caseMarker = (ObjectNode) payload.get("migratedCase").get("caseDetails")
                .get("caseMarkers").get(0);
        caseMarker.put("somePropertyTheSchemaDoesNotDeclare", "value");

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    /**
     * DD-43086 LIBRA02 — {@code migratedCase} requires {@code defendants} in addition to
     * {@code caseDetails}. {@code migrationSourceSystem} is deliberately not declared at all
     * (removed during implementation — see {@link #shouldRejectLibraCaseSubmissionCarryingMigrationSourceSystem()}),
     * so it is not part of this parameterisation.
     */
    @ParameterizedTest(name = "LIBRA02 migratedCase.{0} is required by the LIBRA gate (LIBRA)")
    @ValueSource(strings = {"defendants"})
    void shouldRejectLibraCaseSubmissionMissingARequiredMigratedCaseProperty(final String property)
            throws Exception {

        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        assertTrue(migratedCase.has(property),
                "fixture no longer carries " + property + " — the row proves nothing");
        migratedCase.remove(property);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> "expected exactly one message, got " + messages);
        assertTrue(messages.iterator().next().getMessage().contains(property),
                () -> "message should name the missing property: " + messages);
    }

    /**
     * DD-43086 LIBRA02 — {@code migratedCase} is now closed ({@code additionalProperties: false},
     * matching the LIBRA workbook schema exactly), unlike XHIBIT's own {@code migrated-case.json}
     * which stays open. {@code hearings} and {@code officerInCase} are declared-but-optional, so
     * they don't trip this — only a property the schema doesn't know about at all does.
     */
    @Test
    @DisplayName("LIBRA02 migratedCase rejects an undeclared sibling property — the object is closed (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithAnUndeclaredMigratedCaseProperty() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        migratedCase.put("somePropertyTheSchemaDoesNotDeclare", "value");

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    /**
     * DD-43086 LIBRA02 — {@code migrationSourceSystem} is deliberately not declared under
     * {@code migratedCase} (removed during implementation — it was briefly declared and required,
     * {@code $ref}-ed to the shared {@code migrationSourceSystem.json}, then dropped). Because
     * {@code migratedCase} is closed ({@code additionalProperties: false}), a payload that carries
     * it is rejected as an undeclared property, not merely treated as optional.
     */
    @Test
    @DisplayName("LIBRA02 migratedCase rejects migrationSourceSystem — deliberately undeclared, not just optional (LIBRA)")
    void shouldRejectLibraCaseSubmissionCarryingMigrationSourceSystem() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        assertFalse(migratedCase.has("migrationSourceSystem"),
                "fixture already carries migrationSourceSystem — the row proves nothing");
        migratedCase.putObject("migrationSourceSystem")
                .put("migrationSourceSystemName", LIBRA)
                .put("migrationSourceSystemCaseIdentifier", "This is from " + LIBRA);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    @Test
    @DisplayName("LIBRA02 migratedCase.hearings is declared but optional in the LIBRA gate (LIBRA)")
    void shouldAcceptLibraCaseSubmissionCarryingADeclaredOptionalMigratedCaseProperty() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        assertFalse(migratedCase.has("hearings"),
                "fixture already carries hearings — the row proves nothing");
        migratedCase.putArray("hearings").add(validHearing());

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> "hearings should be accepted (declared): " + messages);
    }

    /**
     * DD-43086 LIBRA02 — {@code defendants} was extended, during implementation, from a bare
     * {@code type: array} to a full recursive expansion of the LIBRA workbook's {@code defendant}
     * definition and everything it reaches ({@code address}, {@code individual}, {@code offence},
     * {@code plea}, {@code verdict}, …). No separate {@code libra-defendant.json}/
     * {@code libra-address.json}/… files remain — {@code defendant} is one root-level
     * {@code #/definitions/defendant} entry within {@code libra.case-submission.json}, and
     * {@code migratedCase.defendants.items} is just {@code {"$ref": "#/definitions/defendant"}}
     * (the reused {@code date}/{@code phone}/{@code email} primitives are their own sibling
     * {@code definitions} entries). Unlike the rest of this gate, these definitions carry the
     * workbook's full constraints (patterns, {@code maxLength}, {@code minimum}/
     * {@code maximum}), not bare types only — a deliberate, scoped exception to FR3a's otherwise
     * structural/presence-only style.
     */
    private static ObjectNode validDefendant() {
        final ObjectNode defendant = MAPPER.createObjectNode();
        defendant.put("prosecutorDefendantId", "D1");
        defendant.put("documentationLanguage", "E");
        defendant.put("hearingLanguage", "E");
        defendant.putObject("address").put("address1", "1 Test Street");
        final ObjectNode offence = MAPPER.createObjectNode();
        offence.put("prosecutorOffenceId", "OFF-0001");
        offence.put("offenceCode", "CODE0001");
        offence.put("offenceSequenceNumber", 1);
        offence.put("offenceCommittedDate", "2024-01-01");
        offence.put("offenceDateCode", 1);
        offence.put("offenceWording", "Test offence wording");
        defendant.putArray("offences").add(offence);
        return defendant;
    }

    @Test
    @DisplayName("LIBRA02 a defendant matching the LIBRA workbook's defendant definition is accepted (LIBRA)")
    void shouldAcceptLibraCaseSubmissionWithAFullyPopulatedDefendant() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        migratedCase.putArray("defendants").add(validDefendant());

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> messages.toString());
    }

    @ParameterizedTest(name = "LIBRA02 defendant.{0} is required, matching the LIBRA workbook's defendant "
            + "definition (LIBRA)")
    @ValueSource(strings = {"prosecutorDefendantId", "documentationLanguage", "hearingLanguage", "address",
            "offences"})
    void shouldRejectLibraCaseSubmissionWithADefendantMissingARequiredProperty(final String property)
            throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        assertTrue(defendant.has(property),
                "defendant fixture no longer carries " + property + " — the row proves nothing");
        defendant.remove(property);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> "expected exactly one message, got " + messages);
        assertTrue(messages.iterator().next().getMessage().contains(property), () -> messages.toString());
    }

    @Test
    @DisplayName("LIBRA02 the gate enforces the LIBRA workbook's constraints at defendant depth — "
            + "documentationLanguage maxLength:1 (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithADefendantViolatingADeclaredConstraint() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        defendant.put("documentationLanguage", "EN");
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    /**
     * All {@code minItems} rejections share this shape: exactly one message, and it names
     * {@code minItems} specifically (networknt's wording: "there must be a minimum of N items in
     * the array") — not just any single message, in case a future fixture change silently swaps
     * in a different violation.
     */
    private static void assertRejectedByMinItems(final Set<ValidationMessage> messages) {
        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("minimum of"),
                () -> "expected a minItems rejection: " + messages);
    }

    /**
     * DD-43086 LIBRA02 — {@code defendant.offences} carries {@code minItems: 1}, matching the
     * workbook (found missing during code review — the sibling arrays
     * {@code hearing.listedDefendants} and {@code listedDefendant.listedOffences} already had it).
     * An empty array satisfies {@code required} (the key is present) but must still be rejected.
     */
    @Test
    @DisplayName("LIBRA02 the gate enforces minItems:1 on defendant.offences (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithADefendantHavingNoOffences() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        defendant.putArray("offences");
        migratedCase.putArray("defendants").add(defendant);

        assertRejectedByMinItems(validateLibraCase(MAPPER.writeValueAsString(payload)));
    }

    @ParameterizedTest(name = "LIBRA02 offence.{0} is required, matching the LIBRA workbook's offence "
            + "definition (LIBRA)")
    @ValueSource(strings = {"offenceCode", "offenceCommittedDate", "offenceDateCode", "offenceSequenceNumber",
            "offenceWording", "prosecutorOffenceId"})
    void shouldRejectLibraCaseSubmissionWithAnOffenceMissingARequiredProperty(final String property)
            throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode offence = (ObjectNode) defendant.get("offences").get(0);
        assertTrue(offence.has(property),
                "offence fixture no longer carries " + property + " — the row proves nothing");
        offence.remove(property);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> "expected exactly one message, got " + messages);
        assertTrue(messages.iterator().next().getMessage().contains(property), () -> messages.toString());
    }

    @ParameterizedTest(name = "DD-43180 offence.offenceDateCode {0} is outside the 1-6 range and rejected (LIBRA)")
    @ValueSource(ints = {0, 7})
    void shouldRejectLibraCaseSubmissionWithAnOffenceDateCodeOutsideRange(final int offenceDateCode)
            throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        ((ObjectNode) defendant.get("offences").get(0)).put("offenceDateCode", offenceDateCode);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("offenceDateCode"),
                () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 offence.arrestDate is validated against the date pattern — a non-date is rejected (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithAnOffenceArrestDateThatIsNotADate() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        ((ObjectNode) defendant.get("offences").get(0)).put("arrestDate", "not-a-date");
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("arrestDate"),
                () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 an offence carrying plea/verdict/allocationDecision with UUID identifiers is accepted (LIBRA)")
    void shouldAcceptLibraCaseSubmissionWithOffencePleaVerdictAllocationDecision() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode offence = (ObjectNode) defendant.get("offences").get(0);
        offence.putObject("plea").put("id", UUID.randomUUID().toString());
        offence.putObject("verdict").put("id", UUID.randomUUID().toString());
        offence.putObject("allocationDecision").put("motReasonId", UUID.randomUUID().toString());
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> messages.toString());
    }

    @ParameterizedTest(name = "DD-43180 offence.{0} requires its UUID identifier ''{1}'' (LIBRA)")
    @MethodSource("uuidBearingOffenceObjects")
    void shouldRejectLibraCaseSubmissionWithAUuidBearingOffenceObjectMissingItsId(
            final String objectName, final String idField) throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        ((ObjectNode) defendant.get("offences").get(0)).putObject(objectName);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains(idField), () -> messages.toString());
    }

    static Stream<Arguments> uuidBearingOffenceObjects() {
        return Stream.of(
                arguments("plea", "id"),
                arguments("verdict", "id"),
                arguments("allocationDecision", "motReasonId"));
    }

    @Test
    @DisplayName("DD-43180 offence.plea.id must be a UUID — a non-UUID value is rejected (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithOffencePleaIdThatIsNotAUuid() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        ((ObjectNode) defendant.get("offences").get(0)).putObject("plea").put("id", "not-a-uuid");
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("id"), () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 a defendant carrying a valid individual (surname + gender) is accepted (LIBRA)")
    void shouldAcceptLibraCaseSubmissionWithAValidIndividual() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        defendant.set("individual", validIndividual());
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 individual.personalInformation.observedEthnicity must be an integer — a string is rejected (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithObservedEthnicityAsString() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode individual = validIndividual();
        ((ObjectNode) individual.get("personalInformation")).put("observedEthnicity", "WHITE");
        defendant.set("individual", individual);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("observedEthnicity"),
                () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 individual.personalInformation requires surname (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithPersonalInformationMissingSurname() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode individual = validIndividual();
        final ObjectNode personalInformation = (ObjectNode) individual.get("personalInformation");
        personalInformation.remove("surname");
        personalInformation.put("forename", "John");
        defendant.set("individual", individual);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("surname"), () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 individual.selfDefinedInformation requires gender (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithSelfDefinedInformationMissingGender() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode individual = validIndividual();
        ((ObjectNode) individual.get("selfDefinedInformation")).remove("gender");
        defendant.set("individual", individual);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("gender"), () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 hearing.hearingType over maxLength:10 is rejected (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithHearingTypeOverMaxLength() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode hearing = validHearing();
        hearing.put("hearingType", "H".repeat(11));
        migratedCase.putArray("hearings").add(hearing);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("hearingType"),
                () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 caseDetails.caseMarkers[] requires markerTypeCode (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithACaseMarkerMissingMarkerTypeCode() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode caseMarker = (ObjectNode) payload.get("migratedCase").get("caseDetails")
                .get("caseMarkers").get(0);
        assertTrue(caseMarker.has("markerTypeCode"),
                "fixture no longer carries markerTypeCode — the row proves nothing");
        caseMarker.remove("markerTypeCode");

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("markerTypeCode"),
                () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 individualAlias.firstName has no length cap — a long value is accepted (LIBRA)")
    void shouldAcceptLibraCaseSubmissionWithALongIndividualAliasFirstName() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode alias = MAPPER.createObjectNode();
        alias.put("firstName", "F".repeat(200));
        alias.put("lastName", "L".repeat(200));
        defendant.putArray("individualAliases").add(alias);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 individualAlias.title over maxLength:35 is rejected (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithAnIndividualAliasTitleOverMaxLength() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode alias = MAPPER.createObjectNode();
        alias.put("title", "T".repeat(36));
        alias.put("firstName", "John");
        defendant.putArray("individualAliases").add(alias);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("title"), () -> messages.toString());
    }

    @ParameterizedTest(name = "DD-43180 defendant.{0} is no longer declared — the relocated field is rejected on defendant (LIBRA)")
    @ValueSource(strings = {"occupation", "defendantOccupationCode", "driverNumber", "licenseCode", "nationalInsuranceNumber"})
    void shouldRejectLibraCaseSubmissionWithARelocatedFieldStillDeclaredOnDefendant(final String property)
            throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        defendant.put(property, "x");
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains(property), () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 individual accepts driverNumber and licenseCode relocated from defendant (LIBRA)")
    void shouldAcceptLibraCaseSubmissionWithIndividualDriverNumberAndLicenseCode() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode individual = validIndividual();
        individual.put("driverNumber", "DVLA1234567890AB");
        individual.put("licenseCode", "A");
        defendant.set("individual", individual);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 individual.nationalInsuranceNumber enforces the CJS pattern — a malformed value is rejected (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithMalformedNationalInsuranceNumber() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode individual = validIndividual();
        individual.put("nationalInsuranceNumber", "not-a-valid-value");
        defendant.set("individual", individual);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("nationalInsuranceNumber"),
                () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 personalInformation accepts occupation and defendantOccupationCode relocated from defendant (LIBRA)")
    void shouldAcceptLibraCaseSubmissionWithPersonalInformationOccupationFields() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode individual = validIndividual();
        ((ObjectNode) individual.get("personalInformation")).put("occupation", "Baker").put("defendantOccupationCode", 42);
        defendant.set("individual", individual);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 personalInformation.occupation enforces maxLength:54 (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithPersonalInformationOccupationOverMaxLength() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode individual = validIndividual();
        ((ObjectNode) individual.get("personalInformation")).put("occupation", "O".repeat(55));
        defendant.set("individual", individual);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("occupation"), () -> messages.toString());
    }

    @Test
    @DisplayName("DD-43180 individual requires selfDefinedInformation (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithIndividualMissingSelfDefinedInformation() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        final ObjectNode individual = MAPPER.createObjectNode();
        individual.putObject("personalInformation").put("surname", "Brown");
        defendant.set("individual", individual);
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
        assertTrue(messages.iterator().next().getMessage().contains("selfDefinedInformation"),
                () -> messages.toString());
    }

    private static ObjectNode validIndividual() {
        final ObjectNode individual = MAPPER.createObjectNode();
        individual.putObject("personalInformation").put("surname", "Brown");
        individual.putObject("selfDefinedInformation").put("gender", 0);
        return individual;
    }

    @Test
    @DisplayName("DD-43180 the LIBRA gate accepts a defendant carrying emailAddress1/emailAddress2 (LIBRA)")
    void shouldAcceptLibraCaseSubmissionWithADefendantCarryingEmailAddresses() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        defendant.put("emailAddress1", "a@example.com");
        defendant.put("emailAddress2", "b@example.com");
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> messages.toString());
    }

    /**
     * DD-43086 LIBRA02 — {@code migratedCase.defendants} and {@code migratedCase.hearings} both
     * carry {@code minItems: 1} — a deliberate LIBRA-gate strengthening with no workbook
     * counterpart (the workbook leaves both bare {@code type: array}, only constraining the
     * nested arrays). {@code defendants} is already {@code required}, so this closes the
     * remaining gap of an empty-but-present array; {@code hearings} is optional, so this only
     * bites once the key is sent at all.
     */
    @Test
    @DisplayName("LIBRA02 the gate enforces minItems:1 on migratedCase.defendants (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithNoDefendants() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        migratedCase.putArray("defendants");

        assertRejectedByMinItems(validateLibraCase(MAPPER.writeValueAsString(payload)));
    }

    @Test
    @DisplayName("LIBRA02 the gate enforces minItems:1 on migratedCase.hearings (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithAnEmptyHearingsArray() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        migratedCase.putArray("hearings");

        assertRejectedByMinItems(validateLibraCase(MAPPER.writeValueAsString(payload)));
    }

    /**
     * DD-43086 LIBRA02 — {@code caseDetails.caseMarkers}, {@code defendant.aliasForCorporate} and
     * {@code defendant.individualAliases} all carry {@code minItems: 1} too — same deliberate,
     * no-workbook-counterpart strengthening as {@code migratedCase.defendants}/{@code hearings}.
     * All three are optional (not in their parent's {@code required}), so this only bites once
     * the key is sent at all — omitting it entirely remains valid (the shared valid fixture
     * already omits {@code aliasForCorporate}/{@code individualAliases} and passes).
     */
    @ParameterizedTest(name = "LIBRA02 the gate enforces minItems:1 on caseDetails.{0} (LIBRA)")
    @ValueSource(strings = {"caseMarkers"})
    void shouldRejectLibraCaseSubmissionWithAnEmptyCaseDetailsArray(final String property) throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode caseDetails = (ObjectNode) payload.get("migratedCase").get("caseDetails");
        caseDetails.putArray(property);

        assertRejectedByMinItems(validateLibraCase(MAPPER.writeValueAsString(payload)));
    }

    @ParameterizedTest(name = "LIBRA02 the gate enforces minItems:1 on defendant.{0} (LIBRA)")
    @ValueSource(strings = {"aliasForCorporate", "individualAliases"})
    void shouldRejectLibraCaseSubmissionWithAnEmptyDefendantArray(final String property) throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        defendant.putArray(property);
        migratedCase.putArray("defendants").add(defendant);

        assertRejectedByMinItems(validateLibraCase(MAPPER.writeValueAsString(payload)));
    }

    @Test
    @DisplayName("LIBRA02 the gate enforces nested workbook constraints two $refs deep — "
            + "defendant.address.postcode pattern (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithADefendantAddressViolatingThePostcodePattern() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode defendant = validDefendant();
        ((ObjectNode) defendant.get("address")).put("postcode", "ABC123");
        migratedCase.putArray("defendants").add(defendant);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    /**
     * DD-43086 LIBRA02 — {@code hearings} was extended, during implementation, from a bare
     * {@code type: array} to a full-constraint inline item schema (the same treatment given to
     * {@code defendants} above), then fully flattened: {@code hearing} and {@code listedDefendant}
     * are both inlined into one root-level {@code #/definitions/hearing} entry within
     * {@code libra.case-submission.json}, with {@code migratedCase.hearings.items} now just
     * {@code {"$ref": "#/definitions/hearing"}} (no separate {@code libra-hearing.json}/
     * {@code libra-listed-defendant.json} files remain).
     * {@code dateOfHearing} references the local {@code #/definitions/date} entry, same as every
     * other date field in the graph — there is no longer a standalone {@code libra-date.json}
     * file at all.
     */
    private static ObjectNode validHearing() {
        final ObjectNode hearing = MAPPER.createObjectNode();
        hearing.put("courtHearingLocation", "AAAAA01");
        hearing.put("courtRoomId", 1);
        hearing.put("dateOfHearing", "2024-01-01");
        hearing.put("timeOfHearing", "09:30:00");
        hearing.put("durationMinutes", 60);
        hearing.put("hearingType", "FHG");
        final ObjectNode listedDefendant = MAPPER.createObjectNode();
        listedDefendant.put("prosecutorDefendantId", "D1");
        listedDefendant.putArray("listedOffences").add("O1");
        hearing.putArray("listedDefendants").add(listedDefendant);
        return hearing;
    }

    @Test
    @DisplayName("LIBRA02 a hearing matching the LIBRA workbook's hearing definition is accepted (LIBRA)")
    void shouldAcceptLibraCaseSubmissionWithAFullyPopulatedHearing() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        migratedCase.putArray("hearings").add(validHearing());

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(Set.of(), messages, () -> messages.toString());
    }

    @ParameterizedTest(name = "LIBRA02 hearing.{0} is required, matching the LIBRA workbook's hearing "
            + "definition (LIBRA)")
    @ValueSource(strings = {"courtHearingLocation", "courtRoomId", "dateOfHearing", "durationMinutes",
            "hearingType", "listedDefendants", "timeOfHearing"})
    void shouldRejectLibraCaseSubmissionWithAHearingMissingARequiredProperty(final String property)
            throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode hearing = validHearing();
        assertTrue(hearing.has(property),
                "hearing fixture no longer carries " + property + " — the row proves nothing");
        hearing.remove(property);
        migratedCase.putArray("hearings").add(hearing);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> "expected exactly one message, got " + messages);
        assertTrue(messages.iterator().next().getMessage().contains(property), () -> messages.toString());
    }

    @Test
    @DisplayName("LIBRA02 the gate enforces the LIBRA workbook's constraints at hearing depth — "
            + "courtHearingLocation must be exactly 7 characters (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithAHearingViolatingADeclaredConstraint() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode hearing = validHearing();
        hearing.put("courtHearingLocation", "TOOLONGLOCATION");
        migratedCase.putArray("hearings").add(hearing);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
    }

    @Test
    @DisplayName("LIBRA02 the gate enforces nested workbook constraints two $refs deep — "
            + "hearing.listedDefendants[].prosecutorDefendantId maxLength (LIBRA)")
    void shouldRejectLibraCaseSubmissionWithAHearingListedDefendantViolatingAConstraint() throws Exception {
        final ObjectNode payload = (ObjectNode) MAPPER.readTree(libra(LIBRA_VALID_CASE));
        final ObjectNode migratedCase = (ObjectNode) payload.get("migratedCase");
        final ObjectNode hearing = validHearing();
        ((ObjectNode) hearing.get("listedDefendants").get(0))
                .put("prosecutorDefendantId", "D".repeat(37));
        migratedCase.putArray("hearings").add(hearing);

        final Set<ValidationMessage> messages = validateLibraCase(MAPPER.writeValueAsString(payload));

        assertEquals(1, messages.size(), () -> messages.toString());
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
            LIBRA_VALID_CASE,
            FIXTURES + "manifest-with-files.json"
    })
    void shouldFailWhenSourceSystemIsNotBound(final String fixturePath) {
        final AssertionError error =
                assertThrows(AssertionError.class, () -> fixture(fixturePath));

        assertTrue(error.getMessage().contains("Unresolved placeholder"), error.getMessage());
    }
}
