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

PingGateway's architecture is **significantly more flexible** than PingAccess
for this use case.  Unlike PA's rule-based model, PingGateway uses a **filter
chain** where each filter is a first-class component that can read/write
sessions, make outbound HTTP calls, and produce responses.  This means the
entire step-up flow can be implemented in Groovy — no Java plugins required.

#### Session Model

PingGateway sessions are a simple `Map<String, Object>` interface, accessed
via `SessionContext` in the context chain:

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

PingGateway supports two session storage mechanisms:

| Feature | Stateful | Stateless (JWT) |
|---------|----------|-----------------|
| Storage | Server-side (PG memory) | Client-side (JWT in `Set-Cookie`) |
| Cookie name | `IG_SESSIONID` | `openig-jwt-session` |
| Size limit | Unlimited | 4 KB per cookie (auto-split) |
| Data types | All | JSON-compatible only |
| Load balancing | Sticky sessions | Any server (shared keys) |

> **Both session types use `Set-Cookie`** — the browser receives and sends
> session state automatically via cookie headers.  The "JWT" in "stateless
> JWT session" refers to the encoding of the cookie value, not a bearer token.
> The cookie is `HttpOnly` and `Secure` — not readable by JavaScript.

For signing context, **stateless JWT sessions** are preferred — the signing
context is small, JSON-compatible, and the JWT cookie provides tamper-proof
integrity without server-side state.

#### Step-Up Flow (PG)

```
Browser                  PingGateway              PingFederate         Backend
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (existing PG session   │                         │                  │
   │   cookie)               │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpFilter]                 │                  │
   │                    reads session:                 │                  │
   │                    no signing context?            │                  │
   │                         │                         │                  │
   │  ◄─── 401 Challenge ─── │                         │                  │
   │  Location: /pf/authn    │                         │                  │
   │  ?ACR=txn-signing       │                         │                  │
   │  &state=<nonce>         │                         │                  │
   │                         │                         │                  │
   │  Browser follows        │                         │                  │
   │  redirect to PF ────────────────────────────────► │                  │
   │                         │                     [Re-authenticate]      │
   │                         │                     (strong auth +         │
   │                         │                      transaction context)  │
   │  ◄──── auth code + state ───────────────────────  │                  │
   │                         │                         │                  │
   │  GET /signing-callback  │                         │                  │
   │  ?code=<code>           │                         │                  │
   │  &state=<nonce>         │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [CallbackFilter]               │                  │
   │                    uses `http` client to call     │                  │
   │                    PF token endpoint:             │                  │
   │                         │ ── POST /token ───────► │                  │
   │                         │ ◄── tokens ──────────── │                  │
   │                    extracts signing claims        │                  │
   │                    writes to Session:             │                  │
   │                      session.put(                 │                  │
   │                        "signing.context",         │                  │
   │                        { amount, payee,           │                  │
   │                          acr, timestamp })        │                  │
   │                         │                         │                  │
   │  ◄─── 302 + Set-Cookie: ───────────────────────── │                  │
   │       (PG session cookie updated with             │                  │
   │        signing context baked into JWT)            │                  │
   │       Location: /api/payments                     │                  │
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (same PG session       │                         │                  │
   │   cookie, now contains  │                         │                  │
   │   signing context)      │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpFilter]                 │                  │
   │                    session has signing            │                  │
   │                    context ✓                      │                  │
   │                    validates TTL, amount ✓        │                  │
   │                         │                         │                  │
   │                    [JwtBuilderFilter]             │                  │
   │                    builds signed JWT from         │                  │
   │                    template:                      │                  │
   │                    { sub, roles, scopes,          │                  │
   │                      txn: session[signing.ctx] }  │                  │
   │                    sets X-Identity-JWT            │                  │
   │                         │ ──────────────────────────────────────────►│
   │                         │                         │             [Process]
   │  ◄────────────── 200 OK ──────────────────────────────────────────── │
```

#### Groovy ScriptableFilter — How It Works

PingGateway's `ScriptableFilter` executes a Groovy script as a filter in
the request/response chain.  Understanding the execution model is important:

**Bindings available to every script:**

| Binding | Type | Purpose |
|---------|------|---------|
| `context` | `Context` | The context chain (session, OAuth2, client, etc.) |
| `request` | `Request` | The current HTTP request |
| `next` | `Handler` | The next filter/handler in the chain — call to forward the request |
| `http` | `Client` | An HTTP client for making **outbound calls** (token exchange, etc.) |
| `logger` | `Logger` | Per-filter logger instance |
| `globals` | `ConcurrentHashMap` | Cross-invocation persistent state (see below) |

**Control flow — what happens when the script returns:**

- **`return next.handle(context, request)`** — forwards the request to the
  next filter in the chain (and eventually to the backend).  The response
  flows back through the chain in reverse order.  The browser receives the
  backend's response.

