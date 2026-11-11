package cc.projectargus.workbench.runtime;

/**
 * Thrown when a client submits a chat request with a {@code requestId} that is already owned
 * by an in-flight inference job.
 */
public final class DuplicateRequestIdException extends RuntimeException {

    private final String requestId;

    public DuplicateRequestIdException(String requestId) {
        super(
            "An active inference request with requestId '"
                + requestId
                + "' is already in progress. Use a unique requestId or wait for the active request to complete."
        );
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
