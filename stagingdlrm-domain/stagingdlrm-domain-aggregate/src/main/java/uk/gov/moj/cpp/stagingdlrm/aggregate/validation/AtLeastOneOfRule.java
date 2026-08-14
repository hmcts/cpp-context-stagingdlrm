package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

import java.util.List;
import java.util.function.Function;

/**
 * Rejects a submission when none of a set of alternative fields is present — the code equivalent of a
 * JSON Schema {@code anyOf} of {@code required} branches. Reads each alternative through a typed
 * getter; stateless and immutable per ADR-002.
 */
public final class AtLeastOneOfRule implements MigratedCaseValidationRule {

    private final String containerPath;
    private final List<String> fieldNames;
    private final List<Function<MigratedCaseSubmission, Object>> values;

    private AtLeastOneOfRule(final String containerPath, final List<String> fieldNames,
                            final List<Function<MigratedCaseSubmission, Object>> values) {
        this.containerPath = containerPath;
        this.fieldNames = List.copyOf(fieldNames);
        this.values = List.copyOf(values);
    }

    public static AtLeastOneOfRule of(final String containerPath, final List<String> fieldNames,
                                      final List<Function<MigratedCaseSubmission, Object>> values) {
        return new AtLeastOneOfRule(containerPath, fieldNames, values);
    }

    @Override
    public List<ValidationError> apply(final RuleInput input) {
        final boolean anyPresent = values.stream().anyMatch(value -> value.apply(input.submission()) != null);
        if (!anyPresent) {
            return List.of(new ValidationError(containerPath,
                    "At least one of " + fieldNames + " is required at " + containerPath));
        }
        return List.of();
    }
}
