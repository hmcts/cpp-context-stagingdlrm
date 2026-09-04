package uk.gov.moj.cpp.stagingdlrm.command.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * BC-21 parity test (see docs/j25-parity-checklist.md).
 *
 * <p>{@code messaging-client-generator-plugin} scans {@code stagingdlrm-command-handler}'s
 * messaging RAML (fed to this module as a {@code raml}-classified dependency) with
 * {@code org.reflections} to generate one {@code @Handles}-annotated method per media type on
 * {@code RemoteCommandApi2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand}. Per the
 * parity-method ADR's caution against a literal generated-type manifest, this pins the
 * generator's *contract* - method count matches media-type count in the RAML source - rather
 * than a hard-coded number, so adding a media type doesn't require editing this test to keep
 * passing for the wrong reason.
 */
class Bc21MessagingClientGenerationParityTest {

    private static final String GENERATED_CLASS =
            "uk.gov.justice.api.RemoteCommandApi2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand";
    private static final Pattern MEDIA_TYPE_LINE = Pattern.compile("^\\s*application/vnd\\.[\\w.-]+\\+json:\\s*$");

    @Test
    void generatedMessagingClientHasOneHandlesMethodPerRamlMediaType() throws Exception {
        final int mediaTypeCount = countMediaTypesInCommandHandlerRaml();
        final int handlesMethodCount = countHandlesMethodsOn(GENERATED_CLASS);

        assertTrue(mediaTypeCount > 0, "BC-21 parity test: found no media types in "
                + "stagingdlrm-command-handler.messaging.raml to compare against");
        assertEquals(mediaTypeCount, handlesMethodCount, "BC-21 parity test: messaging-client-generator-plugin "
                + "produced a different number of @Handles methods (" + handlesMethodCount + ") than there are "
                + "media types (" + mediaTypeCount + ") in the source RAML - the reflections scanning contract "
                + "may have changed");
    }

    private static int countMediaTypesInCommandHandlerRaml() throws Exception {
        final List<String> lines = Files.readAllLines(
                Paths.get("../stagingdlrm-command-handler/src/raml/stagingdlrm-command-handler.messaging.raml"));
        int count = 0;
        for (final String line : lines) {
            final Matcher matcher = MEDIA_TYPE_LINE.matcher(line);
            if (matcher.matches()) {
                count++;
            }
        }
        return count;
    }

    private static int countHandlesMethodsOn(final String className) throws ClassNotFoundException {
        final Class<?> generatedClass = Class.forName(className);
        int count = 0;
        for (final Method method : generatedClass.getDeclaredMethods()) {
            for (final var annotation : method.getAnnotations()) {
                if (annotation.annotationType().getName().equals("uk.gov.justice.services.core.annotation.Handles")) {
                    count++;
                }
            }
        }
        return count;
    }
}
