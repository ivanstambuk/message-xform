package io.messagexform.core.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.model.TransformContext;

/**
 * An immutable, thread-safe compiled expression handle (DO-001-03). Produced by {@link
 * ExpressionEngine#compile(String)} and used to evaluate transformations against input data.
 *
 * <p>Implementations MUST be thread-safe — a single {@code CompiledExpression} is shared across
 * concurrent requests.
 */
public interface CompiledExpression {

    /**
     * Evaluates this expression against the given input JSON and context (SPI-001-03).
     *
     * @param input the JSON body to transform
     * @param context read-only context carrying HTTP metadata ($headers, $status, etc.)
     * @return the transformed JSON output
     * @throws io.messagexform.core.error.ExpressionEvalException if evaluation fails at runtime
     */
    JsonNode evaluate(JsonNode input, TransformContext context);
}
