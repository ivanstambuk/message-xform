# Step-Up Authentication & Session Enrichment for Transaction Signing

> Investigation date: 2026-03-02
> Sources: PingAccess 9.0 SDK guide (`docs/reference/pingaccess-sdk-guide.md`),
> PingGateway 2025.11 SDK guide (`docs/reference/pinggateway-sdk-guide.md`),
> Groovy scripting research (`docs/research/pingaccess-groovy-payload-rewrite.md`),
> PingAccess plugin API research (`docs/research/pingaccess-plugin-api.md`)

---

## 1. Problem Statement

A user is authenticated and holds a browser session cookie (PingAccess web
session or PingGateway JWT session).  For certain high-value operations —
transaction signing, payment confirmation, sensitive data access — the
application requires **step-up re-authentication** with additional context
(transaction amount, payee, challenge nonce, authentication method) that goes
beyond the original login session.

### Constraints

1. **Cookie-only** — in a browser, HttpOnly/Secure cookies are the only safe
   credential carrier.  Access tokens in `localStorage` or response payloads
   are not acceptable.
2. **Same redirect flow** — re-authentication must use the same OIDC redirect
   pattern (browser → IdP → callback) the user already understands.
3. **No duplicate API onboarding** — the same backend API operation should not
   require onboarding under two separate application objects.
4. **Cross-operation scope** — the signing context should be usable across
   multiple related API calls (e.g., create draft → confirm → receipt).

### PingAccess Architectural Limitation

PingAccess maps **one Web Session per Application**.  An Application has a
single `webSessionId`.  There is no built-in mechanism for two concurrent
session contexts on the same application.

If the only mechanism available were web sessions, the solution would require:

1. Two Web Session objects (login session + signing session).
2. Two Application objects (e.g., `/api` + `/api-sign`), same backend.
3. Duplicate onboarding of every API operation that needs both contexts.

This document explores two approaches that **avoid** that limitation.

---

## 2. Approach A — Session State Enrichment

### 2.1 Concept

Use the gateway's persistent session state mechanism to enrich the existing
login session with signing context.  After step-up re-authentication, a custom
rule/filter writes the signing context as session state attributes.  A second
custom rule/filter validates those attributes on protected API operations.

The signing context lives **inside** the existing session — no second web
session, no second application, no duplicate onboarding.

### 2.2 PingAccess Implementation

#### SessionStateSupport API

PingAccess provides `SessionStateSupport` via the Identity object
(§8 of the PA SDK guide):

```java
// com.pingidentity.pa.sdk.session.SessionStateSupport (interface)
Map<String, JsonNode> getAttributes();                    // all session attributes
JsonNode              getAttributeValue(String name);     // single attribute
void                  setAttribute(String name, JsonNode value);  // write attribute
void                  removeAttribute(String name);
```

Key properties:

- **Persists across requests** within a PA session — it is stored in a
  **separate cookie** from the main web session cookie.
- **Writable from rules** — any `RuleInterceptor` (Java plugin or Groovy
  script rule) can write session state via
  `identity.getSessionStateSupport().setAttribute(...)`.
- **Shared across applications** — because the session state cookie is a
  PA-level concept, it is available to rules across multiple applications.
- **Namespaced keys** — the SDK recommends reverse-domain naming
  (e.g., `com.example.signing.context`) to avoid collisions.

> Source: `docs/reference/pingaccess-sdk-guide.md` §8, lines 1121–1133

#### SDK Evidence

The `ExternalAuthorizationRule` SDK sample uses `SessionStateSupport` to store
per-request authorization results across requests — confirming that session
state writes from rules are a supported, documented pattern.

> Source: `docs/reference/pingaccess-sdk-guide.md` §11, line 1516

#### Step-Up Flow (PA)

