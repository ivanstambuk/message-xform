package io.messagexform.core.error;

/**
 * Thrown when expression evaluation fails at runtime (e.g., undefined variable, type error). URN:
 * {@code urn:message-xform:error:expression-eval-failed}
 */
public final class ExpressionEvalException extends TransformEvalException {

    private static final long serialVersionUID = 1L;

    public static final String URN = "urn:message-xform:error:expression-eval-failed";

    public ExpressionEvalException(String message, String specId, Integer chainStep) {
        super(message, specId, chainStep);
    }

    public ExpressionEvalException(String message, Throwable cause, String specId, Integer chainStep) {
        super(message, cause, specId, chainStep);
    }
}
