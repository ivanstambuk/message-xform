package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.SpecParseException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for status code transformations (T-001-37, FR-001-11, ADR-0003).
 * Validates unconditional set, conditional when predicates, load-time
 * validation of status codes, and when predicate evaluation error handling.
 */
@DisplayName("T-001-37: Status code override with set + when predicate")
class StatusTransformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransformEngine engine;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Nested
    @DisplayName("Unconditional status set")
    class UnconditionalSet {

        @Test
        @DisplayName("S-001-38: set: 202 → status changed unconditionally")
        void unconditionalSet_changesStatusCode() throws Exception {
            Path specPath = createTempSpec("""
                    id: status-unconditional
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
                        { "accepted": true, "id": .id }
                    status:
                      set: 202
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"id\": \"job-42\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/api/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().statusCode()).isEqualTo(202);
            assertThat(result.message().body().get("accepted").asBoolean()).isTrue();
            assertThat(result.message().body().get("id").asText()).isEqualTo("job-42");
        }
    }

    @Nested
    @DisplayName("Conditional status set — when predicate")
    class ConditionalSet {

        @Test
        @DisplayName("S-001-36: when '.error != null' + error in body → status changed")
        void conditionalSet_errorPresent_statusChanged() throws Exception {
            Path specPath = createTempSpec("""
                    id: status-conditional-match
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
                          "success": false,
                          "error": .error,
                          "message": .error_description
                        }
                    status:
                      set: 400
                      when: '.error != null'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree(
                    "{\"error\": \"invalid_grant\", \"error_description\": \"The provided grant is invalid or expired\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/api/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().statusCode()).isEqualTo(400);
            assertThat(result.message().body().get("success").asBoolean()).isFalse();
            assertThat(result.message().body().get("error").asText()).isEqualTo("invalid_grant");
        }

        @Test
        @DisplayName("S-001-37 pattern: when '.error != null' + no error → status unchanged")
        void conditionalSet_noError_statusUnchanged() throws Exception {
            Path specPath = createTempSpec("""
                    id: status-conditional-nomatch
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
                          "success": true,
                          "data": .data
                        }
                    status:
                      set: 400
                      when: '.error != null'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"all good\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/api/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().statusCode()).isEqualTo(200);
            assertThat(result.message().body().get("success").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("when predicate evaluates against *transformed* body (ADR-0003)")
        void conditionalSet_evaluatesAgainstTransformedBody() throws Exception {
            // The transform adds a "computed" field; the when predicate checks for it
            Path specPath = createTempSpec("""
                    id: status-transformed-body
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
                          "computed": "present",
                          "data": .data
                        }
                    status:
                      set: 201
                      when: '.computed != null'
                    """);

            engine.loadSpec(specPath);

            // Input does NOT have "computed" — it's only in the transformed output
            JsonNode body = MAPPER.readTree("{\"data\": \"test\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/api/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            // "computed" exists in transformed body → when is true → status changed
            assertThat(result.message().statusCode()).isEqualTo(201);
        }
    }

    @Nested
    @DisplayName("Load-time validation")
    class LoadTimeValidation {

        @Test
        @DisplayName("status code below 100 → rejected at load time")
        void invalidStatusCode_below100_rejected() throws Exception {
            Path specPath = createTempSpec("""
                    id: status-invalid-low
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
                        { "data": .data }
                    status:
                      set: 99
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("99")
                    .hasMessageContaining("100")
                    .hasMessageContaining("599");
        }

        @Test
        @DisplayName("status code above 599 → rejected at load time")
        void invalidStatusCode_above599_rejected() throws Exception {
            Path specPath = createTempSpec("""
                    id: status-invalid-high
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
                        { "data": .data }
                    status:
                      set: 600
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("600")
                    .hasMessageContaining("100")
                    .hasMessageContaining("599");
        }

        @Test
        @DisplayName("status block without 'set' field → rejected at load time")
        void statusBlock_missingSet_rejected() throws Exception {
            Path specPath = createTempSpec("""
                    id: status-no-set
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
                        { "data": .data }
                    status:
                      when: '.error != null'
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("set");
        }
    }

    @Nested
    @DisplayName("When predicate error handling")
    class WhenPredicateErrors {

        @Test
        @DisplayName("when predicate evaluation error → abort, keep original status")
        void whenPredicateError_keepsOriginalStatus() throws Exception {
            // Use a when predicate that will fail at evaluation time
            // (e.g., type error: comparing object to number)
            Path specPath = createTempSpec("""
                    id: status-when-error
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
                        { "data": .data }
                    status:
                      set: 500
                      when: 'contains(.nonexistentArray, "value")'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"test\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/api/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            // Should still succeed — status predicate error is non-fatal
            assertThat(result.isSuccess()).isTrue();
            // Original status preserved because when predicate failed
            assertThat(result.message().statusCode()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("No status block — backward compatibility")
    class NoStatusBlock {

        @Test
        @DisplayName("spec without status block preserves original status code")
        void noStatusBlock_preservesOriginalStatus() throws Exception {
            Path specPath = createTempSpec("""
                    id: no-status-test
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
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(body, null, null, 200, "application/json", "/api/test", "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().statusCode()).isEqualTo(200);
        }
    }

    // --- Helper ---

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
