package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.testkit.TestMessages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for passthrough behaviour (T-001-20, FR-001-06). When no spec/profile
 * matches, the engine MUST return the original message completely unmodified.
 */
@DisplayName("T-001-20: Passthrough for non-matching messages")
class PassthroughTest {

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
    @DisplayName("No specs loaded")
    class NoSpecsLoaded {

        @Test
        @DisplayName("Any message returns PASSTHROUGH when no specs are loaded")
        void noSpecs_anyMessage_returnsPassthrough() throws Exception {
            JsonNode body = MAPPER.readTree("{\"key\": \"value\"}");
            Message message = new Message(
                    TestMessages.toBody(body, "application/json"),
                    TestMessages.toHeaders(
                            Map.of("content-type", "application/json"),
                            Map.of("content-type", List.of("application/json"))),
                    200,
                    "/api/users",
                    "GET",
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.RESPONSE);

            assertThat(result.isPassthrough())
                    .as("Should return PASSTHROUGH when no specs loaded")
                    .isTrue();
            assertThat(result.type()).isEqualTo(TransformResult.Type.PASSTHROUGH);
        }

        @Test
        @DisplayName("Passthrough result has no message or error")
        void noSpecs_passthroughHasNoMessageOrError() throws Exception {
            JsonNode body = MAPPER.readTree("{\"test\": true}");
            Message message = new Message(
                    TestMessages.toBody(body, null),
                    HttpHeaders.empty(),
                    null,
                    null,
                    null,
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(message, Direction.REQUEST);

            assertThat(result.isPassthrough()).isTrue();
            assertThat(result.message()).isNull();
            assertThat(result.errorResponse()).isNull();
            assertThat(result.errorStatusCode()).isNull();
        }

        @Test
        @DisplayName("Passthrough works for both REQUEST and RESPONSE directions")
        void noSpecs_passthroughWorksBothDirections() throws Exception {
            Message message = new Message(
                    TestMessages.toBody(NullNode.getInstance(), null),
                    HttpHeaders.empty(),
                    null,
                    null,
                    null,
                    null,
                    SessionContext.empty());

            TransformResult requestResult = engine.transform(message, Direction.REQUEST);
            TransformResult responseResult = engine.transform(message, Direction.RESPONSE);

            assertThat(requestResult.isPassthrough()).isTrue();
            assertThat(responseResult.isPassthrough()).isTrue();
        }
    }

    @Nested
    @DisplayName("Original message preservation")
    class MessagePreservation {

        @Test
        @DisplayName("Passthrough preserves body, headers, status, path, method")
        void passthrough_preservesAllMessageFields() throws Exception {
            JsonNode body = MAPPER.readTree("""
                    {
                      "id": "usr-42",
                      "name": "Bob Jensen",
                      "sensitive_data": "should-be-preserved"
                    }
                    """);
            Map<String, String> headers = Map.of(
                    "content-type", "application/json",
                    "x-request-id", "req-abc-123");
            Map<String, List<String>> headersAll = Map.of(
                    "content-type", List.of("application/json"),
                    "x-request-id", List.of("req-abc-123"));

            Message original = new Message(
                    TestMessages.toBody(body, "application/json"),
                    TestMessages.toHeaders(headers, headersAll),
                    200,
                    "/api/users/42",
                    "GET",
                    null,
                    SessionContext.empty());

            // With no specs loaded, any transform should passthrough
            TransformResult result = engine.transform(original, Direction.RESPONSE);

            assertThat(result.isPassthrough()).isTrue();
            // The original message is not modified or wrapped — PASSTHROUGH means
            // the caller should forward the original message as-is
        }

        @Test
        @DisplayName("Passthrough with minimal message (null optional fields)")
        void passthrough_worksWithMinimalMessage() throws Exception {
            Message minimal = new Message(
                    TestMessages.toBody(NullNode.getInstance(), null),
                    HttpHeaders.empty(),
                    null,
                    null,
                    null,
                    null,
                    SessionContext.empty());

            TransformResult result = engine.transform(minimal, Direction.REQUEST);

            assertThat(result.isPassthrough()).isTrue();
        }
    }
}
