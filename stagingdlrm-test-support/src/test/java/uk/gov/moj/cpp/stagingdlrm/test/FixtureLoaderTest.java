package uk.gov.moj.cpp.stagingdlrm.test;

import static java.util.Map.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cpp.stagingdlrm.test.FixtureLoader.fixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixtureLoaderTest {

    private static final String NON_ASCII = "json/test-support/non-ascii.json";
    private static final String PARAMETERISED = "json/test-support/parameterised.json";

    /** DD-43078 T1 AC3 — fixtures are decoded as UTF-8, so non-ASCII content survives the load. */
    @Test
    @DisplayName("T1 AC3 reads a fixture as UTF-8")
    void shouldReadFixtureAsUtf8() {
        final String payload = fixture(NON_ASCII);

        assertTrue(payload.contains("Ançelotti-Ødegaard"), payload);
        assertTrue(payload.contains("£1,250.00"), payload);
        assertTrue(payload.contains("naïve — em dash"), payload);
    }

    @Test
    @DisplayName("emptyJson replaces the one-line {} fixture files")
    void shouldProvideTheEmptyJsonObject() {
        assertEquals("{}", FixtureLoader.emptyJson());
    }

    @Test
    @DisplayName("substitutes a supplied parameter")
    void shouldSubstituteParameter() {
        final String payload = fixture(PARAMETERISED, of("SOURCE_SYSTEM", "XHIBIT"));

        assertTrue(payload.contains("\"migrationSourceSystemName\": \"XHIBIT\""), payload);
    }

    @Test
    @DisplayName("T1 AC4 fails when a supplied parameter appears nowhere in the fixture")
    void shouldFailWhenSuppliedParameterIsNotInTheFixture() {
        final AssertionError error = assertThrows(AssertionError.class,
                () -> fixture(PARAMETERISED, of("SOURCE_SYSTEM", "XHIBIT", "NOT_THERE", "x")));

        assertTrue(error.getMessage().contains("{{NOT_THERE}}"), error.getMessage());
        assertTrue(error.getMessage().contains(PARAMETERISED), error.getMessage());
    }

    /** DD-43078 T1 AC5, the FR1 guard: an unbound {@code {{SOURCE_SYSTEM}}} must not pass. */
    @Test
    @DisplayName("T1 AC5 fails when a placeholder is left unresolved")
    void shouldFailWhenAPlaceholderIsLeftUnresolved() {
        final AssertionError error = assertThrows(AssertionError.class, () -> fixture(PARAMETERISED));

        assertTrue(error.getMessage().contains("Unresolved placeholder"), error.getMessage());
        assertTrue(error.getMessage().contains("{{SOURCE_SYSTEM}}"), error.getMessage());
    }

    @Test
    @DisplayName("fails with the path when the fixture is not on the classpath")
    void shouldFailWhenFixtureIsMissing() {
        final AssertionError error =
                assertThrows(AssertionError.class, () -> fixture("json/test-support/nope.json"));

        assertEquals("Fixture not found on the test classpath: json/test-support/nope.json",
                error.getMessage());
    }
}
