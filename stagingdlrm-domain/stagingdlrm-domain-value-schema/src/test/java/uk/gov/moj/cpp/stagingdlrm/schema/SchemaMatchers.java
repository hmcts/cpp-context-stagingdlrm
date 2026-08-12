package uk.gov.moj.cpp.stagingdlrm.schema;

import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.json.JSONObject;

final class SchemaMatchers {

    private SchemaMatchers() {
    }

    static Matcher<JSONObject> validatesAgainst(final Schema schema) {
        return new TypeSafeMatcher<>() {

            private String validationMessage;

            @Override
            protected boolean matchesSafely(final JSONObject payload) {
                try {
                    schema.validate(payload);
                    return true;
                } catch (final ValidationException e) {
                    validationMessage = e.getMessage();
                    return false;
                }
            }

            @Override
            public void describeTo(final Description description) {
                description.appendText("a payload valid against the schema");
            }

            @Override
            protected void describeMismatchSafely(final JSONObject item, final Description mismatch) {
                mismatch.appendText("validation failed with message ").appendValue(validationMessage);
            }
        };
    }

    static Matcher<JSONObject> failsValidationWithMessage(final Schema schema, final String expectedMessage) {
        return new TypeSafeMatcher<>() {

            private boolean rejected;
            private String actualMessage;

            @Override
            protected boolean matchesSafely(final JSONObject payload) {
                try {
                    schema.validate(payload);
                    rejected = false;
                    return false;
                } catch (final ValidationException e) {
                    rejected = true;
                    actualMessage = e.getMessage();
                    return expectedMessage.equals(actualMessage);
                }
            }

            @Override
            public void describeTo(final Description description) {
                description.appendText("validation failure with message ").appendValue(expectedMessage);
            }

            @Override
            protected void describeMismatchSafely(final JSONObject item, final Description mismatch) {
                if (rejected) {
                    mismatch.appendText("failed with message ").appendValue(actualMessage);
                } else {
                    mismatch.appendText("validation passed");
                }
            }
        };
    }
}
