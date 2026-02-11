package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests for {@link MessageBody} (FR-001-14b, DO-001-09). */
class MessageBodyTest {

    // ── Factory: json(byte[]) ──

    @Test
    void jsonBytesHasJsonMediaType() {
        MessageBody body = MessageBody.json("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(body.mediaType()).isEqualTo(MediaType.JSON);
    }

    @Test
    void jsonBytesPreservesContent() {
        byte[] raw = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        MessageBody body = MessageBody.json(raw);
        assertThat(body.content()).isEqualTo(raw);
    }

    // ── Factory: json(String) ──

    @Test
    void jsonStringHasJsonMediaType() {
        MessageBody body = MessageBody.json("hello");
        assertThat(body.mediaType()).isEqualTo(MediaType.JSON);
    }

    @Test
    void jsonStringAsStringRoundTrips() {
        MessageBody body = MessageBody.json("hello");
        assertThat(body.asString()).isEqualTo("hello");
    }

    // ── Factory: empty() ──

    @Test
    void emptyBodyIsEmpty() {
        assertThat(MessageBody.empty().isEmpty()).isTrue();
    }

    @Test
    void emptyBodyHasSizeZero() {
        assertThat(MessageBody.empty().size()).isEqualTo(0);
    }

    @Test
    void emptyBodyHasNoneMediaType() {
        assertThat(MessageBody.empty().mediaType()).isEqualTo(MediaType.NONE);
    }

    // ── Factory: of(byte[], MediaType) ──

    @Test
    void ofWithXmlMediaType() {
        byte[] content = "<root/>".getBytes(StandardCharsets.UTF_8);
        MessageBody body = MessageBody.of(content, MediaType.XML);
        assertThat(body.mediaType()).isEqualTo(MediaType.XML);
        assertThat(body.asString()).isEqualTo("<root/>");
    }

    // ── size() ──

    @Test
    void sizeMatchesByteLength() {
        MessageBody body = MessageBody.json("abc");
        assertThat(body.size()).isEqualTo(3);
    }

    @Test
    void sizeHandlesMultiByteCharacters() {
        MessageBody body = MessageBody.json("ü"); // 2 bytes in UTF-8
        assertThat(body.size()).isEqualTo(2);
    }

    // ── isEmpty() ──

    @Test
    void nonEmptyBodyIsNotEmpty() {
        assertThat(MessageBody.json("x").isEmpty()).isFalse();
    }

    // ── Null content normalization ──

    @Test
    void nullContentNormalizedToEmptyBytes() {
        MessageBody body = MessageBody.of(null, MediaType.JSON);
        assertThat(body.content()).isEqualTo(new byte[0]);
        assertThat(body.isEmpty()).isTrue();
    }

    // ── equals / hashCode (Arrays.equals, not reference equality) ──

    @Test
    void equalBodiesAreEqual() {
        MessageBody a = MessageBody.json("abc");
        MessageBody b = MessageBody.json("abc");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentContentIsNotEqual() {
        MessageBody a = MessageBody.json("abc");
        MessageBody b = MessageBody.json("xyz");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentMediaTypeIsNotEqual() {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        MessageBody a = MessageBody.of(content, MediaType.JSON);
        MessageBody b = MessageBody.of(content, MediaType.XML);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equalsUsesArraysEqualsNotReference() {
        // Two different byte[] instances with same content
        byte[] arr1 = {1, 2, 3};
        byte[] arr2 = {1, 2, 3};
        assertThat(arr1).isNotSameAs(arr2); // different references
        MessageBody a = MessageBody.of(arr1, MediaType.BINARY);
        MessageBody b = MessageBody.of(arr2, MediaType.BINARY);
        assertThat(a).isEqualTo(b); // but equal by content
    }

    // ── toString ──

    @Test
    void toStringIncludesMediaTypeAndSize() {
        MessageBody body = MessageBody.json("test");
        String s = body.toString();
        assertThat(s).contains("JSON");
        assertThat(s).contains("4"); // 4 bytes
    }
}
