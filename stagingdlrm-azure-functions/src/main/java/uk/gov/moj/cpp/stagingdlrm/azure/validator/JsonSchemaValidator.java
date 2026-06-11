package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import static java.util.Objects.requireNonNull;

import uk.gov.moj.cpp.stagingdlrm.azure.rest.LoggerHelper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

public class JsonSchemaValidator implements Validator<String> {

    private final JsonSchema jsonSchema;
    private final ObjectMapper objectMapper;
    private final ExecutionContext context;
    private final LoggerHelper loggerHelper;

    public JsonSchemaValidator(final ExecutionContext context, String schemaFile) {
        this.context = context;
        this.loggerHelper = new LoggerHelper();
        this.jsonSchema = loadJsonSchema(schemaFile);
        this.objectMapper = new ObjectMapper();
    }

    private JsonSchema loadJsonSchema(String schemaFile) {
        return JsonSchemaFactory
                .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4))
                .addUrnFactory(this::getResourceURI)
                .build()
                .getSchema(getResourceURI(schemaFile));
    }

    private URI getResourceURI(String resource) {
        try {
            return requireNonNull(Thread.currentThread().getContextClassLoader().getResource(resource)).toURI();
        } catch (URISyntaxException e) {
            loggerHelper.logSevere(context, "Failed to resolve URI for resource: {0}", resource);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<ValidationMessage> validate(final String submissionId, final String payload) {
        try {
            final JsonNode jsonNode = this.objectMapper.readTree(payload);

            if (jsonNode.isArray()) {
                loggerHelper.logSevere(context, submissionId, "JSON schema validation failed: payload is an array.");
                throw new RuntimeException("Json Schema validation failed");
            } else {
                return this.jsonSchema.validate(jsonNode);
            }
        } catch (JsonProcessingException jsonProcessingException) {
            loggerHelper.logSevere(context, submissionId, "Failed to parse payload for validation: {0}", jsonProcessingException.getMessage());
            throw new RuntimeException(jsonProcessingException);
        }
    }
}