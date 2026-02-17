# PingAM Authentication API — Research Notes

> Source: *PingAM 8 Official Documentation* (`docs/reference/pingam-8.pdf`)
> Extracted: 2026-02-07

---

## Overview

PingAM provides the `/json/authenticate` endpoint for authentication and the `/json/sessions` endpoint for session management / logout. The authentication endpoint is **REST-like** (POST only, no CRUDPAQ verbs).

---

## Endpoint: `/json/authenticate`

**Method:** `POST`

**URL pattern:**
```
https://{host}/am/json/realms/root/realms/{realm}/authenticate
```

Realm hierarchy must be specified in full, with each level prefixed by `realms/`:
```
/realms/root/realms/customers/realms/europe
```

### Query Parameters

| Parameter | Description |
|-----------|-------------|
| `authIndexType` | Type of auth: `service`, `resource`, `composite_advice`, `transaction` |
| `authIndexValue` | Value for the auth type (e.g., tree name) |
| `noSession` | When `true`, AM doesn't return a session token |

### Required Headers

| Header | Value |
|--------|-------|
| `Content-Type` | `application/json` |
| `Accept-API-Version` | `resource=2.0, protocol=1.0` |

---

## Authentication Flow: Simple (Zero-Page Login)

Username and password are sent **in headers**, with an **empty body**:

```bash
curl --request POST \
  --header "Content-Type: application/json" \
  --header "X-OpenAM-Username: bjensen" \
  --header "X-OpenAM-Password: Ch4ng31t" \
  --header "Accept-API-Version: resource=2.0, protocol=1.0" \
  'https://am.example.com:8443/am/json/realms/root/realms/alpha/authenticate'
```

### Success Response

```json
{
  "tokenId": "AQIC5wM…TU3OQ*",
  "successUrl": "/am/console",
  "realm": "/alpha"
}
```

### Success Response (No Session)

When `noSession=true`:
```json
{
  "message": "Authentication Successful",
  "successUrl": "/am/console",
  "realm": "/"
}
```

### Success Response (HttpOnly Cookies enabled)

```json
{
  "tokenId": "",
  "successUrl": "/am/console",
  "realm": "/alpha"
}
```

---

## Authentication Flow: Callback-Based (Complex Journeys)

For complex authentication (MFA, step-up, etc.), AM uses a **callback mechanism**. The flow is:

1. Client sends initial POST (empty body, or with `authIndexType`/`authIndexValue`)
2. AM responds with `authId` + `callbacks` array
3. Client fills in callback input values and POSTs the entire JSON back
4. AM responds with either more callbacks or success

### Step 1: AM Returns Callbacks

```json
{
  "authId": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJvdGsiOiJ…",
  "template": "",
  "stage": "DataStore1",
  "callbacks": [
    {
      "type": "NameCallback",
      "output": [
        {
          "name": "prompt",
          "value": " User Name: "
        }
      ],
      "input": [
        {
          "name": "IDToken1",
          "value": ""
        }
      ]
    },
    {
      "type": "PasswordCallback",
      "output": [
        {
          "name": "prompt",
          "value": " Password: "
        }
      ],
      "input": [
        {
          "name": "IDToken2",
          "value": ""
        }
      ]
    }
  ]
}
```

**Key fields:**
- `authId` — JWT identifying the auth context. Must be returned in every callback response.
- `template` — UI template hint (may be empty)
- `stage` — Current node stage name (e.g., `"DataStore1"`)
- `callbacks[]` — Array of callback objects
  - `type` — Callback class name (e.g., `NameCallback`)
  - `output[]` — Read-only info for display (`name`/`value` pairs)
  - `input[]` — Fields client must fill in (`name`/`value` pairs, value initially empty)

### Step 2: Client Returns Filled Callbacks

POST the entire JSON back with input values filled:

```json
{
  "authId": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJvdGsiOiJ…",
  "template": "",
  "stage": "DataStore1",
  "callbacks": [
    {
      "type": "NameCallback",
      "output": [
        {
          "name": "prompt",
          "value": " User Name: "
        }
      ],
      "input": [
        {
          "name": "IDToken1",
          "value": "bjensen"
        }
      ]
    },
    {
      "type": "PasswordCallback",
      "output": [
        {
          "name": "prompt",
          "value": " Password: "
        }
      ],
      "input": [
        {
          "name": "IDToken2",
          "value": "Ch4ng31t"
        }
      ]
    }
  ]
}
```

