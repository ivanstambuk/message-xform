package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link HttpHeaders} (FR-001-14c, DO-001-10). */
class HttpHeadersTest {

    // ── Case-insensitive lookup ──

    @Test
    void firstIsCaseInsensitive() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Content-Type", "application/json"));
        assertThat(headers.first("content-type")).isEqualTo("application/json");
        assertThat(headers.first("CONTENT-TYPE")).isEqualTo("application/json");
        assertThat(headers.first("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void firstReturnsNullForMissingHeader() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Accept", "text/html"));
        assertThat(headers.first("X-Missing")).isNull();
    }

    // ── all() ──

    @Test
    void allReturnsSingleValueAsList() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Accept", "text/html"));
        assertThat(headers.all("accept")).containsExactly("text/html");
    }

    @Test
    void allReturnsEmptyListForMissing() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Accept", "text/html"));
        assertThat(headers.all("X-Missing")).isEmpty();
    }

    @Test
    void allReturnsMultipleValues() {
        HttpHeaders headers = HttpHeaders.ofMulti(Map.of("set-cookie", List.of("a=1", "b=2")));
        assertThat(headers.all("Set-Cookie")).containsExactly("a=1", "b=2");
    }

    // ── contains() ──

    @Test
    void containsIsCaseInsensitive() {
        HttpHeaders headers = HttpHeaders.of(Map.of("X-Request-ID", "abc"));
        assertThat(headers.contains("x-request-id")).isTrue();
        assertThat(headers.contains("X-REQUEST-ID")).isTrue();
        assertThat(headers.contains("cOnTeNt-TyPe")).isFalse();
    }

    // ── isEmpty() ──

    @Test
    void emptyHeadersIsEmpty() {
        assertThat(HttpHeaders.empty().isEmpty()).isTrue();
    }

    @Test
    void nonEmptyHeadersIsNotEmpty() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Host", "example.com"));
        assertThat(headers.isEmpty()).isFalse();
    }

    // ── toSingleValueMap() ──

    @Test
    void toSingleValueMapKeysAreLowercase() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Content-Type", "json", "Accept", "html"));
        Map<String, String> map = headers.toSingleValueMap();
        assertThat(map).containsKey("content-type");
        assertThat(map).containsKey("accept");
        assertThat(map).doesNotContainKey("Content-Type");
        assertThat(map).doesNotContainKey("Accept");
    }

    // ── toMultiValueMap() ──

    @Test
    void toMultiValueMapKeysAreLowercase() {
        HttpHeaders headers = HttpHeaders.ofMulti(Map.of("Set-Cookie", List.of("a=1", "b=2")));
        Map<String, List<String>> map = headers.toMultiValueMap();
        assertThat(map).containsKey("set-cookie");
        assertThat(map.get("set-cookie")).containsExactly("a=1", "b=2");
    }

    @Test
    void toSingleValueMapFromMultiUsesFirstValue() {
        HttpHeaders headers = HttpHeaders.ofMulti(Map.of("x-multi", List.of("first", "second")));
        assertThat(headers.first("x-multi")).isEqualTo("first");
        assertThat(headers.toSingleValueMap().get("x-multi")).isEqualTo("first");
    }

    // ── ofMulti() ──

    @Test
    void ofMultiPreservesAllValues() {
        HttpHeaders headers = HttpHeaders.ofMulti(Map.of(
                "Accept", List.of("text/html", "application/json"),
                "Host", List.of("example.com")));
        assertThat(headers.all("accept")).containsExactly("text/html", "application/json");
        assertThat(headers.first("host")).isEqualTo("example.com");
    }

    // ── Immutability ──

    @Test
    void toSingleValueMapIsUnmodifiable() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Accept", "text/html"));
        Map<String, String> map = headers.toSingleValueMap();
        assertThatThrownBy(() -> map.put("new", "value")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toMultiValueMapIsUnmodifiable() {
        HttpHeaders headers = HttpHeaders.ofMulti(Map.of("Accept", List.of("text/html")));
        Map<String, List<String>> map = headers.toMultiValueMap();
        assertThatThrownBy(() -> map.put("new", List.of("value"))).isInstanceOf(UnsupportedOperationException.class);
    }

    // ── of() with empty map ──

    @Test
    void ofEmptyMapIsEmpty() {
        HttpHeaders headers = HttpHeaders.of(Map.of());
        assertThat(headers.isEmpty()).isTrue();
    }

    // ── of() from single-value map creates multi-value view ──

    @Test
    void singleValueFactoryCreatesMultiValueView() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Accept", "text/html"));
        assertThat(headers.all("accept")).containsExactly("text/html");
        assertThat(headers.toMultiValueMap().get("accept")).containsExactly("text/html");
    }
}
