package uk.gov.moj.cpp.stagingdlrm.command.api.accesscontrol;

import static java.util.Collections.singletonMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static uk.gov.justice.services.test.utils.core.messaging.MetadataBuilderFactory.metadataOf;

import uk.gov.justice.services.test.utils.core.messaging.JsonEnvelopeBuilder;
import uk.gov.moj.cpp.accesscontrol.common.providers.UserAndGroupProvider;
import uk.gov.moj.cpp.accesscontrol.drools.Action;
import uk.gov.moj.cpp.accesscontrol.test.utils.BaseDroolsAccessControlTest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kie.api.runtime.ExecutionResults;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AccessControlTest extends BaseDroolsAccessControlTest {

    @Mock
    private UserAndGroupProvider userAndGroupProvider;

    public AccessControlTest() {
        super("COMMAND_API_SESSION");
    }

    @Test
    public void shouldOnlyAllowSystemUser() {
        final Map<String, String> metadata = new HashMap<>();
        metadata.putIfAbsent("id", UUID.randomUUID().toString());
        metadata.putIfAbsent("name", "stagingdlrm.receive-migrated-case-submission");
        Action action = createActionFor(metadata);
        given(this.userAndGroupProvider.isSystemUser(action)).willReturn(true);
        final ExecutionResults results = executeRulesWith(action);
        assertSuccessfulOutcome(results);
        verify(userAndGroupProvider).isSystemUser(action);
    }

    @Test
    public void shouldNotAllowSystemUser() {
        final Map<String, String> metadata = new HashMap<>();
        metadata.putIfAbsent("id", UUID.randomUUID().toString());
        metadata.putIfAbsent("name", "stagingdlrm.receive-migrated-case-submission");
        Action action = createActionFor(metadata);
        given(this.userAndGroupProvider.isSystemUser(action)).willReturn(false);
        final ExecutionResults results = executeRulesWith(action);
        assertFailureOutcome(results);
        verify(userAndGroupProvider).isSystemUser(action);
    }

    /**
     * BC-03 parity test (see docs/j25-parity-checklist.md). {@code command-migrate-case-submission-api.drl}
     * declares two rules; only "Command - Rule for Migrate Case Submission" (above) had ever been
     * tested on any JDK. This closes that pre-existing coverage gap for "Command - Rule for Error
     * Migrate Case Submission" - a genuine J17 coverage fix as well as a parity pin. Note: BC-03 itself
     * (Drools 7-&gt;10 recompilation silently flipping allow/deny) is <b>Refuted</b> fleet-wide - this
     * test closes a testing gap that happens to share the ticket number, it does not mitigate a live
     * J25 risk.
     */
    @Test
    public void shouldOnlyAllowSystemUserForErrorMigrateCaseSubmission() {
        final Map<String, String> metadata = new HashMap<>();
        metadata.putIfAbsent("id", UUID.randomUUID().toString());
        metadata.putIfAbsent("name", "stagingdlrm.receive-error-migrated-case-submission");
        Action action = createActionFor(metadata);
        given(this.userAndGroupProvider.isSystemUser(action)).willReturn(true);
        final ExecutionResults results = executeRulesWith(action);
        assertSuccessfulOutcome(results);
        verify(userAndGroupProvider).isSystemUser(action);
    }

    @Test
    public void shouldNotAllowSystemUserForErrorMigrateCaseSubmission() {
        final Map<String, String> metadata = new HashMap<>();
        metadata.putIfAbsent("id", UUID.randomUUID().toString());
        metadata.putIfAbsent("name", "stagingdlrm.receive-error-migrated-case-submission");
        Action action = createActionFor(metadata);
        given(this.userAndGroupProvider.isSystemUser(action)).willReturn(false);
        final ExecutionResults results = executeRulesWith(action);
        assertFailureOutcome(results);
        verify(userAndGroupProvider).isSystemUser(action);
    }

    @Override
    protected Map<Class<?>, Object> getProviderMocks() {
        return singletonMap(UserAndGroupProvider.class, userAndGroupProvider);
    }

    @Override
    protected Action createActionFor(final Map<String, String> metadata) {
        JsonEnvelopeBuilder jsonEnvelopeBuilder = JsonEnvelopeBuilder.envelope().withPayloadOf(UUID.randomUUID().toString(), "caseId");
        return new Action(jsonEnvelopeBuilder.with(metadataOf(UUID.randomUUID().toString(), metadata.get("name"))).build());
    }
}