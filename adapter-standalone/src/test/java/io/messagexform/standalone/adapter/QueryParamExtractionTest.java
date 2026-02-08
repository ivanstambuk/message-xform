package io.messagexform.standalone.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.messagexform.core.model.TransformContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for query parameter extraction into {@link TransformContext}
 * (T-004-17, FR-004-39, S-004-74/75/76/77).
 */
@DisplayName("StandaloneAdapter — query param extraction")
class QueryParamExtractionTest {

    private StandaloneAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StandaloneAdapter();
    }

    @Test
    @DisplayName("query params extracted into TransformContext (S-004-74)")
    void queryParamsExtracted() {
        Context ctx = mockContext(Map.of(
                "page", List.of("2"),
                "sort", List.of("name")));

        TransformContext tc = adapter.buildTransformContext(ctx);

        assertThat(tc.queryParams()).containsEntry("page", "2");
        assertThat(tc.queryParams()).containsEntry("sort", "name");
        assertThat(tc.queryParams()).hasSize(2);
    }

    @Test
    @DisplayName("no query string → queryParams is empty map (S-004-75)")
    void noQueryStringReturnsEmptyMap() {
        Context ctx = mockContext(Map.of());

        TransformContext tc = adapter.buildTransformContext(ctx);

        assertThat(tc.queryParams()).isNotNull();
        assertThat(tc.queryParams()).isEmpty();
    }

    @Test
    @DisplayName("multi-value param → first value only (S-004-76)")
    void multiValueParamFirstValueOnly() {
        Context ctx = mockContext(Map.of("tag", List.of("a", "b")));

        TransformContext tc = adapter.buildTransformContext(ctx);

        assertThat(tc.queryParams()).containsEntry("tag", "a");
        assertThat(tc.queryParams()).hasSize(1);
    }

    @Test
    @DisplayName("URL-encoded param value → decoded (S-004-77)")
    void urlEncodedParamDecoded() {
        // Javalin's queryParamMap() handles URL decoding for us
        Context ctx = mockContext(Map.of(
                "name", List.of("hello world"),
                "path", List.of("/foo/bar")));

        TransformContext tc = adapter.buildTransformContext(ctx);

        assertThat(tc.queryParams()).containsEntry("name", "hello world");
        assertThat(tc.queryParams()).containsEntry("path", "/foo/bar");
    }

    // ---- Helper ----

    private static Context mockContext(Map<String, List<String>> queryParamMap) {
        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);

        when(ctx.method()).thenReturn(HandlerType.GET);
        when(ctx.path()).thenReturn("/api/data");
        when(ctx.body()).thenReturn("");
        when(ctx.queryString()).thenReturn(null);
        when(ctx.headerMap()).thenReturn(Map.of());
        when(ctx.contentType()).thenReturn(null);
        when(ctx.cookieMap()).thenReturn(Map.of());
        when(ctx.queryParamMap()).thenReturn(queryParamMap);
        when(ctx.req()).thenReturn(req);
        when(req.getHeaderNames()).thenReturn(Collections.enumeration(List.of()));

        return ctx;
    }
}
