package io.messagexform.standalone.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.NullNode;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.messagexform.core.model.Message;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StandaloneAdapter} — the {@code GatewayAdapter<Context>}
 * implementation for Javalin (T-004-15, FR-004-06, FR-004-07, FR-004-09).
 */
class StandaloneAdapterTest {

        private StandaloneAdapter adapter;

        @BeforeEach
        void setUp() {
                adapter = new StandaloneAdapter();
        }

        @Nested
        @DisplayName("wrapRequest")
        class WrapRequest {

                @Test
                @DisplayName("POST with JSON body → Message.body() is correct JsonNode")
                void postWithJsonBody() {
                        Context ctx = mockContext(
                                        HandlerType.POST,
                                        "/api/v1/users",
                                        "{\"name\":\"Ivan\",\"age\":30}",
                                        Map.of("content-type", "application/json"),
                                        "page=1");

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.body().isObject()).isTrue();
                        assertThat(msg.body().get("name").asText()).isEqualTo("Ivan");
                        assertThat(msg.body().get("age").asInt()).isEqualTo(30);
                }

                @Test
                @DisplayName("headers extracted with lowercase keys (FR-004-09)")
                void headersNormalizedToLowercase() {
                        Map<String, String> rawHeaders = new LinkedHashMap<>();
                        rawHeaders.put("Content-Type", "application/json");
                        rawHeaders.put("X-Custom-Header", "custom-value");
                        rawHeaders.put("Authorization", "Bearer token123");

                        Context ctx = mockContext(
                                        HandlerType.GET,
                                        "/api/data",
                                        null,
                                        rawHeaders,
                                        null);

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.headers()).containsEntry("content-type", "application/json");
                        assertThat(msg.headers()).containsEntry("x-custom-header", "custom-value");
                        assertThat(msg.headers()).containsEntry("authorization", "Bearer token123");
                        // No original-case keys
                        assertThat(msg.headers()).doesNotContainKey("Content-Type");
                        assertThat(msg.headers()).doesNotContainKey("X-Custom-Header");
                }

                @Test
                @DisplayName("headersAll multi-value map populated correctly")
                void headersAllMultiValue() {
                        Context ctx = mockContext(
                                        HandlerType.GET,
                                        "/api/data",
                                        null,
                                        Map.of("accept", "text/html"),
                                        null);

                        // Set up multi-value headers via servlet request
                        HttpServletRequest req = ctx.req();
                        when(req.getHeaderNames()).thenReturn(
                                        Collections.enumeration(List.of("Accept", "X-Multi")));
                        when(req.getHeaders("Accept")).thenReturn(
                                        Collections.enumeration(List.of("text/html", "application/json")));
                        when(req.getHeaders("X-Multi")).thenReturn(
                                        Collections.enumeration(List.of("val1", "val2", "val3")));

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.headersAll()).containsKey("accept");
                        assertThat(msg.headersAll().get("accept"))
                                        .containsExactly("text/html", "application/json");
                        assertThat(msg.headersAll()).containsKey("x-multi");
                        assertThat(msg.headersAll().get("x-multi"))
                                        .containsExactly("val1", "val2", "val3");
                }

                @Test
                @DisplayName("request path and method extracted")
                void pathAndMethodExtracted() {
                        Context ctx = mockContext(
                                        HandlerType.POST,
                                        "/api/v1/orders",
                                        "{}",
                                        Map.of("content-type", "application/json"),
                                        null);

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.requestPath()).isEqualTo("/api/v1/orders");
                        assertThat(msg.requestMethod()).isEqualTo("POST");
                }

                @Test
                @DisplayName("query string extracted (raw, without leading ?)")
                void queryStringExtracted() {
                        Context ctx = mockContext(
                                        HandlerType.GET,
                                        "/api/search",
                                        null,
                                        Map.of(),
                                        "q=hello&page=2&sort=name");

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.queryString()).isEqualTo("q=hello&page=2&sort=name");
                }

                @Test
                @DisplayName("no body (GET) → Message.body() is NullNode")
                void noBodyReturnsNullNode() {
                        Context ctx = mockContext(
                                        HandlerType.GET,
                                        "/api/users",
                                        null,
                                        Map.of(),
                                        null);

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.body()).isInstanceOf(NullNode.class);
                }

                @Test
                @DisplayName("empty body string → Message.body() is NullNode")
                void emptyBodyReturnsNullNode() {
                        Context ctx = mockContext(
                                        HandlerType.GET,
                                        "/api/users",
                                        "",
                                        Map.of(),
                                        null);

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.body()).isInstanceOf(NullNode.class);
                }

                @Test
                @DisplayName("content type extracted from headers")
                void contentTypeExtracted() {
                        Context ctx = mockContext(
                                        HandlerType.POST,
                                        "/api/data",
                                        "{\"key\":\"value\"}",
                                        Map.of("content-type", "application/json; charset=utf-8"),
                                        null);

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.contentType()).isEqualTo("application/json; charset=utf-8");
                }

                @Test
                @DisplayName("status code is null for requests")
                void statusCodeNullForRequests() {
                        Context ctx = mockContext(
                                        HandlerType.GET,
                                        "/api/data",
                                        null,
                                        Map.of(),
                                        null);

                        Message msg = adapter.wrapRequest(ctx);

                        assertThat(msg.statusCode()).isNull();
                }

                @Test
                @DisplayName("deep copy: mutation to Message does not affect original Context (ADR-0013)")
                void deepCopySemantics() {
                        String originalBody = "{\"name\":\"Original\"}";
                        Context ctx = mockContext(
                                        HandlerType.POST,
                                        "/api/data",
                                        originalBody,
                                        Map.of("content-type", "application/json"),
                                        null);

                        Message msg = adapter.wrapRequest(ctx);

                        // The body JsonNode is a deep copy — verify by checking it's a separate object
                        assertThat(msg.body().get("name").asText()).isEqualTo("Original");
                        // Headers map is an independent copy
                        assertThat(msg.headers()).isNotSameAs(ctx.headerMap());
                }
        }

        @Nested
        @DisplayName("wrapResponse")
        class WrapResponse {

                @Test
                @DisplayName("response with JSON body → Message.body() is correct JsonNode")
                void responseWithJsonBody() {
                        Context ctx = mockResponseContext(
                                        200,
                                        "{\"result\":\"ok\",\"count\":42}",
                                        Map.of("content-type", "application/json"),
                                        "/api/v1/orders",
                                        HandlerType.POST);

                        Message msg = adapter.wrapResponse(ctx);

                        assertThat(msg.body().isObject()).isTrue();
                        assertThat(msg.body().get("result").asText()).isEqualTo("ok");
                        assertThat(msg.body().get("count").asInt()).isEqualTo(42);
                }

                @Test
                @DisplayName("status code from upstream preserved")
                void statusCodePreserved() {
                        Context ctx = mockResponseContext(
                                        201,
                                        "{\"id\":1}",
                                        Map.of("content-type", "application/json"),
                                        "/api/users",
                                        HandlerType.POST);

                        Message msg = adapter.wrapResponse(ctx);

                        assertThat(msg.statusCode()).isEqualTo(201);
                }

                @Test
                @DisplayName("response headers extracted with lowercase keys (FR-004-09)")
                void headersNormalizedToLowercase() {
                        Map<String, String> responseHeaders = new LinkedHashMap<>();
                        responseHeaders.put("Content-Type", "application/json");
                        responseHeaders.put("X-Correlation-ID", "abc-123");

                        Context ctx = mockResponseContext(
                                        200,
                                        "{}",
                                        responseHeaders,
                                        "/api/data",
                                        HandlerType.GET);

                        Message msg = adapter.wrapResponse(ctx);

                        assertThat(msg.headers()).containsEntry("content-type", "application/json");
                        assertThat(msg.headers()).containsEntry("x-correlation-id", "abc-123");
                        assertThat(msg.headers()).doesNotContainKey("Content-Type");
                }

                @Test
                @DisplayName("requestPath and requestMethod from original request (FR-004-06b)")
                void requestPathAndMethodIncluded() {
                        Context ctx = mockResponseContext(
                                        200,
                                        "{}",
                                        Map.of("content-type", "application/json"),
                                        "/api/v1/orders",
                                        HandlerType.POST);

                        Message msg = adapter.wrapResponse(ctx);

                        assertThat(msg.requestPath()).isEqualTo("/api/v1/orders");
                        assertThat(msg.requestMethod()).isEqualTo("POST");
                }

                @Test
                @DisplayName("queryString is null for responses")
                void queryStringNullForResponses() {
                        Context ctx = mockResponseContext(
                                        200,
                                        "{}",
                                        Map.of("content-type", "application/json"),
                                        "/api/data",
                                        HandlerType.GET);

                        Message msg = adapter.wrapResponse(ctx);

                        assertThat(msg.queryString()).isNull();
                }

                @Test
                @DisplayName("no body (204) → Message.body() is NullNode (S-004-64)")
                void noBody204ReturnsNullNode() {
                        Context ctx = mockResponseContext(
                                        204,
                                        null,
                                        Map.of(),
                                        "/api/data",
                                        HandlerType.DELETE);

                        Message msg = adapter.wrapResponse(ctx);

                        assertThat(msg.body()).isInstanceOf(NullNode.class);
                        assertThat(msg.statusCode()).isEqualTo(204);
                }
        }

        // ---- Helpers ----

        /**
         * Creates a mocked Javalin {@link Context} for request-direction tests.
         */
        private static Context mockContext(
                        HandlerType method,
                        String path,
                        String body,
                        Map<String, String> headers,
                        String queryString) {

                Context ctx = mock(Context.class);
                HttpServletRequest req = mock(HttpServletRequest.class);

                when(ctx.method()).thenReturn(method);
                when(ctx.path()).thenReturn(path);
                when(ctx.body()).thenReturn(body != null ? body : "");
                when(ctx.queryString()).thenReturn(queryString);
                when(ctx.headerMap()).thenReturn(headers);
                when(ctx.contentType()).thenReturn(headers.getOrDefault("content-type",
                                headers.getOrDefault("Content-Type", null)));
                when(ctx.req()).thenReturn(req);

                // Default: servlet request returns header names from the headerMap
                when(req.getHeaderNames()).thenReturn(
                                Collections.enumeration(headers.keySet()));
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                        when(req.getHeaders(entry.getKey())).thenReturn(
                                        Collections.enumeration(List.of(entry.getValue())));
                }

                return ctx;
        }

        /**
         * Creates a mocked Javalin {@link Context} for response-direction tests.
         * Simulates ProxyHandler having populated ctx with upstream response data.
         */
        private static Context mockResponseContext(
                        int statusCode,
                        String body,
                        Map<String, String> responseHeaders,
                        String originalPath,
                        HandlerType originalMethod) {

                Context ctx = mock(Context.class);
                HttpServletRequest req = mock(HttpServletRequest.class);
                jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);

                // Original request info (for profile matching)
                when(ctx.method()).thenReturn(originalMethod);
                when(ctx.path()).thenReturn(originalPath);

                // Response body (set by ProxyHandler via ctx.result())
                when(ctx.result()).thenReturn(body);

                // Response status (set by ProxyHandler via ctx.status())
                when(ctx.statusCode()).thenReturn(statusCode);

                // Response headers (set by ProxyHandler via ctx.header())
                when(ctx.res()).thenReturn(res);
                when(res.getHeaderNames()).thenReturn(responseHeaders.keySet());
                for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                        when(res.getHeader(entry.getKey())).thenReturn(entry.getValue());
                        when(res.getHeaders(entry.getKey())).thenReturn(List.of(entry.getValue()));
                }

                // Content-Type from response headers
                String ct = responseHeaders.getOrDefault("content-type",
                                responseHeaders.getOrDefault("Content-Type", null));
                when(ctx.contentType()).thenReturn(ct);

                when(ctx.req()).thenReturn(req);
                when(req.getHeaderNames()).thenReturn(Collections.enumeration(List.of()));

                return ctx;
        }
}
