package io.messagexform.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Outcome of applying a transform (DO-001-05). Exactly one of three states:
 *
 * <ul>
 *   <li>{@link Type#SUCCESS} — transformation succeeded; {@code message} holds the transformed
 *       message.
 *   <li>{@link Type#ERROR} — transformation failed; {@code errorResponse} holds the error body and
 *       {@code errorStatusCode} the HTTP status.
 *   <li>{@link Type#PASSTHROUGH} — no transform matched; the original message is returned
 *       unmodified.
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
    private final JsonNode errorResponse;
    private final Integer errorStatusCode;

    private TransformResult(Type type, Message message, JsonNode errorResponse, Integer errorStatusCode) {
        this.type = type;
        this.message = message;
        this.errorResponse = errorResponse;
        this.errorStatusCode = errorStatusCode;
    }

    /** Creates a SUCCESS result with the transformed message. */
    public static TransformResult success(Message transformedMessage) {
        Objects.requireNonNull(transformedMessage, "transformedMessage must not be null for SUCCESS");
        return new TransformResult(Type.SUCCESS, transformedMessage, null, null);
    }

    /** Creates an ERROR result with an error response body and HTTP status code. */
    public static TransformResult error(JsonNode errorResponse, int errorStatusCode) {
        Objects.requireNonNull(errorResponse, "errorResponse must not be null for ERROR");
        return new TransformResult(Type.ERROR, null, errorResponse, errorStatusCode);
    }

    /** Creates a PASSTHROUGH result indicating no transform was applied. */
    public static TransformResult passthrough() {
        return new TransformResult(Type.PASSTHROUGH, null, null, null);
    }

    public Type type() {
        return type;
    }

    /** Returns the transformed message. Only valid when {@code type() == SUCCESS}. */
    public Message message() {
        return message;
    }

    /** Returns the error response body. Only valid when {@code type() == ERROR}. */
    public JsonNode errorResponse() {
        return errorResponse;
    }

    /** Returns the error HTTP status code. Only valid when {@code type() == ERROR}. */
    public Integer errorStatusCode() {
        return errorStatusCode;
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
        return switch (type) {
            case SUCCESS -> "TransformResult[SUCCESS]";
            case ERROR -> "TransformResult[ERROR, status=" + errorStatusCode + "]";
            case PASSTHROUGH -> "TransformResult[PASSTHROUGH]";
        };
    }
}
