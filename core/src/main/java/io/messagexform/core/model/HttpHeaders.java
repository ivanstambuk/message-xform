package io.messagexform.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Case-insensitive HTTP header collection (DO-001-10, FR-001-14c).
 *
 * <p>
 * A core-owned port type with zero third-party dependencies. Replaces both
 * {@code Map<String,
 * String> headers} (single-value) and
 * {@code Map<String, List<String>> headersAll} (multi-value)
 * from the previous API, providing both views from a single type (ADR-0032,
 * ADR-0033).
 *
 * <p>
 * All header names are normalized to <strong>lowercase</strong> per RFC 9110
 * §5.1. The class is
 * immutable — no add/remove/set mutations.
 */
public final class HttpHeaders {

    private static final HttpHeaders EMPTY = new HttpHeaders(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

    /** Internal storage: case-insensitive key order, values are non-empty lists. */
    private final TreeMap<String, List<String>> store;

    private HttpHeaders(TreeMap<String, List<String>> store) {
        this.store = store;
    }

    /**
     * First value for a header name (case-insensitive).
     *
     * @return the first value, or {@code null} if the header is absent
     */
    public String first(String name) {
        List<String> values = store.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    /**
     * All values for a header name (case-insensitive).
     *
     * @return an unmodifiable list of values, or an empty list if absent
     */
    public List<String> all(String name) {
        List<String> values = store.get(name);
        return values != null ? Collections.unmodifiableList(values) : List.of();
    }

    /**
     * True if the header exists (case-insensitive).
     *
     * @param name the header name
     * @return {@code true} if present
     */
    public boolean contains(String name) {
        return store.containsKey(name);
    }

    /** Returns {@code true} if no headers are present. */
    public boolean isEmpty() {
        return store.isEmpty();
    }

    /**
     * First-value-per-name view with lowercase keys.
     *
     * @return an unmodifiable map
     */
    public Map<String, String> toSingleValueMap() {
        Map<String, String> result = new TreeMap<>();
        store.forEach((key, values) -> {
            if (!values.isEmpty()) {
                result.put(key.toLowerCase(), values.get(0));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * All-values-per-name view with lowercase keys.
     *
     * @return an unmodifiable map
     */
    public Map<String, List<String>> toMultiValueMap() {
        Map<String, List<String>> result = new TreeMap<>();
        store.forEach((key, values) -> result.put(key.toLowerCase(), Collections.unmodifiableList(values)));
        return Collections.unmodifiableMap(result);
    }

    // ── Factory methods ──

    /**
     * Creates headers from a single-value map. Keys are normalized to lowercase.
     *
     * @param singleValue header name → single value
     * @return immutable {@code HttpHeaders}
     */
    public static HttpHeaders of(Map<String, String> singleValue) {
        if (singleValue == null || singleValue.isEmpty()) {
            return EMPTY;
        }
        TreeMap<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        singleValue.forEach((key, value) -> map.put(key.toLowerCase(), List.of(value)));
        return new HttpHeaders(map);
    }

    /**
     * Creates headers from a multi-value map. Keys are normalized to lowercase.
     *
     * @param multiValue header name → list of values
     * @return immutable {@code HttpHeaders}
     */
    public static HttpHeaders ofMulti(Map<String, List<String>> multiValue) {
        if (multiValue == null || multiValue.isEmpty()) {
            return EMPTY;
        }
        TreeMap<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        multiValue.forEach((key, values) -> map.put(key.toLowerCase(), List.copyOf(values)));
        return new HttpHeaders(map);
    }

    /** Returns an empty headers instance. */
    public static HttpHeaders empty() {
        return EMPTY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HttpHeaders that)) return false;
        return store.equals(that.store);
    }

    @Override
    public int hashCode() {
        return store.hashCode();
    }

    @Override
    public String toString() {
        return "HttpHeaders" + store.keySet();
    }
}
