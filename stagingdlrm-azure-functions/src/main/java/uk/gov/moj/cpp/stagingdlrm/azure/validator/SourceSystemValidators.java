package uk.gov.moj.cpp.stagingdlrm.azure.validator;

/**
 * DD-43086 FR4 — the pair of validators configured for one source system. {@code manifestValidator}
 * is the same shared instance across every source system's entry (FR5); only {@code caseValidator}
 * varies.
 */
public record SourceSystemValidators(JsonSchemaValidator caseValidator, JsonSchemaValidator manifestValidator) {

}