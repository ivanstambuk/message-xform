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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for bidirectional spec parsing and evaluation (T-001-16 + T-001-17,
 * FR-001-03, S-001-02, DO-001-02). Verifies that specs with forward/reverse
 * blocks parse correctly and that forward ∘ reverse ≈ identity.
 */
@DisplayName("Bidirectional spec parsing (FX-001-04)")
class BidirectionalSpecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SpecParser parser;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        parser = new SpecParser(registry);
    }

    // --- Metadata ---

    @Test
    @DisplayName("Bidirectional spec has correct metadata")
    void parse_hasCorrectMetadata() {
        TransformSpec spec = parser.parse(fixturePath());

        assertThat(spec.id()).isEqualTo("bidirectional-roundtrip");
        assertThat(spec.version()).isEqualTo("1.0.0");
        assertThat(spec.description()).isEqualTo("Bidirectional user profile: DB record ↔ API response");
        assertThat(spec.lang()).isEqualTo("jslt");
    }

    @Test
    @DisplayName("Bidirectional spec has isBidirectional=true, compiledExpr=null")
    void parse_isBidirectionalTrue() {
        TransformSpec spec = parser.parse(fixturePath());

        assertThat(spec.isBidirectional()).isTrue();
        assertThat(spec.forward()).isNotNull();
        assertThat(spec.reverse()).isNotNull();
        assertThat(spec.compiledExpr()).isNull();
    }

    @Test
    @DisplayName("Bidirectional spec has schemas")
    void parse_hasSchemas() {
        TransformSpec spec = parser.parse(fixturePath());

        assertThat(spec.inputSchema()).isNotNull();
        assertThat(spec.inputSchema().get("type").asText()).isEqualTo("object");

        assertThat(spec.outputSchema()).isNotNull();
        assertThat(spec.outputSchema().get("type").asText()).isEqualTo("object");
    }

    // --- Forward evaluation ---

    @Test
    @DisplayName("Forward expr transforms DB record → API response")
    void forward_transformsCorrectly() throws Exception {
        TransformSpec spec = parser.parse(fixturePath());

        JsonNode dbRecord = MAPPER.readTree("""
                {
                  "user_id": "usr-42",
                  "email_address": "bjensen@example.com",
                  "phone_number": "+31-6-12345678",
                  "is_active": true
                }
                """);

        JsonNode apiResponse = spec.forward().evaluate(dbRecord, TransformContext.empty());

        assertThat(apiResponse.get("userId").asText()).isEqualTo("usr-42");
        assertThat(apiResponse.get("contact").get("email").asText()).isEqualTo("bjensen@example.com");
        assertThat(apiResponse.get("contact").get("phone").asText()).isEqualTo("+31-6-12345678");
        assertThat(apiResponse.get("active").asBoolean()).isTrue();
    }

    // --- Reverse evaluation ---

    @Test
    @DisplayName("Reverse expr transforms API response → DB record")
    void reverse_transformsCorrectly() throws Exception {
        TransformSpec spec = parser.parse(fixturePath());

        JsonNode apiResponse = MAPPER.readTree("""
                {
                  "userId": "usr-42",
                  "contact": {
                    "email": "bjensen@example.com",
                    "phone": "+31-6-12345678"
                  },
                  "active": true
                }
                """);

        JsonNode dbRecord = spec.reverse().evaluate(apiResponse, TransformContext.empty());

        assertThat(dbRecord.get("user_id").asText()).isEqualTo("usr-42");
        assertThat(dbRecord.get("email_address").asText()).isEqualTo("bjensen@example.com");
        assertThat(dbRecord.get("phone_number").asText()).isEqualTo("+31-6-12345678");
        assertThat(dbRecord.get("is_active").asBoolean()).isTrue();
    }

    // --- Round-trip ---

    @Test
    @DisplayName("Round-trip: reverse(forward(input)) == input")
    void roundTrip_forwardThenReverse_producesOriginal() throws Exception {
        TransformSpec spec = parser.parse(fixturePath());

        JsonNode original = MAPPER.readTree("""
                {
                  "user_id": "usr-42",
                  "email_address": "bjensen@example.com",
                  "phone_number": "+31-6-12345678",
                  "is_active": true
                }
                """);

        // Forward: DB → API
        JsonNode apiResponse = spec.forward().evaluate(original, TransformContext.empty());
        // Reverse: API → DB
        JsonNode roundTripped = spec.reverse().evaluate(apiResponse, TransformContext.empty());

        assertThat(roundTripped.get("user_id").asText())
                .isEqualTo(original.get("user_id").asText());
        assertThat(roundTripped.get("email_address").asText())
                .isEqualTo(original.get("email_address").asText());
        assertThat(roundTripped.get("phone_number").asText())
                .isEqualTo(original.get("phone_number").asText());
        assertThat(roundTripped.get("is_active").asBoolean())
                .isEqualTo(original.get("is_active").asBoolean());
    }

    @Test
    @DisplayName("Round-trip: forward(reverse(api)) == api")
    void roundTrip_reverseThenForward_producesOriginal() throws Exception {
        TransformSpec spec = parser.parse(fixturePath());

        JsonNode apiOriginal = MAPPER.readTree("""
                {
                  "userId": "usr-42",
                  "contact": {
                    "email": "bjensen@example.com",
                    "phone": "+31-6-12345678"
                  },
                  "active": true
                }
                """);

        // Reverse: API → DB
        JsonNode dbRecord = spec.reverse().evaluate(apiOriginal, TransformContext.empty());
        // Forward: DB → API
        JsonNode roundTripped = spec.forward().evaluate(dbRecord, TransformContext.empty());

        assertThat(roundTripped.get("userId").asText())
                .isEqualTo(apiOriginal.get("userId").asText());
        assertThat(roundTripped.get("contact").get("email").asText())
                .isEqualTo(apiOriginal.get("contact").get("email").asText());
        assertThat(roundTripped.get("contact").get("phone").asText())
                .isEqualTo(apiOriginal.get("contact").get("phone").asText());
        assertThat(roundTripped.get("active").asBoolean())
                .isEqualTo(apiOriginal.get("active").asBoolean());
    }

    // --- Edge cases ---

    @Test
    @DisplayName("Forward handles null optional fields gracefully")
    void forward_nullOptionalFields() throws Exception {
        TransformSpec spec = parser.parse(fixturePath());

        JsonNode minimal = MAPPER.readTree("""
                {
                  "user_id": "usr-99",
                  "email_address": "minimal@example.com"
                }
                """);

        JsonNode result = spec.forward().evaluate(minimal, TransformContext.empty());

        assertThat(result.get("userId").asText()).isEqualTo("usr-99");
        assertThat(result.get("contact").get("email").asText()).isEqualTo("minimal@example.com");
        // JSLT omits absent fields from output rather than producing JSON null.
        // phone_number and is_active are absent in input → omitted from output.
        assertThat(result.get("contact").has("phone")).isFalse();
        assertThat(result.has("active")).isFalse();
    }

    // --- Helpers ---

    private static Path fixturePath() {
        return Path.of("src/test/resources/test-vectors/jslt-bidirectional-roundtrip.yaml");
    }
}
