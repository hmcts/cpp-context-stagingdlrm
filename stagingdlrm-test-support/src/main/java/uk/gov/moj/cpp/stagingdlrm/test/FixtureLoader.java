package uk.gov.moj.cpp.stagingdlrm.test;

import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Loads a JSON fixture from the test classpath, substituting {@code {{PARAM}}} placeholders.
 *
 * <p>Three deliberate behaviours make this safe to assert on (ADR-001 appendix): fixtures are read
 * as UTF-8, never the platform default; a supplied parameter absent from the fixture fails, so a
 * renamed placeholder cannot silently stop substituting; an unresolved <code>{{...}}</code> fails —
 * the DD-43078 FR1 guard, so an unbound source system cannot reach a comparison and pass.
 */
public final class FixtureLoader {

    private FixtureLoader() {
    }

    /** The empty JSON object — use instead of committing another one-line {@code {}} fixture. */
    public static String emptyJson() {
        return "{}";
    }

    public static String fixture(final String path) {
        return fixture(path, Map.of());
    }

    public static String fixture(final String path, final Map<String, String> parameters) {
        try (InputStream in = FixtureLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("Fixture not found on the test classpath: " + path);
            }
            String payload = new String(in.readAllBytes(), UTF_8);

            for (final Map.Entry<String, String> parameter : parameters.entrySet()) {
                final String token = "{{" + parameter.getKey() + "}}";
                if (!payload.contains(token)) {
                    throw new AssertionError(
                            "Parameter " + token + " was supplied but does not appear in " + path);
                }
                payload = payload.replace(token, parameter.getValue());
            }

            final int unresolved = payload.indexOf("{{");
            if (unresolved >= 0) {
                throw new AssertionError("Unresolved placeholder in " + path + ": "
                        + payload.substring(unresolved, min(unresolved + 40, payload.length())));
            }
            return payload;
        } catch (final IOException e) {
            throw new AssertionError("Failed to read fixture " + path, e);
        }
    }
}
