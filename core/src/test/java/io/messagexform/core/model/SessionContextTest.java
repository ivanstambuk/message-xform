package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link SessionContext} (FR-001-14d, DO-001-11). */
class SessionContextTest {

    // ── get() ──

    @Test
    void getReturnsAttributeValue() {
        SessionContext ctx = SessionContext.of(Map.of("sub", "user1"));
        assertThat(ctx.get("sub")).isEqualTo("user1");
    }

    @Test
    void getReturnsNullForMissing() {
        SessionContext ctx = SessionContext.of(Map.of("sub", "user1"));
        assertThat(ctx.get("missing")).isNull();
    }

    // ── getString() ──

    @Test
    void getStringReturnsStringValue() {
        SessionContext ctx = SessionContext.of(Map.of("sub", "user1"));
        assertThat(ctx.getString("sub")).isEqualTo("user1");
    }

    @Test
    void getStringReturnsNullForNonString() {
        SessionContext ctx = SessionContext.of(Map.of("count", 42));
        assertThat(ctx.getString("count")).isNull();
    }

    @Test
    void getStringReturnsNullForMissing() {
        SessionContext ctx = SessionContext.of(Map.of("sub", "user1"));
        assertThat(ctx.getString("missing")).isNull();
    }

    // ── has() ──

    @Test
    void hasReturnsTrueForExistingKey() {
        SessionContext ctx = SessionContext.of(Map.of("sub", "user1"));
        assertThat(ctx.has("sub")).isTrue();
    }

    @Test
    void hasReturnsFalseForMissingKey() {
        SessionContext ctx = SessionContext.of(Map.of("sub", "user1"));
        assertThat(ctx.has("missing")).isFalse();
    }

    // ── isEmpty() ──

    @Test
    void emptyContextIsEmpty() {
        assertThat(SessionContext.empty().isEmpty()).isTrue();
    }

    @Test
    void nonEmptyContextIsNotEmpty() {
        SessionContext ctx = SessionContext.of(Map.of("sub", "user1"));
        assertThat(ctx.isEmpty()).isFalse();
    }

    // ── toMap() — defensive copy ──

    @Test
    void toMapReturnsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>();
        original.put("sub", "user1");
        SessionContext ctx = SessionContext.of(original);

        Map<String, Object> copy = ctx.toMap();
        copy.put("injected", "attack");

        // Original context is not affected
        assertThat(ctx.has("injected")).isFalse();
        assertThat(ctx.toMap()).doesNotContainKey("injected");
    }

    @Test
    void toMapContainsAllAttributes() {
        SessionContext ctx = SessionContext.of(Map.of("sub", "user1", "role", "admin"));
        Map<String, Object> map = ctx.toMap();
        assertThat(map).containsEntry("sub", "user1");
        assertThat(map).containsEntry("role", "admin");
    }

    // ── toString() — safe, keys only ──

    @Test
    void toStringShowsKeysOnly() {
        SessionContext ctx = SessionContext.of(Map.of("password", "secret123", "sub", "user1"));
        String s = ctx.toString();
        assertThat(s).contains("password"); // key name visible
        assertThat(s).contains("sub"); // key name visible
        assertThat(s).doesNotContain("secret123"); // value NOT visible
        assertThat(s).doesNotContain("user1"); // value NOT visible
    }

    // ── empty() singleton ──

    @Test
    void emptyReturnsSingleton() {
        assertThat(SessionContext.empty()).isSameAs(SessionContext.empty());
    }

    // ── of(null) ──

    @Test
    void ofNullBehavesLikeEmpty() {
        SessionContext ctx = SessionContext.of(null);
        assertThat(ctx.isEmpty()).isTrue();
        assertThat(ctx.toMap()).isEmpty();
    }

    // ── Constructor defensive copy ──

    @Test
    void originalMapMutationDoesNotAffectContext() {
        Map<String, Object> original = new HashMap<>();
        original.put("sub", "user1");
        SessionContext ctx = SessionContext.of(original);

        original.put("injected", "attack");
        assertThat(ctx.has("injected")).isFalse();
    }
}
