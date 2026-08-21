package uk.gov.moj.cpp.stagingdlrm.event.processor.service;

import static java.lang.String.format;
import static java.util.UUID.randomUUID;

import uk.gov.justice.services.core.dispatcher.SystemUserProvider;
import uk.gov.moj.cpp.systemidmapper.client.SystemIdMap;
import uk.gov.moj.cpp.systemidmapper.client.SystemIdMapperClient;
import uk.gov.moj.cpp.systemidmapper.client.SystemIdMapping;

import java.util.Optional;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemMapperService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemMapperService.class);

    public static final String CONTEXT_SYSTEM_USER_ID_IS_NOT_PRESENT = "Context System User Id is not present";
    private static final String UNABLE_TO_CREATE_MAPPING = "Unable to creating mapping for input String %s to a uuid";
    private static final String SOURCE_TYPE = "OU_URN";
    private static final String TARGET_TYPE = "CASE_FILE_ID";
    private static final String EJECTED = "EJECTED";
    private static final String EJECTED_SUFFIX = "_Ejected";

    @Inject
    private SystemUserProvider systemUserProvider;

    @Inject
    private SystemIdMapperClient systemIdMapperClient;

    @Inject
    private ProgressionService progressionService;

    public CaseIdLookupResult getCaseIdForPtiURN(final String ptiUrn) {
        final UUID systemUserId = systemUserProvider.getContextSystemUserId()
                .orElseThrow(() -> new IllegalStateException(CONTEXT_SYSTEM_USER_ID_IS_NOT_PRESENT));

        final Optional<SystemIdMapping> existingMapping = systemIdMapperClient.findBy(
                ptiUrn, SOURCE_TYPE, TARGET_TYPE, systemUserId);

        if (existingMapping.isPresent()) {
            final UUID caseId = existingMapping.map(SystemIdMapping::getTargetId)
                    .orElseThrow(() -> new IllegalStateException(format(UNABLE_TO_CREATE_MAPPING, ptiUrn)));

            final Optional<String> status = getCaseStatus(caseId);
            status.ifPresentOrElse(
                    s -> LOGGER.info("Case {} exists in progression with status: {}", ptiUrn, s),
                    () -> LOGGER.info("Case {} not found in progression despite existing system-id-mapper entry", ptiUrn));

            if (status.isEmpty()) {
                return new CaseIdLookupResult(caseId, false);
            }

            if (status.filter(EJECTED::equals).isPresent()) {
                systemIdMapperClient.remap(ptiUrn + EJECTED_SUFFIX, existingMapping.get().getMappingId(), systemUserId);
                return createNewMapping(ptiUrn);
            }

            return new CaseIdLookupResult(caseId, true);
        }

        return createNewMapping(ptiUrn);
    }

    public static class CaseIdLookupResult {
        private final UUID caseId;
        private final boolean caseAlreadyProcessedAndExistsInProgression;

        public CaseIdLookupResult(final UUID caseId, final boolean caseAlreadyProcessedAndExistsInProgression) {
            this.caseId = caseId;
            this.caseAlreadyProcessedAndExistsInProgression = caseAlreadyProcessedAndExistsInProgression;
        }

        public UUID getCaseId() {
            return caseId;
        }

        public boolean isCaseAlreadyProcessedAndExistsInProgression() {
            return caseAlreadyProcessedAndExistsInProgression;
        }
    }

    private Optional<String> getCaseStatus(final UUID caseId) {
        return progressionService.getProsecutionCaseDetails(caseId)
                .filter(caseDetails -> caseDetails.containsKey("prosecutionCase"))
                .map(caseDetails -> caseDetails.getJsonObject("prosecutionCase").getString("caseStatus", "UNKNOWN"));
    }

    private CaseIdLookupResult createNewMapping(final String ptiUrn) {
        final UUID newCaseId = attemptAddMapping(randomUUID(), ptiUrn)
                .orElseThrow(() -> new IllegalStateException(format(UNABLE_TO_CREATE_MAPPING, ptiUrn)));
        return new CaseIdLookupResult(newCaseId, false);
    }

    private Optional<UUID> attemptAddMapping(final UUID newCaseId, final String ptiUrn) {
        final SystemIdMap systemIdMap = new SystemIdMap(ptiUrn, SOURCE_TYPE, newCaseId, TARGET_TYPE);
        if (systemIdMapperClient.add(systemIdMap,
                systemUserProvider.getContextSystemUserId()
                        .orElseThrow(() -> new IllegalStateException(CONTEXT_SYSTEM_USER_ID_IS_NOT_PRESENT)))
                .isSuccess()) {
            return Optional.of(newCaseId);
        }
        return Optional.empty();
    }
}
