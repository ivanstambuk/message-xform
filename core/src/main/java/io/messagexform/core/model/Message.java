package io.messagexform.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generic HTTP message envelope (DO-001-01). Gateway adapters produce instances
 * of this interface by
 * wrapping their native request/response objects. The engine operates
 * exclusively on {@code Message}
 * — it never touches gateway-native types.
 *
 * <p>
 * All fields are nullable except {@code body}, which defaults to a JSON null
 * node.
 *
 * <p>
 * Header names MUST be normalized to lowercase (RFC 9110 §5.1). The
 * {@link #headers()} map
 * contains first-value semantics; {@link #headersAll()} provides multi-value
 * access.
 */
public record Message(
        JsonNode body,
        Map<String, String> headers,
        Map<String, List<String>> headersAll,
        Integer statusCode,
        String contentType,
        String requestPath,
        String requestMethod,
        String queryString) {

    /**
     * Canonical constructor with validation.
     *
     * @param body          the JSON body — must not be null (use Jackson's NullNode
     *                      for absent bodies)
     * @param headers       single-value header map (lowercase keys)
     * @param headersAll    multi-value header map (lowercase keys)
     * @param statusCode    HTTP status code (null for requests if unknown)
     * @param contentType   the Content-Type header value (convenience accessor)
     * @param requestPath   the request path (needed for profile matching on
     *                      response transforms)
     * @param requestMethod the HTTP method (needed for profile matching)
     * @param queryString   the raw query string without leading '?' (e.g.
     *                      "key=val&other=x"), nullable
     */
    public Message {
        Objects.requireNonNull(body, "body must not be null; use NullNode for absent bodies");
        headers = headers != null ? Collections.unmodifiableMap(headers) : Map.of();
        headersAll = headersAll != null ? Collections.unmodifiableMap(headersAll) : Map.of();
    }

    /**
     * Convenience constructor without query string (backward compatibility).
     * All existing call sites use this form.
     */
    public Message(
            JsonNode body,
            Map<String, String> headers,
            Map<String, List<String>> headersAll,
            Integer statusCode,
            String contentType,
            String requestPath,
            String requestMethod) {
        this(body, headers, headersAll, statusCode, contentType, requestPath, requestMethod, null);
    }
}
