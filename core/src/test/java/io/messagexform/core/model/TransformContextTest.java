package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link TransformContext} (DO-001-07). */
class TransformContextTest {

    @Test
    void headersAsJsonReturnsFirstValueMap() {
        var ctx = new TransformContext(
                Map.of("x-request-id", "abc-123", "content-type", "application/json"), null, 200, null, null);

        var json = ctx.headersAsJson();
        assertThat(json.get("x-request-id").asText()).isEqualTo("abc-123");
        assertThat(json.get("content-type").asText()).isEqualTo("application/json");
    }

    @Test
    void headersAllAsJsonReturnsArrayValues() {
        var ctx = new TransformContext(
                null, Map.of("accept", List.of("application/json", "text/html")), null, null, null);

        var json = ctx.headersAllAsJson();
        assertThat(json.get("accept").isArray()).isTrue();
        assertThat(json.get("accept").get(0).asText()).isEqualTo("application/json");
        assertThat(json.get("accept").get(1).asText()).isEqualTo("text/html");
    }

    @Test
    void statusAsJsonReturnsNumberForResponseTransforms() {
        var ctx = new TransformContext(null, null, 404, null, null);

        var json = ctx.statusAsJson();
        assertThat(json.isNumber()).isTrue();
        assertThat(json.asInt()).isEqualTo(404);
    }

    @Test
    void statusAsJsonReturnsNullForRequestTransforms() {
        var ctx = new TransformContext(null, null, null, null, null);

        var json = ctx.statusAsJson();
        assertThat(json.isNull())
                .as("$status must be null for request transforms (ADR-0017)")
                .isTrue();
    }

    @Test
    void queryParamsAsJsonReturnsStringMap() {
        var ctx = new TransformContext(null, null, null, Map.of("page", "2", "limit", "50"), null);

        var json = ctx.queryParamsAsJson();
        assertThat(json.get("page").asText()).isEqualTo("2");
        assertThat(json.get("limit").asText()).isEqualTo("50");
    }

    @Test
    void cookiesAsJsonReturnsStringMap() {
        var ctx = new TransformContext(null, null, null, null, Map.of("session", "xyz-789"));

        var json = ctx.cookiesAsJson();
        assertThat(json.get("session").asText()).isEqualTo("xyz-789");
    }

    @Test
    void emptyContextHasEmptyMaps() {
        var ctx = TransformContext.empty();

        assertThat(ctx.headers()).isEmpty();
        assertThat(ctx.headersAll()).isEmpty();
        assertThat(ctx.status()).isNull();
        assertThat(ctx.queryParams()).isEmpty();
        assertThat(ctx.cookies()).isEmpty();
    }

    @Test
    void mapsAreUnmodifiable() {
        var headers = new java.util.HashMap<String, String>();
        headers.put("x-test", "val");
        var ctx = new TransformContext(headers, null, null, null, null);

        assertThat(ctx.headers()).containsEntry("x-test", "val");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.headers().put("x-new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
