package uk.gov.moj.cpp.stagingdlrm.it;

import static java.util.List.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClientProvider.newPrivateJmsMessageConsumerClientProvider;
import static uk.gov.moj.cpp.stagingdlrm.helper.FileUtil.getStringFromResource;
import static uk.gov.moj.cpp.stagingdlrm.helper.QueueUtil.retrieveMessageBody;
import static uk.gov.moj.cpp.stagingdlrm.helper.StubUtil.setupUsersGroupQueryStub;
import static uk.gov.moj.cpp.stagingdlrm.test.WholePayloadMatcher.matchesWholePayload;

import uk.gov.justice.services.integrationtest.utils.jms.JmsMessageConsumerClient;
import uk.gov.moj.cpp.stagingdlrm.helper.AbstractTestHelper;

import java.util.Optional;

import javax.json.JsonObject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReceiveErrorCaseSubmissionIT extends AbstractTestHelper {

    private static final JmsMessageConsumerClient consumerClient = newPrivateJmsMessageConsumerClientProvider(CONTEXT)
            .withEventNames("stagingdlrm.events.error-migrated-case-submission-received")
            .getMessageConsumerClient();

    @BeforeAll
    static void setUp() {
        setupUsersGroupQueryStub();
    }

    @Test
    void shouldAcceptReceiveErrorCaseSubmission() {
        final String payload = getStringFromResource("stagingdlrm.receive-error-migrated-case-submission.json");

        final String url = getWriteUrl("/receive-error-migrated-case-submission");
        makePostCall(url,
                "application/vnd.stagingdlrm.receive-error-migrated-case-submission+json",
                payload);

        final Optional<JsonObject> envelope = retrieveMessageBody(consumerClient);

        assertTrue(envelope.isPresent());
        assertThat(envelope.get().toString(),
                matchesWholePayload(getStringFromResource("expected/error-migrated-case-submission-received.json"), of()));
    }
}