```
Browser                  PingAccess               PingFederate         Backend
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (existing PA cookie)   │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule]                   │                  │
   │                    checks session state:          │                  │
   │                    no signing context?            │                  │
   │                         │                         │                  │
   │  ◄─── 401 Challenge ─── │                         │                  │
   │  Location: /pf/authn    │                         │                  │
   │  ?ACR=txn-signing       │                         │                  │
   │  &state=<nonce>         │                         │                  │
   │                         │                         │                  │
   │  ──── OIDC redirect ──────────────────────────►   │                  │
   │                         │                     [Re-authenticate]      │
   │                         │                     (strong auth +         │
   │                         │                      transaction context)  │
   │  ◄──── auth code + state ───────────────────────  │                  │
   │                         │                         │                  │
   │  GET /callback          │                         │                  │
   │  ?code=<code>           │                         │                  │
   │  &state=<nonce>         │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [CallbackRule]                 │                  │
   │                    exchanges code → tokens        │                  │
   │                    extracts signing claims        │                  │
   │                    writes to SessionStateSupport: │                  │
   │                      setAttribute(                │                  │
   │                        "signing.context",         │                  │
   │                        { amount, payee,           │                  │
   │                          acr, timestamp })        │                  │
   │                         │                         │                  │
   │  ◄─── 302 → /api/payments (original request)      │                  │
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (same PA cookie,       │                         │                  │
   │   now with session      │                         │                  │
   │   state cookie)         │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule]                   │                  │
   │                    session state has              │                  │
   │                    signing context ✓              │                  │
   │                    validates TTL, amount ✓        │                  │
   │                         │ ──────────────────────────────────────────►│
   │                         │                         │             [Process]
   │  ◄────────────── 200 OK ──────────────────────────────────────────── │
```

#### Custom Rules Required (PA)

| Rule            | Type        | Purpose |
|-----------------|-------------|---------|
| **StepUpRule**  | Java plugin | Checks `SessionStateSupport` for signing context. Returns 401 with redirect URI if absent or expired |
| **CallbackRule**| Java plugin | Handles the OIDC callback, exchanges authorization code for tokens, extracts signing claims, writes to `SessionStateSupport` — **without** creating a new web session |

> **Critical design point:** The CallbackRule must NOT use PA's built-in OIDC
> callback mechanism (which would overwrite the existing web session).  It is
> a **custom** callback endpoint handled by a rule that calls PingFederate's
> token endpoint directly via `HttpClient` (available to async rules), then
> writes the result to `SessionStateSupport`.

#### Can Groovy Script Rules Access SessionStateSupport?

**Yes.**  Groovy script rules access the same `Exchange` object as Java plugins
(confirmed in `docs/research/pingaccess-groovy-payload-rewrite.md`).  The
access path is:

```groovy
def identity = exc?.identity
def sss = identity?.getSessionStateSupport()

// Read
def signingCtx = sss?.getAttributeValue("signing.context")

// Write
import com.fasterxml.jackson.databind.ObjectMapper
def mapper = new ObjectMapper()
def node = mapper.createObjectNode()
node.put("amount", 1500)
node.put("payee", "ACME Corp")
node.put("timestamp", System.currentTimeMillis())
sss?.setAttribute("signing.context", node)
```

However, Groovy is **not recommended** for the CallbackRule because:

1. It needs to make an outbound HTTP call (token exchange) — Groovy rules have
   no `HttpClient` injection (unlike `AsyncRuleInterceptorBase`).
2. Jackson is available on the classpath (PA bundles it), but there is no
   dependency management for Groovy scripts.
3. The CallbackRule is security-critical — it handles authorization codes and
   token validation.  This demands compiled, tested, auditable code.

**Groovy IS appropriate for the StepUpRule** (read-only validation):

```groovy
// StepUpRule (Groovy) — validate signing context
def sss = exc?.identity?.getSessionStateSupport()
def ctx = sss?.getAttributeValue("signing.context")

if (ctx == null) {
    // No signing context — challenge
    exc?.response = com.pingidentity.pa.sdk.http.ResponseBuilder
        .newInstance(com.pingidentity.pa.sdk.http.HttpStatus.UNAUTHORIZED)
        .header("Location", "/pf/authn?ACR=txn-signing&state=...")
        .build()
    return
}

// Check TTL (e.g., 5 minutes)
def timestamp = ctx?.get("timestamp")?.asLong()
if (System.currentTimeMillis() - timestamp > 300_000) {
    sss.removeAttribute("signing.context")
    // expired — re-challenge
    exc?.response = com.pingidentity.pa.sdk.http.ResponseBuilder
        .newInstance(com.pingidentity.pa.sdk.http.HttpStatus.UNAUTHORIZED)
        .header("Location", "/pf/authn?ACR=txn-signing&state=...")
        .build()
    return
}

pass()
```

