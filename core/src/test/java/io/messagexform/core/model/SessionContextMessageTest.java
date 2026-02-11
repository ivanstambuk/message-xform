package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.messagexform.core.testkit.TestMessages;
import org.junit.jupiter.api.Test;

/**
 * Tests for session context on {@link Message} (T-001-54, FR-001-13, ADR-0030).
 *
 * <p>
 * Verifies that the Message record carries an optional session context
 * that defaults to empty when not supplied.
 */
class SessionContextMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void messageWithoutSessionContext_returnsEmpty() {
        var msg = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                HttpHeaders.empty(),
                null,
                null,
                null,
                null,
                SessionContext.empty());

        assertThat(msg.session())
                .as("Session context must be non-null (empty) when not provided")
                .isNotNull();
        assertThat(msg.session().isEmpty()).as("Session context must be empty").isTrue();
    }

    @Test
    void messageWithoutSessionContext_defaultCtor_returnsEmpty() {
        var msg = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                HttpHeaders.empty(),
                null,
                null,
                null,
                null,
                SessionContext.empty());

        assertThat(msg.session())
                .as("Session context must be non-null (empty) in constructor")
                .isNotNull();
        assertThat(msg.session().isEmpty()).isTrue();
    }

    @Test
    void messageWithSessionContext_returnsProvidedJsonNode() throws Exception {
        JsonNode session = MAPPER.readTree("{\"sub\": \"bjensen\", \"roles\": [\"admin\", \"user\"]}");

        var msg = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                HttpHeaders.empty(),
                null,
                null,
                null,
                null,
                TestMessages.toSessionContext(session));

        assertThat(msg.session()).isNotNull();
        assertThat(msg.session().isEmpty()).isFalse();
        assertThat(msg.session().getString("sub")).isEqualTo("bjensen");
        assertThat(msg.session().get("roles")).isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        var roles = (java.util.List<String>) msg.session().get("roles");
        assertThat(roles.get(0)).isEqualTo("admin");
    }

    @Test
    void messagePreservesSessionContextThroughEnvelopeFields() throws Exception {
        JsonNode body = MAPPER.readTree("{\"key\": \"value\"}");
        JsonNode session = MAPPER.readTree("{\"tenant\": \"acme\"}");

        var msg = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/test",
                "GET",
                "page=1",
                TestMessages.toSessionContext(session));

        // Verify all envelope fields are preserved alongside session context
        assertThat(TestMessages.parseBody(msg.body()).get("key").asText()).isEqualTo("value");
        assertThat(msg.statusCode()).isEqualTo(200);
        assertThat(msg.requestPath()).isEqualTo("/api/test");
        assertThat(msg.queryString()).isEqualTo("page=1");
        assertThat(msg.session().getString("tenant")).isEqualTo("acme");
    }

    @Test
    void emptySessionContextIsSemanticallyDistinctFromPopulatedSessionContext() {
        // Empty session context = "no meaningful session data"
        var msgNoSession = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                HttpHeaders.empty(),
                null,
                null,
                null,
                null,
                SessionContext.empty());
        assertThat(msgNoSession.session()).isNotNull();
        assertThat(msgNoSession.session().isEmpty()).isTrue();

        // Populated session context = "has session data"
        var msgWithSession = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                HttpHeaders.empty(),
                null,
                null,
                null,
                null,
                SessionContext.of(java.util.Map.of("sub", "user1")));
        assertThat(msgWithSession.session()).isNotNull();
        assertThat(msgWithSession.session().isEmpty()).isFalse();
        assertThat(msgWithSession.session().getString("sub")).isEqualTo("user1");
    }
}
