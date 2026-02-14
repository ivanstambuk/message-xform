package io.messagexform.pingaccess;

/**
 * Error handling strategy for transform failures (FR-002-04, FR-002-11).
 *
 * <p>
 * Used as a {@code @UIElement(type = SELECT)} field on
 * {@link MessageTransformConfig}. PingAccess's {@code ConfigurationBuilder}
 * auto-discovers the enum constants for the admin UI (SDK guide §7).
 *
 * <ul>
 * <li>{@link #PASS_THROUGH} — log the error and continue with the original
 * message unchanged.
 * <li>{@link #DENY} — reject the request (or response) with an RFC 9457
 * error response.
 * </ul>
 */
public enum ErrorMode {
    PASS_THROUGH("Pass Through"),
    DENY("Deny");

    private final String label;

    ErrorMode(String label) {
        this.label = label;
    }

    /** Returns the admin-UI display label. */
    @Override
    public String toString() {
        return label;
    }
}
