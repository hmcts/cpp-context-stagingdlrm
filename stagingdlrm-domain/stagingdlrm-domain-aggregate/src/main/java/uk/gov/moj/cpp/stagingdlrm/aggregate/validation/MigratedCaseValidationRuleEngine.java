package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;

import uk.gov.moj.cpp.stagingdlrm.json.schemas.CaseDetails;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

import java.util.List;
import java.util.Map;

public class MigratedCaseValidationRuleEngine {

    private static final Map<MigrationSourceSystemName, List<MigratedCaseValidationRule>> RULES = Map.of(
            XHIBIT, List.of(
                    RequiredFieldRule.of("$.migratedCase.caseDetails.dateReceived",
                            submission -> caseDetails(submission).getDateReceived()),
                    RequiredFieldRule.of("$.migratedCase.caseDetails.receiptType",
                            submission -> caseDetails(submission).getReceiptType()),
                    RequiredFieldRule.of("$.migratedCase.caseDetails.receivingCourt",
                            submission -> caseDetails(submission).getReceivingCourt()),
                    RequiredFieldRule.of("$.migratedCase.caseDetails.retrialIndicator",
                            submission -> caseDetails(submission).getRetrialIndicator()),
                    AtLeastOneOfRule.of("$.migratedCase.caseDetails",
                            List.of("dateOfCommittal", "dateOfSending"),
                            List.of(submission -> caseDetails(submission).getDateOfCommittal(),
                                    submission -> caseDetails(submission).getDateOfSending()))));

    public List<ValidationError> validate(final MigrationSourceSystemName sourceSystem,
                                          final MigratedCaseSubmission submission) {
        final RuleInput input = new RuleInput(submission);
        return RULES.getOrDefault(sourceSystem, List.of()).stream()
                .flatMap(rule -> rule.apply(input).stream())
                .toList();
    }

    private static CaseDetails caseDetails(final MigratedCaseSubmission submission) {
        return submission.getMigratedCase().getCaseDetails();
    }
}
