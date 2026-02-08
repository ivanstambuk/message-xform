package io.messagexform.standalone.proxy;

import java.util.Map;

/**
 * Immutable container for an upstream backend HTTP response.
 *
 * <p>
 * Returned by {@link UpstreamClient#forward} after forwarding a request
 * to the configured backend. All header names are normalized to lowercase.
 *
 * @param statusCode the HTTP status code from the backend
 * @param headers    single-value header map (lowercase keys, first value wins)
 * @param body       the response body as a string (empty string for no-body
 *                   responses like 204)
 */
public record UpstreamResponse(int statusCode, Map<String, String> headers, String body) {}