- **`return new Response(Status.UNAUTHORIZED)`** — short-circuits the chain.
  The `next` handler is **never called** — the request does NOT reach the
  backend.  PingGateway sends this response directly to the browser.  This
  is how the 401 challenge works: the browser receives it and follows the
  `Location` redirect to PingFederate.

- **`return new Response(Status.FOUND)`** — same short-circuit, but with a
  302 redirect.  The CallbackFilter uses this to redirect the browser back
  to the original API endpoint after writing the signing context to the
  session.

**`globals` — persistent cross-invocation state:**

The `globals` binding is a `ConcurrentHashMap` that persists across all
invocations of the same script instance.  It lives for the lifetime of the
PingGateway route — it is NOT request-scoped.  Use cases:

- Caching validation keys fetched from PingFederate's JWKS endpoint
- Tracking replay detection nonces (`jti` values)
- Counters or rate-limiting state

```groovy
// Example: cache PF signing keys (fetched once, reused across requests)
if (!globals.containsKey("pf_jwks")) {
    def jwksResponse = http.send(new Request()
        .setMethod("GET")
        .setUri("https://pf.example.com/pf/JWKS")).get()
    globals["pf_jwks"] = jwksResponse.entity.json
}
def jwks = globals["pf_jwks"]
```

> **Important:** `globals` is shared across concurrent requests to the same
> filter instance.  Use `ConcurrentHashMap` operations (`.putIfAbsent()`,
> `.computeIfAbsent()`) for thread safety.

#### Custom Filters Required (PG)

| Filter            | Type              | Purpose |
|-------------------|-------------------|---------|
| **StepUpFilter**  | Groovy ScriptableFilter | Checks session for signing context. Returns 401 with redirect if absent or expired |
| **CallbackFilter**| Groovy ScriptableFilter | Handles the OIDC callback, uses `http` client to exchange code → tokens, writes signing context to session, redirects browser back |
| **JwtBuilderFilter** | OOTB (config only) | Produces a signed JWT for the backend containing identity + signing context claims — **no code required** |

#### StepUpFilter (Groovy)

```groovy
// StepUpFilter.groovy — validate signing context in session
def session = context.asContext(SessionContext.class).session

def signingCtx = session["signing.context"]
if (signingCtx == null) {
    // No signing context — short-circuit: send 401 to the browser.
    // next.handle() is NOT called — the request never reaches the backend.
    // The browser receives this response and follows the Location redirect.
    def challengeUri = "https://pf.example.com/as/authorization.oauth2" +
        "?response_type=code" +
        "&client_id=signing-app" +
        "&redirect_uri=https://gateway.example.com/signing-callback" +
        "&scope=openid+txn-signing" +
        "&acr_values=txn-signing" +
        "&state=" + UUID.randomUUID().toString()

    // Save original request URI so CallbackFilter can redirect back
    session["signing.original_uri"] = request.uri.toASCIIString()

    return new Response(Status.FOUND)
        .tap { headers.put("Location", challengeUri) }
}

// Validate TTL (5 minutes)
def timestamp = signingCtx.timestamp as long
if (System.currentTimeMillis() - timestamp > 300_000) {
    session.remove("signing.context")
    // Same redirect as above — expired, need re-authentication
    return new Response(Status.FOUND)
        .tap { headers.put("Location", challengeUri) }
}

// Signing context valid — forward to the next filter in the chain.
// JwtBuilderFilter (downstream) will pick up the signing context from
// the session and include it in the signed JWT for the backend.
return next.handle(context, request)
```

#### CallbackFilter (Groovy)

```groovy
// CallbackFilter.groovy — handle OIDC callback, write session state
def session = context.asContext(SessionContext.class).session

// Extract authorization code from the callback query parameters
def code = request.uri.query?.split("&")
    ?.collectEntries { it.split("=", 2).with { [(it[0]): it[1]] } }
    ?.get("code")

if (!code) {
    return new Response(Status.BAD_REQUEST)
        .tap { entity.string = '{"error": "missing authorization code"}' }
}

// Exchange authorization code for tokens using the bound `http` client.
// This is an OUTBOUND call from PingGateway to PingFederate's token endpoint.
def tokenRequest = new Request()
    .setMethod("POST")
    .setUri("https://pf.example.com/as/token.oauth2")
tokenRequest.headers.put("Content-Type", "application/x-www-form-urlencoded")
tokenRequest.entity.string = "grant_type=authorization_code" +
    "&code=${code}" +
    "&redirect_uri=https://gateway.example.com/signing-callback" +
    "&client_id=signing-app" +
    "&client_secret=${globals['client_secret'] ?: 'configured-secret'}"

def tokenResponse = http.send(tokenRequest).get()
def tokens = tokenResponse.entity.json

// Extract signing claims from the ID token or access token
// (depends on PF configuration — claims could be in either)
def idTokenClaims = parseJwtClaims(tokens.id_token)

// Write signing context to the session.
// On the NEXT request, this data will be available in the session cookie.
session["signing.context"] = [
    amount   : idTokenClaims.txn_amount,
    payee    : idTokenClaims.txn_payee,
    acr      : idTokenClaims.acr,
    sub      : idTokenClaims.sub,
    timestamp: System.currentTimeMillis()
]

// Redirect browser back to the original API endpoint.
// The browser will re-send the original request, now with the session
// cookie containing the signing context.
def originalUri = session.remove("signing.original_uri") ?: "/api/payments"
return new Response(Status.FOUND)
    .tap { headers.put("Location", originalUri as String) }
```

