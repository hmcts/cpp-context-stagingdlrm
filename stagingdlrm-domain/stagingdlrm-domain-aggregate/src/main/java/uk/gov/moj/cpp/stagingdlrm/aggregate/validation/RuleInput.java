package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

public record RuleInput(MigratedCaseSubmission submission) {
}
