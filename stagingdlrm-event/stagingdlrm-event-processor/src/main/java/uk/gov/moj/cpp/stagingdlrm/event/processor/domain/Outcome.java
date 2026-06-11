package uk.gov.moj.cpp.stagingdlrm.event.processor.domain;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@SuppressWarnings("squid:S6207")
public record Outcome(UUID caseId, UUID submissionId, String caseUrn, boolean success,
                      String description, String azureLocation) {
    @JsonCreator
    public Outcome(@JsonProperty("caseId") UUID caseId,
                   @JsonProperty("submissionId") UUID submissionId,
                   @JsonProperty("caseUrn") String caseUrn,
                   @JsonProperty("success") boolean success,
                   @JsonProperty("description") String description,
                   @JsonProperty("azureLocation") String azureLocation) {
        this.caseId = caseId;
        this.submissionId = submissionId;
        this.caseUrn = caseUrn;
        this.success = success;
        this.description = description;
        this.azureLocation = azureLocation;
    }
}
