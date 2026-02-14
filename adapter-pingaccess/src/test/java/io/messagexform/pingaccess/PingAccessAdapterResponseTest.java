package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.*;
import com.pingidentity.pa.sdk.policy.AccessException;
import io.messagexform.core.model.MediaType;
import io.messagexform.core.model.Message;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PingAccessAdapter#wrapResponse(Exchange)} (T-002-07,
 * T-002-10a, FR-002-01).
 *
 * <p>
 * Mirrors the request-side tests but verifies response-specific behaviour:
 * status code mapping, request metadata from the request side of the exchange,
 * debug logging, body parse fallback parity, and compressed body fallback.
 */
class PingAccessAdapterResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PingAccessAdapter adapter;

    private Exchange exchange;
    private Request request;
    private Response response;
    private Body requestBody;
    private Body responseBody;
    private Headers requestHeaders;
    private Headers responseHeaders;

    @BeforeEach
    void setUp() {
        adapter = new PingAccessAdapter(MAPPER);

        exchange = mock(Exchange.class);
        request = mock(Request.class);
        response = mock(Response.class);
        requestBody = mock(Body.class);
        responseBody = mock(Body.class);
        requestHeaders = mock(Headers.class);
        responseHeaders = mock(Headers.class);

        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);

        // Request side defaults (for requestPath/method in response wrapping)
        when(request.getUri()).thenReturn("/api/v1/users");
        when(request.getMethod()).thenReturn(Method.GET);
        when(request.getBody()).thenReturn(requestBody);
        when(request.getHeaders()).thenReturn(requestHeaders);
        when(requestBody.isRead()).thenReturn(true);
        when(requestBody.getContent()).thenReturn(new byte[0]);
        when(requestHeaders.getHeaderFields()).thenReturn(List.of());

        // Response side defaults
        when(response.getStatusCode()).thenReturn(200);
        when(response.getBody()).thenReturn(responseBody);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(responseBody.isRead()).thenReturn(true);
        when(responseBody.getContent()).thenReturn(new byte[0]);
        when(responseHeaders.getHeaderFields()).thenReturn(List.of());
    }

    // ---- T-002-07: wrapResponse mapping ----

    @Nested
    class WrapResponseMapping {

        @Test
        void statusCodeIsMapped() {
            when(response.getStatusCode()).thenReturn(404);

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.statusCode()).isEqualTo(404);
        }

        @Test
        void requestPathFromRequestSide() {
            when(request.getUri()).thenReturn("/api/v2/orders?status=pending");

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.requestPath()).isEqualTo("/api/v2/orders");
            assertThat(msg.queryString()).isEqualTo("status=pending");
        }

        @Test
        void requestMethodFromRequestSide() {
            when(request.getMethod()).thenReturn(Method.POST);

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.requestMethod()).isEqualTo("POST");
        }

        @Test
        void responseHeadersMapped() {
            when(responseHeaders.getHeaderFields())
                    .thenReturn(List.of(
                            new HeaderField("Content-Type", "application/json"),
                            new HeaderField("X-Powered-By", "PA")));

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.headers().first("content-type")).isEqualTo("application/json");
            assertThat(msg.headers().first("x-powered-by")).isEqualTo("PA");
        }

        @Test
        void validJsonResponseBody() {
            byte[] json = "{\"id\":42}".getBytes(StandardCharsets.UTF_8);
            when(responseBody.isRead()).thenReturn(true);
            when(responseBody.getContent()).thenReturn(json);

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.body().asString()).isEqualTo("{\"id\":42}");
            assertThat(msg.body().mediaType()).isEqualTo(MediaType.JSON);
            assertThat(adapter.isBodyParseFailed()).isFalse();
        }

        @Test
        void emptyResponseBody() {
            when(responseBody.isRead()).thenReturn(true);
            when(responseBody.getContent()).thenReturn(new byte[0]);

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isFalse();
        }
    }

    // ---- T-002-07: body parse fallback parity with request side ----

    @Nested
    class ResponseBodyFallback {

        @Test
        void malformedJsonResponse() {
            byte[] notJson = "<html>error</html>".getBytes(StandardCharsets.UTF_8);
            when(responseBody.isRead()).thenReturn(true);
            when(responseBody.getContent()).thenReturn(notJson);

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isTrue();
        }

        @Test
        void responseBodyReadThrowsIOException() throws Exception {
            when(responseBody.isRead()).thenReturn(false);
            doThrow(new IOException("network error")).when(responseBody).read();

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isTrue();
        }

        @Test
        void responseBodyReadThrowsAccessException() throws Exception {
            when(responseBody.isRead()).thenReturn(false);
            doThrow(new AccessException("too large", HttpStatus.REQUEST_ENTITY_TOO_LARGE))
                    .when(responseBody)
                    .read();

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isTrue();
        }

        @Test
        void bodyNotReadTriggersRead() throws Exception {
            byte[] json = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            when(responseBody.isRead()).thenReturn(false);
            doNothing().when(responseBody).read();
            when(responseBody.getContent()).thenReturn(json);

            Message msg = adapter.wrapResponse(exchange);

            verify(responseBody).read();
            assertThat(msg.body().asString()).isEqualTo("{\"ok\":true}");
        }
    }

    // ---- T-002-10a: Compressed body fallback ----

    @Nested
    class CompressedBodyFallback {

        @Test
        void gzipBodyFailsParseGracefully() {
            // Gzip magic bytes — not valid JSON
            byte[] gzipBytes = {0x1f, (byte) 0x8b, 0x08, 0x00};
            when(responseBody.isRead()).thenReturn(true);
            when(responseBody.getContent()).thenReturn(gzipBytes);

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isTrue();
        }

        @Test
        void deflateBodyFailsParseGracefully() {
            // Random binary — not valid JSON
            byte[] deflateBytes = {0x78, (byte) 0x9c, 0x01, 0x02};
            when(responseBody.isRead()).thenReturn(true);
            when(responseBody.getContent()).thenReturn(deflateBytes);

            Message msg = adapter.wrapResponse(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isTrue();
        }
    }

    // ---- T-002-07: bodyParseFailed resets between calls ----

    @Nested
    class FlagReset {

        @Test
        void wrapResponseResetsFlagOnValidJson() {
            // First: malformed
            byte[] notJson = "bad".getBytes(StandardCharsets.UTF_8);
            when(responseBody.isRead()).thenReturn(true);
            when(responseBody.getContent()).thenReturn(notJson);
            adapter.wrapResponse(exchange);
            assertThat(adapter.isBodyParseFailed()).isTrue();

            // Second: valid
            byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
            when(responseBody.getContent()).thenReturn(json);
            adapter.wrapResponse(exchange);
            assertThat(adapter.isBodyParseFailed()).isFalse();
        }
    }
}
