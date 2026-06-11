package uk.gov.moj.cpp.stagingdlrm.helper;

import static java.util.UUID.randomUUID;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import uk.gov.justice.services.common.http.HeaderConstants;
import uk.gov.justice.services.integrationtest.utils.jms.JmsResourceManagementExtension;
import uk.gov.justice.services.test.utils.core.rest.RestClient;
import uk.gov.justice.services.test.utils.core.rest.ResteasyClientBuilderFactory;

import java.io.File;
import java.util.UUID;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import com.google.common.base.Joiner;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("WeakerAccess")
@ExtendWith(JmsResourceManagementExtension.class)
public abstract class AbstractTestHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTestHelper.class);

    public static final String USER_ID = UUID.randomUUID().toString();
    public static final String CONTEXT="stagingdlrm";

    private static final String HOST = System.getProperty("INTEGRATION_HOST_KEY", "localhost");
    protected static final String BASE_URI = System.getProperty("baseUri", "http://" + HOST + ":8080");
    private static final String WRITE_BASE_URL = "/stagingdlrm-command-api/command/api/rest/stagingdlrm";
    private static final String READ_BASE_URL = "/prosecutioncasefile-service/query/api/rest/prosecutioncasefile";


    protected final RestClient restClient = new RestClient();

    public static String getWriteUrl(final String resource) {
        return Joiner.on("").join(BASE_URI, WRITE_BASE_URL, resource);
    }

    public static String getReadUrl(final String resource) {
        return Joiner.on("").join(BASE_URI, READ_BASE_URL, resource);
    }

    static {
        doAllStubbing();
    }

    public static void doAllStubbing() {
    }

    protected void makePostCall(final String url, final String mediaType, final String payload) {
        makePostCall(url, mediaType, payload, Response.Status.ACCEPTED.getStatusCode());
    }

    protected void makePostCall(final String url, final String mediaType, final String payload, final int statusCode, final String...  expectedErrorMessage) {
        makePostCall(UUID.fromString(USER_ID), url, mediaType, payload, statusCode,expectedErrorMessage);
    }

    protected void makePostCall(final UUID userId, final String url, final String mediaType, final String payload, final int statusCode, final String...  expectedErrorMessage) {
        LOGGER.info("Post call made: \n\n\tURL = {} \n\tMedia type = {} \n\tPayload = {}\n\nUser ID = {}", url, mediaType, payload, USER_ID);
        final MultivaluedMap<String, Object> map = new MultivaluedHashMap<>();
        map.add(HeaderConstants.USER_ID, userId.toString());
        final Response response = restClient.postCommand(url, mediaType, payload, map);
        assertThat(response.getStatus(), is(statusCode));
        if (expectedErrorMessage != null && expectedErrorMessage.length > 0) {
            response.bufferEntity();
            String responseBody = response.readEntity(String.class);

            for (String error : expectedErrorMessage) {
                assertThat("Response body missing expected validation error: " + error,
                        responseBody, containsString(error));
            }
        }
    }

    protected UUID makeMultipartFormPostCall(final UUID userId, final String url, final String fileFieldName, final String fileName) {
        final File file = new File(fileName);
        final UUID correlationId = randomUUID();
        final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.add(HeaderConstants.USER_ID, userId.toString());
        headers.add(HeaderConstants.CLIENT_CORRELATION_ID, correlationId);
        final MultipartFormDataOutput mdo = new MultipartFormDataOutput();
        mdo.addFormData(fileFieldName, file, MediaType.MULTIPART_FORM_DATA_TYPE, file.getName());
        final GenericEntity<MultipartFormDataOutput> entity = new GenericEntity<MultipartFormDataOutput>(mdo) {
        };
        final Response response = ResteasyClientBuilderFactory.clientBuilder().build().target(getWriteUrl(url)).request().headers(headers).post(
                Entity.entity(entity, MediaType.MULTIPART_FORM_DATA_TYPE)
        );
        response.close();
        assertThat(response.getStatus(), is(Response.Status.ACCEPTED.getStatusCode()));
        return correlationId;
    }

}

