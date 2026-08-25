package uk.gov.moj.cpp.stagingdlrm.aggregate.validation;

public record ValidationError(String jsonPath, String message) {
}
