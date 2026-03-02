# Step-Up Authentication & Session Enrichment for Transaction Signing

> Investigation date: 2026-03-02  
> Sources: PingAccess 9.0 SDK guide (`docs/reference/pingaccess-sdk-guide.md`),  
> PingGateway 2025.11 SDK guide (`docs/reference/pinggateway-sdk-guide.md`),  
> Groovy scripting research (`docs/research/pingaccess-groovy-payload-rewrite.md`),  
> PingAccess plugin API research (`docs/research/pingaccess-plugin-api.md`),  
> RFC 9470 (OAuth 2.0 Step-Up Authentication Challenge Protocol),  
> RFC 9396 (OAuth 2.0 Rich Authorization Requests)

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
2. **API-first** — the frontend (browser or native app) never follows
   server-issued HTTP 302 redirects as a challenge mechanism.  All flows are
   invoked **programmatically** by the client.  The gateway returns structured
   **401** challenges (RFC 9470), and the client decides how to handle
   re-authentication (in-app browser, popup, native auth SDK).
3. **No duplicate API onboarding** — the same backend API operation should not
   require onboarding under two separate application objects.
4. **Cross-operation scope** — the signing context should be usable across
   multiple related API calls (e.g., create draft → confirm → receipt).
5. **Dual trigger** — step-up can be triggered by the **gateway** (static
   policy rule) or by the **backend API** (dynamic, operation-specific).

### PingAccess Architectural Limitation

PingAccess maps **one Web Session per Application**.  An Application has a
single `webSessionId`.  There is no built-in mechanism for two concurrent
session contexts on the same application.

If the only mechanism available were web sessions, the solution would require:

1. Two Web Session objects (login session + signing session).
2. Two Application objects (e.g., `/api` + `/api-sign`), same backend.
3. Duplicate onboarding of every API operation that needs both contexts.

This document explores approaches that **avoid** that limitation.

### PingGateway: No Equivalent Limitation

PingGateway uses a fundamentally different abstraction.  The equivalent
concepts are:

| PingAccess Concept | PingGateway Equivalent | Description |
|--------------------|------------------------|-------------|
| **Application** | **Route** | A JSON config file that matches a request (by URL pattern, virtual host, condition expression) and defines a filter chain + handler.  Each route is roughly analogous to a PA Application. |
| **Web Session** | **Session** (per-route or global) | A session manager (`JwtSessionManager` for stateless, `InMemorySessionManager` for stateful) can be attached to each route independently, or configured globally in `config.json`. |
| **Rule** | **Filter** | Filters are chained in a pipeline; each filter can read/write session state, make outbound HTTP calls, and short-circuit the response. |

**Critical difference: session isolation, not session affinity.**

PingGateway does **not** have the "one Web Session per Application"
constraint.  Each route gets its own **independent session object** when
a request enters it — session state from one route is not inherited by
another route.  This means:

1. **No duplicate onboarding required.**  If you need separate session
   contexts (login vs. signing), you define two routes pointing to the
   same backend.  Each route has its own session automatically — there is
   no administrative overhead of creating paired Application + Web Session
   objects.

2. **Multiple session-like constructs per route.**  A single route can
   use both the built-in `SessionContext` (for long-lived session state)
   and a custom JWE cookie (for ephemeral signing proof) within the same
   filter chain.  There is no architectural constraint preventing this.

3. **Session scope is route-scoped by default.**  In PingAccess,
   `SessionStateSupport` is visible across all applications on the same
   PA instance (because the session state cookie is a PA-level concept).
   In PingGateway, session content is scoped to the processing route — a
   step-up proof written in one route's session is not visible in another
   route's session unless they share a session configuration that
   explicitly supports it.

4. **Routes are cheap.**  Route definitions are lightweight JSON files,
   not heavyweight UI-configured Application objects.  Adding a parallel
   route for signing is trivial.

**Implication for this design:**  Because PG routes have isolated sessions
by default, **Approach A (Session Enrichment)** works even more naturally
on PingGateway than on PingAccess — enriching a route's session with
step-up proof is a simple `session.put(...)` with no risk of polluting
another route's state, and no duplicate API onboarding required.

---

## 2. Protocol: RFC 9470 Step-Up Authentication Challenge

### 2.1 Overview

RFC 9470 defines a standardized mechanism for resource servers to signal that
the user's current authentication is **insufficient** for the requested
operation.  Instead of a custom challenge scheme, the gateway (or backend)
returns a structured **401 Unauthorized** response that tells the client
exactly what authentication level is required.

This project adopts RFC 9470 as the **sole challenge protocol** for step-up
authentication.  All step-up challenges — whether originating from the gateway
or the backend — use this format.

### 2.2 Challenge Flavors

RFC 9470 defines two orthogonal conditions that can trigger a step-up
challenge.  Either or both may be present in a single challenge:

#### Flavor 1: Insufficient Authentication Level (`acr_values`)

The user's current authentication context class (ACR) is too weak for the
requested operation.  For example, the user logged in with password-only,
but the operation requires MFA or transaction signing.

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="insufficient_user_authentication",
  error_description="Transaction signing requires stronger authentication",
  acr_values="urn:example:txn-signing urn:example:mfa"
```

The `acr_values` parameter lists acceptable ACR values in order of preference.
The client includes these in the `acr_values` parameter of the authorization
request to PingFederate/PingAM.

#### Flavor 2: Stale Authentication (`max_age`)

The user's authentication is strong enough in kind but too **old**.  The
operation requires a recent authentication event (e.g., within the last
5 minutes).

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="insufficient_user_authentication",
  error_description="Authentication too old for this operation",
  max_age=300
```

The `max_age` parameter specifies the maximum acceptable age (in seconds)
of the authentication event.  The client includes `max_age=300` in the
authorization request, which forces the IdP to re-authenticate the user
if their session is older than 5 minutes.

#### Combined Challenge

Both conditions can appear in a single challenge:

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="insufficient_user_authentication",
  error_description="Transaction signing requires recent MFA",
  acr_values="urn:example:txn-signing",
  max_age=300