#### Identity Mapping Limitation (PA)

PingAccess identity mapping plugins (`IdentityMappingPlugin`) operate on the
`Exchange` and set headers for the backend.  The built-in identity mapping
types (`HeaderMapping`, `JWT`, `WebSessionAccessToken`) read from
`Identity.getAttributes()` (token claims) — they do NOT read from
`SessionStateSupport`.

**Implication:** If the backend needs signing context as a header (e.g.,
`X-Signing-Context: {json}`), the built-in identity mapping cannot provide
it.  Options:

| Option | Approach | Effort |
|--------|----------|--------|
| **A (recommended)** | StepUpRule itself sets the header via `exchange.getRequest().getHeaders().add(...)` after validating session state | Zero — rule already has access |
| **B** | Custom `IdentityMappingPlugin` that reads `SessionStateSupport` and maps attributes to headers | Medium — new plugin SPI implementation |
| **C** | Include signing context in the upstream token (PF returns it as a claim) and use standard `HeaderMapping` | Complex — requires PF token customization |

Option A is simplest: the validation rule already reads `SessionStateSupport`,
so it can inject the relevant attributes as headers in the same pass.  No
custom identity mapping plugin needed.

### 2.3 PingGateway Implementation

PingGateway's session model is **significantly more flexible** than PingAccess
for this use case.

#### Session API

PingGateway sessions implement `Map<String, Object>`:

```java
SessionContext sessionCtx = context.asContext(SessionContext.class);
Session session = sessionCtx.getSession();

// Write signing context
Map<String, Object> signingCtx = new HashMap<>();
signingCtx.put("amount", 1500);
signingCtx.put("payee", "ACME Corp");
signingCtx.put("timestamp", System.currentTimeMillis());
session.put("signing.context", signingCtx);

// Read signing context
Map<String, Object> ctx = (Map<String, Object>) session.get("signing.context");
```

> Source: `docs/reference/pinggateway-sdk-guide.md` §3, lines 726–870

#### Session Storage

| Feature | Stateful | Stateless (JWT) |
|---------|----------|-----------------|
| Storage | Server-side | Client-side cookie |
| Cookie name | `IG_SESSIONID` | `openig-jwt-session` |
| Size limit | Unlimited | 4 KB per cookie (auto-split) |
| Data types | All | JSON-compatible only |
| Load balancing | Sticky sessions | Any server (shared keys) |

For signing context, **stateless JWT sessions** are preferred — the signing
context is small, JSON-compatible, and the JWT cookie provides tamper-proof
integrity without server-side state.

#### Groovy ScriptableFilter (PG)

PingGateway Groovy scripts have **full access** to the session — this is a
first-class API, not a workaround:

```groovy
// StepUpFilter.groovy (PingGateway ScriptableFilter)
def session = context.asContext(SessionContext.class).session

def signingCtx = session["signing.context"]
if (signingCtx == null) {
    // No signing context — return 401 challenge
    return new Response(Status.UNAUTHORIZED)
        .tap { headers.put("Location", "/pf/authn?ACR=txn-signing") }
}

// Validate TTL
if (System.currentTimeMillis() - (signingCtx.timestamp as long) > 300_000) {
    session.remove("signing.context")
    return new Response(Status.UNAUTHORIZED)
        .tap { headers.put("Location", "/pf/authn?ACR=txn-signing") }
}

// Forward to backend with signing context header
request.headers.put("X-Signing-Context",
    new groovy.json.JsonOutput().toJson(signingCtx))
return next.handle(context, request)
```

PingGateway Groovy scripts also have:
- **`http` client** — bound into scope automatically by
  `AbstractScriptableHeapObject`, backed by `ClientHandler`.
  Available for outbound calls (token exchange).
- **`next` handler** — for forwarding requests.
- **`globals`** — `ConcurrentHashMap` for cross-invocation state.

