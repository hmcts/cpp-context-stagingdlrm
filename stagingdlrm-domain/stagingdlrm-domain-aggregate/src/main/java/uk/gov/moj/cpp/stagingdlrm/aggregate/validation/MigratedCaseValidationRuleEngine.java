package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.LIBRA;
import static uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName.XHIBIT;

import uk.gov.moj.cpp.stagingdlrm.json.schemas.CaseDetails;
import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Defendant;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.Hearing;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
                                    submission -> caseDetails(submission).getDateOfSending())),
                    InitiationCodeValidationRule.withAllowedValues(
                            MigratedCaseValidationRuleEngine::initiationCode, "O")),
            LIBRA, List.of(
                    RequiredFieldRule.of("$.migratedCase.hearings[*].courtRoomId",
                            submission -> presentOnEvery(hearings(submission), Hearing::getCourtRoomId)),
                    RequiredFieldRule.of("$.migratedCase.hearings[*].dateOfHearing",
                            submission -> presentOnEvery(hearings(submission), Hearing::getDateOfHearing)),
                    RequiredFieldRule.of("$.migratedCase.hearings[*].timeOfHearing",
                            submission -> presentOnEvery(hearings(submission), Hearing::getTimeOfHearing)),
                    RequiredFieldRule.of("$.migratedCase.defendants[*].address",
                            submission -> presentOnEvery(defendants(submission), Defendant::getAddress)),
                    InitiationCodeValidationRule.withAllowedValues(
                            MigratedCaseValidationRuleEngine::initiationCode, "C", "Q", "J", "R")));

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

    private static String initiationCode(final MigratedCaseSubmission submission) {
        final CaseDetails caseDetails = caseDetails(submission);
        if (caseDetails == null || caseDetails.getInitiationCode() == null) {
            return null;
        }
        return caseDetails.getInitiationCode().name();
    }

    private static List<Hearing> hearings(final MigratedCaseSubmission submission) {
        return submission.getMigratedCase().getHearings();
    }

    private static List<Defendant> defendants(final MigratedCaseSubmission submission) {
        return submission.getMigratedCase().getDefendants();
    }

    /**
     * Non-null when every element of a repeating block carries the field, null when any element is
     * missing it. Lets a per-element LIBRA presence constraint reuse {@link RequiredFieldRule}
     * (which fires on a null value) with no new rule type. An absent block is not a violation of a
     * per-element rule, so a null/empty list passes.
     */
    private static <T> Object presentOnEvery(final List<T> items, final Function<T, Object> field) {
        if (items == null) {
            return Boolean.TRUE;
        }
        return items.stream().allMatch(item -> field.apply(item) != null) ? Boolean.TRUE : null;
    }
}
