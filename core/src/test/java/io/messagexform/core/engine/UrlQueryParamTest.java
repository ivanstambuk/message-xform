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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for URL query parameter add/remove (T-001-38b, FR-001-12, ADR-0027).
 * Validates static add, dynamic add with header context, remove with exact
 * match and glob patterns, and RFC 3986 §3.4 percent-encoding.
 */
@DisplayName("T-001-38b: URL query parameter add/remove")
class UrlQueryParamTest {

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
    @DisplayName("Static query parameter add")
    class StaticAdd {

        @Test
        @DisplayName("query.add.format: 'json' → static query param added")
        void staticQueryParamAdded() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-static-add
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
                      query:
                        add:
                          format: "json"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(body, null, null, null, "application/json", "/api/resource", "POST", null);

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().queryString()).isEqualTo("format=json");
        }

        @Test
        @DisplayName("static add preserves existing query params")
        void staticAdd_preservesExisting() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-static-add-preserves
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
                      query:
                        add:
                          format: "json"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message =
                    new Message(body, null, null, null, "application/json", "/api/resource", "POST", "existing=keep");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // Existing params preserved, new param added
            assertThat(result.message().queryString()).contains("existing=keep");
            assertThat(result.message().queryString()).contains("format=json");
        }
    }

    @Nested
    @DisplayName("Dynamic query parameter add")
    class DynamicAdd {

        @Test
        @DisplayName("query.add.correlationId.expr from header context")
        void dynamicQueryParamFromHeaders() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-dynamic-add
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
                      query:
                        add:
                          correlationId:
                            expr: '$headers."x-correlation-id"'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-correlation-id", "abc-123-def");
            Message message = new Message(body, headers, null, null, "application/json", "/api/resource", "POST", null);

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().queryString()).isEqualTo("correlationId=abc-123-def");
        }

        @Test
        @DisplayName("dynamic add from original body field (evaluates pre-transform)")
        void dynamicQueryParamFromOriginalBody() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-dynamic-body
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
                      query:
                        add:
                          version:
                            expr: .apiVersion
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"apiVersion\": \"v2\", \"data\": \"payload\"}");
            Message message = new Message(body, null, null, null, "application/json", "/api/resource", "POST", null);

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // Expression evaluated against original body (has .apiVersion)
            assertThat(result.message().queryString()).isEqualTo("version=v2");
        }
    }

    @Nested
    @DisplayName("Query parameter remove")
    class Remove {

        @Test
        @DisplayName("query.remove exact match removes specific params")
        void exactMatchRemovesParams() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-remove-exact
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
                      query:
                        remove: ["_debug", "_internal"]
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(
                    body,
                    null,
                    null,
                    null,
                    "application/json",
                    "/api/resource",
                    "POST",
                    "_debug=true&_internal=metric&existing=keep");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().queryString()).isEqualTo("existing=keep");
        }

        @Test
        @DisplayName("query.remove glob pattern removes matching params (_*)")
        void globPatternRemovesMatchingParams() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-remove-glob
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
                      query:
                        remove: ["_*"]
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(
                    body,
                    null,
                    null,
                    null,
                    "application/json",
                    "/api/resource",
                    "POST",
                    "_debug=true&_internal=metric&existing=keep&_trace=on");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // All underscore-prefixed params removed
            assertThat(result.message().queryString()).isEqualTo("existing=keep");
        }

        @Test
        @DisplayName("remove all params → null query string")
        void removeAllParams_nullQuery() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-remove-all
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
                      query:
                        remove: ["*"]
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message =
                    new Message(body, null, null, null, "application/json", "/api/resource", "POST", "foo=bar&baz=qux");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().queryString()).isNull();
        }
    }

    @Nested
    @DisplayName("Combined operations")
    class CombinedOperations {

        @Test
        @DisplayName("S-001-38b: remove internal, add static + dynamic")
        void removeAndAdd_combined() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-combined
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
                      query:
                        remove: ["_debug", "_internal"]
                        add:
                          format: "json"
                          correlationId:
                            expr: '$headers."x-correlation-id"'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-correlation-id", "req-456");
            Message message = new Message(
                    body,
                    headers,
                    null,
                    null,
                    "application/json",
                    "/api/resource",
                    "POST",
                    "_debug=true&_internal=metric&existing=keep");

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            String qs = result.message().queryString();
            // Removed params gone
            assertThat(qs).doesNotContain("_debug");
            assertThat(qs).doesNotContain("_internal");
            // Existing preserved
            assertThat(qs).contains("existing=keep");
            // Static add
            assertThat(qs).contains("format=json");
            // Dynamic add
            assertThat(qs).contains("correlationId=req-456");
        }
    }

    @Nested
    @DisplayName("Percent-encoding")
    class PercentEncoding {

        @Test
        @DisplayName("query param values with spaces are percent-encoded")
        void valuesWithSpaces_percentEncoded() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-encoding
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
                      query:
                        add:
                          search:
                            expr: .searchTerm
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"searchTerm\": \"hello world\"}");
            Message message = new Message(body, null, null, null, "application/json", "/api/search", "GET", null);

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // Space encoded as %20 per RFC 3986
            assertThat(result.message().queryString()).isEqualTo("search=hello%20world");
        }
    }

    @Nested
    @DisplayName("No existing query string")
    class NoExistingQuery {

        @Test
        @DisplayName("remove on null query string → no change")
        void removeOnNullQuery_noChange() throws Exception {
            Path specPath = createTempSpec("""
                    id: query-remove-null
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
                      query:
                        remove: ["_debug"]
                        add:
                          format: "json"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(body, null, null, null, "application/json", "/api/resource", "POST", null);

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            // Only the static add should be present
            assertThat(result.message().queryString()).isEqualTo("format=json");
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
