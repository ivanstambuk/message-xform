package io.messagexform.pingaccess;

/**
 * Adapter-local summary of a transform result, stored as an
 * {@link com.pingidentity.pa.sdk.http.ExchangeProperty} for downstream rule
 * consumption (FR-002-07).
 *
 * <p>
 * Uses only primitive / String fields so that any downstream rule can
 * read it without introducing a dependency on the core engine model.
 *
 * @param specId       matched spec ID, or null if no match
 * @param specVersion  matched spec version, or null if no match
 * @param direction    {@code "REQUEST"} or {@code "RESPONSE"}
 * @param durationMs   transform duration in milliseconds
 * @param outcome      {@code "SUCCESS"}, {@code "PASSTHROUGH"}, or
 *                     {@code "ERROR"}
 * @param errorType    error type code, or null if no error
 * @param errorMessage human-readable error message, or null if no error
 */
public record TransformResultSummary(
        String specId,
        String specVersion,
        String direction,
        long durationMs,
        String outcome,
        String errorType,
        String errorMessage) {}