> **Control flow:** The `return new Response(Status.FOUND)` sends a 302
> redirect response to the browser.  `next.handle()` is never called —
> there is nothing to forward to the backend at this point.  The browser
> follows the redirect back to `/api/payments`, which re-enters the filter
> chain from the top.  This time, the StepUpFilter finds the signing context
> in the session and lets the request through to `JwtBuilderFilter` and
> then to the backend.

#### JWT Production for Backend — JwtBuilderFilter

The signing context in the session needs to reach the backend as a **signed
JWT**.  PingGateway's built-in `JwtBuilderFilter` does this through pure
route configuration — no Groovy or Java code:

```json
{
  "comment": "Build signed JWT with identity + signing context",
  "type": "JwtBuilderFilter",
  "config": {
    "template": {
      "sub": "${contexts.oauth2.accessToken.info.sub}",
      "roles": "${contexts.oauth2.accessToken.info.roles}",
      "scopes": "${contexts.oauth2.accessToken.scopes}",
      "txn": "${session['signing.context']}",
      "iat": "${now.epochSeconds}",
      "jti": "${contexts.transactionId.transactionId.value}"
    },
    "secretsProvider": "JwtSigningKeyProvider"
  }
}
```

The template expression `${session['signing.context']}` reads the signing
context directly from the session — the same data written by the
CallbackFilter.  The `JwtBuilderFilter` produces a signed JWT and injects
it into the context chain as `JwtBuilderContext`.  A downstream
`HeaderFilter` sets the JWT as the `X-Identity-JWT` request header sent
to the backend.

> **Key advantage over PingAccess:** In PA, the built-in JWT identity
> mapping cannot read `SessionStateSupport` — you'd need a custom Java
> plugin.  In PG, `JwtBuilderFilter` reads **any** expression source
> (session, OAuth2, request, attributes) natively.  Both Approach A and
> Approach B produce a signed JWT for the backend without custom code.

#### Complete Route Configuration

The full filter chain for a protected API route:

```json
{
  "handler": {
    "type": "Chain",
    "config": {
      "filters": [
        {
          "type": "ScriptableFilter",
          "config": {
            "type": "application/x-groovy",
            "file": "StepUpFilter.groovy"
          }
        },
        {
          "type": "JwtBuilderFilter",
          "config": {
            "template": {
              "sub": "${contexts.oauth2.accessToken.info.sub}",
              "txn": "${session['signing.context']}"
            },
            "secretsProvider": "JwtSigningKeyProvider"
          }
        },
        {
          "type": "HeaderFilter",
          "config": {
            "messageType": "REQUEST",
            "add": {
              "X-Identity-JWT": ["${contexts.jwtBuilder.value}"]
            }
          }
        }
      ],
      "handler": "ReverseProxyHandler"
    }
  }
}
```

The callback endpoint is a separate route:

```json
{
  "condition": "${matches(request.uri.path, '^/signing-callback')}",
  "handler": {
    "type": "Chain",
    "config": {
      "filters": [
        {
          "type": "ScriptableFilter",
          "config": {
            "type": "application/x-groovy",
            "file": "CallbackFilter.groovy"
          }
        }
      ],
      "handler": "ClientHandler"
    }
  }
}
```

---

## 3. Approach B — Custom Encrypted JWT Cookie (JWE)

### 3.1 Concept

Completely bypass the gateway's session mechanism for the signing context.
After step-up re-authentication via PingFederate, a short-lived **encrypted
JWT** (JWE — JSON Web Encryption) is issued and set as a **separate cookie**
(not the web session cookie).

The cookie value is a **JWE compact serialization** — the claims are encrypted
using the gateway's own key.  The contents are **not readable** by the browser,
by JavaScript, or by any party that does not hold the decryption key.  Only
the gateway can decrypt and validate the token.

The gateway decrypts the cookie, validates the claims (expiry, subject, `jti`),
and forwards the signing context to the backend (as a re-signed JWT or within
the identity-mapped JWT).  The signing context lives **outside** the session
entirely — it's a standalone, self-contained, encrypted proof.

> **Why JWE, not JWS (signed-only)?**  A signed JWT (JWS) is base64url-encoded
> — anyone can decode and read the claims.  While `HttpOnly` + `Secure` cookie
> flags prevent JavaScript access, the cookie value is still visible in browser
> dev tools, proxy logs, and network captures.  JWE encrypts the payload,
> ensuring **confidentiality** in addition to integrity.  The gateway uses its
> own symmetric key (AES) or asymmetric key (RSA/EC) to encrypt, so only the
> gateway can read the claims.

