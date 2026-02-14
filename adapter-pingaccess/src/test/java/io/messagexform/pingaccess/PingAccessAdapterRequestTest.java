package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.*;
import com.pingidentity.pa.sdk.policy.AccessException;
import io.messagexform.core.model.MediaType;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PingAccessAdapter#wrapRequest(Exchange)} (T-002-03 through
 * T-002-06, FR-002-01).
 *
 * <p>
 * Uses mocked PA SDK
 * {@link Exchange}/{@link Request}/{@link Body}/{@link Headers}
 * objects. Each nested class covers one task.
 */
class PingAccessAdapterRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PingAccessAdapter adapter;

    private Exchange exchange;
    private Request request;
    private Body body;
    private Headers headers;

    @BeforeEach
    void setUp() {
        adapter = new PingAccessAdapter(MAPPER);

        exchange = mock(Exchange.class);
        request = mock(Request.class);
        body = mock(Body.class);
        headers = mock(Headers.class);

        when(exchange.getRequest()).thenReturn(request);
        when(request.getBody()).thenReturn(body);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getMethod()).thenReturn(Method.GET);
        when(request.getUri()).thenReturn("/api/v1/users");
        when(body.isRead()).thenReturn(true);
        when(body.getContent()).thenReturn(new byte[0]);
        when(headers.getHeaderFields()).thenReturn(List.of());
    }

    // ---- T-002-03: URI split: path + query string ----

    @Nested
    class PathAndQueryMapping {

        @Test
        void uriWithQueryString() {
            when(request.getUri()).thenReturn("/api/v1/users?role=admin&limit=10");

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.requestPath()).isEqualTo("/api/v1/users");
            assertThat(msg.queryString()).isEqualTo("role=admin&limit=10");
        }

        @Test
        void uriWithoutQueryString() {
            when(request.getUri()).thenReturn("/api/v1/users");

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.requestPath()).isEqualTo("/api/v1/users");
            assertThat(msg.queryString()).isNull();
        }

        @Test
        void uriWithEmptyQueryString() {
            when(request.getUri()).thenReturn("/api/v1/users?");

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.requestPath()).isEqualTo("/api/v1/users");
            assertThat(msg.queryString()).isEmpty();
        }

        @Test
        void uriWithMultipleQuestionMarks() {
            when(request.getUri()).thenReturn("/search?q=what?&page=1");

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.requestPath()).isEqualTo("/search");
            assertThat(msg.queryString()).isEqualTo("q=what?&page=1");
        }

        @Test
        void rootUri() {
            when(request.getUri()).thenReturn("/");

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.requestPath()).isEqualTo("/");
            assertThat(msg.queryString()).isNull();
        }
    }

    // ---- T-002-04: Header mapping ----

    @Nested
    class HeaderMapping {

        @Test
        void singleValueHeaders() {
            when(headers.getHeaderFields())
                    .thenReturn(List.of(
                            new HeaderField("Content-Type", "application/json"),
                            new HeaderField("X-Request-Id", "abc-123")));

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.headers().first("content-type")).isEqualTo("application/json");
            assertThat(msg.headers().first("x-request-id")).isEqualTo("abc-123");
        }

        @Test
        void multiValueHeaders() {
            when(headers.getHeaderFields())
                    .thenReturn(List.of(
                            new HeaderField("Accept", "text/html"), new HeaderField("Accept", "application/json")));

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.headers().all("accept")).containsExactly("text/html", "application/json");
            assertThat(msg.headers().first("accept")).isEqualTo("text/html");
        }

        @Test
        void mixedCaseHeadersNormalizedToLowercase() {
            when(headers.getHeaderFields())
                    .thenReturn(List.of(
                            new HeaderField("X-Custom-Header", "value1"),
                            new HeaderField("AUTHORIZATION", "Bearer token")));

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.headers().contains("x-custom-header")).isTrue();
            assertThat(msg.headers().contains("authorization")).isTrue();
        }

        @Test
        void emptyHeaders() {
            when(headers.getHeaderFields()).thenReturn(List.of());

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.headers().isEmpty()).isTrue();
        }
    }

    // ---- T-002-05: Body read + JSON parse fallback ----

    @Nested
    class BodyReadAndParseFallback {

        @Test
        void validJsonBody() {
            byte[] json = "{\"name\":\"alice\"}".getBytes(StandardCharsets.UTF_8);
            when(body.isRead()).thenReturn(true);
            when(body.getContent()).thenReturn(json);

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.body().isEmpty()).isFalse();
            assertThat(msg.body().asString()).isEqualTo("{\"name\":\"alice\"}");
            assertThat(msg.body().mediaType()).isEqualTo(MediaType.JSON);
            assertThat(adapter.isBodyParseFailed()).isFalse();
        }

        @Test
        void emptyBodyContent() {
            when(body.isRead()).thenReturn(true);
            when(body.getContent()).thenReturn(new byte[0]);

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isFalse();
        }

        @Test
        void nullBodyContent() {
            when(body.isRead()).thenReturn(true);
            when(body.getContent()).thenReturn(null);

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isFalse();
        }

        @Test
        void bodyNotReadYetTriggersRead() throws Exception {
            byte[] json = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            when(body.isRead()).thenReturn(false);
            doNothing().when(body).read();
            when(body.getContent()).thenReturn(json);

            Message msg = adapter.wrapRequest(exchange);

            verify(body).read();
            assertThat(msg.body().asString()).isEqualTo("{\"ok\":true}");
            assertThat(adapter.isBodyParseFailed()).isFalse();
        }

        @Test
        void bodyReadThrowsIOException() throws Exception {
            when(body.isRead()).thenReturn(false);
            doThrow(new IOException("disk error")).when(body).read();

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isTrue();
        }

        @Test
        void bodyReadThrowsAccessException() throws Exception {
            when(body.isRead()).thenReturn(false);
            doThrow(new AccessException("body too large", HttpStatus.REQUEST_ENTITY_TOO_LARGE))
                    .when(body)
                    .read();

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isTrue();
        }

        @Test
        void malformedJsonBody() {
            byte[] notJson = "this is not json".getBytes(StandardCharsets.UTF_8);
            when(body.isRead()).thenReturn(true);
            when(body.getContent()).thenReturn(notJson);

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.body().isEmpty()).isTrue();
            assertThat(adapter.isBodyParseFailed()).isTrue();
        }

        @Test
        void bodyParseFailedResetsPerCall() throws Exception {
            // First call: malformed → flag set
            byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
            when(body.isRead()).thenReturn(true);
            when(body.getContent()).thenReturn(notJson);
            adapter.wrapRequest(exchange);
            assertThat(adapter.isBodyParseFailed()).isTrue();

            // Second call: valid JSON → flag reset
            byte[] json = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            when(body.getContent()).thenReturn(json);
            adapter.wrapRequest(exchange);
            assertThat(adapter.isBodyParseFailed()).isFalse();
        }

        @Test
        void bodyParseFailedIsolatedPerThread() throws Exception {
            Exchange exchangeA = mock(Exchange.class);
            Request requestA = mock(Request.class);
            Body bodyA = mock(Body.class);
            Headers headersA = mock(Headers.class);
            when(exchangeA.getRequest()).thenReturn(requestA);
            when(requestA.getBody()).thenReturn(bodyA);
            when(requestA.getHeaders()).thenReturn(headersA);
            when(requestA.getMethod()).thenReturn(Method.POST);
            when(requestA.getUri()).thenReturn("/a");
            when(bodyA.isRead()).thenReturn(true);
            when(bodyA.getContent()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));
            when(headersA.getHeaderFields()).thenReturn(List.of());

            Exchange exchangeB = mock(Exchange.class);
            Request requestB = mock(Request.class);
            Body bodyB = mock(Body.class);
            Headers headersB = mock(Headers.class);
            when(exchangeB.getRequest()).thenReturn(requestB);
            when(requestB.getBody()).thenReturn(bodyB);
            when(requestB.getHeaders()).thenReturn(headersB);
            when(requestB.getMethod()).thenReturn(Method.POST);
            when(requestB.getUri()).thenReturn("/b");
            when(bodyB.isRead()).thenReturn(true);
            when(bodyB.getContent()).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            when(headersB.getHeaderFields()).thenReturn(List.of());

            CountDownLatch aWrapped = new CountDownLatch(1);
            CountDownLatch bWrapped = new CountDownLatch(1);
            AtomicBoolean aFlag = new AtomicBoolean();
            AtomicBoolean bFlag = new AtomicBoolean();

            Thread threadA = new Thread(() -> {
                adapter.wrapRequest(exchangeA);
                aWrapped.countDown();
                try {
                    bWrapped.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                aFlag.set(adapter.isBodyParseFailed());
            });

            Thread threadB = new Thread(() -> {
                try {
                    aWrapped.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                adapter.wrapRequest(exchangeB);
                bFlag.set(adapter.isBodyParseFailed());
                bWrapped.countDown();
            });

            threadA.start();
            threadB.start();
            threadA.join();
            threadB.join();

            assertThat(aFlag.get()).isTrue();
            assertThat(bFlag.get()).isFalse();
        }
    }

    // ---- T-002-06: Request metadata mapping ----

    @Nested
    class RequestMetadataMapping {

        @Test
        void methodIsMappedUppercase() {
            when(request.getMethod()).thenReturn(Method.POST);

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.requestMethod()).isEqualTo("POST");
        }

        @Test
        void statusCodeIsNullForRequest() {
            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.statusCode()).isNull();
        }

        @Test
        void nullIdentityProducesEmptySession() {
            // exchange.getIdentity() returns null (no identity mock set up)
            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.session()).isEqualTo(SessionContext.empty());
            assertThat(msg.session().isEmpty()).isTrue();
        }

        @Test
        void deleteMethod() {
            when(request.getMethod()).thenReturn(Method.DELETE);

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.requestMethod()).isEqualTo("DELETE");
        }

        @Test
        void patchMethod() {
            when(request.getMethod()).thenReturn(Method.PATCH);

            Message msg = adapter.wrapRequest(exchange);

            assertThat(msg.requestMethod()).isEqualTo("PATCH");
        }
    }
}