### Step 3: Success (or more callbacks)

On success:
```json
{
  "tokenId": "AQIC5wM…TU3OQ*",
  "successUrl": "/am/console",
  "realm": "/alpha"
}
```

On complex journeys, AM may return **additional callbacks** (e.g., MFA, device verification). Each must be completed sequentially.

---

## Callback Types

### Category: Interactive (request information)

| Callback Type | Purpose |
|--------------|---------|
| `BooleanAttributeInputCallback` | Boolean yes/no confirmation |
| `ChoiceCallback` | Select from a list of options |
| `ConfirmationCallback` | Confirm an action |
| `ConsentMappingCallback` | Collect consent for data sharing |
| `DeviceBindingCallback` | Bind a device to user account |
| `DeviceProfileCallback` | Collect device profile data |
| `DeviceSigningVerifierCallback` | Verify device signature |
| `HiddenValueCallback` | Carry hidden values |
| `IdPCallback` | Social IdP selection data |
| `KbaCreateCallback` | Create knowledge-based auth questions |
| `NameCallback` | **Username / text input** |
| `NumberAttributeInputCallback` | Numeric attribute input |
| `PasswordCallback` | **Password input** |
| `PingOneProtectEvaluationCallback` | PingOne risk evaluation signals |
| `PingOneProtectInitializeCallback` | Initialize PingOne Signals SDK |
| `SelectIdPCallback` | Choose social identity provider |
| `StringAttributeInputCallback` | String attribute input (with IDM validation) |
| `TermsAndConditionsCallback` | Accept terms |
| `TextInputCallback` | Generic text input |
| `ValidatedPasswordCallback` | Password with IDM policy validation |
| `ValidatedUsernameCallback` | Username with IDM policy validation |

### Category: Read-only (return information)

| Callback Type | Purpose |
|--------------|---------|
| `MetadataCallback` | Arbitrary JSON metadata |
| `PollingWaitCallback` | Wait interval before retry |
| `RedirectCallback` | Redirect to external URL |
| `SuspendedTextOutputCallback` | Suspended journey text |
| `TextOutputCallback` | Display text message |

### Category: Backchannel (access request data)

| Callback Type | Purpose |
|--------------|---------|
| `HttpCallback` | Access HTTP request headers/parameters |

---

## Canonical Callback JSON Structure

Every callback follows this pattern:

```json
{
  "type": "<CallbackClassName>",
  "output": [
    {
      "name": "<fieldName>",
      "value": "<displayValue>"
    }
  ],
  "input": [
    {
      "name": "IDToken<N>",
      "value": ""
    }
  ]
}
```

**Naming convention for input fields:**
- `IDToken1`, `IDToken2`, etc. — Sequentially numbered within the callback array
- Some callbacks add suffixes: `IDToken1validateOnly`, `IDToken1question`, `IDToken1answer`

---

## Session Token Lifecycle

### After successful authentication

AM returns a `tokenId` — the **session token** (also called SSO token):
- **Server-side sessions**: ~100 bytes reference to CTS token store
- **Client-side sessions**: 2000+ bytes (contains session state)

### Using the session token

Pass the token in subsequent API calls via the **session cookie header**:

```bash
curl --request POST \
  --header "Content-Type: application/json" \
  --header "iPlanetDirectoryPro: AQIC5w…NTcy*" \
  --header "Accept-API-Version: resource=2.0, protocol=1.0" \
  ...
```

**Cookie header name:** `iPlanetDirectoryPro` (default, configurable in AM admin UI)

### Logout

```bash
curl --request POST \
  --header "Content-type: application/json" \
  --header "iPlanetDirectoryPro: AQICS…NzEz*" \
  --header "Accept-API-Version: resource=3.1, protocol=1.0" \
  'https://am.example.com:8443/am/json/realms/root/realms/alpha/sessions/?_action=logout'
```

Response:
```json
{
  "result": "Successfully logged out"
}
```

---

## UTF-8 Support

Usernames/passwords with non-ASCII characters must be base64-encoded per RFC 2047:

```
=?UTF-8?B?yZfDq8mxw7g=?=
```

Passed via `X-OpenAM-Username` / `X-OpenAM-Password` headers.

