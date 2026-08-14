package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

import uk.gov.moj.cpp.stagingdlrm.json.schemas.MigrationSourceSystemName;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCaseSubmission;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class MigratedCaseValidationRuleEngine {

    private static final Map<MigrationSourceSystemName, List<MigratedCaseValidationRule>> PRODUCTION_RULES =
            new EnumMap<>(MigrationSourceSystemName.class);

    private static final Map<MigrationSourceSystemName, List<MigratedCaseValidationRule>> RULES =
            new EnumMap<>(MigrationSourceSystemName.class);

    static {
        resetTestRules();
    }

    public List<ValidationError> validate(final MigrationSourceSystemName sourceSystem,
                                          final MigratedCaseSubmission submission) {
        final RuleInput input = new RuleInput(submission);
        return RULES.getOrDefault(sourceSystem, List.of()).stream()
                .flatMap(rule -> rule.apply(input).stream())
                .toList();
    }

    static void registerRuleForTest(final MigrationSourceSystemName sourceSystem,
                                    final MigratedCaseValidationRule rule) {
        RULES.computeIfAbsent(sourceSystem, key -> new ArrayList<>()).add(rule);
    }

    static void resetTestRules() {
        RULES.clear();
        PRODUCTION_RULES.forEach((sourceSystem, rules) -> RULES.put(sourceSystem, new ArrayList<>(rules)));
    }
}
