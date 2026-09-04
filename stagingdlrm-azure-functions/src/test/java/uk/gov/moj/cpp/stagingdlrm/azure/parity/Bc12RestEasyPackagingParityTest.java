package uk.gov.moj.cpp.stagingdlrm.azure.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * BC-12 parity test (see docs/j25-parity-checklist.md). This module's 4 {@code org.jboss.resteasy}
 * artifacts are declared at <b>compile</b> scope (no {@code <scope>} element - the default) because,
 * unlike a WAR deployed into WildFly, an Azure Function has no container to supply RESTEasy at
 * runtime. The fleet-wide "exclude the bundled RESTEasy" J25 fix pattern therefore does <b>not</b>
 * apply to this module - applying it would produce a runtime {@code NoClassDefFoundError} in Azure
 * (see the upgrade-mechanics ADR decision 5). This test reads {@code pom.xml} directly and pins that
 * exactly 4 artifacts remain, uninfluenced by scope - not their version, which the upgrade story's
 * Jakarta-REST engine swap will legitimately move; pinning the version would fail this test for the
 * wrong reason at upgrade time.
 */
class Bc12RestEasyPackagingParityTest {

    private static final String RESTEASY_GROUP_ID = "org.jboss.resteasy";

    @Test
    void exactlyFourCompileScopeRestEasyDependenciesAreDeclared() throws Exception {
        final Element project = readPomXml();
        final List<Element> resteasyDependencies = resteasyDependencyElements(project);

        assertEquals(4, resteasyDependencies.size(),
                "BC-12 parity test: expected exactly 4 org.jboss.resteasy dependencies in "
                        + "stagingdlrm-azure-functions/pom.xml - a change here means the fleet-wide "
                        + "RESTEasy repackaging may have silently reached this module's classpath");

        for (final Element dependency : resteasyDependencies) {
            final NodeList scopeElements = dependency.getElementsByTagName("scope");
            assertEquals(0, scopeElements.getLength(),
                    "BC-12 parity test: " + artifactId(dependency) + " must have no <scope> element "
                            + "(i.e. compile) - this Function App has no container to supply RESTEasy at runtime");
        }
    }

    private static List<Element> resteasyDependencyElements(final Element project) {
        final List<Element> result = new ArrayList<>();
        final NodeList dependencyElements = project.getElementsByTagName("dependency");
        for (int i = 0; i < dependencyElements.getLength(); i++) {
            final Element dependency = (Element) dependencyElements.item(i);
            if (RESTEASY_GROUP_ID.equals(groupId(dependency))) {
                result.add(dependency);
            }
        }
        assertTrue(result.size() > 0, "BC-12 parity test: found no org.jboss.resteasy dependencies at all - has pom.xml moved?");
        return result;
    }

    private static String groupId(final Element dependency) {
        return directChildText(dependency, "groupId");
    }

    private static String artifactId(final Element dependency) {
        return directChildText(dependency, "artifactId");
    }

    private static String directChildText(final Element parent, final String tagName) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static Element readPomXml() throws Exception {
        final File pomXml = new File("pom.xml");
        assertTrue(pomXml.isFile(), "BC-12 parity test: expected to find pom.xml at " + pomXml.getAbsolutePath()
                + " - Maven surefire normally runs with the module's basedir as the working directory");
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomXml).getDocumentElement();
    }
}
