package uk.gov.moj.cpp.stagingdlrm.command.api.util;

import static java.lang.ClassLoader.getSystemResourceAsStream;
import static java.lang.String.format;
import static uk.gov.justice.services.messaging.JsonObjects.createReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import javax.json.JsonObject;
import javax.json.JsonReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonUtil.class);

    public static String resourceToString(final String path, final Object... placeholders) {
        try (final InputStream stream = getSystemResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("Resource not found: " + path);
            }
            return format(new String(stream.readAllBytes(), StandardCharsets.UTF_8), placeholders);
        } catch (final IOException e) {
            LOGGER.error("Error consuming file from location {}", path, e);
            throw new UncheckedIOException(e);
        }
    }

    public static JsonObject readJsonResource(final String filePath, final Object... placeholders) {
        return readJson(resourceToString(filePath, placeholders));
    }

    public static JsonObject readJson(final String payload) {
        try (final JsonReader reader = createReader(new StringReader(payload))) {
            return reader.readObject();
        }
    }

}
