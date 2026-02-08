package io.messagexform.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.SchemaValidationException;
import io.messagexform.core.error.SpecParseException;
import io.messagexform.core.model.TransformSpec;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * T-001-18: JSON Schema load-time validation tests (FR-001-09).
 *
 * <p>
 * Verifies that the SpecParser enforces mandatory input/output schemas
 * and validates them against JSON Schema 2020-12 at load time.
 */
@DisplayName("JSON Schema load-time validation (FR-001-09)")
class SchemaValidationTest {

    private static final Path VECTORS_DIR = Path.of("src/test/resources/test-vectors");
    private static final Path INVALID_DIR = VECTORS_DIR.resolve("invalid");

    private SpecParser parser;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        parser = new SpecParser(registry);
    }

    @Nested
    @DisplayName("Valid specs with schemas")
    class ValidSpecs {

        @Test
        @DisplayName("Spec with valid input and output schemas loads without error")
        void specWithValidSchemas_loadsSuccessfully() {
            TransformSpec spec = parser.parse(VECTORS_DIR.resolve("jslt-simple-rename.yaml"));

            assertThat(spec.id()).isEqualTo("simple-rename");
            assertThat(spec.inputSchema()).isNotNull();
            assertThat(spec.outputSchema()).isNotNull();
            // Verify schemas are valid JSON Schema objects (they have "type" and/or
            // "properties")
            assertThat(spec.inputSchema().has("type")).isTrue();
            assertThat(spec.outputSchema().has("type")).isTrue();
        }

        @Test
        @DisplayName("Bidirectional spec with valid schemas loads without error")
        void bidirectionalSpecWithSchemas_loadsSuccessfully() {
            TransformSpec spec = parser.parse(VECTORS_DIR.resolve("jslt-bidirectional-roundtrip.yaml"));

            assertThat(spec.id()).isEqualTo("bidirectional-roundtrip");
            assertThat(spec.inputSchema()).isNotNull();
            assertThat(spec.outputSchema()).isNotNull();
            assertThat(spec.isBidirectional()).isTrue();
        }
    }

    @Nested
    @DisplayName("Missing schemas")
    class MissingSchemas {

        @Test
        @DisplayName("Spec with no schema blocks throws SpecParseException")
        void specWithNoSchemas_throwsSpecParseException() {
            Path path = INVALID_DIR.resolve("no-schemas.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SpecParseException.class)
                    .satisfies(e -> {
                        SpecParseException spe = (SpecParseException) e;
                        assertThat(spe.specId()).isEqualTo("no-schemas");
                        assertThat(spe.source()).contains("no-schemas.yaml");
                    })
                    .hasMessageContaining("input.schema")
                    .hasMessageContaining("required");
        }
    }

    @Nested
    @DisplayName("Invalid schemas")
    class InvalidSchemas {

        @Test
        @DisplayName("Spec with invalid JSON Schema (bad type) throws SchemaValidationException")
        void specWithInvalidSchema_throwsSchemaValidationException() {
            Path path = INVALID_DIR.resolve("invalid-schema.yaml");

            assertThatThrownBy(() -> parser.parse(path))
                    .isInstanceOf(SchemaValidationException.class)
                    .satisfies(e -> {
                        SchemaValidationException sve = (SchemaValidationException) e;
                        assertThat(sve.specId()).isEqualTo("invalid-schema");
                        assertThat(sve.source()).contains("invalid-schema.yaml");
                    })
                    .hasMessageContaining("input.schema");
        }
    }
}
