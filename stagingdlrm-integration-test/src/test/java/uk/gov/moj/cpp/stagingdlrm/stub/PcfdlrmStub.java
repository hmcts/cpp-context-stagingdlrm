package uk.gov.moj.cpp.stagingdlrm.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.quote;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.justice.service.wiremock.testutil.InternalEndpointMockUtils.stubPingFor;
import static uk.gov.moj.cpp.stagingdlrm.helper.StubUtil.setupUsersGroupQueryStub;

import java.util.List;

import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;

public class PcfdlrmStub {
    public static String RECEIVE_MIGRATE_CASE_FILE = ".*/command/api/rest/pcfdlrm/receive-migrated-case-file";
    private static final String CASE_ID_JSON_PATH = "$.migratedCaseDetails.caseDetails.caseId";

    public static void stubForReceiveMigratedCaseFile() {
        stubPingFor("pcfdlrm-service");

        stubFor(post(urlPathMatching(RECEIVE_MIGRATE_CASE_FILE))
                .willReturn(aResponse().withStatus(SC_ACCEPTED))
        );
        setupUsersGroupQueryStub();
    }

    public static void verifyReceiveCaseFileRequested(final List<String> expectedValues) {
        await().atMost(30, SECONDS).pollInterval(1, SECONDS).untilAsserted(() -> {
            final RequestPatternBuilder requestPatternBuilder = postRequestedFor(urlPathMatching(RECEIVE_MIGRATE_CASE_FILE));
            expectedValues.forEach(
                    expectedValue -> requestPatternBuilder.withRequestBody(containing(expectedValue))
            );
            verify(requestPatternBuilder);
        });
    }

    public static void verifyReceiveCaseFileNotRequestedFor(final String submissionId) {
        await().atMost(5, SECONDS).pollInterval(2, SECONDS).until(() -> true);
        assertTrue(
                findAll(postRequestedFor(urlPathMatching(RECEIVE_MIGRATE_CASE_FILE))
                        .withRequestBody(containing(submissionId))).isEmpty(),
                "PCFDLRM receive-migrated-case-file should NOT have been called for submissionId: " + submissionId);
    }

    public static void verifyReceiveCaseFileRequestedWithCaseId(final String submissionId, final String expectedCaseId) {
        verifyReceiveCaseFileRequestedWithCaseIdMatching(submissionId, equalTo(expectedCaseId));
    }

    public static void verifyReceiveCaseFileRequestedWithCaseIdOtherThan(final String submissionId, final String unexpectedCaseId) {
        verifyReceiveCaseFileRequestedWithCaseIdMatching(submissionId, notMatching(quote(unexpectedCaseId)));
    }

    private static void verifyReceiveCaseFileRequestedWithCaseIdMatching(final String submissionId, final StringValuePattern caseIdPattern) {
        await().atMost(30, SECONDS).pollInterval(1, SECONDS).untilAsserted(() ->
                verify(postRequestedFor(urlPathMatching(RECEIVE_MIGRATE_CASE_FILE))
                        .withRequestBody(containing(submissionId))
                        .withRequestBody(matchingJsonPath(CASE_ID_JSON_PATH, caseIdPattern))));
    }

}
