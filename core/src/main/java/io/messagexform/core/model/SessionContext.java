package io.messagexform.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gateway session context value object (DO-001-11, FR-001-14d).
 *
 * <p>
 * Holds arbitrary session attributes provided by the gateway adapter. This is a
 * core-owned port
 * type with zero third-party dependencies — adapters convert from their native
 * session types (e.g.,
 * PingAccess {@code IdentityMappingContext}) to {@code Map<String, Object>} and
 * use the factory
 * method (ADR-0032, ADR-0033).
 *
 * <p>
 * {@link #toString()} prints only key names (not values) to prevent leaking
 * sensitive session
 * data in logs.
 */
public final class SessionContext {

    private static final SessionContext EMPTY = new SessionContext(Map.of());

    private final Map<String, Object> attributes;

    private SessionContext(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    /**
     * Raw attribute value by key.
     *
     * @return the value, or {@code null} if absent
     */
    public Object get(String key) {
        return attributes.get(key);
    }

    /**
     * String-typed attribute by key.
     *
     * @return the string value, or {@code null} if absent or not a string
     */
    public String getString(String key) {
        Object value = attributes.get(key);
        return value instanceof String s ? s : null;
    }

    /**
     * True if the key exists.
     *
     * @param key the attribute key
     * @return {@code true} if the key is present
     */
    public boolean has(String key) {
        return attributes.containsKey(key);
    }

    /** True if empty or no attributes. */
    public boolean isEmpty() {
        return attributes.isEmpty();
    }

    /**
     * Returns a defensive copy of the underlying map.
     *
     * @return a mutable copy — callers may modify without affecting this context
     */
    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(attributes);
    }

    // ── Factory methods ──

    /**
     * Creates a session context from the given attributes.
     *
     * @param attributes session attributes (defensive copy is made); {@code null}
     *                   treated as empty
     * @return immutable {@code SessionContext}
     */
    public static SessionContext of(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return EMPTY;
        }
        return new SessionContext(Collections.unmodifiableMap(new LinkedHashMap<>(attributes)));
    }

    /**
     * Returns the empty session context (singleton).
     *
     * @return the empty context
     */
    public static SessionContext empty() {
        return EMPTY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionContext that)) return false;
        return Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attributes);
    }

    /**
     * Returns a string representation showing only key names (not values) to avoid
     * leaking
     * sensitive session data in logs.
     */
    @Override
    public String toString() {
        return "SessionContext" + attributes.keySet();
    }
}
