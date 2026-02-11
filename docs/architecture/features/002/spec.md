# Feature 002 – PingAccess Adapter

| Field | Value |
|-------|-------|
| Status | Spec Ready |
| Last updated | 2026-02-11 |
| Owners | Ivan |
| Linked plan | `docs/architecture/features/002/plan.md` |
| Linked tasks | `docs/architecture/features/002/tasks.md` |
| Roadmap entry | #2 – PingAccess Adapter |
| Depends on | Feature 001 (core engine) |

> Guardrail: This specification is the single normative source of truth for the feature.
> Track questions in `docs/architecture/open-questions.md`, encode resolved answers
> here, and use ADRs under `docs/decisions/` for architectural clarifications.

## Overview

Gateway adapter that integrates message-xform with **PingAccess 9.0** via the
Java Add-on SDK. Implements the `AsyncRuleInterceptor` SPI to intercept
request/response flows through PingAccess Sites and apply JSLT-based message
transformations using the core engine.

The adapter is a **thin bridge layer** — all transformation logic lives in
`message-xform-core`. The adapter's sole responsibility is:

1. Reading native `Exchange` data (body, headers, status, URL, identity).
2. Wrapping it into a core `Message` via `GatewayAdapter` SPI.
3. Delegating transformation to `TransformEngine`.
4. Writing transformed results back to the `Exchange`.

**Affected modules:** New module `adapter-pingaccess`.

## Key Research

- PingAccess Plugin API: `docs/research/pingaccess-plugin-api.md` (COMPLETE)
- PingAccess Docker & SDK: `docs/research/pingaccess-docker-and-sdk.md` (COMPLETE)
- PingAM Authentication API: `docs/research/pingam-authentication-api.md` (COMPLETE)
- SDK JAR (decompiled): `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` (166 classes)
- PingAccess 9.0 reference: `docs/reference/pingaccess-9.0.pdf`

## Goals

- G-002-01 – Implement `GatewayAdapter<Exchange>` for PingAccess, bridging the
  PA SDK's `Exchange`/`Request`/`Response`/`Body`/`Headers` model to the core
  engine's `Message` record.
- G-002-02 – Use `AsyncRuleInterceptorBase<T>` for non-blocking request/response
  processing (SDK-recommended pattern, supports `CompletionStage<Outcome>`).
- G-002-03 – Provide a PingAccess admin UI configuration panel via `@UIElement`
  annotations for transform spec directory, error mode, and profile selection.
- G-002-04 – Package as a single deployable JAR for PingAccess's `deploy/`
  directory with correct `META-INF/services` SPI registration.
- G-002-05 – Support Docker-based E2E testing against a real PingAccess 9.0 instance.

## Non-Goals

- N-002-01 – Agent deployment support. PingAccess Agent rules do **not** support
  request body access or response handling (confirmed in SDK docs). This adapter
  targets **Site (gateway) deployment only**.
- N-002-02 – Groovy script rules. The adapter is a compiled Java plugin —
  Groovy scripting is an alternative approach not covered here.
- N-002-03 – Async external HTTP calls during transformation. The adapter uses
  `AsyncRuleInterceptorBase` for the non-blocking lifecycle, but the transformation
  itself (JSLT evaluation) is CPU-bound and runs synchronously within the async
  completion stage. External HTTP calls (e.g., calling PingAM directly) are out of
  scope.
- N-002-04 – PingAccess clustering or HA configuration. The adapter is a
  stateless plugin — it works identically in standalone and clustered PA
  deployments.

---

## SDK API Surface

> This section captures the decompiled SDK API surface for traceability.
> Source: `pingaccess-sdk-9.0.1.0.jar` (166 classes), decompiled via `javap`.

### AsyncRuleInterceptor (chosen SPI)

```java
// com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor<T extends PluginConfiguration>
//   extends Interceptor, DescribesUIConfigurable, ConfigurablePlugin<T>
CompletionStage<Outcome> handleRequest(Exchange exchange);
CompletionStage<Void>    handleResponse(Exchange exchange);
ErrorHandlingCallback    getErrorHandlingCallback();
```

### AsyncRuleInterceptorBase (provided base class)

```java
// com.pingidentity.pa.sdk.policy.AsyncRuleInterceptorBase<T extends PluginConfiguration>
//   implements AsyncRuleInterceptor<T>
// Pre-wired fields:
//   - HttpClient httpClient (via @Inject)
//   - TemplateRenderer templateRenderer (via @Inject)
//   - T configuration (via configure(T))
void configure(T config) throws ValidationException;
T getConfiguration();
CompletionStage<Void> handleResponse(Exchange exchange);  // default no-op
HttpClient getHttpClient();
TemplateRenderer getTemplateRenderer();
```

### Exchange (request/response envelope)

```java
// com.pingidentity.pa.sdk.http.Exchange
Request getRequest();           void setRequest(Request);
Response getResponse();         void setResponse(Response);  // null until site responds
Identity getIdentity();
SslData getSslData();
String getOriginalRequestUri();
String getSourceIp();
String getUserAgentHost();
String getUserAgentProtocol();
String getTargetScheme();
Instant getCreationTime();
<T> Optional<T> getProperty(ExchangeProperty<T>);
<T> void setProperty(ExchangeProperty<T>, T);
```

### Message (parent of Request and Response)

```java
// com.pingidentity.pa.sdk.http.Message
Body getBody();                 void setBody(Body);
void setBodyContent(byte[]);    // replaces body + auto-updates Content-Length
Headers getHeaders();           void setHeaders(Headers);
String getStartLine();
String getVersion();
boolean isKeepAlive();
```

### Request extends Message

```java
Method getMethod();              void setMethod(Method);
String getUri();                 void setUri(String);
Map<String, String[]> getQueryStringParams() throws URISyntaxException;
```

### Response extends Message

```java
HttpStatus getStatus();          void setStatus(HttpStatus);
int getStatusCode();             // default → getStatus().getCode()
String getStatusMessage();       // default → getStatus().getMessage()
```

### Body

```java
byte[] getContent();             // read body as byte[]
InputStream newInputStream();
int getLength();
boolean isRead();
void read() throws AccessException, IOException;  // force-read deferred body
Map<String, String[]> parseFormParams();
```

### Headers

```java
List<HeaderField> getAllHeaderFields();
List<HeaderField> getFields(String name);
Optional<String> getFirstValue(String name);
List<String> getValues(String name);
void setValues(String name, List<String> values);
void add(String name, String value);
boolean removeFields(String name);
Map<String, String[]> getCookies();
long getContentLength();         void setContentLength(long);
MediaType getContentType();      void setContentType(String);
```

