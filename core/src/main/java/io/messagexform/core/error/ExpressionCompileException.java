package io.messagexform.core.error;

/** Thrown when an expression fails to compile (syntax error, unknown engine). */
public final class ExpressionCompileException extends TransformLoadException {

    private static final long serialVersionUID = 1L;

    public ExpressionCompileException(String message, String specId, String source) {
        super(message, specId, source);
    }

    public ExpressionCompileException(String message, Throwable cause, String specId, String source) {
        super(message, cause, specId, source);
    }
}
