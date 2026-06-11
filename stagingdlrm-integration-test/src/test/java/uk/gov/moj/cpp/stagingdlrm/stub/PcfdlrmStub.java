package uk.gov.moj.cpp.stagingdlrm.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.justice.service.wiremock.testutil.InternalEndpointMockUtils.stubPingFor;
import static uk.gov.moj.cpp.stagingdlrm.helper.StubUtil.setupUsersGroupQueryStub;

import java.util.List;

import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;

public class PcfdlrmStub {
    public static String RECEIVE_MIGRATE_CASE_FILE = "/pcfdlrm-service/command/api/rest/pcfdlrm/receive-migrated-case-file";

    public static void stubForReceiveMigratedCaseFile() {
        stubPingFor("pcfdlrm-service");

        stubFor(post(urlPathEqualTo(RECEIVE_MIGRATE_CASE_FILE))
                .willReturn(aResponse().withStatus(SC_ACCEPTED))
        );
        setupUsersGroupQueryStub();
    }

    public static void verifyReceiveCaseFileRequested(final List<String> expectedValues) {
        await().atMost(10, SECONDS).pollInterval(5, SECONDS).until(() -> {
            RequestPatternBuilder requestPatternBuilder = postRequestedFor(urlPathMatching(RECEIVE_MIGRATE_CASE_FILE));
            expectedValues.forEach(
                    expectedValue -> requestPatternBuilder.withRequestBody(containing(expectedValue))
            );
            return true;
        });
    }

    public static void verifyReceiveCaseFileNotRequestedFor(final String submissionId) {
        await().atMost(5, SECONDS).pollInterval(2, SECONDS).until(() -> true);
        assertTrue(
                findAll(postRequestedFor(urlPathEqualTo(RECEIVE_MIGRATE_CASE_FILE))
                        .withRequestBody(containing(submissionId))).isEmpty(),
                "PCFDLRM receive-migrated-case-file should NOT have been called for submissionId: " + submissionId);
    }

}
