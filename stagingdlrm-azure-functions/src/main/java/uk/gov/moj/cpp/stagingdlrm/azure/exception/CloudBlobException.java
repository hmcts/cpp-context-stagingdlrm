package uk.gov.moj.cpp.stagingdlrm.azure.exception;

public class CloudBlobException extends RuntimeException {

    public CloudBlobException(final String message, Throwable cause) {
        super(message, cause);
    }
}
