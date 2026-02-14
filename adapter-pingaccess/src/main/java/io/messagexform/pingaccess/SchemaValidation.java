package io.messagexform.pingaccess;

/**
 * Schema validation strictness for transform spec loading (FR-002-04).
 *
 * <p>
 * Used as a {@code @UIElement(type = SELECT)} field on
 * {@link MessageTransformConfig}. PingAccess's {@code ConfigurationBuilder}
 * auto-discovers the enum constants for the admin UI (SDK guide §7).
 *
 * <ul>
 * <li>{@link #STRICT} — reject specs that fail JSON Schema validation.
 * <li>{@link #LENIENT} — log warnings but accept specs that fail validation.
 * </ul>
 */
public enum SchemaValidation {
    STRICT("Strict"),
    LENIENT("Lenient");

    private final String label;

    SchemaValidation(String label) {
        this.label = label;
    }

    /** Returns the admin-UI display label. */
    @Override
    public String toString() {
        return label;
    }
}
