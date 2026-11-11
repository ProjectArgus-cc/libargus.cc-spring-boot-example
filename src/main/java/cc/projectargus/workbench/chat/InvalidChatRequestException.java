package cc.projectargus.workbench.chat;

/**
 * Thrown when a client-supplied chat request violates role, size, parameter, or token-budget bounds.
 */
public final class InvalidChatRequestException extends RuntimeException {

    private final String errorCode;

    public InvalidChatRequestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
