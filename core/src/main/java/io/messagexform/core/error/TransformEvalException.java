package io.messagexform.core.error;

/**
 * Abstract parent for per-request evaluation errors. Thrown during {@code
 * TransformEngine.transform()} when expression evaluation fails. The engine catches these
 * internally and produces a configurable error response (ADR-0022). Carries an additional {@code
 * chainStep} field for pipeline chain context.
 */
public abstract class TransformEvalException extends TransformException {

    private static final long serialVersionUID = 1L;

    private final Integer chainStep;

    protected TransformEvalException(String message, String specId, Integer chainStep) {
        super(message, specId, Phase.EVALUATION);
        this.chainStep = chainStep;
    }

    protected TransformEvalException(String message, Throwable cause, String specId, Integer chainStep) {
        super(message, cause, specId, Phase.EVALUATION);
        this.chainStep = chainStep;
    }

    /** The pipeline chain step index, or {@code null} if not in a chain. */
    public Integer chainStep() {
        return chainStep;
    }
}