### 3.2 Flow

```
Browser                  Gateway (PA or PG)        PingFederate         Backend
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (session cookie only)  │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule/Filter]            │                  │
   │                    No signing JWT cookie?         │                  │
   │                         │                         │                  │
   │  ◄─── 401 Challenge ─── │                         │                  │
   │  Location: /pf/authn    │                         │                  │
   │  ?ACR=txn-signing       │                         │                  │
   │  &response_type=code    │                         │                  │
   │  &scope=openid txn      │                         │                  │
   │                         │                         │                  │
   │  ──── OIDC redirect ────────────────────────────► │                  │
   │                         │                     [Re-authenticate]      │
   │  ◄──── code + state ───────────────────────────── │                  │
   │                         │                         │                  │
   │  GET /signing-callback  │                         │                  │
   │  ?code=<code>           │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [CallbackRule/Filter]          │                  │
   │                    exchange code → tokens         │                  │
   │                    extract signing claims         │                  │
   │                    build JWE (encrypted JWT):     │                  │
   │                      encrypt with gateway key:    │                  │
   │                      { amount, payee, acr,        │                  │
   │                        iat, exp (5 min),          │                  │
   │                        sub, jti }                 │                  │
   │                    (opaque to browser)            │                  │
   │                         │                         │                  │
   │  ◄─── 302 + Set-Cookie: ───────────────────────── │                  │
   │       PA_SIGNING_JWE=<jwe>; HttpOnly; Secure;     │                  │
   │       SameSite=Strict; Path=/api; Max-Age=300     │                  │
   │       Location: /api/payments                     │                  │
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  Cookie: PA_SUBJECT=..  │                         │                  │
   │  Cookie: PA_SIGNING_JWE=.. │                      │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule/Filter]            │                  │
   │                    decrypts JWE with              │                  │
   │                    gateway key, then validates:   │                  │
   │                    - decryption successful        │                  │
   │                    - not expired                  │                  │
   │                    - claims match request         │                  │
   │                         │ ──────────────────────────────────────────►│
   │                         │                         │             [Process]
   │  ◄────────────── 200 OK ──────────────────────────────────────────── │
```

### 3.3 Cookie Properties

```http
Set-Cookie: PA_SIGNING_JWE=eyJhbGciOiJSU0EtT0FFUCIsImVuYyI6IkEyNTZHQ00ifQ...;
  HttpOnly;          # not readable by JavaScript
  Secure;            # HTTPS only
  SameSite=Strict;   # CSRF protection
  Path=/api;         # scoped to API paths
  Max-Age=300        # 5-minute TTL
```

The cookie value is a **JWE compact serialization** (5 dot-separated parts):
`header.encryptedKey.iv.ciphertext.tag` — the claims are **not readable**
without the decryption key.

The JWE header and encrypted payload:

```json
// JWE Header (readable — describes the encryption algorithm)
{
  "alg": "RSA-OAEP",        // key encryption algorithm
  "enc": "A256GCM",         // content encryption algorithm
  "kid": "gateway-signing-key-2026"
}

// Encrypted Payload (only readable after decryption by the gateway)
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

**Algorithm options:**

| Key Management (`alg`) | Content Encryption (`enc`) | Key Type | Notes |
|------------------------|---------------------------|----------|-------|
| `RSA-OAEP` | `A256GCM` | RSA (asymmetric) | Standard for gateway-managed RSA keys |
| `dir` | `A256GCM` | AES-256 (symmetric) | Simpler — direct encryption with shared key |
| `A256KW` | `A256GCM` | AES-256 (symmetric) | Key wrapping — allows key rotation |

For gateway-only encryption (no external party needs to decrypt),
**`dir` + `A256GCM`** (direct symmetric encryption) is the simplest
choice — a single AES-256 key, no key wrapping overhead.

### 3.4 PingAccess Implementation

The custom rule decrypts and validates the JWE cookie:

```java
// In handleRequest():
String jweCookie = exchange.getRequest().getHeaders()
    .getCookies().get("PA_SIGNING_JWE");

if (jweCookie == null) {
    // Challenge: return 401 with redirect
    return challenge(exchange);
}

// Decrypt JWE and validate claims (expiry, subject, jti)
try {
    SigningContext ctx = jweDecryptor.decryptAndValidate(jweCookie);
    if (ctx.isExpired()) {
        return challenge(exchange);
    }
} catch (JweDecryptionException e) {
    // Tampered or wrong key — treat as missing
    return challenge(exchange);
}
```

Setting the JWE cookie in the callback:

```java
// In the callback rule — build JWE and set as cookie:
ObjectNode claims = objectMapper.createObjectNode();
claims.put("sub", identity.getSubject());
claims.put("acr", "txn-signing");
claims.put("iat", System.currentTimeMillis() / 1000);
claims.put("exp", System.currentTimeMillis() / 1000 + 300);
claims.put("jti", UUID.randomUUID().toString());
claims.set("txn", signingClaimsNode);  // { amount, payee, currency }

