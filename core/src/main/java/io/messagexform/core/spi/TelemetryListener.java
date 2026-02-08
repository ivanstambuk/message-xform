package io.messagexform.core.spi;

import io.messagexform.core.model.Direction;

/**
 * SPI interface for observability hooks (T-001-42, NFR-001-09, ADR-0007).
 *
 * <p>
 * Adapters provide concrete implementations that bridge to OTel, Micrometer,
 * or other telemetry systems. The core engine has zero telemetry dependencies —
 * this is a pure Java interface.
 *
 * <p>
 * All methods receive immutable event objects. Implementations MUST be
 * thread-safe and non-blocking. Exceptions thrown by listeners are caught by
 * the engine and logged — they do NOT affect transform execution.
 *
 * <p>
 * Core metrics vocabulary (for adapter implementations):
 * <ul>
 * <li>{@code transform_evaluations_total} — counter, incremented on
 * completed/failed</li>
 * <li>{@code transform_duration_seconds} — histogram, eval duration</li>
 * <li>{@code profile_matches_total} — counter, incremented on matched</li>
 * <li>{@code spec_load_errors_total} — counter, incremented on rejected</li>
 * </ul>
 */
public interface TelemetryListener {

    /**
     * Called when a transform evaluation begins.
     *
     * @param event contains specId, specVersion, direction
     */
    void onTransformStarted(TransformStartedEvent event);

    /**
     * Called when a transform evaluation completes successfully.
     *
     * @param event contains specId, specVersion, direction, durationMs
     */
    void onTransformCompleted(TransformCompletedEvent event);

    /**
     * Called when a transform evaluation fails (expression error, budget exceeded,
     * etc.).
     *
     * @param event contains specId, specVersion, direction, durationMs, errorDetail
     */
    void onTransformFailed(TransformFailedEvent event);

    /**
     * Called when a profile entry matches a request.
     *
     * @param event contains profileId, specId, specVersion, requestPath,
     *              specificityScore
     */
    void onProfileMatched(ProfileMatchedEvent event);

    /**
     * Called when a spec is successfully loaded (or reloaded).
     *
     * @param event contains specId, specVersion, sourcePath
     */
    void onSpecLoaded(SpecLoadedEvent event);

    /**
     * Called when a spec is rejected at load time (parse error, compile error,
     * etc.).
     *
     * @param event contains sourcePath, errorDetail
     */
    void onSpecRejected(SpecRejectedEvent event);

    // --- Event records ---

    /** Event emitted when a transform evaluation starts. */
    record TransformStartedEvent(String specId, String specVersion, Direction direction) {}

    /** Event emitted when a transform evaluation completes successfully. */
    record TransformCompletedEvent(String specId, String specVersion, Direction direction, long durationMs) {}

    /** Event emitted when a transform evaluation fails. */
    record TransformFailedEvent(
            String specId, String specVersion, Direction direction, long durationMs, String errorDetail) {}

    /** Event emitted when a profile entry matches a request. */
    record ProfileMatchedEvent(
            String profileId, String specId, String specVersion, String requestPath, int specificityScore) {}

    /** Event emitted when a spec is successfully loaded. */
    record SpecLoadedEvent(String specId, String specVersion, String sourcePath) {}

    /** Event emitted when a spec is rejected at load time. */
    record SpecRejectedEvent(String sourcePath, String errorDetail) {}
}
