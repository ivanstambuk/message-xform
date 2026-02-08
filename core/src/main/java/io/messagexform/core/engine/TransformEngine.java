package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.error.EvalBudgetExceededException;
import io.messagexform.core.error.TransformEvalException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.model.TransformSpec;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.spi.CompiledExpression;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core transformation engine (API-001-01/03, FR-001-01, FR-001-02, FR-001-04).
 * Loads transform specs from YAML files and applies them to messages.
 *
 * <p>
 * Phase 4 scope: single-spec loading and body-only transformation.
 * Profile matching (Phase 5), context variable binding (T-001-21), error
 * responses (T-001-22..24), and hot reload (Phase 7) are added incrementally.
 *
 * <p>
 * Thread-safe: uses a {@link ConcurrentHashMap} for the spec registry.
 */
public final class TransformEngine {

    private static final ObjectMapper SIZE_MAPPER = new ObjectMapper();

    private final SpecParser specParser;
    private final ErrorResponseBuilder errorResponseBuilder;
    private final EvalBudget budget;
    private final Map<String, TransformSpec> specs = new ConcurrentHashMap<>();

    /**
     * Creates a new engine backed by the given spec parser, using the default
     * error response builder (HTTP 502).
     *
     * @param specParser the parser used to load and compile spec YAML files
     */
    public TransformEngine(SpecParser specParser) {
        this(specParser, new ErrorResponseBuilder(), EvalBudget.DEFAULT);
    }

    /**
     * Creates a new engine with a custom error response builder.
     *
     * @param specParser           the parser used to load and compile spec YAML
     *                             files
     * @param errorResponseBuilder the builder for RFC 9457 error responses
     */
    public TransformEngine(SpecParser specParser, ErrorResponseBuilder errorResponseBuilder) {
        this(specParser, errorResponseBuilder, EvalBudget.DEFAULT);
    }

    /**
     * Creates a new engine with a custom error response builder and evaluation
     * budget.
     *
     * @param specParser           the parser used to load and compile spec YAML
     *                             files
     * @param errorResponseBuilder the builder for RFC 9457 error responses
     * @param budget               evaluation budget (max-eval-ms, max-output-bytes)
     */
    public TransformEngine(SpecParser specParser, ErrorResponseBuilder errorResponseBuilder, EvalBudget budget) {
        this.specParser = Objects.requireNonNull(specParser, "specParser must not be null");
        this.errorResponseBuilder =
                Objects.requireNonNull(errorResponseBuilder, "errorResponseBuilder must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    /**
     * Loads a transform spec from a YAML file and registers it by its spec id.
     * If a spec with the same id is already loaded, it is replaced.
     *
     * @param path path to the spec YAML file
     * @return the loaded and compiled spec
     * @throws io.messagexform.core.error.SpecParseException         if the YAML is
     *                                                               invalid
     * @throws io.messagexform.core.error.ExpressionCompileException if the
     *                                                               expression
     *                                                               fails to
     *                                                               compile
     */
    public TransformSpec loadSpec(Path path) {
        TransformSpec spec = specParser.parse(path);
        specs.put(spec.id(), spec);
        return spec;
    }

    /**
     * Transforms the given message using the first loaded spec. In Phase 4,
     * there is no profile matching — the engine transforms using the most
     * recently loaded spec. Profile-based routing is added in Phase 5.
     *
     * <p>
     * For unidirectional specs, the compiled expression is used regardless of
     * direction. For bidirectional specs, {@link Direction#RESPONSE} selects
     * the {@code forward} expression, and {@link Direction#REQUEST} selects
     * the {@code reverse} expression.
     *
     * @param message   the input message to transform
     * @param direction the direction of the transform (REQUEST or RESPONSE)
     * @return a {@link TransformResult} — SUCCESS with the transformed message,
     *         ERROR with an RFC 9457 body if evaluation fails (ADR-0022),
     *         or PASSTHROUGH if no spec is loaded
     */
    public TransformResult transform(Message message, Direction direction) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(direction, "direction must not be null");

        if (specs.isEmpty()) {
            return TransformResult.passthrough();
        }

        // Phase 4: use the most recently loaded spec (single-spec mode).
        // Phase 5 will add profile matching to select the correct spec.
        TransformSpec spec = specs.values().iterator().next();

        // Resolve the expression based on directionality
        CompiledExpression expr = resolveExpression(spec, direction);

        // Build context from message metadata (T-001-21, FR-001-10/11).
        // For REQUEST transforms, $status is null (ADR-0017).
        Integer status = direction == Direction.RESPONSE ? message.statusCode() : null;
        TransformContext context = new TransformContext(message.headers(), message.headersAll(), status, null, null);
        // Evaluate the expression — catch eval exceptions per ADR-0022
        try {
            long startNanos = System.nanoTime();
            JsonNode transformedBody = expr.evaluate(message.body(), context);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            // T-001-25: Enforce time budget
            if (elapsedMs > budget.maxEvalMs()) {
                throw new EvalBudgetExceededException(
                        String.format(
                                "Evaluation exceeded time budget: %dms > %dms (spec '%s')",
                                elapsedMs, budget.maxEvalMs(), spec.id()),
                        spec.id(),
                        null);
            }

            // T-001-25: Enforce output size budget
            checkOutputSize(transformedBody, spec.id());

            // Build the transformed message, preserving envelope metadata
            Message transformedMessage = new Message(
                    transformedBody,
                    message.headers(),
                    message.headersAll(),
                    message.statusCode(),
                    message.contentType(),
                    message.requestPath(),
                    message.requestMethod());

            return TransformResult.success(transformedMessage);
        } catch (TransformEvalException e) {
            // ADR-0022: Never pass through the original message on eval error.
            // Build an RFC 9457 error response instead.
            JsonNode errorBody = errorResponseBuilder.buildErrorResponse(e, message.requestPath());
            return TransformResult.error(errorBody, errorResponseBuilder.status());
        }
    }

    /**
     * Returns the number of currently loaded specs.
     */
    public int specCount() {
        return specs.size();
    }

    // --- Private helpers ---

    private CompiledExpression resolveExpression(TransformSpec spec, Direction direction) {
        if (spec.isBidirectional()) {
            return direction == Direction.RESPONSE ? spec.forward() : spec.reverse();
        }
        return spec.compiledExpr();
    }

    private void checkOutputSize(JsonNode output, String specId) {
        try {
            byte[] bytes = SIZE_MAPPER.writeValueAsBytes(output);
            if (bytes.length > budget.maxOutputBytes()) {
                throw new EvalBudgetExceededException(
                        String.format(
                                "Output size %d bytes exceeds max-output-bytes %d (spec '%s')",
                                bytes.length, budget.maxOutputBytes(), specId),
                        specId,
                        null);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Should never happen for a valid JsonNode; treat as eval error
            throw new io.messagexform.core.error.ExpressionEvalException(
                    "Failed to measure output size: " + e.getMessage(), e, specId, null);
        }
    }
}
