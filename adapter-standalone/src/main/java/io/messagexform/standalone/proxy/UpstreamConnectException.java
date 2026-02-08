package io.messagexform.standalone.proxy;

/**
 * Thrown when the upstream backend cannot be reached (FR-004-24).
 *
 * <p>
 * This wraps low-level network exceptions ({@code ConnectException},
 * DNS resolution failures) into a domain-specific exception that callers
 * can use to generate a {@code 502 Bad Gateway} response.
 */
public class UpstreamConnectException extends UpstreamException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable error description
     * @param cause   the underlying network exception
     */
    public UpstreamConnectException(String message, Throwable cause) {
        super(message, cause);
    }
}
