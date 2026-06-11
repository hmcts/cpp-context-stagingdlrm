package uk.gov.moj.cpp.stagingdlrm.command.api.util;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.json.JsonObject;

import org.junit.jupiter.api.Test;

class JsonUtilTest {

    @Test
    void shouldReadJsonFromString() {
        final String json = "{\"key\": \"value\", \"number\": 42}";
        final JsonObject result = JsonUtil.readJson(json);

        assertThat(result.getString("key"), is("value"));
        assertThat(result.getInt("number"), is(42));
    }

    @Test
    void shouldReadJsonResourceFromClasspath() {
        final JsonObject result = JsonUtil.readJsonResource("json-util-test-simple.json");

        assertThat(result.getString("key"), is("value"));
        assertThat(result.getInt("number"), is(42));
    }

    @Test
    void shouldReadJsonResourceWithPlaceholders() {
        final JsonObject result = JsonUtil.readJsonResource("json-util-test-with-placeholder.json", "DLRM");

        assertThat(result.getString("message"), is("MOJ DLRM"));
    }

    @Test
    void shouldReadResourceToString() {
        final String result = JsonUtil.resourceToString("json-util-test-simple.json").trim();

        assertThat(result, is("{\"key\": \"value\", \"number\": 42}"));
    }

    @Test
    void shouldReadResourceToStringWithPlaceholders() {
        final String result = JsonUtil.resourceToString("json-util-test-with-placeholder.json", "DLRM").trim();

        assertThat(result, is("{\"message\": \"MOJ DLRM\"}"));
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenResourceNotFound() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> JsonUtil.resourceToString("non-existent-resource.json"));

        assertThat(exception.getMessage(), is("Resource not found: non-existent-resource.json"));
    }

    @Test
    void shouldReadComplexJsonResource() {
        final JsonObject result = JsonUtil.readJsonResource("receive-migrated-case-submission.json");

        assertThat(result.containsKey("migratedCase"), is(true));
        final JsonObject migratedCase = result.getJsonObject("migratedCase");
        assertThat(migratedCase.containsKey("caseDetails"), is(true));
    }
}
