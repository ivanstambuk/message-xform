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
 * Tests for URL block direction restriction (T-001-38e, FR-001-12).
 * URL rewriting only applies to request transforms. When a spec with
 * a url block is used in a response-direction transform, the url block
 * is ignored — path, query, and method remain unchanged.
 */
@DisplayName("T-001-38e: URL block on response transform → ignored")
class UrlDirectionRestrictionTest {

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
    @DisplayName("Response direction — url block ignored")
    class ResponseDirection {

        @Test
        @DisplayName("url.path.expr ignored for RESPONSE direction")
        void pathExpr_ignoredForResponse() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-response-path
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
                        expr: '"/rewritten/path"'
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/original/path",
                    "GET",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestPath()).isEqualTo("/original/path");
        }

        @Test
        @DisplayName("url.query operations ignored for RESPONSE direction")
        void queryOps_ignoredForResponse() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-response-query
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
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/api/resource",
                    "GET",
                    "_debug=true&existing=keep",
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            // Query string unchanged — url block ignored for response
            assertThat(result.message().queryString()).isEqualTo("_debug=true&existing=keep");
        }

        @Test
        @DisplayName("url.method.set ignored for RESPONSE direction")
        void methodSet_ignoredForResponse() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-response-method
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

        @Test
        @DisplayName("full url block (path + query + method) ignored for RESPONSE")
        void fullUrlBlock_ignoredForResponse() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-response-full
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
                      query:
                        add:
                          format: "json"
                      method:
                        set: "DELETE"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/original/path",
                    "GET",
                    "existing=keep",
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestPath()).isEqualTo("/original/path");
            assertThat(result.message().requestMethod()).isEqualTo("GET");
            assertThat(result.message().queryString()).isEqualTo("existing=keep");
        }
    }

    @Nested
    @DisplayName("Request direction — url block applied normally")
    class RequestDirection {

        @Test
        @DisplayName("url block applied normally for REQUEST direction")
        void fullUrlBlock_appliedForRequest() throws Exception {
            Path specPath = createTempSpec("""
                    id: url-request-applied
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
                      query:
                        add:
                          format: "json"
                      method:
                        set: "DELETE"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    null,
                    "/original/path",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().requestPath()).isEqualTo("/rewritten");
            assertThat(result.message().requestMethod()).isEqualTo("DELETE");
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
