package io.messagexform.standalone.proxy;

/**
 * Thrown when the upstream backend does not respond within the configured
 * read timeout (FR-004-25).
 *
 * <p>
 * Callers can use this to generate a {@code 504 Gateway Timeout} response.
 */
public class UpstreamTimeoutException extends UpstreamException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable error description
     * @param cause   the underlying timeout exception
     */
    public UpstreamTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
