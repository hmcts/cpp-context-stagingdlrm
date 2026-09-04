package uk.gov.moj.cpp.stagingdlrm.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;
import java.util.Enumeration;

import javax.json.spi.JsonProvider;

import org.junit.jupiter.api.Test;

/**
 * BC-11 parity test (see docs/j25-parity-checklist.md).
 *
 * <p>Unlike the other four affected modules, this module's own {@code pom.xml} does <b>not</b>
 * declare {@code org.glassfish:javax.json} at all - its only direct JSON-P coordinate is
 * {@code javax.json:javax.json-api:1.0} at {@code provided} scope. {@code org.glassfish:javax.json:1.1.4}
 * only reaches this module's <i>test</i> classpath transitively, via
 * {@code uk.gov.justice.services:test-utils-core} - the dependency this parity story itself added
 * to give this previously test-free module a JUnit runtime (see {@code stagingdlrm-domain-event/pom.xml}).
 * So the classpath state this test pins is one this story constructed, not one the module carried
 * independently - recorded here so a future reader doesn't mistake it for a pre-existing exposure.
 * It is still a legitimate pin: {@code test-utils-core} is exactly the kind of transitive dependency
 * that would carry a J25 Parsson jar too, were it added to the BOM this module inherits from.
 *
 * <p>Observed on J17, 2026-09-04: {@code ServiceLoader.load(JsonProvider.class)} itself finds
 * <b>zero</b> registered providers on this classpath - neither {@code org.glassfish:javax.json:1.1.4}
 * nor the API jar carries a {@code META-INF/services/javax.json.spi.JsonProvider} entry here.
 * {@link JsonProvider#provider()} instead resolves via its internal hard-coded default-class-name
 * fallback to {@code org.glassfish.json.JsonProviderImpl}. The decisive pin is therefore not a
 * {@code ServiceLoader} count but a classpath-resource count: exactly one class named
 * {@code org/glassfish/json/JsonProviderImpl.class} must be reachable, and zero
 * {@code META-INF/services/javax.json.spi.JsonProvider} registrations.
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
