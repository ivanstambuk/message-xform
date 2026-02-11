package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformContext;
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
 * Tests for the 3-arg
 * {@code TransformEngine.transform(Message, Direction, TransformContext)}
 * overload (T-004-01, Q-042). Verifies that adapter-injected context (cookies,
 * query params)
 * flows through to JSLT expressions, and that the existing 2-arg method remains
 * backward-compatible.
 */
@DisplayName("T-004-01: TransformContext injection — 3-arg transform() overload")
class TransformContextInjectionTest {

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
    @DisplayName("3-arg transform: $cookies available in JSLT from injected TransformContext")
    void threeArgTransform_cookiesAvailableInJslt() throws Exception {
        Path specPath = createTempSpec("""
        id: cookie-context-test
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
              "sessionId": $cookies."session",
              "lang": $cookies."lang",
              "data": .payload
            }
        """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
        Message message = new Message(
                TestMessages.toBody(body),
                HttpHeaders.empty(),
                null,
                "/api/test",
                "POST",
                null,
                SessionContext.empty());

        TransformContext context = new TransformContext(
                message.headers(),
                null, // status — null for requests (ADR-0017)
                null, // queryParams
                Map.of("session", "abc123", "lang", "en"),
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.REQUEST, context);

        assertThat(result.isSuccess()).isTrue();
        JsonNode output = TestMessages.parseBody(result.message().body());
        assertThat(output.get("sessionId").asText()).isEqualTo("abc123");
        assertThat(output.get("lang").asText()).isEqualTo("en");
        assertThat(output.get("data").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("3-arg transform: $queryParams available in JSLT from injected TransformContext")
    void threeArgTransform_queryParamsAvailableInJslt() throws Exception {
        Path specPath = createTempSpec("""
        id: queryparam-context-test
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
              "page": $queryParams."page",
              "sort": $queryParams."sort",
              "data": .payload
            }
        """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"items\"}");
        Message message = new Message(
                TestMessages.toBody(body),
                HttpHeaders.empty(),
                null,
                "/api/items",
                "GET",
                null,
                SessionContext.empty());

        TransformContext context = new TransformContext(
                message.headers(), null, Map.of("page", "2", "sort", "name"), null, SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.REQUEST, context);

        assertThat(result.isSuccess()).isTrue();
        JsonNode output = TestMessages.parseBody(result.message().body());
        assertThat(output.get("page").asText()).isEqualTo("2");
        assertThat(output.get("sort").asText()).isEqualTo("name");
        assertThat(output.get("data").asText()).isEqualTo("items");
    }

    @Test
    @DisplayName("3-arg transform: combined $cookies + $queryParams + $headers in JSLT")
    void threeArgTransform_combinedContextBindings() throws Exception {
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
              "session": $cookies."session",
              "page": $queryParams."page",
              "data": .payload
            }
        """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"combined\"}");
        Message message = new Message(
                TestMessages.toBody(body),
                TestMessages.toHeaders(Map.of("x-request-id", "trace-789"), null),
                null,
                "/api/test",
                "POST",
                null,
                SessionContext.empty());

        TransformContext context = new TransformContext(
                message.headers(), null, Map.of("page", "5"), Map.of("session", "xyz789"), SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.REQUEST, context);

        assertThat(result.isSuccess()).isTrue();
        JsonNode output = TestMessages.parseBody(result.message().body());
        assertThat(output.get("requestId").asText()).isEqualTo("trace-789");
        assertThat(output.get("session").asText()).isEqualTo("xyz789");
        assertThat(output.get("page").asText()).isEqualTo("5");
        assertThat(output.get("data").asText()).isEqualTo("combined");
    }

    @Test
    @DisplayName("2-arg transform: backward-compatible — $cookies and $queryParams are empty maps")
    void twoArgTransform_backwardCompatible_emptyContextMaps() throws Exception {
        Path specPath = createTempSpec("""
        id: backward-compat-test
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
              "hasCookies": $cookies != null,
              "hasQueryParams": $queryParams != null,
              "data": .payload
            }
        """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"test\"}");
        Message message = new Message(
                TestMessages.toBody(body), HttpHeaders.empty(), null, "/api/test", "GET", null, SessionContext.empty());

        // Use the existing 2-arg method — should NOT throw, $cookies and $queryParams
        // should be empty objects (not null)
        TransformResult result = engine.transform(message, Direction.REQUEST);

        assertThat(result.isSuccess()).isTrue();
        JsonNode output = TestMessages.parseBody(result.message().body());
        assertThat(output.get("hasCookies").asBoolean()).isTrue();
        assertThat(output.get("hasQueryParams").asBoolean()).isTrue();
        assertThat(output.get("data").asText()).isEqualTo("test");
    }

    @Test
    @DisplayName("3-arg transform: null TransformContext throws NullPointerException")
    void threeArgTransform_nullContext_throwsNPE() throws Exception {
        Path specPath = createTempSpec("""
        id: null-context-test
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
            .
        """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"test\"}");
        Message message = new Message(
                TestMessages.toBody(body), HttpHeaders.empty(), null, "/api/test", "GET", null, SessionContext.empty());

        assertThatThrownBy(() -> engine.transform(message, Direction.REQUEST, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    @DisplayName("3-arg transform: injected context overrides engine-built context for headers/status")
    void threeArgTransform_injectedContextOverridesEngineContext() throws Exception {
        // The injected context's headers should be used, not re-built from the message
        Path specPath = createTempSpec("""
        id: override-context-test
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
              "injectedHeader": $headers."x-custom",
              "data": .payload
            }
        """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"payload\": \"override\"}");
        // Message has no x-custom header
        Message message = new Message(
                TestMessages.toBody(body),
                TestMessages.toHeaders(Map.of("content-type", "application/json"), null),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        // But the injected context DOES have x-custom
        TransformContext context = new TransformContext(
                TestMessages.toHeaders(Map.of("content-type", "application/json", "x-custom", "injected-value"), null),
                200,
                null,
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE, context);

        assertThat(result.isSuccess()).isTrue();
        JsonNode output = TestMessages.parseBody(result.message().body());
        assertThat(output.get("injectedHeader").asText()).isEqualTo("injected-value");
    }

    // --- Helper ---

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