### Identity

```java
String getSubject();             // authenticated user principal
String getMappedSubject();       // mapped identity (set by IdentityMapping plugins)
void   setMappedSubject(String); // used by IdentityMapping — adapter should NOT call this
String getTrackingId();          // PA tracking ID
String getTokenId();             // OAuth token ID
Date   getTokenExpiration();
JsonNode getAttributes();        // identity attributes as Jackson JsonNode (OIDC/token claims)
SessionStateSupport getSessionStateSupport();  // persistent session key-value store
OAuthTokenMetadata  getOAuthTokenMetadata();   // OAuth token metadata
```

### SessionStateSupport

> Provides persistent key-value session storage. Attributes survive across
> requests within the same PingAccess session. Used by `ExternalAuthorizationRule`
> sample to cache authorization decisions.

```java
// com.pingidentity.pa.sdk.identity.SessionStateSupport
Map<String, JsonNode> getAttributes();            // all session attributes
JsonNode getAttribute(String name);               // single attribute by name
JsonNode getAttributeValue(String name);           // alias for getAttribute
void     setAttribute(String name, JsonNode value); // store/update a session attribute
void     removeAttribute(String name);             // remove a session attribute
```

> **Adapter usage:** The adapter reads session attributes as context for JSLT
> transforms (§ FR-002-06). Writing to session state is out of scope for v1 —
> transforms are pure read-only operations on identity context.

### OAuthTokenMetadata

```java
// com.pingidentity.pa.sdk.identity.OAuthTokenMetadata
String      getClientId();   // OAuth client that obtained the token
Set<String> getScopes();     // granted OAuth scopes
String      getTokenType();  // e.g., "Bearer"
String      getRealm();      // OAuth realm
```

### Supporting Types

```java
// Outcome (enum): CONTINUE | RETURN
// Method: GET, POST, PUT, DELETE, PATCH, ... (static constants)
// HttpStatus: OK, BAD_REQUEST, INTERNAL_SERVER_ERROR, ... + forCode(int)
// ExchangeProperty<T>: namespaced typed property for cross-rule state
// HeaderField: (HeaderName name, String value)
// HeaderName: case-insensitive name wrapper
// ResponseBuilder: factory for constructing Response objects (see § Error Handling)
```

### Plugin Configuration & UI

```java
// @Rule annotation (on the plugin class)
@Rule(
    category = RuleInterceptorCategory.Processing,
    destination = { RuleInterceptorSupportedDestination.Site },
    label = "...",
    type = "...",  // unique across all plugins
    expectedConfiguration = MyConfig.class
)

// PluginConfiguration / SimplePluginConfiguration
interface PluginConfiguration { String getName(); void setName(String); }
class SimplePluginConfiguration implements PluginConfiguration { ... }

// @UIElement (on configuration fields)
@UIElement(order = N, type = ConfigurationType.TEXT|TEXTAREA|SELECT|CHECKBOX|...,
           label = "...", required = true|false, defaultValue = "...")

// ConfigurationType enum values:
//   TEXT, TEXTAREA, TIME, SELECT, GROOVY, CONCEALED, LIST, TABLE,
//   CHECKBOX, AUTOCOMPLETEOPEN, AUTOCOMPLETECLOSED, COMPOSITE, RADIO_BUTTON
```

### Java Version Compatibility

PingAccess 9.0 officially supports **Java 17 and 21** (Amazon Corretto, OpenJDK,
Oracle JDK — all 64-bit). The adapter module compiles with **Java 21**, matching
the core module. No cross-compilation needed.

> Source: PingAccess 9.0 docs § "Java runtime environments" (page 78)

---

## Functional Requirements

### FR-002-01: GatewayAdapter Implementation

**Requirement:** The module MUST provide a class `PingAccessAdapter` implementing
`GatewayAdapter<Exchange>` with three methods:

1. `wrapRequest(Exchange)` → `Message`
2. `wrapResponse(Exchange)` → `Message`
3. `applyChanges(Message, Exchange)`

All wrap methods create **deep copies** of the native data (ADR-0013).

| Aspect | Detail |
|--------|--------|
| Success path | `wrapRequest` reads `Exchange.getRequest()` body/headers/URL and returns a populated `Message` record |
| Validation path | Unit tests with mock `Exchange` objects verify all 9 `Message` fields are populated |
| Failure path | Null body → `NullNode`, null headers → empty map, malformed JSON → `NullNode` + log warning |
| Status | ⬜ Not yet implemented |
| Source | G-002-01, SPI-001-04/05/06 |

#### JSON Parse Failure Strategy

Unlike the `StandaloneAdapter` (which throws `IllegalArgumentException` on bad
JSON and provides separate `wrapRequestRaw()`/`wrapResponseRaw()` methods), the
PingAccess adapter handles parse failures **internally** in `wrapRequest()` and
`wrapResponse()`:

1. Attempt to parse `Body.getContent()` as JSON via `ObjectMapper.readTree()`.
2. On parse failure (malformed JSON, non-JSON content type): **return `NullNode`
   as the body** and log a warning. Do NOT throw.
3. The returned `Message` has all other fields (headers, path, method, etc.)
   populated normally.

**Rationale:** In PingAccess, the adapter does not control the top-level
orchestration flow (unlike `ProxyHandler` in standalone mode). The
`MessageTransformRule` receives the `Message` from `wrapRequest()` and passes
it directly to the engine. Throwing would require the rule to catch and
reconstruct — swallowing inside `wrapRequest()` is cleaner.

**Impact on profile matching:** A `NullNode` body still allows profile matching
on path/method/headers. If a profile matches and attempts a body transformation,
the JSLT expression receives `null` as input — this is handled gracefully by
JSLT (returns `null` for property access on null).

#### wrapRequest Mapping

| Message Field | Source |
|---------------|--------|
| `body` | `exchange.getRequest().getBody().getContent()` → parse as `JsonNode` (or `NullNode` if empty/non-JSON) |
| `headers` | `exchange.getRequest().getHeaders().getAllHeaderFields()` → single-value map (first value per name, lowercase keys) |
| `headersAll` | Same source → multi-value map (all values per name, lowercase keys) |
| `statusCode` | `null` (requests have no status code, per ADR-0020) |
| `contentType` | `exchange.getRequest().getHeaders().getContentType()` → `MediaType.toString()` |
| `requestPath` | `exchange.getRequest().getUri()` (path portion only, strip query string) — see URI choice note below |
| `requestMethod` | `exchange.getRequest().getMethod().getName()` → uppercase string |
| `queryString` | `exchange.getRequest().getUri()` (query portion after `?`, or null) |
| `sessionContext` | See FR-002-06 (identity/session binding) |

