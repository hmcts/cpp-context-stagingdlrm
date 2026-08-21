package uk.gov.moj.cpp.stagingdlrm.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.randomUUID;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static org.apache.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.http.HttpStatus.SC_OK;
import static uk.gov.justice.services.common.http.HeaderConstants.ID;
import static uk.gov.moj.cpp.stagingdlrm.helper.WiremockTestHelper.waitForStubToBeReady;

import uk.gov.justice.service.wiremock.testutil.InternalEndpointMockUtils;

import java.util.UUID;

public class ProgressionStub {

    private static final String PROGRESSION_PROSECUTION_CASE = "/progression-service/query/api/rest/progression/prosecutioncases/";
    private static final String PROSECUTION_CASE_ACTION = "progression.query.prosecutioncase";

    public static void stubProgressionProsecutionCase(final UUID caseId, final String caseStatus) {
        InternalEndpointMockUtils.stubPingFor("progression-service");

        stubFor(get(urlPathEqualTo(PROGRESSION_PROSECUTION_CASE + caseId))
                .willReturn(aResponse()
                        .withStatus(SC_OK)
                        .withHeader(ID, randomUUID().toString())
                        .withHeader(CONTENT_TYPE, "application/vnd." + PROSECUTION_CASE_ACTION + "+json")
                        .withBody("{\"_metadata\":{\"id\":\"" + randomUUID() + "\",\"name\":\"" + PROSECUTION_CASE_ACTION + "\"},"
                                + "\"prosecutionCase\":{\"caseStatus\":\"" + caseStatus + "\"}}")));
    }

    public static void stubProgressionProsecutionCaseNotFound(final UUID caseId) {
        InternalEndpointMockUtils.stubPingFor("progression-service");

        stubFor(get(urlPathEqualTo(PROGRESSION_PROSECUTION_CASE + caseId))
                .willReturn(aResponse()
                        .withStatus(SC_NOT_FOUND)));
    }
}
