package io.messagexform.core.error;

/** Thrown when a JSON Schema block in a spec is invalid (JSON Schema 2020-12 validation). */
public final class SchemaValidationException extends TransformLoadException {

    private static final long serialVersionUID = 1L;

    public SchemaValidationException(String message, String specId, String source) {
        super(message, specId, source);
    }

    public SchemaValidationException(String message, Throwable cause, String specId, String source) {
        super(message, cause, specId, source);
    }
}
