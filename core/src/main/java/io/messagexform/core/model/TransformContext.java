package io.messagexform.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read-only context passed to expression engines during evaluation (DO-001-07). Provides access to
 * HTTP metadata (headers, status, query params, cookies) so that JSLT expressions can reference
 * {@code $headers}, {@code $headers_all}, {@code $status}, {@code $queryParams}, and {@code
 * $cookies}.
 *
 * <p>{@code $status} is {@code null} for request transforms (ADR-0017).
 */
public record TransformContext(
        Map<String, String> headers,
        Map<String, List<String>> headersAll,
        Integer status,
        Map<String, String> queryParams,
        Map<String, String> cookies) {

    /** Canonical constructor with defensive copies. */
    public TransformContext {
        headers = headers != null ? Collections.unmodifiableMap(headers) : Map.of();
        headersAll = headersAll != null ? Collections.unmodifiableMap(headersAll) : Map.of();
        queryParams = queryParams != null ? Collections.unmodifiableMap(queryParams) : Map.of();
        cookies = cookies != null ? Collections.unmodifiableMap(cookies) : Map.of();
    }

    /**
     * Converts the header map to a JsonNode for binding as {@code $headers} in expression engines.
     * Returns an ObjectNode with header names as keys and first-values as string values.
     */
    public JsonNode headersAsJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        headers.forEach(node::put);
        return node;
    }

    /**
     * Converts the multi-value header map to a JsonNode for binding as {@code $headers_all}. Each
     * key maps to an array of string values (ADR-0026).
     */
    public JsonNode headersAllAsJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        headersAll.forEach((key, values) -> {
            var arr = node.putArray(key);
            values.forEach(arr::add);
        });
        return node;
    }

    /**
     * Returns the status as a JsonNode. Returns NullNode for request transforms (ADR-0017).
     */
    public JsonNode statusAsJson() {
        return status != null ? JsonNodeFactory.instance.numberNode(status) : JsonNodeFactory.instance.nullNode();
    }

    /** Converts query params to a JsonNode for binding as {@code $queryParams}. */
    public JsonNode queryParamsAsJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        queryParams.forEach(node::put);
        return node;
    }

    /** Converts cookies to a JsonNode for binding as {@code $cookies}. */
    public JsonNode cookiesAsJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        cookies.forEach(node::put);
        return node;
    }

    /** Creates an empty context (useful for tests and simple transforms). */
    public static TransformContext empty() {
        return new TransformContext(null, null, null, null, null);
    }
}