---

## Backchannel Authentication

AM also supports `/json/authenticate/backchannel` for third-party federation:

### Initialize
```bash
POST /json/realms/root/realms/alpha/authenticate/backchannel/initialize
Authorization: Bearer {oauth2_token_with_back_channel_authentication_scope}

{
  "type": "service",
  "value": "Login",
  "subject": {
    "type": "user",
    "name": "bjensen"
  },
  "trackingId": "Y5tyzQi9cGVJjy2L"
}
```

Response:
```json
{
  "transaction": "b3070138-cd73-4ef2-bd58-812602d7b757",
  "redirectUri": "https://am.example.com:8443/am/UI/Login?realm=/alpha&authIndexType=transaction&authIndexValue=b3070138-cd73-4ef2-bd58-812602d7b757"
}
```

---

## Implications for message-xform

### Primary Transformation Targets

1. **Callback response restructuring** — Transform between PingAM's callback format and downstream/upstream API formats
2. **Header ↔ payload promotion** — `X-OpenAM-Username`/`X-OpenAM-Password` headers to/from JSON body fields
3. **Session token handling** — Extract `tokenId` from response and promote to `iPlanetDirectoryPro` header for subsequent calls
4. **Error format normalization** — Transform PingAM error responses to RFC 9457 Problem Details

### Key Design Observations

