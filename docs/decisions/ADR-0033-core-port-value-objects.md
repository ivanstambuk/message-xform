# ADR-0033 – Core-Owned Port Value Objects

Date: 2026-02-11 | Status: Accepted

Companion to: ADR-0032 (Core Anti-Corruption Layer)

## Context

ADR-0032 establishes that core's public API must not expose third-party types
(Jackson `JsonNode`, SLF4J, etc.). The immediate question is: what types
SHOULD core's API expose?

Three design levels were considered:

### Level 1 — Raw Primitives

```java
public record Message(
    byte[] body,
    Map<String, String> headers,
    Map<String, List<String>> headersAll,
    Integer statusCode,
    String contentType,
    ...
    Map<String, Object> sessionContext
)
```

Problems:
- `byte[]` has no semantics — format? encoding? charset?
- `Map<String, String>` doesn't enforce HTTP header case-insensitivity.
- `contentType` duplicates `headers.get("content-type")` — divergence risk.
- Two header maps (`headers` + `headersAll`) is a leaky abstraction.
- No validation, no factory methods, no convenience accessors.
- Every adapter reimplements the same boilerplate (lowercase headers,
  handle nulls, extract content type).

### Level 2 — Core-Owned Value Objects (chosen)

Core defines its own domain types: `MessageBody`, `HttpHeaders`,
`SessionContext`. These are pure Java records/classes with zero third-party
dependencies. They encode domain invariants (header case-insensitivity,
body media type tracking, session type-safe access) and provide factory
methods that guide adapter developers toward correct usage.

### Level 3 — Core-Owned Interfaces

Same as Level 2 but with interfaces instead of concrete types, allowing
multiple implementations (e.g., `LazyMessageBody`, `StreamingMessageBody`).

Rejected as premature: the message transformation domain doesn't require
implementation polymorphism for port types. Records provide immutability,
clear constructors, and `equals`/`hashCode` for free. Interfaces can be
introduced later (by extracting from records) if needed without breaking
the API — records can implement interfaces.

## Decision

We adopt **Level 2 — Core-Owned Value Objects**.

Core's port boundary consists of the following types, all defined in
`io.messagexform.core.model` with **zero third-party dependencies**:

### MessageBody

Wraps raw bytes with content metadata. Replaces `JsonNode body` and the
standalone `contentType` field.

```java
package io.messagexform.core.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable representation of an HTTP message body.
 * Carries raw bytes alongside media type metadata.
 *
 * <p>Core's internal engine parses these bytes into its own (relocated)
 * Jackson tree model for JSLT processing. Adapters never need Jackson —
 * they provide bytes from their gateway SDK and receive bytes back.
 */
public record MessageBody(byte[] content, MediaType mediaType) {

    /** Canonical constructor with null-safety. */
    public MessageBody {
        content = content != null ? content : new byte[0];
        mediaType = mediaType != null ? mediaType : MediaType.NONE;
    }

    /** True if the body has no content. */
    public boolean isEmpty() {
        return content.length == 0;
    }

    /** Returns body as a UTF-8 string. */
    public String asString() {
        return new String(content, StandardCharsets.UTF_8);
    }

    /** Returns the raw byte length. */
    public int size() {
        return content.length;
    }

    // ── Factory methods ─────────────────────────────────────

    /** JSON body from raw bytes. */
    public static MessageBody json(byte[] content) {
        return new MessageBody(content, MediaType.JSON);
    }

    /** JSON body from a UTF-8 string. */
    public static MessageBody json(String content) {
        Objects.requireNonNull(content, "content must not be null");
        return new MessageBody(content.getBytes(StandardCharsets.UTF_8), MediaType.JSON);
    }

    /** Empty body (HTTP 204, HEAD responses, etc.). */
    public static MessageBody empty() {
        return new MessageBody(new byte[0], MediaType.NONE);
    }

    /** Body from raw bytes with explicit media type. */
    public static MessageBody of(byte[] content, MediaType mediaType) {
        return new MessageBody(content, mediaType);
    }

    // ── equals/hashCode over content ────────────────────────

    @Override
    public boolean equals(Object o) {
        return o instanceof MessageBody other
            && mediaType == other.mediaType
            && Arrays.equals(content, other.content);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(content) + mediaType.hashCode();
    }
}
```