```

### 2.3 Response Body: Authorization Context

The 401 challenge response MAY include a JSON body with additional context
for the client.  This body is **not** part of RFC 9470 — it is an
application-level convention that carries:

- The authorization endpoint URL (so the client knows where to redirect).
- The `authorization_details` (RFC 9396) for transaction-specific context.
- The original request URL (so the callback can redirect back).

```json
{
  "error": "insufficient_user_authentication",
  "error_description": "Transaction signing requires stronger authentication",
  "acr_values": "urn:example:txn-signing",
  "max_age": 300,
  "authorization_endpoint": "https://idp.example.com/as/authorization.oauth2",
  "client_id": "gateway-signing-client",
  "redirect_uri": "https://gateway.example.com/signing-callback",
  "authorization_details": [
    {
      "type": "payment_initiation",
      "instructedAmount": {
        "amount": "1500.00",
        "currency": "EUR"
      },
      "creditorName": "ACME Corp"
    }
  ]
}
```

### 2.4 Integration with RFC 9396 (Rich Authorization Requests)

Transaction context is conveyed using **RFC 9396 `authorization_details`** —
a structured JSON array that describes the specific authorization being
requested.  This replaces custom fields like `txn_amount` or `txn_payee`.

The `authorization_details` flow:

1. **Challenge**: Gateway (or backend) includes `authorization_details` in the
   401 response body.
2. **Authorization request**: Client includes `authorization_details` as a
   parameter in the authorization request to PingFederate/PingAM.
3. **Token**: PingFederate/PingAM includes the approved `authorization_details`
   as a top-level claim in the issued token (ID token or access token).
4. **Validation**: Gateway extracts `authorization_details` from the step-up
   token and persists it as part of the signing proof.

```json
// authorization_details in the authorization request
{
  "authorization_details": [
    {
      "type": "payment_initiation",
      "instructedAmount": {
        "amount": "1500.00",
        "currency": "EUR"
      },
      "creditorName": "ACME Corp",
      "creditorAccount": {
        "iban": "DE89370400440532013000"
      }
    }
  ]
}
```

> **Why `authorization_details` instead of custom claims?**  RFC 9396 is the
> IETF standard for conveying fine-grained authorization context in OAuth 2.0
> flows.  It is recognized by PingFederate (via access token management
> policies) and provides a structured, type-safe format that downstream systems
> can validate without proprietary claim mappings.

---

## 3. Architecture

### 3.1 API-First Design Principles

All flows follow these principles:

1. **401, not 302**: The gateway never returns an HTTP 302 redirect as the
   initial challenge.  It returns a **401 Unauthorized** with a structured
   `WWW-Authenticate` header (RFC 9470) and an optional JSON body.
2. **Client-driven**: The client (browser app or native app) reads the 401
   response programmatically and initiates the OIDC authorization flow.  The
   gateway does not drive browser navigation.
3. **Standard OIDC callback**: After authentication, PingFederate/PingAM
   redirects to the gateway's callback endpoint with an authorization code.
   This is the standard OIDC redirect — it is the IdP redirecting, not the
   gateway challenging.
4. **Cookie-based proof**: The callback handler (gateway rule/filter) exchanges
   the code for tokens, extracts the step-up claims, and persists the proof
   via cookie (session state or JWE).
5. **IdP-agnostic**: All diagrams use "PingFederate/PingAM" — the actual
   authentication mechanism (single-step, multi-step journey, FIDO2, OTP)
   is an IdP concern.  From the gateway's perspective, it is an OAuth 2.0
   authorization code flow with `acr_values` and `authorization_details`.

### 3.2 Trigger Mode 1: Gateway-Triggered (Static Policy)

The gateway rule/filter evaluates the request **before** forwarding to the
backend.  Based on static policy (URL pattern, HTTP method, or configuration),
it determines that step-up authentication is required and returns a 401
challenge directly.

```
Client                   Gateway (PA/PG)          PingFederate/PingAM    Backend
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (existing session      │                         │                  │
   │   cookie)               │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule/Filter]            │                  │
   │                    Static policy: POST /api/      │                  │
   │                    payments requires step-up.     │                  │
   │                    No valid step-up proof?        │                  │
   │                         │                         │                  │
   │  ◄─── 401 Unauthorized  │                         │                  │
   │  WWW-Authenticate:      │                         │                  │
   │    Bearer error=        │                         │                  │
   │    "insufficient_user_  │                         │                  │
   │    authentication",     │                         │                  │
   │    acr_values=          │                         │                  │
   │    "urn:example:        │                         │                  │
   │     txn-signing",       │                         │                  │
   │    max_age=300          │                         │                  │
   │  Body: { authorization_ │                         │                  │
   │    details, endpoint,   │                         │                  │
   │    redirect_uri }       │                         │                  │
   │                         │                         │                  │
   │  (client reads 401,     │                         │                  │
   │   programmatically      │                         │                  │
   │   initiates OIDC flow)  │                         │                  │
   │                         │                         │                  │
   │  ── authorize request ──────────────────────────► │                  │
   │  acr_values=txn-signing │                         │                  │
   │  authorization_details= │                     [Authenticate]         │
   │  [{ type: payment, ...}]│                     (MFA, FIDO2, OTP)      │
   │  ◄──── code + state ───────────────────────────── │                  │
   │                         │                         │                  │
   │  GET /signing-callback  │                         │                  │
   │  ?code=<code>           │                         │                  │
   │  &state=<nonce>         │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [CallbackRule/Filter]          │                  │
   │                    exchanges code → tokens        │                  │
   │                    extracts acr, auth_details     │                  │
   │                    persists proof (A or B)        │                  │
   │                         │                         │                  │
   │  ◄─── 200 OK ────────── │                         │                  │
   │  Set-Cookie: proof      │                         │                  │
   │  Body: { status:        │                         │                  │
   │    "step_up_complete",  │                         │                  │
   │    retry_url:           │                         │                  │
   │    "/api/payments" }    │                         │                  │
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (session cookie +      │                         │                  │
   │   proof cookie/state)   │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule/Filter]            │                  │
   │                    valid step-up proof ✓          │                  │
   │                    validates TTL, acr,            │                  │
   │                    authorization_details ✓        │                  │
   │                         │ ──────────────────────────────────────────►│
   │                         │                         │             [Process]
   │  ◄────────────── 200 OK ──────────────────────────────────────────── │
