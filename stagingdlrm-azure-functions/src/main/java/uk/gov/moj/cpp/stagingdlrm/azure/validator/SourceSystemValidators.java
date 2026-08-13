package uk.gov.moj.cpp.stagingdlrm.azure.validator;

/**
 * The pair of validators a source system's submission is checked against (DD-43086 FR4/FR5).
 *
 * <p>Only the {@code caseValidator} varies by source system; the {@code manifestValidator} is a
 * single shared instance referenced by every entry in {@code TimerTriggerJava}'s
 * {@code validatorsBySourceSystem} map (FR5).
 */
public record SourceSystemValidators(JsonSchemaValidator caseValidator,
                                     JsonSchemaValidator manifestValidator) {
}