### MediaType

Simple enum for body format classification. No dependency on
`jakarta.ws.rs.core.MediaType` or any other library.

```java
package io.messagexform.core.model;

/**
 * Supported media types for message bodies.
 * Core uses this to determine parsing strategy (JSON, XML, etc.).
 */
public enum MediaType {
    JSON("application/json"),
    XML("application/xml"),
    FORM("application/x-www-form-urlencoded"),
    TEXT("text/plain"),
    BINARY("application/octet-stream"),
    NONE(null);

    private final String value;

    MediaType(String value) { this.value = value; }

    /** Returns the MIME type string, or null for NONE. */
    public String value() { return value; }

    /**
     * Resolves a Content-Type header value to a MediaType.
     * Ignores parameters (charset, boundary, etc.).
     */
    public static MediaType fromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return NONE;
        String ct = contentType.split(";")[0].trim().toLowerCase();
        return switch (ct) {
            case "application/json" -> JSON;
            case "application/xml", "text/xml" -> XML;
            case "application/x-www-form-urlencoded" -> FORM;
            case "text/plain" -> TEXT;
            default -> BINARY;
        };
    }
}
```

### HttpHeaders

Immutable, case-insensitive HTTP header collection. Replaces both
`Map<String, String> headers` and `Map<String, List<String>> headersAll`.

```java
package io.messagexform.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Immutable, case-insensitive HTTP header collection.
 *
 * <p>Invariant: all header names are stored in lowercase. Construction
 * from any Map normalizes keys automatically. Adapters never need to
 * worry about case sensitivity.
 */
public final class HttpHeaders {

    private static final HttpHeaders EMPTY = new HttpHeaders(Map.of());

    private final Map<String, List<String>> entries;

    private HttpHeaders(Map<String, List<String>> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** Returns the first value for the given header name (case-insensitive). */
    public String first(String name) {
        List<String> values = entries.get(name.toLowerCase());
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    /** Returns all values for the given header name (case-insensitive). */
    public List<String> all(String name) {
        return entries.getOrDefault(name.toLowerCase(), List.of());
    }

    /** True if the header is present (case-insensitive). */
    public boolean contains(String name) {
        return entries.containsKey(name.toLowerCase());
    }

    /**
     * Returns a single-value map (first value per header).
     * Useful for legacy code that expects Map<String, String>.
     */
    public Map<String, String> toSingleValueMap() {
        return entries.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty())
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get(0),
                (a, b) -> a,
                LinkedHashMap::new
            ));
    }

    /** Returns the full multi-value map (immutable). */
    public Map<String, List<String>> toMultiValueMap() {
        return entries;
    }

    /** True if no headers are present. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    // ── Factory methods ─────────────────────────────────────

    /** Creates from a single-value map (keys are lowercased). */
    public static HttpHeaders of(Map<String, String> singleValue) {
        if (singleValue == null || singleValue.isEmpty()) return EMPTY;
        Map<String, List<String>> multi = new TreeMap<>();
        singleValue.forEach((k, v) -> multi.put(k.toLowerCase(), List.of(v)));
        return new HttpHeaders(multi);
    }

    /** Creates from a multi-value map (keys are lowercased). */
    public static HttpHeaders ofMulti(Map<String, List<String>> multiValue) {
        if (multiValue == null || multiValue.isEmpty()) return EMPTY;
        Map<String, List<String>> normalized = new TreeMap<>();
        multiValue.forEach((k, v) ->
            normalized.put(k.toLowerCase(), List.copyOf(v)));
        return new HttpHeaders(normalized);
    }

    /** Empty headers. */
    public static HttpHeaders empty() {
        return EMPTY;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HttpHeaders other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "HttpHeaders" + entries;
    }
}
```