```

### 3.3 Trigger Mode 2: Backend-Triggered (Response Interception)

The gateway forwards the request to the backend.  The **backend** determines
that step-up is required (based on operation, amount, risk scoring, etc.) and
returns a 401 with the RFC 9470 challenge.  The gateway's response interceptor
inspects the backend response, enriches it with gateway-level context
(authorization endpoint, redirect URI, client ID), and returns the enriched
401 to the client.

```
Client                   Gateway (PA/PG)          Backend API            PingFederate/PingAM
   │                         │                         │                  │
   │  POST /api/payments     │                         │                  │
   │  (existing session)     │                         │                  │
   │ ──────────────────────► │                         │                  │
   │                         │                         │                  │
   │                    [StepUpRule/Filter]            │                  │
   │                    Gateway policy: no step-up     │                  │
   │                    required for this path.        │                  │
   │                    Forwards to backend.           │                  │
   │                         │ ──────────────────────► │                  │
   │                         │                         │                  │
   │                         │              [Backend evaluates:]          │
   │                         │              amount > 1000? → step-up      │
   │                         │                         │                  │
   │                         │ ◄── 401 Unauthorized ── │                  │
   │                         │  WWW-Authenticate:      │                  │
   │                         │    Bearer error=        │                  │
   │                         │    "insufficient_user_  │                  │
   │                         │    authentication",     │                  │
   │                         │    acr_values=          │                  │
   │                         │    "urn:example:        │                  │
   │                         │     txn-signing"        │                  │
   │                         │  Body: {                │                  │
   │                         │    authorization_       │                  │
   │                         │    details: [...]       │                  │
   │                         │  }                      │                  │
   │                         │                         │                  │
   │                    [ResponseInterceptor]          │                  │
   │                    Detects 401 + insufficient_    │                  │
   │                    user_authentication from       │                  │
   │                    backend.  Enriches response:   │                  │
   │                    + authorization_endpoint       │                  │
   │                    + redirect_uri (callback)      │                  │
   │                    + client_id                    │                  │
   │                    Preserves backend's            │                  │
   │                    authorization_details.         │                  │
   │                         │                         │                  │
   │  ◄─── 401 Unauthorized  │                         │                  │
   │  (enriched with gateway │                         │                  │
   │   OIDC parameters)      │                         │                  │
   │                         │                         │                  │
   │  (client handles step-up — same as §3.2)          │                  │
   │  ...                    │                         │                  │
```

#### Response Interception — PingAccess

PingAccess `AsyncRuleInterceptorBase` provides both `handleRequest()` and
`handleResponse()` methods.  A single rule can:

1. Pass the request through in `handleRequest()` (return `CONTINUE`).
2. Inspect the backend response in `handleResponse()`.
3. If the response is 401 with `insufficient_user_authentication`, enrich it
   with gateway context and return the modified response.

```java
@Override
public CompletableFuture<Outcome> handleResponse(Exchange exchange) {
    Response backendResponse = exchange.getResponse();
    if (backendResponse.getStatus() == 401) {
        String wwwAuth = backendResponse.getHeaders()
            .getSingleValue("WWW-Authenticate");
        if (wwwAuth != null && wwwAuth.contains("insufficient_user_authentication")) {
            // Enrich the backend's 401 with gateway OIDC parameters
            enrichStepUpChallenge(exchange, backendResponse);
            return CompletableFuture.completedFuture(Outcome.RETURN);
        }
    }
    return CompletableFuture.completedFuture(Outcome.CONTINUE);
}
```

#### Response Interception — PingGateway

PingGateway filters naturally support response interception via promise
chaining.  The filter calls `next.handle()`, then inspects the response:

```groovy
// StepUpFilter.groovy — response interception mode
return next.handle(context, request).thenOnResult { response ->
    if (response.status == Status.UNAUTHORIZED) {
        def wwwAuth = response.headers.getFirst("WWW-Authenticate")
        if (wwwAuth?.contains("insufficient_user_authentication")) {
            // Enrich backend's 401 with gateway OIDC parameters
            def body = response.entity.json
            body.authorization_endpoint = "https://idp.example.com/as/authorization.oauth2"
            body.client_id = "gateway-signing-client"
            body.redirect_uri = "https://gateway.example.com/signing-callback"
            response.entity.json = body
        }
    }
}
```

### 3.4 Callback Handling

After the client completes authentication with PingFederate/PingAM, the IdP
redirects to the gateway's callback endpoint with an authorization code.  The
gateway's CallbackRule/Filter:

1. Exchanges the authorization code for tokens (server-to-server call to PF/AM
   token endpoint).
2. Extracts the step-up claims: `acr`, `auth_time`, `authorization_details`.
3. Persists the proof using one of two approaches (§4):
   - **Approach A**: Writes to session state (`SessionStateSupport` in PA,
     `Session` in PG).
   - **Approach B**: Creates a JWE cookie.
4. Returns a **200 OK** with a JSON body indicating success and the original
   request URL.  The client retries the original API call programmatically.

```json
// Callback response (API-first — no redirect)
{
  "status": "step_up_complete",
  "retry_url": "/api/payments",
  "proof_expires_in": 300
}
```

The proof cookie (session state cookie or JWE cookie) is set via
`Set-Cookie` in this response.  The client's next API call automatically
includes the cookie.

> **Pitfall: step-up token ≠ web session token.**
>
> PingAccess's `Identity.getAttributes()` reflects the claims from the
> **original login token** — the one that established the web session
> (`PA_SUBJECT` cookie).  When the step-up flow re-authenticates via
> PingFederate/PingAM, the resulting token is a **new, separate token**
> obtained by the CallbackRule's code exchange.  This new token is NOT
> used to replace the web session.
>
> **Consequence:** Even if PingFederate/PingAM returns `acr`,
> `authorization_details`, and `auth_time` in the step-up token, these
> claims do **not** appear in `Identity.getAttributes()` and do **not**
> flow into the built-in JWT identity mapping.  The CallbackRule must
> extract them from the step-up token and write them to
> `SessionStateSupport` (Approach A) or a JWE cookie (Approach B).

---

## 4. Step-Up Proof Persistence

After the callback completes, the step-up proof must persist across requests
so that subsequent API calls can proceed without re-authentication.  Two
approaches are available.

### 4.1 Approach A — Session State Enrichment

Use the gateway's persistent session state mechanism to enrich the existing
login session with the step-up proof.  The proof lives **inside** the
existing session — no second web session, no second application, no duplicate
onboarding.

#### PingAccess: SessionStateSupport API

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
  (e.g., `com.example.stepup.proof`) to avoid collisions.

> Source: `docs/reference/pingaccess-sdk-guide.md` §8, lines 1121–1133

The `ExternalAuthorizationRule` SDK sample uses `SessionStateSupport` to store
per-request authorization results across requests — confirming that session
state writes from rules are a supported, documented pattern.

> Source: `docs/reference/pingaccess-sdk-guide.md` §11, line 1516

#### PingGateway: SessionContext

PingGateway sessions are a simple `Map<String, Object>` interface, accessed
via `SessionContext` in the context chain:

```java
SessionContext sessionCtx = context.asContext(SessionContext.class);
Session session = sessionCtx.getSession();

