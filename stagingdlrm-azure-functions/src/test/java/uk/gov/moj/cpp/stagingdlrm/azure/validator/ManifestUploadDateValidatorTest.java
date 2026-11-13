package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import uk.gov.justice.services.messaging.JsonObjects;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import org.junit.jupiter.api.Test;

class ManifestUploadDateValidatorTest {

    private static final Instant NOW = Instant.parse("2026-11-11T14:04:07.107Z");

    private final ManifestUploadDateValidator validator =
            new ManifestUploadDateValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldAcceptUploadDatesInThePastOrEqualToNow() {
        assertThat(validator.validate(manifest("2019-03-14T10:22:31.107Z", "2026-11-11T14:04:07.107Z")), is(empty()));
    }

    @Test
    void shouldAcceptUploadDateWithAnOffset() {
        assertThat(validator.validate(manifest("2026-11-11T15:04:07.107+01:00")), is(empty()));
    }

    @Test
    void shouldRejectUploadDateOneMillisecondInTheFuture() {
        assertThat(validator.validate(manifest("2026-11-11T14:04:07.108Z")),
                contains("files[0].uploadedDateTime is in the future"));
    }

    @Test
    void shouldRejectUploadDateLaterToday() {
        assertThat(validator.validate(manifest("2019-03-14T10:22:31.107Z", "2026-11-11T23:00:00.000Z")),
                contains("files[1].uploadedDateTime is in the future"));
    }

    // The manifest schema only checks the first two entries (files.items is a tuple), so the format is
    // checked here for every entry.
    @Test
    void shouldRejectUnparseableUploadDateOnAnyEntry() {
        assertThat(validator.validate(manifest("2019-03-14T10:22:31.107Z", "2019-03-14T10:22:31.107Z", "14/03/2019")),
                contains("files[2].uploadedDateTime is not an ISO-8601 date-time"));
    }

    @Test
    void shouldRejectNonStringUploadDate() {
        final JsonObject manifest = JsonObjects.createObjectBuilder()
                .add("files", JsonObjects.createArrayBuilder().add(file().add("uploadedDateTime", 20190314)))
                .build();

        assertThat(validator.validate(manifest), contains("files[0].uploadedDateTime is not an ISO-8601 date-time"));
    }

    @Test
    void shouldAcceptEntriesWithoutUploadDate() {
        final JsonObject manifest = JsonObjects.createObjectBuilder()
                .add("files", JsonObjects.createArrayBuilder().add(file()).add(file()))
                .build();

        assertThat(validator.validate(manifest), is(empty()));
    }

    @Test
    void shouldAcceptManifestWithoutFiles() {
        assertThat(validator.validate(JsonObjects.createObjectBuilder().build()), is(empty()));
    }

    @Test
    void shouldReportEveryFailingEntry() {
        assertThat(validator.validate(manifest("2030-01-01T00:00:00.000Z", "2019-03-14T10:22:31.107Z", "not-a-date")),
                contains("files[0].uploadedDateTime is in the future",
                        "files[2].uploadedDateTime is not an ISO-8601 date-time"));
    }

    private static JsonObject manifest(final String... uploadedDateTimes) {
        final JsonArrayBuilder files = JsonObjects.createArrayBuilder();
        for (final String uploadedDateTime : uploadedDateTimes) {
            files.add(file().add("uploadedDateTime", uploadedDateTime));
        }
        return JsonObjects.createObjectBuilder().add("files", files).build();
    }

    private static JsonObjectBuilder file() {
        return JsonObjects.createObjectBuilder()
                .add("fileName", "WitnessStatementDocument_1.pdf")
                .add("fileType", "8")
                .add("documentType", 1);
    }
}
