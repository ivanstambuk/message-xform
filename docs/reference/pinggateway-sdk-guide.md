# PingGateway SDK Implementation Guide

> Single-source reference for implementing PingGateway 2025.11 Java extensions.  
> Sourced from the official PingGateway 2025.11 documentation, bundled code  
> samples (`SampleFilter.java`, `SampleClassAliasResolver.java`), the  
> [PingGateway 2025.11.1 Aggregated Javadoc](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/index.html), and the [PingGateway Reference](https://docs.pingidentity.com/pinggateway/2025.11/reference/preface.html).  
> This document is optimized for LLM context — each section is self-contained.  
> **Goal:** This guide should be sufficient without consulting the Javadoc directly.  
> **Completeness:** Incorporates all extension-relevant content from the official  
> PingGateway 2025.11 documentation chapter and the public Javadoc.  
> The official Javadoc is available for deep-dive reference.

---

---

## Table of Contents

### Part I — Core SPI & Data Model
1. [Filter & Handler SPI](#1-filter--handler-spi)
2. [Request, Response & Message Model](#2-request-response--message-model)
3. [Context Chain & Session](#3-context-chain--session)
4. [Promise API & Async Patterns](#4-promise-api--async-patterns)

### Part II — Configuration & Lifecycle
5. [Heaplet Configuration & Lifecycle](#5-heaplet-configuration--lifecycle)
6. [Route & Chain Configuration](#6-route--chain-configuration)
7. [Expression Language & ExpressionPlugin](#7-expression-language--expressionplugin)
8. [Decorator Model](#8-decorator-model)
9. [Deployment, Classloading & SPI Registration](#9-deployment-classloading--spi-registration)

### Part III — Cross-Cutting Concerns
10. [Error Handling & Response Construction](#10-error-handling--response-construction)
11. [Thread Safety & Concurrency Patterns](#11-thread-safety--concurrency-patterns)
12. [Testing Patterns](#12-testing-patterns)
13. [Observability (Logging, Metrics & Audit)](#13-observability-logging-metrics--audit)

### Part IV — Implementation Patterns Catalog
14. [Routing & Dispatch Patterns](#14-routing--dispatch-patterns)
15. [Request & Response Transformation Patterns](#15-request--response-transformation-patterns)
16. [Security Patterns (Auth, CORS, CSRF, JWT)](#16-security-patterns-auth-cors-csrf-jwt)
17. [Resilience Patterns (Retry, Circuit Breaker, Throttling)](#17-resilience-patterns-retry-circuit-breaker-throttling)
18. [Session & State Management Patterns](#18-session--state-management-patterns)
19. [Async Composition & Caching Patterns](#19-async-composition--caching-patterns)
20. [Lifecycle & Decorator Patterns](#20-lifecycle--decorator-patterns)
21. [URI Rewriting & Proxying Patterns](#21-uri-rewriting--proxying-patterns)
22. [Script Extensibility & Entity Processing Patterns](#22-script-extensibility--entity-processing-patterns)

### Part V — Reference
23. [API Quick Reference](#23-api-quick-reference)
24. [Utility Classes Reference](#24-utility-classes-reference)
25. [Filters & Handlers Catalog](#25-filters--handlers-catalog)
26. [Key Differences from PingAccess & PingFederate](#26-key-differences-from-pingaccess--pingfederate)
27. [Maven Dependencies & External Resources](#27-maven-dependencies--external-resources)


---

# Part I — Core SPI & Data Model

---

## 1. Filter & Handler SPI

### Filter Interface

The primary extension point for intercepting and transforming HTTP traffic.

```java
// org.forgerock.http.Filter
// Interface Stability: Evolving
public interface Filter {
    Promise<Response, NeverThrowsException> filter(
        Context context,
        Request request,
        Handler next
    );
}
```

A `Filter` processes a request before handing it off to the next element in the
chain, in a similar way to an interceptor programming model. The `filter()`
method receives a `Context`, a `Request`, and the `Handler` (next filter or
handler to dispatch to). It returns a `Promise` that provides access to the
`Response`.

### Key Behavioral Contracts

1. **A filter can modify the request** before calling `next.handle()`.
2. **A filter can modify the response** in the promise chain (via `thenOnResult()`).
3. **A filter can short-circuit** by NOT calling `next.handle()` — it creates
   its own `Response` and returns it directly. This is the DENY pattern.
4. **A filter can replace a response** with another of its own in the
   `thenOnResult()` callback.
5. **A filter must make no assumptions about which chain it is in** — the only
   valid use of the `next` handler is to call `next.handle(context, request)`.
6. **A filter can exist in more than one chain** — it must be stateless with
   respect to chain identity.

### Single-Method Bidirectional Pattern

Unlike PingAccess (separate `handleRequest`/`handleResponse`), PingGateway
filters use a **single `filter()` method** with promise chaining:

```java
public class MessageTransformFilter implements Filter {
    @Override
    public Promise<Response, NeverThrowsException> filter(
            Context context, Request request, Handler next) {

        // ── REQUEST PHASE ──
        // Transform the request before forwarding
        transformRequest(context, request);

        // ── Forward to next handler ──
        return next.handle(context, request)
            // ── RESPONSE PHASE ──
            .thenOnResult(response -> {
                transformResponse(context, request, response);
            });
    }
}
```

The `request` parameter is available in the `thenOnResult()` closure because
it is captured from the outer `filter()` scope.

### Full SampleFilter (from official documentation)

```java
package org.forgerock.openig.doc.examples;

import org.forgerock.http.Filter;
import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.openig.heap.GenericHeaplet;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.services.context.Context;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;

public class SampleFilter implements Filter {
    String name;   // Header name — set by Heaplet
    String value;  // Header value — set by Heaplet

    @Override
    public Promise<Response, NeverThrowsException> filter(
            final Context context,
            final Request request,
            final Handler next) {
        // Set header in the request.
        request.getHeaders().put(name, value);
        // Pass to the next filter or handler in the chain.
        return next.handle(context, request)
            .thenOnResult(response -> {
                // Set header in the response.
                response.getHeaders().put(name, value);
            });
    }

    public static class Heaplet extends GenericHeaplet {
        @Override
        public Object create() throws HeapException {
            SampleFilter filter = new SampleFilter();
            filter.name = config.get("name")
                .as(evaluatedWithHeapProperties())
                .required()
                .asString();
            filter.value = config.get("value")
                .as(evaluatedWithHeapProperties())
                .required()
                .asString();
            return filter;
        }
    }
}
```

### Async Composition Patterns

PingGateway filters follow consistent async composition patterns.
Understanding these is essential for writing production-quality filters.

#### Bindings Construction

All expression evaluation requires `Bindings` — the runtime context for
expression resolution. The bindings change depending on the processing phase:

```java
// Request phase — only context and request available:
Bindings reqBindings = Bindings.bindings(context, request);

// Response phase — context, request, AND response available:
Bindings resBindings = Bindings.bindings(context, request, response);
```

#### MessageType Dispatch Pattern

Built-in filters that operate on a configurable message direction
(`REQUEST` or `RESPONSE`) use a `MessageType` enum to select the
processing phase:

```java
public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    if (this.messageType == MessageType.REQUEST) {
        // Process request, then forward
        return processMessage(request, Bindings.bindings(context, request))
            .thenAsync(unused -> next.handle(context, request));
    }
    // Forward first, then process response
    return next.handle(context, request)
        .thenAsync(AsyncFunctions.asyncResultHandler(response ->
            processMessage(response, Bindings.bindings(context, request, response))));
}
```

> **`AsyncFunctions.asyncResultHandler()`** is an internal utility that
> wraps a response-processing function into the correct `thenAsync()`
> callback shape. It handles the response transformation and returns the
> modified response as a resolved `Promise`.

#### Dual-List Pattern (onRequest + onResponse)

Filters that perform different operations on request vs response maintain
separate action lists for each phase:

```java
private final List<Action> onRequest;
private final List<Action> onResponse;

public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    return evalActions(onRequest.iterator(), Bindings.bindings(context, request))
        .thenAsync(unused -> next.handle(context, request))
        .thenAsync(AsyncFunctions.asyncResultHandler(response ->
            evalActions(onResponse.iterator(),
                Bindings.bindings(context, request, response))));
}
```

This pattern is used by `AssignmentFilter` and `SwitchFilter`.

#### Conditional Filter Delegation

A condition-guarded filter delegates to the wrapped filter only when the
condition is met — otherwise it bypasses entirely:

```java
// ConditionalFilter pattern — condition-guarded filter delegation
public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    return condition.test(Bindings.bindings(context, request))
        .thenAsync(isTrue -> isTrue
            ? delegate.filter(context, request, next)   // apply filter
            : next.handle(context, request));            // bypass
}
```

#### Condition + Failure Handler Pattern

Access-control filters test a condition and route to a dedicated failure
handler when the check fails:

```java
// ConditionEnforcementFilter pattern — gate with failure handler
public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    return condition.test(Bindings.bindings(context, request))
        .thenAsync(isVerified ->
            (isVerified ? next : failureHandler).handle(context, request));
}
// Default failureHandler = Handlers.FORBIDDEN (returns 403)
```

#### Request-Copy for Response-Phase Switching

Filters that need to evaluate response-phase conditions and potentially
re-route use `Filters.requestCopyFilter()` to preserve the original
request before the downstream handler consumes it:

```java
// SwitchFilter pattern — response-phase condition routing
public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    Handler wrapped = Handlers.filtered(next, Filters.requestCopyFilter());
    return doSwitch(requestCases, Bindings.bindings(context, request),
        () -> wrapped.handle(context, request)
            .thenAsync(response -> doSwitch(responseCases,
                Bindings.bindings(context, request, response),
                () -> Promises.newResultPromise(response))));
}
```

> **Why copy the request?** Once `next.handle()` processes the request,
> the entity stream may be consumed. If response-phase conditions need
> to re-handle the request with a different handler, the original request
> must be preserved.

---


---

### Handler Interface

A `Handler` is a terminal element that generates a response for a request.

```java
// org.forgerock.http.Handler
// Interface Stability: Evolving
public interface Handler {
    Promise<Response, NeverThrowsException> handle(
        Context context,
        Request request
    );
}
```

A handler can elect to dispatch the request to another handler or chain.
The `handler` is used in route config as the terminal element, while `filters`
intercept before/after.

### Built-in Handlers

| Handler | Purpose |
|---------|---------|
| `ReverseProxyHandler` | Forwards to protected application (reverse proxy) |
| `ClientHandler` | Sends requests to remote servers |
| `StaticResponseHandler` | Returns a static response (useful for testing/stubs) |
| `DispatchHandler` | Routes requests based on conditions |
| `WelcomeHandler` | Returns a welcome page |


> **For message-xform:** We implement a `Filter`, not a `Handler`. The handler
> is always `ReverseProxyHandler` (or whatever the route configures). Our filter
> intercepts the request/response flowing through the chain.

---


## 2. Request, Response & Message Model

### Request

> [Javadoc: Request](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Request.html)

```java
// org.forgerock.http.protocol.Request extends MessageImpl implements Message, Closeable
Request()                              // default constructor
Request(Request request)               // copy constructor

// Core accessors
String getMethod()                     // HTTP method (GET, POST, etc.)
Request setMethod(String method)       // set HTTP method (returns this for chaining)
MutableUri getUri()                    // full URI (mutable)
Request setUri(String uri)             // set URI from string (throws URISyntaxException)
Request setUri(URI uri)                // set URI from java.net.URI

// Query parameters (preferred over deprecated getForm())
Form getQueryParams()                  // parsed query parameters from URI

// Cookies
RequestCookies getCookies()            // parsed from Cookie header (read-only)

// Body & headers (from Message superclass)
Headers getHeaders()                   // HTTP headers (mutable, case-insensitive)
Entity getEntity()                     // body content (mutable)
Request setEntity(Object o)            // polymorphic body setter (see Entity note below)
Request setVersion(String version)     // set HTTP version (default: "HTTP/1.1")
String getVersion()                    // get HTTP version

// Header convenience (from MessageImpl superclass)
Request addHeaders(Header... headers)  // add without replacing
Request putHeaders(Header... headers)  // replace existing
void modifyHeaders(Consumer<Headers> modifier)  // functional header modification
void close()                           // release entity resources

// Lazy copy for deferred request duplication
LazySupplier<Request, IOException> lazyCopy()  // deferred copy until Supplier.get()
```

> **⚠ `getForm()` is deprecated.** Use `getQueryParams()` for URL query parameters
> or `request.getEntity().getForm()` for form-encoded POST bodies.

### Response

> [Javadoc: Response](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Response.html)

```java
// org.forgerock.http.protocol.Response extends MessageImpl implements Message, Closeable
Response()                             // no-arg constructor
Response(Status status)                // construct with status

// Core accessors
Status getStatus()                     // status code + reason phrase
Response setStatus(Status status)      // set status (returns this)
Headers getHeaders()                   // HTTP headers (mutable, case-insensitive)
Entity getEntity()                     // body content (mutable)
Response setEntity(Object o)           // polymorphic body setter (see Entity note below)
Response setVersion(String version)    // set HTTP version (default: "HTTP/1.1")
Exception getCause()                   // exception cause (may be null)
Response setCause(Exception cause)     // set exception cause (returns this)

// Trailer support (HTTP/2, chunked encoding)
Headers getTrailers()                  // access HTTP trailers
Response addTrailers(Header... t)      // add trailer headers
Response putTrailers(Header... t)      // replace trailer headers

// Streaming
boolean hasStreamingContent()          // true if content is streaming
Response setStreamingContent(boolean)  // enable streaming mode

// Promise factory (essential for Filter implementations)
static Promise<Response, NeverThrowsException>
    newResponsePromise(Response response)    // wrap in resolved Promise
static PromiseImpl<Response, NeverThrowsException>
    newResponsePromiseImpl()                 // create unresolved Promise
```

> **`setEntity(Object)` dispatch** (from Message superclass): The parameter type
> determines the behavior:
> - `BranchingInputStream` → `Entity.setRawContentInputStream()`
> - `byte[]` → `Entity.setBytes()` (also sets `Content-Length`)
> - `String` → `Entity.setString()`
> - Any other `Object` → `Entity.setJson()` (sets `Content-Type: application/json; charset=UTF-8`)
>
> Note: Does **not** apply `Content-Encoding`. Intended as a convenience method.

### Status

> [Javadoc: Status](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Status.html)

```java
// org.forgerock.http.protocol.Status
// Constants for common status codes:
// 1xx Informational
Status.CONTINUE                        // 100
Status.SWITCHING_PROTOCOLS             // 101
// 2xx Successful
Status.OK                              // 200
Status.CREATED                         // 201
Status.ACCEPTED                        // 202
Status.NON_AUTHORITATIVE_INFO          // 203
Status.NO_CONTENT                      // 204
Status.RESET_CONTENT                   // 205
// 3xx Redirection
Status.MULTIPLE_CHOICES                // 300
Status.MOVED_PERMANENTLY               // 301
Status.FOUND                           // 302
Status.SEE_OTHER                       // 303
Status.USE_PROXY                       // 305
Status.TEMPORARY_REDIRECT              // 307
// 4xx Client Error
Status.BAD_REQUEST                     // 400
Status.UNAUTHORIZED                    // 401
Status.PAYMENT_REQUIRED                // 402
Status.FORBIDDEN                       // 403
Status.NOT_FOUND                       // 404
Status.METHOD_NOT_ALLOWED              // 405
Status.NOT_ACCEPTABLE                  // 406
Status.PROXY_AUTHENTICATION_REQUIRED   // 407
Status.REQUEST_TIMEOUT                 // 408
Status.CONFLICT                        // 409
Status.GONE                            // 410
Status.LENGTH_REQUIRED                 // 411
Status.PAYLOAD_TOO_LARGE               // 413
Status.URI_TOO_LONG                    // 414
Status.UNSUPPORTED_MEDIA_TYPE          // 415
Status.EXPECTATION_FAILED              // 417
Status.TEAPOT                          // 418
Status.UPGRADE_REQUIRED                // 426
Status.TOO_MANY_REQUESTS               // 429
Status.REQUEST_HEADER_FIELDS_TOO_LARGE // 431
// 5xx Server Error
Status.INTERNAL_SERVER_ERROR           // 500
Status.NOT_IMPLEMENTED                 // 501
Status.BAD_GATEWAY                     // 502
Status.SERVICE_UNAVAILABLE             // 503
Status.GATEWAY_TIMEOUT                 // 504
Status.HTTP_VERSION_NOT_SUPPORTED      // 505

// Factory method (code must be 100 <= code < 1000)
static Status valueOf(int code)        // returns pre-defined or new instance

// Accessors
int getCode()                          // numeric status code
String getReasonPhrase()               // reason phrase
Status.Family getFamily()              // INFORMATIONAL, SUCCESSFUL, etc.
boolean isInformational()              // 1xx
boolean isSuccessful()                 // 2xx
boolean isRedirection()                // 3xx
boolean isClientError()                // 4xx
boolean isServerError()                // 5xx
```

> **`Status.Family` enum:** `INFORMATIONAL`, `SUCCESSFUL`, `REDIRECTION`,
> `CLIENT_ERROR`, `SERVER_ERROR`, `UNKNOWN`. Access via `status.getFamily()`.

### Entity (Body)

> [Javadoc: Entity](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Entity.html)

```java
// org.forgerock.http.protocol.Entity implements Closeable

// --- Synchronous access ---

// JSON (uses PingGateway-bundled Jackson)
Object getJson() throws IOException    // parse JSON → Object (Map/List), cached, null if empty
void setJson(Object obj)               // serialize → JSON, sets Content-Type + Content-Length

// Raw byte access
byte[] getBytes() throws IOException   // read entire entity as byte[], null if empty
void setBytes(byte[] bytes)            // set entity, sets Content-Length

// String access
String getString() throws IOException  // read as String (charset from Content-Type, cached)
void setString(String str)             // set entity, sets Content-Length

// Form data
Form getForm() throws IOException      // decode application/x-www-form-urlencoded body
void setForm(Form form)                // encode as form, sets Content-Type + Content-Length

// Stream access
InputStream getRawContentInputStream() // raw InputStream (no Content-Encoding decoding)
InputStream newDecodedContentInputStream() throws IOException  // respects Content-Encoding
BufferedReader newDecodedContentReader(Charset cs) throws IOException  // decoded + charset
void setRawContentInputStream(BranchingInputStream is)

// Copy content
void copyRawContentTo(OutputStream out) throws IOException
void copyDecodedContentTo(OutputStream out) throws IOException  // decodes Content-Encoding
void copyDecodedContentTo(Writer out) throws IOException        // + charset conversion

// --- Async access (non-blocking Promise variants) ---
Promise<Object, IOException> getJsonAsync()
Promise<byte[], IOException> getBytesAsync()
Promise<String, IOException> getStringAsync()
Promise<Form, IOException> getFormAsync()
Promise<InputStream, NeverThrowsException> getRawContentInputStreamAsync()

// --- Reactive streaming (RxJava3) ---
Flowable<ByteBuffer> getRawContentFlowable()           // raw bytes as reactive stream
Flowable<ByteBuffer> newDecompressedContentFlowable()  // decompressed (Content-Encoding)
Flowable<CharBuffer> decodedContentAsFlowable(Charset) // decoded chars (EXPERIMENTAL)
void setContent(Publisher<ByteBuffer> publisher)       // set from reactive source

// --- State ---
boolean isRawContentEmpty()            // true if no raw content
boolean isDecodedContentEmpty()        // true if no decoded content
void setEmpty()                        // clear content
void close()                           // release underlying buffer

// --- Constants ---
static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json; charset=UTF-8"
static final String APPLICATION_X_WWW_FORM_URLENCODED = "application/x-www-form-urlencoded"
```

> **Key insight:** `Entity.getJson()` and `Entity.setJson()` handle JSON
> serialization/deserialization natively using PingGateway's bundled Jackson.
> However, for message-xform integration, we use `getBytes()`/`setBytes()`
> to work with raw byte arrays — consistent with the PingAccess adapter pattern
> and avoiding assumptions about PG's internal Jackson version.

> **Entity is single-read by default:** After `getBytes()` or `getString()` is
> called, the entity is buffered and can be re-read. Unlike PingAccess `Body`,
> there is no separate `read()` step or `isRead()` check needed.

> **Auto-headers from setters:** `setJson()` auto-sets `Content-Type: application/json; charset=UTF-8`
> and `Content-Length`. `setBytes()` auto-sets `Content-Length`. `setForm()` auto-sets
> `Content-Type: application/x-www-form-urlencoded` and `Content-Length`.
> None of the setters apply `Content-Encoding`.

> **Async variants** return `Promise` and do **not** block the executing thread.
> Use deferred `#{}` syntax in expressions when accessing streamed content.

### Headers

> [Javadoc: Headers](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Headers.html)

```java
// org.forgerock.http.protocol.Headers implements Map<String, Object>
// Case-insensitive HTTP headers. Implements the Java Map interface.

// Retrieval
Header get(Object key)                 // get Header object (case-insensitive)
String getFirst(String key)            // first value as string (null if absent)
<H extends Header> H get(Class<H> headerType) throws MalformedHeaderException
                                       // typed header access (e.g., ContentTypeHeader.class)
List<String> getAll(String key)        // all values for a header as list

// Mutation
Header put(String key, Object value)   // set/replace (value: Header, String, Collection, String[])
Header put(Header header)              // set/replace from typed Header object
void add(String key, Object value)     // append value (preserves existing)
void add(Header header)                // append typed Header
void addAll(Map<? extends String, ? extends Object> map)  // bulk add
void putAll(Map<? extends String, ? extends Object> m)    // bulk replace
Header remove(Object key)              // remove header, returns previous
void clear()                           // remove all

// Views
Map<String, Header> asMapOfHeaders()   // headers as Map<String, Header>
Map<String, List<String>> copyAsMultiMapOfStrings()  // snapshot as multi-map

// Standard Map methods
boolean containsKey(Object key)        // case-insensitive key check
boolean containsValue(Object value)
int size()
boolean isEmpty()
Set<String> keySet()
Collection<Object> values()
Set<Map.Entry<String, Object>> entrySet()
```

> **Case-insensitive natively:** Unlike PingAccess's `Headers` API (which
> returns `Map<String, String[]>` with case-sensitive keys), PingGateway's
> CHF Headers are case-insensitive by design. This simplifies header
> normalization in the adapter.
>
> **Value types for `put()`/`add()`:** Accepted value types are `Header`,
> `String`, `Collection<String>`, or `String[]`. Using any other type
> will throw an exception.

### MutableUri

```java
// org.forgerock.http.MutableUri
// Mutable URI representation.

String getScheme()                     // http, https
void setScheme(String scheme)
String getHost()                       // hostname
void setHost(String host)
int getPort()                          // port number
void setPort(int port)
String getPath()                       // URI path
void setPath(String path)
String getQuery()                      // raw query string
void setQuery(String query)
String getFragment()                   // fragment
void setFragment(String fragment)
URI toURI()                            // convert to java.net.URI
String toASCIIString()                 // full URI as ASCII string
```

### Cookie

```java
// org.forgerock.http.protocol.Cookie
String getName()
String getValue()
String getDomain()
String getPath()
int getMaxAge()
boolean isSecure()
boolean isHttpOnly()
```

> Cookies are accessed via `request.getCookies()` which returns
> `Map<String, Cookie>` parsed from the `Cookie` request header.


## 3. Context Chain & Session

### Context Interface

```java
// org.forgerock.services.context.Context
String getContextName()                // context type name
String getId()                         // unique context ID
String getRootId()                     // root context ID (correlation)
Context getParent()                    // parent context (null if root)
boolean isRootContext()                // true if this is root

// Navigation (throwing)
<T extends Context> T asContext(Class<T> clazz)  // walk up chain to find type
Context getContext(String contextName)            // walk up chain by name

// Navigation (Optional — preferred for null-safety)
<T extends Context> Optional<T> as(Class<T> clazz)  // Optional variant
Optional<Context> get(String contextName)            // Optional by name

// Check existence
boolean containsContext(Class<? extends Context> clazz)
boolean containsContext(String contextName)

JsonValue toJsonValue()                // serialize context chain to JSON
```

> **`asContext()` vs `as()`:** Use `asContext()` when you're certain the context
> exists (throws `IllegalArgumentException` if absent). Use `as()` for safe
> navigation that returns `Optional.empty()` when the context type isn't in
> the chain.

### Context Hierarchy

```
RootContext
  └── SessionContext               ← session attributes (stateful/stateless)
      └── ClientContext            ← client connection info (IP, port, certs)
          └── TransactionIdContext  ← correlation ID for audit trail
              └── UriRouterContext  ← matched route info
                  └── AttributesContext  ← arbitrary key-value pairs
                      └── OAuth2Context  ← OAuth2 token info (if validated)
                          └── ...additional contexts...
```

### Accessing Contexts

```java
// Navigate the context chain using asContext():
SessionContext session = context.asContext(SessionContext.class);
AttributesContext attrs = context.asContext(AttributesContext.class);
TransactionIdContext txn = context.asContext(TransactionIdContext.class);
ClientContext client = context.asContext(ClientContext.class);

// Check if a context type exists:
boolean hasOAuth2 = context.containsContext(OAuth2Context.class);
```

### Core Context Types

#### SessionContext

Provides access to the HTTP session. Session data is stored as key-value pairs.

```java
// org.forgerock.http.session.SessionContext
Session getSession()                   // returns Session (Map-like interface)

// Session interface:
session.put("key", value)              // store value
session.get("key")                     // retrieve value
session.remove("key")                  // remove value
session.containsKey("key")            // check existence
```

#### AttributesContext

Arbitrary key-value attributes set by filters earlier in the chain.

```java
// org.forgerock.services.context.AttributesContext
Map<String, Object> getAttributes()    // mutable attribute map
```

> **Route-level data sharing:** Filters can store computed values in
> `AttributesContext` for downstream filters/handlers to consume. This is
> analogous to PingAccess's `ExchangeProperty` mechanism.

#### ClientContext

Information about the client connection.

```java
// org.forgerock.http.protocol.ClientContext
String getRemoteAddress()              // client IP address
int getRemotePort()                    // client port
String getLocalAddress()               // local server address
int getLocalPort()                     // local server port
List<Certificate> getCertificates()    // client certificates (mTLS)
boolean isExternal()                   // true if external client
boolean isSecure()                     // true if HTTPS
```

#### TransactionIdContext

Correlation ID for distributed tracing and audit trail.

```java
// org.forgerock.services.TransactionId
TransactionIdContext txnCtx = context.asContext(TransactionIdContext.class);
TransactionId txnId = txnCtx.getTransactionId();
String value = txnId.getValue();       // e.g., "abc-123-def-456"
```

#### UriRouterContext

Information about the matched route.

```java
// org.forgerock.http.routing.UriRouterContext
URI getBaseUri()                       // base URI from route
URI getOriginalUri()                   // original request URI before routing
URI getMatchedUri()                    // the matched portion of the URI
URI getRemainingUri()                  // unmatched portion
Map<String, String> getUriTemplateVariables()  // URI template variables
```

#### OAuth2Context

Available when an `OAuth2ResourceServerFilter` has validated an access token.

```java
// org.forgerock.openig.filter.oauth2.OAuth2Context
// Expression access: ${contexts.oauth2.accessToken}
AccessTokenInfo getAccessToken()       // token metadata

// AccessTokenInfo provides:
String getToken()                      // raw token value
Set<String> getScopes()                // granted scopes
long getExpiresAt()                    // expiry timestamp
Map<String, Object> getInfo()          // all token claims as map
Object getInfo(String key)             // single claim value
```

### Additional Context Types

| Context | Purpose |
|---------|---------|
| `CertificateContext` | Client certificate details for mTLS |
| `SsoTokenContext` | PingAM SSO token value |
| `PolicyDecisionContext` | PingAM authorization policy result |
| `JwtValidationContext` | JWT validation results |
| `UserProfileContext` | User profile data from identity provider |
| `SessionInfoContext` | AM session info (timeout, properties) |
| `McpContext` | MCP (Model Context Protocol) metadata |

---


---

### Session Types

PingGateway supports two session storage mechanisms:

| Feature | Stateful Sessions | Stateless Sessions |
|---------|-------------------|-------------------|
| Storage | Server-side (PingGateway) | Client-side (JWT cookies) |
| Default cookie name | `IG_SESSIONID` | `openig-jwt-session` |
| Cookie size limit | Unlimited | 4 KB per cookie (auto-split) |
| Supported data types | All types | JSON-compatible only |
| Load balancing | Requires session stickiness | Any server (shared keys) |
| Thread safety | Not thread-safe | Not thread-safe |

### Session Configuration (in `config.json`)

```json
{
  "session": {
    "type": "JwtSessionManager",
    "config": {
      "maxSessionTimeout": "30 minutes",
      "sessionCookieName": "my-session"
    }
  }
}
```

> Without explicit session configuration, PingGateway uses stateful sessions by
> default.

### Session Access in Java

```java
SessionContext sessionCtx = context.asContext(SessionContext.class);
Session session = sessionCtx.getSession();

// Read
Object value = session.get("my-key");

// Write
session.put("my-key", "my-value");

// Remove
session.remove("my-key");
```

### Session Access in Expressions

```
${session.my-key}              // read session attribute
${toString(session.name)}      // convert to string
${session.name[0]}             // first element if multi-valued
```

> **Thread safety warning:** Session sharing is not thread-safe. It is not
> suitable for concurrent exchanges modifying the same session simultaneously.

### Comparison with PingAccess Session Model

| Aspect | PingAccess | PingGateway |
|--------|------------|-------------|
| Primary abstraction | `Identity` object with structured getters | `SessionContext` with flat key-value `Session` |
| OAuth metadata | `OAuthTokenMetadata` (scopes, clientId) | `OAuth2Context` (separate context in chain) |
| Session state | `SessionStateSupport` (getAttribute/setAttribute) | `Session.get()` / `Session.put()` |
| Key hierarchy | 4-layer merge (subject, identity, session state, OAuth) | 3-layer merge (session, attributes, OAuth2) |

---


### Context Types (Full API)

**AttributesContext** — arbitrary key-value attributes:
```java
public final class AttributesContext extends AbstractContext {
    private final Map<String, Object> attributes = new HashMap<>();
    public Map<String, Object> getAttributes() { return attributes; }
}
```

**ClientContext** — client connection information:
```java
public final class ClientContext extends AbstractContext {
    public String getRemoteAddress()    // client IP
    public int getRemotePort()          // client port
    public String getRemoteUser()       // authenticated user (if known)
    public String getUserAgent()        // User-Agent header value
    public boolean isSecure()           // HTTPS?
    public boolean isExternal()         // external (true) vs internal request
    public String getLocalAddress()     // server-side listener IP
    public int getLocalPort()           // server-side listener port
    public Collection<? extends Certificate> getCertificates()  // client certs
}
```

**Context interface** — navigation and introspection:
```java
public interface Context {
    <T extends Context> T asContext(Class<T> clazz);           // typed lookup
    <T extends Context> Optional<T> as(Class<T> clazz);        // optional typed lookup  ← NEW in 2025.11
    Context getContext(String contextName);                     // lookup by name
    Optional<Context> get(String contextName);                 // optional by name  ← NEW
    boolean containsContext(Class<? extends Context> clazz);   // check existence
    boolean containsContext(String contextName);
    Context getParent();                                        // parent in chain
    boolean isRootContext();
    String getId();                                            // unique UUID
    String getRootId();                                        // root UUID
    String getContextName();                                   // e.g., "session", "client"
    JsonValue toJsonValue();                                   // serialize to JSON
}
```

> **Discovery: `as()` and `get()` methods.** PingGateway 2025.11 added Optional-returning
> context lookup methods. Use `context.as(SessionContext.class)` for null-safe access.


### Session Interface (Full API)

```java
// org.forgerock.http.session.Session
public interface Session extends Map<String, Object> {
    // That's it — Session IS a Map<String, Object>.
    // Inherits: get(), put(), containsKey(), keySet(), entrySet(), etc.
}

// org.forgerock.http.session.SessionContext
public final class SessionContext extends AbstractContext {
    public SessionContext(Context parent, Session session) { ... }
    public Session getSession() { return this.session; }
    public SessionContext setSession(Session session) { ... }
}
```

> **Critical for adapter:** Session is just `Map<String, Object>`. Access via:
> ```java
> Session session = context.asContext(SessionContext.class).getSession();
> Object value = session.get("key");
> session.put("key", value);
> ```


## 4. Promise API & Async Patterns

### Critical Warning

> ⚠️ **When writing Java extensions that use the Promise API, avoid the blocking
> methods `get()`, `getOrThrow()`, and `getOrThrowUninterruptibly()`.** A promise
> represents the result of an asynchronous operation; therefore, using a
> blocking method to wait for the result can cause **deadlocks and/or race issues**.

### Promise<Response, NeverThrowsException>

```java
// org.forgerock.util.promise.Promise<V, E extends Exception>
// For HTTP operations: Promise<Response, NeverThrowsException>

// ── Non-blocking callbacks ──
Promise<V, E> thenOnResult(ResultHandler<V> handler)
    // Callback when the promise resolves successfully.
    // Used for the response-phase in filters.

Promise<V2, E> then(Function<V, V2, E> transform)
    // Transform the result value.

Promise<V2, E> thenAsync(AsyncFunction<V, V2, E> asyncTransform)
    // Chain another async operation.
    // Used when the response-phase itself needs to make async calls.

Promise<V, E> thenCatch(ExceptionHandler<E> handler)
    // Handle exceptions from the promise.

// ── Creating promises ──
static <V> Promise<V, NeverThrowsException> newResultPromise(V value)
    // Wrap a synchronous result as a completed promise.
```

### Correct Filter Patterns

**Pattern 1: Modify request + response (most common)**
```java
@Override
public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    // Modify request
    request.getHeaders().put("X-Custom", "value");
    // Forward and modify response
    return next.handle(context, request)
        .thenOnResult(response -> {
            response.getHeaders().put("X-Custom-Response", "value");
        });
}
```

**Pattern 2: Short-circuit (DENY pattern)**
```java
@Override
public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    if (shouldDeny(request)) {
        // Do NOT call next.handle() — return error response directly
        Response errorResponse = new Response(Status.BAD_GATEWAY);
        errorResponse.getHeaders().put("Content-Type", "application/problem+json");
        errorResponse.getEntity().setString("{\"type\":\"error\",\"status\":502}");
        return Promises.newResultPromise(errorResponse);
    }
    return next.handle(context, request);
}
```

**Pattern 3: Async transformation in response phase**
```java
return next.handle(context, request)
    .thenAsync(response -> {
        // Do something async, return another Promise
        return someAsyncOperation(response);
    });
```

### Anti-Patterns (NEVER do this)

```java
// ⛔ WRONG: Blocking on promise result
Response response = next.handle(context, request).get(); // DEADLOCK RISK!
response.getHeaders().put("X-Header", "value");
return Promises.newResultPromise(response);

// ⛔ WRONG: Using getOrThrow
Response response = next.handle(context, request).getOrThrow(); // DEADLOCK!
```

### CPU-Bound Operations in Promises

JSLT transformation is CPU-bound (synchronous), not I/O-bound. Calling the
synchronous transform engine within `thenOnResult()` (response phase) or
directly (request phase) is acceptable — there is no async I/O to block on.
The non-blocking mandate applies specifically to **waiting on other promises**.

---


### Promise Interface (Full API)

```java
// org.forgerock.util.promise.Promise<V, E extends Exception>

// Blocking access (DO NOT use in filter chain!):
V get() throws ExecutionException, InterruptedException
V get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException, InterruptedException
V getOrThrow() throws InterruptedException, E
V getOrThrowIfInterrupted() throws E       // ← convenience (no InterruptedException)
V getOrThrowUninterruptibly() throws E

// Chaining (non-blocking — USE THESE):
<VOUT> Promise<VOUT, E> then(Function<? super V, VOUT, E> onResult)
<EOUT extends Exception> Promise<V, EOUT> thenCatch(Function<? super E, V, EOUT> onException)
<VOUT, EOUT extends Exception> Promise<VOUT, EOUT> then(
    Function<? super V, VOUT, EOUT> onResult,
    Function<? super E, VOUT, EOUT> onException)

// Async chaining (returns Promise, not value):
<VOUT> Promise<VOUT, E> thenAsync(AsyncFunction<? super V, VOUT, E> onResult)
<EOUT extends Exception> Promise<V, EOUT> thenCatchAsync(AsyncFunction<? super E, V, EOUT>)
<VOUT, EOUT extends Exception> Promise<VOUT, EOUT> thenAsync(
    AsyncFunction<? super V, VOUT, EOUT> onResult,
    AsyncFunction<? super E, VOUT, EOUT> onException)

// Side-effect handlers:
Promise<V, E> thenOnResult(ResultHandler<? super V>)
Promise<V, E> thenOnException(ExceptionHandler<? super E>)
Promise<V, E> thenOnResultOrException(ResultHandler<V>, ExceptionHandler<E>)
Promise<V, E> thenOnResultOrException(Runnable)
Promise<V, E> thenOnRuntimeException(RuntimeExceptionHandler)
Promise<V, E> thenAlways(Runnable)         // ← like finally block
Promise<V, E> thenFinally(Runnable)        // alias for thenAlways

// Utility:
Promise<Void, E> thenDiscardResult()       // discard V, return Void
boolean cancel(boolean mayInterruptIfRunning)
boolean isCancelled()
boolean isDone()
boolean isResult()
```



---

# Part II — Configuration & Lifecycle

---

## 5. Heaplet Configuration & Lifecycle

### Heap Object Model

PingGateway uses a **heap** to manage configured objects. Each custom class
must have an accompanying `Heaplet` implementation. The standard pattern is a
nested `Heaplet` class extending `GenericHeaplet`.

### GenericHeaplet

```java
// org.forgerock.openig.heap.GenericHeaplet
// Base class for creating heap objects from JSON configuration.

public abstract class GenericHeaplet implements Heaplet {
    protected JsonValue config;        // JSON configuration node
    protected String name;             // object name from config
    protected Heap heap;               // parent heap for resolving refs

    // Must be overridden:
    public abstract Object create() throws HeapException;

    // Optional — cleanup hook:
    public void destroy() {
        // Called when the heap object is being torn down
    }
}
```

### Configuration Access Pattern

Within `create()`, access configuration via the `config` field:

```java
public static class Heaplet extends GenericHeaplet {
    @Override
    public Object create() throws HeapException {
        MessageTransformFilter filter = new MessageTransformFilter();

        // Required string field
        filter.specsDir = config.get("specsDir")
            .as(evaluatedWithHeapProperties())  // resolve ${} expressions
            .required()                          // throws HeapException if missing
            .asString();

        // Optional string with default
        filter.profilesDir = config.get("profilesDir")
            .as(evaluatedWithHeapProperties())
            .defaultTo("/profiles")
            .asString();

        // Optional integer with default
        filter.reloadInterval = config.get("reloadIntervalSec")
            .as(evaluatedWithHeapProperties())
            .defaultTo(30)
            .asInteger();

        // Optional boolean with default
        filter.enableJmx = config.get("enableJmxMetrics")
            .as(evaluatedWithHeapProperties())
            .defaultTo(false)
            .asBoolean();

        // Optional enum
        String errorModeStr = config.get("errorMode")
            .as(evaluatedWithHeapProperties())
            .defaultTo("PASS_THROUGH")
            .asString();
        filter.errorMode = ErrorMode.valueOf(errorModeStr);

        return filter;
    }

    @Override
    public void destroy() {
        // Shutdown reload scheduler, release resources
        if (filter.reloadScheduler != null) {
            filter.reloadScheduler.shutdownNow();
        }
    }
}
```

### JsonValue Accessor Methods

The `config` field is a `JsonValue` instance with a fluent API:

```java
config.get("fieldName")                // navigate to child node
    .as(evaluatedWithHeapProperties()) // resolve ${env.VAR} and &{props}
    .required()                        // throw if missing
    .defaultTo(value)                  // provide default
    .asString()                        // convert to String
    .asInteger()                       // convert to Integer
    .asBoolean()                       // convert to Boolean
    .asLong()                          // convert to Long
    .asList(String.class)             // convert to List<String>
    .asMap(String.class)              // convert to Map<String, String>
    .asEnum(MyEnum.class)             // convert to enum
```

### Property Value Substitution

PingGateway supports two forms of property substitution in config:

1. **`&{property}`** — resolved from route `properties` or system properties
2. **`${expression}`** — PingGateway expression language (EL)

```json
{
  "properties": {
    "specsDir": "/opt/specs"
  },
  "handler": {
    "type": "Chain",
    "config": {
      "filters": [{
        "type": "MessageTransformFilter",
        "config": {
          "specsDir": "&{specsDir}",
          "activeProfile": "${system['ig.profile'] ?? 'default'}"
        }
      }]
    }
  }
}
```

> **`evaluatedWithHeapProperties()`** is required to process `&{...}` tokens
> in configuration values. Without it, property tokens are treated as literal
> strings.

### Lifecycle Sequence

1. **Startup:** PingGateway reads route JSON → creates Heaplet → calls `create()` → calls `start()`
2. **Runtime:** Filter instance serves requests (shared across threads)
3. **Shutdown/Route reload:** PingGateway calls `destroy()` on the Heaplet

> **Equivalent to PingAccess:**
> - `create()` ≈ `configure(T config)` (init-time setup)
> - `start()` ≈ post-construction initialization (e.g., starting background tasks)
> - `destroy()` ≈ `@PreDestroy` (cleanup)

### Production Heaplet Conventions

Production-quality filters follow consistent conventions that custom
filters should adopt for consistency and maintainability.

#### Builder + Nested Heaplet Structure

Production filters use a `final` class with a `Builder` and a nested
`Heaplet` — this separates programmatic construction from JSON-based
configuration:

```java
public final class MyFilter implements Filter {
    // Immutable state — set by Builder
    private final MessageType messageType;
    private final List<String> removedHeaders;

    private MyFilter(Builder builder) {
        this.messageType = Objects.requireNonNull(builder.messageType);
        this.removedHeaders = List.copyOf(builder.removedHeaders);
    }

    @Override
    public Promise<Response, NeverThrowsException> filter(
            Context context, Request request, Handler next) { /* ... */ }

    // Builder for programmatic construction and testing
    public static final class Builder {
        private final MessageType messageType;
        private final List<String> removedHeaders = new ArrayList<>();

        public Builder(MessageType messageType) {
            this.messageType = Objects.requireNonNull(messageType);
        }

        public Builder removeHeader(String name) {
            this.removedHeaders.add(name);
            return this;
        }

        public MyFilter build() {
            return new MyFilter(this);
        }
    }

    // Heaplet for JSON-based configuration
    public static class Heaplet extends GenericHeaplet {
        public static final String NAME = "MyFilter";
        static final String CONFIG_MESSAGE_TYPE = "messageType";
        static final String CONFIG_REMOVE = "remove";

        @Override
        public Object create() throws HeapException {
            Builder builder = new Builder(/* ... */);
            // ... populate from config ...
            return builder.build();
        }
    }
}
```

#### Config Key Constants

All configuration field names are declared as `static final String CONFIG_*`
constants in the Heaplet. This prevents typo-based configuration bugs and
makes all accepted keys visible at a glance:

```java
static final String CONFIG_MESSAGE_TYPE = "messageType";
static final String CONFIG_RATE = "rate";
static final String CONFIG_CLEANING_INTERVAL = "cleaningInterval";
static final String CONFIG_EXECUTOR = "executor";
```

#### The `start()` Hook

`GenericHeaplet` calls `create()` then `start()` in sequence. Use `start()`
for post-construction initialization that depends on the created object:

```java
@Override
public void start() throws HeapException {
    // Start background tasks, register endpoints, etc.
}
```

> `start()` is a no-op by default. Override it only when needed.

#### `destroy()` Cleanup

Built-in heaplets clean up resources, cancel scheduled tasks, and
deregister metrics in `destroy()`:

```java
@Override
public void destroy() {
    super.destroy();  // deregisters endpoint registration + metrics
    if (this.filter != null) {
        this.filter.stop();
    }
}
```

> Always call `super.destroy()` first — the base class deregisters the
> endpoint registration and meter registry.

#### Metrics Integration

PingGateway provides a `MeterRegistryHolder` for Prometheus-compatible
metrics. Access it via `meterRegistryHolder()` in your Heaplet:

```java
protected MeterRegistryHolder meterRegistryHolder() throws HeapException;
```

The meter registry is automatically tagged with the heaplet's fully
qualified name and deregistered on `destroy()`.

#### Heap Object References

To reference other heap objects (e.g., a shared `ScheduledExecutorService`),
use `JsonValues.requiredHeapObject()`:

```java
ScheduledExecutorService executor = (ScheduledExecutorService)
    config.get("executor")
        .defaultTo("ScheduledExecutorService")   // default heap name
        .as(JsonValues.requiredHeapObject(heap, ScheduledExecutorService.class));
```

---


### GenericHeaplet Lifecycle (Full API)

The `GenericHeaplet.create(Name, JsonValue, Heap)` method reveals the full lifecycle:

```java
// GenericHeaplet.create() lifecycle
public Object create(Name name, JsonValue config, Heap heap) throws HeapException {
    this.name = name.getLeaf();
    this.qualified = name;
    this.config = config.required().expect(Map.class);  // validates JSON is a Map
    this.heap = heap;
    this.heapletStartupMetrics = heap.startupMetrics()
        .createChild(this.name, "heaplet", "Heaplet create and start time");
    this.heapletStartupMetrics.starting();
    this.object = this.create();    // ← subclass implements this
    this.start();                   // ← optional lifecycle hook (post-create)
    this.heapletStartupMetrics.started(this.object.getClass());
    return this.object;
}
```

> **Discovery: `start()` lifecycle hook.** GenericHeaplet has a `start()` method
> called after `create()`. This is the place for post-initialization work like
> scheduling spec reload timers or registering JMX MBeans.
>
> **Discovery: Startup metrics.** PingGateway tracks heaplet startup timing
> via `StartupMetrics`. Our adapter automatically benefits from this.
>
> **Discovery: `endpointRegistry()`.** GenericHeaplet provides an `endpointRegistry()`
> method for registering REST endpoints on the heap object. Could be used for
> admin endpoints (e.g., spec reload trigger).
>
> **Discovery: `meterRegistryHolder()`.** Provides Micrometer-compatible meter
> registration. Our JMX metrics could integrate with this for PingGateway-native
> monitoring.


### Available GenericHeaplet Protected Methods (Full API)

```java
// Protected fields available in create():
protected String name;             // leaf name of the heap object
protected Name qualified;          // fully-qualified hierarchical name
protected JsonValue config;        // JSON configuration
protected Heap heap;               // parent heap for resolving references
protected Object object;           // the created object (set after create())

// Protected methods:
Function<JsonValue, JsonValue, JsonValueException> evaluatedWithHeapProperties()
<T> Function<JsonValue, Expression<T>, JsonValueException> expression(Class<T> type)
Bindings initialBindings()         // bindings with heap properties + clock
EndpointRegistry endpointRegistry() throws HeapException
MeterRegistryHolder meterRegistryHolder() throws HeapException
String getType()                   // override for meter tagging
void start() throws HeapException  // post-create lifecycle hook
void destroy()                     // cleanup hook (unregisters endpoints/meters)
```


### Heap Interface (Full API)

```java
// org.forgerock.openig.heap.Heap
public interface Heap {
    Name getName();
    <T> T get(String name, Class<T> type) throws HeapException;      // named lookup
    <T> T resolve(JsonValue ref, Class<T> type) throws HeapException;  // JSON ref
    <T> T resolve(JsonValue ref, Class<T> type, boolean optional) throws HeapException;
    Bindings getProperties();
    StartupMetrics startupMetrics();
}
```

> **`heap.get("ScheduledExecutorService", ScheduledExecutorService.class)`** is the
> idiomatic way to access the default executor. See §22.8 for how filters
> use this pattern.


---

### Configuration Architecture

### Configuration File Hierarchy

PingGateway uses a layered configuration model:

```
$HOME/.openig/
├── config/
│   ├── admin.json              ← HTTP connectors, admin API, TLS, mode
│   ├── config.json             ← Global handler, heap, session, baseURI
│   └── routes/
│       ├── 01-health.json      ← Route files (lexicographic order)
│       ├── 10-transform.json
│       └── 99-catch-all.json
├── extra/                      ← Custom extension JARs

├── logs/                       ← Logback output
│   └── route-system.log
├── tmp/                        ← Temporary storage
├── audit-schemas/              ← Custom audit topic schemas
└── SAML/                       ← SAML 2.0 metadata
```

### admin.json (AdminHttpApplication)

Governs the administrative and connector-level settings:

```json
{
  "mode": "PRODUCTION",
  "connectors": [
    {
      "port": 8080
    },
    {
      "port": 8443,
      "tls": { ... }
    }
  ],
  "session": { ... },
  "temporaryDirectory": "/path/to/tmp"
}
```

Key fields:
- `mode`: `"DEVELOPMENT"` or `"PRODUCTION"`
- `connectors`: HTTP/HTTPS listener ports + TLS config
- `session`: Admin session manager override
- `apiProtectionFilter`: Controls access to admin endpoints

### config.json (GatewayHttpApplication)

Governs the main gateway request processing:

```json
{
  "handler": {
    "name": "_router",
    "type": "Router"
  },
  "heap": [ ... ],
  "session": { ... },
  "properties": { ... },
  "globalDecorators": { ... }
}
```

Key fields:
- `handler`: The root handler (typically a `Router`)
- `heap`: Shared objects available to all routes
- `session`: Default session manager (stateful or stateless)
- `properties`: Property values for `&{prop}` substitution

### Route Files

Each `.json` file in `config/routes/` is a route (see §7).

### Inline vs Heap Objects

- **Inline objects**: Declared directly in route config; cannot be referenced
  by other objects
- **Heap objects**: Declared in the `heap` array with a `name`; can be
  referenced by name from anywhere in the route or child routes

```json
{
  "heap": [
    {
      "name": "MySharedFilter",
      "type": "MessageTransformFilter",
      "config": { ... }
    }
  ],
  "handler": {
    "type": "Chain",
    "config": {
      "filters": ["MySharedFilter"],
      "handler": "ReverseProxyHandler"
    }
  }
}
```

### Configuration Comments

JSON doesn't support comments natively. PingGateway ignores unrecognized fields:

- `"comment": "text"` — add a text comment field
- `"_fieldName": value` — prefix with `_` to comment out a field
- `//` — single-line comments (PG's JSON parser tolerates these)

### Restart Requirements

| Change | Restart Required? |
|--------|-------------------|
| Add/modify route files | **No** (auto-scanned by Router) |
| Modify `config.json` | **Yes** |
| Modify `admin.json` | **Yes** |
| Add/modify JAR in `extra/` | **Yes** |
| Modify environment variables / system properties | **Yes** |


> To disable route hot-reload, set `scanInterval` to `"disabled"` in the
> Router config.

---


## 6. Route & Chain Configuration

### Route Structure

A route is a JSON file in `$HOME/.openig/config/routes/` (or configured directory):

```json
{
  "name": "transform-route",
  "condition": "${find(request.uri.path, '^/api')}",
  "baseURI": "https://backend.example.com:8443",
  "handler": {
    "type": "Chain",
    "config": {
      "filters": [
        {
          "type": "MessageTransformFilter",
          "config": {
            "specsDir": "/opt/message-xform/specs",
            "profilesDir": "/opt/message-xform/profiles",
            "activeProfile": "production",
            "errorMode": "PASS_THROUGH",
            "reloadIntervalSec": 30,
            "enableJmxMetrics": true
          }
        }
      ],
      "handler": "ReverseProxyHandler"
    }
  }
}
```

### Route Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Route name (used for logging/admin) |
| `condition` | expression | When to match this route |
| `baseURI` | string | Base URI for the protected application |
| `handler` | object/ref | The handler or chain to process requests |
| `heap` | array | Additional objects to register in this route's heap |
| `session` | object | Session configuration override for this route |

### Condition Expressions

Routes are matched against incoming requests using PingGateway expressions:

```
"${find(request.uri.path, '^/api')}"           // path starts with /api
"${request.uri.path == '/login'}"              // exact path match
"${request.uri.host == 'api.example.com'}"     // host match
"${request.method == 'POST'}"                  // method match
"${request.uri.scheme == 'https'}"             // scheme match
```

### Route Ordering

Route files are processed in **lexicographic order** by filename. Use numeric
prefixes to control order:

```
routes/
├── 01-health.json         ← matched first
├── 10-transform.json      ← our filter route
├── 50-oauth.json
└── 99-catch-all.json      ← matched last
```

> A route without a `condition` matches all requests. The first matching route
> wins.

### Chain (Filter + Handler Composition)

```json
{
  "type": "Chain",
  "config": {
    "filters": [
      "Filter1",
      "Filter2",
      { "type": "InlineFilter", "config": { ... } }
    ],
    "handler": "ReverseProxyHandler"
  }
}
```

Filters in the `filters` array are invoked in order. Each filter calls
`next.handle()` to pass to the next filter, and ultimately to the handler.

### Heap Objects

Named objects can be defined in the route's `heap` array and referenced by name:

```json
{
  "heap": [
    {
      "name": "MyFilter",
      "type": "MessageTransformFilter",
      "config": { ... }
    }
  ],
  "handler": {
    "type": "Chain",
    "config": {
      "filters": ["MyFilter"],
      "handler": "ReverseProxyHandler"
    }
  }
}
```

---


### Development vs Production Mode

### Mode Configuration

Set in `admin.json`:
```json
{
  "mode": "DEVELOPMENT"
}
```

Or via environment variable: `IG_RUN_MODE=DEVELOPMENT`

Or via system property: `-Dig.run.mode=DEVELOPMENT`

Precedence: `admin.json` > `IG_RUN_MODE` env > system property.

### Mode Differences

| Feature | Development | Production |
|---------|-------------|------------|
| Admin API | Fully accessible | Restricted |
| Studio UI | Available (`:8085/studio`) | Unavailable |
| API descriptors (`?_api`) | Available | Unavailable |
| Route Common REST mgmt | Available | Restricted |
| Error detail in responses | Verbose | Minimal |

### Admin API Endpoints

In development mode, the admin REST API is available at port 8085:

```bash
# List all routes
curl "http://ig.example.com:8085/api/system/objects/_router/routes?_queryFilter=true"

# Deploy a route via REST
curl -X PUT http://ig.example.com:8085/api/system/objects/_router/routes/my-route \
  -d @my-route.json \
  --header "Content-Type: application/json" \
  --header "If-Match: *"

# Delete a route
curl -X DELETE http://ig.example.com:8085/api/system/objects/_router/routes/my-route
```

> **Security:** In production, change `apiProtectionFilter` in `admin.json`
> to restrict admin endpoint access. The default production protection blocks
> all external access.

---


## 7. Expression Language & ExpressionPlugin

### Expression Types

There are two categories of expressions, evaluated at different times:

| Type | Syntax | When Evaluated | Blocking |
|------|--------|----------------|----------|
| **Configuration** | `${}` | At route load (startup/hot-reload) | N/A |
| **Runtime (immediate)** | `${}` | Per-request, blocks until content available | Yes |
| **Runtime (deferred)** | `#{}` | Per-request, non-blocking until content ready | No |

> **⚠ Streaming:** When `streamingEnabled` is `true` in `admin.json`, expressions
> that consume streamed content (e.g., `request.entity`) **must** use `#{}` (deferred)
> instead of `${}` (immediate) to avoid blocking the executing thread.

### Expression Syntax

```
// Request properties
${request.uri.path}                    // request URI path
${request.method}                      // HTTP method
${request.headers['Authorization'][0]} // first value of header
${request.cookies['session'].value}    // cookie value
${request.uri.query}                   // raw query string
${request.uri.host}                    // hostname
${request.uri.scheme}                  // http/https
${request.uri.port}                    // port number
${request.entity.string}               // body as string
${request.entity.json}                 // body as parsed JSON object

// Response properties (only in response flow)
${response.status.code}                // numeric status code
${response.status.reasonPhrase}        // reason phrase
${response.headers['Content-Type'][0]} // response header
${response.entity.string}              // response body as string

// Context chain
${contexts.oauth2.accessToken.info}    // OAuth2 token claims
${contexts.oauth2.accessToken.scopes}  // OAuth2 scopes
${contexts.session}                    // session map
${contexts.client.remoteAddress}       // client IP

// Runtime bindings
request                                // org.forgerock.http.protocol.Request
response                               // org.forgerock.http.protocol.Response (response flow only)
context                                // org.forgerock.services.context.Context
contexts                               // Map<String, Context>
attributes                             // Map<String, Object> (from AttributesContext)
session                                // Session (Map-like, from SessionContext)

// Environment
${system['user.home']}                 // system property
${env['HOME']}                         // environment variable

// PingGateway implicit object
${openig.instanceDirectory}            // $HOME/.openig (Linux)
${openig.configDirectory}              // $HOME/.openig/config
${openig.temporaryDirectory}           // $HOME/.openig/tmp
```

### Operators

The following operators are provided (listed highest to lowest precedence):

| Precedence | Operators |
|---|---|
| Property access | `[]`, `.` |
| Grouping | `()` |
| Prefix | `- (unary)`, `not`, `!`, `empty` |
| Multiplicative | `*`, `/`, `div`, `%`, `mod` |
| Additive | `+`, `-` |
| Relational | `<`, `>`, `<=`, `>=`, `lt`, `gt`, `le`, `ge` |
| Equality | `==`, `!=`, `eq`, `ne` |
| Logical AND | `&&`, `and` |
| Logical OR | `\|\|`, `or` |
| Ternary | `? :` |

Examples:
```
// Ternary
${request.uri.path.startsWith('/home') ? 'home' : 'not-home'}

// Logical operators
${find(request.uri.path, '/webapp') and (contains(request.uri.query, 'one') or empty(request.uri.query))}

// Arithmetic
${request.uri.port + 4}

// Type conversion for comparison
${integer(&{my.status.code|404}) == 200}

// Deferred evaluation for form fields
#{request.entity.form['answer'] > 42}
```

### Dynamic Bindings

`ExpressionInstant` provides time-based bindings via the `now` binding:

```
${now.epochSeconds}                    // current epoch seconds
${now.plusMinutes(30).epochSeconds}     // 30 minutes from now
${now.plusDays(1).rfc1123}             // tomorrow in RFC 1123 format
${now.minusHours(1).epochSeconds}      // one hour ago
```

Available time operations: `plusMillis(n)`, `minusMillis(n)`, `plusSeconds(n)`,
`minusSeconds(n)`, `plusMinutes(n)`, `minusMinutes(n)`, `plusHours(n)`,
`minusHours(n)`, `plusDays(n)`, `minusDays(n)`.

### Built-in Functions

| Function | Description |
|----------|-------------|
| `find(string, pattern)` | Regex find (returns boolean) |
| `matches(string, pattern)` | Full regex match |
| `matchesWithRegex(string, pattern)` | Alternative regex match |
| `contains(string, substring)` | Substring check |
| `toString(object)` | Convert to string |
| `integer(string)` | Convert to integer |
| `split(string, regex)` | Split string |
| `join(array, separator)` | Join array elements |
| `length(collection)` | Collection size |
| `toJson(object)` | Serialize to JSON string |
| `empty(value)` | Check if null or empty |

> See [PingGateway Functions Reference](https://docs.pingidentity.com/pinggateway/2025.11/reference/Functions.html)
> for the complete list.

### Java Method Syntax in Expressions

Expressions can call methods on Java objects directly:

```
// String methods
${request.uri.path.startsWith('/home')}
${request.method.toUpperCase()}
${request.headers['Content-Type'][0].contains('json')}
```

### ExpressionPlugin SPI

Add a custom node to the expression context tree:

```java
// org.forgerock.openig.el.ExpressionPlugin
public interface ExpressionPlugin {
    String getKey();           // node name in expression tree
    Object getObject();        // context object for resolution
}
```

Example: `${system['user.home']}` is implemented by an ExpressionPlugin where:
- `getKey()` returns `"system"`
- `getObject()` returns a `Map` of system properties

### ExpressionPlugin Registration

```
META-INF/services/org.forgerock.openig.el.ExpressionPlugin
```

> ExpressionPlugins are not typically needed for the message-xform adapter,
> but understanding them is important for advanced PingGateway integration.

---


### Condition Pattern (Full API)

The `Condition` class enables three flexible configuration formats for boolean
conditions in route config, all producing the same async-evaluable object:

**Three configuration forms:**

```json
// Form 1: Static boolean
{ "condition": true }

// Form 2: Expression string (EL)
{ "condition": "${request.method == 'POST'}" }

// Form 3: Structured with label (for debugging)
{
  "condition": {
    "expression": "${request.uri.path == '/api/v1'}",
    "label": "API v1 path match"
  }
}
```

**Java API:**

```java
public final class Condition {
    // Pre-built constants
    static final Condition ALWAYS_TRUE;
    static final Condition ALWAYS_FALSE;

    // Constructors
    Condition(Expression<Boolean> expression, String label)
    Condition(String expression) throws ExpressionException

    // Async evaluation
    Promise<Boolean, NeverThrowsException> test(Bindings bindings)
    // Returns false on null evaluation result (with warning log)
    // Returns false on RuntimeException (with error log)

    String label()  // human-readable label for debug logging

    // Config parsing function (for use with JsonValue.as())
    static Function<JsonValue, Condition, JsonValueException>
        condition(Bindings initialBindings)
    // Handles all three forms (boolean, string expression, struct)
}
```

> **Key pattern: Optional condition defaulting to `ALWAYS_TRUE`.**
> DispatchHandler and SequenceHandler both use this pattern:
> ```java
> Condition condition = ((Optional<Condition>) jv
>     .get("condition")
>     .as(JsonValues.optionalOf(Condition.condition(initialBindings()))))
>     .orElse(Condition.ALWAYS_TRUE);
> ```
>
> **Null-safe evaluation.** If an expression evaluates to `null`, Condition
> treats it as `false` and logs a warning. This prevents NPEs from missing
> context variables.
>
> **Async-first design.** Even static boolean conditions return a `Promise`,
> keeping the entire evaluation pipeline non-blocking.


## 8. Decorator Model

### Decorator Interface

A Decorator adds new behavior to another object without changing the base
type of the object. PingGateway provides several standard decorators and
supports custom decorators.

### Standard Decorators

| Decorator | Purpose | Compatible With |
|-----------|---------|----------------|
| `baseURI` | Overrides scheme, host, port of target URI | Filters, Handlers |
| `capture` | Logs request/response/context data (JSON) | Filters, Handlers |
| `timer` | Records processing time | Filters, Handlers |
| `tracing` | Distributed tracing (OpenTelemetry) | Filters, Handlers |

### Applying Decorators

**On individual objects** (highest precedence):
```json
{
  "type": "SingleSignOnFilter",
  "capture": "all",
  "config": { ... }
}
```

**On the route handler:**
```json
{
  "capture": "all",
  "handler": {
    "type": "Chain",
    "config": { ... }
  }
}
```

**On all objects in a route (globalDecorators):**
```json
{
  "globalDecorators": {
    "capture": "all",
    "timer": true
  },
  "handler": { ... }
}
```

### Decorator Precedence Order

1. Decorations on individual objects (local, inherited wherever object is used)
2. `globalDecorators` in parent routes → child routes → current route
3. Decorations on the route handler

### CaptureDecorator

The `capture` decorator logs requests, responses, and context data in JSON
form. Useful during development; disable in production.

```json
{
  "name": "capture",
  "type": "CaptureDecorator",
  "config": {
    "captureEntity": true
  }
}
```

Capture modes: `"all"`, `"request"`, `"response"`.

### Custom Decorator Names

> **Naming rule:** PingGateway reserves all field names that use only
> alphanumeric characters. To avoid clashes with future PG releases, use
> dots or dashes in custom decorator names (e.g., `my-decorator`).

> **For message-xform:** Decorators are not directly relevant to the
> adapter implementation, but understanding `capture` is critical for
> debugging filter behavior during development.

---


## 9. Deployment, Classloading & SPI Registration

### Extension JAR Location

Custom extensions are deployed as JAR files to the PingGateway extra directory:

| OS | Path |
|----|------|
| Linux | `$HOME/.openig/extra/` |
| Windows | `%appdata%\OpenIG\extra\` |

```
$HOME/.openig/
├── config/
│   ├── admin.json              ← admin/connector config
│   ├── config.json             ← global config (session, default handler)
│   └── routes/
│       └── 10-transform.json   ← route using our filter
└── extra/
    └── message-xform-pinggateway-<version>-shadow.jar
```

> When PingGateway starts up, the JVM loads `.jar` files in the `extra` directory.

### Shadow JAR Contents

The adapter shadow JAR must contain:
- Adapter classes (`io.messagexform.pinggateway.*`)
- Core engine (`io.messagexform.core.*`)
- `META-INF/services/org.forgerock.openig.alias.ClassAliasResolver`
- Runtime dependencies NOT provided by PingGateway: JSLT, SnakeYAML,
  JSON Schema Validator

**Excluded** (PingGateway-provided — `compileOnly`):

| Library | Notes |
|---------|-------|
| `jackson-databind` / `jackson-core` / `jackson-annotations` | PG bundles Jackson. `Entity.getJson()` uses it internally. |
| `slf4j-api` | PG provides SLF4J backend |
| PingGateway/CHF API | `openig-core`, `chf-http-core`, `chf-http-servlet` |
| `forgerock-util` | Promise API, `JsonValue` |

> **Do NOT bundle or relocate Jackson.** PingGateway provides Jackson on the
> classpath. Bundling Jackson causes version conflicts, just like PingAccess
> (see PA SDK Guide §9).

### Dependency Sources

Unlike PingAccess (which has a standalone SDK JAR), PingGateway does **not**
publish a standalone SDK artifact. Compile-time dependencies come from:

1. **ForgeRock Maven repository** — `https://maven.forgerock.org/artifactoryapi/repo/`
   containing `org.forgerock.http:chf-http-core`, `org.forgerock.openig:openig-core`
2. **PingGateway distribution extraction** — download PingGateway ZIP, extract
   JARs to `libs/pinggateway/` (same pattern as `libs/pingaccess-sdk/`)

### Restarts

> ⚠️ **Mandatory Restart:** You **must** restart PingGateway after deploying
> any custom extension JAR. There is no hot-reload mechanism for JARs in the
> `extra` directory. Routes, however, can be hot-reloaded.

### Docker Deployment

For Docker-based deployments, mount the JAR:

```yaml
volumes:
  - ./message-xform-pinggateway.jar:/home/forgerock/.openig/extra/message-xform-pinggateway.jar
  - ./routes:/home/forgerock/.openig/config/routes
```

---


---

### Purpose

A `ClassAliasResolver` allows using short names (aliases) instead of
fully qualified class names in route configuration:

```json
// Without alias — fully qualified name required:
{ "type": "org.forgerock.openig.doc.examples.SampleFilter" }

// With alias — short name:
{ "type": "SampleFilter" }
```

### Implementation

```java
package org.forgerock.openig.doc.examples;

import static java.util.stream.Collectors.toUnmodifiableSet;
import java.util.*;
import org.forgerock.openig.alias.ClassAliasResolver;
import org.forgerock.openig.heap.Heaplet;
import org.forgerock.openig.heap.Heaplets;

public class SampleClassAliasResolver implements ClassAliasResolver {
    private static final Map<String, Class<?>> ALIASES = new HashMap<>();
    static {
        ALIASES.put("SampleFilter", SampleFilter.class);
    }

    @Override
    public Class<?> resolve(final String alias) {
        return ALIASES.get(alias);
    }

    @Override
    public Set<Class<? extends Heaplet>> supportedTypes() {
        return ALIASES.values()
            .stream()
            .map(Heaplets::findHeapletClass)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(toUnmodifiableSet());
    }
}
```

### Service Registration

Create a file in your JAR at:
```
META-INF/services/org.forgerock.openig.alias.ClassAliasResolver
```

Contents (one FQCN per line):
```
io.messagexform.pinggateway.MessageTransformClassAliasResolver
```

### Alias Naming Guidelines

> When suggesting custom names, know that **PingGateway reserves all field
> names that use only alphanumeric characters**. To avoid clashes with
> future PingGateway releases, consider using dots or dashes in your aliases
> (e.g., `message-xform-filter`). However, for simplicity, a unique name like
> `MessageTransformFilter` is unlikely to clash.

### Heaplet Discovery

PingGateway discovers Heaplets automatically via the nested static class
pattern. **No separate service registration is needed for Heaplets** — 
PingGateway calls `Heaplets.findHeapletClass(aliasedClass)` to locate
the inner `Heaplet` class.

---


### SPI Registration (META-INF/services)

Extensions are discovered via Java `ServiceLoader`. The key SPI files in
`openig-core-2025.11.1.jar`:

```
META-INF/services/org.forgerock.openig.alias.ClassAliasResolver
META-INF/services/org.forgerock.openig.heap.DefaultDeclarationProvider
META-INF/services/org.forgerock.openig.decoration.timer.TimerFactory

META-INF/services/org.forgerock.openig.el.resolver.Resolver
META-INF/services/org.forgerock.http.HttpApplication
META-INF/services/org.forgerock.openig.decoration.baseuri.BaseUriFactory
META-INF/services/org.forgerock.openig.decoration.tracing.TracingDecoratorFactory
META-INF/services/org.forgerock.openig.decoration.capture.CaptureFactory
```

For our adapter, we need in `META-INF/services/`:
1. `org.forgerock.openig.alias.ClassAliasResolver` → our resolver class
2. Optionally: `org.forgerock.openig.heap.DefaultDeclarationProvider` to
   auto-provision default heap objects.


---

# Part III — Cross-Cutting Concerns

---

## 10. Error Handling & Response Construction

### Constructing Error Responses

Unlike PingAccess (which has `ResponseBuilder` with static factories),
PingGateway uses direct construction of `Response` objects:

```java
// Direct construction
Response errorResponse = new Response(Status.BAD_GATEWAY);
errorResponse.getHeaders().put("Content-Type", "application/problem+json");
errorResponse.getEntity().setString(
    "{\"type\":\"transform-error\",\"status\":502,\"title\":\"Bad Gateway\"}"
);
```

### DENY Mode — Request Phase

During the request phase, short-circuit by returning a response without
calling `next.handle()`:

```java
@Override
public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    TransformResult result = transformEngine.transform(wrappedRequest);
    if (result.isError() && errorMode == ErrorMode.DENY) {
        Response errorResponse = new Response(Status.BAD_GATEWAY);
        errorResponse.getHeaders().put("Content-Type", "application/problem+json");
        errorResponse.getEntity().setBytes(
            objectMapper.writeValueAsBytes(result.errorResponse())
        );
        // Return directly — do NOT call next.handle()
        return Promises.newResultPromise(errorResponse);
    }
    // ALLOW — forward transformed request
    return next.handle(context, request);
}
```

### DENY Mode — Response Phase

During the response phase, replace the response in-place:

```java
return next.handle(context, request)
    .thenOnResult(response -> {
        TransformResult result = transformEngine.transform(wrappedResponse);
        if (result.isError() && errorMode == ErrorMode.DENY) {
            response.setStatus(Status.BAD_GATEWAY);
            response.getHeaders().put("Content-Type", "application/problem+json");
            response.getEntity().setBytes(
                objectMapper.writeValueAsBytes(result.errorResponse())
            );
        }
    });
```

### ResponseUtils

PingGateway provides `ResponseUtils` for common error response patterns:

```java
// org.forgerock.openig.http.protocol.ResponseUtils
static Promise<Response, NeverThrowsException> newIllegalStateExceptionPromise(
    Exception exception)
// Creates a promise resolved with an INTERNAL_SERVER_ERROR response
// wrapping the given exception.
```

### Comparison with PingAccess Error Handling

| Aspect | PingAccess | PingGateway |
|--------|------------|-------------|
| Error response factory | `ResponseBuilder.badGateway()` etc. | `new Response(Status.BAD_GATEWAY)` |
| Request-phase DENY | `exchange.setResponse(response); return RETURN;` | Return `Promise` with response directly |
| Response-phase DENY | Modify `exchange.getResponse()` in-place | Modify `response` in `thenOnResult()` |
| Error callback | `ErrorHandlingCallback` interface | No equivalent — handle inline |
| AccessException | PA's `AccessException` for pipeline abort | Not applicable — use `Response` directly |

---


### Response Construction (Full API)

```java
// org.forgerock.http.protocol.Response
public final class Response extends MessageImpl<Response> {
    // Constructors:
    public Response(Status status)             // primary constructor
    public Response(Response response) throws IOException  // copy constructor

    // Static factory methods for Promise wrapping:
    public static Promise<Response, NeverThrowsException> newResponsePromise(Response response)
    public static PromiseImpl<Response, NeverThrowsException> newResponsePromiseImpl()

    // Fluent setters (all return 'this'):
    public Response setStatus(Status status)
    public Response setCause(Exception cause)  // NOTE: Exception, not Throwable
    public Response setEntity(Object o)
    public Response setVersion(String version)
    public Response setStreamingContent(boolean streamingContent)

    // Trailers (HTTP/2):
    public Headers getTrailers()
    public Response addTrailers(Header... trailers)
    public Response putTrailers(Header... trailers)
}
```

> **`setCause()` takes Exception, not Throwable.** The API accepts `Exception`.


## 11. Thread Safety & Concurrency Patterns

### Threading Model

PingGateway uses Vert.x as its HTTP server, which employs an event-loop
threading model. Filter instances are shared across threads and may be
invoked concurrently.

### Thread Safety Rules

**Init-time state (safe for concurrent reads):**
- Fields set in `Heaplet.create()` — configuration values, engine references
- These are effectively immutable after initialization

**Per-request state (MUST be method-local):**
- Any state derived from a specific request (parsed bodies, transform results)
  MUST be local to the `filter()` method
- Do NOT store per-request state in instance fields

```java
public class MessageTransformFilter implements Filter {
    // ✅ SAFE: set once during create(), never modified
    private TransformEngine engine;
    private ErrorMode errorMode;

    // ⛔ WRONG: per-request state in instance field
    // private TransformResult lastResult;

    @Override
    public Promise<Response, NeverThrowsException> filter(
            Context context, Request request, Handler next) {
        // ✅ CORRECT: per-request state is method-local
        TransformResult result = engine.transform(wrapRequest(context, request));
        // ...
    }
}
```

### Session Thread Safety

> **Session sharing is not thread-safe.** It is not suitable for concurrent
> exchanges modifying the same session. This is explicitly documented in
> PingGateway's session architecture.

---


## 12. Testing Patterns

### Creating Test Request/Response Objects

Unlike PingAccess (where `Request`/`Response`/`Exchange` must be mocked),
PingGateway's CHF types have **public constructors** and can be instantiated
directly:

```java
// Create a real Request
Request request = new Request();
request.setMethod("POST");
request.setUri("https://example.com/api/users");
request.getHeaders().put("Content-Type", "application/json");
request.getEntity().setString("{\"name\":\"test\"}");

// Create a real Response
Response response = new Response(Status.OK);
response.getHeaders().put("Content-Type", "application/json");
response.getEntity().setString("{\"id\":1}");
```

### Testing the Filter

```java
@Test
void testFilterModifiesRequestAndResponse() throws Exception {
    // Setup
    Request request = new Request();
    request.setMethod("GET");
    request.setUri("https://example.com/api");
    request.getHeaders().put("X-Original", "value");

    // Mock next handler to return a response
    Response backendResponse = new Response(Status.OK);
    backendResponse.getEntity().setString("{\"key\":\"value\"}");
    Handler next = (ctx, req) -> Promises.newResultPromise(backendResponse);

    // Create a minimal context
    Context context = new RootContext();

    // Invoke filter
    MessageTransformFilter filter = new MessageTransformFilter();
    // ... configure filter ...
    Response result = filter.filter(context, request, next)
        .getOrThrow();  // OK in tests — no deadlock risk

    // Assert
    assertThat(result.getStatus()).isEqualTo(Status.OK);
    assertThat(result.getHeaders().getFirst("X-Custom")).isEqualTo("expected");
}
```

> **`getOrThrow()` in tests:** The non-blocking mandate applies to production
> code. In unit tests, using `getOrThrow()` is safe because there is no
> event-loop to deadlock against.

### Testing Context Chain

```java
// Build a context chain for testing
RootContext root = new RootContext();
SessionContext session = new SessionContext(root, new SimpleMapSession());
session.getSession().put("user", "testuser");
AttributesContext attrs = new AttributesContext(session);
attrs.getAttributes().put("routeParam", "value");

// Pass to filter
filter.filter(attrs, request, next);
```

### Comparing with PingAccess Testing

| Aspect | PingAccess | PingGateway |
|--------|------------|-------------|
| Request/Response | Must mock (no public constructors) | Instantiate directly |
| Exchange | Must mock | No Exchange concept |
| Context | Part of Exchange mock | Build context chain with constructors |
| Handler/Next | Not directly mockable | Simple lambda `(ctx, req) -> promise` |
| Promise blocking | `CompletableFuture.get()` | `Promise.getOrThrow()` (tests only) |
| ResponseBuilder | Requires `ServiceFactory` (prod runtime only) | `new Response(Status.xxx)` works anywhere |

---


## 13. Observability (Logging, Metrics & Audit)

### Logging

### Logging Framework

PingGateway uses **SLF4J** with **Logback** as the backend. Custom extensions
use SLF4J for logging:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageTransformFilter implements Filter {
    private static final Logger logger =
        LoggerFactory.getLogger(MessageTransformFilter.class);

    @Override
    public Promise<Response, NeverThrowsException> filter(
            Context context, Request request, Handler next) {
        logger.debug("Processing request: {} {}",
            request.getMethod(), request.getUri().getPath());
        // ...
    }
}
```

### Log Locations

| Log | Path | Content |
|-----|------|---------|
| System log | `$HOME/.openig/logs/route-system.log` | Route loading, errors, config events |
| Route-specific logs | `$HOME/.openig/logs/route-<name>.log` | Per-route request/response activity |
| Console (stdout) | Standard output | Startup messages, audit events |

### Logback Configuration

Customize logging via `logback.xml` in the PingGateway configuration.
Add loggers for custom extension packages:

```xml
<logger name="io.messagexform" level="DEBUG" />
```

### CaptureDecorator for Development Logging

During development, the `capture` decorator is the most convenient way to
log full request/response data including headers and entity:

```json
{
  "capture": "all",
  "handler": {
    "type": "Chain",
    "config": {
      "filters": [
        {
          "type": "MessageTransformFilter",
          "capture": "all",
          "config": { ... }
        }
      ],
      "handler": "ReverseProxyHandler"
    }
  }
}
```

> **Production:** Disable `capture` and reduce log levels to `WARN` or
> `ERROR` for production deployments.

---


---

### Audit Framework & Custom Audit Events

### Audit Architecture

PingGateway provides a pluggable audit framework. Audit events are published
to configured event handlers on configured topics.

### Standard Audit Topics

| Topic | Events |
|-------|--------|
| `access` | All HTTP request/response access events |
| `config` | Configuration changes |
| Custom topics | User-defined schemas in `$HOME/.openig/audit-schemas/` |

### Custom Audit Topic Schema

Define a JSON schema file in `$HOME/.openig/audit-schemas/<TopicName>.json`:

```json
{
  "schema": {
    "$schema": "http://json-schema.org/draft-04/schema#",
    "id": "TransformAudit",
    "type": "object",
    "properties": {
      "_id": { "type": "string" },
      "timestamp": { "type": "string" },
      "transactionId": { "type": "string" },
      "eventName": { "type": "string" },
      "specId": { "type": "string" },
      "transformResult": { "type": "string" }
    }
  },
  "filterPolicies": {
    "field": {
      "includeIf": [
        "/_id", "/timestamp", "/eventName",
        "/transactionId", "/specId", "/transformResult"
      ]
    }
  },
  "required": ["_id", "timestamp", "transactionId", "eventName"]
}
```

### AuditService Configuration

```json
{
  "name": "AuditService-1",
  "type": "AuditService",
  "config": {
    "config": {},
    "eventHandlers": [
      {
        "class": "org.forgerock.audit.handlers.json.stdout.JsonStdoutAuditEventHandler",
        "config": {
          "name": "jsonstdout",
          "elasticsearchCompatible": false,
          "topics": ["TransformAuditTopic"]
        }
      }
    ]
  }
}
```

### Publishing Audit Events from Java

```java
// Build an audit event
JsonValue auditEvent = json(object(
    field("eventName", "TransformEvent"),
    field("transactionId",
        context.asContext(TransactionIdContext.class)
            .getTransactionId().getValue()),
    field("timestamp", clock.instant().toEpochMilli())
));

// Send to audit service
auditService.handleCreate(context,
    newCreateRequest(resourcePath("/TransformAuditTopic"), auditEvent))
    .thenOnException(e -> logger.warn("Audit event failed", e));
```

> **For message-xform:** Custom audit events could be used to log transform
> results (specId, success/failure, latency). This is an alternative to
> JMX metrics for audit-trail compliance use cases. We could add an optional
> `AuditService` reference to the filter's Heaplet config.

---


---

# Part IV — Implementation Patterns Catalog

---

## 14. Routing & Dispatch Patterns

### Condition-Based Handler Routing Pattern

The `DispatchHandler` evaluates a list of condition→handler bindings in order,
dispatching to the first matching handler. It is the primary mechanism for
conditional routing inside a route.

```java
// Core dispatch loop (recursive async)
Promise<Response, NeverThrowsException> handle0(
        Iterator<Binding> iterator, Bindings bindings,
        Context context, Request request) {
    if (!iterator.hasNext()) {
        // No handler matched — return 404
        return ResponseUtils.newEmptyResponseAsync(Status.NOT_FOUND);
    }
    Binding binding = iterator.next();
    return binding.condition.test(expressionBindings)
        .thenAsync(matched -> matched
            ? processBinding(expressionBindings, context, request, binding)
            : handle0(iterator, expressionBindings, context, request));
}

// Each binding can optionally rebase the request URI
record Binding(Condition condition, Handler handler, Expression<String> baseURI) { }
```

**Heaplet configuration:**

```java
static final String CONFIG_BINDINGS = "bindings";
static final String CONFIG_BINDINGS_CONDITION = "condition";   // optional (defaults ALWAYS_TRUE)
static final String CONFIG_BINDINGS_HANDLER = "handler";       // required
static final String CONFIG_BINDINGS_BASE_URI = "baseURI";      // optional Expression<String>
```

> **First-match-wins semantics.** The dispatch loop is short-circuiting — once
> a condition matches, no further bindings are evaluated. The final binding
> typically omits the condition (defaulting to `ALWAYS_TRUE`) as a catch-all.
>
> **Base URI rebasing.** When `baseURI` is specified, the request's URI is rebased
> before forwarding. This enables routing to different backends based on conditions.

### Iterative Handler Chaining Pattern

The `SequenceHandler` executes handlers **sequentially**, using a post-condition
on each binding to decide whether to continue to the next handler:

```java
// Sequential execution with post-conditions
record Binding(Handler handler, Condition postCondition) { }

private Promise<Response, NeverThrowsException> handle0(
        Iterator<Binding> iterator, Context context, Request request) {
    Binding binding = iterator.next();
    return binding.handler.handle(context, request)
        .thenAsync(result -> binding.postCondition
            .test(Bindings.bindings(context, request, result))
            .thenAsync(continueChain ->
                continueChain && iterator.hasNext()
                    ? handle0(iterator, context, request)
                    : Promises.newResultPromise(result)));
}
```

> **Post-condition pattern.** Unlike DispatchHandler (which uses pre-conditions
> to select *which* handler to run), SequenceHandler uses post-conditions to
> decide *whether to continue*. The post-condition has access to the response,
> enabling patterns like "continue only if status is 2xx".
>
> **Use case: Multi-step orchestration.** Call handler A, check its response,
> conditionally call handler B. Each step can inspect the cumulative state.


### Dual-Phase Condition Routing Pattern
>
> This pattern implements dual-phase routing where conditions can be
> evaluated against both the request (pre-delegation) and the response (post-delegation).
> This is distinct from `DispatchHandler` which only evaluates request-phase conditions.
>
> **Architecture:**
> - Maintains two separate `List<Case>` — one for `onRequest`, one for `onResponse`
> - Each `Case` pairs a `Condition` with a `Handler`
> - **Request phase:** evaluates cases first. If a match is found, the matching handler
>   is invoked *instead* of the next handler. If no request case matches, the request
>   proceeds downstream
> - **Response phase:** after downstream returns, evaluates response cases with full
>   `Bindings(context, request, response)`. If a match is found, the matching handler
>   replaces the response. If no match, the original response passes through
> - Uses `Filters.requestCopyFilter()` to guard the downstream handler, ensuring
>   the original request is preserved for potential response-phase handler invocation
>
> **Recursive async dispatch pattern:**
> ```java
> private Promise<Response, NeverThrowsException> doSwitch0(
>         Iterator<Case> caseIterator, Context context,
>         Request request, Bindings bindings) {
>     if (!caseIterator.hasNext()) {
>         return Promises.newResultPromise(null);  // sentinel → no match
>     }
>     Case current = caseIterator.next();
>     return current.condition.test(bindings)
>         .thenAsync(isTrue -> isTrue
>             ? current.handler.handle(context, request)
>             : this.doSwitch0(caseIterator, context, request, bindings));
> }
> ```
>
> **Key patterns:**
> - `Builder` with `addRequestCase()` / `addResponseCase()` for typed construction
> - Heaplet uses `JsonValues.optionalOf(Condition.condition(...))` with
>   `orElse(Condition.ALWAYS_TRUE)` — same optional-condition pattern as `AssignmentFilter`
> - Heap object resolution: `JsonValues.requiredHeapObject(heap, Handler.class)`
>   to resolve handler references from JSON config


### Config-Driven Filter Chain Pattern
>
> The `Chain` heaplet is the config-level entry point for assembling filter → handler
> pipelines from JSON configuration. It is the most commonly used heaplet in route
> definitions.
>
> **Complete implementation:**
> ```java
> public Object create() throws HeapException {
>     Handler terminus = config.get("handler")
>         .as(requiredHeapObject(heap, Handler.class));
>     List<Filter> filters = config.get("filters")
>         .required().expect(List.class)
>         .as(listOf(requiredHeapObject(heap, Filter.class)));
>     return Handlers.chainOf(terminus, filters);
> }
> ```
>
> **Key patterns:**
> - **`JsonValueFunctions.listOf()`** transforms a JSON array into a typed Java list,
>   resolving each element through the heap
> - **`Handlers.chainOf(Handler, List<Filter>)`** is the list-based chain factory
>   (vs. the varargs `chainOf(Handler, Filter...)` form)
> - This is the "glue" between declarative JSON config and the programmatic
>   `Handlers.chainOf()` API — extension developers rarely need this pattern
>   directly but should understand how their filters get composed


### Dynamic Route Management Pattern (CAS)
>
> This pattern demonstrates production-grade concurrent data structure management, file-system
> monitoring, and CREST API exposure for a dynamic route registry.
>
> **Lock-free route set management with `AtomicReference<Set<Route>>`:**
> ```java
> // Copy-on-write pattern for route addition
> Set<Route> oldRoutes = routesRef.get();
> TreeSet<Route> newRoutes = new TreeSet<>(new LexicographicalRouteComparator());
> newRoutes.addAll(oldRoutes);
> newRoutes.add(route);
> if (!routesRef.compareAndSet(oldRoutes, newRoutes)) {
>     route.destroy();  // CAS failed — concurrent modification
>     throw new RouterHandlerException("Concurrent write operations in progress");
> }
> ```
>
> **Recursive async route matching (same pattern as SwitchFilter):**
> ```java
> private Promise<Response, NeverThrowsException> handle0(
>         Iterator<Route> iterator, Context context,
>         Request request, Bindings bindings) {
>     if (!iterator.hasNext()) {
>         return defaultRoute.handle(context, request);
>     }
>     Route route = iterator.next();
>     return route.acceptAsync(bindings)
>         .thenAsync(result -> result
>             ? route.handle(context, request)
>             : handle0(iterator, context, request, bindings));
> }
> ```
>
> **Key patterns:**
> - **Copy-on-write via CAS:** `AtomicReference<Set<Route>>` avoids locks while
>   maintaining a consistent snapshot for readers. Writers create a new set and
>   `compareAndSet()`; on failure, they destroy the new route and throw
> - **`LexicographicalRouteComparator`** — `TreeSet` ensures deterministic
>   routing order (alphabetical by route name)
> - **Route lifecycle:** `route.start()` / `route.destroy()` — routes have
>   full start/stop lifecycle managed by the router
> - **File-system monitoring:** `DirectoryMonitor` + `ScheduledExecutorService`
>   with configurable `scanInterval` (default `"10 seconds"`)
> - **`onChanges(FileChangeSet)`** — handles added, removed, and modified files
>   (modified = remove + add)
> - **CREST API exposure:** `endpointRegistry().tryRegister("routes", ...)` exposes
>   routes as a REST resource collection at runtime
> - **Metrics gauge:** `Gauge.doubleGauge("deployed-routes", ...)` for monitoring
> - **`failOnRouteError` flag:** controls whether bad routes crash the router
>   (prod) or are logged and skipped (dev)
> - **Static route directory deduplication:** `ROUTE_DIRECTORIES` (synchronized
>   `HashSet`) prevents two routers from sharing the same directory


---

## 15. Request & Response Transformation Patterns

### Bidirectional Path Rewriting Pattern

The `UriPathRewriteFilter` performs path prefix mapping on requests and
automatically reverse-maps `Location` and `Content-Location` response headers:

```java
// Path mapping: from→to on request, to→from on response headers
static class PathMapping {
    PathMapping(String fromPath, String toPath)
    String rewritePath(String path)         // fromPath + rest → toPath + rest
    String rewriteInversePath(String path)  // toPath + rest → fromPath + rest
}

// Longest-prefix-match selection
Optional<PathMapping> findLongestMapping(String path)
// When multiple mappings could match, the longest 'from' path wins

// Response header rewriting
static void rewriteResponseHeaders(MutableUri baseUri, PathMapping mapping,
                                    Headers responseHeaders)
// Rewrites: Location, Content-Location
// Only rewrites if the header URI starts with 'toPath' and is same-origin
```

**Heaplet pattern:**

```java
static final String CONFIG_MAPPINGS = "mappings";          // required Map<String, String>
static final String CONFIG_FAILURE_HANDLER = "failureHandler"; // optional (default: 500)

// Mappings are evaluated as expressions at creation time
Handler failureHandler = config.get("failureHandler")
    .as(JsonValues.optional())
    .map(rethrowFunction(v -> v.as(requiredHeapObject(heap, Handler.class))))
    .orElse(Handlers.INTERNAL_SERVER_ERROR);
```

> **Bidirectional rewriting.** Request paths are rewritten from→to; response
> `Location`/`Content-Location` headers are rewritten to→from. This ensures
> redirect responses point to the public-facing path, not the backend path.
>
> **Configurable failure handler.** When URI rewriting produces an invalid URI,
> the filter delegates to a configurable failure handler (defaulting to 500).


### Conditional Expression Assignment Pattern
>
> This pattern demonstrates the **conditional write-back** pattern: evaluating
> expressions and assigning the result to writable targets (`LeftValueExpression`).
> It operates on both request and response phases.
>
> **Core mechanism:**
> ```java
> // Binding record — condition + target + value
> record Binding(Condition condition,
>                LeftValueExpression<?> target,
>                Expression<?> value) { }
>
> // Recursive async evaluation of bindings
> private Promise<Void, NeverThrowsException> evalBindings(
>         Iterator<Binding> iterator, Bindings bindings) {
>     if (!iterator.hasNext()) {
>         return Promises.newVoidResultPromise();
>     }
>     Binding binding = iterator.next();
>     return binding.condition.test(bindings)
>         .thenAsync(met -> met
>             ? assignValue(binding.target, binding.value, bindings)
>             : Promises.newVoidResultPromise())
>         .thenAsync(unused -> evalBindings(iterator, bindings));
> }
> ```
>
> **Key patterns:**
> - **`LeftValueExpression.set(bindings, value)`** — the writable expression API.
>   Only `LeftValueExpression` supports `.set()`; regular `Expression` is read-only
> - **Null value assignment:** when `value` is null, the target is set to null
>   (clearing the attribute)
> - Uses `AsyncFunctions.asyncResultHandler()` for response-phase evaluation
>   (process-then-pass-through)
> - **Java 16+ `record`** type used for immutable `Binding` data class
> - Heaplet parses `onRequest` / `onResponse` arrays with per-binding
>   `condition`, `target`, and `value` fields


### Dynamic Response Construction Pattern
>
> This pattern builds a complete HTTP response from configuration, with full expression-language
> support in status, headers, trailers, and entity. Demonstrates the parallel
> expression evaluation pattern.
>
> **Parallel evaluation with `Promises.when()`:**
> ```java
> public Promise<Response, NeverThrowsException> handle(
>         Context context, Request request) {
>     Bindings bindings = Bindings.bindings(context, request);
>     Response response = new Response(this.status);
>     List<Promise> promiseList = new ArrayList<>();
>
>     // Evaluate all header expressions in parallel
>     headers.forEach((key, expressions) ->
>         evaluate(expressions, bindings,
>             value -> responseHeaders.add(key, value), promiseList));
>
>     // Evaluate entity expression
>     if (entity.isPresent()) {
>         promiseList.add(entity.get().evalAsync(bindings)
>             .then(response::setEntity).thenDiscardResult());
>     }
>
>     // Wait for all evaluations to complete
>     return Promises.when(promiseList).then(unused -> response);
> }
> ```
>
> **Key patterns:**
> - **`Promises.when(List<Promise>)`** — fans out expression evaluations and
>   joins all results before returning the response
> - **`X-Content-Type-Options: nosniff`** header added automatically
>   (security hardening)
> - **Content-Length: 0** header added when entity is absent and status is
>   not informational / 204 / 304
> - **Trailer support:** headers with `Trailer` announcement
> - **Entity as string or string array:** array elements are concatenated
> - Uses `PromiseUtil.consumeIfPresent()` for null-safe expression results


### Header Manipulation with Expression Evaluation Pattern
>
> This pattern demonstrates header manipulation (add, remove, replace) with
> async expression evaluation for header values.
>
> **Recursive async expression evaluation for added headers:**
> ```java
> private Promise<Void, NeverThrowsException> evalExpressions(
>         Iterator<Expression<String>> iterator, Bindings bindings,
>         Headers headers, String key) {
>     if (!iterator.hasNext()) return Promises.newVoidResultPromise();
>     return iterator.next().evalAsync(bindings)
>         .thenOnResult(value -> { if (value != null) headers.add(key, value); })
>         .thenAsync(unused -> evalExpressions(iterator, bindings, headers, key));
> }
> ```
>
> **Key patterns:**
> - **Remove-before-add for replacement:** `Builder.replaceHeader()` calls
>   `removeHeader()` then `addHeader()` — simple but effective
> - **`MessageType` enum** — `REQUEST` or `RESPONSE` determines which phase
>   processes the headers. Uses `AsyncFunctions.asyncResultHandler()` for
>   response-phase processing
> - **`MultiValueMap<String, Expression<String>>`** with `CaseInsensitiveMap` —
>   supports multiple expression values per header name
> - **`CaseInsensitiveSet`** for removed header names


### Outbound Request Factory Pattern
>
> Constructs a completely new outbound `Request` with expression-evaluated
> URI, method, headers, form parameters, and entity. Demonstrates the
> full request lifecycle including cleanup.
>
> **Request construction with guaranteed cleanup:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     Bindings bindings = Bindings.bindings(context, request);
>     Request newRequest = new Request();
>     newRequest.setMethod(method);
>     version.ifPresent(newRequest::setVersion);
>
>     return uri.evalAsync(bindings)
>         .then(newUri -> setUri(newRequest, newUri))
>         .thenAsync(unused -> entity.isEmpty()
>             ? Promises.newVoidResultPromise()
>             : entity.get().evalAsync(bindings)
>                 .then(newRequest::setEntity).thenDiscardResult())
>         .thenAsync(unused -> processHeaders(bindings, newRequest))
>         .thenAsync(unused -> processForm(bindings, newRequest))
>         .thenAsync(unused -> next.handle(context, newRequest))
>         .thenAlways(newRequest::close);  // always close
> }
> ```
>
> **Key patterns:**
> - **`thenAlways(newRequest::close)`** — ensures the constructed request
>   is closed after handler processing, preventing resource leaks
> - **Form vs entity mutual exclusion:** for POST requests, `form` and
>   `entity` cannot both be configured (Heaplet enforces this)
> - **Smart form injection:** POST → form encoded in entity body;
>   GET → form parameters appended to query string
> - **Recursive async entry processing:** `processEntry()` iterates over
>   multi-valued maps of expressions using the same recursive async
>   pattern seen in HeaderFilter
> - **Expression-evaluated keys:** header and form parameter names are
>   themselves expressions, evaluated at Heaplet creation time


---

## 16. Security Patterns (Auth, CORS, CSRF, JWT)

### Guard Gate Pattern
>
> This pattern implements the simplest and most reusable access-control pattern. Evaluates a `Condition`
> against the request; if true, proceeds to `next`; if false, delegates to a
> configurable `failureHandler` (defaults to `Handlers.FORBIDDEN`).
>
> **Implementation (entire filter logic in one line):**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     return this.condition.test(Bindings.bindings(context, request))
>         .thenAsync(verified ->
>             (verified ? next : failureHandler).handle(context, request));
> }
> ```
>
> **Reuse pattern:**
> - Used internally by `OAuth2ResourceServerFilter` to enforce HTTPS:
>   ```java
>   Condition mustUseHttps = new Condition(
>       "${contexts.router.originalUri.scheme == 'https'}");
>   return Filters.chainOf(
>       new ConditionEnforcementFilter(mustUseHttps), resourceServerFilter);
>   ```
> - Composable: multiple enforcement filters can be chained with `Filters.chainOf()`
> - Heaplet supports optional `failureHandler` with fallback to `Handlers.FORBIDDEN`


### Self-Documenting Predicate Composition Pattern
>
> This pattern demonstrates self-documenting predicate composition using
> `DescribedPredicate<T>` for security token validation.
>
> **`DescribedPredicate<T>` — self-documenting predicates:**
> ```java
> private static class DescribedPredicate<T> implements Predicate<T> {
>     private final Predicate<T> predicate;
>     private final String description;
>
>     @Override public Predicate<T> and(Predicate<? super T> other) {
>         return describedAs(predicate.and(other),
>             "(" + description + " AND " + other + ")");
>     }
>     @Override public Predicate<T> or(Predicate<? super T> other) {
>         return describedAs(predicate.or(other),
>             "(" + description + " OR " + other + ")");
>     }
>     @Override public Predicate<T> negate() {
>         return describedAs(predicate.negate(), "NOT " + description);
>     }
>     @Override public String toString() { return description; }
> }
> ```
>
> **`EmptyPredicate` — identity element for OR composition:**
> ```java
> // EmptyPredicate.or(other) returns other — acts as identity for Predicate.or
> // EmptyPredicate.and(other) returns this — short-circuits AND
> // EmptyPredicate.test(t) always returns false
> ```
>
> **Key patterns:**
> - **SHA-256 token derivation:** CSRF token = SHA-256(session cookie value),
>   compared using `MessageDigest.isEqual()` (constant-time comparison)
> - **Builder fluent API:** `excludeSafeMethods()`, `excludePaths(...)`,
>   `excludePathsStarting(...)` — each wraps in a `DescribedPredicate`
> - **Response-phase token injection:** CSRF token added to response headers
>   whenever a new session cookie is set (Set-Cookie detection)
> - **`Handlers.forbiddenHandler()`** — default failure handler


### Credential Caching + Auth Retry Pattern
>
> This pattern demonstrates a two-pass authentication approach: attempt the request,
> and if a 401 challenge is received, retrieve credentials and retry.
>
> **Two-pass auth with session caching:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     Session session = context.asContext(SessionContext.class).getSession();
>     String cachedUserpass = (String) session.get(attributeName(request));
>     if (cacheHeader && cachedUserpass != null) {
>         // Pass 1: try cached credentials
>         setAuthorizationHeader(request.getHeaders(), cachedUserpass);
>         return wrappedNext.handle(context, request)
>             .thenAsync(ifUnauthorized(                    // if 401 →
>                 executeWithCredentials(context, request,  // Pass 2: re-evaluate
>                     wrappedNext)));
>     }
>     return executeWithCredentialsFilter().filter(context, request, wrappedNext);
> }
> ```
>
> **`IfUnauthorizedFunction` record — reusable auth retry logic:**
> ```java
> private record IfUnauthorizedFunction(
>         AsyncFunction<Void, Response, NeverThrowsException> onUnauthorized)
>     implements AsyncFunction<Response, Response, NeverThrowsException> {
>
>     public Promise<Response, NeverThrowsException> apply(Response response) {
>         if (!Status.UNAUTHORIZED.equals(response.getStatus())) {
>             // Success — suppress WWW-Authenticate header, relay response
>             for (String header : SUPPRESS_RESPONSE_HEADERS)
>                 response.getHeaders().remove(header);
>             return Response.newResponsePromise(response);
>         }
>         // 401 — close current response, retry with new credentials
>         Closeables.closeSilently(response);
>         return onUnauthorized.apply(null);
>     }
> }
> ```
>
> **Key patterns:**
> - **Session key scoping:** `className:scheme:host:port:userpass` — prevents
>   credential leaks across different upstream hosts
> - **Header suppression:** `Authorization` removed from request; `WWW-Authenticate`
>   removed from response (transparency)
> - **Colon validation:** username cannot contain `:` (RFC 7617)
> - **`Promises.when()` for parallel evaluation** of username + password expressions


### POST Body Preservation Across Redirects Pattern
>
> This pattern demonstrates preserving HTTP POST data across authentication
> redirects. It stores the form body in the session before the redirect
> and auto-submits it via an HTML form after the redirect completes.
> (POST) or generates a self-submitting HTML form (GET).
>
> **Auto-submit form for replay:**
> ```java
> private static final String PDP_FORM = """
>     <!DOCTYPE html>
>     <html>
>      <body onload='document.pdp_form.submit();'>
>        <form id='post_auth' name='pdp_form'
>              action='%s' enctype='%s' method='post'>
>          <noscript><input type='submit' value='%s'/></noscript>
>        </form>
>      </body>
>     </html>""";
> ```
>
> **Key patterns:**
> - **Session-based storage with random marker:** each redirect gets a unique
>   `_igdpf` query parameter (URL-friendly random string). Session key:
>   `dpf:` + marker. This prevents collisions across concurrent requests
> - **Expiration-based cleanup:** `preserveEntityData()` scans existing session
>   entries and removes expired ones (configurable `lifetime`, default 5 min)
> - **Binary entity support:** multipart/form-data entities are Base64-encoded
>   in session; regular form data is stored as-is
> - **`AuthRedirectContext`** — context chain element that signals the filter
>   to preserve data. Uses `isImpendingIgRedirectNotified()` to detect
>   when a downstream filter triggered a redirect
> - **Content-Length guard:** entities exceeding `maxContentLength` (default 4 KB)
>   are not preserved (memory protection)


### Correlation ID Propagation Pattern
>
> This pattern propagates correlation IDs across service boundaries
> with a configurable trust boundary.
>
> **Trust-aware correlation:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     if (context.containsContext(TransactionIdContext.class)) {
>         logger.trace("TransactionIdContext already exists");
>     }
>     TransactionId transactionId = trustTransactionIdHeader.getAsBoolean()
>         ? createTransactionId(request.getHeaders()) // trust upstream
>         : new TransactionId();                       // generate new
>     return next.handle(new TransactionIdContext(context, transactionId), request);
> }
> ```
>
> **Key patterns:**
> - **Trust boundary:** `org.forgerock.http.TrustTransactionHeader` system
>   property controls whether incoming `X-ForgeRock-TransactionId` headers
>   are trusted (internal network) or ignored (edge gateway)
> - **`BooleanSupplier` for lazy trust evaluation** — allows dynamic trust
>   configuration without filter recreation
> - **Graceful header parsing:** malformed transaction ID headers fall back
>   to generating a new ID (never fails)
> - **Context injection:** `TransactionIdContext` wraps the parent context
>   and is accessible downstream via `context.asContext(TransactionIdContext.class)`
> - **Paired with `TransactionIdOutboundFilter`** — the outbound filter
>   propagates the transaction ID to upstream requests via header


### Predicate-Based CORS Pattern
>
> Implements RFC 6454 CORS with a clean separation between the filter
> (request classification) and the policy (header generation). The policy
> uses composable predicates for origin, method, and header acceptance.
>
> **Request type classification via enum:**
> ```java
> private static enum RequestType {
>     NOT_CORS, PREFLIGHT, ACTUAL;
>     static RequestType of(Request request) {
>         if (!request.getHeaders().containsKey("Origin")) return NOT_CORS;
>         if (isCorsPreflightRequest(request))              return PREFLIGHT;
>         return ACTUAL;
>     }
> }
> ```
>
> **CorsPolicy builder with predicate composition:**
> ```java
> CorsPolicy.builder()
>     .acceptedOrigin("https://example.com")      // adds OR predicate
>     .acceptOrigin(origin -> origin.endsWith(".internal"))  // custom predicate
>     .acceptMethods(Builder.anyOf("GET", "POST"))
>     .acceptHeaders(Builder.containedInIgnoringCase("Content-Type", "X-Custom"))
>     .exposedHeader("X-Request-Id")
>     .maxAge(Duration.ofHours(1))
>     .allowCredentials(true)
>     .build();
> ```
>
> **Key patterns:**
> - **Enum-based request classification** — `switch` over request type
>   (NOT_CORS / PREFLIGHT / ACTUAL) for clean flow control
> - **Predicate algebra in builder:**
>   - `any()` — always-true predicate
>   - `none()` — always-false predicate (default)
>   - `anyOf(String...)` — HashSet membership test
>   - `containedInIgnoringCase(String...)` — case-insensitive TreeSet
> - **Origin normalization:** adds implicit default port (`:80` / `:443`)
>   for comparison when the configured origin omits the port
> - **`CorsPolicyProvider`** — strategy interface: `findApplicable(Context, origin)`
>   returns `Optional<CorsPolicy>`, enabling per-route CORS policies
> - **`response.modifyHeaders()`** — thread-safe header mutation API
> - **Separate handlers:** preflight returns `204 No Content` directly;
>   actual request passes through to `next` and decorates the response


### JWT Construction with Context Injection Pattern
>
> Constructs a JWT from an expression-evaluated template map and injects
> the resulting `Jwt` object into a `JwtBuilderContext` for downstream
> consumption.
>
> **Template evaluation → JWT creation → context chain:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     Bindings bindings = Bindings.bindings(context, request);
>     return Expressions.evaluateAsync(template, bindings)
>         .thenAsync(evaluated -> {
>             if (evaluated instanceof Map) {
>                 return jwtFactory.create((Map) evaluated)
>                     .thenAsync(jwt -> next.handle(
>                         new JwtBuilderContext(context, jwt), request),
>                     nsse -> Promises.newRuntimeExceptionPromise(
>                         new RuntimeException(nsse)));
>             }
>             return Promises.newRuntimeExceptionPromise(
>                 new IllegalArgumentException(
>                     "Template cannot be evaluated as a Map"));
>         });
> }
> ```
>
> **Key patterns:**
> - **`JwtBuilderContext`** — custom context that carries the built JWT.
>   Downstream filters/handlers access it via
>   `context.asContext(JwtBuilderContext.class).getJwt()`
> - **`Expressions.evaluateAsync()`** — generic deep evaluation: recursively
>   evaluates expressions within Maps and Lists, returning a Promise
> - **`JwtFactory` SPI:** `create(Map claims)` → `Promise<Jwt>`. The factory
>   handles signing (via `SecretsProvider`), algorithm selection, and key ID
> - **Template flexibility:** template can be a `Map` (with expression values)
>   or a `String` expression that evaluates to a Map at runtime


---

## 17. Resilience Patterns (Retry, Circuit Breaker, Throttling)

### Resilient Request Retry Pattern
>
> This pattern implements a production-grade retry mechanism with configurable
> delay, condition-based retry decisions, metrics, and tracing integration.
>
> **Architecture:**
> - **`RetryDecisionMaker<T>`** — functional interface that decides whether to retry
>   based on the result type (Response or RuntimeException):
>   ```java
>   @FunctionalInterface
>   interface RetryDecisionMaker<T> {
>       Promise<RetryDecision, NeverThrowsException> decide(
>           Context context, Request request, T result);
>   }
>   ```
> - **`RetryDecision`** — sealed domain object with `retry(response)`, `retry(exception)`,
>   `abandon(response)`, and `abandon(exception)` factory methods
>
> **Delayed retry using `PromiseImpl.create()`:**
> ```java
> private Promise<Response, NeverThrowsException> delayedRetry(
>         Context context, Request request, Handler next) {
>     PromiseImpl<Response, NeverThrowsException> promise = PromiseImpl.create();
>     executorService.schedule(
>         () -> this.filter(context, request, next)
>                   .thenOnCompletion((ResultHandler) promise),
>         delay.toMillis(), TimeUnit.MILLISECONDS);
>     return promise;
> }
> ```
>
> **Key patterns:**
> - **`PromiseImpl.create()` as a deferred promise.** The promise is returned
>   immediately; the scheduled task completes it later via `thenOnCompletion()`
> - **Dual decision makers:** separate for responses vs. runtime exceptions
> - **`AtomicInteger` remaining counter** for thread-safe retry tracking
> - Uses `Filters.requestCopyFilter()` via `Handlers.chainOf()` to ensure
>   the request can be re-sent
> - **Resource cleanup:** `Closeables.closeSilently(decision.response())` before retry
> - **OpenTelemetry tracing:** `TracingProvider.enrichSpan()` adds retry count
>   as `http.request.resend_count` attribute
> - **Metrics:** `Counter` incremented with total retry attempts
> - Builder pattern with `condition()`, `runtimeExceptionCondition()`,
>   `retries()`, `delay()` setters


### Circuit Breaker Pattern (Failure Threshold / Auto-Recovery)
>
> This pattern implements a simplified circuit breaker with a sliding-window counter
> and automatic recovery via scheduled executor.
>
> **State machine:**
> ```
> ┌───────┐   failures > maxFailures   ┌──────┐
> │ CLOSE ├─────────────────────────────▶│ OPEN │
> └───▲───┘                             └──┬───┘
>     │      after openDuration            │
>     └────────────────────────────────────┘
> ```
>
> **Critical implementation details:**
> ```java
> // Atomic state transition — only one thread opens the circuit
> if (resultRecorder.recordFailure() > maxFailures
>         && state.compareAndExchange(State.CLOSE, State.OPEN) == State.CLOSE) {
>     executor.schedule(this::closeCircuit,
>         openDuration.toMillis(), TimeUnit.MILLISECONDS);
> }
> ```
>
> **Key patterns:**
> - **`AtomicReference<State>` + `compareAndExchange()`** — lock-free state
>   transition that ensures only one thread schedules the recovery
> - **`SlidingCounterResultRecorder`** — sliding window tracks only the last
>   N results (configurable `size`). The window size must be > `maxFailures`
> - **Open handler:** configurable; defaults to throwing a
>   `RuntimeException("Circuit-breaker opened to prevent cascading failures")`
> - **Heaplet validation:** `slidingCounter.size > maxFailures` enforced at config time
> - Uses `ScheduledExecutorService` from heap (defaults to `"ScheduledExecutorService"`)
> - `Durations` utility for parsing duration strings like `"10 seconds"`


### Token Bucket Rate Limiting Pattern
>
> This pattern implements rate limiting using a token-bucket algorithm with
> partitioning and async coordination.
>
> **Parallel policy + partition key resolution with `allDone()`:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     Promise<ThrottlingRate, Exception> ratePromise =
>         throttlingRatePolicy.lookup(context, request);
>     Promise<String, Exception> partitionKeyPromise =
>         Promises.newResultPromise(new ContextAndRequest(context, request))
>             .thenAsync(requestGroupingPolicy);
>
>     return Promises.allDone(Arrays.asList(ratePromise, partitionKeyPromise))
>         .thenAsync(ignore -> {
>             String partitionKey = partitionKeyPromise.get();
>             ThrottlingRate rate = ratePromise.get();
>             if (rate == null) return next.handle(context, request); // no limit
>             return throttlingStrategy.throttle(partitionKey, rate)
>                 .thenAsync(delay -> delay <= 0
>                     ? next.handle(context, request)
>                     : tooManyRequestsResponse(delay));
>         });
> }
> ```
>
> **Token bucket partitioning (ConcurrentHashMap with CAS replacement):**
> ```java
> private TokenBucket selectTokenBucket(String key, ThrottlingRate rate) {
>     TokenBucket previous;
>     do {
>         previous = partitions.get(key);
>         if (previous == null) {
>             previous = partitions.putIfAbsent(key, new TokenBucket(ticker, rate));
>             if (previous == null) return partitions.get(key);
>         }
>         if (previous.getThrottlingRate().equals(rate)) return previous;
>     } while (!partitions.replace(key, previous, new TokenBucket(ticker, rate)));
>     return partitions.get(key);
> }
> ```
>
> **Key patterns:**
> - **`Promises.allDone(List)`** — unlike `Promises.when()`, `allDone()` waits
>   for all promises (even failed ones) before proceeding. Used when you need
>   all results regardless of individual failures
> - **`ThrottlingPolicy`** — functional interface: `lookup(Context, Request)`
>   returns a `Promise<ThrottlingRate, Exception>`
> - **`ThrottlingStrategy`** — pluggable strategy interface; `TokenBucketThrottlingStrategy`
>   is the default implementation
> - **Automatic `Retry-After` header:** computed from delay in nanoseconds,
>   converted to seconds (rounded up)
> - **Periodic cleanup thread:** `ScheduledFuture` removes expired token buckets
>   at configurable intervals (max: 1 day)
> - **`ContextAndRequest`** — value holder for the request grouping policy,
>   providing both context and request as a single parameter


---

## 18. Session & State Management Patterns

### Session-Backed Cookie Management Pattern
>
> This pattern demonstrates a filter that manages cookies using
> `java.net.CookieManager` with session-backed persistence. It supports three distinct actions:
> MANAGE (gateway-controlled), SUPPRESS (strip from both directions), and RELAY
> (pass through unchanged). Uses `java.net.CookieManager` stored in session.
>
> **Session-locked CookieManager creation:**
> ```java
> private CookieManager getManager(Session session) {
>     sessionLock.lock();
>     try {
>         CookieManager manager = (CookieManager) session.get(
>             CookieManager.class.getName());
>         if (manager == null) {
>             CookiePolicy policy = (uri, cookie) ->
>                 action(cookie.getName()) == Action.MANAGE
>                     && this.policy.shouldAccept(uri, cookie);
>             manager = new CookieManager(null, policy);
>             session.put(CookieManager.class.getName(), manager);
>         }
>         return manager;
>     } finally {
>         sessionLock.unlock();
>     }
> }
> ```
>
> **Key patterns:**
> - **`ReentrantLock` for session access** — CookieManager is stored in the
>   session and requires synchronized initialization
> - **Tri-action enum:** `Action.MANAGE` / `SUPPRESS` / `RELAY` — resolved per
>   cookie name from configured sets, with configurable `defaultAction`
> - **`CaseInsensitiveSet`** — used for cookie name matching (case-insensitive)
> - **URI resolution with `UriRouterContext`** — maps the proxied request's
>   URI back to the original client-visible URI for correct cookie domain matching
> - **`Set-Cookie2` deprecation warning** — warns once when encountering the
>   deprecated RFC 2965 header


### Session Load/Save Lifecycle Pattern
>
> The simplest but most critical filter in the stack: loads a session before
> processing and saves it after. Demonstrates the **session-scoped lifecycle**
> pattern with guaranteed restoration.
>
> **Complete implementation:**
> ```java
> class SessionFilter implements Filter {
>     private final SessionManager sessionManager;
>
>     public Promise<Response, NeverThrowsException> filter(
>             Context context, Request request, Handler next) {
>         SessionContext sessionContext = context.asContext(SessionContext.class);
>         Session oldSession = sessionContext.getSession();  // save previous
>         sessionContext.setSession(sessionManager.load(request));  // load new
>
>         return next.handle(context, request)
>             .thenOnResult(response -> {
>                 try {
>                     sessionManager.save(sessionContext.getSession(), response);
>                 } catch (IOException e) {
>                     logger.error("Failed to save session", e);
>                 } finally {
>                     sessionContext.setSession(oldSession);  // always restore
>                 }
>             });
>     }
> }
> ```
>
> **Key patterns:**
> - **Session stacking:** the old session is saved and restored in `finally`,
>   supporting nested session scopes (e.g., gateway session wrapping an
>   upstream session)
> - **`SessionManager` SPI:** `load(Request)` and `save(Session, Response)`
>   are the only methods. Cookie-based, JWT-based, and other session
>   implementations plug into this interface
> - **Error isolation:** session save failures are logged but do not
>   propagate — the response is still delivered to the client
> - **`SessionContext`** — mutable context holding the current session.
>   The session is available to all downstream filters and handlers via
>   `context.asContext(SessionContext.class).getSession()`


---

## 19. Async Composition & Caching Patterns

### `AsyncFunctions` Utility

A single-method utility class providing a critical Promise composition helper:

```java
public static <V, E extends Exception>
    AsyncFunction<V, V, E> asyncResultHandler(AsyncFunction<V, Void, E> handler) {
    return result -> handler.apply(result).then(ignore -> result);
}
```

> **"Process-then-pass-through" pattern.** This converts a side-effecting async
> operation (that returns `Void`) into a passthrough that preserves the original
> value. It is the idiomatic way to perform response-phase processing:
> ```java
> next.handle(context, request)
>     .thenAsync(AsyncFunctions.asyncResultHandler(
>         response -> captureResponse(context, response)));
> // The response is captured but passed through unchanged
> ```
>
> Used extensively by `CaptureFilter`, `EntityExtractFilter`, and `HeaderFilter`
> for response-phase operations that observe but don't modify the response.


### Inline Cache Wrapping Pattern
>
> This pattern demonstrates the **inline heap object fabrication** pattern: dynamically constructing
> a JSON configuration fragment and resolving it through the heap, rather than
> requiring the user to declare the intermediate object.
>
> **Inline cache wrapping:**
> ```java
> // Build an inline "CacheAccessTokenResolver" config programmatically
> Map<String, Object> cacheConfiguration = JsonValue.object(
>     field("delegate", resolverConfig.getObject()),
>     field("defaultTimeout", cacheConfig.get("defaultTimeout").getObject()),
>     field("maximumTimeToCache", cacheConfig.get("maxTimeout").getObject()),
>     // ...
> );
> // Create a synthetic JsonValue with inline type declaration
> JsonValue cacheReference = new JsonValue(
>     JsonValue.object(
>         field("type", "CacheAccessTokenResolver"),
>         field("config", cacheConfiguration)),
>     new JsonPointer(config.getPointer().child("$cacheAccessTokenResolver")));
>
> // Resolve through the heap — creates the cache resolver automatically
> resolver = cacheReference.as(requiredHeapObject(heap, AccessTokenResolver.class));
> ```
>
> **HTTPS enforcement via composition:**
> ```java
> if (requireHttps) {
>     Condition mustUseHttps = new Condition(
>         "${contexts.router.originalUri.scheme == 'https'}");
>     return Filters.chainOf(
>         new ConditionEnforcementFilter(mustUseHttps), resourceServerFilter);
> }
> ```
>
> **Key patterns:**
> - **Inline heap fabrication:** `JsonValue` + `field()` + `JsonPointer` for
>   creating a synthetic config node that the heap resolves like any other object.
>   The `$` prefix in the pointer (`$cacheAccessTokenResolver`) marks it as
>   a synthetic/inline name
> - **Composable filter assembly:** the heaplet's `create()` returns either
>   a single filter or a `chainOf()` composite depending on config
> - **Deprecated alias:** `"OAuth2RSFilter"` kept alongside `"OAuth2ResourceServerFilter"`
>   with `@Deprecated(since="2024.9")`


### Parallel Promise Resolution Pattern (`Promises.join()`)
>
> This pattern demonstrates several advanced techniques in
> a complex policy-evaluation filter:
>
> **1. Parallel promise resolution with `Promises.join()`:**
> ```java
> private Promise<Map<String, Object>, ResourceException> buildSubject(Bindings bindings) {
>     return Promises.join(
>         ssoTokenSubject.apply(bindings),
>         jwtSubject.apply(bindings),
>         claimsSubject.apply(bindings)
>     ).then(results -> {
>         Map<String, Object> subject = JsonValue.object(
>             fieldIfNotNull("ssoToken", results.get(0)),
>             fieldIfNotNull("jwt", results.get(1)),
>             fieldIfNotNull("claims", results.get(2)));
>         if (subject.isEmpty()) {
>             throw new BadRequestException("The subject can't be determined");
>         }
>         return subject;
>     });
> }
> ```
>
> **2. Context injection for downstream access:**
> ```java
> PolicyDecisionContext decisionContext = new PolicyDecisionContext(
>     context,
>     policyDecision.get("attributes").defaultTo(JsonValue.object()),
>     policyDecision.get("advices").defaultTo(JsonValue.object()),
>     policyDecision.get("actions").defaultTo(JsonValue.object()),
>     policyResourceUri);
> // Pass enriched context downstream
> return next.handle(decisionContext, request);
> ```
>
> **3. Heaplet `destroy()` for lifecycle cleanup:**
> ```java
> public void destroy() {
>     if (cachePolicyDecisionFilter != null) {
>         try {
>             cachePolicyDecisionFilter.close();
>         } catch (Exception e) {
>             logger.info("Ignoring error while closing cache", e);
>         }
>     }
> }
> ```
>
> **Key patterns:**
> - **`Promises.join(Promise...)`** — executes multiple promises concurrently,
>   returning `List<Object>` of results. Used for parallel subject resolution
> - **`JsonValue.fieldIfNotNull(key, value)`** — only includes the field if
>   value is non-null (clean JSON construction)
> - **`AsyncFunction<Bindings, T, NeverThrowsException>` fields** — the Builder
>   stores async functions for lazy evaluation at filter time
> - **`alwaysNull()` default** — generic method `<T> Promise<T, NeverThrowsException>
>   alwaysNull(Bindings)` used as default for all optional subject resolvers
> - **`Heaplet.destroy()`** overridden to close the async cache filter
> - Caffeine-based `AsyncCache` with `DisconnectionStrategy` enum for
>   notification-driven cache invalidation
> - Uses **CREST** (`json-resource`) internally for policy evaluation requests


### Side-Car Request + Resource Cleanup Pattern
>
> This pattern demonstrates making an outbound HTTP request from within a filter's `filter()` method,
> processing the response, injecting the result into context, and properly cleaning up
> resources.
>
> **Outbound request with cleanup:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     return idToken.evalAsync(Bindings.bindings(context, request))
>         .thenAsync(resolvedIdToken -> {
>             if (resolvedIdToken == null) {
>                 return ResponseUtils.newEmptyResponseAsync(Status.BAD_REQUEST);
>             }
>             Request transformationRequest = transformationRequest(resolvedIdToken);
>             return handler.handle(context, transformationRequest)
>                 .thenAlways(() -> transformationRequest.close())  // ← cleanup
>                 .thenAsync(processIssuedToken(context, request, next));
>         });
> }
> ```
>
> **Response processing with `thenAlways()` cleanup:**
> ```java
> return response.getEntity().getJsonAsync()
>     .then(jsonContent -> (Map) jsonContent)
>     .thenAsync(json -> {
>         if (response.getStatus() != Status.OK) {
>             return ResponseUtils.newEmptyResponseAsync(Status.BAD_GATEWAY);
>         }
>         String token = (String) json.get("issued_token");
>         return next.handle(new StsContext(context, token), request);
>     })
>     .thenAlways(() -> Closeables.closeSilently(response));  // ← always cleanup
> ```
>
> **Key patterns:**
> - **`Request.close()`** / **`Closeables.closeSilently(response)`** — mandatory
>   resource cleanup for outbound requests and their responses
> - **`thenAlways(Runnable)`** — guaranteed cleanup regardless of success/failure
> - **Custom context injection:** `StsContext` wraps the issued token for downstream access
> - **Error mapping:** upstream 4xx/5xx → `Status.BAD_GATEWAY` (proxy error semantics)
> - **JSON response body construction:** `JsonValue.object(field(key, value)...)` for
>   outbound request entities


### Promise ↔ CompletableFuture Bridge Pattern
>
> The canonical pattern for integrating Caffeine caching with PingGateway's Promise
> API. This bridge is required because Caffeine's `AsyncCache` uses
> `CompletableFuture` while PingGateway uses `Promise`.
>
> **`AsyncCache<K, V, E>` interface (PingGateway's abstraction):**
> ```java
> public interface AsyncCache<K, V, E extends Exception> {
>     Promise<V, E> get(K key, Supplier<Promise<V, E>> asyncValueSupplier);
>     Optional<Promise<V, E>> getIfPresent(K key);
>     void synchronousCleanUp();
>     void synchronousInvalidate(K key);
>     void synchronousInvalidateAll(Iterable<? extends K> keys);
>     void synchronousInvalidateAll();
> }
> ```
>
> **Bridge implementation (key line):**
> ```java
> public Promise<V, E> get(K key, Supplier<Promise<V, E>> asyncValueSupplier) {
>     return FutureUtils.futureToPromise(
>         asyncCache.get(key, (k, e) ->
>             FutureUtils.promiseToFuture(
>                 CaffeineUtils.invalidateEagerly(
>                     asyncCache, asyncValueSupplier.get(), key))),
>         exceptionClass);
> }
> ```
>
> **Builder with monitoring:**
> ```java
> new CaffeineAsyncCache.Builder<>()
>     .monitoring(meterRegistry, "cache_name")   // StatsCounter
>     .ticker(ticker)                             // for testing
>     .maximumSize(1000)
>     .expireAfter(customExpiry)                  // variable per-entry TTL
>     .executor(executor)                         // async eviction
>     .build(ResourceException.class);            // exception type
> ```
>
> **Key patterns:**
> - **`FutureUtils.promiseToFuture()` / `futureToPromise()`** — bidirectional
>   bridge between Promise and CompletableFuture
> - **`CaffeineUtils.invalidateEagerly()`** — eagerly invalidates the cache key
>   if the promise completes with an error (prevents caching of errors)
> - **`CommonsMonitoringStatsCounter`** — adapts Caffeine's `StatsCounter` to
>   PingGateway's MeterRegistry for cache hit/miss metrics
> - **`Ticker` injection for testing** — allows deterministic time control
> - **Generic exception typing:** `Class<E>` parameter enables type-safe
>   exception propagation through the cache layer


### Filter-Level Conditional Wrapper Pattern
>
> This pattern wraps any filter with a condition, using the `Condition` evaluation
> to decide whether to apply the filter or bypass it.
> passes directly to `next`.
>
> **Complete implementation:**
> ```java
> public class ConditionalFilter implements Filter {
>     private final Filter delegate;
>     private final Condition condition;
>
>     public Promise<Response, NeverThrowsException> filter(
>             Context context, Request request, Handler next) {
>         return condition.test(Bindings.bindings(context, request))
>             .thenAsync(match -> match
>                 ? delegate.filter(context, request, next)
>                 : next.handle(context, request));
>     }
> }
> ```
>
> **Usage in Heaplet (one-liner):**
> ```java
> return new ConditionalFilter(
>     config.get("delegate").as(requiredHeapObject(heap, Filter.class)),
>     config.get("condition").required().as(Condition.condition(initialBindings())));
> ```
>
> **Key insight:**
> - This is the **Decorator pattern for filters with conditions**. Extension
>   developers can use this directly to conditionally apply any custom filter
>   without modifying the filter's own logic
> - Alternative to `ConditionEnforcementFilter` (which blocks rather than bypasses)


---

## 20. Lifecycle & Decorator Patterns

### Decorator Interface & Timer Decorator Pattern

Decorators are cross-cutting concerns that wrap heap objects transparently.
They are applied via JSON config attributes like `"capture": true` or
`"timer": true`.

**Decorator interface:**

```java
public interface Decorator {
    boolean accepts(Class<?> type);
    DecorationHandle decorate(Object delegate, JsonValue decoratorConfig,
                              Context context) throws HeapException;
}
```

**`TimerDecorator` — reference implementation:**

```java
public class TimerDecorator extends AbstractDecorator {
    // ServiceLoader-discovered factories for wrapping Filter/Handler/etc.
    private static final Map<Class<?>, TimerFactory> FACTORIES =
        Loader.loadMap(TimerFactory.class, TimerFactory.class.getClassLoader());

    @Override
    public boolean accepts(Class<?> type) {
        return FACTORIES.keySet().stream()
            .anyMatch(supported -> supported.isAssignableFrom(type));
    }

    @Override
    public DecorationHandle decorate(Object delegate, JsonValue decoratorConfig,
                                      Context context) throws HeapException {
        if (decoratorConfig.as(evaluated(context.getHeap().getProperties()))
                .asBoolean()) {
            // Find matching factory and wrap
            return FACTORIES.entrySet().stream()
                .filter(e -> e.getKey().isInstance(delegate))
                .findFirst()
                .map(rethrowFunction(this.decorate(delegate, context)))
                .orElseGet(() -> new DecorationHandle(delegate));
        }
        return new DecorationHandle(delegate); // no-op when disabled
    }
}

// Heaplet pattern for decorators extends DecoratorHeaplet (not GenericHeaplet)
@TypeInfo(value = TimerDecoratorTypeProvider.class)
public static class Heaplet extends DecoratorHeaplet {
    public static final String NAME = "TimerDecorator";
    static final String CONFIG_TIME_UNIT = "timeUnit";
    static final String DEFAULT_TIME_UNIT = "milliseconds";
}
```

> **SPI-discovered wrapping.** `TimerFactory` implementations are loaded via
> `ServiceLoader`, enabling the timer to wrap any type (Filter, Handler) without
> compile-time coupling. Custom decorators can use the same pattern.
>
> **`DecorationHandle` return type.** Every `decorate()` call returns a
> `DecorationHandle` that wraps the decorated object and its cleanup action
> (for deregistering meters/endpoints on destroy).
>
> **`DecoratorHeaplet` base class.** Decorator heaplets extend `DecoratorHeaplet`
> instead of `GenericHeaplet` — this is a separate lifecycle path specifically
> for cross-cutting concerns.


### Multi-Decorator Chaining Pattern
>
> This pattern applies multiple named decorators to a single object,
> demonstrating the **decorator-chain** pattern with lifecycle management.
>
> **Chaining with stop-action propagation:**
> ```java
> public DecorationHandle decorate(Object delegate, JsonValue ignored,
>         Context context) throws HeapException {
>     DecorationHandle decorationHandle = new DecorationHandle(delegate);
>     for (JsonValue decoration : this.decorators) {
>         String decoratorName = decoration.getPointer().leaf();
>         Decorator decorator = heap.get(decoratorName, Decorator.class);
>
>         if (decorator == null || !decorator.accepts(
>                 decorationHandle.getDecorated().getClass())) {
>             continue;
>         }
>
>         DecorationHandle newHandle = decorator.decorate(
>             decorationHandle.getDecorated(), decoration, context);
>         // Chain stop-actions for proper cleanup order
>         newHandle.addStopAction(decorationHandle::stop);
>         decorationHandle = newHandle;
>     }
>     return decorationHandle;
> }
> ```
>
> **Key patterns:**
> - **`accepts(Class<?>)` returns `true` for all types** — global decorators
>   attempt to decorate everything; individual decorators' `accepts()` provides
>   the actual type check
> - **`DecorationHandle.addStopAction()`** — registers a cleanup callback that
>   fires when the decoration is removed. Each new handle chains the previous
>   handle's `stop()`, creating a cleanup stack (LIFO destruction order)
> - **Reserved field name exclusion:** constructor removes known config keys
>   (like `"type"`, `"config"`) so only decorator names remain
> - **Config iteration via `JsonValue` pointer:** `decoration.getPointer().leaf()`
>   extracts the decorator name from the JSON key
> - **`BaseUriDecorator` variant:** uses `Loader.loadMap(BaseUriFactory.class)`
>   for SPI-discovered factory methods, showing how decorators can themselves
>   be extensible via SPI


### Composite Implicit Filter Stack Pattern
>
> This pattern demonstrates a handler that internally composes
> multiple filters into a pipeline, adding cross-cutting concerns transparently.
> at creation time. This demonstrates the **framework-managed filter composition**
> pattern.
>
> **Implicit filter stack assembly:**
> ```java
> @Override
> protected List<Filter> filters(Options options) throws HeapException {
>     List<Filter> filters = new ArrayList<>();
>
>     // WebSocket detection (if enabled)
>     if (wsEnabled)
>         filters.add(provider.newWebSocketHandshakeDetectorFilter());
>
>     // Always-present infrastructure filters
>     filters.add(new SseResponseSupportFilter());      // SSE streaming
>     filters.add(new HopByHopHeadersRemovalFilter());  // RFC 7230 §6.1
>     filters.add(new BadGatewayFilter());               // Exception → 502
>
>     // Optional resilience filters from config
>     circuitBreakerFilter(config).ifPresent(filters::add);
>     retryFilter(config, meterRegistryHolder()).ifPresent(filters::add);
>
>     // WebSocket proxy (if enabled)
>     if (wsEnabled)
>         filters.add(provider.newWebSocketProxyFilter(wsOptions, registry));
>
>     return filters;
> }
> ```
>
> **Key patterns:**
> - **Implicit composition:** users configure only `ReverseProxyHandler` — the
>   infrastructure filters (hop-by-hop removal, bad gateway handling, SSE support)
>   are added automatically. Extension developers should follow this pattern
>   for handlers that require standard cross-cutting concerns
> - **`BadGatewayFilter`** — catches transport-level exceptions and maps them
>   to 502 Bad Gateway responses (error boundary pattern)
> - **`HopByHopHeadersRemovalFilter`** — strips `Connection`, `Keep-Alive`,
>   `Proxy-*`, `Transfer-Encoding`, `Upgrade` headers (RFC 7230 compliance)
> - **Options inheritance for WebSocket:** `copyFrom(Options, Option<?>...)`
>   copies TLS, timeout, and proxy settings from the parent handler's options
>   to the WebSocket sub-handler
> - **`Closeable` filter cleanup:** `destroy()` checks if the websocket filter
>   implements `Closeable` and closes it (duck-typing cleanup)
> - **Inheritance over composition:** `ReverseProxyHandlerHeaplet extends
>   HttpClientHandlerHeaplet` — adds WebSocket and resilience capabilities
>   on top of the base HTTP client handler


### Double-Checked Lazy Metric Registration Pattern
>
> Demonstrates lazy metric registration with double-checked locking, ensuring
> metrics are registered only when the first request arrives. Uses `LongAdder`
> for contention-free active request counting.
>
> **Double-checked locking for metric initialization:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     if (!metricsInitialized) {           // volatile read — fast path
>         lock.lock();
>         try {
>             if (!metricsInitialized) {    // recheck under lock
>                 initMetrics();
>             }
>         } finally {
>             lock.unlock();
>         }
>     }
>     requests.increment();
>     activeRequestCount.increment();
>     Timer.Sample sample = Timer.start(meterRegistry);
>     return next.handle(context, request)
>         .thenOnResult(response -> {
>             sample.stop(responseTime);
>             if (response != null) {
>                 if (response.getCause() != null) errorsResponseCount.increment();
>                 responseStatusCounter.get(response.getStatus().getFamily())
>                     .increment();
>             } else {
>                 nullResponseCount.increment();
>             }
>         })
>         .thenOnRuntimeException(e -> errorsResponseCount.increment())
>         .thenAlways(() -> {
>             responses.increment();
>             activeRequestCount.decrement();
>         });
> }
> ```
>
> **Key patterns:**
> - **`volatile boolean` + `ReentrantLock`** — classic double-checked locking
>   for one-time metric registration
> - **`LongAdder`** — for active request count (better than `AtomicLong`
>   under high contention); exposed via `Gauge.doubleGauge("request.active",
>   activeRequestCount::sum)`
> - **`EnumMap<Status.Family, Counter>`** — one counter per HTTP status family
>   (1xx–5xx), pre-populated at init time
> - **`Timer.Sample`** — start/stop pattern for response time measurement
>   with percentile publishing (p50, p75, p99, p99.9)
> - **`thenAlways()`** — guarantees response counter increment and active
>   request decrement, even on exception
> - **`thenOnRuntimeException()`** — catches runtime exceptions surfaced
>   during response processing, incrementing the error counter
> - **Delayed initialization option:** `delayMetricsInitialization` flag
>   allows deferring metric registration until first request (useful when
>   the meter registry isn't available at construction time)


---

## 21. URI Rewriting & Proxying Patterns

### Expression-Based URI Rebasing Pattern

The `ForwardedRequestFilter` demonstrates advanced Promise chaining for
rebuilding the original URI from X-Forwarded-* header values (or expressions):

```java
// All three URI components are optional expressions
private final Expression<String> scheme;  // e.g., "${request.headers['X-Forwarded-Proto'][0]}"
private final Expression<String> host;
private final Expression<Number> port;

public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    URI originalUri = context.asContext(UriRouterContext.class).getOriginalUri();
    return rebasedUri(originalUri, Bindings.bindings(context, request))
        .thenAsync(newUri -> {
            UriRouterContext newContext = UriRouterContext
                .uriRouterContext(context)
                .originalUri(newUri)
                .build();
            return next.handle(newContext, request);
        }, e -> ResponseUtils.newIllegalStateExceptionPromise(
            "Failed to rebase the original URI.", e));
}

// Nested async chaining for multi-component URI construction
private Promise<URI, URISyntaxException> rebasedUri(URI original, Bindings b) {
    return evalOrThrow(scheme, b, original.getScheme())
      .thenAsync(s -> evalOrThrow(host, b, original.getHost())
        .thenAsync(h -> evalOrThrow(port, b, original.getPort())
          .then(p -> Uris.create(s, original.getRawUserInfo(),
                                 h, toInt(p),
                                 original.getRawPath(),
                                 original.getRawQuery(),
                                 original.getRawFragment()))));
}

// Expression eval with fallback to original value
private static <T> Promise<T, URISyntaxException> evalOrThrow(
        Expression<T> expr, Bindings bindings, T fallback) {
    if (expr == null) return Promises.newResultPromise(fallback);
    return expr.evalAsync(bindings)
        .then(result -> {
            if (result == null) throw new URISyntaxException(
                expr.toString(), "Unexpected null result");
            return result;
        }, NeverThrowsException.neverThrown());
}
```

**Heaplet validation pattern:**

```java
// At least one component must be configured
if (scheme == null && host == null && port == null) {
    throw new HeapException(
        "The ForwardedRequestFilter requires at least either scheme, host or port");
}
```

> **Nested `thenAsync` for multi-component async assembly.** Each URI component
> is evaluated asynchronously, then composed into a single URI. This pattern
> is reusable whenever building a result from multiple independently-evaluated
> expressions.
>
> **`evalOrThrow` with fallback.** When an expression is null (not configured),
> the original value passes through unchanged. When configured but evaluates to
> null, it throws. This "optional override" pattern is common in filters that
> selectively modify URI components.
>
> **Context replacement pattern.** Rather than modifying the existing context,
> the filter creates a new `UriRouterContext` wrapping the parent. This preserves
> the original context while adding the rebased URI.


### Response Location Header Rebasing Pattern
>
> Rewrites `Location` response headers to translate between upstream and
> client-facing URIs. Essential for reverse proxy scenarios where redirect
> targets must be translated.
>
> **Async response-phase rebasing:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     return next.handle(context, request)
>         .thenAsync(response -> processResponse(response,
>             Bindings.bindings(context, request, response),
>             context.asContext(UriRouterContext.class).getOriginalUri()));
> }
>
> private Promise<Response, NeverThrowsException> processResponse(
>         Response response, Bindings bindings, URI originalUri) {
>     LocationHeader header = LocationHeader.valueOf(response);
>     if (header.getLocationUri() == null)
>         return Promises.newResultPromise(response);
>
>     return evaluateBaseUri(bindings, originalUri)
>         .then(baseUri -> {
>             URI current = new URI(header.getLocationUri());
>             URI rebased = Uris.rebase(current, baseUri);
>             if (!current.equals(rebased))
>                 response.getHeaders().put("Location", rebased.toString());
>             return response;
>         });
> }
> ```
>
> **Key patterns:**
> - **`Uris.rebase(current, base)`** — rebases the scheme, host, and port
>   of the current URI onto the base URI, preserving path and query
> - **`baseURI` expression fallback:** if no base URI expression is configured,
>   the original client-facing URI is used (from `UriRouterContext`)
> - **Conditional rewrite:** only updates the header if the rebased URI
>   differs from the original (avoids unnecessary header mutation)
> - **Paired with `ForwardedRequestFilter`** — used together for full
>   bidirectional URI translation in reverse proxy configurations


### Original URI Rebasing Pattern
>
> Rewrites the `originalUri` in the `UriRouterContext` based on configurable
> expressions for scheme, host, and port. Used when the gateway sits behind
> a load balancer or TLS terminator.
>
> **Nested async expression evaluation with fallback:**
> ```java
> private Promise<URI, URISyntaxException> rebasedUri(
>         URI originalUri, Bindings bindings) {
>     return evalOrThrow(scheme, bindings, originalUri.getScheme())
>         .thenAsync(schemeVal ->
>             evalOrThrow(host, bindings, originalUri.getHost())
>                 .thenAsync(hostVal ->
>                     evalOrThrow(port, bindings, originalUri.getPort())
>                         .then(portVal -> Uris.create(
>                             schemeVal, originalUri.getRawUserInfo(),
>                             hostVal, toInt(portVal),
>                             originalUri.getRawPath(),
>                             originalUri.getRawQuery(),
>                             originalUri.getRawFragment()))));
> }
>
> private static <T> Promise<T, URISyntaxException> evalOrThrow(
>         Expression<T> expression, Bindings bindings, T fallback) {
>     if (expression == null) return Promises.newResultPromise(fallback);
>     return expression.evalAsync(bindings)
>         .then(result -> {
>             if (result == null) throw new URISyntaxException(
>                 expression.toString(), "Unexpected null result");
>             return result;
>         }, NeverThrowsException.neverThrown());
> }
> ```
>
> **Key patterns:**
> - **`evalOrThrow()` — expression evaluation with null-safety and fallback:**
>   if no expression is configured, the original URI component is used
> - **Port validation:** rejects ports outside 0–65535 range and non-integer
>   numeric types
> - **Partial override:** any combination of scheme, host, and port can be
>   configured independently (at least one is required, enforced by Heaplet)
> - **`UriRouterContext.uriRouterContext(context).originalUri(newUri).build()`**
>   — creates a new context wrapping the parent with an updated original URI
> - **Error handling:** `ResponseUtils.newIllegalStateExceptionPromise()` for
>   URI construction failures

---


---

## 22. Script Extensibility & Entity Processing Patterns

### Regex-Based Entity Extraction Pattern

The `EntityExtractFilter` scans an entity body line-by-line using regex patterns
and stores matches in a target expression (using `LeftValueExpression`):

```java
// MessageType dispatch pattern (request-phase vs response-phase)
EntityExtractFilter(MessageType type, LeftValueExpression<?> target,
                    Charset charset, Set<MatchRequest> matchRequests) {
    this.processRequest = type == MessageType.REQUEST
        ? this::process : (bindings, msg) -> Promises.newVoidResultPromise();
    this.processResponse = type == MessageType.RESPONSE
        ? this::process : (bindings, msg) -> Promises.newVoidResultPromise();
}

// Extraction uses RxJava Flowable for streaming entity processing
private Promise<Void, NeverThrowsException> process(Bindings bindings, Message<?> msg) {
    return msg.getEntity()
        .decodedContentAsFlowable(charset)
        .compose(new CharBufferToLineFlowableTransformer())
        .takeWhile(line -> !matchRequestCopy.isEmpty())  // stop early when all matched
        .collectInto(nullInitializedMap(), linesMatchingPattern(matchRequests))
        .to(ReactiveUtils.toPromise())
        .thenOnResult(map -> target.set(bindings, map))
        .thenDiscardResult();
}

// MatchRequest: key, pattern, optional template
static class MatchRequest {
    MatchRequest(String key, Pattern pattern, String template)
    // If template is null, uses Matcher::group (entire match)
    // If template is set, uses PatternTemplate for named group interpolation
}
```

> **Early termination pattern.** The `takeWhile(!matchRequests.isEmpty())`
> stops processing as soon as all expected matches are found — avoids reading
> entire large entities when only extracting a few values.
>
> **`LeftValueExpression` for write-back.** Unlike read-only `Expression<T>`,
> `LeftValueExpression` supports `.set(bindings, value)` for writing extracted
> data back into the context (e.g., into `AttributesContext`).
>
> **Reactive entity processing.** Uses RxJava3 `Flowable` for line-by-line
> streaming — the entity is never fully buffered in memory.


### Script Extensibility Pattern
>
> The scripting infrastructure allows Groovy (or other JSR-223 language) scripts
> to act as filters or handlers. `AbstractScriptableHeapObject` provides the
> binding enrichment, script compilation, and lifecycle management.
>
> **ScriptableFilter — binds `next` handler into script scope:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     return runScriptAsync(
>         Bindings.bindings(context, request).bind("next", next),
>         context, Response.class)
>     .thenCatch(e -> { throw new RuntimeException(e); });
> }
> ```
>
> **Binding enrichment (AbstractScriptableHeapObject):**
> ```java
> private Map<String, Object> enrichBindings(Bindings source, Context context)
>         throws ScriptException {
>     Bindings enriched = Bindings.bindings()
>         .bind(heap.getProperties())   // heap-level properties
>         .bind(source);                // context + request + next
>     Map<String, Object> bindings = new HashMap<>(enriched.asMap());
>     bindings.put("logger", getScriptLogger());  // logger.<filterName>
>     bindings.put("globals", scriptGlobals);      // ConcurrentHashMap
>     if (clientHandler != null) {
>         bindings.put("http", new Client(clientHandler, context)); // HTTP client
>     }
>     if (args != null) {
>         for (var entry : args.entrySet()) {
>             if (bindings.containsKey(entry.getKey()))
>                 throw new ScriptException("Can't override binding: " + key);
>             bindings.put(entry.getKey(), entry.getValue());
>         }
>     }
>     return bindings;
> }
> ```
>
> **Key patterns:**
> - **`globals` (ConcurrentHashMap)** — persistent cross-invocation state
>   for scripts (e.g., counters, caches). Shared across all invocations
>   of the same script instance
> - **`http` (Client)** — convenience HTTP client bound into script scope,
>   wrapping the configurable `clientHandler` (default: `"ClientHandler"`)
> - **`args` (Map)** — custom arguments from JSON config, evaluated with
>   heap properties. Cannot override standard bindings (enforced)
> - **Script compilation:** `ScriptFactoryManager` → `ScriptFactory` → inline
>   source or file resource. Supports multi-line source (`source` as array)
> - **Promise result type checking:** `typeCheck(clazz, result)` validates
>   the script's return value matches the expected type
> - **Groovy exception unwrapping:** `GroovyRuntimeException` causes are
>   extracted; `RuntimeException` causes are re-thrown directly


### RxJava Streaming Regex Extraction Pattern
>
> Extracts named values from message entities using regex patterns, leveraging
> RxJava `Flowable` for streaming processing of large entities.
>
> **Streaming line-by-line regex matching:**
> ```java
> private Promise<Void, NeverThrowsException> process(
>         Bindings bindings, Message<?> message) {
>     Set<MatchRequest> matchRequestCopy = new HashSet<>(matchRequests);
>     return message.getEntity()
>         .decodedContentAsFlowable(charset)
>         .compose(new CharBufferToLineFlowableTransformer())
>         .takeWhile(line -> !matchRequestCopy.isEmpty())    // early exit
>         .collectInto(nullInitializedMap(),
>             linesMatchingPattern(matchRequestCopy))
>         .to(ReactiveUtils.toPromise())
>         .thenOnResult(map -> target.set(bindings, map))
>         .thenDiscardResult();
> }
> ```
>
> **Key patterns:**
> - **RxJava → Promise bridge:** `.to(ReactiveUtils.toPromise())` converts
>   `Single<Map>` to `Promise<Map, NeverThrowsException>`
> - **`takeWhile(!matchRequestCopy.isEmpty())`** — stops reading the entity
>   stream as soon as all patterns have matched (performance optimization)
> - **Mutable `Set<MatchRequest>` copy** — each invocation gets its own copy;
>   matched requests are removed from the set during processing
> - **`PatternTemplate`** — allows template-based extraction (capturing group
>   references in templates), not just raw group match
> - **Null-initialized result map** — all keys initially null; unmatched
>   patterns remain null in the result
> - **`BiFunction`-based phase selection:** request/response processing is
>   assigned at construction time via function references, avoiding
>   runtime if/else branching


### CREST Audit Event Emission Pattern
>
> Emits structured audit events to the ForgeRock Common Audit (CAUD) framework
> via a CREST `RequestHandler`. Demonstrates the fluent audit event builder
> pattern and context extraction.
>
> **Fluent audit event construction:**
> ```java
> public Promise<Response, NeverThrowsException> filter(
>         Context context, Request request, Handler next) {
>     IgAccessAuditEventBuilder builder = new IgAccessAuditEventBuilder();
>     builder.eventName("OPENIG-HTTP-ACCESS")
>         .timestamp(clock.instant().toEpochMilli())
>         .rootIdFromContext(context)      // exchange ID
>         .routeIdFromContext(context)     // from RoutingContext
>         .routeNameFromContext(context)
>         .transactionIdFromContext(context)
>         .serverFromContext(clientContext)
>         .clientFromContext(clientContext)
>         .httpRequest(isSecure, method, path, queryParams, headers);
>
>     return next.handle(context, request)
>         .thenOnResult(response -> sendAuditEvent(response, context, builder));
> }
> ```
>
> **Key patterns:**
> - **Custom builder extending `AccessAuditEventBuilder`** — `IgAccessAuditEventBuilder`
>   adds IG-specific fields (`ig.exchangeId`, `ig.routeId`, `ig.routeName`)
>   under a custom `"ig"` JSON subtree
> - **Lazy subtree creation:** `getOrCreateIg()` creates the `"ig"` JSON object
>   on first access
> - **CREST integration:** audit events are submitted as CREST create requests
>   (`Requests.newCreateRequest("/access", event)`)
> - **Response timing:** `Duration.between(requestReceivedTime, now)` in
>   milliseconds, mapped to `ResponseStatus.SUCCESSFUL` / `FAILED`
> - **Status family mapping:** `switch (status.getFamily())` with `CLIENT_ERROR`
>   and `SERVER_ERROR` → `FAILED`, all others → `SUCCESSFUL`
> - **Fire-and-forget audit:** `handleCreate().thenOnException(warn)` — audit
>   failures don't break the request pipeline



---

# Part V — Reference

---

## 23. API Quick Reference

### Status Constants (Complete List)

```java
// All Status constants in PingGateway 2025.11:
Status.CONTINUE                     // 100
Status.SWITCHING_PROTOCOLS          // 101
Status.OK                          // 200
Status.CREATED                     // 201
Status.ACCEPTED                    // 202
Status.NON_AUTHORITATIVE_INFO      // 203
Status.NO_CONTENT                  // 204
Status.RESET_CONTENT               // 205
Status.MULTIPLE_CHOICES            // 300
Status.MOVED_PERMANENTLY           // 301
Status.FOUND                      // 302
Status.SEE_OTHER                   // 303
Status.USE_PROXY                   // 305
Status.UNUSED                     // 306
Status.TEMPORARY_REDIRECT          // 307
Status.BAD_REQUEST                 // 400
Status.UNAUTHORIZED                // 401
Status.PAYMENT_REQUIRED            // 402
Status.FORBIDDEN                  // 403
Status.NOT_FOUND                   // 404
Status.METHOD_NOT_ALLOWED          // 405
Status.NOT_ACCEPTABLE              // 406
Status.PROXY_AUTHENTICATION_REQUIRED // 407
Status.REQUEST_TIMEOUT             // 408
Status.CONFLICT                    // 409
Status.GONE                       // 410
Status.LENGTH_REQUIRED             // 411
Status.PAYLOAD_TOO_LARGE           // 413
Status.URI_TOO_LONG                // 414
Status.UNSUPPORTED_MEDIA_TYPE      // 415
Status.EXPECTATION_FAILED          // 417
Status.TEAPOT                     // 418
Status.UPGRADE_REQUIRED            // 426
Status.TOO_MANY_REQUESTS           // 429
Status.REQUEST_HEADER_FIELDS_TOO_LARGE // 431
Status.INTERNAL_SERVER_ERROR       // 500
Status.NOT_IMPLEMENTED             // 501
Status.BAD_GATEWAY                 // 502
Status.SERVICE_UNAVAILABLE         // 503
Status.GATEWAY_TIMEOUT             // 504
Status.HTTP_VERSION_NOT_SUPPORTED  // 505

// Family classification:
Status.Family.INFORMATIONAL        // 1xx
Status.Family.SUCCESSFUL           // 2xx
Status.Family.REDIRECTION          // 3xx
Status.Family.CLIENT_ERROR         // 4xx
Status.Family.SERVER_ERROR         // 5xx
```


### Core Type Alias Map

The following is the **complete** list of type aliases registered in
`CoreClassAliasResolver` — these are the `"type"` values usable in JSON config:

| Alias | Class |
|---|---|
| `AllowOnlyFilter` | `o.f.openig.filter.allow.AllowOnlyFilter` |
| `AssignmentFilter` | `o.f.openig.filter.AssignmentFilter` |
| `AuditService` | `o.f.openig.audit.AuditServiceObjectHeaplet` |
| `BaseUriDecorator` | `o.f.openig.decoration.baseuri.BaseUriDecorator` |
| `CaptureDecorator` | `o.f.openig.decoration.capture.CaptureDecorator` |
| `Chain` | `o.f.openig.filter.ChainHandlerHeaplet` |
| `ChainOfFilters` | `o.f.openig.filter.ChainFilterHeaplet` |
| `CircuitBreakerFilter` | `o.f.openig.filter.circuitbreaker.CircuitBreakerFilter` |
| `ClientHandler` | `o.f.openig.handler.ClientHandlerHeaplet` |
| `ClientTlsOptions` | `o.f.openig.security.ClientTlsOptionsHeaplet` |
| `ConditionalFilter` | `o.f.openig.filter.ConditionalFilterHeaplet` |
| `ConditionEnforcementFilter` | `o.f.openig.filter.ConditionEnforcementFilter` |
| `CookieFilter` | `o.f.openig.filter.CookieFilter` |
| `CorsFilter` | `o.f.openig.filter.CorsFilterHeaplet` |
| `CsrfFilter` | `o.f.openig.filter.CsrfFilterHeaplet` |
| `CustomProxyOptions` | `o.f.openig.proxy.CustomProxyOptions` |
| `DataPreservationFilter` | `o.f.openig.filter.DataPreservationFilter` |
| `DateHeaderFilter` | `o.f.openig.filter.DateHeaderFilter` |
| `DefaultRateThrottlingPolicy` | `o.f.openig.filter.throttling.DefaultRateThrottlingPolicyHeaplet` |
| `Delegate` | `o.f.openig.decoration.DelegateHeaplet` |
| `DispatchHandler` | `o.f.openig.handler.DispatchHandler` |
| `EntityExtractFilter` | `o.f.openig.filter.EntityExtractFilter` |
| `FapiInteractionIdFilter` | `o.f.openig.filter.finance.FapiInteractionIdFilter` |
| `FileAttributesFilter` | `o.f.openig.filter.FileAttributesFilter` |
| `FileSystemSecretStore` | `o.f.openig.secrets.FileSystemSecretStoreHeaplet` |
| `ForwardedRequestFilter` | `o.f.openig.filter.ForwardedRequestFilter` |
| `FragmentFilter` | `o.f.openig.filter.FragmentFilter` |
| `HeaderFilter` | `o.f.openig.filter.HeaderFilter` |
| `HsmSecretStore` | `o.f.openig.secrets.HsmSecretStoreHeaplet` |
| `HttpBasicAuthFilter` | `o.f.openig.filter.HttpBasicAuthFilter` |
| `HttpBasicAuthenticationClientFilter` | `o.f.openig.filter.HttpBasicAuthenticationClientFilterHeaplet` |
| `InMemorySessionManager` | `o.f.openig.session.cookie.InMemorySessionManagerHeaplet` |
| `JdbcDataSource` | `o.f.openig.sql.JdbcDataSourceHeaplet` |
| `JwkPropertyFormat` | `o.f.openig.secrets.JwkPropertyFormatHeaplet` |
| `JwkSetHandler` | `o.f.openig.handler.JwkSetHandler` |
| `JwkSetSecretStore` | `o.f.openig.secrets.JwkSetSecretStoreHeaplet` |
| `JwtBuilderFilter` | `o.f.openig.filter.JwtBuilderFilter` |
| `JwtSession` | `o.f.openig.session.jwt.JwtSessionManagerHeaplet` |
| `JwtSessionManager` | `o.f.openig.session.jwt.JwtSessionManagerHeaplet` |
| `JwtValidationFilter` | `o.f.openig.filter.jwt.JwtValidationFilter` |
| `KeyManager` | `o.f.openig.security.KeyManagerHeaplet` |
| `KeyStore` | `o.f.openig.security.KeyStoreHeaplet` |
| `KeyStoreSecretStore` | `o.f.openig.secrets.KeyStoreSecretStoreHeaplet` |
| `LocationHeaderFilter` | `o.f.openig.filter.LocationHeaderFilter` |
| `MappedThrottlingPolicy` | `o.f.openig.filter.throttling.MappedThrottlingPolicyHeaplet` |
| `NoOpAuditService` | `o.f.openig.audit.NoOpAuditService` |
| `NoProxyOptions` | `o.f.openig.proxy.NoProxyOptions` |
| `PasswordReplayFilter` | `o.f.openig.filter.PasswordReplayFilterHeaplet` |
| `ResourceHandler` | `o.f.openig.handler.resources.ResourceHandler` |
| `ReverseProxyHandler` | `o.f.openig.handler.ReverseProxyHandlerHeaplet` |
| `Router` | `o.f.openig.handler.router.RouterHandler` |
| `RouterHandler` | `o.f.openig.handler.router.RouterHandler` |
| `ScheduledExecutorService` | `o.f.openig.thread.ScheduledExecutorServiceHeaplet` |

| `SequenceHandler` | `o.f.openig.handler.SequenceHandler` |
| `StaticRequestFilter` | `o.f.openig.filter.StaticRequestFilter` |
| `StaticResponseHandler` | `o.f.openig.handler.StaticResponseHandler` |
| `SwitchFilter` | `o.f.openig.filter.SwitchFilter` |
| `ThrottlingFilter` | `o.f.openig.filter.throttling.ThrottlingFilterHeaplet` |
| `TimerDecorator` | `o.f.openig.decoration.timer.TimerDecorator` |
| `TracingDecorator` | `o.f.openig.decoration.tracing.TracingDecorator` |
| `UriPathRewriteFilter` | `o.f.openig.filter.UriPathRewriteFilter` |
| `WelcomeHandler` | `o.f.openig.handler.WelcomeHandler` |


### Default Heap Objects

These objects are automatically available in the heap without explicit declaration:

| Name | Type | Notes |
|---|---|---|
| `ClientHandler` | `ClientHandlerHeaplet` | Default outbound HTTP client |
| `ReverseProxyHandler` | `ReverseProxyHandlerHeaplet` | Default reverse proxy |
| `ForgeRockClientHandler` | `Chain` | ClientHandler + TransactionIdOutboundFilter |
| `ScheduledExecutorService` | `ScheduledExecutorServiceHeaplet` | Default executor (pool=1) |
| `TemporaryStorage` | `TemporaryStorageHeaplet` | Temp file management |
| `TransactionIdOutboundFilter` | `TransactionIdOutboundFilterHeaplet` | Propagates TxnId |
| `AuditService` | `NoOpAuditService` | Default no-op audit |
| `ProxyOptions` | `NoProxyOptions` | Default no-proxy |
| `capture` | `CaptureDecorator` | Built-in decorator |
| `timer` | `TimerDecorator` | Built-in decorator |
| `baseURI` | `BaseUriDecorator` | Built-in decorator |
| `tracing` | `TracingDecorator` | Built-in decorator |
| `BASE64` | `Base64PropertyFormat` | Secret format |
| `PLAIN` | `PlainPropertyFormatHeaplet` | Secret format |


---

## 24. Utility Classes Reference

### `Filters` Utility Class

Static utility class providing factory methods for composing and wrapping filters
at the CHF level. These are the low-level building blocks that
components use internally.

```java
// Identity filter — passes through unchanged
private static final Filter EMPTY_FILTER =
    (context, request, next) -> next.handle(context, request);

// Compose multiple filters into a single logical filter (recursive chain)
static Filter chainOf(Filter... filters)
static Filter chainOf(List<Filter> filters)
// Implementation: recursively combines via Handlers.filtered(next, secondFilter)
// Edge cases: empty list → EMPTY_FILTER, single element → that filter directly

// Conditional filter — only applies delegate when predicate matches
static Filter conditionalFilter(Filter delegate, BiPredicate<Context, Request> condition)
// Implementation: condition.test(context, request) ? delegate.filter(...) : next.handle(...)

// URI path matching predicates (for use with conditionalFilter)
static BiPredicate<Context, Request> matchRequestUriPath(Pattern regex)
static BiPredicate<Context, Request> matchRequestUriPath(String regex)
// Implementation: regex.matcher(request.getUri().getPath()).matches()

// Request copy filter — creates a copy of the request before forwarding
static Filter requestCopyFilter()
// Implementation: new Request(request) + closeSilently on response completion
// Use case: when the filter may modify the request and downstream needs the original

// Session management filters
static Filter newSessionFilter(SessionManager)          // @Deprecated
static Filter newAsyncSessionFilter(AsyncSessionManager) // preferred

// HTTP OPTIONS filter
static Filter newOptionsFilter(String... allowedMethods)

// HTTP Basic Auth client filters (add Authorization header)
static Filter newHttpBasicAuthenticationFilter(CredentialPair<GenericSecret>)
static Filter newHttpBasicAuthenticationFilter(CredentialPair<GenericSecret>, Charset)
static Filter newUrlEncodedHttpBasicAuthFilter(CredentialPair<GenericSecret>)

// Bearer token auth client filter (with automatic retry on 401)
static Filter newBearerTokenAuthFilter(SecretReference<GenericSecret> tokenReference)
static Filter newBearerTokenAuthFilterWithoutRetry(SecretReference<GenericSecret>)

// CSRF protection filter
static CsrfFilter.Builder newCsrfFilter(String cookieName)
static Filter newDefaultCsrfFilter(String cookieName) // excludes safe methods
```

> **Key pattern: `conditionalFilter` + `matchRequestUriPath`.**
> This is the programmatic equivalent of route-level conditions. Use it to
> apply a filter only to specific URI patterns without creating separate routes.
>
> **Key pattern: `chainOf` for programmatic filter composition.**
> `Filters.chainOf(filterA, filterB, filterC)` produces the same pipeline as
> a `"Chain"` declaration in JSON config. The recursion uses `Handlers.filtered()`
> to wrap each filter around the next-in-chain handler.
>
> **Bearer token retry pattern.** The `BearerTokenAuthClientFilter` automatically
> retries idempotent requests (GET, HEAD, OPTIONS, TRACE, PUT, DELETE) with a
> refreshed token when a 401 response includes a `WWW-Authenticate: Bearer`
> challenge with `error=invalid_token`. Non-idempotent methods are not retried.


### `Handlers` Utility Classes

PingGateway has **two** `Handlers` utility classes at different layers:

**CHF Layer** — `org.forgerock.http.handler.Handlers`:

```java
// Wrap a handler with a filter (creates a DescribableHandler)
static DescribableHandler filtered(Handler handler, Filter filter)
// Implementation: filter.filter(context, request, handler)

// Chain handler through multiple filters (applies filters in order)
static DescribableHandler chainOf(Handler handler, Filter... filters)
static DescribableHandler chainOf(Handler handler, List<Filter> filters)
// Implementation: iterates in reverse order, wrapping each filter via filtered()

// Adapter: wrap any Handler as DescribableHandler (adds API description support)
static DescribableHandler asDescribableHandler(Handler handler)

// Error response handlers
static Handler internalServerErrorHandler(Exception cause)
static Handler forbiddenHandler()
```

**IG Layer** — `org.forgerock.openig.handler.Handlers`:

```java
// Pre-built constant handlers for common error responses
public static final Handler FORBIDDEN              // 403
public static final Handler UNAUTHORIZED           // 401
public static final Handler NO_CONTENT             // 204
public static final Handler INTERNAL_SERVER_ERROR   // 500
public static final Handler BAD_REQUEST            // 400
```

> **Two Handlers classes — know the difference.** The CHF-layer `Handlers`
> provides composition utilities (`chainOf`, `filtered`). The IG-layer
> `Handlers` provides stock error handler constants. Both are commonly used
> in heaplet `create()` methods.
>
> **`Handlers.chainOf` is the inverse of `Filters.chainOf`.** The former wraps
> a terminal handler in filters (producing a handler). The latter composes
> filters into a compound filter (that still needs a terminal handler).


### `Responses` Utility Class

Factory methods for common error responses and exception function adapters:

```java
// Immediate response factories
static Response newInternalServerError()               // 500
static Response newInternalServerError(Exception e)    // 500 with cause
static Response newNotFound()                          // 404
static Response newForbidden()                         // 403

// Exception-to-response adapters (for Promise error branches)
static <E extends Exception> Function<E, Response, NeverThrowsException>
    onExceptionInternalServerError()
// Usage: promise.thenCatch(Responses.onExceptionInternalServerError())

static <E extends Exception> AsyncFunction<E, Response, NeverThrowsException>
    internalServerError()
// Usage: promise.thenCatchAsync(Responses.internalServerError())

// NeverThrowsException adapters (for Promise type gymnastics)
static <V, E extends Exception> Function<NeverThrowsException, V, E>
    noopExceptionFunction()
static <V, E extends Exception> AsyncFunction<NeverThrowsException, V, E>
    noopExceptionAsyncFunction()
```

> **`Responses.internalServerError()` as error function.** This is the idiomatic
> way to convert an exception in an async chain into a 500 response.
> Auth filters use it extensively:
> ```java
> tokenReference.getAsync()
>     .thenAsync(token -> next.handle(context, request),
>                Responses.internalServerError());
> ```


### `JsonValues` Utility Class

The primary configuration parsing toolkit. Every heaplet uses these functional
transforms to convert JSON config into typed Java objects.

**Core Value Transforms:**

```java
// Expression evaluation (string interpolation + EL)
static Function<JsonValue, JsonValue, JsonValueException> evaluated()
static Function<JsonValue, JsonValue, JsonValueException> evaluated(Bindings bindings)
static AsyncFunction<JsonValue, JsonValue, NeverThrowsException> evaluatedAsync(Bindings)

// Expression parsing (compile string to typed Expression)
static <T> Function<JsonValue, Expression<T>, JsonValueException>
    expression(Class<T> type)
static <T> Function<JsonValue, Expression<T>, JsonValueException>
    expression(Class<T> type, Bindings bindings)

// LeftValueExpression parsing (for assignment targets)
static <T> Function<JsonValue, LeftValueExpression<T>, JsonValueException>
    leftValueExpression(Class<T> type)

// Bindings construction from JSON
static Function<JsonValue, Bindings, JsonValueException> bindings()
static Function<JsonValue, Bindings, JsonValueException> bindings(Bindings initial)
```

**Heap Object Resolution:**

```java
// Resolve a JSON reference to a heap object (required — throws if not found)
static <T> Function<JsonValue, T, HeapException>
    requiredHeapObject(Heap heap, Class<T> type)
// Usage: config.get("handler").as(JsonValues.requiredHeapObject(heap, Handler.class))

// Resolve a JSON reference to a heap object (optional — returns null if not found)
static <T> Function<JsonValue, T, HeapException>
    optionalHeapObject(Heap heap, Class<T> type)

// Lookup by name first, then create inline if not found
static <T> Function<JsonValue, T, HeapException>
    lookupOrCreateHeapObject(Heap heap, Class<T> type)
// Supports both: "myHandler" (string ref) and { "name": "...", "type": "..." } (inline)
```

**Collection Transforms:**

```java
// Parse JSON array (or single element) to List
static <T, E extends Exception> Function<JsonValue, List<T>, E>
    listOf(Function<JsonValue, T, E> adapter)
// null → emptyList, array → list, single → singletonList

// Parse JSON array to Set
static <T, E extends Exception> Function<JsonValue, Set<T>, E>
    setOf(Function<JsonValue, T, E> adapter)

// Parse JSON object to Map<String, V>
static <V, E extends Exception> Function<JsonValue, Map<String, V>, E>
    mapOf(Function<JsonValue, V, E> transformFunction)

// Parse JSON array to Stream
static <V, E extends Exception> Function<JsonValue, Stream<V>, E>
    streamOf(Function<JsonValue, V, E> transformFunction)
```

**Optional Handling:**

```java
// Wrap a JsonValue in Optional (null → empty)
static Function<JsonValue, Optional<JsonValue>, JsonValueException> optional()
// Usage: config.get("failureHandler").as(JsonValues.optional())

// Compose optional with a transform
static <T> Function<JsonValue, Optional<T>, JsonValueException>
    optionalOf(Function<JsonValue, T, JsonValueException> delegate)
// Usage: config.get("timeout").as(JsonValues.optionalOf(javaDuration()))
```

**Duration & Validation:**

```java
// Positive, limited duration (rejects zero and unlimited)
static Function<JsonValue, Duration, JsonValueException>
    positiveLimitedDuration(Duration defaultDuration)

// Limited duration (allows zero, rejects unlimited)
static Function<JsonValue, Duration, JsonValueException>
    limitedDuration(Duration defaultDuration)

// Required validation with custom message
static Function<JsonValue, JsonValue, JsonValueException> required(String message)
```

**URI & Path Handling:**

```java
// Ensure trailing slash / remove trailing slash
static Function<JsonValue, JsonValue, JsonValueException> slashEnded()
static Function<JsonValue, JsonValue, JsonValueException> trailingSlashRemoved()

// Multi-value field priority (first defined wins)
static JsonValue firstOf(JsonValue config, String... names)
```

**Deprecation & Migration:**

```java
// Read config with fallback to deprecated attribute names
static JsonValue getWithDeprecation(JsonValue config, Logger logger,
    String name, String... deprecatedNames)
// Logs a warning when the deprecated name is used

static void warnForDeprecation(JsonValue config, Logger logger,
    String name, String deprecatedName)
```

**Class Resolution:**

```java
// Resolve a string to a Class<?> via ClassAliasResolver then Class.forName
static Class<?> asClass(JsonValue value)
```

> **Idiomatic config parsing pattern.** Almost every heaplet `create()` method
> follows this pattern:
> ```java
> Handler failureHandler = ((Optional<Handler>) config
>     .get("failureHandler")
>     .as(JsonValues.optional()))
>     .map(LambdaExceptionUtils.rethrowFunction(
>         v -> (Handler) v.as(JsonValues.requiredHeapObject(heap, Handler.class))))
>     .orElse(Handlers.INTERNAL_SERVER_ERROR);
> ```
>
> **`listOf` auto-wraps scalars.** If the JSON value is a single element instead
> of an array, `listOf` wraps it in a singleton list. This enables both
> `"header": "X-Foo"` and `"header": ["X-Foo", "X-Bar"]` in config.


---

## 25. Filters & Handlers Catalog

### Filters (Complete Catalog)

| Filter | Purpose | Pattern |
|--------|---------|--------|
| `AllowOnlyFilter` | Whitelist allowed HTTP methods | Access-control |
| `AssignmentFilter` | Set context attributes or session values via expressions | Dual-list (onRequest/onResponse) |
| `CircuitBreakerFilter` | Circuit breaker for downstream failures | Resilience |
| `ConditionEnforcementFilter` | Gate requests by condition, route failures to handler | Condition + failure handler |
| `ConditionalFilter` | Apply a wrapped filter conditionally | Conditional delegation |
| `CookieFilter` | Manage cookies across redirects | Cookie manipulation |
| `CorsFilter` | CORS preflight and header injection | Standards compliance |
| `CsrfFilter` | CSRF token validation | Security |
| `DataPreservationFilter` | Preserve POST data across redirects | State preservation |
| `DateHeaderFilter` | Set the `Date` response header | Header injection |
| `EntityExtractFilter` | Extract regex matches from entity into expressions | Reactive streaming |
| `FapiInteractionIdFilter` | Set FAPI interaction ID header | Standards compliance |
| `FileAttributesFilter` | Set attributes from file content | Configuration |
| `ForwardedRequestFilter` | Rewrite request URI from forwarded headers | URI rewriting |
| `FragmentFilter` | Preserve URL fragments across redirects | State preservation |
| `HeaderFilter` | Add/remove/replace headers on request or response | MessageType dispatch |
| `HttpBasicAuthFilter` | Add Basic auth to outgoing requests | Outbound auth |
| `JwtBuilderFilter` | Build signed/encrypted JWTs | Token creation |
| `JwtValidationFilter` | Validate signed/encrypted JWTs | Token validation |
| `LocationHeaderFilter` | Rewrite `Location` headers for proxied responses | Header rewriting |
| `PasswordReplayFilter` | Replay credentials to protected apps | Auth replay |

| `SetCookieUpdateFilter` | Modify Set-Cookie response headers | Cookie manipulation |
| `SqlAttributesFilter` | Set attributes from SQL query results | Data enrichment |
| `StaticRequestFilter` | Replace request method, URI, form data | Static replacement |
| `SwitchFilter` | Route to different handlers based on conditions | Conditional routing |
| `ThrottlingFilter` | Rate-limit requests by grouping policy | Rate limiting |
| `TransactionIdOutboundFilter` | Propagate transaction ID to downstream | Distributed tracing |
| `UriPathRewriteFilter` | Rewrite URI path segments | URI rewriting |

### Handlers Catalog

| Handler | Purpose |
|---------|--------|
| `Chain` | Compose filters with a terminal handler |
| `ChainOfFilters` | Compose filters into a single filter (no terminal handler) |
| `ClientHandler` | Send requests to remote servers (outbound calls) |
| `DispatchHandler` | Route requests based on condition bindings |
| `JwkSetHandler` | Serve JWKS endpoints |
| `ResourceHandler` | Serve static files |
| `ReverseProxyHandler` | Forward requests to protected application (main proxy) |
| `Router` / `RouterHandler` | Route to sub-routes based on conditions |

| `SequenceHandler` | Execute handlers in sequence |
| `StaticResponseHandler` | Return a static response (testing/stubs) |
| `WelcomeHandler` | Return a welcome page |

### Key Filter Patterns (by example)

#### HeaderFilter — MessageType Dispatch

Operates on either the request or response phase based on `messageType`:

```json
{
  "type": "HeaderFilter",
  "config": {
    "messageType": "REQUEST",
    "add": {
      "X-Custom-Header": ["value1", "value2"]
    },
    "remove": ["X-Unwanted"],
    "replace": {
      "Host": ["${request.uri.host}"]
    }
  }
}
```

`messageType` is `"REQUEST"` or `"RESPONSE"`. The filter removes headers
first, then adds/replaces — all using expression-evaluated values.

#### SwitchFilter — Conditional Routing

Evaluates request-phase and response-phase conditions independently,
routing to the first matching handler:

```json
{
  "type": "SwitchFilter",
  "config": {
    "onRequest": [
      {
        "condition": "${find(request.uri.path, '/api')}",
        "handler": "ApiHandler"
      }
    ],
    "onResponse": [
      {
        "condition": "${response.status.code == 401}",
        "handler": "UnauthorizedHandler"
      },
      {
        "condition": "${response.status.isServerError()}",
        "handler": "ServerErrorHandler"
      }
    ]
  }
}
```

> The `SwitchFilter` copies the request before forwarding so that
> response-phase conditions can re-handle with a different handler if needed.
> If no case matches, the original response passes through unmodified.

#### ConditionalFilter — Guarded Filter Application

Wraps any filter with a condition — the delegate only runs when the
condition is true:

```json
{
  "type": "ConditionalFilter",
  "config": {
    "condition": "${find(request.uri.path, '/api')}",
    "delegate": {
      "type": "HeaderFilter",
      "config": {
        "messageType": "REQUEST",
        "add": { "X-API-Flag": ["true"] }
      }
    }
  }
}
```

#### ConditionEnforcementFilter — Access Control Gate

Tests a condition and routes failures to a dedicated handler
(defaults to 403 Forbidden):

```json
{
  "type": "ConditionEnforcementFilter",
  "config": {
    "condition": "${request.headers['X-Api-Key'][0] == 'secret'}",
    "failureHandler": {
      "type": "StaticResponseHandler",
      "config": {
        "status": 403,
        "reason": "Forbidden",
        "entity": "Access denied"
      }
    }
  }
}
```

#### AssignmentFilter — Dual-Phase Variable Assignment

Sets expression targets in both request and response phases,
with optional per-binding conditions:

```json
{
  "type": "AssignmentFilter",
  "config": {
    "onRequest": [
      {
        "target": "${attributes.startTime}",
        "value": "${now.epochSeconds}"
      }
    ],
    "onResponse": [
      {
        "condition": "${response.status.isSuccessful()}",
        "target": "${session.username}",
        "value": "${contexts.oauth2.accessToken.info.sub}"
      }
    ]
  }
}
```

#### EntityExtractFilter — Reactive Entity Parsing

Extracts named regex groups from the entity body using reactive
streaming (line-by-line processing, early termination on all matches):

```json
{
  "type": "EntityExtractFilter",
  "config": {
    "messageType": "RESPONSE",
    "target": "${attributes.extracted}",
    "bindings": [
      {
        "key": "title",
        "pattern": "<title>(.*?)</title>"
      },
      {
        "key": "csrfToken",
        "pattern": "name=\"csrf_token\" value=\"(.*?)\"",
        "template": "$1"
      }
    ]
  }
}
```

### DispatchHandler Example

```json
{
  "type": "DispatchHandler",
  "config": {
    "bindings": [
      {
        "condition": "${find(request.uri.path, '/api')}",
        "handler": "ApiChain"
      },
      {
        "handler": "DefaultHandler",
        "condition": "${request.method == 'GET'}"
      }
    ]
  }
}
```

> **For message-xform:** Understanding these filters is important for
> documentation and for configuring test routes during E2E testing. Our
> `MessageTransformFilter` replaces the need for ad-hoc `HeaderFilter` +
> `AssignmentFilter` chains with a single spec-driven transformation.
> The `SwitchFilter` pattern directly informs our conditional response
> routing design (ADR-0036).

---


---

## 26. Key Differences from PingAccess & PingFederate

### Architecture Comparison

| Dimension | PingAccess 9.0.1 | PingFederate 13.0 | PingGateway 2025.11 |
|-----------|-----------------|-------------------|---------------------|
| Extension SPI | `AsyncRuleInterceptor` | IdP/SP Adapters, PCV, etc. | `Filter` |
| Config model | `@UIElement` + admin UI | Descriptor XML + admin UI | JSON route + `Heaplet` |
| Request/Response | `Exchange` (single object) | `HttpServletRequest/Response` | Separate `Request` + `Response` via promise |
| Body API | `Body.getContent()/setBodyContent()` | `HttpServletRequest.getInputStream()` | `Entity.getBytes()/setBytes()` |
| Headers | `Headers.asMap()` → `Map<String, String[]>` | `HttpServletRequest.getHeaders()` | `Headers.copyAsMultiMapOfStrings()` |
| Header case | Case-sensitive, caller normalizes | Case-insensitive (Servlet API) | Case-insensitive (CHF) |
| Session | `Identity` + `OAuthTokenMetadata` + `SessionStateSupport` | `SourceDescriptor` attributes | `SessionContext` + `AttributesContext` + `OAuth2Context` |
| Error handling | `ResponseBuilder` + `AccessException` + `ErrorHandlingCallback` | `AuthnAdapterException` | `new Response(Status.xxx)` directly |
| Deployment | Shadow JAR in `<PA_HOME>/deploy/` | JAR in `<PF>/server/default/deploy/` | JAR in `$HOME/.openig/extra/` |
| Admin UI | Yes (PA admin panel) | Yes (PF admin console) | No admin UI for custom filters |
| Restart required | Yes | Yes | Yes (for JARs; routes hot-reload) |
| Discovery | `ServiceLoader` for `AsyncRuleInterceptor` | `ServiceLoader` + descriptor XML | `ClassAliasResolver` + nested `Heaplet` |

### GatewayAdapter Type Mapping

| GatewayAdapter Method | PingAccess | PingGateway |
|----------------------|------------|-------------|
| Request body | `exchange.getRequest().getBody().getContent()` | `request.getEntity().getBytes()` |
| Response body | `exchange.getResponse().getBody().getContent()` | `response.getEntity().getBytes()` |
| Request headers | `exchange.getRequest().getHeaders().asMap()` | `request.getHeaders().copyAsMultiMapOfStrings()` |
| Response headers | `exchange.getResponse().getHeaders().asMap()` | `response.getHeaders().copyAsMultiMapOfStrings()` |
| Status code | `exchange.getResponse().getStatusCode()` | `response.getStatus().getCode()` |
| Request path | `exchange.getRequest().getUrl().getPath()` | `request.getUri().getPath()` |
| Request method | `exchange.getRequest().getMethod().name()` | `request.getMethod()` |
| Query string | `exchange.getRequest().getUrl().getQuery()` | `request.getUri().getQuery()` |
| Set body | `exchange.getResponse().setBodyContent(bytes)` | `response.getEntity().setBytes(bytes)` |
| Set header | `exchange.getResponse().getHeaders().setHeader(k,v)` | `response.getHeaders().put(k, v)` |
| Set status | `exchange.getResponse().setStatus(HttpStatus.xxx)` | `response.setStatus(Status.valueOf(code))` |
| Set path | N/A (immutable in PA) | `request.getUri().setPath(path)` |
| Set method | N/A (immutable in PA) | `request.setMethod(method)` |

### Service Registration Comparison

| Product | Registration Mechanism |
|---------|----------------------|
| PingAccess | `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor` |
| PingFederate | `META-INF/services/<SPI-interface>` + descriptor XML |
| PingGateway | `META-INF/services/org.forgerock.openig.alias.ClassAliasResolver` |

### Module Structure

```
adapter-pinggateway/
├── build.gradle.kts             ← shadow JAR, compileOnly PG deps
└── src/
    ├── main/java/io/messagexform/pinggateway/
    │   ├── MessageTransformFilter.java          ← Filter implementation
    │   ├── PingGatewayAdapter.java              ← GatewayAdapter<PingGatewayExchange>
    │   ├── PingGatewayExchange.java             ← Context+Request+Response wrapper
    │   ├── MessageTransformClassAliasResolver.java  ← alias resolver
    │   └── FilterConfig.java                    ← typed config POJO
    ├── main/resources/
    │   └── META-INF/services/
    │       └── org.forgerock.openig.alias.ClassAliasResolver
    └── test/java/io/messagexform/pinggateway/
        ├── PingGatewayAdapterRequestTest.java
        ├── PingGatewayAdapterResponseTest.java
        ├── MessageTransformFilterTest.java
        └── PingGatewayExchangeTest.java
```

### Reuse from PingAccess Adapter

The following patterns can be reused directly:
- **Core engine integration** — `TransformEngine`, `SpecParser`, `ProfileLoader`
- **Error mode dispatch** — `PASS_THROUGH` vs `DENY` logic
- **Header diff logic** — same diff-based header application strategy
- **Shadow JAR configuration** — exclude PG-provided classes
- **Reload scheduler** — `ScheduledExecutorService` with daemon thread
- **Body parse fallback** — `MessageBody.empty()` on parse failure
- **JMX metrics** — `TransformMetrics` MBean registration

New work specific to PingGateway:
- `Filter` lifecycle (single method, promise chain)
- `Heaplet`-based configuration (replacing `@UIElement` annotations)
- Context-chain-based session extraction (replacing `Identity` model)
- `PingGatewayExchange` wrapper (bridging split request/response model)
- `ClassAliasResolver` registration (replacing `ServiceLoader` for SPI)
- No admin UI — configuration is JSON-only

---


---

## 27. Maven Dependencies & External Resources

### 23.1 Maven Repository

PingGateway artifacts are published to the ForgeRock Maven repository:

```xml
<repositories>
    <repository>
        <id>forgerock-private-releases</id>
        <url>https://maven.forgerock.org/repo/private-releases</url>
    </repository>
</repositories>
```

> **Note:** Access requires ForgeRock BackStage credentials. For our project,
> we use the JARs from the local distribution at
> `binaries/pinggateway/dist/identity-gateway-2025.11.1/lib/`.

### 23.2 Key JAR Files (From Distribution)

| JAR | Size | Purpose |
|---|---|---|
| `openig-core-2025.11.1.jar` | 883K | Core IG: filters, handlers, heap, config |
| `chf-http-core-27.1.0-*.jar` | 343K | CHF: Request/Response/Entity/Headers/Context |
| `forgerock-util-27.1.0-*.jar` | 210K | Promise API, Function, AsyncFunction |
| `openig-toolkit-core-2025.11.1.jar` | 220K | JWT, secret management tools |
| `openig-oauth2-2025.11.1.jar` | 272K | OAuth2 filter, resource server |
| `openig-openam-2025.11.1.jar` | 429K | PingAM integration, SSO |
| `openig-ping-2025.11.1.jar` | — | Ping Identity integration |

### 23.3 Gradle Dependency Configuration (for our adapter)

```groovy
// For compileOnly — we don't bundle PingGateway classes, they're provided at runtime
dependencies {
    compileOnly files(
        'binaries/pinggateway/dist/identity-gateway-2025.11.1/lib/openig-core-2025.11.1.jar',
        'binaries/pinggateway/dist/identity-gateway-2025.11.1/lib/chf-http-core-27.1.0-20251107135816-7efadf43b92e791e11ad58e7a01107577d83f956.jar',
        'binaries/pinggateway/dist/identity-gateway-2025.11.1/lib/forgerock-util-27.1.0-20251107135816-7efadf43b92e791e11ad58e7a01107577d83f956.jar',
    )
    // json-fluent / forgerock-json for JsonValue:
    compileOnly files(
        'binaries/pinggateway/dist/identity-gateway-2025.11.1/lib/json-resource-27.1.0-20251107135816-7efadf43b92e791e11ad58e7a01107577d83f956.jar',
    )
}
```

### 23.4 Maven POM Template (Community Pattern)

From the ForgeRock community guide on creating custom compiled Java filters:

```xml
<project>
    <groupId>com.example</groupId>
    <artifactId>my-custom-filter</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <ig.version>2025.11.1</ig.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.forgerock.openig</groupId>
            <artifactId>openig-core</artifactId>
            <version>${ig.version}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>forgerock-private-releases</id>
            <url>https://maven.forgerock.org/repo/private-releases</url>
        </repository>
    </repositories>
</project>
```

### 23.5 External Resources

| Resource | URL | Notes |
|---|---|---|
| **Aggregated Javadoc (2025.11.1)** | [apidocs/index.html](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/index.html) | **Primary API reference** — all packages, classes, methods |
| PingGateway Reference Guide | [reference/preface.html](https://docs.pingidentity.com/pinggateway/2025.11/reference/preface.html) | Config field conventions, reserved routes, duration syntax |
| PingGateway Official Docs | [docs.pingidentity.com/pinggateway/2025.11/](https://docs.pingidentity.com/pinggateway/2025.11/index.html) | Comprehensive guide, gateway-guide, configure |
| ForgeRock Community Guide | `community.forgerock.com` | "Creating Custom Compiled Java Filters For Ping Gateway" |
| OpenIG Config Examples (GitHub) | `github.com/wstrange/openig_examples` | Sample route configs, OIDC, OAuth2 |
| PingGateway GitHub Releases | `github.com/ForgeRock/PingGateway` | Version history, release notes |

**Javadoc Quick Links** (most-referenced types for extension development):

| Type | Javadoc URL |
|---|---|
| `Filter` | [org.forgerock.http.Filter](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/Filter.html) |
| `Handler` | [org.forgerock.http.Handler](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/Handler.html) |
| `Request` | [org.forgerock.http.protocol.Request](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Request.html) |
| `Response` | [org.forgerock.http.protocol.Response](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Response.html) |
| `Entity` | [org.forgerock.http.protocol.Entity](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Entity.html) |
| `Headers` | [org.forgerock.http.protocol.Headers](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Headers.html) |
| `Status` | [org.forgerock.http.protocol.Status](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/http/protocol/Status.html) |
| `Context` | [org.forgerock.services.context.Context](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/services/context/Context.html) |
| `GenericHeaplet` | [org.forgerock.openig.heap.GenericHeaplet](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/openig/heap/GenericHeaplet.html) |
| `Heap` | [org.forgerock.openig.heap.Heap](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/openig/heap/Heap.html) |
| `JsonValue` | [org.forgerock.json.JsonValue](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/json/JsonValue.html) |
| `Promise` | [org.forgerock.util.promise.Promise](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/util/promise/Promise.html) |
| `ClassAliasResolver` | [org.forgerock.openig.alias.ClassAliasResolver](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/openig/alias/ClassAliasResolver.html) |
| `Expression` | [org.forgerock.openig.el.Expression](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/openig/el/Expression.html) |
| `Bindings` | [org.forgerock.openig.el.Bindings](https://docs.pingidentity.com/pinggateway/2025.11/_attachments/apidocs/org/forgerock/openig/el/Bindings.html) |

### 23.6 Key Development Requirements

From the community guide and our analysis:

- **Java:** OpenJDK 17+ (verified from distribution)
- **Build:** Maven or Gradle
- **Deployment:** Copy shadow JAR to `$HOME/.openig/extra/`
- **Restart:** Required after deploying new JARs
- **Route config:** Custom filter type referenced by FQCN or via ClassAliasResolver alias

---

*Status: COMPLETE — PingGateway 2025.11 extension model fully documented
and mapped to message-xform adapter integration strategy. All extractable
content from `docs/reference/vendor/pinggateway-2025.11.txt` has been mined.
§22 enriched with 60 subsections covering: utility class APIs, the Condition
evaluation pattern, representative implementation patterns (routing,
rewriting, extraction, forwarding), resilience patterns (retry,
circuit breaker), dynamic response construction, config-driven chain
assembly, inline cache wrapping, parallel promise joining, side-car token
transformation, Promise↔CompletableFuture bridging, multi-decorator
chaining, dynamic route management with CAS, token bucket rate limiting,
session-backed cookie management, self-documenting predicate composition,
conditional filter wrapping, header manipulation with expression evaluation,
credential caching with auth retry, POST body preservation across redirects,
correlation ID propagation, composite implicit filter stack composition,
predicate-based CORS, Groovy script extensibility, RxJava streaming regex
extraction, double-checked lazy metric registration, CREST audit event
emission, session load/save lifecycle, JWT construction with context injection,
Location header rebasing, outbound request factory, and original URI rebasing.
External resources (community guides, GitHub samples) identified and cataloged.*


---

*Status: COMPLETE — PingGateway 2025.11 extension model fully documented.
Restructured into 5 parts with 27 sections. All implementation patterns
organized by theme (routing, security, resilience, session, async, lifecycle,
URI rewriting, scripting). API reference data consolidated in Part V.
External resources (community guides, GitHub samples) cataloged.*

