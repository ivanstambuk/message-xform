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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests header name normalization to lowercase (T-001-36, FR-001-10, RFC 9110
 * §5.1).
 * Verifies that mixed-case header names are normalized in $headers binding and
 * that header operations match case-insensitively.
 */
@DisplayName("T-001-36: Header name normalization to lowercase")
class HeaderNormalizationTest {

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
    @DisplayName("$headers keys are normalized to lowercase even when input has mixed case")
    void headersBinding_normalizedToLowercase() throws Exception {
        Path specPath = createTempSpec("""
                id: header-norm-binding-test
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
                      "contentType": $headers."content-type"
                    }
                """);

        engine.loadSpec(specPath);

        // Simulate mixed-case headers from a gateway adapter
        Map<String, String> mixedCaseHeaders = new LinkedHashMap<>();
        mixedCaseHeaders.put("x-request-id", "req-123");
        mixedCaseHeaders.put("content-type", "application/json");

        Map<String, List<String>> mixedCaseHeadersAll = new LinkedHashMap<>();
        mixedCaseHeadersAll.put("x-request-id", List.of("req-123"));
        mixedCaseHeadersAll.put("content-type", List.of("application/json"));

        JsonNode body = MAPPER.readTree("{\"data\": \"test\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                TestMessages.toHeaders(mixedCaseHeaders, mixedCaseHeadersAll),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("requestId")
                        .asText())
                .isEqualTo("req-123");
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("contentType")
                        .asText())
                .isEqualTo("application/json");
    }

    @Test
    @DisplayName("header remove matches case-insensitively (glob pattern)")
    void remove_matchesCaseInsensitively() throws Exception {
        Path specPath = createTempSpec("""
                id: header-norm-remove-test
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
                  remove:
                    - "x-internal-*"
                """);

        engine.loadSpec(specPath);

        // Headers with varying case — all should be removed by the lowercased pattern
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-internal-id", "123");
        headers.put("x-internal-trace", "abc");
        headers.put("x-public", "visible");

        Map<String, List<String>> headersAll = new LinkedHashMap<>();
        headersAll.put("x-internal-id", List.of("123"));
        headersAll.put("x-internal-trace", List.of("abc"));
        headersAll.put("x-public", List.of("visible"));

        JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                TestMessages.toHeaders(headers, headersAll),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.message().headers().toSingleValueMap())
                .doesNotContainKey("x-internal-id")
                .doesNotContainKey("x-internal-trace")
                .containsEntry("x-public", "visible");
    }

    @Test
    @DisplayName("header rename normalizes both old and new names to lowercase")
    void rename_normalizesNamesToLowercase() throws Exception {
        Path specPath = createTempSpec("""
                id: header-norm-rename-test
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
                  rename:
                    x-old-name: x-new-name
                """);

        engine.loadSpec(specPath);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-old-name", "my-value");
        headers.put("x-keep", "untouched");

        Map<String, List<String>> headersAll = new LinkedHashMap<>();
        headersAll.put("x-old-name", List.of("my-value"));
        headersAll.put("x-keep", List.of("untouched"));

        JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                TestMessages.toHeaders(headers, headersAll),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.message().headers().toSingleValueMap())
                .doesNotContainKey("x-old-name")
                .containsEntry("x-new-name", "my-value")
                .containsEntry("x-keep", "untouched");
    }

    @Test
    @DisplayName("header add normalizes header names to lowercase")
    void add_normalizesNamesToLowercase() throws Exception {
        Path specPath = createTempSpec("""
                id: header-norm-add-test
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
                    X-Added-Header: "my-value"
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
        // Header name should be normalized to lowercase in the output
        assertThat(result.message().headers().toSingleValueMap()).containsEntry("x-added-header", "my-value");
        assertThat(result.message().headers().toSingleValueMap()).doesNotContainKey("X-Added-Header");
    }

    @Test
    @DisplayName("output headers are always lowercase regardless of input casing")
    void outputHeaders_alwaysLowercase() throws Exception {
        Path specPath = createTempSpec("""
                id: header-norm-output-test
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
                    X-New: "added"
                  rename:
                    x-old: x-renamed
                """);

        engine.loadSpec(specPath);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-old", "renamed-value");
        headers.put("x-untouched", "preserved");

        Map<String, List<String>> headersAll = new LinkedHashMap<>();
        headersAll.put("x-old", List.of("renamed-value"));
        headersAll.put("x-untouched", List.of("preserved"));

        JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                TestMessages.toHeaders(headers, headersAll),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        // All output header names must be lowercase
        for (String key : result.message().headers().toSingleValueMap().keySet()) {
            assertThat(key).isEqualTo(key.toLowerCase());
        }
        assertThat(result.message().headers().toSingleValueMap())
                .containsEntry("x-new", "added")
                .containsEntry("x-renamed", "renamed-value")
                .containsEntry("x-untouched", "preserved");
    }

    // --- Helper ---

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