This means the **entire flow** (StepUpFilter + CallbackFilter) can be
implemented in Groovy on PingGateway — no Java plugin required.

#### PingGateway: No Identity Mapping Limitation

PingGateway does not have a separate "identity mapping" SPI like PingAccess.
Headers are set directly by filters.  Any filter can read session state and
set headers — there is no abstraction boundary to work around.

---

## 3. Approach B — Custom Signed JWT Cookie

### 3.1 Concept

Completely bypass the gateway's session mechanism for the signing context.
After step-up re-authentication via PingFederate, a short-lived **signed JWT**
is issued and set as a **separate cookie** (not the web session cookie).

The gateway passes this cookie through to the backend (or a gateway rule
validates it).  The signing context lives **outside** the session entirely —
it's a standalone, self-contained proof.

### 3.2 Flow

```
Browser                  Gateway (PA or PG)        PingFederate         Backend
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (session cookie only)  │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule/Filter]             │                  │
   │                    No signing JWT cookie?          │                  │
   │                         │                         │                  │
   │  ◄─── 401 Challenge ─── │                         │                  │
   │  Location: /pf/authn    │                         │                  │
   │  ?ACR=txn-signing       │                         │                  │
   │  &response_type=code    │                         │                  │
   │  &scope=openid txn      │                         │                  │
   │                         │                         │                  │
   │  ──── OIDC redirect ──────────────────────────► │                  │
   │                         │                    [Re-authenticate]      │
   │  ◄──── code + state ─────────────────────────── │                  │
   │                         │                         │                  │
   │  GET /signing-callback  │                         │                  │
   │  ?code=<code>           │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [CallbackRule/Filter]           │                  │
   │                    exchange code → tokens          │                  │
   │                    extract signing claims          │                  │
   │                    build signing JWT:              │                  │
   │                      { amount, payee, acr,         │                  │
   │                        iat, exp (5 min),           │                  │
   │                        sub, jti }                  │                  │
   │                    sign with gateway key           │                  │
   │                         │                         │                  │
   │  ◄─── 302 + Set-Cookie: ──────────────────────── │                  │
   │       PA_SIGNING_JWT=<jwt>; HttpOnly; Secure;     │                  │
   │       SameSite=Strict; Path=/api; Max-Age=300     │                  │
   │       Location: /api/payments                     │                  │
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  Cookie: PA_SUBJECT=..  │                         │                  │
   │  Cookie: PA_SIGNING_JWT=.. │                      │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule/Filter]             │                  │
   │                    validates signing JWT:          │                  │
   │                    - signature valid               │                  │
   │                    - not expired                   │                  │
   │                    - claims match request          │                  │
   │                         │ ─────────────────────────────────────────►│
   │  ◄────────────── 200 OK ──────────────────────────────────────── │
```

### 3.3 Cookie Properties

```http
Set-Cookie: PA_SIGNING_JWT=eyJhbGciOi...;
  HttpOnly;          # not readable by JavaScript
  Secure;            # HTTPS only
  SameSite=Strict;   # CSRF protection
  Path=/api;         # scoped to API paths
  Max-Age=300        # 5-minute TTL
```

The JWT itself contains:

```json
{
  "alg": "RS256",
  "typ": "JWT"
}
{
  "sub": "user123",
  "acr": "txn-signing",
  "txn": {
    "amount": 1500,
    "payee": "ACME Corp",
    "currency": "EUR"
  },
  "iat": 1741121377,
  "exp": 1741121677,
  "jti": "a1b2c3d4"
}
```

### 3.4 PingAccess Implementation

The custom rule validates the JWT cookie:

```java
// In handleRequest():
String jwtCookie = exchange.getRequest().getHeaders()
    .getCookies().get("PA_SIGNING_JWT");

if (jwtCookie == null) {
    // Challenge: return 401 with redirect
    return challenge(exchange);
}

// Validate JWT (signature, expiry, claims)
SigningContext ctx = jwtValidator.validate(jwtCookie);
if (ctx == null || ctx.isExpired()) {
    return challenge(exchange);
}

// Inject as header for backend
exchange.getRequest().getHeaders().add(
    "X-Signing-Context", ctx.toJson());
```

Setting the cookie in the callback:

