package io.messagexform.core.engine;

/**
 * Evaluation budgets for transform expressions (NFR-001-07, CFG-001-06/07).
 * Enforces wall-clock time and output size limits to guard against runaway
 * expressions.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @param maxEvalMs      maximum wall-clock time in milliseconds for expression
 *                       evaluation (default: 50ms)
 * @param maxOutputBytes maximum serialised output size in bytes (default: 1MB)
 */
public record EvalBudget(int maxEvalMs, int maxOutputBytes) {

    /** Default budget: 50ms wall-clock, 1MB output. */
    public static final EvalBudget DEFAULT = new EvalBudget(50, 1024 * 1024);

    public EvalBudget {
        if (maxEvalMs <= 0) {
            throw new IllegalArgumentException("maxEvalMs must be positive, got: " + maxEvalMs);
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive, got: " + maxOutputBytes);
        }
    }
}
