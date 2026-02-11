package io.messagexform.core.model;

import java.util.Objects;

/**
 * Generic HTTP message envelope (DO-001-01, FR-001-04). Gateway adapters
 * produce instances by wrapping their native request/response objects. The
 * engine operates exclusively on {@code Message} — it never touches
 * gateway-native types.
 *
 * <p>
 * All port-type fields ({@code body}, {@code headers}, {@code session})
 * are core-owned value objects with zero third-party dependencies (ADR-0032,
 * ADR-0033). The record is immutable — use {@code with*()} methods to create
 * copies with modified fields.
 */
public record Message(
        MessageBody body,
        HttpHeaders headers,
        Integer statusCode,
        String requestPath,
        String requestMethod,
        String queryString,
        SessionContext session) {

    /** Canonical constructor with validation. */
    public Message {
        Objects.requireNonNull(body, "body must not be null; use MessageBody.empty() for absent bodies");
        if (headers == null) headers = HttpHeaders.empty();
        if (session == null) session = SessionContext.empty();
    }

    // ── with*() copy methods ──

    /** Returns a copy with a different body. */
    public Message withBody(MessageBody newBody) {
        return new Message(newBody, headers, statusCode, requestPath, requestMethod, queryString, session);
    }

    /** Returns a copy with different headers. */
    public Message withHeaders(HttpHeaders newHeaders) {
        return new Message(body, newHeaders, statusCode, requestPath, requestMethod, queryString, session);
    }

    /** Returns a copy with a different status code. */
    public Message withStatusCode(Integer newStatusCode) {
        return new Message(body, headers, newStatusCode, requestPath, requestMethod, queryString, session);
    }

    /** Returns a copy with a different request path. */
    public Message withRequestPath(String newRequestPath) {
        return new Message(body, headers, statusCode, newRequestPath, requestMethod, queryString, session);
    }

    /** Returns a copy with a different request method. */
    public Message withRequestMethod(String newRequestMethod) {
        return new Message(body, headers, statusCode, requestPath, newRequestMethod, queryString, session);
    }

    /** Returns a copy with a different query string. */
    public Message withQueryString(String newQueryString) {
        return new Message(body, headers, statusCode, requestPath, requestMethod, newQueryString, session);
    }

    // ── Derived accessors ──

    /** Returns the media type of the body. */
    public MediaType mediaType() {
        return body.mediaType();
    }

    /**
     * Returns the Content-Type string from the body's media type.
     * Returns {@code null} when the media type is {@link MediaType#NONE}.
     */
    public String contentType() {
        return body.mediaType().value();
    }
}
