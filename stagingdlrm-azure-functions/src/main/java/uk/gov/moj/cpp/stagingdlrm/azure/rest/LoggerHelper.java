package uk.gov.moj.cpp.stagingdlrm.azure.rest;

import static java.util.logging.Level.SEVERE;

import java.text.MessageFormat;
import java.util.logging.Level;

import com.microsoft.azure.functions.ExecutionContext;

public class LoggerHelper {

    private static final String SUBMISSION_ID_PREFIX = "[submissionId=%s] %s";

    public void logInfo(ExecutionContext context, String message) {
        context.getLogger().info(message);
    }

    public void logInfo(ExecutionContext context, String message, Object param) {
        context.getLogger().log(Level.INFO, message, param);
    }

    public void logInfo(ExecutionContext context, String message, Object[] params) {
        context.getLogger().log(Level.INFO, message, params);
    }

    public void logInfo(ExecutionContext context,
                        String submissionId,
                        String message) {

        context.getLogger().log(Level.INFO, () -> String.format(SUBMISSION_ID_PREFIX, submissionId, message));
    }

    public void logInfo(ExecutionContext context, final String submissionId, String message, Object param) {
        context.getLogger().log(Level.INFO, () -> String.format(SUBMISSION_ID_PREFIX, submissionId, MessageFormat.format(message, param)));
    }

    public void logInfo(final ExecutionContext context, final String submissionId, final String message, final Object[] params) {
        context.getLogger().log(Level.INFO, () -> String.format(SUBMISSION_ID_PREFIX, submissionId, MessageFormat.format(message, params)));
    }

    public void logSevere(final ExecutionContext context, final String submissionId, final String message) {
        context.getLogger().log(SEVERE, () -> String.format(SUBMISSION_ID_PREFIX, submissionId, message));
    }

    public void logSevere(final ExecutionContext context, final String submissionId, final String message, Object param) {
        context.getLogger().log(SEVERE, () -> String.format(SUBMISSION_ID_PREFIX, submissionId, MessageFormat.format(message, param)));
    }

    public void logSevere(final ExecutionContext context, final String message, Object param) {
        context.getLogger().log(SEVERE, message, param);
    }
}