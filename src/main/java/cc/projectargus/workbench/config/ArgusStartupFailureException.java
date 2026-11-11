package cc.projectargus.workbench.config;

/**
 * Exception thrown during application startup when the local GGUF model or native runtime
 * cannot be initialized.
 */
public final class ArgusStartupFailureException extends RuntimeException {

    private final String errorCode;
    private final String remediation;

    public ArgusStartupFailureException(String errorCode, String message, String remediation) {
        super(message);
        this.errorCode = errorCode;
        this.remediation = remediation;
    }

    public ArgusStartupFailureException(String errorCode, String message, String remediation, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.remediation = remediation;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getRemediation() {
        return remediation;
    }
}
