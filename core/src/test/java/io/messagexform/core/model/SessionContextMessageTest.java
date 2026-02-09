package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

/**
 * Tests for session context on {@link Message} (T-001-54, FR-001-13, ADR-0030).
 *
 * <p>
 * Verifies that the Message record carries an optional session context
 * (JsonNode) that defaults to null when not supplied.
 */
class SessionContextMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void messageWithoutSessionContext_returnsNull() {
        // 7-arg backward-compatible constructor — no sessionContext
        var msg = new Message(NullNode.getInstance(), null, null, null, null, null, null);

        assertThat(msg.sessionContext())
                .as("Session context must default to null when not provided")
                .isNull();
    }

    @Test
    void messageWithoutSessionContext_8argConstructor_returnsNull() {
        // 8-arg constructor (body, headers, headersAll, statusCode, contentType, path,
        // method, queryString)
        var msg = new Message(NullNode.getInstance(), null, null, null, null, null, null, null);

        assertThat(msg.sessionContext())
                .as("Session context must default to null in 8-arg constructor")
                .isNull();
    }

    @Test
    void messageWithSessionContext_returnsProvidedJsonNode() throws Exception {
        JsonNode session = MAPPER.readTree("{\"sub\": \"bjensen\", \"roles\": [\"admin\", \"user\"]}");

        // Full 9-arg canonical constructor
        var msg = new Message(NullNode.getInstance(), null, null, null, null, null, null, null, session);

        assertThat(msg.sessionContext()).isNotNull();
        assertThat(msg.sessionContext().get("sub").asText()).isEqualTo("bjensen");
        assertThat(msg.sessionContext().get("roles").isArray()).isTrue();
        assertThat(msg.sessionContext().get("roles").get(0).asText()).isEqualTo("admin");
    }

    @Test
    void messagePreservesSessionContextThroughEnvelopeFields() throws Exception {
        JsonNode body = MAPPER.readTree("{\"key\": \"value\"}");
        JsonNode session = MAPPER.readTree("{\"tenant\": \"acme\"}");

        var msg = new Message(body, null, null, 200, "application/json", "/api/test", "GET", "page=1", session);

        // Verify all envelope fields are preserved alongside session context
        assertThat(msg.body().get("key").asText()).isEqualTo("value");
        assertThat(msg.statusCode()).isEqualTo(200);
        assertThat(msg.requestPath()).isEqualTo("/api/test");
        assertThat(msg.queryString()).isEqualTo("page=1");
        assertThat(msg.sessionContext().get("tenant").asText()).isEqualTo("acme");
    }

    @Test
    void nullSessionContextIsDistinctFromNullNodeSessionContext() {
        // null sessionContext = "no session context available"
        var msgNoSession = new Message(NullNode.getInstance(), null, null, null, null, null, null, null, null);
        assertThat(msgNoSession.sessionContext()).isNull();

        // NullNode sessionContext = "session context is explicitly JSON null"
        var msgNullSession =
                new Message(NullNode.getInstance(), null, null, null, null, null, null, null, NullNode.getInstance());
        assertThat(msgNullSession.sessionContext()).isNotNull();
        assertThat(msgNullSession.sessionContext().isNull()).isTrue();
    }
}
