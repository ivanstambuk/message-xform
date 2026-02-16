# PingGateway Filter/Handler API — Research Notes

Date: 2026-02-16
Status: Complete
Feature: 003 – PingGateway Adapter

## Purpose

Research the PingGateway Java extension model to determine the optimal
integration strategy for message-xform as a custom Filter.

---

## 1. Extension Architecture

### 1.1 Extension Points

PingGateway (version 2025.11) provides four key extension interfaces:

| Interface | Purpose | Stability |
|-----------|---------|-----------|
| `Filter` | Intercepts request/response in a chain | **Evolving** |
| `Handler` | Terminal handler that generates responses | Evolving |
| `Decorator` | Adds cross-cutting behavior to objects | Evolving |
| `ExpressionPlugin` | Extends the expression language | Evolving |
| `ClassAliasResolver` | Maps short names to FQCNs | Evolving |

**Filter is the natural fit** for message-xform — it intercepts requests and
responses in the filter chain, which matches the adapter pattern used for
PingAccess (`RuleInterceptor`) and Standalone (`ProxyHandler`).

### 1.2 Filter Interface

```java
package org.forgerock.http;

public interface Filter {
    Promise<Response, NeverThrowsException> filter(
        Context context,
        Request request,
        Handler next
    );
}
```

**Key differences from PingAccess:**

| Aspect | PingAccess | PingGateway |
|--------|------------|-------------|
| SPI | `AsyncRuleInterceptor` (separate `handleRequest`/`handleResponse`) | Single `filter()` method |
| Request/Response separation | Two separate methods | Single method — response is accessed via `next.handle()` promise |
| Return type | `CompletionStage<Outcome>` / `CompletionStage<Void>` | `Promise<Response, NeverThrowsException>` |
| Configuration | `@UIElement` annotations, `SimplePluginConfiguration` | JSON route config + `GenericHeaplet` |
| Packaging | Shadow JAR in `deploy/` | JAR in `$HOME/.openig/extra/` |
| Native types | `Exchange`, PA `Request`/`Response`/`Body`/`Headers` | CHF `Request`/`Response`/`Entity`/`Headers` |

### 1.3 Request/Response Model

PingGateway uses the **Common HTTP Framework (CHF)** types from
`org.forgerock.http.protocol`:

#### Request

```java
org.forgerock.http.protocol.Request
  .getUri()       → MutableUri (path, query, scheme, host, port)
  .getMethod()    → String
  .getHeaders()   → Headers (Map<String, Header>)
  .getEntity()    → Entity (body content)
  .getCookies()   → Map<String, Cookie>  // parsed from Cookie header
```

#### Response

```java
org.forgerock.http.protocol.Response
  .getStatus()    → Status (code + reason phrase)
  .getHeaders()   → Headers
  .getEntity()    → Entity (body content)
```

#### Entity (Body)

```java
org.forgerock.http.protocol.Entity
  .getJson()      → Object (parsed JSON via Jackson — if available)
  .setJson(obj)   → void   (serialize Object to JSON entity)
  .getBytes()     → byte[]
  .setBytes(bytes)→ void
  .getString()    → String
  .setString(str) → void
  .getRawContentInputStream() → InputStream
  .isEmpty()      → boolean
  .close()        → void   // releases underlying buffer
```

**Key insight:** `Entity.getJson()` and `Entity.setJson()` handle JSON
serialization/deserialization natively using the PingGateway-bundled Jackson.
However, for message-xform integration, we should use `getBytes()`/`setBytes()`
to work with raw byte arrays — consistent with the PingAccess adapter pattern
and avoiding assumptions about PG's internal Jackson behavior.

#### Headers

```java
org.forgerock.http.protocol.Headers
  .get(String name)       → Header          // case-insensitive
  .put(String name, val)  → void            // set/replace
  .add(String name, val)  → void            // append
  .remove(String name)    → void
  .getFirst(String name)  → String          // first value
  .copyAsMultiMapOfStrings() → Map<String, List<String>>  // snapshot
```

Headers in CHF are **case-insensitive** natively — unlike PA's `Headers` API,
which returns `Map<String, String[]>`. This simplifies our header normalization.

### 1.4 Context Chain

PingGateway uses a hierarchical `Context` chain (not a flat `Exchange`):

```
RootContext
  └── SessionContext          ← session attributes (stateful/stateless)
      └── TransactionIdContext  ← correlation ID
          └── UriRouterContext   ← matched route info
              └── AttributesContext  ← arbitrary key-value pairs
                  └── OAuth2Context   ← OAuth token info (if OAuth validated)
                      └── ...
```

Access session data via:
```java
context.asContext(SessionContext.class).getSession()  // Map-like session
context.asContext(AttributesContext.class).getAttributes()  // route-level attrs
```

