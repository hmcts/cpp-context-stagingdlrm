package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Rejects a submission when a single field is absent and a condition on the submission holds; when
 * the condition does not hold the field is optional. Same typed-getter shape and error as
 * {@link RequiredFieldRule}, so the rule stays a stateless, immutable, source-system-agnostic value
 * per ADR-002.
 */
public final class RequiredWhenRule implements MigratedCaseValidationRule {

    private final String jsonPath;
    private final Predicate<MigratedCaseSubmission> condition;
    private final Function<MigratedCaseSubmission, Object> value;

    private RequiredWhenRule(final String jsonPath,
                             final Predicate<MigratedCaseSubmission> condition,
                             final Function<MigratedCaseSubmission, Object> value) {
        this.jsonPath = jsonPath;
        this.condition = condition;
        this.value = value;
    }

    public static RequiredWhenRule of(final String jsonPath,
                                      final Predicate<MigratedCaseSubmission> condition,
                                      final Function<MigratedCaseSubmission, Object> value) {
        return new RequiredWhenRule(jsonPath, condition, value);
    }

    @Override
    public List<ValidationError> apply(final RuleInput input) {
        if (condition.test(input.submission()) && value.apply(input.submission()) == null) {
            return List.of(new ValidationError(jsonPath, "Missing required field: " + jsonPath));
        }
        return List.of();
    }
}
