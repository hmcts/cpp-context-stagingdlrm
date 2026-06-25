package uk.gov.moj.cpp.stagingdlrm.azure.rest;

import static java.lang.String.format;
import static java.util.Objects.nonNull;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static uk.gov.justice.services.messaging.JsonObjects.createArrayBuilder;
import static uk.gov.justice.services.messaging.JsonObjects.createObjectBuilder;

import java.io.StringWriter;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import uk.gov.justice.services.messaging.JsonObjects;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonWriter;
import javax.net.ssl.SSLContext;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import com.microsoft.azure.functions.ExecutionContext;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;

public class StagingDlrmCommandHelper {

    private static final String FILE_TYPE = "fileType";
    private static final String DOCUMENT_TYPE = "documentType";
    public static final String AZURE_LOCATION = "azureLocation";

    private final ExecutionContext context;

    private final Client client;

    public StagingDlrmCommandHelper(final ExecutionContext context) {
        this.context = context;
        this.client = getClient(context);
    }

    public JsonObject generateErrorMigratedCaseSubmissionPayload(final String payload, final String submissionId, final String caseUrn, final String azureLocation, final String responseString) {
        context.getLogger().log(INFO, "Generating error migrated case submission payload for submissionId: {0}, caseUrn: {1}, azureLocation: {2}", new Object[]{submissionId, caseUrn, azureLocation});
        context.getLogger().log(INFO, "Error message: {0}", responseString);

        final JsonObjectBuilder errorMigratedCaseSubmissionJsonBuilder = createObjectBuilder();
        errorMigratedCaseSubmissionJsonBuilder.add("payload", payload);
        errorMigratedCaseSubmissionJsonBuilder.add("submissionId", submissionId);
        errorMigratedCaseSubmissionJsonBuilder.add("caseUrn", caseUrn);
        errorMigratedCaseSubmissionJsonBuilder.add(AZURE_LOCATION, azureLocation);
        errorMigratedCaseSubmissionJsonBuilder.add("errorMessage", responseString);

        return errorMigratedCaseSubmissionJsonBuilder.build();
    }

    public JsonObject generateErrorMigratedCaseSubmissionPayload(final JsonObject migratedCaseSubmissionJsonObject, final String submissionId, final String caseUrn, final String azureLocation, final String responseString) {
        context.getLogger().log(INFO, "Generating error migrated case submission payload from JsonObject for submissionId: {0}", submissionId);
        final String payload = convertToString(migratedCaseSubmissionJsonObject);
        return generateErrorMigratedCaseSubmissionPayload(payload, submissionId, caseUrn, azureLocation, responseString);
    }

    public JsonObject generateMigratedCaseSubmissionPayload(
            final JsonObject jsonInput, final List<String> materialFiles, final JsonObject metadataInput, String submissionId, String azureLocation) {

        context.getLogger().log(INFO, "Generating migrated case submission payload for submissionId: {0}, azureLocation: {1}", new Object[]{submissionId, azureLocation});

        final JsonObject migratedCaseJsonObject = jsonInput.getJsonObject("migratedCase");

        final JsonArrayBuilder materialJsonArrayBuilder = createArrayBuilder();

        final boolean materialAttached = metadataInput.containsKey("files");
        context.getLogger().log(INFO, "Material attached: {0}", materialAttached);

        final JsonObjectBuilder metadataJsonBuilder = createObjectBuilder();

        metadataJsonBuilder.add("id", UUID.randomUUID().toString());

        if (materialAttached) {

            final JsonArray metadataInputJsonArray = metadataInput.getJsonArray("files");

            metadataJsonBuilder.add("numberOfMaterials", metadataInputJsonArray.size());

            for (int i = 0, size = metadataInputJsonArray.size(); i < size; i++) {

                final JsonObject metadataInputJsonArrayJsonObject = metadataInputJsonArray.getJsonObject(i);

                final String fileName = metadataInputJsonArrayJsonObject.getString("fileName");

                final Optional<String> materialFileOptional = materialFiles.stream().filter(materialFile -> materialFile.endsWith(fileName)).findFirst();

                if (materialFileOptional.isEmpty()) {
                    context.getLogger().log(INFO, "No matching material file found for fileName: {0}", fileName);
                }

                final String materialFile = materialFileOptional.orElse("");

                final JsonObjectBuilder materialJsonBuilder = createObjectBuilder();

                materialJsonBuilder.add("id", UUID.randomUUID().toString());

                if(nonNull(metadataInputJsonArrayJsonObject.get(FILE_TYPE))) {
                    materialJsonBuilder.add(FILE_TYPE, metadataInputJsonArrayJsonObject.getString(FILE_TYPE));
                }

                if(nonNull(metadataInputJsonArrayJsonObject.get(DOCUMENT_TYPE))) {
                    materialJsonBuilder.add(DOCUMENT_TYPE, metadataInputJsonArrayJsonObject.getInt(DOCUMENT_TYPE));
                }

                addMaterialFileDetails(materialJsonBuilder, materialFile);

                materialJsonArrayBuilder.add(materialJsonBuilder.build());
            }
        } else {
            metadataJsonBuilder.add("numberOfMaterials", 0);
        }

        final JsonObjectBuilder caseSubmissionJsonBuilder = createObjectBuilder();

        caseSubmissionJsonBuilder.add("migratedCase", buildMigratedCaseJsonBuilder(migratedCaseJsonObject, metadataInput));

        if (materialAttached) {
            caseSubmissionJsonBuilder.add("materials", materialJsonArrayBuilder.build());
        }

        caseSubmissionJsonBuilder.add("metadata", metadataJsonBuilder.build());
        caseSubmissionJsonBuilder.add("submissionId", submissionId);
        caseSubmissionJsonBuilder.add(AZURE_LOCATION, azureLocation);

        return caseSubmissionJsonBuilder.build();
    }

