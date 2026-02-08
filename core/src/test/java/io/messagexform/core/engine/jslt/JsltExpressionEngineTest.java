package io.messagexform.core.engine.jslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.error.ExpressionCompileException;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.spi.CompiledExpression;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JsltExpressionEngine} (FR-001-02, SPI-001-01/02/03). Parameterized cases
 * covering basic transforms, conditionals, arrays, and context variable binding.
 */
class JsltExpressionEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsltExpressionEngine engine = new JsltExpressionEngine();

    @Test
    void engineIdIsJslt() {
        assertThat(engine.id()).isEqualTo("jslt");
    }

    @Test
    void simpleRename() throws Exception {
        // S-001-08 pattern: rename fields
        CompiledExpression compiled = engine.compile("{\"userId\": .user_id, \"fullName\": .full_name}");
        JsonNode input = MAPPER.readTree("{\"user_id\": \"u-123\", \"full_name\": \"Alice Smith\"}");
        JsonNode output = compiled.evaluate(input, TransformContext.empty());

        assertThat(output.get("userId").asText()).isEqualTo("u-123");
        assertThat(output.get("fullName").asText()).isEqualTo("Alice Smith");
    }

    @Test
    void conditionalExpression() throws Exception {
        // S-001-15 pattern: conditional transform
        String expr =
                "if (.error) {\"error\": true, \"message\": .error.message} else {\"error\": false, \"data\": .data}";
        CompiledExpression compiled = engine.compile(expr);

        // Error case
        JsonNode errorInput = MAPPER.readTree("{\"error\": {\"message\": \"not found\"}}");
        JsonNode errorOutput = compiled.evaluate(errorInput, TransformContext.empty());
        assertThat(errorOutput.get("error").asBoolean()).isTrue();
        assertThat(errorOutput.get("message").asText()).isEqualTo("not found");

        // Success case
        JsonNode successInput = MAPPER.readTree("{\"data\": \"hello\"}");
        JsonNode successOutput = compiled.evaluate(successInput, TransformContext.empty());
        assertThat(successOutput.get("error").asBoolean()).isFalse();
        assertThat(successOutput.get("data").asText()).isEqualTo("hello");
    }

    @Test
    void arrayReshape() throws Exception {
        // S-001-13 pattern: array-of-objects reshaping
        String expr = "[for (.items) {\"id\": .item_id, \"label\": .item_name}]";
        CompiledExpression compiled = engine.compile(expr);
        JsonNode input = MAPPER.readTree(
                "{\"items\": [{\"item_id\": 1, \"item_name\": \"A\"}, {\"item_id\": 2, \"item_name\": \"B\"}]}");
        JsonNode output = compiled.evaluate(input, TransformContext.empty());

        assertThat(output.isArray()).isTrue();
        assertThat(output.size()).isEqualTo(2);
        assertThat(output.get(0).get("id").asInt()).isEqualTo(1);
        assertThat(output.get(0).get("label").asText()).isEqualTo("A");
        assertThat(output.get(1).get("id").asInt()).isEqualTo(2);
        assertThat(output.get(1).get("label").asText()).isEqualTo("B");
    }

    @Test
    void openWorldPassthrough() throws Exception {
        // S-001-07 pattern: copy everything except specific fields
        CompiledExpression compiled = engine.compile("{ * - secret : . }");
        JsonNode input = MAPPER.readTree("{\"public\": \"yes\", \"secret\": \"hidden\", \"other\": 42}");
        JsonNode output = compiled.evaluate(input, TransformContext.empty());

        assertThat(output.has("public")).isTrue();
        assertThat(output.has("other")).isTrue();
        assertThat(output.has("secret")).as("secret field must be stripped").isFalse();
    }

    @Test
    void contextVariablesAreAccessible() throws Exception {
        // T-001-12: verify that JSLT can access injected context variables
        CompiledExpression compiled = engine.compile("{\"requestId\": $headers.\"x-request-id\", \"status\": $status}");
        TransformContext ctx = new TransformContext(Map.of("x-request-id", "abc-123"), null, 200, null, null);
        JsonNode input = MAPPER.readTree("{}");
        JsonNode output = compiled.evaluate(input, ctx);

        assertThat(output.get("requestId").asText()).isEqualTo("abc-123");
        assertThat(output.get("status").asInt()).isEqualTo(200);
    }

    @Test
    void contextVariableQueryParams() throws Exception {
        CompiledExpression compiled = engine.compile("{\"page\": $queryParams.page}");
        TransformContext ctx = new TransformContext(null, null, null, Map.of("page", "3"), null);
        JsonNode input = MAPPER.readTree("{}");
        JsonNode output = compiled.evaluate(input, ctx);

        assertThat(output.get("page").asText()).isEqualTo("3");
    }

    @Test
    void compileInvalidExpressionThrowsExpressionCompileException() {
        assertThatThrownBy(() -> engine.compile("{\"broken\": "))
                .isInstanceOf(ExpressionCompileException.class)
                .hasMessageContaining("compile");
    }

    @Test
    void evaluateUndeclaredExternalVariableThrowsEvalException() {
        // JSLT throws at eval time for undeclared external variables.
        CompiledExpression compiled = engine.compile("$undeclaredVar");

        assertThatThrownBy(() -> compiled.evaluate(MAPPER.readTree("{}"), TransformContext.empty()))
                .isInstanceOf(ExpressionEvalException.class);
    }

    @Test
    void identityTransform() throws Exception {
        CompiledExpression compiled = engine.compile(".");
        JsonNode input = MAPPER.readTree("{\"key\": \"value\", \"nested\": {\"a\": 1}}");
        JsonNode output = compiled.evaluate(input, TransformContext.empty());

        assertThat(output).isEqualTo(input);
    }

    @Test
    void compiledExpressionIsThreadSafe() throws Exception {
        // Compile once, evaluate from multiple threads
        CompiledExpression compiled = engine.compile("{\"doubled\": .value * 2}");

        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.concurrent.CompletableFuture<JsonNode>[] futures =
                (java.util.concurrent.CompletableFuture<JsonNode>[]) new java.util.concurrent.CompletableFuture[10];
        for (int i = 0; i < 10; i++) {
            final int val = i;
            futures[i] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                JsonNode input = MAPPER.createObjectNode().put("value", val);
                return compiled.evaluate(input, TransformContext.empty());
            });
        }
        java.util.concurrent.CompletableFuture.allOf(futures).join();

        for (int i = 0; i < 10; i++) {
            JsonNode result = futures[i].join();
            assertThat(result.get("doubled").asInt()).isEqualTo(i * 2);
        }
    }
}
