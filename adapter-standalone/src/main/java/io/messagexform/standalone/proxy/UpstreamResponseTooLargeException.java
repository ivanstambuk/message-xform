package io.messagexform.standalone.proxy;

/**
 * Thrown when the upstream backend response body exceeds
 * {@code proxy.max-body-bytes} (FR-004-13, S-004-56).
 *
 * <p>
 * Callers should translate this into a {@code 502 Bad Gateway} response
 * with an RFC 9457 Problem Details body.
 */
public class UpstreamResponseTooLargeException extends UpstreamException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable error description including body size
     *                and configured limit
     */
    public UpstreamResponseTooLargeException(String message) {
        super(message, null);
    }
}
