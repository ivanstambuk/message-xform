package io.messagexform.core.model;

import java.util.Collections;
import java.util.Map;

/**
 * Read-only context passed to expression engines during evaluation (DO-001-07).
 * Provides access to HTTP metadata (headers, status, query params, cookies)
 * and optional session context so that JSLT expressions can reference
 * {@code $headers}, {@code $headers_all}, {@code $status},
 * {@code $queryParams}, {@code $cookies}, and {@code $session}.
 *
 * <p>
 * {@code $status} is {@code null} for request transforms (ADR-0017).
 *
 * <p>
 * {@code $session} is empty when no gateway session context is available
 * (FR-001-13, ADR-0030).
 *
 * <p>
 * All port-type fields ({@code headers}, {@code session}) are core-owned
 * value objects with zero third-party dependencies (ADR-0032, ADR-0033).
 * The {@code *AsJson()} conversion methods have moved to the engine
 * (they require Jackson, which is now an internal dependency).
 */
public record TransformContext(
        HttpHeaders headers,
        Integer status,
        Map<String, String> queryParams,
        Map<String, String> cookies,
        SessionContext session) {

    /** Canonical constructor with defensive copies. */
    public TransformContext {
        if (headers == null) headers = HttpHeaders.empty();
        queryParams = queryParams != null ? Collections.unmodifiableMap(queryParams) : Map.of();
        cookies = cookies != null ? Collections.unmodifiableMap(cookies) : Map.of();
        if (session == null) session = SessionContext.empty();
    }

    /** Creates an empty context (useful for tests and simple transforms). */
    public static TransformContext empty() {
        return new TransformContext(null, null, null, null, null);
    }
}
