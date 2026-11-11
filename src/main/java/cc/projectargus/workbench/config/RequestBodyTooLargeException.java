package cc.projectargus.workbench.config;

import java.io.IOException;

/**
 * Thrown by {@link JsonRequestBodySizeFilter} when a client request body exceeds
 * the configured {@code ARGUS_MAX_REQUEST_BODY_BYTES} limit during stream reading.
 */
public final class RequestBodyTooLargeException extends IOException {

    private final long maxBytes;

    public RequestBodyTooLargeException(long maxBytes) {
        super("Request body exceeds the maximum allowed size of " + maxBytes + " bytes.");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
