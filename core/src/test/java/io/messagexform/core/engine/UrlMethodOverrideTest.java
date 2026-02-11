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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for HTTP method override (T-001-38c, FR-001-12, ADR-0027).
 * Validates unconditional method set, conditional set with 'when' predicate
 * (truthy and falsy), and evaluation context (original body per ADR-0027).
 */
@DisplayName("T-001-38c: HTTP method override")
class UrlMethodOverrideTest {

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
    @DisplayName("Unconditional method override")
    class UnconditionalOverride {

        @Test
        @DisplayName("method.set: 'DELETE' (unconditional) → method changed to DELETE")
        void unconditionalMethodOverride() throws Exception {
            Path specPath = createTempSpec("""
                    id: method-unconditional
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: .
                    url:
                      method:
                        set: "DELETE"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"resourceId\": \"123\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    null,
                    "/api/resource/123",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestMethod()).isEqualTo("DELETE");
        }
    }

    @Nested
    @DisplayName("Conditional method override (method.when)")
    class ConditionalOverride {

        @Test
        @DisplayName("method.when predicate is truthy → method changed")
        void whenPredicateTruthy_methodChanged() throws Exception {
            Path specPath = createTempSpec("""
                    id: method-conditional-match
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: .
                    url:
                      method:
                        set: "GET"
                        when: '.action == "read"'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"action\": \"read\", \"resourceId\": \"42\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    null,
                    "/api/resource",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestMethod()).isEqualTo("GET");
        }

        @Test
        @DisplayName("method.when predicate is falsy → method unchanged")
        void whenPredicateFalsy_methodUnchanged() throws Exception {
            Path specPath = createTempSpec("""
                    id: method-conditional-no-match
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: .
                    url:
                      method:
                        set: "GET"
                        when: '.action == "read"'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"action\": \"write\", \"resourceId\": \"42\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    null,
                    "/api/resource",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestMethod()).isEqualTo("POST"); // unchanged
        }
    }

    @Nested
    @DisplayName("ADR-0027: method.when evaluates against original body")
    class OriginalBodyEvaluation {

        @Test
        @DisplayName("method.when evaluates against original body (pre-transform)")
        void methodWhen_evaluatesOriginalBody() throws Exception {
            Path specPath = createTempSpec("""
                    id: method-original-body
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
                        { "result": "transformed" }
                    url:
                      method:
                        set: "DELETE"
                        when: '.action == "delete"'
                    """);

            engine.loadSpec(specPath);

            // Body has .action = "delete", but transform strips it
            JsonNode body = MAPPER.readTree("{\"action\": \"delete\", \"resourceId\": \"99\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    null,
                    "/api/resource/99",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // method.when evaluates against ORIGINAL body (has .action), so method changes
            assertThat(result.message().requestMethod()).isEqualTo("DELETE");
            // Body was transformed (no .action in output)
            assertThat(TestMessages.parseBody(result.message().body()).has("action"))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Combined with path rewrite")
    class CombinedWithPathRewrite {

        @Test
        @DisplayName("full de-polymorphization: path + method + body transform")
        void fullDePolymorphization() throws Exception {
            Path specPath = createTempSpec("""
                    id: full-depoly
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
                        { "name": .name }
                    url:
                      path:
                        expr: '"/api/" + .resource + "/" + .resourceId'
                      method:
                        set: "DELETE"
                        when: '.action == "delete"'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree(
                    "{\"action\": \"delete\", \"resource\": \"users\", \"resourceId\": \"42\", \"name\": \"Bob\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    null,
                    "/api/dispatch",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestPath()).isEqualTo("/api/users/42");
            assertThat(result.message().requestMethod()).isEqualTo("DELETE");
            assertThat(TestMessages.parseBody(result.message().body()).has("name"))
                    .isTrue();
            assertThat(TestMessages.parseBody(result.message().body()).has("action"))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Direction handling")
    class DirectionHandling {

        @Test
        @DisplayName("method override skipped for RESPONSE direction")
        void methodOverride_skippedForResponse() throws Exception {
            Path specPath = createTempSpec("""
                    id: method-response-skip
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: .
                    url:
                      method:
                        set: "DELETE"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/resource",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestMethod()).isEqualTo("POST"); // unchanged
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
