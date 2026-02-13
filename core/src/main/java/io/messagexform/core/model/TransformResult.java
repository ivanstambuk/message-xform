package io.messagexform.core.model;

import java.util.Objects;

/**
 * Outcome of applying a transform (DO-001-05). Exactly one of three states:
 *
 * <ul>
 * <li>{@link Type#SUCCESS} — transformation succeeded; {@code message} holds
 * the transformed
 * message.
 * <li>{@link Type#ERROR} — transformation failed; {@code errorResponse} holds
 * the error body and
 * {@code errorStatusCode} the HTTP status.
 * <li>{@link Type#PASSTHROUGH} — no transform matched; the original message is
 * returned
 * unmodified.
 * </ul>
 */
public final class TransformResult {

    /** The type of transform outcome. */
    public enum Type {
        SUCCESS,
        ERROR,
        PASSTHROUGH
    }

    private final Type type;
    private final Message message;
    private final MessageBody errorResponse;
    private final Integer errorStatusCode;
    private final String specId;
    private final String specVersion;

    private TransformResult(
            Type type,
            Message message,
            MessageBody errorResponse,
            Integer errorStatusCode,
            String specId,
            String specVersion) {
        this.type = type;
        this.message = message;
        this.errorResponse = errorResponse;
        this.errorStatusCode = errorStatusCode;
        this.specId = specId;
        this.specVersion = specVersion;
    }

    /** Creates a SUCCESS result with the transformed message (no spec metadata). */
    public static TransformResult success(Message transformedMessage) {
        Objects.requireNonNull(transformedMessage, "transformedMessage must not be null for SUCCESS");
        return new TransformResult(Type.SUCCESS, transformedMessage, null, null, null, null);
    }

    /**
     * Creates a SUCCESS result with the transformed message and spec provenance
     * (T-001-67).
     */
    public static TransformResult success(Message transformedMessage, String specId, String specVersion) {
        Objects.requireNonNull(transformedMessage, "transformedMessage must not be null for SUCCESS");
        return new TransformResult(Type.SUCCESS, transformedMessage, null, null, specId, specVersion);
    }

    /**
     * Creates an ERROR result with an error response body and HTTP status code (no
     * spec metadata).
     */
    public static TransformResult error(MessageBody errorResponse, int errorStatusCode) {
        Objects.requireNonNull(errorResponse, "errorResponse must not be null for ERROR");
        return new TransformResult(Type.ERROR, null, errorResponse, errorStatusCode, null, null);
    }

    /** Creates an ERROR result with spec provenance metadata (T-001-67). */
    public static TransformResult error(
            MessageBody errorResponse, int errorStatusCode, String specId, String specVersion) {
        Objects.requireNonNull(errorResponse, "errorResponse must not be null for ERROR");
        return new TransformResult(Type.ERROR, null, errorResponse, errorStatusCode, specId, specVersion);
    }

    /** Creates a PASSTHROUGH result indicating no transform was applied. */
    public static TransformResult passthrough() {
        return new TransformResult(Type.PASSTHROUGH, null, null, null, null, null);
    }

    public Type type() {
        return type;
    }

    /**
     * Returns the transformed message. Only valid when {@code type() == SUCCESS}.
     */
    public Message message() {
        return message;
    }

    /** Returns the error response body. Only valid when {@code type() == ERROR}. */
    public MessageBody errorResponse() {
        return errorResponse;
    }

    /**
     * Returns the error HTTP status code. Only valid when {@code type() == ERROR}.
     */
    public Integer errorStatusCode() {
        return errorStatusCode;
    }

    /**
     * Returns the matched spec id, or {@code null} if unavailable
     * (PASSTHROUGH or backward-compatible factory).
     */
    public String specId() {
        return specId;
    }

    /**
     * Returns the matched spec version, or {@code null} if unavailable
     * (PASSTHROUGH or backward-compatible factory).
     */
    public String specVersion() {
        return specVersion;
    }

    public boolean isSuccess() {
        return type == Type.SUCCESS;
    }

    public boolean isError() {
        return type == Type.ERROR;
    }

    public boolean isPassthrough() {
        return type == Type.PASSTHROUGH;
    }

    @Override
    public String toString() {
        String specRef = specId != null ? ", spec=" + specId + "@" + specVersion : "";
        return switch (type) {
            case SUCCESS -> "TransformResult[SUCCESS" + specRef + "]";
            case ERROR -> "TransformResult[ERROR, status=" + errorStatusCode + specRef + "]";
            case PASSTHROUGH -> "TransformResult[PASSTHROUGH]";
        };
    }
}
