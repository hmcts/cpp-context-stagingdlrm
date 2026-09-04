package uk.gov.moj.cpp.stagingdlrm.command.api.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import uk.gov.justice.services.core.annotation.Handles;

/**
 * BC-21 parity test (see docs/j25-parity-checklist.md) - {@code messaging-client-generator-plugin}
 * (reflections 0.9.10&rarr;0.10.2 scanning-contract change).
 *
 * <p>Asserts the generator's <b>contract</b> - one {@code @Handles}-annotated method per media-type
 * JSON schema in {@code stagingdlrm-command-handler}'s own RAML - rather than a hard-coded manifest of
 * generated methods, so this test survives a future handler command being added or removed and only
 * fails if the generator's scanning contract itself discovers a different set (per
 * 01-requirements.md's own risk note against a maintenance-burden manifest).
 */
class Bc21MessagingClientGenerationParityTest {

    private static final String GENERATED_CLASS =
            "uk.gov.justice.api.RemoteCommandApi2CommandHandlerMessageStagingdlrmStagingdlrmHandlerCommand";

    private static final String COMMAND_HANDLER_RAML_SCHEMA_DIR =
            "../stagingdlrm-command-handler/src/raml/json/schema";

    @Test
    void handlesMethodCountMatchesCommandHandlerRamlSchemaCount() throws Exception {
        final int schemaCount = countRamlSchemaFiles();
        final long handlesMethodCount = countHandlesAnnotatedMethods();

        assertTrue(schemaCount > 0, "BC-21 parity test: expected at least one RAML schema file to compare against");
        assertEquals(schemaCount, handlesMethodCount,
                "BC-21 parity test: " + GENERATED_CLASS + " should carry exactly one @Handles method "
                        + "per stagingdlrm-command-handler RAML schema - a mismatch means "
                        + "messaging-client-generator-plugin's reflections-based scanning discovered a "
                        + "different set than what is actually declared");
    }

    private static int countRamlSchemaFiles() {
        final File schemaDir = new File(COMMAND_HANDLER_RAML_SCHEMA_DIR);
        assertTrue(schemaDir.isDirectory(), "BC-21 parity test: expected to find " + schemaDir.getAbsolutePath()
                + " - Maven surefire normally runs with this module's basedir as the working directory, "
                + "and stagingdlrm-command-handler is its sibling module");
        final FilenameFilter jsonFilter = (dir, name) -> name.endsWith(".json");
        final File[] schemaFiles = schemaDir.listFiles(jsonFilter);
        return schemaFiles == null ? 0 : schemaFiles.length;
    }

    private static long countHandlesAnnotatedMethods() throws ClassNotFoundException {
        final Class<?> generatedClass = Class.forName(GENERATED_CLASS);
        long count = 0;
        for (final Method method : generatedClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Handles.class)) {
                count++;
            }
        }
        return count;
    }
}
