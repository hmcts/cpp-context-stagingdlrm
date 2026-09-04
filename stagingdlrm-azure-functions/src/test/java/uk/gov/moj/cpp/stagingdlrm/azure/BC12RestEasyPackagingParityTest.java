package uk.gov.moj.cpp.stagingdlrm.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * BC-12 parity test (see docs/j25-parity-checklist.md).
 *
 * <p>Unlike a WAR deployed into WildFly, the Function App has no container to supply a JAX-RS
 * engine, so its four RESTEasy artifacts must stay {@code compile}-scope and directly declared -
 * the fleet-wide RESTEasy-4-to-Jakarta-REST-3-swap fix of demoting them to {@code provided} +
 * {@code packagingExcludes} (see docs/pipeline/adrs/DD-43191-j25-upgrade-mechanics.md decision 5)
 * would turn into a runtime {@code NoClassDefFoundError} in Azure if applied here. Single decisive
 * build-time check (parity-method ADR decision 2), reading this module's own {@code pom.xml}
 * rather than booting a container - a packaging fact, not runtime behaviour.
 */
class BC12RestEasyPackagingParityTest {

    private static final Set<String> EXPECTED_RESTEASY_ARTIFACTS = Set.of(
            "resteasy-client", "resteasy-jaxb-provider", "resteasy-jackson2-provider", "resteasy-multipart-provider");

    @Test
    void fourResteasyArtifactsRemainDirectlyDeclaredAtCompileScope() throws Exception {
        final List<Element> resteasyDependencies = resteasyDependencyElements();

        assertEquals(EXPECTED_RESTEASY_ARTIFACTS.size(), resteasyDependencies.size(),
                "BC-12 parity test: expected exactly " + EXPECTED_RESTEASY_ARTIFACTS.size()
                        + " org.jboss.resteasy dependencies in stagingdlrm-azure-functions/pom.xml, found "
                        + resteasyDependencies.size());

        // Version is deliberately NOT pinned here - the upgrade story's RESTEasy-4-to-Jakarta-
        // REST-3 swap will legitimately move it, and pinning it would make this test fail for
        // that expected reason instead of the packaging regression (scope/bundling) it exists to
        // catch. See docs/pipeline/adrs/DD-43191-j25-upgrade-mechanics.md decision 5.
        final Set<String> foundArtifacts = new java.util.HashSet<>();
        for (final Element dependency : resteasyDependencies) {
            final String artifactId = childText(dependency, "artifactId");
            final String scope = childText(dependency, "scope");

            foundArtifacts.add(artifactId);
            assertNull(scope, "BC-12 parity test: " + artifactId + " must stay compile-scope (no <scope> element) - "
                    + "the Function App has no container to supply it. Found scope: " + scope);
        }
        assertEquals(EXPECTED_RESTEASY_ARTIFACTS, foundArtifacts);
    }

    private static List<Element> resteasyDependencyElements() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // The input is this module's own pom.xml, but disable DOCTYPE declarations anyway - an
        // XML parser without this is a Sonar-flagged XXE vulnerability regardless of input trust.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        final Element project = builder.parse(new File("pom.xml")).getDocumentElement();

        final List<Element> result = new ArrayList<>();
        final NodeList dependencyNodes = project.getElementsByTagName("dependency");
        for (int i = 0; i < dependencyNodes.getLength(); i++) {
            final Element dependency = (Element) dependencyNodes.item(i);
            if ("org.jboss.resteasy".equals(childText(dependency, "groupId"))) {
                result.add(dependency);
            }
        }
        return result;
    }

    private static String childText(final Element parent, final String tagName) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }
}
