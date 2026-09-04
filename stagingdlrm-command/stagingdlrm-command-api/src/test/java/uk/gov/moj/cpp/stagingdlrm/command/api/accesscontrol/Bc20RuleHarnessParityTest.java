package uk.gov.moj.cpp.stagingdlrm.command.api.accesscontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import uk.gov.moj.cpp.accesscontrol.test.utils.BaseDroolsAccessControlTest;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.kie.api.definition.KiePackage;
import org.kie.api.definition.rule.Rule;

/**
 * BC-20 parity test (see docs/j25-parity-checklist.md).
 *
 * <p>Guards against the failure mode the investigation report identifies in
 * {@code BaseDroolsAccessControlTest}: because {@link uk.gov.moj.cpp.accesscontrol.drools.Outcome}
 * defaults {@code success} to {@code false}, a knowledge base that silently loads zero rules
 * (e.g. a {@code kmodule.xml} resolved via a {@code jar:} URL that a rewritten resource-resolution
 * path doesn't handle) makes every {@link AccessControlTest#shouldNotAllowSystemUser()}-style deny
 * assertion pass vacuously - indistinguishable from a genuine BC-03 allow/deny flip. This test
 * fails loudly instead of quietly.
 *
 * <p>Deliberately extends {@link BaseDroolsAccessControlTest} and reads {@code kSession.getKieBase()}
 * rather than building a second, independent {@code KieContainer} via
 * {@code KieServices.get().getKieClasspathContainer()}: the harness's own {@code setup()} is the
 * actual code path every other test in this class depends on, and is exactly what the investigation
 * report's BC-20 defect rewrites. A rule-count check against a container this test builds itself
 * would keep passing even if the harness's own loading path silently started producing zero rules -
 * the harness's real {@link #kSession} is the only decisive thing to interrogate.
 */
class Bc20RuleHarnessParityTest extends BaseDroolsAccessControlTest {

    private static final Set<String> EXPECTED_RULE_NAMES = Set.of(
            "Command - Rule for Migrate Case Submission",
            "Command - Rule for Error Migrate Case Submission");

    Bc20RuleHarnessParityTest() {
        super("COMMAND_API_SESSION");
    }

    @Override
    protected Map<Class<?>, Object> getProviderMocks() {
        // No rule is fired here - only the loaded rule set is inspected - so no provider global
        // needs a mock.
        return Collections.emptyMap();
    }

    @Test
    void commandApiKnowledgeBaseLoadsExactlyTheTwoDeclaredRules() {
        final Set<String> loadedRuleNames = new HashSet<>();
        for (final KiePackage kiePackage : kSession.getKieBase().getKiePackages()) {
            for (final Rule rule : kiePackage.getRules()) {
                loadedRuleNames.add(rule.getName());
            }
        }

        assertEquals(EXPECTED_RULE_NAMES, loadedRuleNames, "BC-20 parity test: the command-API knowledge "
                + "base loaded a different rule set than command-migrate-case-submission-api.drl declares - "
                + "a dropped rule here means the corresponding deny test in AccessControlTest is passing vacuously");
    }
}
