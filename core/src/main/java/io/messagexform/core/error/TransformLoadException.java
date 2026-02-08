package io.messagexform.core.error;

/**
 * Abstract parent for load-time configuration errors. Thrown during {@code
 * TransformEngine.loadSpec()} or {@code TransformEngine.loadProfile()}. Carries an additional
 * {@code source} field identifying the file or resource that caused the error.
 */
public abstract class TransformLoadException extends TransformException {

    private static final long serialVersionUID = 1L;

    private final String source;

    protected TransformLoadException(String message, String specId, String source) {
        super(message, specId, Phase.LOAD);
        this.source = source;
    }

    protected TransformLoadException(String message, Throwable cause, String specId, String source) {
        super(message, cause, specId, Phase.LOAD);
        this.source = source;
    }

    /** The file path or resource identifier that caused the error. */
    public String source() {
        return source;
    }
}
