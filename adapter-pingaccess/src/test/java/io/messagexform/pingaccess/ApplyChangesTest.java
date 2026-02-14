package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.*;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.MessageBody;
import io.messagexform.core.model.SessionContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for apply helpers on {@link PingAccessAdapter} (T-002-08, T-002-09,
 * T-002-10, T-002-11, T-002-11a).
 *
 * <p>
 * Covers request-side apply (URI + method), response-side apply (status),
 * header diff strategy with protected-header exclusion, and body replacement
 * with content-type semantics.
 */
class ApplyChangesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PingAccessAdapter adapter;

    private Exchange exchange;
    private Request request;
    private Response response;
    private Headers requestHeaders;
    private Headers responseHeaders;

    @BeforeEach
    void setUp() {
        adapter = new PingAccessAdapter(MAPPER);

        exchange = mock(Exchange.class);
        request = mock(Request.class);
        response = mock(Response.class);
        requestHeaders = mock(Headers.class);
        responseHeaders = mock(Headers.class);

        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);
        when(request.getHeaders()).thenReturn(requestHeaders);
        when(response.getHeaders()).thenReturn(responseHeaders);

        // Default: empty header fields for diff computation
        when(requestHeaders.getHeaderFields()).thenReturn(List.of());
        when(responseHeaders.getHeaderFields()).thenReturn(List.of());
    }

    /**
     * Creates a Message with the given fields. Helper for readability.
     */
    private Message message(
            MessageBody body, HttpHeaders headers, Integer status, String path, String method, String query) {
        return new Message(body, headers, status, path, method, query, SessionContext.empty());
    }

    // ---- T-002-08: Request-side apply logic ----

    @Nested
    class ApplyRequestChanges {

        @Test
        void setsUriFromPathAndQuery() {
            Message msg =
                    message(MessageBody.empty(), HttpHeaders.empty(), null, "/api/v2/items", "GET", "sort=asc&limit=5");

            adapter.applyRequestChanges(msg, exchange, List.of());

            verify(request).setUri("/api/v2/items?sort=asc&limit=5");
        }

        @Test
        void setsUriWithoutQueryWhenNull() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), null, "/api/v1/users", "GET", null);

            adapter.applyRequestChanges(msg, exchange, List.of());

            verify(request).setUri("/api/v1/users");
        }

        @Test
        void setsMethod() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), null, "/test", "PUT", null);

            adapter.applyRequestChanges(msg, exchange, List.of());

            ArgumentCaptor<Method> captor = ArgumentCaptor.forClass(Method.class);
            verify(request).setMethod(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("PUT");
        }

        @Test
        void setsCustomMethod() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), null, "/test", "PATCH", null);

            adapter.applyRequestChanges(msg, exchange, List.of());

            ArgumentCaptor<Method> captor = ArgumentCaptor.forClass(Method.class);
            verify(request).setMethod(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("PATCH");
        }
    }

    // ---- T-002-08 (response side): status code ----

    @Nested
    class ApplyResponseChanges {

        @Test
        void setsStatusCode() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), 201, "/test", "POST", null);

            adapter.applyResponseChanges(msg, exchange, List.of());

            ArgumentCaptor<HttpStatus> captor = ArgumentCaptor.forClass(HttpStatus.class);
            verify(response).setStatus(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo(201);
        }

        @Test
        void setsNon200StatusCode() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), 503, "/test", "GET", null);

            adapter.applyResponseChanges(msg, exchange, List.of());

            ArgumentCaptor<HttpStatus> captor = ArgumentCaptor.forClass(HttpStatus.class);
            verify(response).setStatus(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo(503);
        }

        @Test
        void nullStatusCodeDoesNotCallSetStatus() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), null, "/test", "GET", null);

            adapter.applyResponseChanges(msg, exchange, List.of());

            verify(response, never()).setStatus(any());
        }

        // T-002-11: PA non-standard status code passthrough

        @Test
        void nonStandardStatusCode277() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), 277, "/test", "GET", null);

            adapter.applyResponseChanges(msg, exchange, List.of());

            ArgumentCaptor<HttpStatus> captor = ArgumentCaptor.forClass(HttpStatus.class);
            verify(response).setStatus(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo(277);
        }

        @Test
        void nonStandardStatusCode477() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), 477, "/test", "GET", null);

            adapter.applyResponseChanges(msg, exchange, List.of());

            ArgumentCaptor<HttpStatus> captor = ArgumentCaptor.forClass(HttpStatus.class);
            verify(response).setStatus(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo(477);
        }
    }

    // ---- T-002-11a: SPI applyChanges() safety ----

    @Nested
    class SpiApplyChangesSafety {

        @Test
        void applyChangesThrowsUnsupportedOperationException() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), null, "/test", "GET", null);

            assertThatThrownBy(() -> adapter.applyChanges(msg, exchange))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("applyRequestChanges");
        }
    }

    // ---- T-002-09: Header diff strategy ----

    @Nested
    class HeaderDiff {

        @Test
        void newHeadersAreAdded() {
            // Original: no headers. Transformed: added x-new
            List<String> originalNames = List.of();
            HttpHeaders transformed = HttpHeaders.of(Map.of("x-new", "value1"));
            Message msg = message(MessageBody.empty(), transformed, null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, originalNames);

            verify(requestHeaders).add("x-new", "value1");
        }

        @Test
        void existingHeadersAreUpdated() {
            // Original: x-existing. Transformed: x-existing with new value
            List<String> originalNames = List.of("x-existing");
            HttpHeaders transformed = HttpHeaders.of(Map.of("x-existing", "new-value"));
            Message msg = message(MessageBody.empty(), transformed, null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, originalNames);

            verify(requestHeaders).setValues("x-existing", List.of("new-value"));
        }

        @Test
        void removedHeadersAreDeleted() {
            // Original: x-old. Transformed: no headers
            List<String> originalNames = List.of("x-old");
            HttpHeaders transformed = HttpHeaders.empty();
            Message msg = message(MessageBody.empty(), transformed, null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, originalNames);

            verify(requestHeaders).removeFields("x-old");
        }

        @Test
        void mixedAddUpdateRemove() {
            // Original: keep, remove. Transformed: keep (updated), added.
            List<String> originalNames = List.of("keep", "remove");
            HttpHeaders transformed = HttpHeaders.of(Map.of("keep", "updated-val", "added", "new-val"));
            Message msg = message(MessageBody.empty(), transformed, null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, originalNames);

            verify(requestHeaders).setValues("keep", List.of("updated-val"));
            verify(requestHeaders).removeFields("remove");
            verify(requestHeaders).add("added", "new-val");
        }

        @Test
        void contentLengthExcludedFromDiff() {
            // Original had content-length. Transformed has content-length.
            // Neither add/update/remove should be called for content-length.
            List<String> originalNames = List.of("content-length", "x-keep");
            HttpHeaders transformed = HttpHeaders.of(Map.of("content-length", "999", "x-keep", "val"));
            Message msg = message(MessageBody.empty(), transformed, null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, originalNames);

            // content-length should NOT be added, updated, or removed
            verify(requestHeaders, never()).add(eq("content-length"), anyString());
            verify(requestHeaders, never()).setValues(eq("content-length"), any());
            verify(requestHeaders, never()).removeFields("content-length");
            // x-keep should be updated normally
            verify(requestHeaders).setValues("x-keep", List.of("val"));
        }

        @Test
        void transferEncodingExcludedFromDiff() {
            List<String> originalNames = List.of("transfer-encoding");
            HttpHeaders transformed = HttpHeaders.of(Map.of("transfer-encoding", "chunked"));
            Message msg = message(MessageBody.empty(), transformed, null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, originalNames);

            verify(requestHeaders, never()).add(eq("transfer-encoding"), anyString());
            verify(requestHeaders, never()).setValues(eq("transfer-encoding"), any());
            verify(requestHeaders, never()).removeFields("transfer-encoding");
        }

        @Test
        void responseSideHeaderDiff() {
            // Verify header diff works for response side too
            List<String> originalNames = List.of("x-old");
            HttpHeaders transformed = HttpHeaders.of(Map.of("x-new", "val"));
            Message msg = message(MessageBody.empty(), transformed, 200, "/test", "GET", null);

            adapter.applyResponseChanges(msg, exchange, originalNames);

            verify(responseHeaders).removeFields("x-old");
            verify(responseHeaders).add("x-new", "val");
        }
    }

    // ---- T-002-10: Body replacement + content-type ----

    @Nested
    class BodyReplacement {

        @Test
        void nonEmptyBodyCallsSetBodyContent() {
            byte[] content = "{\"transformed\":true}".getBytes(StandardCharsets.UTF_8);
            Message msg = message(MessageBody.json(content), HttpHeaders.empty(), null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, List.of());

            verify(request).setBodyContent(content);
        }

        @Test
        void nonEmptyBodySetsContentTypeJsonUtf8() {
            byte[] content = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
            Message msg = message(MessageBody.json(content), HttpHeaders.empty(), null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, List.of());

            verify(requestHeaders).setFirstValue("Content-Type", "application/json; charset=utf-8");
        }

        @Test
        void emptyBodyDoesNotCallSetBodyContent() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, List.of());

            verify(request, never()).setBodyContent(any());
        }

        @Test
        void emptyBodyDoesNotModifyContentType() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), null, "/test", "GET", null);

            adapter.applyRequestChanges(msg, exchange, List.of());

            verify(requestHeaders, never()).setFirstValue(eq("Content-Type"), anyString());
        }

        @Test
        void responseBodyReplacement() {
            byte[] content = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            Message msg = message(MessageBody.json(content), HttpHeaders.empty(), 200, "/test", "GET", null);

            adapter.applyResponseChanges(msg, exchange, List.of());

            verify(response).setBodyContent(content);
            verify(responseHeaders).setFirstValue("Content-Type", "application/json; charset=utf-8");
        }

        @Test
        void responseEmptyBodyPassthrough() {
            Message msg = message(MessageBody.empty(), HttpHeaders.empty(), 200, "/test", "GET", null);

            adapter.applyResponseChanges(msg, exchange, List.of());

            verify(response, never()).setBodyContent(any());
            verify(responseHeaders, never()).setFirstValue(eq("Content-Type"), anyString());
        }
    }
}
