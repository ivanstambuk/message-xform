package io.messagexform.core.error;

/** Thrown when a spec YAML file has invalid syntax or missing required fields. */
public final class SpecParseException extends TransformLoadException {

    private static final long serialVersionUID = 1L;

    public SpecParseException(String message, String specId, String source) {
        super(message, specId, source);
    }

    public SpecParseException(String message, Throwable cause, String specId, String source) {
        super(message, cause, specId, source);
    }
}
