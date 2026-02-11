package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for bidirectional transform via profiles
 * (T-001-32, FR-001-03, S-001-02).
 *
 * Profile entries with direction: response use forward.expr;
 * entries with direction: request use reverse.expr.
 */
@DisplayName("BidirectionalProfileTest")
class BidirectionalProfileTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TransformEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Test
    @DisplayName("response direction → forward.expr applied")
    void responseUsesForward() throws IOException {
        engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-bidirectional-roundtrip.yaml"));

        Path profilePath = tempDir.resolve("bidi-response.yaml");
        Files.writeString(profilePath, """
                profile: bidi-test
                version: "1.0.0"
                transforms:
                  - spec: bidirectional-roundtrip@1.0.0
                    direction: response
                    match:
                      path: "/api/users"
                      method: GET
                """);

        engine.loadProfile(profilePath);

        // DB record format (snake_case, flat)
        JsonNode inputBody = JSON.readTree("""
                {
                  "user_id": "u456",
                  "email_address": "jane@example.com",
                  "phone_number": "+31612345678",
                  "is_active": true
                }
                """);
        Message message = new Message(
                TestMessages.toBody(inputBody, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/users",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        JsonNode body = TestMessages.parseBody(result.message().body());
        // forward.expr: snake_case → camelCase with nested contact
        assertThat(body.get("userId").asText()).isEqualTo("u456");
        assertThat(body.get("contact").get("email").asText()).isEqualTo("jane@example.com");
        assertThat(body.get("contact").get("phone").asText()).isEqualTo("+31612345678");
        assertThat(body.get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("request direction → reverse.expr applied")
    void requestUsesReverse() throws IOException {
        engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-bidirectional-roundtrip.yaml"));

        Path profilePath = tempDir.resolve("bidi-request.yaml");
        Files.writeString(profilePath, """
                profile: bidi-test
                version: "1.0.0"
                transforms:
                  - spec: bidirectional-roundtrip@1.0.0
                    direction: request
                    match:
                      path: "/api/users"
                      method: POST
                """);

        engine.loadProfile(profilePath);

        // API format (camelCase, nested contact)
        JsonNode inputBody = JSON.readTree("""
                {
                  "userId": "u456",
                  "contact": {
                    "email": "jane@example.com",
                    "phone": "+31612345678"
                  },
                  "active": true
                }
                """);
        Message message = new Message(
                TestMessages.toBody(inputBody, "application/json"),
                HttpHeaders.empty(),
                null,
                "/api/users",
                "POST",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.REQUEST);

        assertThat(result.isSuccess()).isTrue();
        JsonNode body = TestMessages.parseBody(result.message().body());
        // reverse.expr: camelCase nested → snake_case flat
        assertThat(body.get("user_id").asText()).isEqualTo("u456");
        assertThat(body.get("email_address").asText()).isEqualTo("jane@example.com");
        assertThat(body.get("phone_number").asText()).isEqualTo("+31612345678");
        assertThat(body.get("is_active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("round-trip: forward(input) → reverse(output) ≈ original")
    void roundTrip() throws IOException {
        engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-bidirectional-roundtrip.yaml"));

        // Profile with both response and request entries
        Path profilePath = tempDir.resolve("bidi-roundtrip.yaml");
        Files.writeString(profilePath, """
                profile: bidi-roundtrip
                version: "1.0.0"
                transforms:
                  - spec: bidirectional-roundtrip@1.0.0
                    direction: response
                    match:
                      path: "/api/users"
                  - spec: bidirectional-roundtrip@1.0.0
                    direction: request
                    match:
                      path: "/api/users"
                """);

        engine.loadProfile(profilePath);

        // Original DB record
        JsonNode original = JSON.readTree("""
                {
                  "user_id": "u789",
                  "email_address": "roundtrip@example.com",
                  "phone_number": "+1234",
                  "is_active": false
                }
                """);

        // Step 1: Forward (response direction) — DB → API
        Message responseMsg = new Message(
                TestMessages.toBody(original, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/users",
                "GET",
                null,
                SessionContext.empty());
        TransformResult forwardResult = engine.transform(responseMsg, Direction.RESPONSE);

        assertThat(forwardResult.isSuccess()).isTrue();
        JsonNode apiFormat = TestMessages.parseBody(forwardResult.message().body());
        assertThat(apiFormat.get("userId").asText()).isEqualTo("u789");

        // Step 2: Reverse (request direction) — API → DB
        Message requestMsg = new Message(
                TestMessages.toBody(apiFormat, "application/json"),
                HttpHeaders.empty(),
                null,
                "/api/users",
                "POST",
                null,
                SessionContext.empty());
        TransformResult reverseResult = engine.transform(requestMsg, Direction.REQUEST);

        assertThat(reverseResult.isSuccess()).isTrue();
        JsonNode roundTripped = TestMessages.parseBody(reverseResult.message().body());

        // Round-trip should recover the original fields
        assertThat(roundTripped.get("user_id").asText()).isEqualTo("u789");
        assertThat(roundTripped.get("email_address").asText()).isEqualTo("roundtrip@example.com");
        assertThat(roundTripped.get("phone_number").asText()).isEqualTo("+1234");
        assertThat(roundTripped.get("is_active").asBoolean()).isFalse();
    }
}
