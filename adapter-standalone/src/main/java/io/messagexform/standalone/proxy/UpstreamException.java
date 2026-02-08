package io.messagexform.standalone.proxy;

/**
 * Base exception for upstream backend communication failures.
 *
 * <p>
 * Subtypes represent specific failure modes:
 * <ul>
 * <li>{@link UpstreamConnectException} — connection refused, host unreachable
 * <li>{@link UpstreamTimeoutException} — read timeout exceeded
 * </ul>
 */
public abstract class UpstreamException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable error description
     * @param cause   the underlying exception
     */
    protected UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