// Write step-up proof
Map<String, Object> proof = new HashMap<>();
proof.put("acr", "urn:example:txn-signing");
proof.put("auth_time", System.currentTimeMillis() / 1000);
proof.put("authorization_details", authorizationDetails);
proof.put("timestamp", System.currentTimeMillis());
session.put("stepup.proof", proof);

// Read step-up proof
Map<String, Object> proof = (Map<String, Object>) session.get("stepup.proof");
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

For step-up proofs, **stateless JWT sessions** are preferred — the proof is
small, JSON-compatible, and the JWT cookie provides tamper-proof integrity
without server-side state.

### 4.2 Approach B — Custom Encrypted JWT Cookie (JWE)

Completely bypass the gateway's session mechanism for the step-up proof.
After authentication via PingFederate/PingAM, the CallbackRule/Filter creates
a short-lived **encrypted JWT** (JWE) and sets it as a **separate cookie**.

The JWE is encrypted using the gateway's own key.  Only the gateway can
decrypt and validate the token.  The contents are **not readable** by the
browser, by JavaScript, or by any party that does not hold the decryption key.

> **Why JWE, not JWS (signed-only)?**  A signed JWT (JWS) is base64url-encoded
> — anyone can decode and read the claims.  While `HttpOnly` + `Secure` cookie
> flags prevent JavaScript access, the cookie value is still visible in browser
> dev tools, proxy logs, and network captures.  JWE encrypts the payload,
> ensuring **confidentiality** in addition to integrity.

#### Cookie Properties

```http
Set-Cookie: STEPUP_PROOF_JWE=eyJhbGciOiJSU0EtT0FFUCIsImVuYyI6IkEyNTZHQ00ifQ...;
  HttpOnly;          # not readable by JavaScript
  Secure;            # HTTPS only
  SameSite=Strict;   # CSRF protection
  Path=/api;         # scoped to API paths
  Max-Age=300        # 5-minute TTL
```

#### JWE Encrypted Payload

```json
// JWE Header (readable — describes the encryption algorithm)
{
  "alg": "dir",              // direct key encryption (symmetric)
  "enc": "A256GCM",         // AES-256-GCM content encryption
  "kid": "gateway-key-2026"
}

// Encrypted Payload (only readable after decryption by the gateway)
{
  "sub": "user123",
  "acr": "urn:example:txn-signing",
  "auth_time": 1741121377,
  "authorization_details": [
    {
      "type": "payment_initiation",
      "instructedAmount": {
        "amount": "1500.00",
        "currency": "EUR"
      },
      "creditorName": "ACME Corp"
    }
  ],
  "iat": 1741121377,
  "exp": 1741121677,
  "jti": "a1b2c3d4"
}
```

For gateway-only encryption (no external party needs to decrypt),
**`dir` + `A256GCM`** (direct symmetric encryption) is the simplest choice —
a single AES-256 key, no key wrapping overhead.

---

## 5. PingAccess Implementation

### 5.1 Gateway-Triggered StepUpRule

A Java plugin (`AsyncRuleInterceptorBase`) that checks for a valid step-up
proof and returns a RFC 9470 challenge if absent or expired:

```java
@Override
public CompletableFuture<Outcome> handleRequest(Exchange exchange) {
    // Check for existing step-up proof
    // Approach A: check SessionStateSupport
    Identity identity = exchange.getIdentity();
    SessionStateSupport sss = identity.getSessionStateSupport();
    JsonNode proof = sss.getAttributeValue("stepup.proof");

    // Approach B: check JWE cookie
    // String jweCookie = exchange.getRequest().getHeaders()
    //     .getCookies().get("STEPUP_PROOF_JWE");

    if (proof == null || isExpired(proof)) {
        return returnChallenge(exchange);
    }

    // Proof valid — inject authorization_details as header for backend
    exchange.getRequest().getHeaders().add(
        "X-Authorization-Details",
        proof.get("authorization_details").toString());

    return CompletableFuture.completedFuture(Outcome.CONTINUE);
}

private CompletableFuture<Outcome> returnChallenge(Exchange exchange) {
    // Build RFC 9470 challenge response
    String wwwAuth = "Bearer error=\"insufficient_user_authentication\", " +
        "error_description=\"Step-up authentication required\", " +
        "acr_values=\"urn:example:txn-signing\", " +
        "max_age=300";

    ObjectNode body = objectMapper.createObjectNode();
    body.put("error", "insufficient_user_authentication");
    body.put("error_description", "Step-up authentication required");
    body.put("acr_values", "urn:example:txn-signing");
    body.put("max_age", 300);
    body.put("authorization_endpoint", config.getAuthorizationEndpoint());
    body.put("client_id", config.getClientId());
    body.put("redirect_uri", config.getRedirectUri());
    // authorization_details can be added based on request context

    Response challenge = ResponseBuilder
        .newInstance(HttpStatus.UNAUTHORIZED)
        .header("WWW-Authenticate", wwwAuth)
        .header("Content-Type", "application/json")
        .body(body.toString())
        .build();

    exchange.setResponse(challenge);
    return CompletableFuture.completedFuture(Outcome.RETURN);
}
```

### 5.2 Backend-Triggered Response Interception

