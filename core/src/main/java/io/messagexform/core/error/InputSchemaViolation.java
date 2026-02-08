package io.messagexform.core.error;

/**
 * Thrown when strict-mode input validation fails against the spec's {@code input.schema}. URN:
 * {@code urn:message-xform:error:schema-validation-failed}
 */
public final class InputSchemaViolation extends TransformEvalException {

    private static final long serialVersionUID = 1L;

    public static final String URN = "urn:message-xform:error:schema-validation-failed";

    public InputSchemaViolation(String message, String specId, Integer chainStep) {
        super(message, specId, chainStep);
    }

    public InputSchemaViolation(String message, Throwable cause, String specId, Integer chainStep) {
        super(message, cause, specId, chainStep);
    }
}
