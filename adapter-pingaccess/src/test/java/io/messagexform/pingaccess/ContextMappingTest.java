package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.*;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformContext;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PingAccessAdapter#buildTransformContext} (T-002-17,
 * FR-002-13).
 *
 * <p>
 * Verifies headers, query params, cookies, status, and session mapping,
 * including the {@code URISyntaxException} fallback.
 */
class ContextMappingTest {

    private PingAccessAdapter adapter;
    private Exchange exchange;
    private Request request;
    private Headers headers;

    @BeforeEach
    void setUp() {
        adapter = new PingAccessAdapter(new ObjectMapper());
        exchange = mock(Exchange.class);
        request = mock(Request.class);
        headers = mock(Headers.class);

        when(exchange.getRequest()).thenReturn(request);
        when(request.getHeaders()).thenReturn(headers);

        // Default: no header fields, no query params, no cookies
        when(headers.getHeaderFields()).thenReturn(List.of());
        when(headers.getCookies()).thenReturn(Map.of());
    }

    // ---- Headers mapping ----

    @Nested
    class HeadersMapping {

        @Test
        void headersAreMappedFromExchange() throws Exception {
            HeaderField hf1 = new HeaderField("Content-Type", "application/json");
            HeaderField hf2 = new HeaderField("X-Custom", "value1");
            when(headers.getHeaderFields()).thenReturn(List.of(hf1, hf2));
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.headers().first("content-type")).isEqualTo("application/json");
            assertThat(ctx.headers().first("x-custom")).isEqualTo("value1");
        }

        @Test
        void multiValueHeadersAreAvailable() throws Exception {
            HeaderField hf1 = new HeaderField("Accept", "text/html");
            HeaderField hf2 = new HeaderField("Accept", "application/json");
            when(headers.getHeaderFields()).thenReturn(List.of(hf1, hf2));
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            // Single-value view returns the first value
            assertThat(ctx.headers().first("accept")).isEqualTo("text/html");
            // Multi-value view returns all values
            assertThat(ctx.headers().all("accept")).containsExactly("text/html", "application/json");
        }
    }

    // ---- Query params mapping ----

    @Nested
    class QueryParamsMapping {

        @Test
        void queryParamsFirstValueSemantics() throws Exception {
            Map<String, String[]> queryMap = new LinkedHashMap<>();
            queryMap.put("page", new String[] {"2", "3"});
            queryMap.put("limit", new String[] {"50"});
            when(request.getQueryStringParams()).thenReturn(queryMap);

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.queryParams()).containsEntry("page", "2");
            assertThat(ctx.queryParams()).containsEntry("limit", "50");
        }

        @Test
        void emptyQueryParams() throws Exception {
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.queryParams()).isEmpty();
        }

        @Test
        void nullQueryParams() throws Exception {
            when(request.getQueryStringParams()).thenReturn(null);

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.queryParams()).isEmpty();
        }

        @Test
        void uriSyntaxExceptionFallsBackToEmptyMap() throws Exception {
            when(request.getQueryStringParams()).thenThrow(new URISyntaxException("bad%query", "Malformed"));

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.queryParams()).isEmpty();
        }
    }

    // ---- Cookies mapping ----

    @Nested
    class CookiesMapping {

        @Test
        void cookiesFirstValueSemantics() throws Exception {
            Map<String, String[]> cookieMap = new LinkedHashMap<>();
            cookieMap.put("sessionToken", new String[] {"abc123", "def456"});
            cookieMap.put("lang", new String[] {"en"});
            when(headers.getCookies()).thenReturn(cookieMap);
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.cookies()).containsEntry("sessionToken", "abc123");
            assertThat(ctx.cookies()).containsEntry("lang", "en");
        }

        @Test
        void emptyCookies() throws Exception {
            when(headers.getCookies()).thenReturn(Map.of());
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.cookies()).isEmpty();
        }

        @Test
        void nullCookies() throws Exception {
            when(headers.getCookies()).thenReturn(null);
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.cookies()).isEmpty();
        }
    }

    // ---- Status mapping ----

    @Nested
    class StatusMapping {

        @Test
        void requestPhaseStatusIsNull() throws Exception {
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.status()).isNull();
        }

        @Test
        void responsePhaseStatusIsMapped() throws Exception {
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, 200, null);

            assertThat(ctx.status()).isEqualTo(200);
        }

        @Test
        void nonStandardStatusCodePassesThrough() throws Exception {
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, 477, null);

            assertThat(ctx.status()).isEqualTo(477);
        }
    }

    // ---- Session context mapping ----

    @Nested
    class SessionMapping {

        @Test
        void nullSessionDefaultsToEmpty() throws Exception {
            when(request.getQueryStringParams()).thenReturn(Map.of());

            TransformContext ctx = adapter.buildTransformContext(exchange, null, null);

            assertThat(ctx.session()).isNotNull();
            assertThat(ctx.session().isEmpty()).isTrue();
        }

        @Test
        void providedSessionIsPassedThrough() throws Exception {
            when(request.getQueryStringParams()).thenReturn(Map.of());
            SessionContext session = SessionContext.of(Map.of("sub", "user123", "role", "admin"));

            TransformContext ctx = adapter.buildTransformContext(exchange, null, session);

            assertThat(ctx.session().get("sub")).isEqualTo("user123");
            assertThat(ctx.session().get("role")).isEqualTo("admin");
        }
    }
}
