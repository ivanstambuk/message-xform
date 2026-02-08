package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.error.ProfileResolveException;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for TransformEngine with profile-based routing
 * (T-001-30, API-001-01/02, FR-001-05, FR-001-06).
 */
@DisplayName("ProfileIntegrationTest")
class ProfileIntegrationTest {

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

    @Nested
    @DisplayName("Profile-routed transform")
    class ProfileRouted {

        @Test
        @DisplayName("load spec + profile → matching request → transform applied")
        void matchingRequestTransformed() throws IOException {
            // Load the spec
            engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-simple-rename.yaml"));

            // Create a profile that references the loaded spec
            Path profilePath = tempDir.resolve("test-profile.yaml");
            Files.writeString(profilePath, """
                    profile: test-integration
                    version: "1.0.0"
                    transforms:
                      - spec: simple-rename@1.0.0
                        direction: response
                        match:
                          path: "/api/users"
                          method: POST
                    """);

            engine.loadProfile(profilePath);

            // Build a matching message
            JsonNode inputBody = JSON.readTree("""
                    {
                      "user_id": "u123",
                      "first_name": "John",
                      "last_name": "Doe",
                      "email_address": "john@example.com",
                      "phone_number": "+1234567890",
                      "is_active": true
                    }
                    """);
            Message message = new Message(
                    inputBody,
                    Map.of("content-type", "application/json"),
                    Map.of(),
                    200,
                    "application/json",
                    "/api/users",
                    "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            JsonNode body = result.message().body();
            assertThat(body.get("userId").asText()).isEqualTo("u123");
            assertThat(body.get("displayName").asText()).isEqualTo("John Doe");
            assertThat(body.get("contact").get("email").asText()).isEqualTo("john@example.com");
        }

        @Test
        @DisplayName("non-matching request → passthrough")
        void nonMatchingRequestPassthrough() throws IOException {
            engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-simple-rename.yaml"));

            Path profilePath = tempDir.resolve("test-profile.yaml");
            Files.writeString(profilePath, """
                    profile: test-passthrough
                    version: "1.0.0"
                    transforms:
                      - spec: simple-rename@1.0.0
                        direction: response
                        match:
                          path: "/api/users"
                          method: POST
                    """);

            engine.loadProfile(profilePath);

            // Build a message with a different path
            JsonNode inputBody = JSON.readTree("{\"key\": \"value\"}");
            Message message = new Message(
                    inputBody, Map.of(), Map.of(), 200, "application/json", "/completely/different/path", "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isPassthrough())
                    .as("non-matching request should passthrough")
                    .isTrue();
        }

        @Test
        @DisplayName("direction mismatch → passthrough")
        void directionMismatchPassthrough() throws IOException {
            engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-simple-rename.yaml"));

            Path profilePath = tempDir.resolve("test-profile.yaml");
            Files.writeString(profilePath, """
                    profile: test-direction
                    version: "1.0.0"
                    transforms:
                      - spec: simple-rename@1.0.0
                        direction: response
                        match:
                          path: "/api/users"
                          method: POST
                    """);

            engine.loadProfile(profilePath);

            JsonNode inputBody = JSON.readTree("{\"key\": \"value\"}");
            Message message =
                    new Message(inputBody, Map.of(), Map.of(), null, "application/json", "/api/users", "POST");

            // Request direction shouldn't match a response-only entry
            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isPassthrough()).isTrue();
        }

        @Test
        @DisplayName("most-specific-wins with multiple entries")
        void mostSpecificWins() throws IOException {
            engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-simple-rename.yaml"));
            engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-conditional.yaml"));

            // Profile with exact and glob entries
            Path profilePath = tempDir.resolve("test-specificity.yaml");
            Files.writeString(profilePath, """
                    profile: test-specificity
                    version: "1.0.0"
                    transforms:
                      - spec: conditional-response@1.0.0
                        direction: response
                        match:
                          path: "/json/*/authenticate"
                          method: POST
                      - spec: simple-rename@1.0.0
                        direction: response
                        match:
                          path: "/json/alpha/authenticate"
                          method: POST
                    """);

            engine.loadProfile(profilePath);

            // Message matching the exact path — should use simple-rename (more specific)
            JsonNode inputBody = JSON.readTree("""
                    {
                      "user_id": "u123",
                      "first_name": "John",
                      "last_name": "Doe",
                      "email_address": "john@example.com",
                      "is_active": true
                    }
                    """);
            Message message = new Message(
                    inputBody, Map.of(), Map.of(), 200, "application/json", "/json/alpha/authenticate", "POST");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            // simple-rename produces userId, displayName, contact
            assertThat(result.message().body().has("userId"))
                    .as("most-specific match (simple-rename) should be used")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Profile loading errors")
    class ProfileErrors {

        @Test
        @DisplayName("profile referencing unloaded spec → ProfileResolveException")
        void unloadedSpecReference() throws IOException {
            // Do NOT load any specs
            Path profilePath = tempDir.resolve("bad-ref.yaml");
            Files.writeString(profilePath, """
                    profile: test-bad-ref
                    version: "1.0.0"
                    transforms:
                      - spec: nonexistent@1.0.0
                        direction: response
                        match:
                          path: "/test"
                    """);

            assertThatThrownBy(() -> engine.loadProfile(profilePath))
                    .isInstanceOf(ProfileResolveException.class)
                    .hasMessageContaining("nonexistent@1.0.0");
        }
    }

    @Nested
    @DisplayName("Phase 4 backward compatibility")
    class Phase4Compat {

        @Test
        @DisplayName("no profile loaded → single-spec mode still works")
        void noProfileFallback() throws IOException {
            engine.loadSpec(Path.of("src/test/resources/test-vectors/jslt-simple-rename.yaml"));

            // No profile loaded — should use Phase 4 single-spec behaviour
            JsonNode inputBody = JSON.readTree("""
                    {
                      "user_id": "u123",
                      "first_name": "John",
                      "last_name": "Doe",
                      "email_address": "john@example.com",
                      "is_active": true
                    }
                    """);
            Message message = new Message(inputBody, Map.of(), Map.of(), 200, "application/json", "/any/path", "GET");

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.message().body().get("userId").asText()).isEqualTo("u123");
        }
    }
}
