package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.SpecParseException;
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
 * Tests for mapper validation rules (T-001-40, FR-001-08).
 * Enforces all load-time validation rules for mapper pipelines.
 */
@DisplayName("T-001-40: Mapper validation rules (FR-001-08)")
class MapperValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransformEngine engine;
    private SpecParser specParser;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Nested
    @DisplayName("expr missing from apply")
    class ExprMissingFromApply {

        @Test
        @DisplayName("apply list without expr → SpecParseException at load time")
        void applyWithoutExpr_throwsSpecParseException() throws Exception {
            Path specPath = createTempSpec("""
                    id: missing-expr
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    mappers:
                      sanitize:
                        lang: jslt
                        expr: .
                    transform:
                      lang: jslt
                      expr: .
                      apply:
                        - mapperRef: sanitize
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("expr");
        }
    }

    @Nested
    @DisplayName("expr appears twice in apply")
    class ExprAppearsTwice {

        @Test
        @DisplayName("apply list with duplicate expr → SpecParseException at load time")
        void duplicateExpr_throwsSpecParseException() throws Exception {
            Path specPath = createTempSpec("""
                    id: duplicate-expr
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    mappers:
                      sanitize:
                        lang: jslt
                        expr: .
                    transform:
                      lang: jslt
                      expr: .
                      apply:
                        - expr
                        - mapperRef: sanitize
                        - expr
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("expr")
                    .hasMessageContaining("once");
        }
    }

    @Nested
    @DisplayName("Unknown mapper id in apply")
    class UnknownMapperId {

        @Test
        @DisplayName("apply references non-existent mapper → SpecParseException at load time")
        void unknownMapperRef_throwsSpecParseException() throws Exception {
            Path specPath = createTempSpec("""
                    id: unknown-mapper
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
                      apply:
                        - expr
                        - mapperRef: does-not-exist
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("does-not-exist")
                    .hasMessageContaining("Unknown mapper");
        }

        @Test
        @DisplayName("apply references mapper from wrong block → SpecParseException")
        void mapperFromWrongBlock_throwsSpecParseException() throws Exception {
            // Mapper 'alpha' is defined but 'beta' is referenced — typo scenario
            Path specPath = createTempSpec("""
                    id: wrong-mapper
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    mappers:
                      alpha:
                        lang: jslt
                        expr: .
                    transform:
                      lang: jslt
                      expr: .
                      apply:
                        - mapperRef: beta
                        - expr
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("beta")
                    .hasMessageContaining("Unknown mapper");
        }
    }

    @Nested
    @DisplayName("Duplicate mapper id in apply")
    class DuplicateMapperIdInApply {

        @Test
        @DisplayName("same mapper referenced twice in apply → SpecParseException")
        void duplicateMapperRef_throwsSpecParseException() throws Exception {
            Path specPath = createTempSpec("""
                    id: dup-mapper-ref
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    mappers:
                      sanitize:
                        lang: jslt
                        expr: .
                    transform:
                      lang: jslt
                      expr: .
                      apply:
                        - mapperRef: sanitize
                        - expr
                        - mapperRef: sanitize
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("sanitize")
                    .hasMessageContaining("duplicate");
        }
    }

    @Nested
    @DisplayName("Invalid apply step format")
    class InvalidApplyStepFormat {

        @Test
        @DisplayName("apply step with unexpected key → SpecParseException")
        void unexpectedKey_throwsSpecParseException() throws Exception {
            Path specPath = createTempSpec("""
                    id: bad-step
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
                      apply:
                        - expr
                        - unknown: some-value
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Invalid apply step");
        }

        @Test
        @DisplayName("apply is not a list → SpecParseException")
        void applyNotAList_throwsSpecParseException() throws Exception {
            Path specPath = createTempSpec("""
                    id: apply-not-list
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
                      apply: just-a-string
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("apply")
                    .hasMessageContaining("list");
        }
    }

    @Nested
    @DisplayName("Backward compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("apply absent → normal single-expression evaluation (backwards compat)")
        void absentApply_normalEvaluation() throws Exception {
            Path specPath = createTempSpec("""
                    id: no-apply-compat
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
                        { "result": .data }
                    """);

            engine.loadSpec(specPath);

            JsonNode body = MAPPER.readTree("{\"data\": \"hello\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/test",
                    "POST",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(TestMessages.parseBody(result.message().body())
                            .get("result")
                            .asText())
                    .isEqualTo("hello");
        }
    }

    @Nested
    @DisplayName("Mapper block validation")
    class MapperBlockValidation {

        @Test
        @DisplayName("mapper without expr → SpecParseException at load time")
        void mapperWithoutExpr_throwsSpecParseException() throws Exception {
            Path specPath = createTempSpec("""
                    id: mapper-no-expr
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    mappers:
                      bad-mapper:
                        lang: jslt
                    transform:
                      lang: jslt
                      expr: .
                    """);

            assertThatThrownBy(() -> engine.loadSpec(specPath)).isInstanceOf(SpecParseException.class);
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
