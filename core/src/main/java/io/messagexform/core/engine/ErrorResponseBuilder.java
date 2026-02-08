package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.messagexform.core.error.EvalBudgetExceededException;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.error.InputSchemaViolation;
import io.messagexform.core.error.TransformEvalException;

/**
 * Builds RFC 9457 Problem Details error responses from evaluation-time
 * exceptions (FR-001-07, CFG-001-03/04, ADR-0022, ADR-0024).
 *
 * <p>Default error response format:
 * <pre>{@code
 * {
 * "type": "urn:message-xform:error:expression-eval-failed",
 * "title": "Transform Failed",
 * "status": 502,
 * "detail": "JSLT evaluation error in spec 'callback-prettify@1.0.0': ...",
 * "instance": "/json/alpha/authenticate"
 * }
 * }</pre>
 *
 * <p>The URN-style {@code type} field is derived from the exception class's
 * static {@code URN} constant (see Error Catalogue in ADR-0024).
 *
 * <p>Thread-safe and immutable.
 */
public final class ErrorResponseBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_STATUS = 502;
    private static final String DEFAULT_TITLE = "Transform Failed";

    private final int status;

    /** Creates a builder with default HTTP status 502. */
    public ErrorResponseBuilder() {
        this(DEFAULT_STATUS);
    }

    /**
     * Creates a builder with a custom HTTP status code.
     *
     * @param status the HTTP status code to use in error responses
     */
    public ErrorResponseBuilder(int status) {
        this.status = status;
    }

    /**
     * Builds an RFC 9457 Problem Details JSON response from an evaluation
     * exception.
     *
     * @param exception    the evaluation-time exception
     * @param instancePath the request path (used as RFC 9457 {@code instance}),
     *                     may be null
     * @return a {@link JsonNode} conforming to RFC 9457
     */
    public JsonNode buildErrorResponse(TransformEvalException exception, String instancePath) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("type", resolveUrn(exception));
        response.put("title", DEFAULT_TITLE);
        response.put("status", status);
        response.put("detail", exception.getMessage());

        if (instancePath != null) {
            response.put("instance", instancePath);
        } else {
            response.putNull("instance");
        }

        return response;
    }

    /** Returns the HTTP status code used by this builder. */
    public int status() {
        return status;
    }

    // --- Private helpers ---

    private static String resolveUrn(TransformEvalException exception) {
        if (exception instanceof ExpressionEvalException) {
            return ExpressionEvalException.URN;
        } else if (exception instanceof EvalBudgetExceededException) {
            return EvalBudgetExceededException.URN;
        } else if (exception instanceof InputSchemaViolation) {
            return InputSchemaViolation.URN;
        }
        // Fallback for unknown subtypes — should not happen with sealed hierarchy
        return "urn:message-xform:error:unknown";
    }
}