**For `$session` binding:** The PingGateway `SessionContext` provides a
different abstraction than PingAccess's `Identity`-based model. PingGateway
sessions are more HTTP-session-like (key-value map), whereas PingAccess has
structured `Identity`/`OAuthTokenMetadata`/`SessionStateSupport` layers.

---

## 2. Filter Lifecycle & Request/Response Handling

### 2.1 Single-Method Bidirectional Pattern

Unlike PingAccess (separate `handleRequest`/`handleResponse`), PingGateway
filters use a **single `filter()` method** with the promise chain:

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

**Critical constraint: Non-blocking mandate.** PingGateway docs explicitly
warn against using blocking methods (`get()`, `getOrThrow()`) on promises.
The adapter MUST use `thenOnResult()`, `thenAsync()`, or `thenCatch()`.

However, JSLT transformation is CPU-bound (synchronous), not I/O-bound.
The pattern of calling the synchronous transform engine within `thenOnResult()`
(response phase) or directly (request phase) is acceptable — there is no
async I/O to block on.

### 2.2 Configuration via Heaplet

PingGateway uses a **heap** object model. Each custom filter must provide a
nested `Heaplet` class extending `GenericHeaplet`:

```java
public static class Heaplet extends GenericHeaplet {
    @Override
    public Object create() throws HeapException {
        MessageTransformFilter filter = new MessageTransformFilter();
        filter.specsDir = config.get("specsDir")
            .as(evaluatedWithHeapProperties())
            .required()
            .asString();
        filter.profilesDir = config.get("profilesDir")
            .as(evaluatedWithHeapProperties())
            .defaultTo("/profiles")
            .asString();
        // ... other config
        return filter;
    }

    @Override
    public void destroy() {
        // Cleanup: shutdown reload executor, etc.
    }
}
```

The `destroy()` method provides clean lifecycle teardown — equivalent to
PingAccess's `@PreDestroy`.

### 2.3 Route Configuration

The filter is configured in a PingGateway route JSON file:

```json
{
  "handler": {
    "type": "Chain",
    "config": {
      "filters": [
        {
          "type": "MessageTransformFilter",
          "config": {
            "specsDir": "/path/to/specs",
            "profilesDir": "/path/to/profiles",
            "activeProfile": "production",
            "errorMode": "PASS_THROUGH",
            "reloadIntervalSec": 30
          }
        }
      ],
      "handler": "ReverseProxyHandler"
    }
  },
  "condition": "${find(request.uri.path, '^/api')}"
}
```

---

## 3. Deployment Model

### 3.1 JAR Packaging

Custom extensions are deployed as JARs to `$HOME/.openig/extra/`:

```
$HOME/.openig/
├── config/
│   ├── admin.json          ← admin config
│   └── routes/
│       └── 10-transform.json  ← route using our filter
├── extra/
│   └── message-xform-pinggateway.jar  ← our shadow JAR
└── scripts/
    └── groovy/             ← Groovy scripts (if any)
```

Dependencies not already in PingGateway must be included. PingGateway bundles:
- Jackson (version TBD — must align like PA)
- SLF4J
- CHF (common HTTP framework)

The shadow JAR strategy should mirror `adapter-pingaccess`: bundle
`message-xform-core` and its dependencies (SnakeYAML, JSLT), exclude
PG-provided classes (Jackson, SLF4J).

### 3.2 Service Registration

Two `META-INF/services/` files are required:

1. **ClassAliasResolver** — maps `"MessageTransformFilter"` to FQCN:
   ```
   META-INF/services/org.forgerock.openig.alias.ClassAliasResolver
   → io.messagexform.pinggateway.MessageTransformClassAliasResolver
   ```

2. **Heaplet** — discovered automatically via the inner `Heaplet` class.
   No separate registration needed (PingGateway scans for nested `Heaplet`
   classes via `Heaplets.findHeapletClass()`).

### 3.3 PingGateway Dependencies

**Key question:** What PingGateway JARs are needed at compile time?

Unlike PingAccess, PingGateway does **not** have a standalone SDK JAR.
The CHF libraries are published to Maven Central under the `org.forgerock`
group, but availability depends on ForgeRock's Maven repository access policy.

**Options:**
1. **ForgeRock Maven repository** — https://maven.forgerock.org/artifactoryapi/repo/
   Contains `org.forgerock.http:chf-http-core`, `org.forgerock.openig:openig-core`, etc.
2. **Extract from PingGateway distribution** — Download PingGateway ZIP, extract
   JARs needed for compilation (`openig-core-*.jar`, `chf-http-core-*.jar`).
3. **Interface-only stubs** — Create minimal interface stubs for compilation.

**Recommendation:** Use the ForgeRock Maven repository (option 1) as the
primary approach, with local JAR fallback (option 2) like the PA SDK pattern.
Check repository accessibility first.

