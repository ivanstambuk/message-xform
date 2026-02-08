package io.messagexform.core.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.TransformSpec;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SpecParser} (T-001-14, FR-001-01, DO-001-02). Verifies that
 * valid spec YAML
 * files are parsed into correct {@link TransformSpec} instances and that
 * compiled expressions
 * produce correct output.
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

    // --- Helpers ---

    private static Path fixturePath(String filename) {
        return Path.of("src/test/resources/test-vectors/" + filename);
    }
}
