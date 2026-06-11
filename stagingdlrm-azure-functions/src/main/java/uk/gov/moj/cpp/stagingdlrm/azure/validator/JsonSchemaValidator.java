package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

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

    public JsonSchemaValidator(final ExecutionContext context, String schemaFile) {
        this.context = context;
        context.getLogger().log(INFO, "Loading JSON schema: {0}", schemaFile);
        this.jsonSchema = loadJsonSchema(schemaFile);
        this.objectMapper = new ObjectMapper();
        context.getLogger().log(INFO, "Successfully loaded JSON schema: {0}", schemaFile);
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
            context.getLogger().log(SEVERE, "Failed to resolve URI for resource: {0}", resource);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<ValidationMessage> validate(final String payload) {
        context.getLogger().log(INFO, "Validating payload against JSON schema.");
        try {
            final JsonNode jsonNode = this.objectMapper.readTree(payload);

            if (jsonNode.isArray()) {
                context.getLogger().log(SEVERE, "JSON schema validation failed: payload is an array.");
                throw new RuntimeException("Json Schema validation failed");
            } else {
                final Set<ValidationMessage> validationMessages = this.jsonSchema.validate(jsonNode);
                context.getLogger().log(INFO, "Validation complete. Number of violations: {0}", validationMessages.size());
                return validationMessages;
            }
        } catch (JsonProcessingException jsonProcessingException) {
            context.getLogger().log(SEVERE, "Failed to parse payload for validation: {0}", jsonProcessingException.getMessage());
            throw new RuntimeException(jsonProcessingException);
        }
    }
}