// Encrypt as JWE (e.g., using Nimbus JOSE+JWT or Jose4j)
String jwe = jweEncryptor.encrypt(claims);  // dir+A256GCM or RSA-OAEP+A256GCM

SetCookie signingCookie = new SetCookie("PA_SIGNING_JWE", jwe);
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
// CallbackFilter.groovy — build JWE and set as cookie
import org.forgerock.json.jose.builders.JwtBuilderFactory
import org.forgerock.json.jose.jwe.JweAlgorithm
import org.forgerock.json.jose.jwe.EncryptionMethod

def tokenResponse = http.send(tokenRequest).get()
def claims = extractSigningClaims(tokenResponse)

// Build encrypted JWT (JWE) using ForgeRock commons-jose (on PG classpath)
def jwe = new JwtBuilderFactory()
    .jwe(encryptionKey)                          // gateway's AES-256 or RSA key
    .headers()
        .alg(JweAlgorithm.DIRECT)                // direct key encryption
        .enc(EncryptionMethod.A256GCM)           // AES-256-GCM content encryption
        .done()
    .claims(new JwtClaimsSet([
        sub: claims.sub,
        acr: "txn-signing",
        txn: [amount: claims.amount, payee: claims.payee],
        iat: System.currentTimeMillis().intdiv(1000),
        exp: System.currentTimeMillis().intdiv(1000) + 300,
        jti: UUID.randomUUID().toString()
    ]))
    .build()   // returns compact JWE: header.key.iv.ciphertext.tag

def cookieValue = "PA_SIGNING_JWE=${jwe}; " +
    "HttpOnly; Secure; SameSite=Strict; Path=/api; Max-Age=300"

