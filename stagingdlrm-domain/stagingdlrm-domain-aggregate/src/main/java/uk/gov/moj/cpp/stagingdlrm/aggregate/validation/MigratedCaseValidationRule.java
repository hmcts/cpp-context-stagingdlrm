package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import java.util.List;

@FunctionalInterface
public interface MigratedCaseValidationRule {

    List<ValidationError> apply(RuleInput input);
}
