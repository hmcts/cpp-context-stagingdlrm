package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * Checks each manifest {@code files[].uploadedDateTime}: it must be an ISO-8601 date-time and must
 * not be after the time the submission is processed. Every entry is checked, because the manifest
 * schema's {@code files.items} tuple only validates the first two.
 */
public class ManifestUploadDateValidator {

    private static final String FILES = "files";
    private static final String UPLOADED_DATE_TIME = "uploadedDateTime";

    private final Clock clock;

    public ManifestUploadDateValidator(final Clock clock) {
        this.clock = clock;
    }

    /**
     * @return one message per failing entry, in entry order; empty when every entry is valid
     */
    public List<String> validate(final JsonObject manifest) {
        final JsonArray files = manifest.containsKey(FILES) ? manifest.getJsonArray(FILES) : JsonValue.EMPTY_JSON_ARRAY;
        final Instant now = clock.instant();
        final List<String> messages = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            final JsonObject file = files.getJsonObject(i);
            if (!file.containsKey(UPLOADED_DATE_TIME)) {
                continue;
            }
            final String field = FILES + "[" + i + "]." + UPLOADED_DATE_TIME;
            final Optional<Instant> uploadedDateTime = parse(file.get(UPLOADED_DATE_TIME));
            if (uploadedDateTime.isEmpty()) {
                messages.add(field + " is not an ISO-8601 date-time");
            } else if (uploadedDateTime.get().isAfter(now)) {
                messages.add(field + " is in the future");
            }
        }
        return messages;
    }

    private static Optional<Instant> parse(final JsonValue value) {
        if (!(value instanceof JsonString jsonString)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(jsonString.getString()));
        } catch (final DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
