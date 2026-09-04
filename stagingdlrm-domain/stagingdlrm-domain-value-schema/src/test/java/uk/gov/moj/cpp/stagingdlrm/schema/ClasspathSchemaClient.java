package uk.gov.moj.cpp.stagingdlrm.schema;

import org.everit.json.schema.loader.SchemaClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * BC-13 parity test support (see docs/j25-parity-checklist.md).
 *
 * <p>The migrated-case-submission schema set resolves its {@code $ref}s via absolute
 * {@code http://cpp.moj.gov.uk/...} / {@code http://justice.gov.uk/...} ids, not relative
 * classpath paths, and the on-disk file names don't always match the last id path segment
 * (e.g. id {@code .../schemas/hearing.json} lives at {@code migrated-hearing.json}). Rather
 * than hand-maintaining an id-to-path table, this client merges every
 * {@code META-INF/schema_catalog.json} already on the test classpath — the same catalogue
 * {@code catalog-generation-plugin} produces for the framework's own runtime validator — from
 * this module and from {@code common-core-domain}, and resolves every {@code $ref} against it.
 */
final class ClasspathSchemaClient implements SchemaClient {

    private final Map<String, String> idToClasspathLocation = new HashMap<>();

    ClasspathSchemaClient() {
        try {
            final Enumeration<URL> catalogs = Thread.currentThread().getContextClassLoader()
                    .getResources("META-INF/schema_catalog.json");
            while (catalogs.hasMoreElements()) {
                mergeCatalog(catalogs.nextElement());
            }
        } catch (final IOException e) {
            throw new IllegalStateException("BC-13 parity test: failed to enumerate schema_catalog.json resources", e);
        }
        if (idToClasspathLocation.isEmpty()) {
            throw new IllegalStateException("BC-13 parity test: no META-INF/schema_catalog.json found on the test "
                    + "classpath - has the catalog-generation-plugin run (generate-sources)?");
        }
    }

    private void mergeCatalog(final URL catalogUrl) throws IOException {
        try (InputStream in = catalogUrl.openStream()) {
            final JSONObject catalog = new JSONObject(new JSONTokener(in));
            final JSONArray groups = catalog.getJSONArray("groups");
            for (int i = 0; i < groups.length(); i++) {
                final JSONObject group = groups.getJSONObject(i);
                final String baseLocation = group.getString("baseLocation");
                final JSONArray schemas = group.getJSONArray("schemas");
                for (int j = 0; j < schemas.length(); j++) {
                    final JSONObject schema = schemas.getJSONObject(j);
                    idToClasspathLocation.put(schema.getString("id"), baseLocation + schema.getString("location"));
                }
            }
        }
    }

    @Override
    public InputStream get(final String url) {
        final String withoutFragment = url.contains("#") ? url.substring(0, url.indexOf('#')) : url;
        final String classpathLocation = idToClasspathLocation.get(withoutFragment);
        if (classpathLocation == null) {
            throw new IllegalStateException("BC-13 parity test: no schema_catalog.json entry resolves '" + withoutFragment
                    + "' to a classpath resource. Known ids: " + idToClasspathLocation.keySet());
        }
        final InputStream resource = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathLocation);
        if (resource == null) {
            throw new IllegalStateException("BC-13 parity test: catalog maps '" + withoutFragment + "' to classpath "
                    + "resource '" + classpathLocation + "', but that resource is not on the classpath");
        }
        return resource;
    }
}
