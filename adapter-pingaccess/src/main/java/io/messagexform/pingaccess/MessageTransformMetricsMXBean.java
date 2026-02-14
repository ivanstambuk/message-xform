package io.messagexform.pingaccess;

/**
 * JMX MXBean interface for transform metrics (FR-002-14).
 *
 * <p>
 * Exposes aggregate counters, spec reload stats, and latency measurements.
 * Registered under ObjectName
 * {@code io.messagexform:type=TransformMetrics,instance=<pluginName>}
 * when {@code enableJmxMetrics = true}.
 *
 * <p>
 * All counters are thread-safe and lock-free (backed by {@code LongAdder}).
 *
 * @see MessageTransformMetrics
 */
public interface MessageTransformMetricsMXBean {

    // --- Counters ---

    /** Successful transforms (body modified). */
    long getTransformSuccessCount();

    /** PASSTHROUGH results (no match / no-op). */
    long getTransformPassthroughCount();

    /** Transform failures (JSLT error, budget exceeded). */
    long getTransformErrorCount();

    /** DENY outcomes (errorMode=DENY + failure). */
    long getTransformDenyCount();

    /** Sum of all transform outcomes. */
    long getTransformTotalCount();

    // --- Spec reload ---

    /** Successful spec/profile reloads. */
    long getReloadSuccessCount();

    /** Failed reloads (malformed YAML). */
    long getReloadFailureCount();

    /** Current number of loaded specs. */
    long getActiveSpecCount();

    // --- Latency (milliseconds) ---

    /** Rolling average transform duration in milliseconds. */
    double getAverageTransformTimeMs();

    /** Max transform duration since startup (or last reset). */
    long getMaxTransformTimeMs();

    /** Most recent transform duration in milliseconds. */
    long getLastTransformTimeMs();

    // --- Reset ---

    /** Admin operation: zero all counters and latency stats. */
    void resetMetrics();
}
