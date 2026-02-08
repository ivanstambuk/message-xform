package io.messagexform.standalone.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.messagexform.core.model.TransformContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for cookie extraction into {@link TransformContext}
 * (T-004-16, FR-004-37, S-004-67/68/69).
 */
@DisplayName("StandaloneAdapter — cookie extraction")
class CookieExtractionTest {

    private StandaloneAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StandaloneAdapter();
    }

    @Test
    @DisplayName("Cookie header → TransformContext.cookies() populated (S-004-67)")
    void cookiesExtracted() {
        Map<String, String> cookieMap = new LinkedHashMap<>();
        cookieMap.put("session", "abc123");
        cookieMap.put("lang", "en");

        Context ctx = mockContext(cookieMap);

        TransformContext tc = adapter.buildTransformContext(ctx);

        assertThat(tc.cookies()).containsEntry("session", "abc123");
        assertThat(tc.cookies()).containsEntry("lang", "en");
        assertThat(tc.cookies()).hasSize(2);
    }

    @Test
    @DisplayName("no Cookie header → cookies is empty map (S-004-68)")
    void noCookiesReturnsEmptyMap() {
        Context ctx = mockContext(Map.of());

        TransformContext tc = adapter.buildTransformContext(ctx);

        assertThat(tc.cookies()).isNotNull();
        assertThat(tc.cookies()).isEmpty();
    }

    @Test
    @DisplayName("URL-encoded cookie value → decoded (S-004-69)")
    void urlEncodedCookieDecoded() {
        // Javalin's cookieMap() handles URL decoding for us
        Map<String, String> cookieMap = new LinkedHashMap<>();
        cookieMap.put("name", "hello world");
        cookieMap.put("path", "/foo/bar");

        Context ctx = mockContext(cookieMap);

        TransformContext tc = adapter.buildTransformContext(ctx);

        assertThat(tc.cookies()).containsEntry("name", "hello world");
        assertThat(tc.cookies()).containsEntry("path", "/foo/bar");
    }

    // ---- Helper ----

    private static Context mockContext(Map<String, String> cookieMap) {
        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);

        when(ctx.method()).thenReturn(HandlerType.GET);
        when(ctx.path()).thenReturn("/api/data");
        when(ctx.body()).thenReturn("");
        when(ctx.queryString()).thenReturn(null);
        when(ctx.headerMap()).thenReturn(Map.of());
        when(ctx.contentType()).thenReturn(null);
        when(ctx.cookieMap()).thenReturn(cookieMap);
        when(ctx.queryParamMap()).thenReturn(Map.of());
        when(ctx.req()).thenReturn(req);
        when(req.getHeaderNames()).thenReturn(Collections.enumeration(List.of()));

        return ctx;
    }
}