```java
// In the callback rule:
SetCookie signingCookie = new SetCookie("PA_SIGNING_JWT", signedJwt);
signingCookie.setHttpOnly(true);
signingCookie.setSecure(true);
signingCookie.setPath("/api");
signingCookie.setMaxAge(300);

Response redirect = ResponseBuilder
    .found(originalRequestUri)
    .build();
redirect.getHeaders().addCookie(signingCookie);
exchange.setResponse(redirect);
return CompletableFuture.completedFuture(Outcome.RETURN);
```

### 3.5 PingGateway Implementation

```groovy
// CallbackFilter.groovy — set signing JWT cookie
def tokenResponse = http.send(tokenRequest).get()
def claims = extractSigningClaims(tokenResponse)
def jwt = buildSignedJwt(claims) // Jose4j or Nimbus

def cookie = new Cookie()
cookie.name = "PG_SIGNING_JWT"
cookie.value = jwt
cookie.httpOnly = true
cookie.secure = true
cookie.path = "/api"
cookie.maxAge = 300

def redirect = new Response(Status.FOUND)
redirect.headers.put("Location", originalUri)
redirect.headers.add("Set-Cookie", cookie.toHeaderValue())
return redirect
```

PingGateway's `JwtSessionManager` could also be used to sign the JWT with
the gateway's own keys (symmetric or asymmetric), but a custom JWT gives more
control over claims and TTL scoping.

---

## 4. Comparison Matrix

| Dimension | Approach A (Session State) | Approach B (Custom JWT Cookie) |
|-----------|--------------------------|-------------------------------|
| **Session coupling** | Enriches existing session | Independent — separate cookie |
| **Cookie count** | +1 (session state cookie) | +1 (signing JWT cookie) |
| **Tamper-proof** | PA-signed session state cookie | Self-signed JWT with RS256 |
| **TTL management** | Manual (timestamp in attributes) | JWT `exp` claim (standard) |
| **Scope control** | Global (all apps on same PA) | Cookie `Path` scoping |
| **One-shot semantics** | Manual `removeAttribute()` | Cookie `Max-Age` + `jti` replay check |
| **Backend integration** | Rule injects header from session state | Rule injects header from JWT claims |
| **Groovy-only (no Java)** | Partial (StepUpRule yes, CallbackRule needs HttpClient) | Same (callback needs HttpClient) |
| **PingGateway Groovy-only** | ✅ Full (http client available in scripts) | ✅ Full |
| **Stateless** | No (PA-managed session state) | Yes (JWT is self-contained) |
| **Portability** | PA/PG-specific session API | JWT can be validated by any backend |
| **Complexity** | Lower (uses built-in session infra) | Higher (JWT signing, key management) |

### Recommendation

- **Approach A** is simpler when the gateway already manages sessions and the
  signing context is short-lived.  Ideal for PingAccess environments where
  `SessionStateSupport` is already in use for other purposes.

- **Approach B** is architecturally cleaner: separation of concerns between
  "are you logged in" (session cookie) and "did you authorize this" (signing JWT).
  Better for environments with multiple backends, microservices, or when the
  backend needs to validate the signing proof independently.

Both approaches can coexist.

---

## 5. Groovy Capabilities Summary

### PingAccess Groovy Script Rules

| Capability | Available? | Notes |
|-----------|:----------:|-------|
| Read `Exchange` | ✅ | `exc` binding |
| Read `Identity` | ✅ | `exc?.identity` |
| Read `SessionStateSupport` | ✅ | `exc?.identity?.getSessionStateSupport()` |
| Write `SessionStateSupport` | ✅ | `.setAttribute(name, jsonNode)` |
| Read `OAuthTokenMetadata` | ✅ | `exc?.identity?.getOAuthTokenMetadata()` |
| Set request headers | ✅ | `exc?.request?.header?.add(name, value)` |
| Set response (DENY) | ✅ | `exc?.response = ResponseBuilder...build()` |
| Outbound HTTP calls | ❌ | No `HttpClient` injection in Groovy scripts |
| Jackson `ObjectMapper` | ⚠️ | Available on classpath but no DI; create manually |
| JWT signing/validation | ❌ | No JWT library access in Groovy sandbox |