The same rule (or a dedicated rule) can also intercept 401 responses from
the backend:

```java
@Override
public CompletableFuture<Outcome> handleResponse(Exchange exchange) {
    Response backendResponse = exchange.getResponse();
    if (backendResponse.getStatus() != 401) {
        return CompletableFuture.completedFuture(Outcome.CONTINUE);
    }

    String wwwAuth = backendResponse.getHeaders()
        .getSingleValue("WWW-Authenticate");
    if (wwwAuth == null || !wwwAuth.contains("insufficient_user_authentication")) {
        return CompletableFuture.completedFuture(Outcome.CONTINUE);
    }

    // Backend returned step-up challenge — enrich with gateway OIDC context
    ObjectNode enrichedBody = objectMapper.createObjectNode();

    // Preserve backend's authorization_details if present
    byte[] responseBody = backendResponse.getBody();
    if (responseBody != null && responseBody.length > 0) {
        JsonNode backendBody = objectMapper.readTree(responseBody);
        if (backendBody.has("authorization_details")) {
            enrichedBody.set("authorization_details",
                backendBody.get("authorization_details"));
        }
        if (backendBody.has("acr_values")) {
            enrichedBody.set("acr_values", backendBody.get("acr_values"));
        }
    }

    // Add gateway-level OIDC parameters
    enrichedBody.put("authorization_endpoint", config.getAuthorizationEndpoint());
    enrichedBody.put("client_id", config.getClientId());
    enrichedBody.put("redirect_uri", config.getRedirectUri());

    Response enriched = ResponseBuilder
        .newInstance(HttpStatus.UNAUTHORIZED)
        .header("WWW-Authenticate", wwwAuth)  // preserve backend's header
        .header("Content-Type", "application/json")
        .body(enrichedBody.toString())
        .build();

    exchange.setResponse(enriched);
    return CompletableFuture.completedFuture(Outcome.RETURN);
}
```

### 5.3 CallbackRule

The CallbackRule handles the OIDC callback after the client completes
authentication with PingFederate/PingAM.  It is the most complex rule — it
must exchange the authorization code for tokens, extract step-up claims, and
persist the proof.

> **Critical design point:** The CallbackRule must NOT use PA's built-in OIDC
> callback mechanism (which would overwrite the existing web session).  It is
> a **custom** callback endpoint handled by a rule that calls PingFederate/
> PingAM's token endpoint directly via `HttpClient` (available to async
> rules), then persists the proof.

```java
@Override
public CompletableFuture<Outcome> handleRequest(Exchange exchange) {
    String code = exchange.getRequest().getQueryParameters().get("code");
    String state = exchange.getRequest().getQueryParameters().get("state");

    if (code == null) {
        return returnError(exchange, "missing authorization code");
    }

    // Exchange code for tokens (server-to-server)
    return httpClient.exchangeCode(code, config.getRedirectUri())
        .thenApply(tokenResponse -> {
            // Extract step-up claims from ID token or access token
            JsonNode idTokenClaims = parseJwtClaims(tokenResponse.getIdToken());

            ObjectNode proof = objectMapper.createObjectNode();
            proof.put("sub", idTokenClaims.get("sub").asText());
            proof.put("acr", idTokenClaims.get("acr").asText());
            proof.put("auth_time", idTokenClaims.get("auth_time").asLong());
            if (idTokenClaims.has("authorization_details")) {
                proof.set("authorization_details",
                    idTokenClaims.get("authorization_details"));
            }
            proof.put("timestamp", System.currentTimeMillis());

            // Persist proof — Approach A
            SessionStateSupport sss = exchange.getIdentity()
                .getSessionStateSupport();
            sss.setAttribute("stepup.proof", proof);

            // Or Approach B: build JWE and set as cookie
            // String jwe = jweEncryptor.encrypt(proof);
            // SetCookie cookie = new SetCookie("STEPUP_PROOF_JWE", jwe);
            // cookie.setHttpOnly(true);
            // ...

            // Return 200 with retry URL (API-first — no redirect)
            ObjectNode responseBody = objectMapper.createObjectNode();
            responseBody.put("status", "step_up_complete");
            responseBody.put("retry_url",
                decodeState(state).getOriginalUrl());
            responseBody.put("proof_expires_in", 300);

            Response response = ResponseBuilder
                .newInstance(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(responseBody.toString())
                .build();

            exchange.setResponse(response);
            return Outcome.RETURN;
        });
}
```

### 5.4 Groovy Capabilities (PingAccess)

| Capability | Available? | Notes |
|-----------|:----------:|-------|
| Read `Exchange` | ✅ | `exc` binding |
| Read `Identity` | ✅ | `exc?.identity` |
| Read `SessionStateSupport` | ✅ | `exc?.identity?.getSessionStateSupport()` |
| Write `SessionStateSupport` | ✅ | `.setAttribute(name, jsonNode)` |
| Set request headers | ✅ | `exc?.request?.header?.add(name, value)` |
| Set response (challenge) | ✅ | `exc?.response = ResponseBuilder...build()` |
| Outbound HTTP calls | ❌ | No `HttpClient` injection in Groovy scripts |
| JWT/JWE operations | ❌ | No JWT library access in Groovy sandbox |

**Groovy is appropriate for the StepUpRule** (read-only validation, return
401 challenge) but **not for the CallbackRule** (needs outbound HTTP for
token exchange).

```groovy
// StepUpRule (Groovy) — validate step-up proof, return RFC 9470 challenge
def sss = exc?.identity?.getSessionStateSupport()
def proof = sss?.getAttributeValue("stepup.proof")

if (proof == null) {
    def wwwAuth = 'Bearer error="insufficient_user_authentication", ' +
        'acr_values="urn:example:txn-signing", max_age=300'

    exc?.response = com.pingidentity.pa.sdk.http.ResponseBuilder
        .newInstance(com.pingidentity.pa.sdk.http.HttpStatus.UNAUTHORIZED)
        .header("WWW-Authenticate", wwwAuth)
        .header("Content-Type", "application/json")
        .body('{"error":"insufficient_user_authentication"}')
        .build()
    return
}

// Check TTL (5 minutes)
def timestamp = proof?.get("timestamp")?.asLong()
if (System.currentTimeMillis() - timestamp > 300_000) {
    sss.removeAttribute("stepup.proof")
    // Same 401 challenge as above
    return
}

pass()
```

