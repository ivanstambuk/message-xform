package io.messagexform.core.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Body value object for HTTP messages (DO-001-09, FR-001-14b).
 *
 * <p>
 * Holds raw body bytes and a {@link MediaType}. This is a core-owned port type
 * with zero
 * third-party dependencies — adapters convert gateway-native body formats to
 * {@code MessageBody}
 * using the factory methods (ADR-0032, ADR-0033).
 *
 * <p>
 * The record overrides {@code equals}/{@code hashCode} to use
 * {@link Arrays#equals(byte[],
 * byte[])} for byte content comparison (records use reference equality for
 * arrays by default).
 */
public record MessageBody(byte[] content, MediaType mediaType) {

    private static final MessageBody EMPTY = new MessageBody(new byte[0], MediaType.NONE);

    /** Canonical constructor — normalizes null content to empty byte array. */
    public MessageBody {
        if (content == null) {
            content = new byte[0];
        }
        Objects.requireNonNull(mediaType, "mediaType must not be null; use MediaType.NONE for absent types");
    }

    /** True when content is null or zero-length. */
    public boolean isEmpty() {
        return content.length == 0;
    }

    /** Returns content as a UTF-8 string. */
    public String asString() {
        return new String(content, StandardCharsets.UTF_8);
    }

    /** Content length in bytes. */
    public int size() {
        return content.length;
    }

    // ── Factory methods ──

    /** Creates a JSON body from raw bytes. */
    public static MessageBody json(byte[] content) {
        return new MessageBody(content, MediaType.JSON);
    }

    /** Creates a JSON body from a string (UTF-8 encoded). */
    public static MessageBody json(String content) {
        return new MessageBody(
                content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0], MediaType.JSON);
    }

    /** Returns an empty body (no content, {@link MediaType#NONE}). */
    public static MessageBody empty() {
        return EMPTY;
    }

    /** Creates a body with the given content and media type. */
    public static MessageBody of(byte[] content, MediaType mediaType) {
        return new MessageBody(content, mediaType);
    }

    // ── equals / hashCode (byte-content-aware) ──

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageBody that)) return false;
        return mediaType == that.mediaType && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(content) + mediaType.hashCode();
    }

    @Override
    public String toString() {
        return "MessageBody[" + mediaType + ", " + content.length + " bytes]";
    }
}