### PingGateway ScriptableFilter (Groovy)

| Capability | Available? | Notes |
|-----------|:----------:|-------|
| Read/write `Session` | ✅ | `session.get()` / `session.put()` |
| Read `OAuth2Context` | ✅ | `context.as(OAuth2Context.class)` |
| Set request/response headers | ✅ | Full `Headers` API |
| Short-circuit response | ✅ | Return `new Response(Status.UNAUTHORIZED)` |
| Outbound HTTP calls | ✅ | `http` (Client) bound into scope |
| JWT creation | ✅ | Jose4j/Nimbus on classpath (PG bundles them) |
| Session expressions | ✅ | `${session["signing.context"]}` in route config |

**Key difference:** PingGateway's Groovy scripting is a full-featured filter
model with HTTP client and JWT library access.  PingAccess Groovy scripts are
more limited — they have Exchange access but no outbound capabilities.

---

## 6. Identity Mapping Analysis

### PingAccess

#### Built-in Identity Mapping Types

PingAccess identity mapping produces a representation of the authenticated
user to send to the backend (typically as a request header).  The built-in
types all read from `Identity.getAttributes()` — which contains **token
claims** from PingFederate, not session state attributes from
`SessionStateSupport`.

| Mapping Type | Source | Output Format | Reads SessionState? |
|-------------|--------|--------------|:-------------------:|
| `HeaderMapping` | `Identity.getAttributes()` | Plain headers (one per attribute) | ❌ |
| `JWT` | `Identity.getAttributes()` | **Signed JWT** (`X-PA-IDENTITY` header) | ❌ |
| `WebSessionAccessToken` | OIDC access token | Raw access token as header | ❌ |
| `ClientCertificate` | TLS cert | Certificate fields as headers | ❌ |
| Custom `IdentityMappingPlugin` | `Exchange` (full access) | Any format | ✅ |

#### The JWT Identity Mapping Problem

Many backends require a **signed JWT** in a header (e.g., `X-PA-IDENTITY`)
rather than plain header values.  PingAccess's built-in JWT identity mapping
produces this JWT from `Identity.getAttributes()` — which contains:

- Subject (`sub`)
- Token claims from PingFederate (roles, groups, custom claims)
- OAuth metadata (scopes, client ID)

It does **NOT** include `SessionStateSupport` attributes.  This means:

> **With Approach A (Session State Enrichment), the signing context in
> `SessionStateSupport` CANNOT appear in the identity-mapped JWT.**