### 5.5 Identity Mapping Limitation (PA)

PingAccess built-in identity mapping types (`HeaderMapping`, `JWT`,
`WebSessionAccessToken`) read from `Identity.getAttributes()` — they do
NOT read from `SessionStateSupport`.

**Implication:** If the backend needs step-up proof as part of the
identity-mapped JWT, the built-in JWT mapping cannot provide it.  Options:

| Option | Approach | Effort |
|--------|----------|--------|
| **A (recommended)** | StepUpRule itself sets the header via `exchange.getRequest().getHeaders().add(...)` after validating proof | Zero — rule already has access |
| **B** | Custom `IdentityMappingPlugin` that reads `SessionStateSupport` | Medium — new plugin SPI |
| **C** | Use Approach B (JWE cookie) — gateway decrypts and re-signs for backend | Medium — JWE + JWT key management |

Option A is simplest: the validation rule already reads the proof, so it can
inject `authorization_details` as a header in the same pass.  No custom
identity mapping plugin needed.

---

## 6. PingGateway Implementation

PingGateway's architecture is **significantly more flexible** than PingAccess.
Unlike PA's rule-based model, PingGateway uses a **filter chain** where each
filter is a first-class component that can read/write sessions, make outbound
HTTP calls, and produce responses.  The entire step-up flow can be implemented
in Groovy — no Java plugins required.

### 6.1 Gateway-Triggered StepUpFilter

```groovy
// StepUpFilter.groovy — RFC 9470 challenge + proof validation
def session = context.asContext(SessionContext.class).session

def proof = session["stepup.proof"]
if (proof == null) {
    // No step-up proof — return RFC 9470 challenge.
    // next.handle() is NOT called — the request never reaches the backend.
    def body = [
        error: "insufficient_user_authentication",
        error_description: "Step-up authentication required",
        acr_values: "urn:example:txn-signing",
        max_age: 300,
        authorization_endpoint: "https://idp.example.com/as/authorization.oauth2",
        client_id: "gateway-signing-client",
        redirect_uri: "https://gateway.example.com/signing-callback"
    ]

    // Save original request URI for the callback
    session["stepup.original_uri"] = request.uri.toASCIIString()

    def response = new Response(Status.UNAUTHORIZED)
    response.headers.put("WWW-Authenticate",
        'Bearer error="insufficient_user_authentication", ' +
        'acr_values="urn:example:txn-signing", max_age=300')
    response.headers.put("Content-Type", "application/json")
    response.entity.json = body
    return response
}

// Validate TTL (5 minutes)
def timestamp = proof.timestamp as long
if (System.currentTimeMillis() - timestamp > 300_000) {
    session.remove("stepup.proof")
    // Same 401 challenge as above (omitted for brevity)
    return new Response(Status.UNAUTHORIZED)
}

// Proof valid — forward to backend
return next.handle(context, request)
```

### 6.2 Backend-Triggered Response Interception

```groovy
// StepUpResponseInterceptor.groovy — enrich backend 401 challenges
return next.handle(context, request).thenOnResult { response ->
    if (response.status == Status.UNAUTHORIZED) {
        def wwwAuth = response.headers.getFirst("WWW-Authenticate")
        if (wwwAuth?.contains("insufficient_user_authentication")) {
            // Enrich with gateway OIDC parameters
            def body = response.entity.json ?: [:]
            body.authorization_endpoint =
                "https://idp.example.com/as/authorization.oauth2"
            body.client_id = "gateway-signing-client"
            body.redirect_uri =
                "https://gateway.example.com/signing-callback"

            // Save original URI for callback
            def session = context.asContext(SessionContext.class).session
            session["stepup.original_uri"] = request.uri.toASCIIString()

            response.entity.json = body
        }
    }
}
```

### 6.3 CallbackFilter

```groovy
// CallbackFilter.groovy — handle OIDC callback, persist step-up proof
def session = context.asContext(SessionContext.class).session

// Extract authorization code from the callback query parameters
def code = request.uri.query?.split("&")
    ?.collectEntries { it.split("=", 2).with { [(it[0]): it[1]] } }
    ?.get("code")

if (!code) {
    return new Response(Status.BAD_REQUEST)
        .tap { entity.json = [error: "missing authorization code"] }
}

// Exchange authorization code for tokens using the bound `http` client.
// This is an OUTBOUND call from PingGateway to PingFederate/PingAM.
def tokenRequest = new Request()
    .setMethod("POST")
    .setUri("https://idp.example.com/as/token.oauth2")
tokenRequest.headers.put("Content-Type", "application/x-www-form-urlencoded")
tokenRequest.entity.string = "grant_type=authorization_code" +
    "&code=${code}" +
    "&redirect_uri=https://gateway.example.com/signing-callback" +
    "&client_id=gateway-signing-client" +
    "&client_secret=${globals['client_secret'] ?: 'configured-secret'}"

def tokenResponse = http.send(tokenRequest).get()
def tokens = tokenResponse.entity.json

// Extract step-up claims from the ID token
def idTokenClaims = parseJwtClaims(tokens.id_token)

// Write step-up proof to the session.
session["stepup.proof"] = [
    acr                  : idTokenClaims.acr,
    auth_time            : idTokenClaims.auth_time,
    sub                  : idTokenClaims.sub,
    authorization_details: idTokenClaims.authorization_details,
    timestamp            : System.currentTimeMillis()
]

// Return 200 OK with retry URL (API-first — no redirect)
def originalUri = session.remove("stepup.original_uri") ?: "/api/payments"
def response = new Response(Status.OK)
response.headers.put("Content-Type", "application/json")
response.entity.json = [
    status          : "step_up_complete",
    retry_url       : originalUri,
    proof_expires_in: 300
]
return response
```

### 6.4 JwtBuilderFilter for Backend JWT

PingGateway's built-in `JwtBuilderFilter` can produce a signed JWT for the
backend containing identity + step-up proof — all through configuration, no
code:

