package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.testkit.TestMessages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests dynamic header expressions (T-001-35, FR-001-10, S-001-34).
 * Dynamic headers use an {@code expr} sub-key evaluated against the
 * transformed body to produce header values.
 */
@DisplayName("T-001-35: Dynamic header expressions")
class DynamicHeaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransformEngine engine;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Test
    @DisplayName("S-001-34: body-to-header injection via dynamic expr (evaluated against transformed body)")
    void dynamicExpr_extractsValueFromBody() throws Exception {
        // S-001-34 pattern: extract values from body and emit as headers.
        // Dynamic expr evaluates against the TRANSFORMED body per spec FR-001-10.
        Path specPath = createTempSpec("""
                id: dynamic-header-test
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    {
                      "type": if (.callbacks) "challenge" else "simple",
                      "authId": .authId,
                      "error": .error
                    }
                headers:
                  add:
                    x-auth-method:
                      expr: .type
                    x-error-code:
                      expr: .error.code
                    x-transformed-by: "message-xform"
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("""
                {
                  "authId": "eyJ0eXAi...",
                  "callbacks": [{"type": "NameCallback"}],
                  "error": {"code": "AUTH_REQUIRED", "message": "Authentication required"}
                }
                """);
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/auth",
                "POST",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        // Dynamic header: evaluates expr against TRANSFORMED body
        assertThat(result.message().headers().toSingleValueMap())
                .containsEntry("x-auth-method", "challenge")
                .containsEntry("x-error-code", "AUTH_REQUIRED")
                .containsEntry("x-transformed-by", "message-xform");
    }

    @Test
    @DisplayName("non-string expr result is coerced to JSON string representation")
    void dynamicExpr_nonStringResult_coercedToString() throws Exception {
        Path specPath = createTempSpec("""
                id: dynamic-coerce-test
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    { "count": .count, "active": .active }
                headers:
                  add:
                    x-item-count:
                      expr: .count
                    x-is-active:
                      expr: .active
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"count\": 42, \"active\": true}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/items",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        // Non-string results coerced to their JSON text representation
        assertThat(result.message().headers().toSingleValueMap())
                .containsEntry("x-item-count", "42")
                .containsEntry("x-is-active", "true");
    }

    @Test
    @DisplayName("dynamic expr that evaluates to null produces null header value (no header set)")
    void dynamicExpr_nullResult_noHeaderSet() throws Exception {
        Path specPath = createTempSpec("""
                id: dynamic-null-test
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    { "data": .payload }
                headers:
                  add:
                    x-maybe:
                      expr: .missingField
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        // Null result → header not set
        assertThat(result.message().headers().toSingleValueMap()).doesNotContainKey("x-maybe");
    }

    @Test
    @DisplayName("dynamic expr mixed with static headers and remove")
    void dynamicExpr_mixedWithStaticAndRemove() throws Exception {
        Path specPath = createTempSpec("""
                id: dynamic-mixed-test
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    { "status": .status, "data": .data }
                headers:
                  add:
                    x-static: "always"
                    x-dynamic-status:
                      expr: .status
                  remove:
                    - "x-internal-*"
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"status\": \"active\", \"data\": \"payload\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                TestMessages.toHeaders(Map.of("x-internal-debug", "true", "x-keep", "me"), Map.of()),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.message().headers().toSingleValueMap())
                .containsEntry("x-static", "always")
                .containsEntry("x-dynamic-status", "active")
                .containsEntry("x-keep", "me")
                .doesNotContainKey("x-internal-debug");
    }

    // --- Helper ---

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
