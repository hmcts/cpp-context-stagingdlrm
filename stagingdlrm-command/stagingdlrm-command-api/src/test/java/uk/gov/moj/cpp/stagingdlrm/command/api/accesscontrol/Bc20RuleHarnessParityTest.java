package uk.gov.moj.cpp.stagingdlrm.command.api.accesscontrol;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.definition.KiePackage;
import org.kie.api.definition.rule.Rule;

/**
 * BC-20 parity test (see docs/j25-parity-checklist.md). Guards the vacuous-deny failure mode: a
 * {@code KieBase} that silently loads zero rules makes every {@code *RulesTest} deny assertion pass
 * for the wrong reason, indistinguishable from a genuine allow/deny flip.
 *
 * <p>Deliberately interrogates the real, classpath-built {@code KieBase} directly rather than the
 * {@code StatelessKieSession} {@code AccessControlTest} uses - per the fleet-wide guide's reusable
 * lesson, a {@code StatelessKieSession} does not expose the {@code KieBase}, so the rule-count
 * assertion must go via {@code KieServices.get().getKieClasspathContainer().getKieBase(name)}.
 *
 * <p>Verified against {@code kmodule.xml}: {@code kbase name="COMMAND_API"
 * packages="uk.gov.moj.cpp.stagingdlrm.command.api.accesscontrol"}. The DRL itself declares no
 * explicit {@code package} statement, so Drools infers one from the resource's directory path - which
 * does match this kbase's {@code packages} filter (confirmed indirectly: {@link AccessControlTest}'s
 * existing allow/deny assertions only make sense if the rules are genuinely loaded and firing, which
 * they demonstrably are). This repo does <b>not</b> have the "{@code packages} names the resource
 * folder, the DRL declares a different {@code package}" gotcha the fleet-wide guide's
 * {@code system-doc-generator} entry warns about - checked, not assumed.
 */
class Bc20RuleHarnessParityTest {

    private static final String KBASE_NAME = "COMMAND_API";

    @Test
    void commandApiKieBaseLoadsExactlyTheTwoDeclaredRules() {
        final KieBase kieBase = KieServices.get().getKieClasspathContainer().getKieBase(KBASE_NAME);

        final Set<String> ruleNames = kieBase.getKiePackages().stream()
                .map(KiePackage::getRules)
                .flatMap(java.util.Collection::stream)
                .map(Rule::getName)
                .collect(toSet());

        assertEquals(Set.of(
                "Command - Rule for Migrate Case Submission",
                "Command - Rule for Error Migrate Case Submission"
        ), ruleNames, "BC-20 parity test: the " + KBASE_NAME + " KieBase must load exactly the 2 rules "
                + "command-migrate-case-submission-api.drl declares - a J25 zero-rule load would make "
                + "every deny assertion in AccessControlTest pass vacuously instead of genuinely");
    }
}
