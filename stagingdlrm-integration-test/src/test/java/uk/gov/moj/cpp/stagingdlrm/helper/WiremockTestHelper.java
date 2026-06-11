package uk.gov.moj.cpp.stagingdlrm.helper;

import static javax.ws.rs.core.Response.Status.OK;
import static uk.gov.justice.services.test.utils.core.http.RequestParamsBuilder.requestParams;
import static uk.gov.justice.services.test.utils.core.http.RestPoller.poll;
import static uk.gov.justice.services.test.utils.core.matchers.ResponseStatusMatcher.status;
import static uk.gov.moj.cpp.stagingdlrm.helper.StubUtil.resetStubs;
import static uk.gov.moj.cpp.stagingdlrm.stub.PcfdlrmStub.stubForReceiveMigratedCaseFile;


import uk.gov.justice.services.test.utils.core.http.RequestParams;
import uk.gov.moj.cpp.stagingdlrm.stub.SystemIdMapperStub;

import java.util.UUID;

import javax.ws.rs.core.Response.Status;

/**
 * Provides helper methods for tests to interact with Wiremock instance
 */
public class WiremockTestHelper {

    private WiremockTestHelper() {
    }

    private static final String HOST = System.getProperty("INTEGRATION_HOST_KEY", "localhost");
    private static final String BASE_URI = "http://" + HOST + ":8080";

    public static void createCommonMockEndpoints() {
        resetStubs();
        stubForReceiveMigratedCaseFile();
        SystemIdMapperStub.stubGetCaseIdByURN("TVL55117DFXXV", UUID.fromString("51cac7fb-387c-4d19-9c80-8963fa8cf222"));
    }

    public static void waitForStubToBeReady(String resource, String mediaType) {
        waitForStubToBeReady(resource, mediaType, OK);
    }

    public static void waitForStubToBeReady(String resource, String mediaType, Status expectedStatus, String headerName, String headerValue) {
        final RequestParams requestParams = requestParams(BASE_URI + resource, mediaType)
                .withHeader(headerName, headerValue)
                .build();
        poll(requestParams)
                .until(
                        status().is(expectedStatus)
                );
    }

    public static void waitForStubToBeReady(String resource, String mediaType, Status expectedStatus) {
        final RequestParams requestParams = requestParams(BASE_URI + resource, mediaType).build();

        poll(requestParams)
                .until(
                        status().is(expectedStatus)
                );
    }
}
