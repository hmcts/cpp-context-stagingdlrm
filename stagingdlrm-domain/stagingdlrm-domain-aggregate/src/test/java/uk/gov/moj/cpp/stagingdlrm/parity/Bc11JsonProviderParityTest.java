package uk.gov.moj.cpp.stagingdlrm.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;
import java.util.Enumeration;

import javax.json.spi.JsonProvider;

import org.junit.jupiter.api.Test;

/**
 * BC-11 parity test (see docs/j25-parity-checklist.md).
 *
 * <p>This module declares {@code org.glassfish:javax.json} directly, at <b>test</b> scope, with
 * no version pinned locally - it resolves to {@code 1.1.4} via the BOM chain (see
 * {@code stagingdlrm-domain-aggregate/pom.xml}). {@code javax.json:javax.json-api:1.0} also reaches
 * this module's compile classpath, transitively via {@code stagingdlrm-domain-event} &rarr;
 * {@code messaging-core}. The J25 upgrade's exposure is a {@code ServiceLoader} collision once
 * Parsson also lands on the test classpath (glassfish &rarr; Parsson) - a test that merely calls a
 * JSON-P factory and succeeds proves nothing, since one provider still wins under a collision
 * (parity-method ADR "BC-11's assertion is about classpath state" risk note, requirements
 * 01-requirements.md Risks section). The count and identity of the resolved provider are the
 * assertions.
 *
 * <p>Observed on J17, 2026-09-04: {@code ServiceLoader.load(JsonProvider.class)} itself finds
 * <b>zero</b> registered providers on this classpath - neither {@code org.glassfish:javax.json:1.1.4}
 * nor the API jar carries a {@code META-INF/services/javax.json.spi.JsonProvider} entry here.
 * {@link JsonProvider#provider()} instead resolves via its internal hard-coded default-class-name
 * fallback to {@code org.glassfish.json.JsonProviderImpl}. The decisive pin is therefore not a
 * {@code ServiceLoader} count but a classpath-resource count: exactly one class named
 * {@code org/glassfish/json/JsonProviderImpl.class} must be reachable, and zero
 * {@code META-INF/services/javax.json.spi.JsonProvider} registrations - a J25 Parsson jar (which
 * does register properly via {@code ServiceLoader}) landing alongside would move both numbers.
 */
class Bc11JsonProviderParityTest {

    @Test
    void exactlyOneJsonProviderImplementationClassIsOnTheClasspath() throws Exception {
        assertEquals(1, countResources("org/glassfish/json/JsonProviderImpl.class"),
                "BC-11 parity test: expected exactly one org/glassfish/json/JsonProviderImpl.class on the classpath");
    }

    @Test
    void noServiceLoaderRegistrationExistsForJsonProviderYet() throws Exception {
        assertEquals(0, countResources("META-INF/services/javax.json.spi.JsonProvider"),
                "BC-11 parity test: expected zero ServiceLoader-registered JsonProvider entries on J17 - "
                        + "the resolution mechanism underneath JsonProvider.provider() has changed; re-derive this pin");
    }

    @Test
    void jsonProviderResolvesToTheSingleGlassfishImplementation() {
        final JsonProvider provider = JsonProvider.provider();

        assertEquals("org.glassfish.json.JsonProviderImpl", provider.getClass().getName());
    }

    private static int countResources(final String resourceName) throws Exception {
        final Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(resourceName);
        int count = 0;
        while (resources.hasMoreElements()) {
            resources.nextElement();
            count++;
        }
        return count;
    }
}
