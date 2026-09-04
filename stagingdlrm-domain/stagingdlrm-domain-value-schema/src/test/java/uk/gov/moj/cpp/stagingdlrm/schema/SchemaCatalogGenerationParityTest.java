package uk.gov.moj.cpp.stagingdlrm.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * BC-21 parity test (see docs/j25-parity-checklist.md).
 *
 * <p>{@code catalog-generation-plugin} scans {@code src/main/resources/json/schema} with
 * {@code org.reflections} (0.9.10 &rarr; 0.10.2 on the J25 upgrade) to build
 * {@code META-INF/schema_catalog.json}. Per the parity-method ADR's caution against a literal
 * generated-type manifest, this pins the generator's *contract* rather than a hard-coded list:
 * every {@code *.json} schema file on disk must appear exactly once in the generated catalogue.
 * A silently smaller catalogue - the reflections-scanning failure mode the J25 upgrade risks -
 * fails this test rather than surfacing later as an unresolved {@code $ref}.
 *
 * <p>Single decisive check, run in {@code mvn test} (build-time/component tier per the parity
 * method ADR decision 2) - not an integration test.
 */
class SchemaCatalogGenerationParityTest {

    @Test
    void generatedCatalogueContainsExactlyOneEntryPerSchemaFileOnDisk() throws Exception {
        final Set<String> schemaFilesOnDisk = schemaJsonFileNamesUnder("src/main/resources/json/schema");
        final Set<String> schemaLocationsInCatalog = schemaLocationsFromGeneratedCatalog();

        assertFalse(schemaFilesOnDisk.isEmpty(), "BC-21 parity test: found no *.json files under src/main/resources/json/schema "
                + "to compare against - is the working directory the module basedir?");
        assertEquals(schemaFilesOnDisk.size(), schemaLocationsInCatalog.size(),
                "BC-21 parity test: catalog-generation-plugin produced a different number of catalogue entries "
                        + "than there are schema files on disk - reflections scanning contract may have changed. "
                        + "On disk: " + schemaFilesOnDisk + " ; in catalog: " + schemaLocationsInCatalog);
        assertEquals(schemaFilesOnDisk, schemaLocationsInCatalog,
                "BC-21 parity test: the catalogue's (baseLocation + location) set does not match the schema "
                        + "file set on disk");
    }

    @Test
    void generatedCatalogueIsOnTheClasspathForRuntimeRefResolution() {
        // The framework's runtime schema validator - and this module's own BC-13 parity test's
        // ClasspathSchemaClient - both depend on META-INF/schema_catalog.json being packaged.
        final InputStream catalog = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/schema_catalog.json");

        assertNotNull(catalog, "BC-21 parity test: META-INF/schema_catalog.json is not on the test classpath");
    }

    private static Set<String> schemaJsonFileNamesUnder(final String relativeDir) throws IOException {
        final Path root = Paths.get(relativeDir);
        try (Stream<Path> paths = Files.walk(root)) {
            final Set<String> result = new HashSet<>();
            paths.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> result.add(root.relativize(p).toString().replace('\\', '/')));
            return result;
        }
    }

    private static Set<String> schemaLocationsFromGeneratedCatalog() throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get("target/generated-resources/META-INF/schema_catalog.json"))) {
            final JSONObject catalog = new JSONObject(new JSONTokener(in));
            final JSONArray groups = catalog.getJSONArray("groups");
            final Set<String> locations = new HashSet<>();
            for (int i = 0; i < groups.length(); i++) {
                final JSONObject group = groups.getJSONObject(i);
                // baseLocation is relative to src/main/resources/json/schema/ (jsonSchemaPath in
                // the plugin config), e.g. "" or "migrated/"; location is the file name within it.
                final String baseLocation = group.getString("baseLocation").replaceFirst("^json/schema/", "");
                final JSONArray schemas = group.getJSONArray("schemas");
                for (int j = 0; j < schemas.length(); j++) {
                    locations.add(baseLocation + schemas.getJSONObject(j).getString("location"));
                }
            }
            return locations;
        }
    }
}