This is a fundamental limitation.  The plain-header workaround ("Option A:
StepUpRule sets headers directly") works when the backend accepts a simple
`X-Signing-Context: {json}` header, but it **does not work** when the backend
requires claims inside the JWT that PingAccess produces via identity mapping.

#### Options for JWT-Based Backend Integration

| Option | How It Works | Fits Approach A? | Fits Approach B? |
|--------|-------------|:----------------:|:----------------:|
| **1. Rule sets plain header** | StepUpRule adds `X-Signing-Context` header alongside the JWT identity mapping header | ⚠️ Partial — signing context is a separate header, not inside the JWT | N/A |
| **2. Custom IdentityMappingPlugin** | New Java plugin that reads `SessionStateSupport` and produces a JWT with signing claims included | ✅ Yes | N/A |
| **3. Custom JWT cookie (Approach B)** | Gateway issues a separate signed JWT cookie; the backend reads it directly | N/A | ✅ Yes — the JWT IS the signing proof |
| **4. PF token customization** | PingFederate includes signing claims in the access/ID token during step-up | ✅ Yes — built-in JWT mapping then includes them | ✅ Yes |

**Analysis:**

- **Option 1** is the simplest but breaks the "single JWT" contract if the
  backend expects all identity + signing claims in one token.

- **Option 2** requires a custom `IdentityMappingPlugin` — a new Java plugin
  SPI implementation:

```java
@IdentityMapping(
    type = "SessionStateJwtMapping",
    label = "Session State → JWT",
    expectedConfiguration = SessionStateJwtMappingConfig.class)
public class SessionStateJwtIdentityMapping
    extends IdentityMappingPluginBase<SessionStateJwtMappingConfig> {

    @Override
    public void handleMapping(Exchange exchange) throws IOException {
        Identity identity = exchange.getIdentity();
        if (identity == null) return;

        // Start with standard identity attributes
        ObjectNode claims = objectMapper.createObjectNode();
        claims.put("sub", identity.getSubject());
        JsonNode attrs = identity.getAttributes();
        if (attrs != null && attrs.isObject()) {
            attrs.fields().forEachRemaining(e ->
                claims.set(e.getKey(), e.getValue()));
        }

        // Merge session state (signing context)
        SessionStateSupport sss = identity.getSessionStateSupport();
        if (sss != null) {
            JsonNode signingCtx = sss.getAttributeValue("signing.context");
            if (signingCtx != null) {
                claims.set("txn", signingCtx);
            }
        }

        // Sign and set as header
        String jwt = jwtSigner.sign(claims);
        exchange.getRequest().getHeaders().add(
            getConfiguration().getHeaderName(), jwt);
    }
}
```

  This is a **new plugin** (separate from message-xform) with its own SPI
  registration (`META-INF/services/...IdentityMappingPlugin`), key
  management, and configuration UI.  Medium-to-high effort.

- **Option 3 (Approach B)** avoids the identity mapping problem entirely.
  The signing JWT is a separate cookie — the backend reads it from the
  `Cookie` header or the gateway extracts it and forwards it as a header.
  The backend validates it independently.  The existing identity mapping
  (JWT or HeaderMapping) continues to work unchanged for the login session.

- **Option 4** pushes the complexity to PingFederate.  If PF can return
  transaction-scoped claims in the ID token during step-up (custom OIDC
  scopes, ACR-dependent claim mappings), the standard JWT identity mapping
  will include them automatically.  This is the cleanest solution but
  requires PF configuration and may not be feasible for all transaction types.

#### Recommendation for JWT-Based Backends

**Approach B (Custom JWT Cookie) is the preferred solution** when the backend
requires signing context in a JWT.  It avoids:

- No custom identity mapping plugin
- No PF token customization
- No coupling between the session state and the identity mapping pipeline
- Clean separation: login JWT (identity mapping) vs. signing JWT (custom cookie)

If the backend insists on a **single JWT** containing both identity and
signing claims, Option 2 (custom `IdentityMappingPlugin`) is the only viable
path under Approach A.

### PingGateway

PingGateway has no separate identity mapping SPI.  All header injection is
done by filters.  Session attributes are directly accessible from any filter
in the chain via `context.asContext(SessionContext.class).getSession()`.

A filter can produce a JWT with any combination of identity attributes and
session state — there is no abstraction boundary between "identity" and
"session state" that would prevent merging them into a single JWT.

For JWT-based backends, a Groovy ScriptableFilter can:

1. Read identity from `OAuth2Context` or `SsoTokenContext`
2. Read signing context from `Session`
3. Merge both into a single JWT
4. Sign with PG-managed keys (Jose4j/Nimbus on classpath)
5. Set as a request header

No custom Java plugin is needed.  PingGateway's flat model (everything is a
filter, everything accesses Context) avoids the identity mapping limitation
entirely.

---

## 7. Security Considerations

### Cookie Security

Both approaches produce cookies that MUST be:

- `HttpOnly` — not readable by JavaScript (XSS mitigation).
- `Secure` — HTTPS only.
- `SameSite=Strict` or `SameSite=Lax` — CSRF mitigation.
- Scoped `Path` — limit to API path prefixes.
- Short `Max-Age` — signing context should expire in minutes, not hours.

### Replay Protection

- **Approach A**: Implement a `jti` (unique ID) in the session state and
  track used IDs.  Or use one-shot semantics: `removeAttribute()` after
  the first successful use.
- **Approach B**: Include a `jti` claim in the JWT.  Maintain a short-lived
  replay cache (in-memory set with TTL matching JWT expiry).

### Key Management

- **Approach A**: PA/PG manages the session cookie signing keys.  No
  additional key management.
- **Approach B**: The gateway needs a signing key for the custom JWT.
  Options: use the PingFederate-issued token directly (no gateway signing),
  or use a gateway-managed keypair (requires key rotation).

