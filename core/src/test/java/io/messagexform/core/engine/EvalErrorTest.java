package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.ExpressionEvalException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for end-to-end evaluation error handling (T-001-24, FR-001-07,
 * S-001-22). Verifies that a broken JSLT expression at evaluation time
 * produces a proper error response, not an unhandled exception.
 */
@DisplayName("T-001-24: JSLT evaluation error → error response")
class EvalErrorTest {

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
    @DisplayName("Undefined variable in JSLT → ERROR result with RFC 9457 body")
    void undefinedVariable_returnsErrorResult() throws Exception {
        // This spec uses an undeclared external variable $nonExistent
        Path specPath = createTempSpec("""
                id: broken-eval-spec
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
                      "data": $nonExistent
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"test\"}");
        Message message = new Message(body, null, null, 200, "application/json", "/api/broken", "POST");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isError())
                .as("Should return ERROR result, not throw exception")
                .isTrue();
        assertThat(result.type()).isEqualTo(TransformResult.Type.ERROR);
    }

    @Test
    @DisplayName("Error result contains RFC 9457 error response with correct type URN")
    void errorResult_hasRfc9457Body() throws Exception {
        Path specPath = createTempSpec("""
                id: broken-eval-spec
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
                      "data": $nonExistent
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"key\": \"value\"}");
        Message message = new Message(body, null, null, 200, "application/json", "/api/test", "GET");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isError()).isTrue();
        assertThat(result.errorResponse()).isNotNull();
        assertThat(result.errorResponse().get("type").asText()).isEqualTo(ExpressionEvalException.URN);
        assertThat(result.errorResponse().get("title").asText()).isEqualTo("Transform Failed");
        assertThat(result.errorResponse().get("status").asInt()).isEqualTo(502);
        assertThat(result.errorResponse().get("detail").asText()).contains("JSLT");
    }

    @Test
    @DisplayName("Error result preserves instance path from message")
    void errorResult_preservesInstancePath() throws Exception {
        Path specPath = createTempSpec("""
                id: broken-instance-spec
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
                      "data": $nonExistent
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"key\": \"value\"}");
        Message message = new Message(body, null, null, 200, "application/json", "/json/alpha/authenticate", "POST");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isError()).isTrue();
        assertThat(result.errorResponse().get("instance").asText()).isEqualTo("/json/alpha/authenticate");
    }

    @Test
    @DisplayName("Original message is NOT passed through on error (ADR-0022)")
    void errorResult_doesNotPassthroughOriginalMessage() throws Exception {
        Path specPath = createTempSpec("""
                id: no-passthrough-spec
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
                      "data": $nonExistent
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"secret\": \"should-not-leak\"}");
        Message message = new Message(body, null, null, 200, "application/json", "/api/secure", "POST");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        // ADR-0022: On error, never pass through the original message
        assertThat(result.isError()).isTrue();
        assertThat(result.isPassthrough()).isFalse();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("Error result has correct HTTP status code")
    void errorResult_hasCorrectStatusCode() throws Exception {
        Path specPath = createTempSpec("""
                id: status-code-spec
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
                      "data": $nonExistent
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{}");
        Message message = new Message(body, null, null, 200, "application/json", "/api/test", "GET");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isError()).isTrue();
        assertThat(result.errorStatusCode()).isEqualTo(502);
    }

    // --- Helper ---

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
