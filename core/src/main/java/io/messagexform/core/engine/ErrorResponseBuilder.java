package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.messagexform.core.error.EvalBudgetExceededException;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.error.InputSchemaViolation;
import io.messagexform.core.error.TransformEvalException;

/**
 * Builds error responses from evaluation-time exceptions (FR-001-07,
 * CFG-001-03/04/05, ADR-0022, ADR-0024).
 *
 * <p>
 * Supports two modes:
 * <ul>
 * <li><b>RFC 9457</b> (default) — produces a standard Problem Details JSON
 * with {@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance} fields.</li>
 * <li><b>Custom template</b> — operator-defined JSON template with
 * {@code {{error.detail}}}, {@code {{error.specId}}},
 * {@code {{error.type}}} placeholder substitution.</li>
 * </ul>
 *
 * <p>
 * Thread-safe and immutable.
 */
public final class ErrorResponseBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_STATUS = 502;
    private static final String DEFAULT_TITLE = "Transform Failed";

    private final int status;
    private final String customTemplate; // null for RFC 9457 mode

    /** Creates a builder with default HTTP status 502 in RFC 9457 mode. */
    public ErrorResponseBuilder() {
        this(DEFAULT_STATUS);
    }

    /**
     * Creates a builder with a custom HTTP status code in RFC 9457 mode.
     *
     * @param status the HTTP status code to use in error responses
     */
    public ErrorResponseBuilder(int status) {
        this.status = status;
        this.customTemplate = null;
    }

    private ErrorResponseBuilder(String customTemplate, int status) {
        this.status = status;
        this.customTemplate = customTemplate;
    }

    /**
     * Creates a builder in custom template mode. The template is a JSON string
     * with {@code {{error.detail}}}, {@code {{error.specId}}}, and
     * {@code {{error.type}}} placeholders.
     *
     * @param template the JSON template string
     * @param status   the HTTP status code to use in error responses
     * @return a new builder in custom template mode
     */
    public static ErrorResponseBuilder withCustomTemplate(String template, int status) {
        return new ErrorResponseBuilder(template, status);
    }

    /**
     * Builds an error response JSON from an evaluation exception.
     *
     * <p>
     * In RFC 9457 mode, produces a standard Problem Details response.
     * In custom template mode, substitutes placeholders in the template.
     *
     * @param exception    the evaluation-time exception
     * @param instancePath the request path, may be null
     * @return a {@link JsonNode} error response
     */
    public JsonNode buildErrorResponse(TransformEvalException exception, String instancePath) {
        if (customTemplate != null) {
            return buildCustomResponse(exception);
        }
        return buildRfc9457Response(exception, instancePath);
    }

    /** Returns the HTTP status code used by this builder. */
    public int status() {
        return status;
    }

    // --- Private helpers ---

    private JsonNode buildRfc9457Response(TransformEvalException exception, String instancePath) {
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

    private JsonNode buildCustomResponse(TransformEvalException exception) {
        String specId = exception.specId() != null ? exception.specId() : "unknown";
        String urn = resolveUrn(exception);
        String detail = exception.getMessage() != null ? exception.getMessage() : "";

        String result = customTemplate
                .replace("{{error.detail}}", detail)
                .replace("{{error.specId}}", specId)
                .replace("{{error.type}}", urn);

        try {
            return MAPPER.readTree(result);
        } catch (Exception e) {
            // Fallback: if the substituted template is not valid JSON,
            // return a minimal RFC 9457 response
            return buildRfc9457Response(exception, null);
        }
    }

    private static String resolveUrn(TransformEvalException exception) {
        if (exception instanceof ExpressionEvalException) {
            return ExpressionEvalException.URN;
        } else if (exception instanceof EvalBudgetExceededException) {
            return EvalBudgetExceededException.URN;
        } else if (exception instanceof InputSchemaViolation) {
            return InputSchemaViolation.URN;
        }
        // Fallback for unknown subtypes
        return "urn:message-xform:error:unknown";
    }
}
