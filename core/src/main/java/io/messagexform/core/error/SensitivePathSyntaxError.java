package io.messagexform.core.error;

/** Thrown when an RFC 9535 JSONPath expression in the {@code sensitive} list has invalid syntax. */
public final class SensitivePathSyntaxError extends TransformLoadException {

    private static final long serialVersionUID = 1L;

    public SensitivePathSyntaxError(String message, String specId, String source) {
        super(message, specId, source);
    }

    public SensitivePathSyntaxError(String message, Throwable cause, String specId, String source) {
        super(message, cause, specId, source);
    }
}
