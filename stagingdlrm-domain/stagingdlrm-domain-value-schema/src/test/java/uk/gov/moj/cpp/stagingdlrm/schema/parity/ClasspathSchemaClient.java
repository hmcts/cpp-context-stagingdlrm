package uk.gov.moj.cpp.stagingdlrm.schema.parity;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import org.everit.json.schema.loader.SchemaClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * BC-13 parity test infrastructure (see docs/j25-parity-checklist.md).
 *
 * <p>Resolves everit's {@code $ref} lookups against this module's own generated
 * {@code META-INF/schema_catalog.json} (produced by {@code catalog-generation-plugin} at
 * {@code generate-sources} - already part of this module's build, no new plugin execution needed)
 * rather than guessing a classpath path from the {@code $ref} URI itself. This matters because
 * several schemas in this catalogue declare an {@code id} that does not match their file's own
 * name - {@code http://.../prosecutor.json} lives at {@code pcf-prosecutor.json},
 * {@code http://.../week-commencing-date.json} at {@code migrated-week-commencing-date.json},
 * {@code http://.../listed-defendant.json} at {@code migrated-listed-defendant.json} (all verified
 * against the catalogue on 2026-09-04). A resolver that reads the URI's own filename would silently
 * fail to load {@code case-details.json} or {@code migrated-hearing.json}, both of which
 * {@code $ref} a mismatched id.
 *
 * <p>One further mapping is added by hand: {@code http://justice.gov.uk/domain/core/common/definitions.json}
 * is not in this module's own catalogue - it is bundled inside the {@code common-core-domain} compile
 * dependency at classpath path {@code json/schema/definitions.json} (verified inside
 * {@code common-core-domain-17.104.4.jar}). Framework-owned, not this repo's to catalogue, but needed
 * to fully resolve {@code case-details.json}'s date fields.
 */
final class ClasspathSchemaClient implements SchemaClient {

    private static final String CATALOG_RESOURCE = "META-INF/schema_catalog.json";

    private static final String COMMON_CORE_DOMAIN_DEFINITIONS_ID =
            "http://justice.gov.uk/domain/core/common/definitions.json";
    private static final String COMMON_CORE_DOMAIN_DEFINITIONS_CLASSPATH = "json/schema/definitions.json";

    private final Map<String, String> idToClasspathResource;

    ClasspathSchemaClient() {
        this.idToClasspathResource = readCatalogue();
    }

    private static Map<String, String> readCatalogue() {
        final Map<String, String> map = new HashMap<>();
        map.put(COMMON_CORE_DOMAIN_DEFINITIONS_ID, COMMON_CORE_DOMAIN_DEFINITIONS_CLASSPATH);

        final JSONObject catalog = readCatalogResource();
        final JSONArray groups = catalog.getJSONArray("groups");
        for (int groupIndex = 0; groupIndex < groups.length(); groupIndex++) {
            final JSONObject group = groups.getJSONObject(groupIndex);
            final String baseLocation = group.getString("baseLocation");
            final JSONArray schemas = group.getJSONArray("schemas");
            for (int schemaIndex = 0; schemaIndex < schemas.length(); schemaIndex++) {
                final JSONObject schema = schemas.getJSONObject(schemaIndex);
                map.put(schema.getString("id"), baseLocation + schema.getString("location"));
            }
        }
        return map;
    }

    private static JSONObject readCatalogResource() {
        try (final InputStream inputStream = requireResource(CATALOG_RESOURCE);
             final Reader reader = new InputStreamReader(inputStream, UTF_8)) {
            return new JSONObject(new JSONTokener(reader));
        } catch (final IOException e) {
            throw new UncheckedIOException(
                    "BC-13 parity test: failed to read " + CATALOG_RESOURCE
                            + " - has catalog-generation-plugin's generate-schema-catalog execution run?", e);
        }
    }

    private static InputStream requireResource(final String resource) {
        final InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        if (inputStream == null) {
            throw new IllegalStateException(
                    "BC-13 parity test: classpath resource not found: " + resource);
        }
        return inputStream;
    }

    @Override
    public InputStream get(final String url) {
        final String classpathResource = idToClasspathResource.get(url);
        if (classpathResource == null) {
            throw new IllegalStateException(format(
                    "BC-13 parity test: no schema on the classpath declares id '%s' - "
                            + "the catalogue and this test's fixtures have drifted", url));
        }
        return requireResource(classpathResource);
    }
}
