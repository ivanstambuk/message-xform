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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests header add/remove/rename operations (T-001-34, FR-001-10).
 * Validates static header values, glob-pattern removal, rename, and
 * processing order (remove → rename → add static).
 */
@DisplayName("T-001-34: Header add/remove/rename operations")
class HeaderTransformTest {

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
    @DisplayName("headers.add — static values")
    class AddStatic {

        @Test
        @DisplayName("adds static headers to the output message")
        void addStaticHeaders() throws Exception {
            Path specPath = createTempSpec("""
                    id: header-add-test
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
                        x-transformed-by: "message-xform"
                        x-spec-version: "1.0.0"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(body, Map.of(), Map.of(), 200, "application/json", "/api/test", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().headers())
                    .containsEntry("x-transformed-by", "message-xform")
                    .containsEntry("x-spec-version", "1.0.0");
        }

        @Test
        @DisplayName("static add preserves existing headers not targeted by operations")
        void addPreservesExistingHeaders() throws Exception {
            Path specPath = createTempSpec("""
                    id: header-add-preserve
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
                        x-new-header: "added"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(
                    body,
                    Map.of("x-existing", "keep-me", "content-type", "application/json"),
                    Map.of("x-existing", List.of("keep-me"), "content-type", List.of("application/json")),
                    200,
                    "application/json",
                    "/api/test",
                    "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().headers())
                    .containsEntry("x-existing", "keep-me")
                    .containsEntry("x-new-header", "added");
        }
    }

    @Nested
    @DisplayName("headers.remove — glob patterns")
    class Remove {

        @Test
        @DisplayName("removes headers matching glob pattern")
        void removeGlobPattern() throws Exception {
            Path specPath = createTempSpec("""
                    id: header-remove-test
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
                        - "x-debug-*"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(
                    body,
                    Map.of(
                            "x-internal-id", "999",
                            "x-internal-trace", "abc",
                            "x-debug-info", "verbose",
                            "x-request-id", "keep-this",
                            "content-type", "application/json"),
                    Map.of(
                            "x-internal-id", List.of("999"),
                            "x-internal-trace", List.of("abc"),
                            "x-debug-info", List.of("verbose"),
                            "x-request-id", List.of("keep-this"),
                            "content-type", List.of("application/json")),
                    200,
                    "application/json",
                    "/api/test",
                    "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().headers())
                    .doesNotContainKey("x-internal-id")
                    .doesNotContainKey("x-internal-trace")
                    .doesNotContainKey("x-debug-info")
                    .containsEntry("x-request-id", "keep-this")
                    .containsEntry("content-type", "application/json");
        }

        @Test
        @DisplayName("removes headers with exact name (no glob)")
        void removeExactName() throws Exception {
            Path specPath = createTempSpec("""
                    id: header-remove-exact
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
                        - "x-secret"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(
                    body,
                    Map.of("x-secret", "classified", "x-public", "visible"),
                    Map.of("x-secret", List.of("classified"), "x-public", List.of("visible")),
                    200,
                    "application/json",
                    "/api/test",
                    "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().headers()).doesNotContainKey("x-secret").containsEntry("x-public", "visible");
        }
    }

    @Nested
    @DisplayName("headers.rename")
    class Rename {

        @Test
        @DisplayName("renames headers from old name to new name")
        void renameHeader() throws Exception {
            Path specPath = createTempSpec("""
                    id: header-rename-test
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
                        x-old-header: x-new-header
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(
                    body,
                    Map.of("x-old-header", "my-value", "x-other", "unchanged"),
                    Map.of("x-old-header", List.of("my-value"), "x-other", List.of("unchanged")),
                    200,
                    "application/json",
                    "/api/test",
                    "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().headers())
                    .doesNotContainKey("x-old-header")
                    .containsEntry("x-new-header", "my-value")
                    .containsEntry("x-other", "unchanged");
        }

        @Test
        @DisplayName("rename is a no-op when source header does not exist")
        void renameNonexistentHeader() throws Exception {
            Path specPath = createTempSpec("""
                    id: header-rename-noop
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
                        x-missing: x-target
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(
                    body,
                    Map.of("x-existing", "val"),
                    Map.of("x-existing", List.of("val")),
                    200,
                    "application/json",
                    "/api/test",
                    "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().headers())
                    .doesNotContainKey("x-missing")
                    .doesNotContainKey("x-target")
                    .containsEntry("x-existing", "val");
        }
    }

    @Nested
    @DisplayName("Processing order: remove → rename → add")
    class ProcessingOrder {

        @Test
        @DisplayName("combined operations execute in correct order")
        void combinedOperationsOrder() throws Exception {
            // This test validates the processing order:
            // 1. remove — strip x-internal-* headers
            // 2. rename — rename x-old → x-renamed
            // 3. add — add x-transformed-by: message-xform
            Path specPath = createTempSpec("""
                    id: header-combined-test
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
                        x-transformed-by: "message-xform"
                      remove:
                        - "x-internal-*"
                      rename:
                        x-old: x-renamed
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(
                    body,
                    Map.of(
                            "x-internal-id", "999",
                            "x-old", "legacy-value",
                            "x-keep", "preserved"),
                    Map.of(
                            "x-internal-id", List.of("999"),
                            "x-old", List.of("legacy-value"),
                            "x-keep", List.of("preserved")),
                    200,
                    "application/json",
                    "/api/test",
                    "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            Map<String, String> headers = result.message().headers();
            // removed
            assertThat(headers).doesNotContainKey("x-internal-id");
            // renamed
            assertThat(headers).doesNotContainKey("x-old");
            assertThat(headers).containsEntry("x-renamed", "legacy-value");
            // added
            assertThat(headers).containsEntry("x-transformed-by", "message-xform");
            // preserved
            assertThat(headers).containsEntry("x-keep", "preserved");
        }

        @Test
        @DisplayName("add overwrites an existing header if same name")
        void addOverwritesExistingHeader() throws Exception {
            Path specPath = createTempSpec("""
                    id: header-add-overwrite
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
                        x-version: "2.0"
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"payload\": \"hello\"}");
            Message message = new Message(
                    body,
                    Map.of("x-version", "1.0"),
                    Map.of("x-version", List.of("1.0")),
                    200,
                    "application/json",
                    "/api/test",
                    "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().headers()).containsEntry("x-version", "2.0");
        }
    }

    @Nested
    @DisplayName("No headers block — backward compatibility")
    class NoHeadersBlock {

        @Test
        @DisplayName("spec without headers block passes headers through unchanged")
        void noHeadersBlock_passesThrough() throws Exception {
            Path specPath = createTempSpec("""
                    id: no-headers-test
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
            Message message = new Message(
                    body,
                    Map.of("x-existing", "keep"),
                    Map.of("x-existing", List.of("keep")),
                    200,
                    "application/json",
                    "/api/test",
                    "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().headers()).containsEntry("x-existing", "keep");
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
