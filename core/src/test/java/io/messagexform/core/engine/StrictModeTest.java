package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.InputSchemaViolation;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for strict-mode schema validation at evaluation time (T-001-26,
 * FR-001-09, CFG-001-09). Verifies input schema validation before
 * expression evaluation when strict mode is enabled.
 */
@DisplayName("T-001-26: Strict-mode schema validation")
class StrictModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("Strict mode ON")
    class StrictModeOn {

        @Test
        @DisplayName("Conforming input → transform succeeds")
        void conformingInput_succeeds() throws Exception {
            TransformEngine engine = createEngine(SchemaValidationMode.STRICT);

            Path specPath = createTempSpec("""
                    id: strict-pass
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                        required:
                          - name
                        properties:
                          name:
                            type: string
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        { "greeting": "Hello " + .name }
                    """);
            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"name\": \"Alice\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/greet",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(TestMessages.parseBody(result.message().body())
                            .get("greeting")
                            .asText())
                    .isEqualTo("Hello Alice");
        }

        @Test
        @DisplayName("Non-conforming input → ERROR with schema-validation URN")
        void nonConformingInput_returnsError() throws Exception {
            TransformEngine engine = createEngine(SchemaValidationMode.STRICT);

            Path specPath = createTempSpec("""
                    id: strict-fail
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                        required:
                          - name
                        properties:
                          name:
                            type: string
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        { "greeting": "Hello " + .name }
                    """);
            engine.loadSpec(specPath);

            // Missing required field "name"
            JsonNode body = MAPPER.readTree("{\"age\": 30}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/greet",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isError())
                    .as("Non-conforming input should produce ERROR in strict mode")
                    .isTrue();
            assertThat(TestMessages.parseBody(result.errorResponse())
                            .get("type")
                            .asText())
                    .isEqualTo(InputSchemaViolation.URN);
        }

        @Test
        @DisplayName("Wrong type for field → ERROR")
        void wrongType_returnsError() throws Exception {
            TransformEngine engine = createEngine(SchemaValidationMode.STRICT);

            Path specPath = createTempSpec("""
                    id: strict-type
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                        properties:
                          count:
                            type: integer
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        { "doubled": .count * 2 }
                    """);
            engine.loadSpec(specPath);

            // count is a string, not integer
            JsonNode body = MAPPER.readTree("{\"count\": \"not-a-number\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/calc",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isError()).isTrue();
            assertThat(TestMessages.parseBody(result.errorResponse())
                            .get("type")
                            .asText())
                    .isEqualTo(InputSchemaViolation.URN);
        }
    }

    @Nested
    @DisplayName("Strict mode OFF (lenient, default)")
    class StrictModeOff {

        @Test
        @DisplayName("Non-conforming input proceeds without validation")
        void nonConformingInput_succeeds() throws Exception {
            TransformEngine engine = createEngine(SchemaValidationMode.LENIENT);

            Path specPath = createTempSpec("""
                    id: lenient-pass
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                        required:
                          - name
                        properties:
                          name:
                            type: string
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        { "greeting": "Hello" }
                    """);
            engine.loadSpec(specPath);

            // Missing required "name" — but lenient mode skips validation
            JsonNode body = MAPPER.readTree("{\"age\": 30}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/greet",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess())
                    .as("Lenient mode should skip input validation")
                    .isTrue();
        }

        @Test
        @DisplayName("Default mode is lenient")
        void defaultMode_isLenient() throws Exception {
            // Use the 1-arg constructor (no SchemaValidationMode)
            EngineRegistry registry = new EngineRegistry();
            registry.register(new JsltExpressionEngine());
            SpecParser specParser = new SpecParser(registry);
            TransformEngine engine = new TransformEngine(specParser);

            Path specPath = createTempSpec("""
                    id: default-lenient
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                        required:
                          - name
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        { "result": "ok" }
                    """);
            engine.loadSpec(specPath);

            // Missing required "name" — should still succeed with default (lenient) mode
            JsonNode body = MAPPER.readTree("{}");
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
        }
    }

    // --- Helpers ---

    private TransformEngine createEngine(SchemaValidationMode mode) {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        return new TransformEngine(specParser, new ErrorResponseBuilder(), EvalBudget.DEFAULT, mode);
    }

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
