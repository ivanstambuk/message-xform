package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.SpecParseException;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for URL method validation at load time (T-001-38d, FR-001-12).
 * Invalid HTTP methods in url.method.set MUST be rejected with
 * SpecParseException.
 * Valid methods: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS.
 */
@DisplayName("T-001-38d: URL method validation at load time")
class UrlMethodValidationTest {

    private TransformEngine engine;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Nested
    @DisplayName("Invalid methods rejected")
    class InvalidMethods {

        @ParameterizedTest(name = "method.set: ''{0}'' → SpecParseException")
        @ValueSource(strings = {"YOLO", "CONNECT", "TRACE", "INVALID", "postput", "123"})
        @DisplayName("Invalid HTTP methods rejected at load time")
        void invalidMethod_throwsSpecParseException(String method) throws Exception {
            Path specPath = createTempSpec(String.format("""
                    id: method-invalid
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
                        set: "%s"
                    """, method));

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Invalid HTTP method");
        }
    }

    @Nested
    @DisplayName("Valid methods accepted")
    class ValidMethods {

        @ParameterizedTest(name = "method.set: ''{0}'' → accepted")
        @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
        @DisplayName("Valid HTTP methods accepted at load time")
        void validMethod_accepted(String method) throws Exception {
            Path specPath = createTempSpec(String.format("""
                    id: method-valid
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
                        set: "%s"
                    """, method));

            assertThatCode(() -> engine.loadSpec(specPath)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("method.set in lowercase normalized to uppercase")
        void lowercaseMethod_normalizedToUppercase() throws Exception {
            Path specPath = createTempSpec("""
                    id: method-lowercase
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
                        set: "delete"
                    """);

            // Should load without error — normalized to DELETE
            assertThatCode(() -> engine.loadSpec(specPath)).doesNotThrowAnyException();
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