---

## 4. Mapping to GatewayAdapter SPI

### 4.1 Native Type: Filter Pair (Context + Request)

Unlike PingAccess where both request and response are accessed via `Exchange`,
PingGateway provides request and response through different mechanisms:

| Phase | Data Access |
|-------|-------------|
| REQUEST | `Request request` parameter from `filter()` |
| RESPONSE | `Response response` from `next.handle()` promise result |

**GatewayAdapter type parameter:** `GatewayAdapter<?>` — this is tricky
because the request and response arrive through different paths. Options:

| Option | Type Param | Approach |
|--------|-----------|----------|
| A | `GatewayAdapter<Request>` | Separate adapter methods, response wrapping takes raw `Response` |
| B | `GatewayAdapter<Object>` | Wrapper holding both context and request/response |
| C | Custom wrapper | `PingGatewayExchange` wrapper class holding `Context` + `Request` + `Response` |

**Recommendation: Option C** — Create a lightweight `PingGatewayExchange`
record/class that wraps the `Context`, `Request`, and (optionally) `Response`:

```java
record PingGatewayExchange(Context context, Request request, Response response) {
    static PingGatewayExchange forRequest(Context ctx, Request req) {
        return new PingGatewayExchange(ctx, req, null);
    }
    static PingGatewayExchange forResponse(Context ctx, Request req, Response resp) {
        return new PingGatewayExchange(ctx, req, resp);
    }
}
```

This mirrors PingAccess's `Exchange` concept while adapting to PingGateway's
split request/response model.

### 4.2 Field Mapping

#### wrapRequest(PingGatewayExchange)

| Message Field | Source |
|---------------|--------|
| `body` | `request.getEntity().getBytes()` → validate JSON → `MessageBody.json(bytes)` or `MessageBody.empty()` |
| `headers` | `request.getHeaders().copyAsMultiMapOfStrings()` → lowercase keys → `HttpHeaders.ofMulti(map)` |
| `statusCode` | `null` (request has no status) |
| `requestPath` | `request.getUri().getPath()` |
| `requestMethod` | `request.getMethod()` → uppercase |
| `queryString` | `request.getUri().getQuery()` |
| `session` | `SessionContext` from context chain (see §4.3) |

#### wrapResponse(PingGatewayExchange)

| Message Field | Source |
|---------------|--------|
| `body` | `response.getEntity().getBytes()` → validate JSON → `MessageBody.json(bytes)` or `MessageBody.empty()` |
| `headers` | `response.getHeaders().copyAsMultiMapOfStrings()` → lowercase keys → `HttpHeaders.ofMulti(map)` |
| `statusCode` | `response.getStatus().getCode()` |
| `requestPath` | `request.getUri().getPath()` (original request for profile matching) |
| `requestMethod` | `request.getMethod()` |
| `queryString` | `request.getUri().getQuery()` |
| `session` | `SessionContext` from context chain |

#### applyChanges

**Request phase:**
- Body: `request.getEntity().setBytes(transformed.body().content())`
- Headers: diff-based update via `request.getHeaders().put()`/`.remove()`
- URI: `request.getUri().setPath()`, `.setQuery()`
- Method: `request.setMethod(transformed.requestMethod())`

**Response phase:**
- Body: `response.getEntity().setBytes(transformed.body().content())`
- Headers: diff-based update via `response.getHeaders().put()`/`.remove()`
- Status: `response.setStatus(Status.valueOf(transformed.statusCode()))`

### 4.3 Session Context Mapping

PingGateway's `SessionContext` provides a **flat key-value session map** —
much simpler than PingAccess's four-layer merge model.

