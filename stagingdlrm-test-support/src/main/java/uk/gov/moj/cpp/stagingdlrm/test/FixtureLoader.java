package uk.gov.moj.cpp.stagingdlrm.test;

import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public final class FixtureLoader {

    private static final String EMPTY_JSON = "{}";

    private FixtureLoader() {
    }

    public static String emptyJson() {
        return EMPTY_JSON;
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
