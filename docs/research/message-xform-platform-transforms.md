# Message-Xform Platform Transform Pipeline

> **Status**: Reference document — captures the architecture of the transform
> pipeline used in the PingAM platform deployment.
>
> **Last updated**: 2026-02-17 (profile v4.0.0, 7 specs)

## Overview

The platform deployment uses message-xform as a PingAccess processing rule to
create a clean authentication API surface. The pipeline handles both **request-side
URL rewriting** (clean URLs → AM endpoints) and **response-side body transforms**
(AM callbacks → structured JSON).

```
Client                    PingAccess                     PingAM
──────                    ──────────                     ──────

POST /api/v1/auth/passkey
  ──────────────────────►
                          [REQUEST PHASE]
                          1. am-passkey-url spec:
                             path → /am/json/authenticate
                             query += authIndexType=service
                                      authIndexValue=WebAuthnJourney
                             body → unchanged (identity: ".")
                          2. request.setUri(rewritten)
                                ──────────────────────►
                                                        Process journey
                                ◄──────────────────────
                          [RESPONSE PHASE]
                          3. wrapResponse() reads request.getUri()
                             → sees /am/json/authenticate ✓
                          4. match.when body predicate:
                             WebAuthn? → am-webauthn-response
                             Login?    → am-auth-response-v2
                          5. am-strip-internal (chain)
  ◄──────────────────────
  { type: "webauthn-register", challengeRaw: "...", ... }
```

## Critical Architectural Insight: URI Propagation

The PA adapter's `wrapResponse()` (line 100 of `PingAccessAdapter.java`) reads
the request path from `exchange.getRequest().getUri()`:

```java
// PingAccessAdapter.wrapResponse()
String uri = request.getUri();  // ← reads the REWRITTEN uri
```

After `handleRequest()` processes the request transforms, `applyRequestChanges()`
calls `request.setUri(rewrittenUri)`. This means:

1. Request-side spec rewrites `/api/v1/auth/passkey` → `/am/json/authenticate?...`
2. PA forwards to AM at the rewritten URI
3. AM responds
4. Response-side `wrapResponse()` reads `request.getUri()` which is now
   `/am/json/authenticate?...`
5. Response-side `match.path: "/am/json/authenticate"` → **matches** ✓

**This is why a single profile with different request-side and response-side
path patterns works.** The response transforms don't need to know about the
clean URL — they only see the rewritten AM path.

## Transform Spec Catalog

### Request-side (URL rewriting)

| Spec | Incoming path | Rewritten to | Query params |
|------|---------------|-------------|--------------|
| `am-auth-url-rewrite` | `/api/v1/auth/login` | `/am/json/authenticate` | (none) |
| `am-passkey-url` | `/api/v1/auth/passkey` | `/am/json/authenticate` | `authIndexType=service&authIndexValue=WebAuthnJourney` |
| `am-passkey-usernameless-url` | `/api/v1/auth/passkey/usernameless` | `/am/json/authenticate` | `authIndexType=service&authIndexValue=UsernamelessJourney` |

All three use:
- JSLT identity transform (`.`) — body passes through unchanged
- `url.path.expr: '"/am/json/authenticate"'`
- `headers.add: Accept-API-Version: resource=2.0,protocol=1.0`

**Why identity body transform is safe (D9):** Decision D9 restricts request-side
**body** transforms because AM's callback protocol requires `authId` JWT and
callback arrays echoed verbatim. URL rewriting and header injection don't touch
the body, so they're safe.

### Response-side (body transforms)

| Spec | What it does | Condition |
|------|-------------|-----------|
| `am-webauthn-response` | Parses JS → `{type, challengeRaw, rpId, userVerification, timeout}` | TextOutputCallback contains `navigator.credentials` |
| `am-auth-response-v2` | Renames fields, produces `fields[]`, extracts `authId` to header | Everything else |
| `am-strip-internal` | Removes `_`-prefixed fields | Always (chains after body transform) |
| `am-header-inject` | Adds `x-auth-provider`, `x-transform-engine` headers | All `/am/json/**` |

### Body-predicate routing (ADR-0036)

The profile uses `match.when` to choose between WebAuthn and login transforms:

```yaml
# WebAuthn match — checks for navigator.credentials in TextOutputCallback
when:
  lang: jslt
  expr: |
    .callbacks and
      size([for (.callbacks) .
        if (.type == "TextOutputCallback" and
            test(.output[0].value, "navigator\\.credentials"))]) > 0
```

The complementary login match uses `not(.callbacks) or size(...) == 0`.

## PA Application Architecture

Two PA Applications share the same PingAM backend site:

| Application | Context root | Purpose |
|-------------|-------------|---------|
| PingAM Proxy | `/am` | Direct AM access (E2E tests, admin) |
| Auth API | `/api` | Clean URL surface for API consumers |

Both have the MessageTransformRule attached. The profile routes based on path.

**Setup order:**
1. `configure-pa.sh` — creates site + `/am` app
2. `configure-pa-plugin.sh` — creates MessageTransformRule
3. `configure-pa-api.sh` — creates `/api` app + attaches rule

## Profile Version History

| Version | Changes |
|---------|---------|
| 1.0.0 | Initial: `am-auth-response` (v1) + `am-header-inject` |
| 2.0.0 | `am-auth-response-v2` + `am-strip-internal` (ADR-0008 chaining) |
| 3.0.0 | `am-webauthn-response` + `match.when` body predicates (ADR-0036) |
| 4.0.0 | Request-side URL rewriting (3 specs) for clean `/api/v1/auth/*` URLs |
