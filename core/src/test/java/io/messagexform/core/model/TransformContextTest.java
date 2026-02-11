package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.testkit.TestMessages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link TransformContext} (DO-001-07). */
class TransformContextTest {

    @Test
    void headersAsJsonReturnsFirstValueMap() {
        var ctx = new TransformContext(
                TestMessages.toHeaders(Map.of("x-request-id", "abc-123", "content-type", "application/json"), null),
                200,
                null,
                null,
                SessionContext.empty());

        var json = TransformEngine.headersToJson(ctx.headers());
        assertThat(json.get("x-request-id").asText()).isEqualTo("abc-123");
        assertThat(json.get("content-type").asText()).isEqualTo("application/json");
    }

    @Test
    void headersAllAsJsonReturnsArrayValues() {
        var ctx = new TransformContext(
                TestMessages.toHeaders(null, Map.of("accept", List.of("application/json", "text/html"))),
                null,
                null,
                null,
                SessionContext.empty());

        var json = TransformEngine.headersAllToJson(ctx.headers());
        assertThat(json.get("accept").isArray()).isTrue();
        assertThat(json.get("accept").get(0).asText()).isEqualTo("application/json");
        assertThat(json.get("accept").get(1).asText()).isEqualTo("text/html");
    }

    @Test
    void statusAsJsonReturnsNumberForResponseTransforms() {
        var ctx = new TransformContext(HttpHeaders.empty(), 404, null, null, SessionContext.empty());

        var json = TransformEngine.statusToJson(ctx.status());
        assertThat(json.isNumber()).isTrue();
        assertThat(json.asInt()).isEqualTo(404);
    }

    @Test
    void statusAsJsonReturnsNullForRequestTransforms() {
        var ctx = new TransformContext(HttpHeaders.empty(), null, null, null, SessionContext.empty());

        var json = TransformEngine.statusToJson(ctx.status());
        assertThat(json.isNull())
                .as("$status must be null for request transforms (ADR-0017)")
                .isTrue();
    }

    @Test
    void queryParamsAsJsonReturnsStringMap() {
        var ctx = new TransformContext(
                HttpHeaders.empty(), null, Map.of("page", "2", "limit", "50"), null, SessionContext.empty());

        var json = TransformEngine.queryParamsToJson(ctx.queryParams());
        assertThat(json.get("page").asText()).isEqualTo("2");
        assertThat(json.get("limit").asText()).isEqualTo("50");
    }

    @Test
    void cookiesAsJsonReturnsStringMap() {
        var ctx = new TransformContext(
                HttpHeaders.empty(), null, null, Map.of("session", "xyz-789"), SessionContext.empty());

        var json = TransformEngine.cookiesToJson(ctx.cookies());
        assertThat(json.get("session").asText()).isEqualTo("xyz-789");
    }

    @Test
    void emptyContextHasEmptyMaps() {
        var ctx = TransformContext.empty();

        assertThat(ctx.headers().toSingleValueMap()).isEmpty();
        assertThat(ctx.headers().toMultiValueMap()).isEmpty();
        assertThat(ctx.status()).isNull();
        assertThat(ctx.queryParams()).isEmpty();
        assertThat(ctx.cookies()).isEmpty();
    }

    @Test
    void mapsAreUnmodifiable() {
        var headers = new java.util.HashMap<String, String>();
        headers.put("x-test", "val");
        var ctx = new TransformContext(TestMessages.toHeaders(headers, null), null, null, null, SessionContext.empty());

        assertThat(ctx.headers().toSingleValueMap()).containsEntry("x-test", "val");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ctx.headers().toSingleValueMap().put("x-new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
