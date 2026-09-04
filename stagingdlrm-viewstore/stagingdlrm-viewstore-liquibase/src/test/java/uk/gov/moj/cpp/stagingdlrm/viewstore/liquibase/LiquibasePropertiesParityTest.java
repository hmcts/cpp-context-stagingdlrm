package uk.gov.moj.cpp.stagingdlrm.viewstore.liquibase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * BC-07 parity test (see docs/j25-parity-checklist.md). Liquibase 4&rarr;5 rejects properties it has
 * removed, failing the pre-install migration job at deploy time - a deploy blocker, not a runtime
 * behaviour change. Pins the exact key set (and J17 values) in this module's own
 * {@code liquibase.properties}, verified on disk on 2026-09-04, so an unsupported key added later is
 * caught in {@code mvn test} rather than in a K8s pre-install job. This module had zero Java before
 * this story.
 */
class LiquibasePropertiesParityTest {

    @Test
    void pinsTheExactKeySetAndJ17ValuesOfLiquibaseProperties() throws IOException {
        final Properties properties = new Properties();
        try (final InputStream inputStream =
                     Thread.currentThread().getContextClassLoader().getResourceAsStream("liquibase.properties")) {
            properties.load(inputStream);
        }

        assertEquals(Set.of("changelogFile", "liquibase.hub.mode", "liquibase.headless"), properties.stringPropertyNames(),
                "BC-07 parity test: liquibase.properties's key set has changed - a key Liquibase 5 has "
                        + "removed support for would fail the upgrade story's deploy, not this test");
        assertEquals("liquibase/stagingdlrm.xml", properties.getProperty("changelogFile"));
        assertEquals("off", properties.getProperty("liquibase.hub.mode"));
        assertEquals("true", properties.getProperty("liquibase.headless"));
    }
}
