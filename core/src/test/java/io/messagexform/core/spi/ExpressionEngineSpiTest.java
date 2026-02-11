package io.messagexform.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformContext;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link ExpressionEngine} and {@link CompiledExpression} SPI contracts (DO-001-06,
 * DO-001-03, FR-001-02).
 */
class ExpressionEngineSpiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A trivial mock engine that returns the input unchanged (identity transform). */
    private static final ExpressionEngine IDENTITY_ENGINE = new ExpressionEngine() {
        @Override
        public String id() {
            return "identity";
        }

        @Override
        public CompiledExpression compile(String expression) {
            // Ignores the expression, always returns input as-is
            return (input, context) -> input;
        }
    };

    @Test
    void engineIdReturnsNonEmptyIdentifier() {
        assertThat(IDENTITY_ENGINE.id()).isNotNull().isNotEmpty().isEqualTo("identity");
    }

    @Test
    void compileReturnsNonNullCompiledExpression() {
        CompiledExpression compiled = IDENTITY_ENGINE.compile("anything");
        assertThat(compiled).isNotNull();
    }

    @Test
    void evaluateTransformsInput() throws Exception {
        CompiledExpression compiled = IDENTITY_ENGINE.compile(".");
        JsonNode input = MAPPER.readTree("{\"name\": \"Alice\"}");
        JsonNode output = compiled.evaluate(input, TransformContext.empty());

        assertThat(output).isEqualTo(input);
    }

    @Test
    void evaluateReceivesTransformContext() throws Exception {
        // A mock engine that appends the status from context to the output
        ExpressionEngine contextAwareEngine = new ExpressionEngine() {
            @Override
            public String id() {
                return "context-aware";
            }

            @Override
            public CompiledExpression compile(String expression) {
                return (input, context) -> {
                    var node = input.deepCopy();
                    if (node.isObject()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                                .set("status_from_context", TransformEngine.statusToJson(context.status()));
                    }
                    return node;
                };
            }
        };

        CompiledExpression compiled = contextAwareEngine.compile(".");
        JsonNode input = MAPPER.readTree("{\"key\": \"value\"}");
        TransformContext ctx = new TransformContext(HttpHeaders.empty(), 200, null, null, SessionContext.empty());
        JsonNode output = compiled.evaluate(input, ctx);

        assertThat(output.get("key").asText()).isEqualTo("value");
        assertThat(output.get("status_from_context").asInt()).isEqualTo(200);
    }
}