def redirect = new Response(Status.FOUND)
redirect.headers.put("Location", originalUri)
redirect.headers.add("Set-Cookie", cookieValue)
return redirect
```

PingGateway bundles ForgeRock's `commons-jose` library (and Jose4j/Nimbus),
so JWE encryption is available directly in Groovy ScriptableFilters without
additional dependencies.  The encryption key can be loaded from PG's
`SecretsProvider` or from a keystore configured in `config.json`.

---

## 4. Comparison Matrix

| Dimension | Approach A (Session State) | Approach B (Custom JWT Cookie) |
|-----------|--------------------------|-------------------------------|
| **Session coupling** | Enriches existing session | Independent — separate cookie |
| **Cookie count** | +1 (session state cookie) | +1 (signing JWT cookie) |
| **Tamper-proof** | PA-signed session state cookie | JWE — encrypted + integrity-protected |
| **Confidentiality** | PA-encrypted session state cookie | JWE — claims not readable without key |
| **TTL management** | Manual (timestamp in attributes) | JWT `exp` claim (standard) |
| **Scope control** | Global (all apps on same PA) | Cookie `Path` scoping |
| **One-shot semantics** | Manual `removeAttribute()` | Cookie `Max-Age` + `jti` replay check |
| **Backend integration** | JwtBuilderFilter (PG) or custom IM plugin (PA) | Gateway decrypts JWE, re-signs for backend |
| **Groovy-only (no Java)** | Partial (StepUpRule yes, CallbackRule needs HttpClient) | Same (callback needs HttpClient) |
| **PingGateway Groovy-only** | ✅ Full (http client available in scripts) | ✅ Full |
| **Stateless** | No (PA-managed session state) | Yes (JWE is self-contained) |
| **Portability** | PA/PG-specific session API | JWE can be validated by any party with key |
| **Complexity** | Lower (uses built-in session infra) | Higher (JWE encryption, key management) |

### Recommendation

- **Approach A** is simpler when the gateway already manages sessions and the
  signing context is short-lived.  Ideal for PingAccess environments where
  `SessionStateSupport` is already in use for other purposes.

- **Approach B** is architecturally cleaner: separation of concerns between
  "are you logged in" (session cookie) and "did you authorize this" (JWE cookie).
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
| **3. Custom JWE cookie (Approach B)** | Gateway issues a separate encrypted JWT cookie; decrypts and re-signs for the backend | N/A | ✅ Yes — the JWE IS the signing proof |
| **4. PF token customization** (✅ confirmed) | PingFederate includes signing claims in the access/ID token during step-up | ✅ Yes — built-in JWT mapping includes them automatically | ✅ Yes |

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
  The signing context is in a separate JWE cookie — the gateway decrypts it
  and can re-sign the claims into the backend-facing JWT.  The existing identity
  mapping (JWT or HeaderMapping) continues to work unchanged for the login session.

- **Option 4 (PF token customization) — CONFIRMED AND RECOMMENDED.**
  PingFederate **does** support returning transaction-scoped claims in the
  ID token or access token during step-up authentication.  This is achieved
  through custom OIDC scopes, ACR-dependent claim mappings, or access token
  management policies in PF.

  When PF returns claims like `txn_amount`, `txn_payee`, `acr` in the token,
  these claims land in `Identity.getAttributes()` after PA validates the
  token.  The **built-in JWT identity mapping** then includes them
  automatically in the backend-facing JWT — no custom plugin, no session
  state, no code.

  This is the **simplest path for PingAccess JWT-based backends** because:
  - Zero custom plugins (no `IdentityMappingPlugin`, no custom rules for
    JWT production)
  - StepUpRule only needs to check for the ACR claim in the identity
    attributes (already in the token)
  - The built-in JWT identity mapping handles everything

  **Trade-off:** The transaction claims are in the access token itself,
  which is issued by PF and has PF's token lifetime.  For short-lived
  signing context (5 minutes), you'd need PF to issue a short-lived
  token scoped to the step-up flow.

#### Recommendation for JWT-Based Backends

**Option 4 (PF token customization) is the preferred solution** for
PingAccess when the backend requires signing context in a JWT.  It requires:

- PF claim mapping configuration (one-time setup)
- No custom gateway plugins
- No session state management
- Built-in JWT identity mapping works unchanged

**Approach B (Custom JWE Cookie) is the fallback** when:

- PF cannot be configured to return transaction claims (policy constraint)
- Token lifetime cannot be scoped to the step-up flow
- The signing context must be independent of the OIDC token lifecycle
- Clean separation: login JWT (identity mapping) vs. signing JWE (custom cookie)

**Option 2 (Custom IdentityMappingPlugin) is a last resort** — only needed
if session state must be the source of truth AND it must appear in a single
backend JWT.  With PF token customization confirmed, this scenario is rare.

If the backend insists on a **single JWT** containing both identity and
signing claims and PF cannot include them, Option 2 (custom
`IdentityMappingPlugin`) is the only path under Approach A.

### PingGateway

PingGateway does not have a separate "identity mapping" SPI like PingAccess.
Instead, it provides **`JwtBuilderFilter`** — a built-in, OOTB filter
specifically designed to produce signed (or encrypted) JWTs for backend
consumption.  This is the direct equivalent of PA's JWT identity mapping,
but significantly more powerful.

> **Organizational requirement: backends expect a signed JWT, not plain
> headers.**  Plain headers are a fallback only — the preferred backend
> integration pattern is a signed JWT containing identity + context claims.
> PingGateway's `JwtBuilderFilter` satisfies this requirement natively.

#### JwtBuilderFilter — How It Works

`JwtBuilderFilter` (`o.f.openig.filter.JwtBuilderFilter`) is a standard
PingGateway filter in the request/response chain.  It:

1. **Evaluates a claim template** — a JSON map where each value is a
   PingGateway expression (`${...}`) resolved against the full context
   chain (session, OAuth2, attributes, request, etc.).
2. **Creates a JWT** via a `JwtFactory` SPI — the factory handles
   algorithm selection (RS256, ES256, etc.), key resolution via
   `SecretsProvider`, and key ID (`kid`) injection.
3. **Injects the JWT into the context chain** as a `JwtBuilderContext` —
   a custom context type that downstream filters can access via
   `context.asContext(JwtBuilderContext.class).getJwt()`.
4. **A downstream `HeaderFilter`** reads the built JWT from the context
   and sets it as a request header (e.g., `X-Identity-JWT`).

```java
// Simplified internal flow (from PG SDK source):
public Promise<Response, NeverThrowsException> filter(
        Context context, Request request, Handler next) {
    Bindings bindings = Bindings.bindings(context, request);
    return Expressions.evaluateAsync(template, bindings)
        .thenAsync(evaluated -> {
            return jwtFactory.create((Map) evaluated)
                .thenAsync(jwt -> next.handle(
                    new JwtBuilderContext(context, jwt), request));
        });
}
```

#### Route Configuration Example

A route that produces a signed JWT containing identity claims AND session
state (signing context) for the backend:

```json
{
  "handler": {
    "type": "Chain",
    "config": {
      "filters": [
        {
          "comment": "Build signed JWT with identity + signing context",
          "type": "JwtBuilderFilter",
          "config": {
            "template": {
              "sub": "${contexts.oauth2.accessToken.info.sub}",
              "roles": "${contexts.oauth2.accessToken.info.roles}",
              "scopes": "${contexts.oauth2.accessToken.scopes}",
              "clientId": "${contexts.oauth2.accessToken.info.client_id}",
              "txn": "${session['signing.context']}",
              "iat": "${now.epochSeconds}",
              "jti": "${contexts.transactionId.transactionId.value}"
            },
            "secretsProvider": "JwtSigningKeyProvider"
          }
        },
        {
          "comment": "Inject the built JWT as a request header",
          "type": "HeaderFilter",
          "config": {
            "messageType": "REQUEST",
            "add": {
              "X-Identity-JWT": ["${contexts.jwtBuilder.value}"]
            }
          }
        }
      ],
      "handler": "ReverseProxyHandler"
    }
  }
}
```

**Key points:**

- The `template` map is the claim set.  Each value is a PG expression with
  **full access to all context types**: `${session.*}` (session attributes),
  `${contexts.oauth2.*}` (OAuth2 token info), `${request.*}` (request
  properties), `${contexts.client.*}` (client connection info).
- `${session['signing.context']}` directly reads session state — no custom
  plugin, no Groovy, no Java code.  Pure configuration.
- The `secretsProvider` reference points to a configured `SecretsProvider`
  object (JWKS file, keystore, or PingFederate-managed keys) that handles
  signing.
- The resulting JWT is accessed downstream via the expression
  `${contexts.jwtBuilder.value}` — which returns the compact serialized
  JWT string (`eyJhbGciOi...`).

#### What JwtBuilderFilter Can Access (Expression Sources)

| Expression | Source | Example |
|-----------|--------|--------|
| `${session['key']}` | Session attributes (stateful or JWT cookie) | `${session['signing.context']}` |
| `${contexts.oauth2.accessToken.info.sub}` | OAuth2 token claims | Subject from validated access token |
| `${contexts.oauth2.accessToken.scopes}` | OAuth2 scopes | `["openid", "profile"]` |
| `${contexts.client.remoteAddress}` | Client connection | `192.168.1.100` |
| `${request.uri.path}` | Request URI | `/api/payments/confirm` |
| `${contexts.transactionId.transactionId.value}` | Distributed trace ID | Correlation UUID |
| `${contexts.ssoToken.value}` | PingAM SSO token | AM session ID |
| `${attributes['key']}` | AttributesContext (per-request) | Custom filter output |

This means `JwtBuilderFilter` can produce a JWT containing **any combination**
of identity attributes and session state — all through configuration, no code.

#### Alternative: Groovy ScriptableFilter for JWT

For scenarios requiring more complex logic (conditional claims, computed
values, multi-source merging), a Groovy ScriptableFilter can produce JWTs
programmatically:

```groovy
import org.forgerock.json.jose.builders.JwtBuilderFactory
import org.forgerock.json.jose.jws.JwsAlgorithm