- The callback JSON is **deeply nested** and **variable** — different callback types have different output/input field names
- The `authId` JWT must be preserved across the callback round-trip (it's the stateful context identifier)
- Input field names follow a convention (`IDToken1`, `IDToken2`) but some callbacks add suffixes
- The flow is **multi-step**: transformations may need to handle state across multiple request/response pairs
- **Both request and response bodies must be transformable** — the same JSON structure flows in both directions

---

---

## Session Management — API-Native Approach

> Research conducted: 2026-02-16
> Goal: Determine how to issue a session token to the frontend without
> exposing PingAM's raw `tokenId` or requiring browser redirects.

### PingFederate `pi.flow` — NOT Available in PingAM

PingFederate supports `response_mode=pi.flow` for **redirectless OIDC flows**:
```
GET /as/authorization.oauth2?client_id=im_client&response_type=token&response_mode=pi.flow
```
This allows mobile apps and SPAs to complete OIDC flows entirely via REST.

**PingAM does NOT support this.** PingAM 8's supported response modes
(from vendor docs, line 276214):
- `fragment`, `jwt`, `form_post.jwt`, `form_post`, `fragment.jwt`, `query`, `query.jwt`

No `pi.flow` or equivalent redirectless OIDC mode exists.

### Option 1: ROPC Grant (Single-Call OAuth2 Token)

PingAM supports Resource Owner Password Credentials — a direct token endpoint:

```bash
POST /am/oauth2/access_token
  -data "grant_type=password&username=user.1&password=Password1
         &client_id=paClient&client_secret=secret&scope=openid"
```
Returns:
```json
{
  "access_token": "eyJ0...",
  "token_type": "Bearer",
  "expires_in": 3599
}
```

**Pros:** Single call, no AM tokenId visible, standard OAuth2 token.
**Cons:** Deprecated by OAuth2.1. **Cannot support WebAuthn/passkey** — ROPC only
handles simple username/password via a configured auth tree. Multi-step interactive
callbacks are impossible with ROPC.

**Verdict: Rejected** — does not cover passkey flows.

### Option 2: PA Web Session with SPA Support (Full OIDC)

PingAccess SPA Support (vendor docs line 17907-17912):

> *"SPA support merges the conventional 401 unauthorized response of an API
> application with the traditional 302 redirect response of a Web application.
> The SPA supported result is a 401 response containing a JavaScript body that
> can initiate a redirect. API clients will ignore the JavaScript body and react
> appropriately to the 401 response."*

When `SPA Support = Enabled` and `Accept: application/json`:
- GET/POST → **401 + JSON body** (not 302 redirect)
- JSON body contains the OIDC auth URL

The full flow would be:
1. Client hits protected PA resource → PA returns **401 JSON** with OIDC auth URL
2. Client calls AM's REST auth endpoint (callbacks) to authenticate
3. Client exchanges SSO token for auth code (REST, using `iPlanetDirectoryPro` cookie)
4. Client presents auth code to PA's `/pa/oidc/cb` callback endpoint
5. PA creates session (`PA-MH` cookie)

**Requires:**
- AM configured as full OIDC provider (OAuth2 service, client registration, redirect URIs)
- PA OIDC configuration (Token Provider, PF Runtime, OIDC Provider — see existing
  `e2e-pingaccess/src/test/java/e2e/setup/create-oidc-provider.feature`)
- PA Web Session creation (see `create-web-session.feature`)
- Multi-step client orchestration

**Pros:** True PA-issued opaque token. AM tokens fully hidden.
**Cons:** Significant infrastructure. Client must orchestrate 5+ HTTP calls
across different endpoints.

**Verdict: Deferred** — correct long-term architecture, but too much infrastructure
for Phase 8 scope. Can evolve to this later.

### Option 3: Hybrid — REST Auth + Transform-Injected Cookie (CHOSEN)

Message-xform transforms the AM authentication response to:
1. **Strip** `tokenId` from the response JSON body
2. **Inject** `Set-Cookie: iPlanetDirectoryPro=<tokenId>; Path=/; HttpOnly; Secure`
3. Return clean JSON: `{ "authenticated": true, "realm": "/" }`

Subsequent requests carry the cookie automatically. PA + AM session
validation handles authorization on downstream calls.

**Pros:**
- ✅ Works with ALL auth flows (username/password, passkey, identifier-first, usernameless)
- ✅ REST-native — no browser redirects
- ✅ AM token hidden from response body (cookie is HttpOnly, not inspectable by JS)
- ✅ Uses only message-xform's existing capabilities (header injection, body transform)
- ✅ Client code is simple — standard cookie-based session

**Cons:**
- ❌ Session IS technically AM's SSO token (in a cookie), not a PA-issued opaque token
- ❌ Session lifetime tied to AM session config, not PA session config
- ❌ No token refresh mechanism (AM SSO tokens have fixed TTL)

**Verdict: Chosen** — pragmatic, Phase 8 scope, covers all auth flows.
Captured as decision D14 in PLAN.md.

### PingAM 2-Step REST Token Issuance (Reference)

For future Option 2 implementation, here's the documented REST-only pattern
for obtaining OAuth2 tokens from PingAM (vendor docs lines 263591-263677):

**Step 1:** Authenticate → get SSO token:
```bash
POST /am/json/realms/root/realms/alpha/authenticate
  -header "X-OpenAM-Username: bjensen"
  -header "X-OpenAM-Password: Ch4ng31t"
  -header "Accept-API-Version: resource=2.0, protocol=1.0"
→ { "tokenId": "AQIC5wM…TU3OQ*" }
```

**Step 2:** Exchange SSO token → OAuth2 token via `/oauth2/authorize`:
```bash
POST /am/oauth2/realms/root/realms/alpha/authorize
  -cookie "iPlanetDirectoryPro=AQIC5wM…TU3OQ*"
  -data "client_id=myClient&response_type=token&decision=allow&csrf=AQIC5wM…TU3OQ*"
→ HTTP 302 Location: https://...callback#access_token=<token>&...
```

The auth code must be extracted from the Location header's fragment.
This is fully REST-based but requires client-side orchestration of two calls.

---

## `authId` Handling — Header vs Body

### Problem

PingAM's callback protocol requires echoing `authId` (a JWT) in every
callback submission. In the raw API, this is a top-level field in the
request/response JSON body:

```json
{
  "authId": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...",
  "stage": "DataStore1",
  "callbacks": [ ... ]
}
```

This pollutes the clean API surface with protocol-level state.

### Solution (D15)

Move `authId` to a custom response header:

- **Response:** `X-Auth-Session: eyJ0...` (message-xform extracts from body → header)
- **Request:** Client echoes `X-Auth-Session: eyJ0...` (message-xform injects from header → body)

The JSON body becomes clean — only application-level fields (`fields[]`,
`challenge`, `authenticated`, etc.).

### Implementation

Requires **bidirectional** header/body transforms:
1. **Response transform:** `body.authId → header X-Auth-Session` (extract + remove from body)
2. **Request transform:** `header X-Auth-Session → body.authId` (inject into body)
3. Filter: only apply to callbacks (not success responses which have `tokenId`)

---

*Status: COMPLETE — Full authentication REST API + session management research documented*
