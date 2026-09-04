package uk.gov.moj.cpp.stagingdlrm.schema.parity;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;

/**
 * BC-21 parity test (see docs/j25-parity-checklist.md) - {@code catalog-generation-plugin}
 * (reflections 0.9.10&rarr;0.10.2 scanning-contract change).
 *
 * <p>Asserts the generator's <b>contract</b> - schema-file-count on disk equals catalogue-entry-count
 * in the generated {@code META-INF/schema_catalog.json} - rather than a hard-coded manifest of every
 * generated type. A literal list would be edited by every future schema addition or removal; this
 * assertion survives that and only fails if the generator's scanning contract itself changes what it
 * discovers (per 01-requirements.md's own risk note against a maintenance-burden manifest).
 */
class SchemaCatalogGenerationParityTest {

    @Test
    void catalogEntryCountMatchesSchemaFileCountOnDisk() throws IOException, URISyntaxException {
        final long schemaFileCountOnDisk = countJsonSchemaFilesOnDisk();
        final int catalogEntryCount = countCatalogEntries();

        assertTrue(schemaFileCountOnDisk > 0, "BC-21 parity test: expected at least one schema file on disk to compare against");
        assertEquals(schemaFileCountOnDisk, catalogEntryCount,
                "BC-21 parity test: catalog-generation-plugin's generated catalogue should have exactly one entry "
                        + "per .json schema file - a mismatch means its reflections-based scanning contract "
                        + "discovered a different set than what is actually on disk");
    }

    private static long countJsonSchemaFilesOnDisk() throws IOException, URISyntaxException {
        final Path schemaRoot = classpathResourceAsPath("json/schema");
        try (Stream<Path> paths = Files.walk(schemaRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .count();
        }
    }

    private static Path classpathResourceAsPath(final String resource) throws URISyntaxException {
        final var url = Thread.currentThread().getContextClassLoader().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Classpath resource not found: " + resource);
        }
        return Paths.get(url.toURI());
    }

    private static int countCatalogEntries() {
        final JSONObject catalog = readCatalogResource();
        final JSONArray groups = catalog.getJSONArray("groups");
        int total = 0;
        for (int groupIndex = 0; groupIndex < groups.length(); groupIndex++) {
            total += groups.getJSONObject(groupIndex).getJSONArray("schemas").length();
        }
        return total;
    }

    private static JSONObject readCatalogResource() {
        try (final InputStream inputStream = requireResource("META-INF/schema_catalog.json");
             final Reader reader = new InputStreamReader(inputStream, UTF_8)) {
            return new JSONObject(new JSONTokener(reader));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream requireResource(final String resource) {
        final InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        if (inputStream == null) {
            throw new IllegalStateException("Classpath resource not found: " + resource);
        }
        return inputStream;
    }
}
