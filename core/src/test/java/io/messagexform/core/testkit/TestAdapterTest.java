package io.messagexform.core.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TestGatewayAdapter} and {@link TestMessage} (T-001-49).
 * Verifies the full adapter lifecycle: native → wrap → transform → applyChanges
 * → native.
 */
class TestAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TestGatewayAdapter adapter = new TestGatewayAdapter();

    @Nested
    @DisplayName("wrapRequest")
    class WrapRequestTests {

        @Test
        void populatesAllMessageFields() {
            TestMessage request = TestMessage.request("{\"userId\": 42}", "/api/users", "POST")
                    .withContentType("application/json")
                    .withHeader("x-request-id", "abc-123")
                    .withQueryString("page=1&size=10");

            Message msg = adapter.wrapRequest(request);

            assertThat(msg.body().get("userId").asInt()).isEqualTo(42);
            assertThat(msg.headers()).containsEntry("x-request-id", "abc-123");
            assertThat(msg.statusCode()).isNull();
            assertThat(msg.requestPath()).isEqualTo("/api/users");
            assertThat(msg.requestMethod()).isEqualTo("POST");
            assertThat(msg.contentType()).isEqualTo("application/json");
            assertThat(msg.queryString()).isEqualTo("page=1&size=10");
        }

        @Test
        void deepCopiesBody_mutationsDoNotAffectNative() {
            TestMessage request = TestMessage.request("{\"name\": \"original\"}", "/test", "GET");

            Message msg = adapter.wrapRequest(request);
            ((ObjectNode) msg.body()).put("name", "mutated");

            // Native is unaffected — copy-on-wrap (ADR-0013)
            assertThat(request.bodyJson()).isEqualTo("{\"name\": \"original\"}");
        }

        @Test
        void handlesNullBody() {
            TestMessage request = TestMessage.request(null, "/test", "GET");

            Message msg = adapter.wrapRequest(request);

            assertThat(msg.body().isNull()).isTrue();
        }

        @Test
        void handlesEmptyHeaders() {
            TestMessage request = TestMessage.request("{}", "/test", "GET");

            Message msg = adapter.wrapRequest(request);

            assertThat(msg.headers()).isEmpty();
            assertThat(msg.headersAll()).isEmpty();
        }

        @Test
        void normalizesHeaderNamesToLowercase() {
            TestMessage request =
                    TestMessage.create().withBodyJson("{}").withPath("/test").withMethod("GET");
            request.headers().put("X-Mixed-Case", List.of("value"));

            Message msg = adapter.wrapRequest(request);

            assertThat(msg.headers()).containsKey("x-mixed-case");
            assertThat(msg.headersAll()).containsKey("x-mixed-case");
        }

        @Test
        void rejectsNullNativeRequest() {
            assertThatThrownBy(() -> adapter.wrapRequest(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("wrapResponse")
    class WrapResponseTests {

        @Test
        void populatesAllMessageFields() {
            TestMessage response = TestMessage.response("{\"status\": \"ok\"}", 200, "/api/users", "GET")
                    .withContentType("application/json")
                    .withHeader("set-cookie", "a=1", "b=2");

            Message msg = adapter.wrapResponse(response);

            assertThat(msg.body().get("status").asText()).isEqualTo("ok");
            assertThat(msg.statusCode()).isEqualTo(200);
            assertThat(msg.requestPath()).isEqualTo("/api/users");
            assertThat(msg.requestMethod()).isEqualTo("GET");
            assertThat(msg.headersAll().get("set-cookie")).containsExactly("a=1", "b=2");
        }

        @Test
        void preservesRequestMetadataForProfileMatching() {
            TestMessage response = TestMessage.response("{}", 200, "/api/v1/orders", "POST");

            Message msg = adapter.wrapResponse(response);

            // Profile matcher needs these on response Messages
            assertThat(msg.requestPath()).isEqualTo("/api/v1/orders");
            assertThat(msg.requestMethod()).isEqualTo("POST");
        }
    }

    @Nested
    @DisplayName("applyChanges")
    class ApplyChangesTests {

        @Test
        void writesTransformedBodyToNative() throws Exception {
            TestMessage nativeMsg = TestMessage.response("{\"old\": true}", 200);
            JsonNode newBody = MAPPER.readTree("{\"new\": true}");
            Message transformed = new Message(newBody, Map.of(), Map.of(), 200, null, null, null);

            adapter.applyChanges(transformed, nativeMsg);

            assertThat(nativeMsg.bodyJson()).isEqualTo("{\"new\":true}");
        }

        @Test
        void writesTransformedStatusToNative() throws Exception {
            TestMessage nativeMsg = TestMessage.response("{}", 200);
            Message transformed = new Message(MAPPER.readTree("{}"), Map.of(), Map.of(), 201, null, null, null);

            adapter.applyChanges(transformed, nativeMsg);

            assertThat(nativeMsg.statusCode()).isEqualTo(201);
        }

        @Test
        void writesTransformedHeadersToNative() throws Exception {
            TestMessage nativeMsg = TestMessage.response("{}", 200).withHeader("x-old", "remove-me");
            var newHeadersAll = Map.of("x-new", List.of("added"));
            Message transformed =
                    new Message(MAPPER.readTree("{}"), Map.of("x-new", "added"), newHeadersAll, 200, null, null, null);

            adapter.applyChanges(transformed, nativeMsg);

            assertThat(nativeMsg.headers()).containsKey("x-new");
            assertThat(nativeMsg.headers()).doesNotContainKey("x-old");
        }

        @Test
        void writesTransformedUrlFieldsToNative() throws Exception {
            TestMessage nativeMsg =
                    TestMessage.request("{}", "/old/path", "POST").withQueryString("debug=true");
            Message transformed = new Message(
                    MAPPER.readTree("{}"), Map.of(), Map.of(), null, null, "/new/path", "PUT", "format=json");

            adapter.applyChanges(transformed, nativeMsg);

            assertThat(nativeMsg.path()).isEqualTo("/new/path");
            assertThat(nativeMsg.method()).isEqualTo("PUT");
            assertThat(nativeMsg.queryString()).isEqualTo("format=json");
        }

        @Test
        void rejectsNullArguments() {
            TestMessage msg = TestMessage.create().withBodyJson("{}");
            Message transformed = new Message(MAPPER.createObjectNode(), Map.of(), Map.of(), null, null, null, null);

            assertThatThrownBy(() -> adapter.applyChanges(null, msg)).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> adapter.applyChanges(transformed, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("End-to-end: wrap → transform → applyChanges")
    class EndToEndTests {

        @Test
        void requestTransformEndToEnd() throws Exception {
            // 1. Create native request
            TestMessage nativeRequest = TestMessage.request(
                            "{\"userId\": 42, \"action\": \"create\"}", "/api/users", "POST")
                    .withContentType("application/json")
                    .withHeader("x-request-id", "req-001");

            // 2. Wrap → Message (copy-on-wrap)
            Message requestMsg = adapter.wrapRequest(nativeRequest);

            // 3. Transform (simulated — engine would do this)
            // For this test, manually create the transformed message
            JsonNode transformedBody = MAPPER.readTree("{\"id\": 42, \"operation\": \"CREATE\"}");
            Message transformedMsg = new Message(
                    transformedBody,
                    requestMsg.headers(),
                    requestMsg.headersAll(),
                    null,
                    requestMsg.contentType(),
                    "/api/users/42",
                    "PUT",
                    null);

            // 4. Apply changes back to native
            adapter.applyChanges(transformedMsg, nativeRequest);

            // 5. Verify native is updated
            JsonNode updatedBody = MAPPER.readTree(nativeRequest.bodyJson());
            assertThat(updatedBody.get("id").asInt()).isEqualTo(42);
            assertThat(updatedBody.get("operation").asText()).isEqualTo("CREATE");
            assertThat(nativeRequest.path()).isEqualTo("/api/users/42");
            assertThat(nativeRequest.method()).isEqualTo("PUT");
        }

        @Test
        void responseTransformEndToEnd() throws Exception {
            // 1. Create native response
            TestMessage nativeResponse = TestMessage.response(
                            "{\"callbacks\": [{\"type\": \"NameCallback\"}]}", 200, "/json/realms/authenticate", "POST")
                    .withContentType("application/json");

            // 2. Wrap → Message
            Message responseMsg = adapter.wrapResponse(nativeResponse);
            assertThat(responseMsg.requestPath()).isEqualTo("/json/realms/authenticate");

            // 3. Simulate transform
            JsonNode transformedBody = MAPPER.readTree("{\"fields\": [{\"label\": \"Username\"}]}");
            Message transformedMsg = new Message(
                    transformedBody,
                    responseMsg.headers(),
                    responseMsg.headersAll(),
                    200,
                    responseMsg.contentType(),
                    responseMsg.requestPath(),
                    responseMsg.requestMethod());

            // 4. Apply changes
            adapter.applyChanges(transformedMsg, nativeResponse);

            // 5. Verify
            JsonNode updatedBody = MAPPER.readTree(nativeResponse.bodyJson());
            assertThat(updatedBody.has("fields")).isTrue();
            assertThat(updatedBody.get("fields").get(0).get("label").asText()).isEqualTo("Username");
        }

        @Test
        void failedTransform_nativeUnchanged() {
            // When the engine returns ERROR, applyChanges is NOT called.
            // The native message must remain exactly as it was.
            TestMessage nativeRequest = TestMessage.request("{\"original\": true}", "/api/test", "GET")
                    .withHeader("x-request-id", "req-fail");

            // Simulate: adapter wraps, engine produces ERROR result → no applyChanges
            adapter.wrapRequest(nativeRequest); // copy created but discarded

            // Native is completely untouched
            assertThat(nativeRequest.bodyJson()).isEqualTo("{\"original\": true}");
            assertThat(nativeRequest.path()).isEqualTo("/api/test");
            assertThat(nativeRequest.method()).isEqualTo("GET");
            assertThat(nativeRequest.headers()).containsKey("x-request-id");
        }

        @Test
        void e2eWithRealEngine() throws Exception {
            // Full integration: use a real TransformEngine with the adapter.
            EngineRegistry registry = new EngineRegistry();
            registry.register(new JsltExpressionEngine());
            SpecParser specParser = new SpecParser(registry);
            var engine = new TransformEngine(specParser);
            Path specPath = Path.of("src/test/resources/test-vectors/jslt-simple-rename.yaml");
            engine.loadSpec(specPath);

            // 1. Create native request with matching body
            TestMessage nativeRequest = TestMessage.request(
                            "{\"user_id\": \"usr-42\", \"first_name\": \"Alice\", \"last_name\": \"Smith\", \"email_address\": \"alice@example.com\"}",
                            "/api/test",
                            "POST")
                    .withContentType("application/json");

            // 2. Wrap
            Message msg = adapter.wrapRequest(nativeRequest);

            // 3. Transform with real engine
            TransformResult result = engine.transform(msg, Direction.REQUEST);
            assertThat(result.isSuccess()).isTrue();

            // 4. Apply changes
            adapter.applyChanges(result.message(), nativeRequest);

            // 5. Verify the native message has the transformed body
            JsonNode updatedBody = MAPPER.readTree(nativeRequest.bodyJson());
            assertThat(updatedBody.get("userId").asText()).isEqualTo("usr-42");
            assertThat(updatedBody.get("displayName").asText()).isEqualTo("Alice Smith");
            assertThat(updatedBody.get("contact").get("email").asText()).isEqualTo("alice@example.com");
        }
    }

    @Nested
    @DisplayName("TestMessage fluent builder")
    class TestMessageBuilderTests {

        @Test
        void requestFactoryMethod() {
            TestMessage msg = TestMessage.request("{\"x\": 1}", "/path", "GET");

            assertThat(msg.bodyJson()).isEqualTo("{\"x\": 1}");
            assertThat(msg.path()).isEqualTo("/path");
            assertThat(msg.method()).isEqualTo("GET");
            assertThat(msg.statusCode()).isNull();
        }

        @Test
        void responseFactoryMethod() {
            TestMessage msg = TestMessage.response("{\"x\": 1}", 200);

            assertThat(msg.bodyJson()).isEqualTo("{\"x\": 1}");
            assertThat(msg.statusCode()).isEqualTo(200);
        }

        @Test
        void responseWithRequestMetadata() {
            TestMessage msg = TestMessage.response("{}", 404, "/api/users", "DELETE");

            assertThat(msg.statusCode()).isEqualTo(404);
            assertThat(msg.path()).isEqualTo("/api/users");
            assertThat(msg.method()).isEqualTo("DELETE");
        }

        @Test
        void fluentChaining() {
            TestMessage msg = TestMessage.create()
                    .withBodyJson("{}")
                    .withPath("/test")
                    .withMethod("PATCH")
                    .withStatusCode(204)
                    .withContentType("application/json")
                    .withQueryString("key=val")
                    .withHeader("x-custom", "value");

            assertThat(msg.path()).isEqualTo("/test");
            assertThat(msg.method()).isEqualTo("PATCH");
            assertThat(msg.statusCode()).isEqualTo(204);
            assertThat(msg.contentType()).isEqualTo("application/json");
            assertThat(msg.queryString()).isEqualTo("key=val");
            assertThat(msg.headers()).containsKey("x-custom");
        }

        @Test
        void toStringTruncatesLongBodies() {
            String longBody = "{\"data\": \"" + "x".repeat(100) + "\"}";
            TestMessage msg = TestMessage.request(longBody, "/test", "POST").withStatusCode(200);

            String str = msg.toString();
            assertThat(str).contains("...");
            assertThat(str).contains("/test");
        }
    }
}
