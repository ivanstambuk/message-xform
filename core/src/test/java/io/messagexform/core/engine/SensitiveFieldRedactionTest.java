package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.SensitivePathSyntaxError;
import io.messagexform.core.model.TransformSpec;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for sensitive field redaction (T-001-43, NFR-001-06, ADR-0019).
 *
 * <p>
 * Verifies that:
 * <ul>
 * <li>Valid sensitive paths are parsed and stored on TransformSpec</li>
 * <li>Invalid RFC 9535 paths trigger SensitivePathSyntaxError at load time</li>
 * <li>Sensitive fields appear in the spec model for downstream redaction</li>
 * </ul>
 */
@DisplayName("SensitiveFieldRedactionTest")
class SensitiveFieldRedactionTest {

    private SpecParser specParser;
    private TransformEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Test
    @DisplayName("Valid sensitive paths → parsed and stored on TransformSpec")
    void validSensitivePathsParsed() throws IOException {
        Path specPath = tempDir.resolve("sensitive-spec.yaml");
        Files.writeString(specPath, """
                id: auth-spec
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                sensitive:
                  - "$.authId"
                  - "$.callbacks[*].input[*].value"
                transform:
                  lang: jslt
                  expr: '.'
                """);

        TransformSpec spec = engine.loadSpec(specPath);

        assertThat(spec.hasSensitivePaths()).isTrue();
        assertThat(spec.sensitivePaths()).containsExactly("$.authId", "$.callbacks[*].input[*].value");
    }

    @Test
    @DisplayName("No sensitive block → sensitivePaths is null")
    void noSensitiveBlock() throws IOException {
        Path specPath = tempDir.resolve("plain-spec.yaml");
        Files.writeString(specPath, """
                id: plain-spec
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: '.'
                """);

        TransformSpec spec = engine.loadSpec(specPath);

        assertThat(spec.hasSensitivePaths()).isFalse();
        assertThat(spec.sensitivePaths()).isNull();
    }

    @Test
    @DisplayName("Invalid path (missing $ prefix) → SensitivePathSyntaxError at load time")
    void invalidPathMissingDollar() throws IOException {
        Path specPath = tempDir.resolve("bad-path.yaml");
        Files.writeString(specPath, """
                id: bad-spec
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                sensitive:
                  - "$.authId"
                  - "callbacks[*].input"
                transform:
                  lang: jslt
                  expr: '.'
                """);

        assertThatThrownBy(() -> engine.loadSpec(specPath))
                .isInstanceOf(SensitivePathSyntaxError.class)
                .hasMessageContaining("callbacks[*].input")
                .hasMessageContaining("must start with '$'");
    }

    @Test
    @DisplayName("Invalid path (invalid characters) → SensitivePathSyntaxError at load time")
    void invalidPathBadCharacters() throws IOException {
        Path specPath = tempDir.resolve("bad-chars.yaml");
        Files.writeString(specPath, """
                id: bad-chars-spec
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                sensitive:
                  - "$..authId"
                transform:
                  lang: jslt
                  expr: '.'
                """);

        assertThatThrownBy(() -> engine.loadSpec(specPath))
                .isInstanceOf(SensitivePathSyntaxError.class)
                .hasMessageContaining("$..authId");
    }

    @Test
    @DisplayName("Root-only path ($) → valid")
    void rootOnlyPathValid() throws IOException {
        Path specPath = tempDir.resolve("root-only.yaml");
        Files.writeString(specPath, """
                id: root-spec
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                sensitive:
                  - "$"
                transform:
                  lang: jslt
                  expr: '.'
                """);

        TransformSpec spec = engine.loadSpec(specPath);
        assertThat(spec.sensitivePaths()).containsExactly("$");
    }

    @Test
    @DisplayName("Empty sensitive list → no paths stored (hasSensitivePaths false)")
    void emptySensitiveList() throws IOException {
        Path specPath = tempDir.resolve("empty-sensitive.yaml");
        Files.writeString(specPath, """
                id: empty-spec
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                sensitive: []
                transform:
                  lang: jslt
                  expr: '.'
                """);

        TransformSpec spec = engine.loadSpec(specPath);
        assertThat(spec.hasSensitivePaths()).isFalse();
        assertThat(spec.sensitivePaths()).isEmpty();
    }
}
