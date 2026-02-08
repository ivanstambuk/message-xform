package io.messagexform.core.error;

/**
 * Thrown when expression evaluation exceeds {@code max-eval-ms} or output exceeds {@code
 * max-output-bytes}. URN: {@code urn:message-xform:error:eval-budget-exceeded}
 */
public final class EvalBudgetExceededException extends TransformEvalException {

    private static final long serialVersionUID = 1L;

    public static final String URN = "urn:message-xform:error:eval-budget-exceeded";

    public EvalBudgetExceededException(String message, String specId, Integer chainStep) {
        super(message, specId, chainStep);
    }

    public EvalBudgetExceededException(String message, Throwable cause, String specId, Integer chainStep) {
        super(message, cause, specId, chainStep);
    }
}
