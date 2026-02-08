package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for context variable binding in the transform pipeline (T-001-21,
 * FR-001-10, FR-001-11). Verifies that $headers, $headers_all, and $status
 * from the Message are passed to the JSLT expression engine.
 */
@DisplayName("T-001-21: Context variable binding — $headers, $status")
class ContextBindingTest {

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
    @DisplayName("JSLT expression accesses $headers.\"x-request-id\" from message headers")
    void headersBinding_accessSpecificHeader() throws Exception {
        Path specPath = createTempSpec("""
                id: headers-test
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
                      "requestId": $headers."x-request-id",
                      "data": .payload
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
        Message message = new Message(
                body,
                Map.of("x-request-id", "req-abc-123", "content-type", "application/json"),
                Map.of("x-request-id", List.of("req-abc-123"), "content-type", List.of("application/json")),
                200,
                "application/json",
                "/api/test",
                "POST");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.message().body().get("requestId").asText()).isEqualTo("req-abc-123");
        assertThat(result.message().body().get("data").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("JSLT expression accesses $status for response transforms")
    void statusBinding_responseTransformHasStatusCode() throws Exception {
        Path specPath = createTempSpec("""
                id: status-test
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
                      "statusCode": $status,
                      "ok": $status == 200,
                      "data": .payload
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"response-data\"}");
        Message message = new Message(body, null, null, 200, "application/json", "/api/test", "GET");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.message().body().get("statusCode").asInt()).isEqualTo(200);
        assertThat(result.message().body().get("ok").asBoolean()).isTrue();
        assertThat(result.message().body().get("data").asText()).isEqualTo("response-data");
    }

    @Test
    @DisplayName("$status is null for REQUEST transforms (ADR-0017)")
    void statusBinding_requestTransformHasNullStatus() throws Exception {
        Path specPath = createTempSpec("""
                id: status-null-test
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
                      "hasStatus": $status != null,
                      "data": .payload
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"request-data\"}");
        // Request messages have null statusCode
        Message message = new Message(body, null, null, null, "application/json", "/api/test", "POST");

        TransformResult result = engine.transform(message, Direction.REQUEST);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.message().body().get("hasStatus").asBoolean()).isFalse();
        assertThat(result.message().body().get("data").asText()).isEqualTo("request-data");
    }

    @Test
    @DisplayName("$headers_all provides multi-value header access")
    void headersAllBinding_multiValueHeaders() throws Exception {
        Path specPath = createTempSpec("""
                id: headers-all-test
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
                      "acceptCount": size($headers_all."accept"),
                      "data": .payload
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"test\"}");
        Message message = new Message(
                body,
                Map.of("accept", "application/json"),
                Map.of("accept", List.of("application/json", "text/html")),
                200,
                "application/json",
                "/api/test",
                "GET");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.message().body().get("acceptCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Combined context: $headers + $status in same expression")
    void combinedContext_headersAndStatus() throws Exception {
        Path specPath = createTempSpec("""
                id: combined-context-test
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
                      "requestId": $headers."x-request-id",
                      "status": $status,
                      "message": .msg
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"msg\": \"hello world\"}");
        Message message = new Message(
                body,
                Map.of("x-request-id", "trace-456"),
                Map.of("x-request-id", List.of("trace-456")),
                201,
                "application/json",
                "/api/items",
                "POST");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        JsonNode output = result.message().body();
        assertThat(output.get("requestId").asText()).isEqualTo("trace-456");
        assertThat(output.get("status").asInt()).isEqualTo(201);
        assertThat(output.get("message").asText()).isEqualTo("hello world");
    }

    // --- Helper ---

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