def session = context.asContext(SessionContext.class).session
def oauth2 = context.asContext(OAuth2Context.class).accessToken

def claims = [
    sub   : oauth2.info.sub,
    roles : oauth2.info.roles,
    scopes: oauth2.scopes,
]

// Conditionally add signing context if present
def signingCtx = session["signing.context"]
if (signingCtx) {
    claims.txn = signingCtx
    claims.acr = "txn-signing"
}

// Build and sign JWT (Jose4j / ForgeRock commons-jose on classpath)
def jwt = new JwtBuilderFactory()
    .jws(signingKey)
    .headers().alg(JwsAlgorithm.RS256).done()
    .claims(new JwtClaimsSet(claims))
    .build()

request.headers.put("X-Identity-JWT", jwt)
return next.handle(context, request)
```

This approach gives maximum flexibility but is typically unnecessary — the
declarative `JwtBuilderFilter` template handles most scenarios without code.

#### PingGateway: No Identity Mapping Limitation

Unlike PingAccess, PingGateway has **no abstraction boundary** between
"identity" and "session state" when producing JWTs.  The `JwtBuilderFilter`
template can reference any context in the chain — session, OAuth2,
request attributes — all in the same claim set.

**This means Approach A (Session State Enrichment) works cleanly on
PingGateway even for JWT-based backends**, without custom plugins.  The
signing context written to the session by the CallbackFilter appears
automatically in the next JWT produced by `JwtBuilderFilter`.

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
- **Approach B**: The gateway needs an **encryption key** for the JWE cookie.
  Options: AES-256 symmetric key (`dir` + `A256GCM` — simplest), or RSA/EC
  asymmetric key (`RSA-OAEP` + `A256GCM` — allows key separation).
  Key must be managed and rotated by the gateway.

---

## 8. Implementation Effort Comparison

### PingAccess

| Component | Approach A | Approach B |
|-----------|-----------|-----------|
| StepUpRule | Java plugin (read session state, challenge if absent) | Java plugin (read JWT cookie, validate, challenge if absent) |
| CallbackRule | Java plugin (token exchange, write session state) | Java plugin (token exchange, sign JWT, set cookie) |
| Groovy alternative (StepUpRule) | ✅ Possible | ✅ Possible |
| Groovy alternative (CallbackRule) | ❌ No HttpClient | ❌ No HttpClient |
| Identity mapping change | Custom `IdentityMappingPlugin` if JWT-based backend | None (rule sets headers) |
| Key management | None (session state) or signing key (custom IM plugin) | Gateway encryption key (AES-256 or RSA) |
| Total new plugins | 2 rules (+ 1 IM plugin if JWT backend) | 2 rules + JWT signing |

### PingGateway

| Component | Approach A | Approach B |
|-----------|-----------|-----------|
| StepUpFilter | Groovy ScriptableFilter or Java filter | Groovy ScriptableFilter or Java filter |
| CallbackFilter | Groovy ScriptableFilter (has `http` client) | Groovy ScriptableFilter |
| JWT for backend | `JwtBuilderFilter` (OOTB, config-only) | Custom JWT in CallbackFilter |
| Groovy-only | ✅ Both filters | ✅ Both filters |
| Session config | `JwtSessionManager` in `config.json` | None (custom cookie) |
| Key management | PG `SecretsProvider` (JwtBuilderFilter) | Gateway encryption key or PG keys |
| Total new components | 2 filters + 1 route config (no code) | 2 filters + JWT signing |

---

## 9. JWT Production Comparison: PingAccess vs PingGateway

Both gateways can produce signed JWTs for backends, but the mechanisms and
limitations differ significantly:

| Aspect | PA JWT Identity Mapping | PG `JwtBuilderFilter` |
|--------|------------------------|----------------------|
| **Type** | Identity mapping SPI plugin | Standard filter (OOTB) |
| **Configuration** | Admin UI dropdown (select attributes) | JSON route config with expression templates |
| **Claim source** | `Identity.getAttributes()` only | **Any expression**: session, OAuth2, attributes, request |
| **Session state access** | ❌ Cannot read `SessionStateSupport` | ✅ `${session['signing.context']}` |
| **Custom claims** | Only what's in the PF token | Arbitrary — any expression that resolves |
| **Conditional claims** | ❌ Not supported | ✅ Via Groovy ScriptableFilter fallback |
| **Key management** | PA-internal (not configurable) | `SecretsProvider` (JWKS, keystore, PF-managed) |
| **Signing algorithms** | PA-determined | Configurable (RS256, ES256, etc.) |
| **Code required** | None (built-in) / Java plugin (custom) | None (config-only) / Groovy (complex cases) |
| **Session state in JWT** | ❌ Requires custom `IdentityMappingPlugin` | ✅ Native — `${session['key']}` in template |

> **Key takeaway:** PingGateway's `JwtBuilderFilter` is strictly more powerful
> than PingAccess's JWT identity mapping.  However, with PF confirmed to
> support transaction-scoped claims, PingAccess can also produce JWTs with
> signing context via the built-in JWT identity mapping — as long as PF
> includes those claims in the token.

### Impact on Approach Selection

| Scenario | PingAccess | PingGateway |
|----------|-----------|-------------|
| Backend accepts plain headers | Approach A works (rule sets headers) — fallback only | Approach A works (filter sets headers) — fallback only |
| Backend requires signed JWT | **Option 4 (PF claims)** — built-in JWT mapping works; Approach B as fallback | Approach A works natively via `JwtBuilderFilter`; **both approaches viable** |
| Backend requires single JWT (identity + signing) | **Option 4** (PF claims in token) or custom `IdentityMappingPlugin` | `JwtBuilderFilter` template merges both sources |

---

## 10. Open Questions

1. **PA session state cookie scope**: Is the session state cookie
   automatically shared across all PA applications on the same virtual host,
   or does it require explicit configuration?  Needs live testing.

2. **PA session state size limits**: What is the maximum size of data that
   can be stored in `SessionStateSupport`?  The session state cookie has
   browser size limits (~4 KB).

3. ~~**PF transaction-scoped claims**~~: **RESOLVED — YES.** PingFederate
   supports returning custom claims (transaction amount, payee) in the
   ID token or access token for step-up flows, via custom OIDC scopes
   and ACR-dependent claim mappings.  This simplifies both approaches,
   particularly for PingAccess (see §6, Option 4).

4. **Groovy `ResponseBuilder` access**: Can Groovy scripts in PA actually
   call `ResponseBuilder.newInstance()`?  The static factory uses
   `ServiceFactory` internally, which may behave differently in the Groovy
   sandbox.  Needs live testing.

5. **PG OIDC filters**: Can PingGateway's built-in `OAuth2ClientFilter` /
   `SingleSignOnFilter` be configured for step-up flows (custom ACR, scopes),
   or does the entire OIDC flow need to be custom-scripted?

6. **PG `JwtBuilderFilter` claim types**: Does the expression template
   support nested objects (e.g., `txn.amount`, `txn.payee`) or only
   flat string values?  The session object is `Map<String, Object>` so
   nested maps should serialize, but this needs verification.

---

## 11. References

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
| PG SDK Guide — JwtBuilderFilter | `docs/reference/pinggateway-sdk-guide.md` §14, lines 3342–3378 |
| PG SDK Guide — Filter Catalog (JwtBuilderFilter) | `docs/reference/pinggateway-sdk-guide.md` §15, line 4962 |
| PG SDK Guide — ScriptableFilter | `docs/reference/pinggateway-sdk-guide.md` §14, lines 4348–4402 |
| PG SDK Guide — Session Access in Expressions | `docs/reference/pinggateway-sdk-guide.md` §3, lines 873–879 |

---

*Status: COMPLETE — Research covering both session enrichment and custom JWT
approaches for PingAccess and PingGateway step-up authentication*
