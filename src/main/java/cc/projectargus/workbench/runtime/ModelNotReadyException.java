package cc.projectargus.workbench.runtime;

/**
 * Thrown when a chat request arrives while the local model or native runtime is not ready.
 */
public final class ModelNotReadyException extends RuntimeException {

    private final String errorCode;

    public ModelNotReadyException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