If OAuth2 context is available (via `OAuth2Context` in the context chain), the
adapter can also extract token metadata (similar to PA's `OAuthTokenMetadata`).

**Proposed merge layers:**

| Layer | Source | Contents | Precedence |
|-------|--------|----------|------------|
| 1 (base) | `SessionContext.getSession()` | All session key-value pairs | Lowest |
| 2 | `AttributesContext.getAttributes()` | Route-level computed attributes | |
| 3 (top) | `OAuth2Context` (if present) | `accessToken`, scopes, expiry, etc. | Highest |

This is simpler than the PA adapter's 4-layer model because PingGateway
doesn't have PA's `Identity` object with its structured getter hierarchy.

---

## 5. Key Differences from PingAccess Adapter

| Dimension | PingAccess | PingGateway |
|-----------|------------|-------------|
| Extension SPI | `AsyncRuleInterceptor` | `Filter` |
| Config model | `@UIElement` + admin UI | JSON route config + `Heaplet` |
| Request/Response | `Exchange` (single object) | Separate `Request` + `Response` via promise |
| Body API | `Body.getContent()/setBodyContent()` | `Entity.getBytes()/setBytes()` |
| Headers | `Headers.asMap()` → `Map<String, String[]>` | `Headers.copyAsMultiMapOfStrings()` → `Map<String, List<String>>` |
| Header case | Case-sensitive map, caller normalizes | Case-insensitive natively |
| Session | `Identity` + `OAuthTokenMetadata` + `SessionStateSupport` | `SessionContext` + `AttributesContext` + `OAuth2Context` |
| Deployment | Shadow JAR in `deploy/`, PA admin registers rule | JAR in `extra/`, JSON route file references filter |
| Admin UI | Yes (PA admin panel) | No admin UI for custom filters (route JSON only, or Studio if available) |
| Error response | `ResponseBuilder` (SDK factory) | Construct `new Response(Status.xxx)` directly |
| Lifecycle | `configure()` / `@PreDestroy` | `Heaplet.create()` / `Heaplet.destroy()` |

---

## 6. Open Questions

All answered inline:

| # | Question | Answer |
|---|----------|--------|
| Q-003-01 | Is the ForgeRock Maven repo publicly accessible? | Needs verification. Fallback: extract JARs from PG distribution. |
| Q-003-02 | What Jackson version does PG 2025.11 bundle? | Must check PG libs directory. Likely 2.17.x (recent release). |
| Q-003-03 | Does PG support Java 21? | Yes — PG 2025.11 requires Java 17+ (like PA 9.0), and Java 21 is a supported target. |
| Q-003-04 | Is `Entity.getBytes()` always safe (no streaming issues)? | `Entity` buffers content after first read. Unlike PA `Body`, no separate `read()` step needed. |
| Q-003-05 | Can the filter inspect the request during response phase? | Yes — the `request` parameter is available in the `thenOnResult()` closure because it's captured from the outer `filter()` scope. |
| Q-003-06 | Does PG need a DENY mechanism like PA? | Yes — error mode should be consistent. For DENY: construct `new Response(Status.BAD_GATEWAY)` with RFC 9457 body, return directly without calling `next.handle()` (request phase) or replace response in callback (response phase). |

---

## 7. Conclusions & Recommendations

### 7.1 Integration Strategy

**Implement as a custom Java `Filter`** deployed as a shadow JAR to
`$HOME/.openig/extra/`. This is the documented, supported, and recommended
extension point for PingGateway.

### 7.2 GatewayAdapter Design

Use a `PingGatewayExchange` wrapper record as the type parameter for
`GatewayAdapter<PingGatewayExchange>`. This cleanly maps to the SPI while
accommodating PingGateway's split request/response model.

### 7.3 Module Structure

Create a new Gradle module: `adapter-pinggateway/`

```
adapter-pinggateway/
├── build.gradle.kts           ← shadow JAR, compileOnly PG deps
└── src/
    ├── main/java/io/messagexform/pinggateway/
    │   ├── MessageTransformFilter.java       ← Filter implementation
    │   ├── PingGatewayAdapter.java           ← GatewayAdapter<PingGatewayExchange>
    │   ├── PingGatewayExchange.java          ← Context+Request+Response wrapper
    │   ├── MessageTransformClassAliasResolver.java  ← short name → FQCN
    │   └── FilterConfig.java                 ← typed config POJO
    ├── main/resources/
    │   └── META-INF/services/
    │       └── org.forgerock.openig.alias.ClassAliasResolver
    └── test/java/io/messagexform/pinggateway/
        ├── PingGatewayAdapterRequestTest.java
        ├── PingGatewayAdapterResponseTest.java
        ├── MessageTransformFilterTest.java
        └── PingGatewayExchangeTest.java
```

### 7.4 Dependency Resolution Strategy

**Phase 1:** Attempt ForgeRock Maven repository for CHF/IG dependencies.
**Phase 2:** If not accessible, download PingGateway 2025.11 distribution and
extract JARs to `libs/pinggateway/` (same pattern as `libs/pingaccess-sdk/`).

### 7.5 Reuse from PingAccess Adapter

The following patterns can be reused directly:
- **Core engine integration** — `TransformEngine`, `SpecParser`, `ProfileLoader`
- **Error mode dispatch** — `PASS_THROUGH` vs `DENY` logic
- **Header diff logic** — same diff-based header application strategy
- **Shadow JAR configuration** — exclude PG-provided classes
- **Reload scheduler** — `ScheduledExecutorService` with daemon thread
- **Body parse fallback** — `MessageBody.empty()` on parse failure

The main new work is:
- `Filter` lifecycle (single method, promise chain)
- `Heaplet`-based configuration
- Context-chain-based session extraction
- `PingGatewayExchange` wrapper
- `ClassAliasResolver` registration
- No admin UI (config is JSON-only)

---

*Status: COMPLETE — PingGateway filter API fully researched and mapped*
