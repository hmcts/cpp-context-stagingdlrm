package uk.gov.moj.cpp.stagingdlrm.viewstore.liquibase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * BC-07 parity test (see docs/j25-parity-checklist.md).
 *
 * <p>Liquibase 4&rarr;5 rejects properties it removed, as a pre-install migration-job failure -
 * a deploy blocker, not a runtime behaviour change (parity-method ADR's Bucket A table /
 * requirements FR13). Pins the exact key set in {@code liquibase.properties} so an unsupported key is caught
 * in {@code mvn test}, not in a K8s job. Deleting the removed keys is the upgrade story's job
 * (FR18) - this test only pins what is here today.
 */
class LiquibasePropertiesParityTest {

    private static final Set<String> EXPECTED_KEYS = Set.of("changelogFile", "liquibase.hub.mode", "liquibase.headless");

    @Test
    void pinsTheExactPropertyKeySet() throws Exception {
        final Properties properties = loadLiquibaseProperties();

        assertEquals(EXPECTED_KEYS, properties.stringPropertyNames(), "BC-07 parity test: liquibase.properties key set "
                + "has drifted from the J17 baseline - " + EXPECTED_KEYS + " expected, found " + properties.stringPropertyNames());
    }

    @Test
    void pinsTheObservedJ17Values() throws Exception {
        final Properties properties = loadLiquibaseProperties();

        assertEquals("liquibase/stagingdlrm.xml", properties.getProperty("changelogFile"));
        assertEquals("off", properties.getProperty("liquibase.hub.mode"));
        assertEquals("true", properties.getProperty("liquibase.headless"));
    }

    private static Properties loadLiquibaseProperties() throws Exception {
        final Properties properties = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("liquibase.properties")) {
            assertNotNull(in, "BC-07 parity test: liquibase.properties is not on the test classpath");
            properties.load(in);
        }
        return properties;
    }
}
