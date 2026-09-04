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

/**
 * The two "ErrorMigrateCaseSubmission" tests below close the BC-03 branch gap (see
 * docs/j25-parity-checklist.md and docs/pipeline/adrs/DD-43191-j25-parity-method.md's Bucket A table):
 * {@code command-migrate-case-submission-api.drl} declares two rules, and until now only
 * "Command - Rule for Migrate Case Submission" had any test coverage on any JDK. FR9 requires
 * both rules to have an allow and a deny case - a genuine J17 coverage fix as well as a parity
 * pin (this is not a behaviour change; both rules already existed).
 */
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

    // ------------------------------------------------------------------
    // BC-03 - the second rule, "Command - Rule for Error Migrate Case Submission", matching
    // action "stagingdlrm.receive-error-migrated-case-submission". Same allow/deny shape as the
    // rule above, over the previously-untested action name.
    // ------------------------------------------------------------------

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