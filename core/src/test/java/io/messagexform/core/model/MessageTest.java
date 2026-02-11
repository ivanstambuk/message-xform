package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.messagexform.core.testkit.TestMessages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link Message} (DO-001-01, FR-001-04). */
class MessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void constructWithAllFields() throws Exception {
        var body = MAPPER.readTree("{\"key\": \"value\"}");
        var headers = Map.of("content-type", "application/json", "x-request-id", "abc-123");
        var headersAll = Map.of("accept", List.of("application/json", "text/html"));

        var msg = new Message(
                TestMessages.toBody(body, "application/json"),
                TestMessages.toHeaders(headers, headersAll),
                200,
                "/api/v1/users",
                "POST",
                null,
                SessionContext.empty());

        assertThat(TestMessages.parseBody(msg.body()).get("key").asText()).isEqualTo("value");
        assertThat(msg.headers().toSingleValueMap()).containsEntry("content-type", "application/json");
        assertThat(msg.headers().toSingleValueMap()).containsEntry("x-request-id", "abc-123");
        assertThat(msg.headers().toMultiValueMap().get("accept")).containsExactly("application/json", "text/html");
        assertThat(msg.statusCode()).isEqualTo(200);
        assertThat(msg.contentType()).isEqualTo("application/json");
        assertThat(msg.requestPath()).isEqualTo("/api/v1/users");
        assertThat(msg.requestMethod()).isEqualTo("POST");
    }

    @Test
    void nullBodyThrowsNullPointerException() {
        assertThatThrownBy(() -> new Message(null, HttpHeaders.empty(), null, null, null, null, SessionContext.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("body must not be null");
    }

    @Test
    void nullHeadersDefaultToEmptyMaps() {
        var msg = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                HttpHeaders.empty(),
                null,
                null,
                null,
                null,
                SessionContext.empty());

        assertThat(msg.headers().toSingleValueMap()).isEmpty();
        assertThat(msg.headers().toMultiValueMap()).isEmpty();
    }

    @Test
    void headersAreUnmodifiable() {
        var headers = new java.util.HashMap<String, String>();
        headers.put("x-test", "value");
        var msg = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                TestMessages.toHeaders(headers, null),
                null,
                null,
                null,
                null,
                SessionContext.empty());

        assertThatThrownBy(() -> msg.headers().toSingleValueMap().put("x-new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void headersAllAreUnmodifiable() {
        var headersAll = new java.util.HashMap<String, List<String>>();
        headersAll.put("accept", List.of("text/html"));
        var msg = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                TestMessages.toHeaders(null, headersAll),
                null,
                null,
                null,
                null,
                SessionContext.empty());

        assertThatThrownBy(() -> msg.headers().toMultiValueMap().put("x-new", List.of("val")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void statusCodeAndPathCanBeNull() {
        var msg = new Message(
                TestMessages.toBody(NullNode.getInstance(), null),
                HttpHeaders.empty(),
                null,
                null,
                null,
                null,
                SessionContext.empty());

        assertThat(msg.statusCode()).isNull();
        assertThat(msg.requestPath()).isNull();
        assertThat(msg.requestMethod()).isNull();
        assertThat(msg.contentType()).isNull();
    }
}