> **URI choice:** The adapter uses `Request.getUri()` (the current URI, which
> may already be rewritten by upstream PingAccess rules) rather than
> `Exchange.getOriginalRequestUri()` (the URI before any rule rewrites).
> **Rationale:** Profile matching should operate on the URI that will actually
> reach the backend, not the original client URI. If a prior rule rewrites
> `/old/path` to `/new/path`, the transform spec should match `/new/path`.
> `getOriginalRequestUri()` is available for logging/diagnostics if needed.

#### wrapResponse Mapping

| Message Field | Source |
|---------------|--------|
| `body` | `exchange.getResponse().getBody().getContent()` → parse as `JsonNode` |
| `headers` | `exchange.getResponse().getHeaders().getAllHeaderFields()` → same normalization |
| `headersAll` | Same source → multi-value map |
| `statusCode` | `exchange.getResponse().getStatusCode()` |
| `contentType` | `exchange.getResponse().getHeaders().getContentType()` → string |
| `requestPath` | `exchange.getRequest().getUri()` (original request path for profile matching) |
| `requestMethod` | `exchange.getRequest().getMethod().getName()` |
| `queryString` | From original request URI |
| `sessionContext` | See FR-002-06 |

#### applyChanges Direction Strategy

`applyChanges(Message, Exchange)` must apply to the correct side of the
Exchange depending on which phase called it. The `GatewayAdapter<R>` SPI uses
a single method signature with `R = Exchange`; the **caller** (`MessageTransformRule`)
knows the direction and routes accordingly:

- **During `handleRequest`:** caller passes `exchange` and the adapter writes
  to `exchange.getRequest()` (body, headers, URI, method).
- **During `handleResponse`:** caller passes `exchange` and the adapter writes
  to `exchange.getResponse()` (body, headers, status code).

**Implementation approach:** The adapter stores the current direction as a
method-local parameter. Two concrete approaches:

1. **Direction parameter on adapter** (preferred): Add a `Direction` enum
   parameter as a private helper: `applyChanges(Message, Exchange, Direction)`.
   The public `GatewayAdapter.applyChanges(Message, Exchange)` delegates by
   defaulting to RESPONSE (backward-compatible with `StandaloneAdapter`).
   `MessageTransformRule` calls the direction-aware overload directly.

2. **Two methods**: `applyRequestChanges(Message, Exchange)` and
   `applyResponseChanges(Message, Exchange)` as internal helpers. The SPI
   `applyChanges()` delegates to the appropriate one.

| Phase | Target Side | Fields Written |
|-------|-------------|----------------|
| REQUEST | `exchange.getRequest()` | body via `setBodyContent(bytes)`, headers via `setValues`/`removeFields`, URI via `setUri()`, method via `setMethod()` |
| RESPONSE | `exchange.getResponse()` | body via `setBodyContent(bytes)`, headers via `setValues`/`removeFields`, status via `setStatus(HttpStatus.forCode())` |