```json
{
  "comment": "Build signed JWT with identity + step-up proof",
  "type": "JwtBuilderFilter",
  "config": {
    "template": {
      "sub": "${contexts.oauth2.accessToken.info.sub}",
      "roles": "${contexts.oauth2.accessToken.info.roles}",
      "scopes": "${contexts.oauth2.accessToken.scopes}",
      "acr": "${session['stepup.proof']['acr']}",
      "authorization_details": "${session['stepup.proof']['authorization_details']}",
      "iat": "${now.epochSeconds}",
      "jti": "${contexts.transactionId.transactionId.value}"
    },
    "secretsProvider": "JwtSigningKeyProvider"
  }
}
```

The template expression `${session['stepup.proof']}` reads the proof
directly from the session.  No custom plugin, no Groovy — pure configuration.

> **Key advantage over PingAccess:** In PA, the built-in JWT identity
> mapping cannot read `SessionStateSupport` — you'd need a custom Java
> plugin.  In PG, `JwtBuilderFilter` reads **any** expression source
> (session, OAuth2, request, attributes) natively.

#### What JwtBuilderFilter Can Access (Expression Sources)

| Expression | Source | Example |
|-----------|--------|--------|
| `${session['key']}` | Session attributes | `${session['stepup.proof']}` |
| `${contexts.oauth2.accessToken.info.sub}` | OAuth2 token claims | Subject |
| `${contexts.oauth2.accessToken.scopes}` | OAuth2 scopes | `["openid"]` |
| `${request.uri.path}` | Request URI | `/api/payments` |
| `${contexts.transactionId.transactionId.value}` | Trace ID | Correlation UUID |
| `${attributes['key']}` | AttributesContext (per-request) | Custom values |

### 6.5 Complete Route Configuration

Protected API route:

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
              "authorization_details": "${session['stepup.proof']['authorization_details']}"
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

Callback endpoint:

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

## 7. Comparison Matrix

| Dimension | Approach A (Session State) | Approach B (Custom JWE Cookie) |
|-----------|--------------------------|-------------------------------|
| **Session coupling** | Enriches existing session | Independent — separate cookie |
| **Cookie count** | +1 (session state cookie) | +1 (signing JWE cookie) |
| **Tamper-proof** | Gateway-signed session state cookie | JWE — encrypted + integrity-protected |
| **Confidentiality** | Gateway-encrypted session state cookie | JWE — claims not readable without key |
| **TTL management** | Manual (timestamp in attributes) | JWT `exp` claim (standard) |
| **Scope control** | Global (all apps on same PA) | Cookie `Path` scoping |
| **One-shot semantics** | Manual `removeAttribute()` | Cookie `Max-Age` + `jti` replay check |
| **Backend integration** | JwtBuilderFilter (PG) or custom IM plugin (PA) | Gateway decrypts JWE, re-signs for backend |
| **PingGateway Groovy-only** | ✅ Full | ✅ Full |
| **PA Groovy (StepUpRule)** | ✅ (read-only proof validation) | ✅ (read JWE cookie, validate) |
| **PA Groovy (CallbackRule)** | ❌ (needs HttpClient for token exchange) | ❌ (needs HttpClient) |
| **Stateless** | No (gateway-managed session state) | Yes (JWE is self-contained) |
| **Portability** | PA/PG-specific session API | JWE can be validated by any party with key |
| **Complexity** | Lower (uses built-in session infra) | Higher (JWE encryption, key management) |

### Trigger Mode Comparison

| Dimension | Gateway-Triggered | Backend-Triggered |
|-----------|------------------|-------------------|
| **Decision point** | Gateway rule (static policy) | Backend API (dynamic, per-operation) |
| **Latency** | Lower (no backend round-trip) | Higher (backend must be called first) |
| **Flexibility** | Limited to URL/method patterns | Full business logic (amount, risk, etc.) |
| **Backend coupling** | None — backend unaware | Backend must return RFC 9470 challenge |
| **Gateway complexity** | StepUpRule in `handleRequest()` | ResponseInterceptor in `handleResponse()` |
| **Combinable** | ✅ Both modes can coexist in the same rule |

### Platform Comparison: JWT Production

| Aspect | PA JWT Identity Mapping | PG `JwtBuilderFilter` |
|--------|------------------------|----------------------|
| **Claim source** | `Identity.getAttributes()` only | **Any expression**: session, OAuth2, attributes |
| **Session state access** | ❌ Cannot read `SessionStateSupport` | ✅ `${session['stepup.proof']}` |
| **Custom claims** | Only what's in the PF/AM token | Arbitrary — any expression that resolves |
| **Code required** | Java plugin (custom IM) | None (config-only) |
| **Session state in JWT** | ❌ Requires custom `IdentityMappingPlugin` | ✅ Native — `${session['key']}` in template |

---

## 8. Security Considerations

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
- **Approach B**: Include a `jti` claim in the JWE.  Maintain a short-lived
  replay cache (in-memory set with TTL matching JWT expiry).

### Key Management

- **Approach A**: PA/PG manages the session cookie signing keys.  No
  additional key management.
- **Approach B**: The gateway needs an encryption key for the JWE cookie.
  Options: use a gateway-managed symmetric key (AES-256), or an asymmetric
  keypair (RSA/EC).  Key rotation must be planned.

### RFC 9470 Challenge Integrity

The 401 challenge response body (containing `authorization_details`,
`authorization_endpoint`, `redirect_uri`) is sent over HTTPS, providing
transport-level integrity.  However, the client SHOULD validate that the
`authorization_endpoint` matches a pre-configured trusted IdP URL to prevent
challenge injection attacks.

---

## 9. Implementation Effort

### PingAccess

| Component | Approach A | Approach B |
|-----------|-----------|-----------| 
| StepUpRule (gateway-triggered) | Java or Groovy — reads session state, returns 401 | Java or Groovy — reads JWE cookie, returns 401 |
| ResponseInterceptor (backend-triggered) | Java — enriches backend 401 | Java — enriches backend 401 |
| CallbackRule | Java (needs HttpClient for token exchange) | Java (needs HttpClient + JWE encryption) |
| Groovy alternative (StepUpRule) | ✅ Possible | ✅ Possible |
| Groovy alternative (CallbackRule) | ❌ No HttpClient | ❌ No HttpClient |
| Identity mapping | Custom `IdentityMappingPlugin` for JWT backends | Rule sets headers directly |
| Key management | None (session state) | Gateway encryption key |
| Total new plugins | 2–3 rules (+ 1 IM plugin if JWT backend) | 2–3 rules + JWE key management |