---

## 8. Implementation Effort Comparison

### PingAccess

| Component | Approach A | Approach B |
|-----------|-----------|-----------|
| StepUpRule | Java plugin (read session state, challenge if absent) | Java plugin (read JWT cookie, validate, challenge if absent) |
| CallbackRule | Java plugin (token exchange, write session state) | Java plugin (token exchange, sign JWT, set cookie) |
| Groovy alternative (StepUpRule) | ✅ Possible | ✅ Possible |
| Groovy alternative (CallbackRule) | ❌ No HttpClient | ❌ No HttpClient |
| Identity mapping change | None (rule sets headers) | None (rule sets headers) |
| Key management | None | Gateway signing key |
| Total new plugins | 2 rules | 2 rules + JWT signing |

### PingGateway

| Component | Approach A | Approach B |
|-----------|-----------|-----------|
| StepUpFilter | Groovy ScriptableFilter or Java filter | Groovy ScriptableFilter or Java filter |
| CallbackFilter | Groovy ScriptableFilter (has `http` client) | Groovy ScriptableFilter |
| Groovy-only | ✅ Both filters | ✅ Both filters |
| Session config | `JwtSessionManager` in `config.json` | None (custom cookie) |
| Key management | PG session keys | Gateway signing key or PG keys |
| Total new components | 2 filters (can be Groovy) | 2 filters + JWT signing |

---

## 9. Open Questions

1. **PA session state cookie scope**: Is the session state cookie
   automatically shared across all PA applications on the same virtual host,
   or does it require explicit configuration?  Needs live testing.

2. **PA session state size limits**: What is the maximum size of data that
   can be stored in `SessionStateSupport`?  The session state cookie has
   browser size limits (~4 KB).

3. **PF transaction-scoped claims**: Does PingFederate support returning
   custom claims (transaction amount, payee) in the ID token or access token
   for a step-up flow?  This would simplify both approaches.

4. **Groovy `ResponseBuilder` access**: Can Groovy scripts in PA actually
   call `ResponseBuilder.newInstance()`?  The static factory uses
   `ServiceFactory` internally, which may behave differently in the Groovy
   sandbox.  Needs live testing.

5. **PG OIDC filters**: Can PingGateway's built-in `OAuth2ClientFilter` /
   `SingleSignOnFilter` be configured for step-up flows (custom ACR, scopes),
   or does the entire OIDC flow need to be custom-scripted?

---

## 10. References

| Source | Path |
|--------|------|
| PA SDK Guide — SessionStateSupport | `docs/reference/pingaccess-sdk-guide.md` §8, lines 1121–1133 |
| PA SDK Guide — Identity | `docs/reference/pingaccess-sdk-guide.md` §8, lines 1110–1119 |
| PA SDK Guide — IdentityMapping | `docs/reference/pingaccess-sdk-guide.md` §16, lines 2135–2177 |
| PA SDK Guide — ExchangeProperty | `docs/reference/pingaccess-sdk-guide.md` §5, lines 894–911 |
| PA SDK Guide — Thread Safety (ExternalAuthorizationRule) | `docs/reference/pingaccess-sdk-guide.md` §11, line 1516 |
| PA Groovy Research | `docs/research/pingaccess-groovy-payload-rewrite.md` |
| PA Plugin API Research | `docs/research/pingaccess-plugin-api.md` lines 265–270 |
| PG SDK Guide — SessionContext & Session | `docs/reference/pinggateway-sdk-guide.md` §3, lines 726–870 |
| PG SDK Guide — Session Types | `docs/reference/pinggateway-sdk-guide.md` §3, lines 827–855 |
| PG SDK Guide — PA vs PG Session Model | `docs/reference/pinggateway-sdk-guide.md` §3, lines 884–892 |
| PG SDK Guide — ScriptableFilter | `docs/reference/pinggateway-sdk-guide.md` §14, lines 4348–4402 |
| PG SDK Guide — Session Access in Expressions | `docs/reference/pinggateway-sdk-guide.md` §3, lines 873–879 |

---

*Status: COMPLETE — Research covering both session enrichment and custom JWT
approaches for PingAccess and PingGateway step-up authentication*
