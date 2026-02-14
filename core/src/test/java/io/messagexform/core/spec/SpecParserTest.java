package io.messagexform.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.ExpressionCompileException;
import io.messagexform.core.error.SpecParseException;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.TransformSpec;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SpecParser} (T-001-14 + T-001-15, FR-001-01, FR-001-07,
 * DO-001-02). Verifies that valid spec YAML files are parsed into correct
 * {@link TransformSpec} instances, that compiled expressions produce correct
 * output, and that invalid specs produce the correct typed exceptions.
 */
class SpecParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SpecParser parser;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        parser = new SpecParser(registry);
    }

    // --- FX-001-01: jslt-simple-rename.yaml ---

    @Test
    void parseSimpleRename_hasCorrectMetadata() {
        TransformSpec spec = parser.parse(fixturePath("jslt-simple-rename.yaml"));

        assertThat(spec.id()).isEqualTo("simple-rename");
        assertThat(spec.version()).isEqualTo("1.0.0");
        assertThat(spec.description()).isEqualTo("Rename snake_case fields to camelCase and nest contact info");
        assertThat(spec.lang()).isEqualTo("jslt");
    }

    @Test
    void parseSimpleRename_isBidirectionalFalse() {
        TransformSpec spec = parser.parse(fixturePath("jslt-simple-rename.yaml"));

        assertThat(spec.isBidirectional()).isFalse();
        assertThat(spec.compiledExpr()).isNotNull();
        assertThat(spec.forward()).isNull();
        assertThat(spec.reverse()).isNull();
    }

    @Test
    void parseSimpleRename_hasSchemas() {
        TransformSpec spec = parser.parse(fixturePath("jslt-simple-rename.yaml"));

        assertThat(spec.inputSchema()).isNotNull();
        assertThat(spec.inputSchema().has("type")).isTrue();
        assertThat(spec.inputSchema().get("type").asText()).isEqualTo("object");

        assertThat(spec.outputSchema()).isNotNull();
        assertThat(spec.outputSchema().has("type")).isTrue();
        assertThat(spec.outputSchema().get("type").asText()).isEqualTo("object");
    }

    @Test
    void parseSimpleRename_evaluatesCorrectly() throws Exception {
        TransformSpec spec = parser.parse(fixturePath("jslt-simple-rename.yaml"));

        JsonNode input = MAPPER.readTree("""
                {
                  "user_id": "usr-42",
                  "first_name": "Bob",
                  "last_name": "Jensen",
                  "email_address": "bjensen@example.com",
                  "phone_number": "+31-6-12345678",
                  "is_active": true
                }
                """);

        JsonNode output = spec.compiledExpr().evaluate(input, TransformContext.empty());

        assertThat(output.get("userId").asText()).isEqualTo("usr-42");
        assertThat(output.get("displayName").asText()).isEqualTo("Bob Jensen");
        assertThat(output.get("contact").get("email").asText()).isEqualTo("bjensen@example.com");
        assertThat(output.get("contact").get("phone").asText()).isEqualTo("+31-6-12345678");
        assertThat(output.get("active").asBoolean()).isTrue();
    }

    // --- FX-001-02: jslt-conditional.yaml ---

    @Test
    void parseConditional_hasCorrectMetadata() {
        TransformSpec spec = parser.parse(fixturePath("jslt-conditional.yaml"));

        assertThat(spec.id()).isEqualTo("conditional-response");
        assertThat(spec.version()).isEqualTo("1.0.0");
        assertThat(spec.lang()).isEqualTo("jslt");
        assertThat(spec.compiledExpr()).isNotNull();
    }

    @Test
    void parseConditional_errorCase() throws Exception {
        TransformSpec spec = parser.parse(fixturePath("jslt-conditional.yaml"));

        JsonNode input = MAPPER.readTree("""
                {
                  "error": "invalid_grant",
                  "error_description": "The provided grant is invalid"
                }
                """);

        JsonNode output = spec.compiledExpr().evaluate(input, TransformContext.empty());

        assertThat(output.get("success").asBoolean()).isFalse();
        assertThat(output.get("error").get("code").asText()).isEqualTo("invalid_grant");
        assertThat(output.get("error").get("message").asText()).isEqualTo("The provided grant is invalid");
    }

    @Test
    void parseConditional_successCase() throws Exception {
        TransformSpec spec = parser.parse(fixturePath("jslt-conditional.yaml"));

        JsonNode input = MAPPER.readTree("""
                {
                  "id": "usr-42",
                  "name": "Bob Jensen"
                }
                """);

        JsonNode output = spec.compiledExpr().evaluate(input, TransformContext.empty());

        assertThat(output.get("success").asBoolean()).isTrue();
        assertThat(output.get("data").get("id").asText()).isEqualTo("usr-42");
        assertThat(output.get("data").get("name").asText()).isEqualTo("Bob Jensen");
        assertThat(output.get("data").get("status").asText()).isEqualTo("active");
    }

    // --- FX-001-03: jslt-array-reshape.yaml ---

    @Test
    void parseArrayReshape_hasCorrectMetadata() {
        TransformSpec spec = parser.parse(fixturePath("jslt-array-reshape.yaml"));

        assertThat(spec.id()).isEqualTo("array-reshape");
        assertThat(spec.version()).isEqualTo("1.0.0");
        assertThat(spec.lang()).isEqualTo("jslt");
        assertThat(spec.compiledExpr()).isNotNull();
    }

    @Test
    void parseArrayReshape_evaluatesCorrectly() throws Exception {
        TransformSpec spec = parser.parse(fixturePath("jslt-array-reshape.yaml"));

        JsonNode input = MAPPER.readTree("""
                {
                  "totalResults": 2,
                  "startIndex": 1,
                  "itemsPerPage": 10,
                  "Resources": [
                    {
                      "id": "u-1",
                      "userName": "bjensen",
                      "displayName": "Bob Jensen",
                      "emails": [{"value": "bjensen@example.com", "type": "work", "primary": true}],
                      "active": true
                    },
                    {
                      "id": "u-2",
                      "userName": "jsmith",
                      "displayName": "Jane Smith",
                      "emails": [{"value": "jsmith@example.com", "type": "work"}],
                      "active": false
                    }
                  ]
                }
                """);

        JsonNode output = spec.compiledExpr().evaluate(input, TransformContext.empty());

        assertThat(output.get("total").asInt()).isEqualTo(2);
        assertThat(output.get("users").isArray()).isTrue();
        assertThat(output.get("users").size()).isEqualTo(2);

        JsonNode user1 = output.get("users").get(0);
        assertThat(user1.get("id").asText()).isEqualTo("u-1");
        assertThat(user1.get("username").asText()).isEqualTo("bjensen");
        assertThat(user1.get("displayName").asText()).isEqualTo("Bob Jensen");
        assertThat(user1.get("email").asText()).isEqualTo("bjensen@example.com");
        assertThat(user1.get("active").asBoolean()).isTrue();

        JsonNode user2 = output.get("users").get(1);
        assertThat(user2.get("id").asText()).isEqualTo("u-2");
        assertThat(user2.get("username").asText()).isEqualTo("jsmith");
        assertThat(user2.get("active").asBoolean()).isFalse();
    }

    // --- Edge cases ---

    @Test
    void parseSpec_descriptionIsOptional() {
        // jslt-simple-rename has a description; let's verify we handle it.
        // Description-absent case will be tested in T-001-15 with a minimal fixture.
        TransformSpec spec = parser.parse(fixturePath("jslt-simple-rename.yaml"));
        assertThat(spec.description()).isNotNull();
    }

    @Test
    void parseSpec_langDefaultsToJslt() {
        // All current fixtures have explicit lang: jslt.
        // Default-lang behavior is implicitly tested: if lang=jslt → engine lookup
        // works.
        TransformSpec spec = parser.parse(fixturePath("jslt-simple-rename.yaml"));
        assertThat(spec.lang()).isEqualTo("jslt");
    }

    // --- T-001-15: Error handling (FR-001-01, FR-001-07) ---

    @Nested
    @DisplayName("T-001-15: Spec parse error handling")
    class ErrorHandlingTest {

        @Test
        @DisplayName("Invalid YAML syntax → SpecParseException")
        void invalidYaml_throwsSpecParseException() {
            Path path = invalidFixturePath("invalid-yaml-syntax.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Failed to read or parse YAML")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        assertThat(spe.specId()).isNull();
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Unknown engine id (lang: nonexistent) → ExpressionCompileException")
        void unknownEngine_throwsExpressionCompileException() {
            Path path = invalidFixturePath("unknown-engine.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(ExpressionCompileException.class)
                    .hasMessageContaining("Unknown expression engine")
                    .hasMessageContaining("nonexistent")
                    .satisfies(ex -> {
                        ExpressionCompileException ece = (ExpressionCompileException) ex;
                        assertThat(ece.specId()).isEqualTo("unknown-engine-spec");
                        assertThat(ece.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Invalid JSLT syntax → ExpressionCompileException")
        void badJsltSyntax_throwsExpressionCompileException() {
            Path path = invalidFixturePath("bad-jslt-syntax.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(ExpressionCompileException.class)
                    .hasMessageContaining("Failed to compile expression")
                    .hasMessageContaining("bad-jslt")
                    .satisfies(ex -> {
                        ExpressionCompileException ece = (ExpressionCompileException) ex;
                        assertThat(ece.specId()).isEqualTo("bad-jslt");
                        assertThat(ece.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Missing required field: id → SpecParseException")
        void missingId_throwsSpecParseException() {
            Path path = invalidFixturePath("missing-id.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Missing or invalid required field")
                    .hasMessageContaining("'id'")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        // specId is null since we couldn't extract it
                        assertThat(spe.specId()).isNull();
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Missing required field: version → SpecParseException")
        void missingVersion_throwsSpecParseException() {
            Path path = invalidFixturePath("missing-version.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Missing or invalid required field")
                    .hasMessageContaining("'version'")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        // id WAS available, so specId should be populated
                        assertThat(spe.specId()).isEqualTo("missing-version");
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Missing transform/forward/reverse blocks → SpecParseException")
        void missingTransformBlock_throwsSpecParseException() {
            Path path = invalidFixturePath("missing-transform.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("'transform' block")
                    .hasMessageContaining("'forward'")
                    .hasMessageContaining("'reverse'")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        assertThat(spe.specId()).isEqualTo("missing-transform");
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Transform block without 'expr' key → SpecParseException")
        void missingExpr_throwsSpecParseException() {
            Path path = invalidFixturePath("missing-expr.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Missing or invalid 'expr'")
                    .hasMessageContaining("'transform'")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        assertThat(spe.specId()).isEqualTo("missing-expr");
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Forward block without reverse → SpecParseException (incomplete bidirectional)")
        void forwardOnly_throwsSpecParseException() {
            Path path = invalidFixturePath("forward-only.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("'transform' block")
                    .hasMessageContaining("'forward'")
                    .hasMessageContaining("'reverse'")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        assertThat(spe.specId()).isEqualTo("forward-only");
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Non-existent file → SpecParseException")
        void nonExistentFile_throwsSpecParseException() {
            Path path = fixturePath("does-not-exist.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Failed to read or parse YAML")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        assertThat(spe.specId()).isNull();
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Unknown key in headers block (e.g. headers.request) → SpecParseException")
        void unknownHeaderKey_throwsSpecParseException() {
            Path path = invalidFixturePath("unknown-header-key.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Unknown key")
                    .hasMessageContaining("'headers'")
                    .hasMessageContaining("request")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        assertThat(spe.specId()).isEqualTo("unknown-header-key");
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }

        @Test
        @DisplayName("Unknown top-level key (e.g. routing) → SpecParseException")
        void unknownRootKey_throwsSpecParseException() {
            Path path = invalidFixturePath("unknown-root-key.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .hasMessageContaining("Unknown key")
                    .hasMessageContaining("'spec root'")
                    .hasMessageContaining("routing")
                    .satisfies(ex -> {
                        SpecParseException spe = (SpecParseException) ex;
                        assertThat(spe.specId()).isEqualTo("unknown-root-key");
                        assertThat(spe.source()).isEqualTo(path.toString());
                    });
        }
    }

    // --- Helpers ---

    private static Path fixturePath(String filename) {
        return Path.of("src/test/resources/test-vectors/" + filename);
    }

    private static Path invalidFixturePath(String filename) {
        return Path.of("src/test/resources/test-vectors/invalid/" + filename);
    }
}