### PingGateway

| Component | Approach A | Approach B |
|-----------|-----------|-----------| 
| StepUpFilter (gateway-triggered) | Groovy ScriptableFilter | Groovy ScriptableFilter |
| ResponseInterceptor (backend-triggered) | Groovy ScriptableFilter | Groovy ScriptableFilter |
| CallbackFilter | Groovy (has `http` client) | Groovy (has `http` + JWE via Jose4j) |
| JWT for backend | `JwtBuilderFilter` (OOTB, config-only) | Custom JWT in filter |
| Groovy-only | ✅ All filters | ✅ All filters |
| Key management | PG `SecretsProvider` (JwtBuilderFilter) | PG `SecretsProvider` or keystore |
| Total new components | 2–3 filters + 1 route config (no Java) | 2–3 filters + JWE key management |

---

## 10. Groovy Capabilities Summary

### PingAccess Groovy Script Rules

| Capability | Available? | Notes |
|-----------|:----------:|-------|
| Read `Exchange` | ✅ | `exc` binding |
| Read `Identity` | ✅ | `exc?.identity` |
| Read `SessionStateSupport` | ✅ | `exc?.identity?.getSessionStateSupport()` |
| Write `SessionStateSupport` | ✅ | `.setAttribute(name, jsonNode)` |
| Read `OAuthTokenMetadata` | ✅ | `exc?.identity?.getOAuthTokenMetadata()` |
| Set request headers | ✅ | `exc?.request?.header?.add(name, value)` |
| Set response (challenge) | ✅ | `exc?.response = ResponseBuilder...build()` |
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
| JWE creation | ✅ | ForgeRock `commons-jose` library on classpath |
| Session expressions | ✅ | `${session["stepup.proof"]}` in route config |
| `globals` (cross-request state) | ✅ | `ConcurrentHashMap` for caching (e.g., JWKS) |

**Key difference:** PingGateway's Groovy scripting is a full-featured filter
model with HTTP client and JWT/JWE library access.  PingAccess Groovy scripts
are more limited — they have Exchange access but no outbound capabilities.

---

## 11. Open Questions

1. **PA session state cookie scope**: Is the session state cookie
   automatically shared across all PA applications on the same virtual host,
   or does it require explicit configuration?  Needs live testing.

2. **PA session state size limits**: What is the maximum size of data that
   can be stored in `SessionStateSupport`?  The session state cookie has
   browser size limits (~4 KB).

3. **PF/AM `authorization_details` support**: Does PingFederate support
   RFC 9396 `authorization_details` in authorization requests and token
   responses?  PF supports token exchange (RFC 8693), but RAR support
   needs verification.

4. **Groovy `ResponseBuilder` access**: Can Groovy scripts in PA actually
   call `ResponseBuilder.newInstance()`?  The static factory uses
   `ServiceFactory` internally, which may behave differently in the Groovy
   sandbox.  Needs live testing.

5. **PG OIDC filters**: Can PingGateway's built-in `OAuth2ClientFilter` /
   `SingleSignOnFilter` be configured for step-up flows (custom ACR, scopes,
   `authorization_details`), or does the entire OIDC flow need to be
   custom-scripted?

6. **PG `JwtBuilderFilter` claim types**: Does the expression template
   support nested objects (e.g., `authorization_details` array) or only
   flat string values?  The session object is `Map<String, Object>` so
   nested maps should serialize, but this needs verification.

7. **Backend 401 passthrough**: When the backend returns 401 with
   `insufficient_user_authentication`, does PingAccess's built-in session
   enforcement intercept it before the response processing rule fires?
   The rule execution order matters.

8. **PG cross-route session sharing**: If two PG routes (e.g., `/api`
   and `/api-sign`) use the same `JwtSessionManager` with the same
   encryption key, do they share session state via the same cookie?  Or
   does each route always build a fresh session object regardless?  This
   matters for the parallel-route scenario where the signing route needs
   to see the step-up proof written by the callback route.  Needs live
   testing.

---

## 12. References

| Source | Path |
|--------|------|
| RFC 9470 | OAuth 2.0 Step-Up Authentication Challenge Protocol |
| RFC 9396 | OAuth 2.0 Rich Authorization Requests |
| PA SDK Guide — SessionStateSupport | `docs/reference/pingaccess-sdk-guide.md` §8, lines 1121–1133 |
| PA SDK Guide — Identity | `docs/reference/pingaccess-sdk-guide.md` §8, lines 1110–1119 |
| PA SDK Guide — IdentityMapping | `docs/reference/pingaccess-sdk-guide.md` §16, lines 2135–2177 |
| PA SDK Guide — ExchangeProperty | `docs/reference/pingaccess-sdk-guide.md` §5, lines 894–911 |
| PA SDK Guide — Thread Safety (ExternalAuthorizationRule) | `docs/reference/pingaccess-sdk-guide.md` §11, line 1516 |
| PA Groovy Research | `docs/research/pingaccess-groovy-payload-rewrite.md` |
| PA Plugin API Research | `docs/research/pingaccess-plugin-api.md` lines 265–270 |
| PG SDK Guide — SessionContext & Session | `docs/reference/pinggateway-sdk-guide.md` §3, lines 726–870 |
| PG SDK Guide — Session Types | `docs/reference/pinggateway-sdk-guide.md` §3, lines 827–855 |
| PG SDK Guide — JwtBuilderFilter | `docs/reference/pinggateway-sdk-guide.md` §14, lines 3342–3378 |
| PG SDK Guide — ScriptableFilter | `docs/reference/pinggateway-sdk-guide.md` §14, lines 4348–4402 |
| PG SDK Guide — Session Access in Expressions | `docs/reference/pinggateway-sdk-guide.md` §3, lines 873–879 |

---

*Status: COMPLETE — Research covering RFC 9470 step-up authentication challenge
protocol with gateway-triggered and backend-triggered modes, session state
enrichment and custom JWE cookie approaches, RFC 9396 authorization_details
integration, for PingAccess and PingGateway.*
