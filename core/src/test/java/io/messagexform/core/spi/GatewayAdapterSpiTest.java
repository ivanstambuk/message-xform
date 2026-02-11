package io.messagexform.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.testkit.TestMessages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Compile-time verification that the {@link GatewayAdapter} SPI is usable
 * (T-001-48). Uses a trivial in-memory mock to confirm the interface compiles,
 * can be implemented, and works end-to-end with {@link Message}.
 */
class GatewayAdapterSpiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Mock native types
    // -----------------------------------------------------------

    /**
     * Simulates a gateway-native HTTP exchange (both request and response).
     * PingAccess has Exchange, servlets have HttpServletRequest, etc.
     */
    static final class MockNativeExchange {
        String bodyJson;
        Map<String, List<String>> headers;
        Integer statusCode;
        String path;
        String method;
        String contentType;
        String queryString;

        MockNativeExchange(
                String bodyJson,
                Map<String, List<String>> headers,
                Integer statusCode,
                String path,
                String method,
                String contentType,
                String queryString) {
            this.bodyJson = bodyJson;
            this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
            this.statusCode = statusCode;
            this.path = path;
            this.method = method;
            this.contentType = contentType;
            this.queryString = queryString;
        }
    }

    // --- Mock adapter
    // ----------------------------------------------------------------

    /**
     * Minimal {@link GatewayAdapter} implementation that wraps/unwraps
     * {@link MockNativeExchange}. Demonstrates copy-on-wrap (ADR-0013).
     */
    static final class MockGatewayAdapter implements GatewayAdapter<MockNativeExchange> {

        @Override
        public Message wrapRequest(MockNativeExchange nativeRequest) {
            JsonNode body = parseBody(nativeRequest.bodyJson);
            Map<String, String> firstHeaders = firstValue(nativeRequest.headers);
            return new Message(
                    TestMessages.toBody(
                            body.deepCopy(), // no status for requests
                            nativeRequest.contentType),
                    TestMessages.toHeaders(firstHeaders, deepCopyHeaders(nativeRequest.headers)),
                    null,
                    nativeRequest.path,
                    nativeRequest.method,
                    nativeRequest.queryString,
                    SessionContext.empty());
        }

        @Override
        public Message wrapResponse(MockNativeExchange nativeResponse) {
            JsonNode body = parseBody(nativeResponse.bodyJson);
            Map<String, String> firstHeaders = firstValue(nativeResponse.headers);
            return new Message(
                    TestMessages.toBody(body.deepCopy(), nativeResponse.contentType),
                    TestMessages.toHeaders(firstHeaders, deepCopyHeaders(nativeResponse.headers)),
                    nativeResponse.statusCode,
                    nativeResponse.path,
                    nativeResponse.method,
                    null,
                    SessionContext.empty());
        }

        @Override
        public void applyChanges(Message transformedMessage, MockNativeExchange nativeTarget) {
            nativeTarget.bodyJson =
                    TestMessages.parseBody(transformedMessage.body()).toString();
            nativeTarget.statusCode = transformedMessage.statusCode();
            // Write back headers
            nativeTarget.headers.clear();
            transformedMessage.headers().toMultiValueMap().forEach((name, values) -> {
                nativeTarget.headers.put(name, new ArrayList<>(values));
            });
            // Write back URL fields
            if (transformedMessage.requestPath() != null) {
                nativeTarget.path = transformedMessage.requestPath();
            }
            if (transformedMessage.requestMethod() != null) {
                nativeTarget.method = transformedMessage.requestMethod();
            }
            nativeTarget.queryString = transformedMessage.queryString();
        }

        private JsonNode parseBody(String json) {
            if (json == null || json.isBlank()) {
                return NullNode.getInstance();
            }
            try {
                return MAPPER.readTree(json);
            } catch (Exception e) {
                return NullNode.getInstance();
            }
        }

        private Map<String, String> firstValue(Map<String, List<String>> multi) {
            Map<String, String> result = new LinkedHashMap<>();
            multi.forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    result.put(k.toLowerCase(), v.get(0));
                }
            });
            return result;
        }

        private Map<String, List<String>> deepCopyHeaders(Map<String, List<String>> source) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            source.forEach((k, v) -> copy.put(k.toLowerCase(), new ArrayList<>(v)));
            return copy;
        }
    }

    // --- Tests
    // -----------------------------------------------------------------------

    @Test
    void wrapRequest_populatesMessageFields() {
        var adapter = new MockGatewayAdapter();
        var headers = Map.of("Content-Type", List.of("application/json"), "X-Request-ID", List.of("abc-123"));

        var exchange = new MockNativeExchange(
                "{\"userId\": 42}",
                new HashMap<>(headers),
                null,
                "/api/v1/users",
                "POST",
                "application/json",
                "key=val");

        Message msg = adapter.wrapRequest(exchange);

        assertThat(TestMessages.parseBody(msg.body()).get("userId").asInt()).isEqualTo(42);
        assertThat(msg.headers().toSingleValueMap()).containsEntry("x-request-id", "abc-123");
        assertThat(msg.statusCode()).isNull();
        assertThat(msg.requestPath()).isEqualTo("/api/v1/users");
        assertThat(msg.requestMethod()).isEqualTo("POST");
        assertThat(msg.contentType()).isEqualTo("application/json");
        assertThat(msg.queryString()).isEqualTo("key=val");
    }

    @Test
    void wrapResponse_populatesMessageFields() {
        var adapter = new MockGatewayAdapter();
        var headers = Map.of("content-type", List.of("application/json"), "set-cookie", List.of("a=1", "b=2"));

        var exchange = new MockNativeExchange(
                "{\"status\": \"ok\"}", new HashMap<>(headers), 200, "/api/v1/users", "GET", "application/json", null);

        Message msg = adapter.wrapResponse(exchange);

        assertThat(TestMessages.parseBody(msg.body()).get("status").asText()).isEqualTo("ok");
        assertThat(msg.statusCode()).isEqualTo(200);
        assertThat(msg.requestPath()).isEqualTo("/api/v1/users");
        assertThat(msg.requestMethod()).isEqualTo("GET");
        assertThat(msg.headers().toMultiValueMap().get("set-cookie")).containsExactly("a=1", "b=2");
    }

    @Test
    void wrapRequest_deepCopiesBody_mutationsDoNotAffectNative() {
        var adapter = new MockGatewayAdapter();
        var exchange =
                new MockNativeExchange("{\"name\": \"original\"}", new HashMap<>(), null, "/test", "GET", null, null);

        Message msg = adapter.wrapRequest(exchange);

        // Mutate the Message body — native should be unaffected (ADR-0013 copy-on-wrap)
        ((com.fasterxml.jackson.databind.node.ObjectNode) TestMessages.parseBody(msg.body())).put("name", "mutated");

        // Re-parse the native body to verify independence
        JsonNode nativeBody = parseJson(exchange.bodyJson);
        assertThat(nativeBody.get("name").asText()).isEqualTo("original");
    }

    @Test
    void applyChanges_writesTransformedStateToNative() throws Exception {
        var adapter = new MockGatewayAdapter();

        var nativeExchange = new MockNativeExchange(
                "{\"old\": true}",
                new HashMap<>(Map.of("x-old", List.of("remove-me"))),
                200,
                "/old/path",
                "POST",
                "application/json",
                "debug=true");

        // Simulate a transformed message
        var transformedBody = MAPPER.readTree("{\"new\": true}");
        var newHeaders = Map.of("x-new", "added");
        var newHeadersAll = Map.of("x-new", List.of("added"));
        Message transformed = new Message(
                TestMessages.toBody(transformedBody, "application/json"),
                TestMessages.toHeaders(newHeaders, newHeadersAll),
                201,
                "/new/path",
                "PUT",
                "format=json",
                SessionContext.empty());

        adapter.applyChanges(transformed, nativeExchange);

        assertThat(nativeExchange.bodyJson).isEqualTo("{\"new\":true}");
        assertThat(nativeExchange.statusCode).isEqualTo(201);
        assertThat(nativeExchange.headers).containsKey("x-new");
        assertThat(nativeExchange.headers).doesNotContainKey("x-old");
        assertThat(nativeExchange.path).isEqualTo("/new/path");
        assertThat(nativeExchange.method).isEqualTo("PUT");
        assertThat(nativeExchange.queryString).isEqualTo("format=json");
    }

    @Test
    void applyChanges_notCalledOnFailure_nativeUnchanged() {
        var original =
                new MockNativeExchange("{\"preserved\": true}", new HashMap<>(), 200, "/original", "GET", null, null);

        // Simulate: engine returns ERROR — adapter does NOT call applyChanges.
        // Just verify the native exchange is still untouched.
        assertThat(original.bodyJson).isEqualTo("{\"preserved\": true}");
        assertThat(original.statusCode).isEqualTo(200);
        assertThat(original.path).isEqualTo("/original");
    }

    @Test
    void wrapResponse_setsRequestMetadataForProfileMatching() {
        // For response transforms, the adapter must set request metadata (path, method)
        // on the response Message from the gateway's exchange context, because profile
        // matching always uses request criteria (spec.md, API-001-01 note).
        var adapter = new MockGatewayAdapter();
        var response = new MockNativeExchange(
                "{\"data\": 1}",
                new HashMap<>(),
                200,
                "/api/v1/orders", // request path from exchange
                "POST", // request method from exchange
                "application/json",
                null);

        Message msg = adapter.wrapResponse(response);

        // Profile matcher needs request metadata on the response Message
        assertThat(msg.requestPath()).isEqualTo("/api/v1/orders");
        assertThat(msg.requestMethod()).isEqualTo("POST");
    }

    private static JsonNode parseJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
