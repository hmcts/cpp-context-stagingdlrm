package uk.gov.moj.cpp.stagingdlrm.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class FindSchemaDuplicatesTest {

    @Test
    public void testSchemaDuplicates() throws Exception {
        final List<DiscoveredSchema> schemas = discoverSchemas();

        // Ignore any schemas that originate from pcfdlrm artifacts
        final Map<String, Map<String, List<String>>> nameToChecksumToLocations = new HashMap<>();

        for (final DiscoveredSchema schema : schemas) {
            if (schema.isFromPcfdlrm()) {
                continue;
            }
            nameToChecksumToLocations
                    .computeIfAbsent(schema.fileName, k -> new HashMap<>())
                    .computeIfAbsent(schema.checksum, k -> new ArrayList<>())
                    .add(schema.location);
        }

        final StringBuilder errorMessage = new StringBuilder();
        boolean hasConflictingDuplicates = false;

        for (final Map.Entry<String, Map<String, List<String>>> entry : nameToChecksumToLocations.entrySet()) {
            final String fileName = entry.getKey();
            final Map<String, List<String>> checksumToLocations = entry.getValue();

            if (checksumToLocations.size() > 1) {
                hasConflictingDuplicates = true;
                errorMessage.append("Different schemas share the same file name: ")
                        .append(fileName)
                        .append('\n');
                for (final Map.Entry<String, List<String>> c : checksumToLocations.entrySet()) {
                    errorMessage.append("  checksum=").append(c.getKey()).append('\n');
                    for (final String loc : c.getValue()) {
                        errorMessage.append("    ").append(loc).append('\n');
                    }
                }
            }
        }

        assertTrue(!hasConflictingDuplicates,
                errorMessage.length() == 0 ? "Schema duplicates check failed" : errorMessage.toString());
    }

    private List<DiscoveredSchema> discoverSchemas() throws IOException, URISyntaxException, NoSuchAlgorithmException {
        final List<DiscoveredSchema> discovered = new ArrayList<>();
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        final Enumeration<URL> roots = cl.getResources("json/schema");

        while (roots.hasMoreElements()) {
            final URL url = roots.nextElement();
            final String protocol = url.getProtocol();

            if ("jar".equals(protocol)) {
                scanJarRoot(url, discovered);
            } else if ("file".equals(protocol)) {
                scanFileRoot(url, discovered);
            }
        }

        return discovered;
    }

    private void scanJarRoot(final URL url, final List<DiscoveredSchema> out)
            throws IOException, NoSuchAlgorithmException {
        final JarURLConnection conn = (JarURLConnection) url.openConnection();
        final JarFile jarFile = conn.getJarFile();
        final String entryRoot = conn.getEntryName() == null ? "json/schema" : conn.getEntryName();
        final boolean fromPcfdlrm = jarFile.getName().toLowerCase().contains("pcfdlrm");

        try (JarFile jf = jarFile) {
            final Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                final JarEntry je = entries.nextElement();
                final String name = je.getName();
                if (je.isDirectory()) {
                    continue;
                }
                if (!name.startsWith(entryRoot) || !name.endsWith(".json")) {
                    continue;
                }
                final String fileName = name.substring(name.lastIndexOf('/') + 1);
                try (InputStream is = new BufferedInputStream(jf.getInputStream(je))) {
                    final String checksum = sha256(is);
                    out.add(new DiscoveredSchema(fileName, checksum, jarFile.getName() + "!/" + name, fromPcfdlrm));
                }
            }
        }
    }

    private void scanFileRoot(final URL url, final List<DiscoveredSchema> out)
            throws URISyntaxException, IOException, NoSuchAlgorithmException {
        final Path root = Path.of(new URI(url.toString()));
        Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    try (InputStream is = new BufferedInputStream(Files.newInputStream(p))) {
                        final String checksum = sha256(is);
                        final String fileName = p.getFileName().toString();
                        final String location = root.relativize(p).toString();
                        final boolean fromPcfdlrm = location.toLowerCase().contains("pcfdlrm");
                        out.add(new DiscoveredSchema(fileName, checksum, location, fromPcfdlrm));
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private String sha256(final InputStream inputStream) throws IOException, NoSuchAlgorithmException {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            md.update(buffer, 0, read);
        }
        return Base64.getEncoder().encodeToString(md.digest());
    }

    private static final class DiscoveredSchema {
        final String fileName;
        final String checksum;
        final String location;
        final boolean fromPcfdlrm;

        DiscoveredSchema(final String fileName, final String checksum, final String location, final boolean fromPcfdlrm) {
            this.fileName = fileName;
            this.checksum = checksum;
            this.location = location;
            this.fromPcfdlrm = fromPcfdlrm;
        }

        boolean isFromPcfdlrm() {
            return fromPcfdlrm;
        }
    }
}