### SessionContext

Type-safe access to gateway-provided session attributes. Replaces
`JsonNode sessionContext` and `Map<String, Object> sessionContext`.

```java
package io.messagexform.core.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable gateway session context (FR-001-13, ADR-0030).
 *
 * <p>Adapters populate this from their native session APIs by converting
 * gateway-specific types (PA's JsonNode, Kong's table, etc.) into a plain
 * {@code Map<String, Object>}. Core binds this as {@code $session} in
 * JSLT expressions — the internal (relocated) Jackson ObjectMapper
 * converts the Map to a JsonNode for JSLT evaluation.
 *
 * <p>Values must be JSON-compatible types: String, Number, Boolean,
 * null, List, or nested Map<String, Object>.
 */
public final class SessionContext {

    private static final SessionContext EMPTY = new SessionContext(Map.of());

    private final Map<String, Object> attributes;

    private SessionContext(Map<String, Object> attributes) {
        this.attributes = Collections.unmodifiableMap(attributes);
    }

    /** Returns the value for the given key, or null if absent. */
    public Object get(String key) {
        return attributes.get(key);
    }

    /** Returns the value as a String, or null if absent or not a String. */
    public String getString(String key) {
        Object v = attributes.get(key);
        return v instanceof String s ? s : null;
    }

    /** True if the key is present. */
    public boolean has(String key) {
        return attributes.containsKey(key);
    }

    /** True if no attributes are present. */
    public boolean isEmpty() {
        return attributes.isEmpty();
    }

    /** Returns the underlying map (immutable). */
    public Map<String, Object> toMap() {
        return attributes;
    }

    // ── Factory methods ─────────────────────────────────────

    /** Creates from a map of attributes. Null-safe. */
    public static SessionContext of(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) return EMPTY;
        return new SessionContext(Map.copyOf(attributes));
    }

    /** Empty session context. */
    public static SessionContext empty() {
        return EMPTY;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SessionContext other
            && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
        return attributes.hashCode();
    }

    @Override
    public String toString() {
        return "SessionContext{keys=" + attributes.keySet() + "}";
    }
}
```

### Updated Message Record

```java
package io.messagexform.core.model;

import java.util.Objects;

/**
 * Immutable representation of an HTTP message (request or response)
 * flowing through the transform engine.
 *
 * <p>All fields use core-owned value objects — no third-party types.
 * Gateway adapters construct Messages using factory methods on each
 * value type (e.g., {@code MessageBody.json(bytes)},
 * {@code HttpHeaders.of(map)}).
 */
public record Message(
    MessageBody body,
    HttpHeaders headers,
    Integer statusCode,
    String requestPath,
    String requestMethod,
    String queryString,
    SessionContext session
) {
    /** Canonical constructor with defaults. */
    public Message {
        body = body != null ? body : MessageBody.empty();
        headers = headers != null ? headers : HttpHeaders.empty();
        session = session != null ? session : SessionContext.empty();
    }

    /** Returns a new Message with a different body. */
    public Message withBody(MessageBody newBody) {
        return new Message(newBody, headers, statusCode, requestPath,
            requestMethod, queryString, session);
    }

    /** Returns a new Message with different headers. */
    public Message withHeaders(HttpHeaders newHeaders) {
        return new Message(body, newHeaders, statusCode, requestPath,
            requestMethod, queryString, session);
    }

    /** Returns a new Message with a different status code. */
    public Message withStatusCode(Integer newStatusCode) {
        return new Message(body, headers, newStatusCode, requestPath,
            requestMethod, queryString, session);
    }

    /** Convenience: media type from body. */
    public MediaType mediaType() {
        return body.mediaType();
    }

    /** Convenience: content-type header value from body. */
    public String contentType() {
        return body.mediaType().value();
    }
}
```

### Updated TransformResult

