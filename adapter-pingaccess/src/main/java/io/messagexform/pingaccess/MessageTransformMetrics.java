package io.messagexform.pingaccess;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe, lock-free implementation of
 * {@link MessageTransformMetricsMXBean}
 * (FR-002-14, T-002-27).
 *
 * <p>
 * All counters use {@link LongAdder} for contention-free concurrent updates.
 * Latency tracking uses {@link AtomicLong} for max/last values.
 *
 * <p>
 * The {@code activeSpecCount} is set externally by the rule after each
 * reload/configure cycle — it is not auto-tracked.
 */
final class MessageTransformMetrics implements MessageTransformMetricsMXBean {

    // --- Transform counters ---
    private final LongAdder successCount = new LongAdder();
    private final LongAdder passthroughCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder denyCount = new LongAdder();

    // --- Reload counters ---
    private final LongAdder reloadSuccessCount = new LongAdder();
    private final LongAdder reloadFailureCount = new LongAdder();

    // --- Active spec count (set externally) ---
    private final AtomicLong activeSpecCount = new AtomicLong();

    // --- Latency tracking ---
    private final LongAdder totalTransformTimeMs = new LongAdder();
    private final LongAdder totalTransformCount = new LongAdder();
    private final AtomicLong maxTransformTimeMs = new AtomicLong();
    private final AtomicLong lastTransformTimeMs = new AtomicLong();

    // ── Increment methods (called by MessageTransformRule) ──

    void recordSuccess(long durationMs) {
        successCount.increment();
        recordLatency(durationMs);
    }

    void recordPassthrough(long durationMs) {
        passthroughCount.increment();
        recordLatency(durationMs);
    }

    void recordError(long durationMs) {
        errorCount.increment();
        recordLatency(durationMs);
    }

    void recordDeny(long durationMs) {
        denyCount.increment();
        recordLatency(durationMs);
    }

    void recordReloadSuccess() {
        reloadSuccessCount.increment();
    }

    void recordReloadFailure() {
        reloadFailureCount.increment();
    }

    void setActiveSpecCount(long count) {
        activeSpecCount.set(count);
    }

    // ── MXBean interface ──

    @Override
    public long getTransformSuccessCount() {
        return successCount.sum();
    }

    @Override
    public long getTransformPassthroughCount() {
        return passthroughCount.sum();
    }

    @Override
    public long getTransformErrorCount() {
        return errorCount.sum();
    }

    @Override
    public long getTransformDenyCount() {
        return denyCount.sum();
    }

    @Override
    public long getTransformTotalCount() {
        return successCount.sum() + passthroughCount.sum() + errorCount.sum() + denyCount.sum();
    }

    @Override
    public long getReloadSuccessCount() {
        return reloadSuccessCount.sum();
    }

    @Override
    public long getReloadFailureCount() {
        return reloadFailureCount.sum();
    }

    @Override
    public long getActiveSpecCount() {
        return activeSpecCount.get();
    }

    @Override
    public double getAverageTransformTimeMs() {
        long count = totalTransformCount.sum();
        if (count == 0) {
            return 0.0;
        }
        return (double) totalTransformTimeMs.sum() / count;
    }

    @Override
    public long getMaxTransformTimeMs() {
        return maxTransformTimeMs.get();
    }

    @Override
    public long getLastTransformTimeMs() {
        return lastTransformTimeMs.get();
    }

    @Override
    public void resetMetrics() {
        successCount.reset();
        passthroughCount.reset();
        errorCount.reset();
        denyCount.reset();
        reloadSuccessCount.reset();
        reloadFailureCount.reset();
        totalTransformTimeMs.reset();
        totalTransformCount.reset();
        maxTransformTimeMs.set(0);
        lastTransformTimeMs.set(0);
        // activeSpecCount is NOT reset — it reflects current engine state
    }

    // ── Internal ──

    private void recordLatency(long durationMs) {
        totalTransformTimeMs.add(durationMs);
        totalTransformCount.increment();
        lastTransformTimeMs.set(durationMs);
        // CAS loop for max
        maxTransformTimeMs.accumulateAndGet(durationMs, Math::max);
    }
}
