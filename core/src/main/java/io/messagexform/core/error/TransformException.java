package io.messagexform.core.error;

/**
 * Abstract base for all message-xform exceptions (ADR-0024). Never thrown directly — use the
 * concrete subclasses under {@link TransformLoadException} or {@link TransformEvalException}.
 */
public abstract class TransformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Phase in which the error occurred. */
    public enum Phase {
        LOAD,
        EVALUATION
    }

    private final String specId;
    private final Phase phase;

    protected TransformException(String message, String specId, Phase phase) {
        super(message);
        this.specId = specId;
        this.phase = phase;
    }

    protected TransformException(String message, Throwable cause, String specId, Phase phase) {
        super(message, cause);
        this.specId = specId;
        this.phase = phase;
    }

    /** The spec that triggered the error, or {@code null} if not yet identified. */
    public String specId() {
        return specId;
    }

    /** Human-readable error description (alias for {@link #getMessage()}). */
    public String detail() {
        return getMessage();
    }

    /** The phase in which the error occurred. */
    public Phase phase() {
        return phase;
    }
}
