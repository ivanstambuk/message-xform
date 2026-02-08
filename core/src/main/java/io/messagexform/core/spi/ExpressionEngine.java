package io.messagexform.core.spi;

/**
 * Pluggable expression engine SPI (DO-001-06, FR-001-02). Implementations provide a specific
 * transformation language (JSLT, JOLT, jq, etc.) and are registered with the engine via {@code
 * TransformEngine.registerEngine()}.
 *
 * <p>Implementations MUST be stateless and thread-safe.
 */
public interface ExpressionEngine {

    /**
     * Returns the engine identifier, e.g. {@code "jslt"}, {@code "jolt"} (SPI-001-01). This id is
     * used in spec YAML files as the {@code lang} field to select the engine.
     *
     * @return a non-null, non-empty engine identifier (lowercase, no spaces)
     */
    String id();

    /**
     * Compiles the given expression string into an immutable, thread-safe handle (SPI-001-02).
     *
     * @param expression the expression source code (e.g. a JSLT expression)
     * @return a compiled expression ready for evaluation
     * @throws io.messagexform.core.error.ExpressionCompileException if the expression has syntax
     *     errors
     */
    CompiledExpression compile(String expression);
}
