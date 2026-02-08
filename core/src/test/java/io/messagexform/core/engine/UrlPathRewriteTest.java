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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for URL path rewriting (T-001-38a, FR-001-12, ADR-0027).
 * Validates path rewrite with JSLT expressions, original-body evaluation
 * context, RFC 3986 percent-encoding, and error handling for null/non-string
 * results.
 */
@DisplayName("T-001-38a: URL path rewrite with JSLT expression")
class UrlPathRewriteTest {

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
    @DisplayName("Basic path rewrite")
    class BasicPathRewrite {

        @Test
        @DisplayName("S-001-38a: de-polymorphize dispatch — path rewritten from body fields")
        void dePolymorphizeDispatch_pathRewritten() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-path-basic
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
                        { "name": .name, "email": .email }
                    url:
                      path:
                        expr: '"/api/" + .action + "/" + .resourceId'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree(
                    "{\"action\": \"users\", \"resourceId\": \"123\", \"name\": \"Bob Jensen\", \"email\": \"bjensen@example.com\"}");
            Message message = new Message(body, null, null, null, "application/json", "/dispatch", "POST");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // Body transform strips routing fields
            assertThat(result.message().body().has("action")).isFalse();
            assertThat(result.message().body().has("resourceId")).isFalse();
            assertThat(result.message().body().get("name").asText()).isEqualTo("Bob Jensen");
            assertThat(result.message().body().get("email").asText()).isEqualTo("bjensen@example.com");
            // Path rewritten from original body fields
            assertThat(result.message().requestPath()).isEqualTo("/api/users/123");
        }

        @Test
        @DisplayName("path rewrite evaluates against ORIGINAL body (ADR-0027)")
        void pathRewrite_evaluatesAgainstOriginalBody() throws Exception {
            // Body transform completely restructures the body — routing fields are gone.
            // URL path expr must still access the original body fields.
            Path specPath = createTempSpec("""
                    id: url-path-original-body
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
                        { "result": "transformed", "preserved": .data }
                    url:
                      path:
                        expr: '"/resources/" + .resourceType + "/" + .resourceId'
                    """);

            engine.loadSpec(specPath);

            JsonNode body =
                    MAPPER.readTree("{\"resourceType\": \"orders\", \"resourceId\": \"456\", \"data\": \"payload\"}");
            Message message = new Message(body, null, null, null, "application/json", "/generic", "POST");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // Transformed body does NOT have routing fields
            assertThat(result.message().body().get("result").asText()).isEqualTo("transformed");
            assertThat(result.message().body().has("resourceType")).isFalse();
            // But path was still rewritten from original body
            assertThat(result.message().requestPath()).isEqualTo("/resources/orders/456");
        }
    }

    @Nested
    @DisplayName("RFC 3986 percent-encoding")
    class PercentEncoding {

        @Test
        @DisplayName("body field with spaces → percent-encoded in path")
        void bodyFieldWithSpaces_percentEncoded() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-path-encoding
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
                      path:
                        expr: '"/api/search/" + .query'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"query\": \"hello world\"}");
            Message message = new Message(body, null, null, null, "application/json", "/search", "GET");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // Space is encoded as %20, but '/' is preserved
            assertThat(result.message().requestPath()).isEqualTo("/api/search/hello%20world");
        }

        @Test
        @DisplayName("path with special characters → percent-encoded per RFC 3986 §3.3")
        void specialCharacters_percentEncoded() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-path-special-chars
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
                      path:
                        expr: '"/api/files/" + .fileName'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"fileName\": \"report 2026 Q1.pdf\"}");
            Message message = new Message(body, null, null, null, "application/json", "/files", "GET");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // Spaces and special chars encoded, slashes preserved
            String path = result.message().requestPath();
            assertThat(path).startsWith("/api/files/");
            assertThat(path).doesNotContain(" ");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("path.expr returns null → ExpressionEvalException → error response")
        void pathExprReturnsNull_errorResponse() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-path-null
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
                      path:
                        expr: .missingField
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(body, null, null, null, "application/json", "/original", "POST");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            // Should be an error — path.expr returned null
            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("path.expr returns non-string → ExpressionEvalException → error response")
        void pathExprReturnsNonString_errorResponse() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-path-non-string
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
                      path:
                        expr: '42'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(body, null, null, null, "application/json", "/original", "POST");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            // Should be an error — path.expr returned a number, not a string
            assertThat(result.isError()).isTrue();
        }
    }

    @Nested
    @DisplayName("Direction handling")
    class DirectionHandling {

        @Test
        @DisplayName("URL rewrite only applied for REQUEST direction")
        void urlRewrite_appliedOnlyForRequest() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-path-request-only
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
                      path:
                        expr: '"/rewritten"'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");

            // Response direction — URL block should be ignored
            Message responseMessage = new Message(body, null, null, 200, "application/json", "/original", "GET");
            TransformResult responseResult = engine.transform(responseMessage, Direction.RESPONSE);

            assertThat(responseResult.isSuccess()).isTrue();
            assertThat(responseResult.message().requestPath()).isEqualTo("/original");

            // Request direction — URL block should be applied
            Message requestMessage = new Message(body, null, null, null, "application/json", "/original", "POST");
            TransformResult requestResult = engine.transform(requestMessage, Direction.REQUEST);

            assertThat(requestResult.isSuccess()).isTrue();
            assertThat(requestResult.message().requestPath()).isEqualTo("/rewritten");
        }
    }

    @Nested
    @DisplayName("No URL block — backward compatibility")
    class NoUrlBlock {

        @Test
        @DisplayName("spec without url block preserves original path")
        void noUrlBlock_preservesOriginalPath() throws Exception {
            Path specPath = createTempSpec("""
                    id: no-url-test
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
            Message message = new Message(body, null, null, null, "application/json", "/api/test", "POST");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestPath()).isEqualTo("/api/test");
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