    public Response sendPostCommandApi(String url, JsonObject jsonObject, String contentType, String stagingDlrmUid) {

        final String payload = convertToString(jsonObject);

        context.getLogger().log(INFO, "Payload : {0}", payload);
        context.getLogger().log(INFO, "URI : {0}", url);
        context.getLogger().log(INFO, "Content-type : {0}", contentType);
        context.getLogger().log(INFO, "CJSCPPUID : {0}", stagingDlrmUid);

        final Entity<String> entity = Entity.entity(payload, contentType);
        final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.add("Content-type", "application/json");
        headers.add("CJSCPPUID", stagingDlrmUid);

        return client.target(url)
                .request()
                .headers(headers)
                .post(entity);
    }

    private void addMaterialFileDetails(final JsonObjectBuilder materialJsonBuilder, final String materialFile) {
        if (!materialFile.isEmpty()) {
            final String[] materialFileSplit = materialFile.split("/");
            materialJsonBuilder.add(AZURE_LOCATION, materialFile);
            materialJsonBuilder.add("fileName", materialFileSplit[materialFileSplit.length - 1]);
        }
    }

    private JsonObject buildMigratedCaseJsonBuilder(final JsonObject migratedCaseJsonObject, final JsonObject metadataInput) {

        final JsonObject migrationSourceSystem = metadataInput.getJsonObject("migrationSourceSystem");

        final JsonObjectBuilder migratedCaseJsonBuilder = createObjectBuilder();

        if (nonNull(migratedCaseJsonObject)) {
            migratedCaseJsonBuilder.add("caseDetails", migratedCaseJsonObject.getJsonObject("caseDetails"));
            migratedCaseJsonBuilder.add("hearings", migratedCaseJsonObject.getJsonArray("hearings"));
            migratedCaseJsonBuilder.add("defendants", migratedCaseJsonObject.getJsonArray("defendants"));
        }

        if (nonNull(migrationSourceSystem)) {
            migratedCaseJsonBuilder.add("migrationSourceSystem", migrationSourceSystem);
        }
        return migratedCaseJsonBuilder.build();
    }

    private String convertToString(JsonObject jsonObject) {
        final StringWriter stringWriter = new StringWriter();
        try (JsonWriter jw = JsonObjects.createWriter(stringWriter)) {
            jw.write(jsonObject);
        }
        return stringWriter.toString();
    }


    private SSLContext getSslConnectionSocketFactory(final ExecutionContext context) {
        SSLContext sslContext = null;
        try {
            TrustStrategy acceptingTrustStrategy = new TrustAllStrategy();
            sslContext = SSLContexts.custom().loadTrustMaterial(acceptingTrustStrategy).build();
        } catch (KeyStoreException | NoSuchAlgorithmException | KeyManagementException ex) {
            context.getLogger().info(format("Error reading certificate : , %s", ex));
        }
        return sslContext;
    }

    private Client getClient(final ExecutionContext context) {
        return ClientBuilder
                .newBuilder()
                .sslContext(getSslConnectionSocketFactory(context))
                .build();
    }

}
