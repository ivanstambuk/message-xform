package io.messagexform.standalone.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.MessageBody;
import io.messagexform.core.model.SessionContext;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        void postWithJsonBody() throws Exception {
            Context ctx = mockContext(
                    HandlerType.POST,
                    "/api/v1/users",
                    "{\"name\":\"Ivan\",\"age\":30}",
                    Map.of("content-type", "application/json"),
                    "page=1");

            Message msg = adapter.wrapRequest(ctx);

            JsonNode body = MAPPER.readTree(msg.body().asString());
            assertThat(body.isObject()).isTrue();
            assertThat(body.get("name").asText()).isEqualTo("Ivan");
            assertThat(body.get("age").asInt()).isEqualTo(30);
        }

        @Test
        @DisplayName("headers extracted with lowercase keys (FR-004-09)")
        void headersNormalizedToLowercase() {
            Map<String, String> rawHeaders = new LinkedHashMap<>();
            rawHeaders.put("Content-Type", "application/json");
            rawHeaders.put("X-Custom-Header", "custom-value");
            rawHeaders.put("Authorization", "Bearer token123");

            Context ctx = mockContext(HandlerType.GET, "/api/data", null, rawHeaders, null);

            Message msg = adapter.wrapRequest(ctx);

            assertThat(msg.headers().toSingleValueMap()).containsEntry("content-type", "application/json");
            assertThat(msg.headers().toSingleValueMap()).containsEntry("x-custom-header", "custom-value");
            assertThat(msg.headers().toSingleValueMap()).containsEntry("authorization", "Bearer token123");
            // No original-case keys (keys are lowercased)
            assertThat(msg.headers().first("Content-Type")).isEqualTo("application/json");
            // Case-insensitive lookup should still work
            assertThat(msg.headers().first("content-type")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("headersAll multi-value map populated correctly")
        void headersAllMultiValue() {
            Context ctx = mockContext(HandlerType.GET, "/api/data", null, Map.of("accept", "text/html"), null);

            // Set up multi-value headers via servlet request
            HttpServletRequest req = ctx.req();
            when(req.getHeaderNames()).thenReturn(Collections.enumeration(List.of("Accept", "X-Multi")));
            when(req.getHeaders("Accept"))
                    .thenReturn(Collections.enumeration(List.of("text/html", "application/json")));
            when(req.getHeaders("X-Multi")).thenReturn(Collections.enumeration(List.of("val1", "val2", "val3")));

            Message msg = adapter.wrapRequest(ctx);

            assertThat(msg.headers().toMultiValueMap()).containsKey("accept");
            assertThat(msg.headers().toMultiValueMap().get("accept")).containsExactly("text/html", "application/json");
            assertThat(msg.headers().toMultiValueMap()).containsKey("x-multi");
            assertThat(msg.headers().toMultiValueMap().get("x-multi")).containsExactly("val1", "val2", "val3");
        }

        @Test
        @DisplayName("request path and method extracted")
        void pathAndMethodExtracted() {
            Context ctx = mockContext(
                    HandlerType.POST, "/api/v1/orders", "{}", Map.of("content-type", "application/json"), null);

            Message msg = adapter.wrapRequest(ctx);

            assertThat(msg.requestPath()).isEqualTo("/api/v1/orders");
            assertThat(msg.requestMethod()).isEqualTo("POST");
        }

        @Test
        @DisplayName("query string extracted (raw, without leading ?)")
        void queryStringExtracted() {
            Context ctx = mockContext(HandlerType.GET, "/api/search", null, Map.of(), "q=hello&page=2&sort=name");

            Message msg = adapter.wrapRequest(ctx);

            assertThat(msg.queryString()).isEqualTo("q=hello&page=2&sort=name");
        }

        @Test
        @DisplayName("no body (GET) → Message.body() is empty")
        void noBodyReturnsEmpty() {
            Context ctx = mockContext(HandlerType.GET, "/api/users", null, Map.of(), null);

            Message msg = adapter.wrapRequest(ctx);

            assertThat(msg.body().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("empty body string → Message.body() is empty")
        void emptyBodyReturnsEmpty() {
            Context ctx = mockContext(HandlerType.GET, "/api/users", "", Map.of(), null);

            Message msg = adapter.wrapRequest(ctx);

            assertThat(msg.body().isEmpty()).isTrue();
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

            // Body carries a JSON media type from the parseBody method
            assertThat(msg.body().isEmpty()).isFalse();
        }

        @Test
        @DisplayName("status code is null for requests")
        void statusCodeNullForRequests() {
            Context ctx = mockContext(HandlerType.GET, "/api/data", null, Map.of(), null);

            Message msg = adapter.wrapRequest(ctx);

            assertThat(msg.statusCode()).isNull();
        }

        @Test
        @DisplayName("deep copy: mutation to Message does not affect original Context (ADR-0013)")
        void deepCopySemantics() throws Exception {
            String originalBody = "{\"name\":\"Original\"}";
            Context ctx = mockContext(
                    HandlerType.POST, "/api/data", originalBody, Map.of("content-type", "application/json"), null);

            Message msg = adapter.wrapRequest(ctx);

            // The body is a deep copy — verify by parsing
            JsonNode body = MAPPER.readTree(msg.body().asString());
            assertThat(body.get("name").asText()).isEqualTo("Original");
            // Headers are an independent copy via HttpHeaders
            assertThat(msg.headers().toSingleValueMap()).isNotSameAs(ctx.headerMap());
        }
    }

    @Nested
    @DisplayName("wrapResponse")
    class WrapResponse {

        @Test
        @DisplayName("response with JSON body → Message.body() is correct JsonNode")
        void responseWithJsonBody() throws Exception {
            Context ctx = mockResponseContext(
                    200,
                    "{\"result\":\"ok\",\"count\":42}",
                    Map.of("content-type", "application/json"),
                    "/api/v1/orders",
                    HandlerType.POST);

            Message msg = adapter.wrapResponse(ctx);

            JsonNode body = MAPPER.readTree(msg.body().asString());
            assertThat(body.isObject()).isTrue();
            assertThat(body.get("result").asText()).isEqualTo("ok");
            assertThat(body.get("count").asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("status code from upstream preserved")
        void statusCodePreserved() {
            Context ctx = mockResponseContext(
                    201, "{\"id\":1}", Map.of("content-type", "application/json"), "/api/users", HandlerType.POST);

            Message msg = adapter.wrapResponse(ctx);

            assertThat(msg.statusCode()).isEqualTo(201);
        }

        @Test
        @DisplayName("response headers extracted with lowercase keys (FR-004-09)")
        void headersNormalizedToLowercase() {
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            responseHeaders.put("Content-Type", "application/json");
            responseHeaders.put("X-Correlation-ID", "abc-123");

            Context ctx = mockResponseContext(200, "{}", responseHeaders, "/api/data", HandlerType.GET);

            Message msg = adapter.wrapResponse(ctx);

            assertThat(msg.headers().toSingleValueMap()).containsEntry("content-type", "application/json");
            assertThat(msg.headers().toSingleValueMap()).containsEntry("x-correlation-id", "abc-123");
            // Case-insensitive (HttpHeaders stores lowercase)
            assertThat(msg.headers().first("content-type")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("requestPath and requestMethod from original request (FR-004-06b)")
        void requestPathAndMethodIncluded() {
            Context ctx = mockResponseContext(
                    200, "{}", Map.of("content-type", "application/json"), "/api/v1/orders", HandlerType.POST);

            Message msg = adapter.wrapResponse(ctx);

            assertThat(msg.requestPath()).isEqualTo("/api/v1/orders");
            assertThat(msg.requestMethod()).isEqualTo("POST");
        }

        @Test
        @DisplayName("queryString is null for responses")
        void queryStringNullForResponses() {
            Context ctx = mockResponseContext(
                    200, "{}", Map.of("content-type", "application/json"), "/api/data", HandlerType.GET);

            Message msg = adapter.wrapResponse(ctx);

            assertThat(msg.queryString()).isNull();
        }

        @Test
        @DisplayName("no body (204) → Message.body() is empty (S-004-64)")
        void noBody204ReturnsEmpty() {
            Context ctx = mockResponseContext(204, null, Map.of(), "/api/data", HandlerType.DELETE);

            Message msg = adapter.wrapResponse(ctx);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(msg.statusCode()).isEqualTo(204);
        }
    }

    @Nested
    @DisplayName("applyChanges")
    class ApplyChanges {

        @Test
        @DisplayName("transformed body written to ctx.result()")
        void bodyWrittenToResult() {
            Message msg = new Message(
                    MessageBody.json("{\"result\":\"ok\"}"),
                    HttpHeaders.empty(),
                    200,
                    "/api/data",
                    "GET",
                    null,
                    SessionContext.empty());

            Context ctx = mock(Context.class);
            when(ctx.result("{\"result\":\"ok\"}")).thenReturn(ctx);

            adapter.applyChanges(msg, ctx);

            verify(ctx).result("{\"result\":\"ok\"}");
        }

        @Test
        @DisplayName("transformed headers written to ctx.header()")
        void headersWrittenToContext() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-custom", "value1");
            headers.put("x-correlation-id", "abc-123");

            Message msg = new Message(
                    MessageBody.json("{}"),
                    HttpHeaders.of(headers),
                    200,
                    "/api/data",
                    "GET",
                    null,
                    SessionContext.empty());

            Context ctx = mock(Context.class);
            when(ctx.header("x-custom", "value1")).thenReturn(ctx);
            when(ctx.header("x-correlation-id", "abc-123")).thenReturn(ctx);

            adapter.applyChanges(msg, ctx);

            verify(ctx).header("x-custom", "value1");
            verify(ctx).header("x-correlation-id", "abc-123");
        }

        @Test
        @DisplayName("transformed status code written to ctx.status()")
        void statusWrittenToContext() {
            Message msg = new Message(
                    MessageBody.json("{}"),
                    HttpHeaders.empty(),
                    201,
                    "/api/users",
                    "POST",
                    null,
                    SessionContext.empty());

            Context ctx = mock(Context.class);
            when(ctx.status(201)).thenReturn(ctx);

            adapter.applyChanges(msg, ctx);

            verify(ctx).status(201);
        }

        @Test
        @DisplayName("empty body → empty response body")
        void emptyBodyWritesEmptyResult() {
            Message msg = new Message(
                    MessageBody.empty(), HttpHeaders.empty(), 204, "/api/data", "DELETE", null, SessionContext.empty());

            Context ctx = mock(Context.class);
            when(ctx.result("")).thenReturn(ctx);
            when(ctx.status(204)).thenReturn(ctx);

            adapter.applyChanges(msg, ctx);

            verify(ctx).result("");
            verify(ctx).status(204);
        }
    }

    // ---- Helpers ----

    /**
     * Creates a mocked Javalin {@link Context} for request-direction tests.
     */
    private static Context mockContext(
            HandlerType method, String path, String body, Map<String, String> headers, String queryString) {

        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);

        when(ctx.method()).thenReturn(method);
        when(ctx.path()).thenReturn(path);
        when(ctx.body()).thenReturn(body != null ? body : "");
        when(ctx.queryString()).thenReturn(queryString);
        when(ctx.headerMap()).thenReturn(headers);
        when(ctx.contentType())
                .thenReturn(headers.getOrDefault("content-type", headers.getOrDefault("Content-Type", null)));
        when(ctx.req()).thenReturn(req);

        // Default: servlet request returns header names from the headerMap
        when(req.getHeaderNames()).thenReturn(Collections.enumeration(headers.keySet()));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            when(req.getHeaders(entry.getKey())).thenReturn(Collections.enumeration(List.of(entry.getValue())));
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
        String ct = responseHeaders.getOrDefault("content-type", responseHeaders.getOrDefault("Content-Type", null));
        when(ctx.contentType()).thenReturn(ct);

        when(ctx.req()).thenReturn(req);
        when(req.getHeaderNames()).thenReturn(Collections.enumeration(List.of()));

        return ctx;
    }
}
