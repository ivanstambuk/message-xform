package io.messagexform.core.engine;

/**
 * Schema validation mode for transform evaluation (FR-001-09, CFG-001-09).
 *
 * <ul>
 * <li>{@link #STRICT} — validate input against {@code input.schema} before
 * evaluation. Non-conforming input produces an error response.</li>
 * <li>{@link #LENIENT} — skip evaluation-time schema validation (production
 * default for performance).</li>
 * </ul>
 */
public enum SchemaValidationMode {
    /** Validate input against schema before evaluation. */
    STRICT,

    /** Skip evaluation-time schema validation (default). */
    LENIENT
}
