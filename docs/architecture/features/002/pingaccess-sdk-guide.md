# PingAccess SDK Implementation Guide

> Single-source reference for implementing PingAccess 9.0.1 SDK plugins.
> Sourced from the official SDK Javadoc and sample rules shipped with PingAccess.
> This document is optimized for LLM context — each section is self-contained.

| Field | Value |
|-------|-------|
| SDK Version | `com.pingidentity.pingaccess:pingaccess-sdk:9.0.1.0` |
| Java | 17 or 21 (Amazon Corretto, OpenJDK, Oracle JDK — 64-bit) |
| Source | Official SDK Javadoc (`binaries/pingaccess-9.0.1/sdk/apidocs/`) + sample rules |
| Related spec | `docs/architecture/features/002/spec.md` (co-located) |

---

## Topic Index

| # | Section | One-liner | Tags |
|---|---------|-----------|------|
| 1 | [AsyncRuleInterceptor SPI](#1-asyncruleinterceptor-spi) | The plugin interface your adapter implements | `plugin`, `SPI`, `interceptor`, `lifecycle` |
| 2 | [Exchange](#2-exchange) | The request/response envelope passed to every handler | `exchange`, `request`, `response`, `identity` |
| 3 | [Message / Request / Response](#3-message--request--response) | Reading and writing HTTP bodies, headers, status | `message`, `body`, `headers`, `request`, `response` |
| 4 | [Identity & Session Context](#4-identity--session-context) | Authentication context: user, claims, OAuth, session state | `identity`, `session`, `OAuth`, `OIDC`, `claims`, `SessionStateSupport` |
| 5 | [Plugin Configuration & UI](#5-plugin-configuration--ui) | @UIElement, ConfigurationBuilder, Bean Validation | `configuration`, `UIElement`, `admin`, `validation` |
| 6 | [ResponseBuilder & Error Handling](#6-responsebuilder--error-handling) | Building responses, DENY mode, ErrorHandlingCallback | `response`, `error`, `DENY`, `ResponseBuilder`, `NPE` |
| 7 | [Deployment & Classloading](#7-deployment--classloading) | Shadow JAR, Jackson relocation, SPI registration | `shadow`, `JAR`, `Jackson`, `relocation`, `classloader` |
| 8 | [Thread Safety](#8-thread-safety) | Init-time vs per-request state, concurrency model | `thread`, `concurrency`, `Inject`, `mutable` |
| 9 | [Testing Patterns](#9-testing-patterns) | Mockito mock chains, config validation, dependency alignment | `test`, `Mockito`, `mock`, `Validator`, `JUnit` |
| 10 | [Supporting Types](#10-supporting-types) | Enums, factories, utility classes | `Outcome`, `HttpStatus`, `ServiceFactory`, `ExchangeProperty` |

---

## 1. AsyncRuleInterceptor SPI

> **Tags:** `plugin`, `SPI`, `interceptor`, `lifecycle`, `AsyncRuleInterceptorBase`

A `AsyncRuleInterceptor` is the runtime instantiation of a Rule that supports
applying access control logic as an asynchronous computation.
Your adapter extends `AsyncRuleInterceptorBase<T>` which provides sensible defaults.

> **When to use Async vs Sync:** Per the SDK Javadoc, `AsyncRuleInterceptor`
> should only be used when the logic of the Rule requires using a `HttpClient`.
> If no `HttpClient` is needed, use `RuleInterceptor` instead. Our adapter uses
> async because future features may require HTTP callouts.

### Interface: `AsyncRuleInterceptor<T>`

```java
// com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor<T extends PluginConfiguration>
//   extends Interceptor, DescribesUIConfigurable, ConfigurablePlugin<T>
CompletionStage<Outcome> handleRequest(Exchange exchange);
CompletionStage<Void>    handleResponse(Exchange exchange);
ErrorHandlingCallback    getErrorHandlingCallback();
```

### Base class: `AsyncRuleInterceptorBase<T>`

```java
// com.pingidentity.pa.sdk.policy.AsyncRuleInterceptorBase<T extends PluginConfiguration>
//   implements AsyncRuleInterceptor<T>
// Pre-wired fields (set via setter injection):
//   - HttpClient httpClient (via setHttpClient)
//   - TemplateRenderer templateRenderer (via setTemplateRenderer)
//   - T configuration (via configure(T))
void configure(T config) throws ValidationException;
T getConfiguration();
CompletionStage<Void> handleResponse(Exchange exchange);  // default no-op
HttpClient getHttpClient();
TemplateRenderer getTemplateRenderer();
```

### Lifecycle

1. **Instantiation:** PA creates plugin via no-arg constructor.
2. **Injection:** `@Inject` setters called (`setObjectMapper`, `setRenderer`, `setHttpClient`).
3. **Configuration:** `configure(T)` called with admin-provided config.
4. **Runtime:** `handleRequest()` / `handleResponse()` called per request.
5. **Teardown:** No explicit destroy — plugin is garbage-collected on PA restart.

### Naming note

> Sync base class `RuleInterceptorBase` → method is `getRenderer()`.
> Async base class `AsyncRuleInterceptorBase` → method is `getTemplateRenderer()`.
> Always use `getTemplateRenderer()` for async plugins.

---

## 2. Exchange

> **Tags:** `exchange`, `request`, `response`, `identity`, `PolicyConfiguration`

The `Exchange` is the central envelope passed to `handleRequest` and `handleResponse`.
It holds the request, response, identity, and exchange-scoped properties.

```java
// com.pingidentity.pa.sdk.http.Exchange
Request  getRequest();           void setRequest(Request);
Response getResponse();         void setResponse(Response);  // null until site responds
Identity getIdentity();         // null for unauthenticated requests
SslData  getSslData();
String   getOriginalRequestUri();
String   getSourceIp();
String   getUserAgentHost();
String   getUserAgentProtocol();
String   getTargetScheme();
List<?>  getTargetHosts();           // backend target host(s)
Instant  getCreationTime();
Locale   getResolvedLocale();
PolicyConfiguration getPolicyConfiguration();   // application + resource metadata
<T> Optional<T> getProperty(ExchangeProperty<T>);
<T> void setProperty(ExchangeProperty<T>, T);
```

### Critical: `getResponse()` is null during request phase

During `handleRequest()`, the backend has not yet responded, so
`exchange.getResponse()` returns **`null`**. Any attempt to call methods on it
will throw `NullPointerException`. See [§6 ResponseBuilder](#6-responsebuilder--error-handling)
for how to handle this.

### PolicyConfiguration sub-interface

```java
// com.pingidentity.pa.sdk.policy.PolicyConfiguration
Application getApplication();   // PA application metadata
Resource    getResource();      // PA resource (endpoint) metadata

// Application methods:
//   String getName(); int getId(); ...
// Resource methods:
//   String getName(); int getId(); String getPathPattern(); ...
```

> Useful for context-aware transforms (e.g., different behavior per application).
> Not exposed in v1 `$session` but available for future `$context` enrichment.

---

## 3. Message / Request / Response

> **Tags:** `message`, `body`, `headers`, `request`, `response`, `Body`, `Headers`

### Message (parent of Request and Response)

```java
// com.pingidentity.pa.sdk.http.Message
Body    getBody();                 void setBody(Body);
void    setBodyContent(byte[]);    // replaces body + auto-updates Content-Length
Headers getHeaders();              void setHeaders(Headers);
String  getStartLine();            // HTTP start line
String  getVersion();              // "1.0" or "1.1"
boolean isKeepAlive();
```

> **⚠️ `setBody()` vs `setBodyContent()`:** Per the Javadoc, `setBody()` does
> **NOT** update the `Content-Length` header. Use `setBodyContent(byte[])` instead
> — it updates `Content-Length` automatically. The provided byte array is copied.

### Request

```java
// com.pingidentity.pa.sdk.http.Request extends Message
Method  getMethod();              void setMethod(Method);
URI     getUri();                 void setUri(URI);
```

### Response

```java
// com.pingidentity.pa.sdk.http.Response extends Message
int       getStatusCode();
HttpStatus getStatus();           void setStatus(HttpStatus);
```

### Body

```java
// com.pingidentity.pa.sdk.http.Body
byte[]       getContent();     // reads full body into memory
InputStream  getInputStream(); // streaming access
boolean      isRead();         // true if content was already consumed
boolean      isInMemory();     // true if body is buffered in memory
long         getSize();
```

> **Constraint:** `Body.isInMemory()` may return `false` for large/streamed
> response bodies. `body.getContent()` reads the entire body into memory.
> The adapter does not impose additional limits beyond the core engine's
> `maxOutputSizeBytes`.

### Headers

```java
// com.pingidentity.pa.sdk.http.Headers
Map<String, String[]> asMap();   // all headers (name → values[])
String   getValue(String name);  // first value
String[] getValues(String name); // all values
void     setValues(String name, List<String> values);
void     add(String name, String value);
boolean  removeFields(String name);
Map<String, String[]> getCookies();
long     getContentLength();     void setContentLength(long);
MediaType getContentType();      void setContentType(String);
```

---

## 4. Identity & Session Context

> **Tags:** `identity`, `session`, `OAuth`, `OIDC`, `claims`, `SessionStateSupport`, `OAuthTokenMetadata`, `JsonNode`

### Identity

An `Identity` object contains attributes and associated values derived from an
authentication token issued by a token provider. The actual token can be a PA
JWT cookie or an OAuth token.

```java
// com.pingidentity.pa.sdk.identity.Identity
String  getSubject();             // authenticated user principal
String  getMappedSubject();       // subject set by IdentityMappingPlugin (may be null/empty)
void    setMappedSubject(String); // used by IdentityMapping — adapter should NOT call this
String  getTrackingId();          // PA tracking ID (for logging only, NOT globally unique)
String  getTokenId();             // unique ID presented in the authentication token
Instant getTokenExpiration();     // token expiry (java.time.Instant, NOT Date)
JsonNode getAttributes();        // JSON identity attributes (OIDC/token claims)
SessionStateSupport getSessionStateSupport();  // persistent session key-value store
OAuthTokenMetadata  getOAuthTokenMetadata();   // null if OAuth is not used
```

> **`exchange.getIdentity()` may return `null`** for unauthenticated/anonymous
> resources. Always null-check before accessing identity fields.
>
> **`getAttributes()` returns an immutable snapshot:** Modifications to the
> returned `JsonNode` will NOT update the identity's attributes.

### SessionStateSupport

Provides access to persistent data across exchanges. Session state data is stored
in a cookie. Requires a web application with a web session configured.

```java
// com.pingidentity.pa.sdk.identity.SessionStateSupport
Map<String, JsonNode> getAttributes();         // unmodifiable map (snapshot)
Set<String>           getAttributeNames();     // unmodifiable set (snapshot)
JsonNode              getAttributeValue(String name);  // single attribute or null
void                  setAttribute(String name, JsonNode value);
void                  removeAttribute(String name);
```

> **Caveats from SDK Javadoc:**
> - `getAttributes()` and `getAttributeNames()` return **unmodifiable snapshots**.
>   Later `setAttribute`/`removeAttribute` calls are NOT reflected in already-returned maps.
> - Server-side attribute caching may cause cache entries to be deleted if cache
>   limits are exceeded. Implementers should be prepared to re-acquire data.
> - In a cluster, requests to other nodes may result in a **cache miss**.
> - API-configured applications may **not support** persistent session state.
>
> **Adapter usage:** The adapter reads session attributes as read-only context
> for JSLT transforms. Writing to session state is out of scope for v1.

### OAuthTokenMetadata

The `OAuthTokenMetadata` provides access to additional details about an OAuth
token apart from the attributes on `Identity`.

```java
// com.pingidentity.pa.sdk.identity.OAuthTokenMetadata
String      getClientId();     // client_id of the OAuth client (null if not present)
Set<String> getScopes();       // granted OAuth scopes (empty if not present)
String      getTokenType();    // e.g., "Bearer"
String      getRealm();        // OAuth realm associated with the application (may be null)
Instant     getExpiresAt();    // calculated expiration from expires_in (null if not present)
Instant     getRetrievedAt();  // when PingAccess received the access token response
```

> **Null handling:** `getOAuthTokenMetadata()` returns `null` if OAuth is not
> used for authentication. Individual fields may also be null.

### Building $session — Flat Merge Pattern

The adapter builds a flat `$session` JsonNode by merging four layers. Later
layers override earlier layers on key collision:

| Layer | Source | Contents | Precedence |
|-------|--------|----------|------------|
| 1 (base) | `Identity` getters | `subject`, `mappedSubject`, `trackingId`, `tokenId`, `tokenExpiration` | Lowest |
| 2 | `OAuthTokenMetadata` | `clientId`, `scopes`, `tokenType`, `realm`, `tokenExpiresAt`, `tokenRetrievedAt` | |
| 3 | `Identity.getAttributes()` | OIDC claims / token introspection (spread into flat object) | |
| 4 (top) | `SessionStateSupport.getAttributes()` | Dynamic session state (spread into flat object) | Highest |

**Why this precedence?** Session state (L4) is the most dynamic — rules can
write to it during the session. OIDC claims (L3) override PA identity fields
(L1) because claims are the authoritative token data.

```java
// Build flat $session JsonNode from Exchange.getIdentity()
private JsonNode buildSessionContext(Identity identity) {
    if (identity == null) return null;

    ObjectNode session = objectMapper.createObjectNode();

    // Layer 1: PA identity fields (base)
    session.put("subject", identity.getSubject());
    session.put("mappedSubject", identity.getMappedSubject());
    session.put("trackingId", identity.getTrackingId());
    session.put("tokenId", identity.getTokenId());
    if (identity.getTokenExpiration() != null) {
        session.put("tokenExpiration", identity.getTokenExpiration().toString());
    }

    // Layer 2: OAuth metadata
    OAuthTokenMetadata oauth = identity.getOAuthTokenMetadata();
    if (oauth != null) {
        session.put("clientId", oauth.getClientId());
        session.put("tokenType", oauth.getTokenType());
        session.put("realm", oauth.getRealm());
        if (oauth.getExpiresAt() != null) {
            session.put("tokenExpiresAt", oauth.getExpiresAt().toString());
        }
        if (oauth.getRetrievedAt() != null) {
            session.put("tokenRetrievedAt", oauth.getRetrievedAt().toString());
        }
        ArrayNode scopesNode = objectMapper.createArrayNode();
        if (oauth.getScopes() != null) {
            oauth.getScopes().forEach(scopesNode::add);
        }
        session.set("scopes", scopesNode);
    }

    // Layer 3: OIDC claims / token attributes (SPREAD into flat object)
    JsonNode attributes = identity.getAttributes();
    if (attributes != null && attributes.isObject()) {
        attributes.fields().forEachRemaining(entry ->
            session.set(entry.getKey(), entry.getValue())  // overrides layers 1-2 on collision
        );
    }

    // Layer 4: Session state (SPREAD into flat object — highest precedence)
    SessionStateSupport sss = identity.getSessionStateSupport();
    if (sss != null && sss.getAttributes() != null) {
        sss.getAttributes().forEach((key, value) ->
            session.set(key, value)  // overrides all previous layers on collision
        );
    }

    return session;
}
```

### Boundary Conversion (Jackson relocation)

When Jackson is relocated in the shadow JAR (which is mandatory — see §7),
`Identity.getAttributes()` returns a PA-classloader `JsonNode` while the adapter
uses a shaded `JsonNode`. These are different classes. Convert at the boundary:

```java
// Boundary conversion: PA JsonNode → shaded JsonNode
byte[] raw = paObjectMapper.writeValueAsBytes(identity.getAttributes());
JsonNode shadedNode = ourObjectMapper.readTree(raw);
```

This conversion is done once per request in `buildSessionContext()` and is
negligible in cost (<1ms for typical OIDC claim payloads).

---

## 5. Plugin Configuration & UI

> **Tags:** `configuration`, `UIElement`, `admin`, `validation`, `ConfigurationBuilder`, `ConfigurationType`

### @Rule Annotation

```java
@Rule(
    category = RuleInterceptorCategory.Processing,
    destination = { RuleInterceptorSupportedDestination.Site },
    label = "Message Transform",
    type = "MessageTransform",   // unique across all plugins
    expectedConfiguration = MessageTransformConfig.class
)
```

### ConfigurationType Enum (SDK 9.0.1)

```java
// Available values:
TEXT, TEXTAREA, TIME, SELECT, GROOVY, CONCEALED, LIST, TABLE,
CHECKBOX, AUTOCOMPLETEOPEN
```

> **No** `AUTOCOMPLETECLOSED`, `COMPOSITE`, or `RADIO_BUTTON` in the enum.
> Radio button pattern: use two `boolean` fields (`@UIElement(type=CHECKBOX)`).

### Configuration Field Discovery Patterns

**Pattern 1 — Annotation-driven (recommended):**

```java
// Used by Clobber404ResponseRule, RiskAuthorizationRule
public List<ConfigurationField> getConfigurationFields() {
    return ConfigurationBuilder.from(Configuration.class)  // auto-discovers @UIElement
            .addAll(ErrorHandlerUtil.getConfigurationFields())  // appends error handler fields
            .toConfigurationFields();
}
```

**Pattern 2 — Programmatic (ParameterRule sample):**

```java
public List<ConfigurationField> getConfigurationFields() {
    return new ConfigurationBuilder()
        .configurationField("paramType", "Param Type", ConfigurationType.SELECT)
            .options("Query", "Header", "Cookie")
            .required(true)
            .done()
        .configurationField("paramTable", "Parameters", ConfigurationType.TABLE)
            .subFields()
                .configurationField("paramName", "Name", ConfigurationType.TEXT).required(true).done()
                .configurationField("paramValue", "Value", ConfigurationType.TEXT).required(true).done()
            .done()
            .done()
        .toConfigurationFields();
}
```

### Advanced @UIElement Attributes

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `modelAccessor` | Dynamic dropdown populated by accessor classes | `ThirdPartyServiceAccessor.class`, `KeyPairAccessor.class` |
| `helpContent` | Inline help text shown in admin UI | `"Path to the spec directory on the file system"` |
| `helpTitle` | Help popup title | `"Specs Directory"` |
| `helpURL` | External help link | `"https://docs.example.com/specs"` |
| `defaultValue` | Default value shown in admin UI | `"general.error.page.template.html"` |
| `order` | Field ordering in admin UI (0-based) | `order = 10` |

### Validation Constraints (JSR-380 / Jakarta Bean Validation)

```java
public class AdvancedConfiguration implements PluginConfiguration {
    @UIElement(order = 10, type = ConfigurationType.TEXT, label = "Port")
    @Min(1) @Max(65535)
    private int port;

    @UIElement(order = 20, type = ConfigurationType.TEXT, label = "Hostname")
    @NotNull
    private String hostname;

    @JsonUnwrapped                 // flattens error handler fields into config JSON
    @Valid                         // enables nested validation
    private ErrorHandlerConfigurationImpl errorHandlerPolicyConfig;
}
```

---

## 6. ResponseBuilder & Error Handling

> **Tags:** `response`, `error`, `DENY`, `ResponseBuilder`, `NPE`, `AccessException`, `ErrorHandlingCallback`

### ResponseBuilder API

Builder used for creating `Response` objects. Each `build()` invocation creates
a new `Response` instance.

```java
// com.pingidentity.pa.sdk.http.ResponseBuilder — static factories
static ResponseBuilder newInstance(HttpStatus status);   // arbitrary status
static ResponseBuilder ok();                             // 200 OK
static ResponseBuilder ok(String msg);                   // 200 + text/html body
static ResponseBuilder badRequest();                     // 400
static ResponseBuilder badRequestJson(String json);      // 400 + application/json body
static ResponseBuilder unauthorized();                   // 401
static ResponseBuilder forbidden();                      // 403
static ResponseBuilder notFound();                       // 404 + text/html
static ResponseBuilder notFoundJson();                   // 404 + application/json
static ResponseBuilder unprocessableEntity();            // 422
static ResponseBuilder internalServerError();            // 500
static ResponseBuilder serviceUnavailable();             // 503
static ResponseBuilder found(String url);                // 302 redirect

// Builder methods
ResponseBuilder header(String name, String value);
ResponseBuilder header(HeaderField headerField);
ResponseBuilder headers(Headers headers);         // copies, does not store reference
ResponseBuilder contentType(String type);
ResponseBuilder contentType(MediaType type);
ResponseBuilder body(byte[] body);                // copies array, updates Content-Length
ResponseBuilder body(String msg);                 // UTF-8 encoding, updates Content-Length
ResponseBuilder body(String msg, Charset charset);
Response build();                                 // creates new Response instance
```

> **Note:** The `body(byte[])` and `headers(Headers)` methods copy the provided
> objects — they do not store references. The `headers()` method throws
> `IllegalArgumentException` if the `Headers` object was not created by the
> provided `HeadersFactory` (see `ServiceFactory.headersFactory()`).

### ⚠️ CRITICAL: DENY Mode in handleRequest — Response is NULL

During `handleRequest()`, `exchange.getResponse()` is **`null`** because the
backend has not yet responded. You MUST use `ResponseBuilder` to construct a new
`Response` and set it on the exchange:

```java
// DENY mode — handleRequest error path
byte[] errorBody = result.errorResponse().getBytes(StandardCharsets.UTF_8);
Response errorResponse = ResponseBuilder.status(result.errorStatusCode())
    .header("Content-Type", "application/problem+json")
    .body(errorBody)
    .build();
exchange.setResponse(errorResponse);
return CompletableFuture.completedFuture(Outcome.RETURN);
```

**Previous incorrect design (GAP-09):** The spec previously described calling
`exchange.getResponse().setBodyContent()` during request phase. This would cause
a `NullPointerException` because the response object does not exist.

**Alternative considered:** Throw `AccessException` and delegate to
`ErrorHandlingCallback`. Rejected because the callback writes a PA-formatted
HTML error page, not our RFC 9457 JSON format.

### DENY Mode in handleResponse — Response EXISTS

During `handleResponse()`, the response object already exists. Rewrite in-place:

```java
// DENY mode — handleResponse error path
byte[] errorBody = result.errorResponse().getBytes(StandardCharsets.UTF_8);
exchange.getResponse().setBodyContent(errorBody);
exchange.getResponse().setStatus(HttpStatus.forCode(result.errorStatusCode()));
exchange.getResponse().getHeaders().setContentType("application/problem+json");
```

### SDK References

- `Clobber404ResponseRule.handleResponse()` uses `ResponseBuilder.notFound()` +
  `getRenderer().renderResponse(exchange, ...)` to construct a new response.
- `RiskAuthorizationRule.handleRequest()` uses `Outcome.RETURN` with
  `POLICY_ERROR_INFO` to delegate to ErrorHandlingCallback.
- `WriteResponseRule` throws `AccessException` to trigger
  `getErrorHandlingCallback().writeErrorResponse(exchange)`.

---

## 7. Deployment & Classloading

> **Tags:** `shadow`, `JAR`, `Jackson`, `relocation`, `classloader`, `SPI`, `SLF4J`, `ServiceLoader`

### Shadow JAR Contents

The adapter shadow JAR must contain:
- Adapter classes (`io.messagexform.pingaccess.*`)
- Core engine (`io.messagexform.core.*`)
- `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`
- All runtime dependencies (Jackson, JSLT, SnakeYAML, JSON Schema Validator)

**Excluded** (provided by PA runtime):
- PingAccess SDK classes
- SLF4J API (PA ships its own SLF4J provider — bundling a second causes classpath conflict)

### Jackson Relocation (MANDATORY)

PingAccess uses Jackson internally (`Identity.getAttributes()` returns `JsonNode`,
`SessionStateSupport.setAttribute()` takes `JsonNode`). If the adapter bundles an
un-relocated Jackson version:

1. PA's `Identity.getAttributes()` returns a `JsonNode` from PA's classloader
2. The adapter's code expects a `JsonNode` from its own bundled Jackson
3. These are **different classes** from different classloaders
4. **`ClassCastException` at runtime** — silent deployment failure that only
   manifests when processing requests with identity context

**Solution:** Use Gradle Shadow's `relocate` to shade Jackson into a private
package (e.g., `io.messagexform.shaded.jackson`).

See [§4 Boundary Conversion](#boundary-conversion-jackson-relocation) for the
serialization-based conversion pattern at the PA/adapter boundary.

### SPI Registration

```
META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor
```

Contains the FQCN of the plugin class. PingAccess discovers it via `ServiceLoader`.

### Deployment Path

Drop the shadow JAR into `/opt/server/deploy/` on the PA server. On restart, PA
scans this directory and loads all discovered plugins.

---

## 8. Thread Safety

> **Tags:** `thread`, `concurrency`, `Inject`, `mutable`, `instance`, `shared`

Plugin instances may be shared across threads (PA uses a thread pool for request
processing). The adapter MUST distinguish between two categories of state:

### Init-time state (safe for concurrent reads)

These fields are set once during plugin initialization and never modified during
request processing:

- `@Inject` fields: `ObjectMapper`, `TemplateRenderer`, `HttpClient`
- Configuration field set by `configure(T)`
- Any field populated in the constructor or `configure()` method

These are safe for concurrent access because they are effectively immutable
after initialization.

### Per-request state (MUST be method-local)

Any state derived from a specific request (parsed bodies, transform results,
error responses) MUST be local to the `handleRequest()` / `handleResponse()`
method. Do NOT store per-request state in instance fields.

### Evidence from SDK Samples

The `ExternalAuthorizationRule` sample uses instance field `objectMapper`
(injected via setter) across threads — confirming that PA treats injected
fields as thread-safe. It stores per-request authorization results in
`SessionStateSupport` (not instance fields).

---

## 9. Testing Patterns

> **Tags:** `test`, `Mockito`, `mock`, `Validator`, `JUnit`, `Exchange`, `config`

### Mockito Mock Chain (from `ExternalAuthorizationRuleTest`)

The standard pattern for mocking the PA SDK object graph:

```java
// Create mocks
Exchange exchange = mock(Exchange.class);
Request request = mock(Request.class);
Body body = mock(Body.class);
Headers headers = mock(Headers.class);
Identity identity = mock(Identity.class);
SessionStateSupport sss = mock(SessionStateSupport.class);
OAuthTokenMetadata oauthMeta = mock(OAuthTokenMetadata.class);

// Wire mock chain
when(exchange.getRequest()).thenReturn(request);
when(exchange.getResponse()).thenReturn(null); // request phase: no response yet
when(exchange.getIdentity()).thenReturn(identity);
when(request.getBody()).thenReturn(body);
when(request.getHeaders()).thenReturn(headers);
when(body.getContent()).thenReturn("{\"key\":\"value\"}".getBytes(UTF_8));
when(body.isRead()).thenReturn(true);

// Identity chain
when(identity.getSubject()).thenReturn("joe");
when(identity.getAttributes()).thenReturn(objectMapper.readTree("{\"sub\":\"joe\"}"));
when(identity.getSessionStateSupport()).thenReturn(sss);
when(identity.getOAuthTokenMetadata()).thenReturn(oauthMeta);

// Session state
when(sss.getAttributes()).thenReturn(Map.of(
    "authzCache", objectMapper.readTree("{\"decision\":\"PERMIT\"}")
));

// OAuth metadata
when(oauthMeta.getClientId()).thenReturn("my-client");
when(oauthMeta.getScopes()).thenReturn(Set.of("openid", "profile"));
```

### DENY Mode Request-Phase Verification

```java
// Verify ResponseBuilder was used correctly (ArgumentCaptor pattern)
ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
verify(exchange).setResponse(responseCaptor.capture());
Response errorResponse = responseCaptor.getValue();
assertThat(errorResponse.getStatusCode()).isEqualTo(502);
```

### Configuration Validation (Standalone Validator)

Use standalone Jakarta Bean Validation — no Spring context needed:

```java
// In @BeforeAll or test setup
ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
Validator validator = factory.getValidator();

// Test config validation
MessageTransformConfig config = new MessageTransformConfig();
config.setSpecsDir("");  // invalid
Set<ConstraintViolation<MessageTransformConfig>> violations = validator.validate(config);
assertThat(violations).isNotEmpty();
```

### Test Dependency Alignment

| Dependency | SDK Sample Version | Our Project | Notes |
|------------|-------------|-------------|-------|
| JUnit | 4.13.2 | 5 (Jupiter) | Intentional — our project uses JUnit 5 |
| Mockito | 5.15.2 | 5.x (inherited) | Compatible — verify alignment in `build.gradle.kts` |
| Hibernate Validator | 7.0.5.Final | (add as testImplementation) | Needed for config validation tests |
| Spring Test | 6.2.11 | (not used) | We use standalone `Validator` instead |

---

## 10. Supporting Types

> **Tags:** `Outcome`, `HttpStatus`, `Method`, `ExchangeProperty`, `ResponseBuilder`, `ServiceFactory`, `HeaderField`

```java
// Outcome (enum): CONTINUE | RETURN
//   CONTINUE — let request proceed to backend (or response to client)
//   RETURN — halt pipeline, send current response (used with DENY mode)

// Method: GET, POST, PUT, DELETE, PATCH, ... (static constants)

// HttpStatus: OK, BAD_REQUEST, INTERNAL_SERVER_ERROR, ... + forCode(int)

// ExchangeProperty<T>: namespaced typed property for cross-rule state
//   Example: ExchangeProperty<TransformResult> TRANSFORM_RESULT = ...

// HeaderField: (HeaderName name, String value) — header entry tuple

// HeaderName: case-insensitive name wrapper

// ResponseBuilder: factory for Response objects (see §6)

// AccessException: thrown to abort pipeline (triggers ErrorHandlingCallback)

// ServiceFactory: SPI discovery utility
//   bodyFactory() → creates Body instances
//   headersFactory() → creates Headers instances
//   configurationModelAccessorFactory() → creates accessors for config model
//   getSingleImpl(Class<T>) / getImplInstances(Class<T>) — SPI discovery
//   Useful in tests for creating mock-compatible Body/Headers objects.
```

---

## Java Version Compatibility

PingAccess 9.0 officially supports **Java 17 and 21** (Amazon Corretto, OpenJDK,
Oracle JDK — all 64-bit). The adapter module compiles with **Java 21**, matching
the core module. No cross-compilation needed.

> Source: PingAccess 9.0 docs § "Java runtime environments" (page 78)

---

## SDK Artefact Locations

| Artefact | Path |
|----------|------|
| SDK JAR (binary) | `binaries/pingaccess-9.0.1/sdk/` |
| SDK Javadoc (HTML) | `binaries/pingaccess-9.0.1/sdk/apidocs/` |
| SDK sample rules | `binaries/pingaccess-9.0.1/sdk/samples/` |
| Local SDK JAR copy | `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` |
