package uk.gov.moj.cpp.stagingdlrm.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static uk.gov.moj.cpp.stagingdlrm.helper.FileUtil.getStringFromResource;
import static uk.gov.moj.cpp.stagingdlrm.helper.WiremockTestHelper.waitForStubToBeReady;

import uk.gov.justice.service.wiremock.testutil.InternalEndpointMockUtils;

import java.util.UUID;

public class SystemIdMapperStub {


    public static void stubGetCaseIdByURN(final String urn, final UUID cppCaseId) {
        InternalEndpointMockUtils.stubPingFor("system-id-mapper-api");

        String systemMapping = getStringFromResource("stub-data/systemid.mapping.json");
        systemMapping = systemMapping.replace("CASE-UUID", cppCaseId.toString());

        stubFor(get(urlPathMatching("/system-id-mapper-api/rest/systemid/mappings"))
                .withQueryParam("sourceId", containing(urn))
                .withQueryParam("sourceType", containing("OU_URN"))
                .withQueryParam("targetType", containing("CASE_FILE_ID"))
                .willReturn(aResponse().withStatus(200)
                        .withBody(systemMapping)));
        waitForStubToBeReady("/system-id-mapper-api/rest/systemid/mappings?sourceId=" + urn + "&sourceType=OU_URN&targetType=CASE_FILE_ID", "application/vnd.systemid.mapping+json");
    }

    public static void stubAddMapping() {
        stubFor(post(urlPathMatching("/system-id-mapper-api/rest/systemid/mappings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + UUID.randomUUID() + "\",\"code\":\"OK\"}")));
    }

}