```java
public final class TransformResult {
    public enum Type { SUCCESS, ERROR, PASSTHROUGH }

    private final Type type;
    private final Message message;
    private final MessageBody errorResponse;
    private final Integer errorStatusCode;

    public static TransformResult success(Message transformedMessage) { ... }
    public static TransformResult error(MessageBody errorBody, int statusCode) { ... }
    public static TransformResult passthrough() { ... }
}
```

### Adapter Usage — How Clean It Becomes

```java
// ── PingAccess adapter ──────────────────────────────────────
byte[] body = exchange.getRequest().getBody().getContent();
Map<String, String> hdrs = extractHeaders(exchange);
Map<String, Object> session = paObjectMapper.convertValue(
    identity.getAttributes(), new TypeReference<>() {});

Message msg = new Message(
    MessageBody.json(body),            // factory guides correct usage
    HttpHeaders.of(hdrs),              // auto-lowercases keys
    exchange.getResponse().getStatus(),
    exchange.getRequest().getUrl().getPath(),
    exchange.getRequest().getMethod(),
    exchange.getRequest().getUrl().getQuery(),
    SessionContext.of(session)         // null-safe
);

TransformResult result = engine.transform(msg, Direction.RESPONSE);

if (result.isSuccess()) {
    exchange.getResponse().getBody()
        .setContent(result.message().body().content());
}


// ── Standalone adapter ──────────────────────────────────────
Message msg = new Message(
    MessageBody.json(ctx.bodyAsBytes()),
    HttpHeaders.of(ctx.headerMap()),
    null,  // no status on request
    ctx.path(),
    ctx.method().name(),
    ctx.queryString(),
    SessionContext.empty()
);


// ── Kong adapter (future, hypothetical) ─────────────────────
Message msg = new Message(
    MessageBody.json(kong.request.getRawBody().getBytes()),
    HttpHeaders.of(kong.request.getHeaders()),
    null,
    kong.request.getPathWithQuery(),
    kong.request.getMethod(),
    null,
    SessionContext.of(kong.ctx.shared)
);
```

All three adapters follow the same pattern. The port types **guide** correct
construction — `MessageBody.json()`, `HttpHeaders.of()`, `SessionContext.of()`
make the intent explicit and the invariants automatic.

## Consequences

Positive:
- **Self-documenting API** — `MessageBody.json(bytes)` is clearer than
  `new byte[]` + a separate `contentType` string.
- **Invariant enforcement** — `HttpHeaders` guarantees lowercase keys;
  `SessionContext` guarantees immutability.
- **Type safety** — `MessageBody` vs `byte[]` are distinct types; compiler
  catches misuse.
- **Adapter simplicity** — factory methods guide correct construction.
  No boilerplate for header normalization, null handling, etc.
- **Evolvable** — adding `MessageBody.compressed()` or
  `HttpHeaders.withAdded(name, value)` doesn't break existing call sites.
- **Testable** — `Message` with `MessageBody.json("{}")` is cleaner than
  `objectMapper.readTree("{}")` in every test.
- **Zero dependencies** — all types are pure Java. No Jackson, no Jakarta,
  no SLF4J.
- **Future-proof** — records can implement interfaces later if Level 3 is
  ever needed (e.g., `record MessageBody implements Body`).

Negative / trade-offs:
- **More types** — 4 new classes (`MessageBody`, `MediaType`, `HttpHeaders`,
  `SessionContext`). Mitigated by: they're small (~50-80 lines each), live
  in the same `model` package, and replace scattered raw-type logic.
- **Refactor volume** — every test that constructs a `Message` must use the
  new factories. Mitigated by: factory methods make tests more readable.
- **Learning curve** — contributors must learn the port types. Mitigated by:
  Javadoc, factory methods, and IDE autocomplete make discovery easy.

References:
- ADR-0032: Core Anti-Corruption Layer (byte boundary)
- Evans, E. (2003). *Domain-Driven Design*. Ch. 5: "Value Objects"
- Cockburn, A. (2005). *Hexagonal Architecture* (Ports and Adapters)
- Fowler, M. (2002). *Patterns of Enterprise Application Architecture*.
  "Value Object" pattern.
