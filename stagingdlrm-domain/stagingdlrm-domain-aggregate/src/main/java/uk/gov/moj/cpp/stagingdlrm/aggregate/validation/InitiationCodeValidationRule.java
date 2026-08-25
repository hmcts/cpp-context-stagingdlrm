package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

public final class InitiationCodeValidationRule implements MigratedCaseValidationRule {

    private static final String JSON_PATH = "$.migratedCase.caseDetails.initiationCode";

    private final Set<String> allowedValues;
    private final Function<MigratedCaseSubmission, String> value;

    private InitiationCodeValidationRule(final Set<String> allowedValues,
                                         final Function<MigratedCaseSubmission, String> value) {
        this.allowedValues = allowedValues;
        this.value = value;
    }

    public static InitiationCodeValidationRule withAllowedValues(final Function<MigratedCaseSubmission, String> value,
                                                                 final String... allowedValues) {
        return new InitiationCodeValidationRule(Set.of(allowedValues), value);
    }

    @Override
    public List<ValidationError> apply(final RuleInput input) {
        final String code = value.apply(input.submission());
        if (code != null && !allowedValues.contains(code)) {
            return List.of(new ValidationError(JSON_PATH,
                    "Initiation code '" + code + "' is not permitted for this source system; permitted values: " + allowedValues));
        }
        return List.of();
    }
}
