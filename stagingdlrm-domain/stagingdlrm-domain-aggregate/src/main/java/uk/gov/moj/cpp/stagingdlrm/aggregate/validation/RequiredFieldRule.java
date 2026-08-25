package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

import java.util.List;
import java.util.function.Function;

/**
 * Rejects a submission when a single field is absent. The field is read through a typed getter
 * (no reflection, no path parsing) and the JSON path is carried only for the error message, so the
 * rule stays a stateless, immutable, source-system-agnostic value per ADR-002.
 */
public final class RequiredFieldRule implements MigratedCaseValidationRule {

    private final String jsonPath;
    private final Function<MigratedCaseSubmission, Object> value;

    private RequiredFieldRule(final String jsonPath, final Function<MigratedCaseSubmission, Object> value) {
        this.jsonPath = jsonPath;
        this.value = value;
    }

    public static RequiredFieldRule of(final String jsonPath, final Function<MigratedCaseSubmission, Object> value) {
        return new RequiredFieldRule(jsonPath, value);
    }

    @Override
    public List<ValidationError> apply(final RuleInput input) {
        if (value.apply(input.submission()) == null) {
            return List.of(new ValidationError(jsonPath, "Missing required field: " + jsonPath));
        }
        return List.of();
    }
}
