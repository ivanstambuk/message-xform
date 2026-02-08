package io.messagexform.standalone.config;

/**
 * Thrown when configuration loading fails — missing file, invalid YAML,
 * or missing required fields. Provides a descriptive message suitable
 * for startup error output.
 */
public class ConfigLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigLoadException(String message) {
        super(message);
    }

    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