> **Note:** `requestPath` and `requestMethod` changes are only applied during
> REQUEST phase. `statusCode` changes are only applied during RESPONSE phase.
> Attempting to write request fields during RESPONSE is silently ignored by
> PingAccess (Constraint #3).

#### Header Application Strategy

The core engine's `Message.headers()` contains the **final** header map after
transformation. The adapter cannot simply call `setValues()` for each header —
it must also **remove** pre-existing headers that were dropped by the transform.

The adapter uses a **diff-based** approach:

1. **Capture original headers** at wrap time (snapshot the header names from
   the native `Request`/`Response` before passing to the engine).
2. **After transformation**, compare the original header names with the
   transformed `Message.headers()` keyset.
3. **Apply changes:**
   - Headers in transformed but not in original → `headers.add(name, value)` (new).
   - Headers in both → `headers.setValues(name, List.of(value))` (update).
   - Headers in original but not in transformed → `headers.removeFields(name)` (remove).

This ensures the PingAccess `Headers` object accurately reflects the transform
result without leaking pre-existing headers that the spec intended to remove.

> **Alternative considered:** Clear all headers then set new ones. Rejected
> because PA may have internal/system headers that the adapter shouldn't
> touch (e.g., hop-by-hop headers managed by the engine).

### FR-002-02: AsyncRuleInterceptor Plugin

**Requirement:** The module MUST provide a class `MessageTransformRule` extending
`AsyncRuleInterceptorBase<MessageTransformConfig>` with:

1. `handleRequest(Exchange)` → `CompletionStage<Outcome>`:
   - Builds `TransformContext` via `PingAccessAdapter.buildTransformContext()` (FR-002-13).
   - Wraps request via `PingAccessAdapter.wrapRequest()`.
   - Transforms via `TransformEngine.transform(message, Direction.REQUEST, transformContext)`
     (**3-arg overload** — required for `$cookies`, `$queryParams`, `$session` injection).
   - Dispatches on `TransformResult.type()`:
     - `SUCCESS` → applies changes via `PingAccessAdapter.applyChanges()` with REQUEST direction.
     - `PASSTHROUGH` → no-op (exchange untouched).
     - `ERROR` → see FR-002-11 error-mode dispatch.
   - Returns `Outcome.CONTINUE` (or `Outcome.RETURN` in DENY mode on error).
2. `handleResponse(Exchange)` → `CompletionStage<Void>`:
   - Wraps response via `PingAccessAdapter.wrapResponse()`.
   - Transforms via `TransformEngine.transform(message, Direction.RESPONSE, transformContext)`.
   - Dispatches on `TransformResult.type()`:
     - `SUCCESS` → applies changes via `PingAccessAdapter.applyChanges()` with RESPONSE direction.
     - `PASSTHROUGH` → no-op.
     - `ERROR` → see FR-002-11 error-mode dispatch.
   - Returns completed `CompletionStage<Void>`.
3. `getErrorHandlingCallback()` → `ErrorHandlingCallback`:
   - **This method is abstract on `AsyncRuleInterceptor` and MUST be implemented.**
   - Returns `new RuleInterceptorErrorHandlingCallback(getTemplateRenderer(), errorConfig)`
     using the SDK's provided implementation. This callback writes a PA-formatted
     error response when an unhandled exception escapes `handleRequest()`/`handleResponse()`.

| Aspect | Detail |
|--------|--------|
| Success path | Request body transformed, response body transformed, both directions independent |
| Failure path | Transform error → log warning, return original untouched exchange (ADR-0013 copy-on-wrap safety), continue pipeline |
| Error mode (request) | `PASS_THROUGH` → `Outcome.CONTINUE` with original. `DENY` → `Outcome.RETURN` with RFC 9457 error response body written to exchange. |
| Error mode (response) | `PASS_THROUGH` → original response untouched. `DENY` → **rewrite** the response body/status to a 502 RFC 9457 error (there is no pipeline-halt mechanism for responses — `handleResponse` returns `Void`, not `Outcome`). |
| Thread safety | Plugin instances may be shared across threads — all adapter state is method-local |
| Status | ⬜ Not yet implemented |
| Source | G-002-02 |

### FR-002-03: @Rule Annotation

**Requirement:** The plugin class MUST be annotated with:

```java
@Rule(
    category = RuleInterceptorCategory.Processing,
    destination = { RuleInterceptorSupportedDestination.Site },
    label = "Message Transform",
    type = "MessageTransform",
    expectedConfiguration = MessageTransformConfig.class
)
```

**Constraints:**
- `destination = Site` is **mandatory** — Agent rules cannot access request body
  or handle responses.
- `type = "MessageTransform"` must be unique across all PingAccess plugins.
- `category = Processing` — this is a request/response processing rule, not an
  access control rule.

| Aspect | Detail |
|--------|--------|
| Status | ⬜ Not yet implemented |
| Source | G-002-03, SDK constraint |

### FR-002-04: Plugin Configuration (Admin UI)

**Requirement:** The plugin MUST expose the following configuration fields via
`@UIElement` annotations on a `MessageTransformConfig extends SimplePluginConfiguration`:

| Field | UIElement Type | Label | Required | Default | Description |
|-------|---------------|-------|----------|---------|-------------|
| `specsDir` | TEXT | Spec Directory | Yes | `/specs` | Path to directory of transform YAML specs |
| `profilesDir` | TEXT | Profiles Directory | No | `/profiles` | Path to directory of transform profiles |
| `activeProfile` | TEXT | Active Profile | No | (empty) | Profile name to activate (empty = no profile) |
| `errorMode` | SELECT | Error Mode | Yes | `PASS_THROUGH` | `PASS_THROUGH` or `DENY` — behaviour on transform failure |
| `reloadIntervalSec` | TEXT | Reload Interval (s) | No | `0` | **Spec YAML file** reload interval in seconds (0 = disabled). See note below. |
| `schemaValidation` | SELECT | Schema Validation | No | `LENIENT` | `STRICT` or `LENIENT` — schema validation mode |

The configuration JSON maps directly to these fields via the PingAccess admin API.

**`reloadIntervalSec` clarification:** This controls periodic re-reading of
transform spec YAML files from `specsDir` and profiles from `profilesDir` —
it does **not** hot-reload the adapter JAR itself (Constraint #2). When
`reloadIntervalSec > 0`, the adapter starts a `ScheduledExecutorService`
(single daemon thread) that calls `TransformEngine.reload()` at the specified
interval. This allows ops to update transform specs on disk without restarting
PingAccess. When `reloadIntervalSec = 0` (default), specs are loaded only once
during `configure()` and changes require a PA restart.

| Aspect | Detail |
|--------|--------|
| Success path | Admin creates rule → specifies spec directory → plugin loads specs on `configure()` |
| Validation path | Missing `specsDir` → `ValidationException` on `configure()` |
| Status | ⬜ Not yet implemented |
| Source | G-002-03 |

### FR-002-05: Plugin Lifecycle

**Requirement:** The plugin MUST initialize and manage the core engine through
the PingAccess plugin lifecycle:

1. **Construction:** PingAccess instantiates the plugin via default constructor.
2. **Dependency injection:** `@Inject` methods called (HttpClient, TemplateRenderer
   — available but not needed for pure transforms).
3. **Configuration:** `configure(MessageTransformConfig)` called with JSON-mapped
   configuration fields. At this point, the plugin MUST:
   - Initialize `SpecParser` with JSLT expression engine.
   - Initialize `TransformEngine`.
   - Load all specs from `specsDir`.
   - Optionally load profiles from `profilesDir`.
4. **Runtime:** `handleRequest()` / `handleResponse()` called per request.
5. **Teardown:** No explicit destroy — plugin is garbage-collected on PA restart.

| Aspect | Detail |
|--------|--------|
| Success path | `configure()` initializes engine + loads specs without error |
| Failure path | Spec parse error → `ValidationException` with detail message → plugin not activated |
| Status | ⬜ Not yet implemented |
| Source | G-002-05, SDK lifecycle docs |

### FR-002-06: Session Context Binding (Identity)

**Requirement:** The adapter MUST populate the `sessionContext` field of the
`Message` record from the PingAccess `Identity` object (ADR-0030, FR-001-13).

The `$session` JsonNode includes identity fields, OAuth metadata, OIDC/token
claims, and optionally session state attributes:

```java
// Build sessionContext JsonNode from Exchange.getIdentity()
Identity identity = exchange.getIdentity();
ObjectNode session = objectMapper.createObjectNode();
session.put("subject", identity.getSubject());
session.put("mappedSubject", identity.getMappedSubject());
session.put("trackingId", identity.getTrackingId());
session.put("tokenId", identity.getTokenId());
if (identity.getTokenExpiration() != null) {
    session.put("tokenExpiration", identity.getTokenExpiration().toInstant().toString());
}
if (identity.getAttributes() != null) {
    session.set("attributes", identity.getAttributes());  // OIDC claims / token introspection
}

// OAuth metadata (GAP-03)
OAuthTokenMetadata oauth = identity.getOAuthTokenMetadata();
if (oauth != null) {
    ObjectNode oauthNode = objectMapper.createObjectNode();
    oauthNode.put("clientId", oauth.getClientId());
    oauthNode.put("tokenType", oauth.getTokenType());
    oauthNode.put("realm", oauth.getRealm());
    ArrayNode scopesNode = objectMapper.createArrayNode();
    if (oauth.getScopes() != null) {
        oauth.getScopes().forEach(scopesNode::add);
    }
    oauthNode.set("scopes", scopesNode);
    session.set("oauth", oauthNode);
}

// Session state attributes (read-only snapshot)
SessionStateSupport sss = identity.getSessionStateSupport();
if (sss != null && sss.getAttributes() != null) {
    ObjectNode stateNode = objectMapper.createObjectNode();
    sss.getAttributes().forEach(stateNode::set);
    session.set("sessionState", stateNode);
}
```

#### `$session` Schema

| Path | Type | Source | Notes |
|------|------|--------|-------|
| `$session.subject` | string | `Identity.getSubject()` | Authenticated principal |
| `$session.mappedSubject` | string | `Identity.getMappedSubject()` | Mapped by IdentityMapping plugin |
| `$session.trackingId` | string | `Identity.getTrackingId()` | PA-generated tracking ID |
| `$session.tokenId` | string | `Identity.getTokenId()` | OAuth token ID |
| `$session.tokenExpiration` | string (ISO 8601) | `Identity.getTokenExpiration()` | Omitted if no expiration |
| `$session.attributes` | object | `Identity.getAttributes()` | OIDC claims / token introspection claims |
| `$session.attributes.sub` | string | (typical OIDC claim) | OIDC subject |
| `$session.attributes.email` | string | (typical OIDC claim) | User email |
| `$session.attributes.roles` | array | (typical OIDC claim, nested path) | Navigate with dot-path in JSLT |
| `$session.oauth.clientId` | string | `OAuthTokenMetadata.getClientId()` | OAuth client ID |
| `$session.oauth.scopes` | array | `OAuthTokenMetadata.getScopes()` | Granted OAuth scopes |
| `$session.oauth.tokenType` | string | `OAuthTokenMetadata.getTokenType()` | e.g., "Bearer" |
| `$session.oauth.realm` | string | `OAuthTokenMetadata.getRealm()` | OAuth realm |
| `$session.sessionState` | object | `SessionStateSupport.getAttributes()` | Read-only snapshot of PA session state |

**`tokenExpiration` note:** `Identity.getTokenExpiration()` returns a `java.util.Date`.
The adapter formats it as an ISO 8601 string (`Instant.toString()` format, e.g.
`"2026-02-11T12:30:00Z"`). JSLT can compare this as a string for basic expiry
checks. If the token has no expiration, the field is omitted.

**OAuth metadata note:** `OAuthTokenMetadata` provides the OAuth client context
(client ID, scopes, token type, realm). This enables scope-based JSLT transforms
(e.g., `if (contains($session.oauth.scopes, "admin")) ...`). If `getOAuthTokenMetadata()`
returns `null`, the `oauth` sub-object is omitted.

**Session state note:** `SessionStateSupport` provides persistent key-value
storage across requests within a PA session. The adapter reads these attributes
as a **read-only snapshot** — the adapter does NOT write to session state.
Session state attributes set by other PA rules (e.g., `ExternalAuthorizationRule`'s
cached authorization responses) are available to JSLT transforms via
`$session.sessionState.<key>`.

| Aspect | Detail |
|--------|--------|
| Success path | JSLT `$session.subject` resolves to authenticated user, `$session.oauth.clientId` to OAuth client |
| Failure path | No identity (unauthenticated) → `sessionContext = null` → `$session` is `null` in JSLT |
| Failure path (OAuth) | No OAuth metadata → `$session.oauth` missing → `$session.oauth.clientId` evaluates to `null` |
| Status | ⬜ Not yet implemented |
| Source | ADR-0030, FR-001-13, G-002-01 |

### FR-002-07: ExchangeProperty State (cross-rule data)

**Requirement:** The adapter MUST store the `TransformResult` (match status,
spec id, transform duration) as an `ExchangeProperty` on the `Exchange`, enabling
downstream rules or logging to inspect transform metadata.

```java
private static final ExchangeProperty<Map<String, Object>> TRANSFORM_RESULT =
    ExchangeProperty.create("io.messagexform", "transformResult", Map.class);
```

| Aspect | Detail |
|--------|--------|
| Success path | Downstream rule reads `exchange.getProperty(TRANSFORM_RESULT)` |
| Status | ⬜ Not yet implemented |
| Source | SDK ExchangeProperty pattern |

### FR-002-08: SPI Registration

**Requirement:** The module MUST include a
`META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor` file
containing the fully qualified class name of the plugin:

```
io.messagexform.pingaccess.MessageTransformRule
```

This enables PingAccess's `ServiceLoader` discovery.

| Aspect | Detail |
|--------|--------|
| Validation path | `jar tf adapter-pingaccess-*.jar | grep META-INF/services` → shows the SPI file |
| Status | ⬜ Not yet implemented |
| Source | G-002-04, SDK SPI pattern |

### FR-002-09: Deployment Packaging

**Requirement:** The module MUST produce a **shadow JAR** containing:

- The adapter classes (`io.messagexform.pingaccess.*`)
- The core engine (`io.messagexform.core.*`)
- `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`
- All runtime dependencies (Jackson, JSLT, SnakeYAML, JSON Schema Validator)

The shadow JAR MUST **exclude** the PingAccess SDK classes — those are provided
by the PA runtime (`/opt/server/lib/pingaccess-sdk-*.jar`).

**SLF4J handling:** SLF4J API MUST be **excluded** from the shadow JAR
(`compileOnly` scope). PingAccess ships its own SLF4J provider (Logback);
bundling a second SLF4J API would cause a classpath conflict
(`SLF4JServiceProvider` already loaded). The adapter uses SLF4J as a compile
dependency only.

**Jackson relocation:** PingAccess uses Jackson internally (e.g. `Identity.
getAttributes()` returns `JsonNode`). To avoid version conflicts, the shadow
JAR SHOULD use Gradle Shadow's `relocate` feature to shade Jackson classes
into a private package (e.g. `io.messagexform.shaded.jackson`). If Jackson
version alignment with PA is confirmed (same major.minor), relocation can be
skipped — but this must be verified at build time.

**TelemetryListener:** The PA adapter does NOT register a custom
`TelemetryListener` implementation. The core engine's built-in SLF4J logging
is sufficient. PA's own monitoring (PingAccess admin → audit logs) captures
rule execution events. Custom telemetry can be added as a follow-up if needed.

The JAR MUST be deployable by copying to `<PA_HOME>/deploy/` (Docker:
`/opt/server/deploy/`).

| Aspect | Detail |
|--------|--------|
| Success path | `cp adapter-pingaccess-*.jar /opt/server/deploy/ && restart PA` → plugin visible in admin UI |
| Validation path | `./gradlew :adapter-pingaccess:shadowJar` produces a single JAR < 20 MB |
| Status | ⬜ Not yet implemented |
| Source | G-002-04 |

### FR-002-10: Gradle Module Setup

**Requirement:** The `adapter-pingaccess` module MUST be configured as a standard
Gradle subproject with:

1. Dependency on `project(":core")` (implementation).
2. Dependency on `com.pingidentity.pingaccess:pingaccess-sdk:9.0.1.0`
   (**compileOnly** — provided by PA runtime).
3. Dependency on `jakarta.validation:jakarta.validation-api:3.1.1` (compileOnly).
4. Dependency on `jakarta.inject:jakarta.inject-api:2.0.1` (compileOnly).
5. Shadow JAR plugin configured to exclude the `compileOnly` dependencies.
6. Java 21 toolchain (same as core).
7. The PingAccess Maven repository
   (`https://maven.pingidentity.com/release/`) added as a repository. Fallback:
   local JAR at `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` via
   `files()` dependency if the Maven repo is unreachable.

| Aspect | Detail |
|--------|--------|
| Success path | `./gradlew :adapter-pingaccess:build` compiles and passes tests |
| Status | ⬜ Not yet implemented |
| Source | G-002-04, FR-009-01 (Gradle governance) |

### FR-002-11: Error Handling

**Requirement:** The adapter MUST handle transform errors according to the
configured error mode. Transform errors include: JSLT evaluation failures,
spec parse errors at runtime, eval budget exceeded, and output size exceeded.

| Error Mode | handleRequest Behaviour | handleResponse Behaviour |
|------------|------------------------|--------------------------|
| `PASS_THROUGH` | Log warning, return `Outcome.CONTINUE` (original untouched request continues to backend) | Log warning, leave original response untouched |
| `DENY` | Write RFC 9457 error body to `exchange.getResponse()` via `setBodyContent()` + `setStatus(HttpStatus.forCode(502))`, return `Outcome.RETURN` to halt pipeline | **Rewrite** response: `exchange.getResponse().setBodyContent()` with RFC 9457 error body + `setStatus(502)`. There is **no pipeline-halt** for responses (returns `Void`, not `Outcome`). The error response replaces the backend response. |

**DENY mode detailed behaviour:**

1. **In `handleRequest`:** The engine returns `TransformResult.ERROR` with
   `errorResponse` (RFC 9457 JSON) and `errorStatusCode` (typically 502).
   The adapter writes this error body to the exchange's response via
   `exchange.getResponse().setBodyContent(errorBytes)` and sets status to
   `HttpStatus.forCode(502)`. Then returns `Outcome.RETURN` — PingAccess
   short-circuits and returns the error response to the client without
   forwarding to the backend.

2. **In `handleResponse`:** The engine returns `TransformResult.ERROR`.
   Since `handleResponse()` returns `CompletionStage<Void>` (no `Outcome`
   mechanism), the adapter writes the error body to
   `exchange.getResponse().setBodyContent(errorBytes)` and sets status
   to 502 via `setStatus()`. The client receives the error response
   instead of the original backend response. **This is a body/status
   rewrite, not a pipeline halt.**

The adapter MUST NOT throw `AccessException` for transform failures — transform
errors are non-fatal by default (adapter continues the pipeline). Unhandled
exceptions are caught by `getErrorHandlingCallback()` (FR-002-02).

| Aspect | Detail |
|--------|--------|
| Success path (PASS_THROUGH) | Malformed JSON body → log + continue with original body |
| Success path (DENY, request) | JSLT error → write 502 error to exchange response → `Outcome.RETURN` |
| Success path (DENY, response) | JSLT error → rewrite response body/status to 502 error |
| Status | ⬜ Not yet implemented |
| Source | ADR-0022, ADR-0024 |

### FR-002-12: Docker E2E Test Script

**Requirement:** The project MUST include a script (`scripts/pa-e2e-test.sh`)
that:

1. Builds the adapter shadow JAR via Gradle.
2. Starts a PingAccess 9.0 Docker container
   (`pingidentity/pingaccess:9.0.1-latest`) with:
   - The adapter JAR mounted into `/opt/server/deploy/`.
   - A license file mounted into `/opt/out/instance/conf/`.
   - Test transform specs mounted into a spec directory.
   - A test site configured (mock backend via a simple HTTP echo server).
3. Waits for PingAccess to start and be healthy.
4. Configures a rule + application + resource via the PA Admin REST API
   (port 9000).
5. Sends test requests through the PA engine (port 3000) and verifies
   transformed responses.
6. Tears down the Docker containers.

**Note:** This script is planned for implementation **after** the adapter
code is complete. It is listed here for traceability.

| Aspect | Detail |
|--------|--------|
| Success path | `./scripts/pa-e2e-test.sh` → builds + configures PA + runs assertions + exits 0 |
| Status | ⬜ Not yet implemented (planned, implementation deferred) |
| Source | G-002-05 |

### FR-002-13: TransformContext Construction

**Requirement:** The adapter MUST build a `TransformContext` from the PingAccess
`Exchange` and pass it to the 3-arg `TransformEngine.transform()` overload.
Without this, JSLT variables `$cookies`, `$queryParams`, `$headers`, `$status`,
and `$session` would be null/empty.

The adapter MUST provide a `buildTransformContext(Exchange)` method (not part
of the `GatewayAdapter` SPI — adapter-specific helper) that maps:

| TransformContext Field | Source | Notes |
|------------------------|--------|-------|
| `headers` | `exchange.getRequest().getHeaders().getAllHeaderFields()` → single-value map (first value per name, lowercase keys) | Same normalization as `wrapRequest` |
| `headersAll` | Same source → multi-value map | Same normalization |
| `status` | `null` for REQUEST direction, `exchange.getResponse().getStatusCode()` for RESPONSE | Set by the caller (`MessageTransformRule`) based on direction |
| `queryParams` | `exchange.getRequest().getQueryStringParams()` → flatten `Map<String, String[]>` to `Map<String, String>` using first-value semantics | Values are URL-decoded by the PA SDK. On `URISyntaxException`: log warning, use empty map. |
| `cookies` | `exchange.getRequest().getHeaders().getCookies()` → flatten `Map<String, String[]>` to `Map<String, String>` using first-value semantics | Cookie values are URL-decoded |
| `sessionContext` | See FR-002-06 (`Exchange.getIdentity()` → build `JsonNode`) | `null` if no identity (unauthenticated) |

**Query param multi-value handling:** PingAccess's `getQueryStringParams()` returns
`Map<String, String[]>`. The adapter uses **first-value semantics** (take `values[0]`
for each key), matching the `StandaloneAdapter` pattern (FR-004-39). Multi-value
query params are available in full via `$headers_all` if the gateway transmits them
as headers, but the `$queryParams` variable uses single-value.

**`URISyntaxException` handling:** `Request.getQueryStringParams()` can throw
`URISyntaxException` for malformed URIs. On this exception, the adapter logs a
warning and returns an empty `queryParams` map. The transform proceeds with
`$queryParams` as an empty object — JSLT expressions referencing query params
evaluate to `null` gracefully.

**Lifecycle note:** `buildTransformContext()` is called **once per request** at
the start of `handleRequest()`. The same `TransformContext` is reused for both
request and response phases of the same exchange (with `status` updated for the
response phase). This avoids re-parsing cookies/query params in `handleResponse()`.

```java
// Pseudocode — illustrative
TransformContext buildTransformContext(Exchange exchange, Direction direction) {
    Map<String, String> headers = normalizeHeaders(exchange.getRequest().getHeaders());
    Map<String, List<String>> headersAll = extractMultiValueHeaders(exchange.getRequest().getHeaders());

    Map<String, String> queryParams;
    try {
        queryParams = flattenFirstValue(exchange.getRequest().getQueryStringParams());
    } catch (URISyntaxException e) {
        LOG.warn("Malformed URI, query params unavailable: {}", e.getMessage());
        queryParams = Map.of();
    }

    Map<String, String> cookies = flattenFirstValue(exchange.getRequest().getHeaders().getCookies());

    Integer status = direction == Direction.RESPONSE
            ? exchange.getResponse().getStatusCode() : null;

    JsonNode sessionContext = buildSessionContext(exchange.getIdentity()); // FR-002-06

    return new TransformContext(headers, headersAll, status, queryParams, cookies, sessionContext);
}
```

| Aspect | Detail |
|--------|--------|
| Success path | `TransformContext` populated with headers, cookies, query params, session → JSLT `$cookies.sessionToken` resolves correctly |
| Failure path (URI) | Malformed query string → empty `queryParams` → JSLT `$queryParams.page` evaluates to `null` |
| Failure path (no identity) | Unauthenticated → `sessionContext = null` → `$session` is `null` in JSLT |
| Status | ⬜ Not yet implemented |
| Source | G-002-01, FR-004-37 (StandaloneAdapter reference pattern) |

---

## Non-Functional Requirements

| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-002-01 | Adapter transform latency MUST add < 10 ms overhead beyond the core engine transform time for a typical JSON body (< 64 KB). | Gateway SLA | Measure via `ExchangeProperty` timestamps (creation time vs. transform completion). | Core engine performance. | Spec. |
| NFR-002-02 | Shadow JAR size MUST be < 20 MB (excludes PA SDK). | Deployment ergonomics | `ls -lh adapter-pingaccess-*.jar` | Shadow plugin, dependency management. | Spec. |
| NFR-002-03 | Adapter MUST be thread-safe — no mutable shared state. All state MUST be method-local or immutable (engine is already thread-safe). | PA runtime constraint | Code review, ArchUnit rule (FR-009-12). | Core engine thread safety. | SDK docs. |
| NFR-002-04 | Adapter MUST NOT use reflection — same no-reflection policy as core (FR-009-12 Rule 3). | AOT compatibility | ArchUnit test. | ArchUnit (FR-009-12). | Project constitution. |
| NFR-002-05 | Adapter MUST compile with Java 21 toolchain and `-Xlint:all -Werror`. | Build consistency | `./gradlew :adapter-pingaccess:compileJava` | FR-009-02, FR-009-03. | FR-009-03. |

---

## Branch & Scenario Matrix

| Scenario ID | Description / Expected Outcome |
|-------------|-------------------------------|
| S-002-01 | **Request body transform:** POST request with JSON body → JSLT transforms body → PingAccess forwards transformed body to backend. |
| S-002-02 | **Response body transform:** Backend returns JSON → JSLT transforms response body → client receives transformed JSON. |
| S-002-03 | **Bidirectional transform:** Both request and response transformed in a single round-trip (same spec with `request` + `response` directions). |
| S-002-04 | **Header transform:** Request headers added/removed/renamed before forwarding; response headers modified before returning to client. |
| S-002-05 | **Status code transform:** Backend returns 404 → spec maps to 200 with synthetic body. |
| S-002-06 | **URL rewrite:** Request path rewritten by spec before forwarding to backend (ADR-0027). |
| S-002-07 | **Empty body:** Request with no body → NullNode → transform skipped (no match) → original forwarded. |
| S-002-08 | **Non-JSON body:** Request with `text/plain` body → body parses to NullNode → headers-only transform possible. |
| S-002-09 | **Profile matching:** Only transform requests matching active profile path pattern `/api/v1/*`. |
| S-002-10 | **No matching spec:** Request doesn't match any spec → pass-through, no transformation applied. |
| S-002-11 | **Error mode PASS_THROUGH:** Spec expression throws → log warning → continue with original body. |
| S-002-12 | **Error mode DENY:** Spec expression throws → return 502 with RFC 9457 error body. |
| S-002-13 | **Session context in JSLT:** JSLT expression uses `$session.subject` → resolves from PingAccess Identity. |
| S-002-14 | **No identity (unauthenticated):** Exchange has no Identity → `$session` is null → JSLT gracefully handles. |
| S-002-15 | **Multiple specs loaded:** Two specs match different paths → correct spec applied per request path. |
| S-002-16 | **Large body (64 KB):** Transform completes within eval budget, no OOM. |
| S-002-17 | **Plugin configuration via admin UI:** Admin creates MessageTransform rule, specifies spec directory → plugin loads specs. |
| S-002-18 | **Invalid spec directory:** `specsDir` doesn't exist → `ValidationException` on `configure()`. |
| S-002-19 | **Plugin SPI registration:** JAR deployed to `/opt/server/deploy/` → PA restart → "Message Transform" rule type visible in admin. |
| S-002-20 | **Thread safety:** Concurrent requests through the same rule instance → no data corruption. |
| S-002-21 | **ExchangeProperty metadata:** Downstream rule reads transform result metadata (spec id, duration). |
| S-002-22 | **Cookie access in JSLT:** JSLT expression uses `$cookies` → resolves from PA request cookies via `TransformContext.cookiesAsJson()`. |
| S-002-23 | **Query param access in JSLT:** JSLT expression uses `$queryParams` → resolves from PA query string via `TransformContext.queryParamsAsJson()`. |
| S-002-24 | **Shadow JAR correctness:** Deploy shadow JAR → no `ClassNotFoundException` at runtime (all deps bundled, PA SDK excluded). |

---

## Test Strategy

### Unit Tests

- **`PingAccessAdapterTest`** — Tests all `wrapRequest`, `wrapResponse`, and
  `applyChanges` methods with mock `Exchange`/`Request`/`Response`/`Body`/`Headers`
  objects. Follows the pattern of `StandaloneAdapterTest`.
  - Mock framework: Mockito (already in use).
  - Key verification: all 9 `Message` fields populated correctly, deep copy
    semantics, header normalization (lowercase), null/empty body handling.

- **`MessageTransformRuleTest`** — Tests the `handleRequest` / `handleResponse`
  lifecycle with a real `TransformEngine` and mock `Exchange`.
  - Key verification: transform applied, changes written back, error modes,
    no-match pass-through, `ExchangeProperty` metadata set.

- **`MessageTransformConfigTest`** — Tests configuration validation
  (`@UIElement` constraints, required fields, defaults).

### Integration Tests

- **Docker-based E2E** (FR-002-12) — deferred to post-implementation phase.
  Script-based, runs against real PingAccess container.

---

## Interface & Contract Catalogue

### Build Commands (CLI)

| ID | Command | Behaviour |
|----|---------|-----------|
| CLI-002-01 | `./gradlew :adapter-pingaccess:build` | Compile + test adapter module |
| CLI-002-02 | `./gradlew :adapter-pingaccess:shadowJar` | Build deployable shadow JAR |
| CLI-002-03 | `./gradlew :adapter-pingaccess:test` | Run adapter unit tests only |

### Configuration Files

| ID | Path | Purpose |
|----|------|---------|
| CFG-002-01 | `adapter-pingaccess/build.gradle.kts` | Module build config, PA SDK dependency |
| CFG-002-02 | `settings.gradle.kts` | Include `adapter-pingaccess` subproject |
| CFG-002-03 | `adapter-pingaccess/src/main/resources/META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor` | SPI registration |

### Source Files (planned)

| ID | Path | Purpose |
|----|------|---------|
| SRC-002-01 | `adapter-pingaccess/src/main/java/io/messagexform/pingaccess/PingAccessAdapter.java` | `GatewayAdapter<Exchange>` implementation |
| SRC-002-02 | `adapter-pingaccess/src/main/java/io/messagexform/pingaccess/MessageTransformRule.java` | `AsyncRuleInterceptorBase` plugin |
| SRC-002-03 | `adapter-pingaccess/src/main/java/io/messagexform/pingaccess/MessageTransformConfig.java` | `SimplePluginConfiguration` with `@UIElement` fields |
| SRC-002-04 | `adapter-pingaccess/src/test/java/io/messagexform/pingaccess/PingAccessAdapterTest.java` | Adapter unit tests |
| SRC-002-05 | `adapter-pingaccess/src/test/java/io/messagexform/pingaccess/MessageTransformRuleTest.java` | Plugin lifecycle tests |
| SRC-002-06 | `adapter-pingaccess/src/test/java/io/messagexform/pingaccess/MessageTransformConfigTest.java` | Config validation tests |

---

## Constraints

1. **Site-only deployment:** The `@Rule(destination = Site)` annotation is
   mandatory. Agent rules cannot access request body or invoke `handleResponse`.
2. **Restart required:** PingAccess must be restarted after deploying/updating
   the plugin JAR. There is no hot-reload for Java plugins (Groovy scripts
   support hot-reload but are out of scope). Spec YAML files *can* be
   reloaded without restart if `reloadIntervalSec > 0` (FR-002-04).
3. **Response immutability (PA 6.1+):** Modifying *request* fields during
   `handleResponse()` processing is ignored (logged as warning by PA). The
   adapter MUST NOT attempt to modify request data in the response phase.
4. **Thread safety:** Plugin instances may be shared across threads. All
   adapter state must be method-local or immutable.
5. **Body.read() may be deferred:** The `Body.getContent()` call may trigger
   a lazy read. The adapter MUST call `body.read()` if `!body.isRead()`
   before accessing content. **On failure** (`AccessException` or `IOException`
   from `body.read()`): treat as empty body (`NullNode`), log warning with
   exception details. This is consistent with the "malformed JSON → NullNode"
   fallback in FR-002-01.
6. **No pipeline halt for responses:** `handleResponse()` returns
   `CompletionStage<Void>`, not `Outcome`. The adapter cannot prevent
   response delivery — it can only rewrite the response body/status.
   This affects DENY error mode semantics (FR-002-11).

---

## Appendix

### A. PingAccess SDK Dependency Coordinates

```kotlin
// Gradle (Kotlin DSL) — in adapter-pingaccess/build.gradle.kts
repositories {
    maven { url = uri("https://maven.pingidentity.com/release/") }
}

dependencies {
    implementation(project(":core"))

    // PA SDK — provided at runtime by PingAccess
    compileOnly("com.pingidentity.pingaccess:pingaccess-sdk:9.0.1.0")
    compileOnly("jakarta.validation:jakarta.validation-api:3.1.1")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
}
```

### B. Adapter Bridge Architecture

```
Client                    PingAccess                        Backend
  │                           │                               │
  │  POST /api/users          │                               │
  │  { "name": "ivan" }       │                               │
  │──────────────────────►    │                               │
  │                           │ handleRequest():               │
  │                           │  1. wrapRequest(exchange)      │
  │                           │  2. engine.transform(msg, ctx) │
  │                           │  3. applyChanges(result, exch) │
  │                           │──────────────────────────────► │
  │                           │                               │
  │                           │  ◄─────────────────────────── │
  │                           │ handleResponse():              │
  │                           │  1. wrapResponse(exchange)     │
  │                           │  2. engine.transform(msg, ctx) │
  │                           │  3. applyChanges(result, exch) │
  │  { "id": 1, "name": ... } │                               │
  │  ◄───────────────────── │                               │
```

### C. Related Documents

| Document | Relevance |
|----------|-----------|
| `docs/research/pingaccess-plugin-api.md` | SDK research (complete) |
| `docs/research/pingaccess-docker-and-sdk.md` | Docker + SDK samples (complete) |
| `docs/research/pingam-authentication-api.md` | PingAM callback format (transform target) |
| `docs/reference/pingaccess-9.0.pdf` | Official PA 9.0 documentation |
| `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` | SDK JAR (166 classes) |
| `adapter-standalone/src/main/java/.../StandaloneAdapter.java` | Reference adapter implementation |
| ADR-0013 | Copy-on-wrap message adapter pattern |
| ADR-0020 | Nullable status code (requests) |
| ADR-0022 | Error response on transform failure |
| ADR-0024 | Error type catalogue |
| ADR-0027 | URL rewriting |
| ADR-0030 | Session context binding |
