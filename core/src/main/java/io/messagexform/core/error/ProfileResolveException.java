package io.messagexform.core.error;

/** Thrown when a profile references a missing spec, unknown version, or has ambiguous match rules. */
public final class ProfileResolveException extends TransformLoadException {

    private static final long serialVersionUID = 1L;

    public ProfileResolveException(String message, String specId, String source) {
        super(message, specId, source);
    }

    public ProfileResolveException(String message, Throwable cause, String specId, String source) {
        super(message, cause, specId, source);
    }
}
