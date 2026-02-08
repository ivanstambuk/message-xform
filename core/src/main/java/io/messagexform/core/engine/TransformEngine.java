package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
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

    private final SpecParser specParser;
    private final Map<String, TransformSpec> specs = new ConcurrentHashMap<>();

    /**
     * Creates a new engine backed by the given spec parser.
     *
     * @param specParser the parser used to load and compile spec YAML files
     */
    public TransformEngine(SpecParser specParser) {
        this.specParser = Objects.requireNonNull(specParser, "specParser must not be null");
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
     * @return a {@link TransformResult} (SUCCESS with the transformed message,
     *         or PASSTHROUGH if no spec is loaded)
     * @throws io.messagexform.core.error.ExpressionEvalException if evaluation
     *                                                            fails
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

        // Evaluate the expression against the message body
        // Phase 4: no context variable binding (T-001-21 adds $headers, $status)
        TransformContext context = TransformContext.empty();
        JsonNode transformedBody = expr.evaluate(message.body(), context);

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
}
