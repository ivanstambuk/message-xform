# HATEOAS/HAL for Journey-Agnostic Authentication Transforms

> **Status**: Research Spike — exploratory analysis
>
> **Created**: 2026-03-02
>
> **Author**: AI (research session)
>
> **Context**: Can HATEOAS links be added to PingAM authentication responses
>   without embedding journey-specific business logic in the gateway layer?

---

## 1. Problem Statement

The current platform transforms (`am-auth-response-v2.yaml`,
`am-webauthn-response.yaml`) contain **journey-specific logic** inside the
gateway layer:

| Spec | Journey-specific knowledge embedded |
|------|------------------------------------|
| `am-auth-response-v2` | Knows about `NameCallback` → "username", `PasswordCallback` → "password", specific `cb_type()` / `cb_name()` mapping |
| `am-webauthn-response` | Knows about `TextOutputCallback` containing `navigator.credentials`, regex extraction of `challenge`, `rpId`, `userVerification` from embedded JavaScript |
| Profile `match.when` | Body-predicate routing knows WebAuthn callbacks have `navigator.credentials` in `TextOutputCallback` |

If a new journey is created (e.g., Device Binding, KBA, Social Login, MFA OTP),
the gateway transforms need updating. The transforms are tightly coupled to
the journey shape.

**Goal**: Explore architectures where the gateway transform layer is **fully
journey-agnostic** — it transforms any PingAM callback response into a clean
API surface with HATEOAS `_links` without knowing which journey is active.

---

## 2. What Would HATEOAS Look Like?

### Current response (journey-specific transforms)

```json
{
  "fields": [
    { "name": "username", "type": "text", "prompt": "User Name" },
    { "name": "password", "type": "password", "prompt": "Password" }
  ]
}
```

### Target response (journey-agnostic with HAL)

```json
{
  "stage": "DataStore1",
  "callbacks": [
    {
      "type": "text",
      "name": "username",
      "prompt": "User Name",
      "inputKey": "IDToken1"
    },
    {
      "type": "password",
      "name": "password",
      "prompt": "Password",
      "inputKey": "IDToken2"
    }
  ],
  "_links": {
    "self": { "href": "/api/v1/auth/login" },
    "submit": {
      "href": "/api/v1/auth/login",
      "method": "POST",
      "title": "Submit credentials"
    },
    "cancel": {
      "href": "/api/v1/auth/login?_action=cancel",
      "method": "POST",
      "title": "Cancel authentication"
    }
  },
  "_actions": {
    "submit": {
      "type": "submit",
      "description": "Submit the callback values to continue the journey"
    },
    "register-passkey": {
      "type": "webauthn-register",
      "description": "Register a new passkey"
    }
  }
}
```

The `_links` and `_actions` tell the client **what it can do next** — without
the client needing to hard-code journey flows.

---

## 3. The Core Challenge

PingAM's callback responses contain:

| Field | Content | Journey knowledge? |
|-------|---------|-------------------|
| `authId` | JWT session token | ❌ Generic |
| `stage` | Node stage name (e.g., `DataStore1`) | ⚠️ Node-specific but usable as opaque label |
| `template` | UI template hint | ❌ Generic (usually empty) |
| `callbacks[]` | Array of callback objects | ✅ **Journey-specific** structure |

The callback array is where journey-specific knowledge lives. A
`NameCallback` always looks the same, but **what callbacks appear together**,
what they **mean** in context (e.g., "this TextOutputCallback contains a
WebAuthn script"), and **what the user can do next** — all of that is implicit
in the journey tree design, not explicit in the response.

**The critical missing piece**: PingAM does not include any "next actions" or
HATEOAS metadata in its callback responses. The response says "here are the
callbacks" but never says "here are your options after this step."

This leads to a fundamental question: **where should journey knowledge live?**

```
┌─────────────────────────────────────────────────────────────────┐
│ Current Architecture                                            │
│                                                                  │
│  PingAM ── raw callbacks ──► Gateway ── journey-specific ──► Client
│                               transforms                         │
│                               (knowledge HERE)                   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Proposed: Knowledge at Source                                    │
│                                                                  │
│  PingAM ── enriched callbacks ──► Gateway ── generic ──► Client  │
│  (knowledge HERE via             structural                      │
│   Scripted Decision Nodes)       mapping only                    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Proposed: Knowledge at Client                                    │
│                                                                  │
│  PingAM ── raw callbacks ──► Gateway ── generic ──► Client       │
│                               structural             (knowledge  │
│                               mapping only            HERE)      │
└─────────────────────────────────────────────────────────────────┘
```

The three solutions below correspond to these three positions.

---

## 4. Solution 1: AM-Side Enrichment via MetadataCallback ⭐

> **Recommended approach** — journey knowledge stays at the source (PingAM),
> gateway becomes a generic structural mapper.

### How it works

Add a **Scripted Decision Node** at strategic points in the journey tree that
injects a `MetadataCallback` with HATEOAS-style `_links` and `_actions`.
The gateway layer then performs only generic structural mapping — no journey
knowledge needed.

### PingAM Scripted Decision Node capabilities

From the vendor docs (PingAM 8, §Scripted Decision node API):

| Binding | Purpose | Relevance |
|---------|---------|-----------|
| `callbacksBuilder` | Create callbacks programmatically | ✅ Can emit `metadataCallback(data)` |
| `nodeState` | Read/write shared/transient state | ✅ Can read what journey stage we're in |
| `action.goTo()` | Set outcome + chain methods | ✅ `withStage(stage)` sets stage name |
| `requestHeaders` | Access HTTP request headers | ✅ Can condition on request path |
| `idRepository` | Access user profile | ✅ Can check user capabilities |

**Key API**: `callbacksBuilder.metadataCallback(data)` — injects arbitrary
JSON as a `MetadataCallback` into the response.

### Example: Scripted Decision Node script

```javascript
// Journey: Login with optional passkey
// Place this node right before the Page Node that collects credentials

var username = nodeState.get("username");
var hasPasskey = false;

if (username) {
  try {
    var identity = idRepository.getIdentity(username);
    var devices = identity.getAttributeValues("boundDevices");
    hasPasskey = devices && devices.length > 0;
  } catch (e) {
    // User not found yet — no passkey info available
  }
}

var metadata = {
  "_links": {
    "self": { "href": "/api/v1/auth/login" },
    "submit": {
      "href": "/api/v1/auth/login",
      "method": "POST",
      "title": "Submit credentials"
    }
  },
  "_actions": {
    "submit": {
      "type": "submit",
      "description": "Submit credentials to continue"
    }
  }
};

if (hasPasskey) {
  metadata._links["passkey"] = {
    "href": "/api/v1/auth/passkey",
    "method": "POST",
    "title": "Sign in with passkey"
  };
  metadata._actions["passkey"] = {
    "type": "webauthn-auth",
    "description": "Authenticate with a registered passkey"
  };
}

callbacksBuilder.metadataCallback(metadata);
action.goTo("true");
```

### What AM returns with enrichment

```json
{
  "authId": "eyJ0...",
  "stage": "DataStore1",
  "callbacks": [
    {
      "type": "NameCallback",
      "output": [{"name": "prompt", "value": "User Name"}],
      "input": [{"name": "IDToken1", "value": ""}]
    },
    {
      "type": "PasswordCallback",
      "output": [{"name": "prompt", "value": "Password"}],
      "input": [{"name": "IDToken2", "value": ""}]
    },
    {
      "type": "MetadataCallback",
      "output": [{
        "name": "data",
        "value": {
          "_links": {
            "self": { "href": "/api/v1/auth/login" },
            "submit": { "href": "/api/v1/auth/login", "method": "POST" },
            "passkey": { "href": "/api/v1/auth/passkey", "method": "POST" }
          },
          "_actions": {
            "submit": { "type": "submit" },
            "passkey": { "type": "webauthn-auth" }
          }
        }
      }]
    }
  ]
}
```

### Generic gateway transform (JSLT)

This single spec replaces all journey-specific transforms:

```yaml
id: am-auth-response-generic
version: "2.0.0"
description: "Journey-agnostic AM callback transform with HATEOAS extraction"

transform:
  lang: jslt
  expr: |
    let metadata_cbs = [for (.callbacks)
      .output[0].value
        if (.type == "MetadataCallback")]
    let metadata = if (size($metadata_cbs) > 0) $metadata_cbs[0] else null

    let user_callbacks = [for (.callbacks)
      {
        "type": if (.type == "NameCallback") "text"
                else if (.type == "PasswordCallback") "password"
                else if (.type == "TextInputCallback") "text"
                else if (.type == "ChoiceCallback") "choice"
                else if (.type == "ConfirmationCallback") "confirm"
                else if (.type == "HiddenValueCallback") "hidden"
                else if (.type == "BooleanAttributeInputCallback") "boolean"
                else if (.type == "TextOutputCallback") "display"
                else lowercase(.type),
        "prompt": if (.output) trim(.output[0].value) else .type,
        "inputKey": if (.input) .input[0].name else null
      }
      if (.type != "MetadataCallback")]

    if (.callbacks)
      {
        "_authId": .authId,
        "stage": .stage,
        "callbacks": $user_callbacks
      }
      + if ($metadata) $metadata else {}
    else if (.tokenId)
      {
        "_tokenId": .tokenId,
        "authenticated": true,
        "realm": .realm,
        "_links": {
          "profile": { "href": "/api/v1/user/profile" },
          "logout": { "href": "/api/v1/auth/logout", "method": "POST" }
        }
      }
    else
      .
```

**Key insight**: The callback-type-to-UI-type mapping (NameCallback → "text",
PasswordCallback → "password") is **structural knowledge**, not journey
knowledge. It's a static mapping of PingAM callback classes to generic UI
input types. This mapping is stable across all journeys and would only change
if PingAM adds new callback types (which is rare).

The `_links` and `_actions` — the journey-specific "what can you do next"
data — comes from AM itself via `MetadataCallback`. The gateway just extracts
and promotes it.

### Assessment

| Aspect | Rating |
|--------|--------|
| Journey-agnosticism | ✅ High — gateway knows callback types (structural) but not journey logic |
| HATEOAS richness | ✅ High — full control, AM has all context to build accurate links |
| Gateway complexity | ✅ Low — single generic transform spec replaces multiple journey-specific ones |
| AM maintenance | ⚠️ Medium — must add Scripted Decision Node to each journey |
| WebAuthn compat | ⚠️ Unclear — still needs special handling unless AM pre-extracts ceremony data |

---

## 5. Solution 2: Generic Gateway Mapping (No HATEOAS)

> Simplest gateway — but pushes journey knowledge to the client.

### How it works

Replace the current journey-specific transforms with a single **generic**
transform that maps ALL PingAM callback types to a universal schema. No
HATEOAS links — just structural cleanup.

### Transform spec

```yaml
id: am-auth-generic
version: "1.0.0"

transform:
  lang: jslt
  expr: |
    if (.callbacks)
      {
        "_authId": .authId,
        "stage": .stage,
        "callbacks": [for (.callbacks)
          {
            "type": lowercase(.type),
            "outputs": .output,
            "inputs": .input
          }
        ]
      }
    else if (.tokenId)
      {
        "_tokenId": .tokenId,
        "authenticated": true,
        "realm": .realm
      }
    else
      .
```

### Assessment

| Aspect | Rating |
|--------|--------|
| Journey-agnosticism | ✅ Full — zero journey knowledge in gateway |
| HATEOAS richness | ❌ None — client must know the journey flow |
| Gateway complexity | ✅ Minimal — trivial transform spec, never changes |
| AM maintenance | ✅ None — no AM changes needed |
| WebAuthn compat | ❌ Problem — raw TextOutputCallback with embedded JS still arrives |

**Verdict**: Solves the journey-agnosticism problem but **doesn't deliver
HATEOAS**. The client still needs journey flow knowledge. Useful as a first
step — clean API surface without enrichment.

---

## 6. Solution 3: Hybrid (AM Enrichment + Generic Gateway)

> The recommended long-term architecture — combines Solutions 1 and 2.

### How it works

1. **AM side**: Scripted Decision Nodes inject `MetadataCallback` with
   `_links`, `_actions`, and any journey-specific enrichment (e.g.,
   pre-extracted WebAuthn ceremony data so the gateway doesn't need regex).

2. **Gateway side**: A single generic transform spec that:
   - Maps callback types to UI types (static, structural)
   - Extracts `MetadataCallback` and promotes `_links`/`_actions` to top level
   - Moves `authId` to `X-Auth-Session` header
   - Strips internal fields

3. **Works with any gateway** — PingAccess (message-xform plugin or Groovy
   Script Rule), PingGateway (Groovy ScriptableFilter or message-xform
   standalone), or standalone proxy.

### WebAuthn: the hard case

Every other PingAM callback sends data as structured fields — `output[0].value`
is a prompt string, `input[0].name` is a field key. A generic mapper just
reads those fields and moves on.

WebAuthn is different. The WebAuthn Registration/Authentication node doesn't
send ceremony data (challenge, rpId, userVerification) as structured JSON.
Instead, it embeds a **literal JavaScript snippet** inside a
`TextOutputCallback`:

```json
{
  "type": "TextOutputCallback",
  "output": [{
    "name": "message",
    "value": "var options = { publicKey: { challenge: new Int8Array([-3, 65, 13, ...]).buffer, rp: { name: \"platform.local\" }, user: { name: \"demo\", id: new Int8Array([100, 101, 109, 111]).buffer, displayName: \"demo\" }, pubKeyCredParams: [{ type: \"public-key\", alg: -7 }, { type: \"public-key\", alg: -257 }], timeout: 60000, attestation: \"none\", authenticatorSelection: { userVerification: \"required\" } } }; navigator.credentials.create(options).then(function(credential) { ... });"
  }]
}
```

That `value` field is **JavaScript source code**, not data. To extract the
actual WebAuthn parameters (challenge, rpId, userVerification, timeout), the
current `am-webauthn-response.yaml` transform has to **parse JavaScript with
regex** — matching patterns like `challenge: new Int8Array([...])` and
`userVerification: "..."` out of a code string.

This breaks the generic mapping pattern in two ways:

1. **Indistinguishable from normal text.** A `TextOutputCallback` with a
   WebAuthn script looks identical (same callback type) to one that just shows
   the user a message. The only way to tell them apart is to look *inside*
   the value and check for `navigator.credentials` — which is journey-specific
   knowledge.

2. **Requires code parsing, not field mapping.** Even once identified,
   extracting structured ceremony data from a JavaScript string requires
   regex or a JS parser — not a simple `output[0].value` read.

**Possible workaround — AM-side pre-extraction**: If a Scripted Decision Node
could extract the ceremony data and re-emit it as a `MetadataCallback`, the
gateway would never see the raw JavaScript:

```javascript
// AM-side WebAuthn enrichment script (Scripted Decision Node)
var ceremonyData = {
  "_links": {
    "self": { "href": "/api/v1/auth/passkey" },
    "submit": { "href": "/api/v1/auth/passkey", "method": "POST" }
  },
  "webauthn": {
    "type": "webauthn-register",
    "challenge": "base64url-encoded-challenge",
    "rpId": "platform.local",
    "userVerification": "required",
    "timeout": 60000
  }
};
callbacksBuilder.metadataCallback(ceremonyData);
action.goTo("true");
```

**⚠️ Limitation**: The Scripted Decision Node runs **before** or **after**
other nodes in the tree, not **inside** them. The WebAuthn node generates
callbacks internally — a Scripted Decision Node placed before it doesn't have
the WebAuthn ceremony data yet, and one placed after it would see the node's
outcome, not its callbacks.

**Practical approach**: Accept that WebAuthn is the one callback type that
cannot be made journey-agnostic. Keep a WebAuthn-specific transform as the
sole exception alongside the generic one. All other journeys use the generic
transform; only WebAuthn needs its own.

---

## 7. Implementation Options

Solutions 1–3 answer *what* to transform and *where* the knowledge lives.
This section answers *how* to implement the gateway-side transform. These
options are orthogonal — any of them can be used with any solution above.

### Option A: message-xform (YAML + JSLT)

This is the project's existing approach. The generic callback mapping from
Solutions 1 and 2 is expressed as a declarative YAML+JSLT transform spec.

| Aspect | Detail |
|--------|--------|
| Deployment on PA | `MessageTransformRule` plugin (compiled JAR in `deploy/`) |
| Deployment on PG | Standalone HTTP proxy alongside PingGateway |
| Language | JSLT (declarative, JSON-native) |
| Testability | ✅ Unit tests via `TransformEngine` |
| Portability | ✅ Runs on PA, PG, and standalone |

This is the **gateway-agnostic** option — a single spec works everywhere.

### Option B: PingAccess Groovy Script Rule (OOTB)

PingAccess has a **built-in Groovy Script Rule** — no custom plugin or JAR
deployment required. Created via the Admin UI (Access → Rules → Add Rule →
Type: "Groovy Script"), the script has full access to the `Exchange` object.

**Key objects available** (from PingAccess 9.0 docs, §Groovy in PingAccess):

| Object | Access pattern | Capabilities |
|--------|---------------|-------------|
| Exchange | `exc` | Request + response lifecycle |
| Request | `exc?.request` | URI, method, headers, body |
| Response | `exc?.response` | Status, headers, body (null during request phase) |
| Headers | `exc?.response?.header` | `add()`, `getFirstValue()`, `removeFields()`, `setContentType()` |
| Body | `exc?.response?.body` | `getContent()` (byte[]), `getLength()` |
| Logger | `exc?.log` | `info()`, `debug()`, `warn()`, `error()` |
| Properties | `exc?.setProperty(k,v)` | Per-request key-value storage |

**⚠️ Matcher requirement**: PA Groovy scripts must end with a matcher
(`pass()` to allow, `fail()` to deny). The script modifies the response
body and headers *before* calling the matcher.

#### Example: generic callback transform as a PA Groovy Rule

```groovy
// PingAccess Rule: AuthCallbackTransform (Type: Groovy Script)
// Created via Admin UI — no JAR deployment needed

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

if (exc?.response) {
    def contentType = exc?.response?.header?.getFirstValue("Content-Type")
    if (contentType?.contains("application/json")) {
        def slurper = new JsonSlurper()
        def body = slurper.parseText(new String(exc?.response?.body?.getContent()))

        if (body.callbacks) {
            def transformed = [
                stage: body.stage,
                callbacks: body.callbacks.collect { cb ->
                    [
                        type: mapType(cb.type),
                        prompt: cb.output?.getAt(0)?.value?.trim() ?: cb.type,
                        inputKey: cb.input?.getAt(0)?.name
                    ]
                }.findAll { it.type != 'metadata' }
            ]

            // Extract MetadataCallback HATEOAS data if present
            def metadataCb = body.callbacks.find { it.type == 'MetadataCallback' }
            if (metadataCb?.output?.getAt(0)?.value) {
                transformed.putAll(metadataCb.output[0].value)
            }

            exc?.response?.header?.add("X-Auth-Session", body.authId)

            def json = JsonOutput.toJson(transformed)
            exc?.response?.body = json.getBytes("UTF-8")
            exc?.response?.header?.setContentLength(json.length())
        }
    }
}

pass()

String mapType(String amType) {
    switch (amType) {
        case 'NameCallback': return 'text'
        case 'PasswordCallback': return 'password'
        case 'TextInputCallback': return 'text'
        case 'ChoiceCallback': return 'choice'
        case 'ConfirmationCallback': return 'confirm'
        case 'HiddenValueCallback': return 'hidden'
        case 'TextOutputCallback': return 'display'
        case 'MetadataCallback': return 'metadata'
        default: return amType.toLowerCase()
    }
}
```

| Aspect | Detail |
|--------|--------|
| Deployment | PA Admin UI (no restart, no JAR) |
| Language | Groovy (dynamic, compiled to JVM bytecode) |
| Testability | ❌ No unit test harness — syntax-validated on save; logic testing requires a running PA instance |
| Portability | ❌ PA-specific APIs (`exc`, `pass()`/`fail()`) — cannot run outside PingAccess |
| HTTP callouts | No managed `HttpClient` (unlike Java plugins); raw `java.net.URL` may work but is unsupported |
| Body write | Manual `byte[]` + `JsonOutput` serialization |
| Caveat | Must end with `pass()`/`fail()` — it's an access control rule, not a pure transformation filter |

**Best for**: Quick prototyping, environments where deploying a custom JAR
is not desirable, or situations where message-xform is not deployed.

### Option C: PingAccess Custom Java Plugin

PingAccess also supports compiled Java plugins deployed as JARs via the
`AsyncRuleInterceptor` SPI. The message-xform `MessageTransformRule` is
itself such a plugin.

```java
// Conceptual — PingAccess AsyncRuleInterceptorBase subclass
public class CallbackTransformRule extends AsyncRuleInterceptorBase {
    @Override
    public Outcome handleResponse(Exchange exchange) {
        JsonNode body = parseJson(exchange.getResponse().getBody());
        if (body.has("callbacks")) {
            // ... same mapping logic as Groovy example ...
            exchange.getResponse().setBody(transformed.toString());
        }
        return Outcome.CONTINUE;
    }
}
```

Building a custom Java plugin for this **reinvents message-xform**. Use this
option only when you need compiled-Java capabilities beyond what Groovy or
JSLT can express (e.g., custom crypto, complex threading, third-party library
dependencies).

| Aspect | Detail |
|--------|--------|
| Deployment | JAR in PA `deploy/` dir (restart required) |
| Language | Java 21 (compiled) |
| Testability | ✅ JUnit + mock Exchange — full unit test coverage possible |
| Portability | ❌ PA-specific SPI (`AsyncRuleInterceptor`) — cannot run outside PingAccess |
| HTTP callouts | ✅ Managed `HttpClient` via `@Inject` |

### Option D: PingGateway ScriptableFilter (Groovy)

PingGateway supports Groovy scripts as filters via `ScriptableFilter`:

```json
{
  "type": "ScriptableFilter",
  "config": {
    "type": "application/x-groovy",
    "file": "AuthCallbackTransform.groovy"
  }
}
```

```groovy
// AuthCallbackTransform.groovy
import groovy.json.JsonSlurper

def slurper = new JsonSlurper()

return next.handle(context, request).thenOnResult { response ->
    if (response.status.code == 200 &&
        response.headers['content-type']?.firstValue?.contains('application/json')) {

        def body = slurper.parseText(response.entity.string)

        if (body.callbacks) {
            def transformed = [
                stage: body.stage,
                callbacks: body.callbacks.findAll { it.type != 'MetadataCallback' }
                    .collect { cb -> [type: mapType(cb.type),
                        prompt: cb.output?.getAt(0)?.value?.trim() ?: cb.type,
                        inputKey: cb.input?.getAt(0)?.name] }
            ]

            def metadataCb = body.callbacks.find { it.type == 'MetadataCallback' }
            if (metadataCb?.output?.getAt(0)?.value) {
                transformed.putAll(metadataCb.output[0].value)
            }

            response.headers.put('X-Auth-Session', body.authId)
            response.entity.json = transformed
        } else if (body.tokenId) {
            response.headers.put('Set-Cookie',
                "iPlanetDirectoryPro=${body.tokenId}; Path=/; HttpOnly; Secure; SameSite=Lax")
            response.entity.json = [authenticated: true, realm: body.realm]
        }
    }
}

String mapType(String t) {
    [NameCallback:'text', PasswordCallback:'password', TextInputCallback:'text',
     ChoiceCallback:'choice', ConfirmationCallback:'confirm',
     HiddenValueCallback:'hidden', TextOutputCallback:'display',
     MetadataCallback:'metadata'].getOrDefault(t, t.toLowerCase())
}
```

| Aspect | Detail |
|--------|--------|
| Deployment | `.groovy` file on disk (hot-reloadable) |
| Language | Groovy (dynamic, compiled to JVM bytecode via JSR-223) |
| Testability | ❌ No standard test harness — logic testing requires a running PG instance |
| Portability | ❌ PG-specific APIs (`next.handle`, `response.entity`) — cannot run outside PingGateway |
| HTTP callouts | ✅ Built-in `http` client handler binding |
| Body write | `response.entity.json = ...` (convenient) |

### Implementation options comparison

| Aspect | message-xform | PA Groovy Rule | PA Java Plugin | PG Groovy Filter |
|--------|--------------|----------------|----------------|------------------|
| Language | JSLT (declarative) | Groovy (dynamic, bytecode) | Java (compiled) | Groovy (dynamic, bytecode) |
| Deployment | JAR (PA) or proxy (PG) | Admin UI (no restart) | JAR + restart | File on disk (hot-reload) |
| Body write | Declarative spec | `byte[]` + JsonOutput | `setBody()` | `entity.json = ...` |
| HTTP callouts | ❌ Not in spec | ❌ No managed client | ✅ `HttpClient` via `@Inject` | ✅ Built-in `http` binding |
| Unit testable | ✅ `TransformEngine` | ❌ E2E only | ✅ JUnit + mock | ❌ E2E only |
| Runs on PA | ✅ Plugin | ✅ OOTB rule | ✅ Custom JAR | ❌ |
| Runs on PG | ✅ Standalone proxy | ❌ | ❌ | ✅ Native |
| Runs standalone | ✅ | ❌ | ❌ | ❌ |
| Custom plugin needed | ✅ (existing) | ❌ | ✅ (new JAR) | ❌ |

---

## 8. Comparison Matrix

| Criterion | Solution 1: AM Enrichment | Solution 2: Generic Gateway | Solution 3: Hybrid |
|-----------|--------------------------|---------------------------|-------------------|
| Journey-agnostic gateway | ✅ | ✅ | ✅ |
| HATEOAS `_links` | ✅ Rich | ❌ None | ✅ Rich |
| AM configuration needed | 🔧 Scripts per journey | ❌ None | 🔧 Scripts per journey |
| PingAccess compatible | ✅ | ✅ | ✅ |
| PingGateway compatible | ✅ | ✅ | ✅ |
| Standalone compatible | ✅ | ✅ | ✅ |
| WebAuthn handling | ⚠️ Complex | ❌ Raw JS | ⚠️ Best-effort |
| Gateway maintenance | ✅ Low | ✅ Minimal | ✅ Low |
| AM maintenance | ⚠️ Per journey | ✅ None | ⚠️ Per journey |
| Testing complexity | ⚠️ E2E required | ✅ Unit testable | ⚠️ E2E required |

---

## 9. PingAM Node Capabilities for Enrichment

### Page Nodes cannot enrich

**Page Nodes cannot enrich callbacks** with HATEOAS metadata. A Page Node
(PingAM's UI grouping mechanism) groups multiple callbacks into a single step
for display but only controls **which callbacks appear together** — not the
callback contents themselves.

To add enrichment, you need a **Scripted Decision Node** placed appropriately
in the tree. The `callbacksBuilder.metadataCallback(data)` API is the only
built-in mechanism for injecting arbitrary JSON into the callback response.

### Alternative: custom AM node (Java plugin)

Instead of a Scripted Decision Node, you can write a **custom Java node** that
implements `TreeNode` and uses the AM node API. This is more complex to deploy
(JAR in AM's classpath) but provides:

- Full Java type safety
- Access to the same bindings (`TreeContext`, `SharedState`, etc.)
- Ability to emit any callback type including `MetadataCallback`
- Better testability (JUnit + mock TreeContext)

A reusable "HATEOAS Enrichment Node" could serve all journeys:

```java
// Conceptual — not production code
@Node.Metadata(outcomeProvider = SingleOutcomeNode.OutcomeProvider.class)
public class HateoasEnrichmentNode extends SingleOutcomeNode {
    @Override
    public Action process(TreeContext context) {
        JsonValue links = buildLinks(context);
        return goToNext()
            .addCallback(new MetadataCallback(links))
            .build();
    }
}
```

---

## 10. Recommendation

**For immediate use**: Solution 2 (generic gateway mapping) is the quickest
win. Replace the current journey-specific transforms with a single generic
spec that does structural callback mapping. No HATEOAS links but the gateway
becomes fully journey-agnostic. WebAuthn callbacks remain as raw
TextOutputCallbacks — clients must handle them.

**For full HATEOAS**: Solution 3 (hybrid) is the right long-term
architecture. AM-side Scripted Decision Nodes inject `MetadataCallback` with
`_links` and `_actions`; the gateway does generic structural mapping +
MetadataCallback extraction. This requires:

1. Per-journey Scripted Decision Node scripts in AM
2. A single generic message-xform transform spec (replaces 2 current specs)
3. A WebAuthn-specific transform that may still need JS regex extraction
   (unless AM pre-extracts ceremony data into MetadataCallback)

**For implementation**: message-xform (Option A) is the recommended
implementation for any solution — it's portable, testable, and declarative.
The PingAccess Groovy Script Rule (Option B) is the best alternative for
quick prototyping or environments where deploying a custom JAR is not
desirable. PingGateway's ScriptableFilter (Option D) is the PG-native
equivalent.

---

## 11. Open Questions

1. **MetadataCallback timing**: Can a Scripted Decision Node that runs
   *after* a Page Node add MetadataCallbacks to that Page Node's callback
   set? Or does each node emit its own callbacks independently? This affects
   whether enrichment can be done as a "decorator" node vs. requiring
   rebuilding the entire callback set.

2. **WebAuthn ceremony data**: Is there an AM-native way to expose WebAuthn
   ceremony data (challenge, rpId, userVerification) without the embedded
   JavaScript TextOutputCallback? The ForgeRock Android/iOS SDKs parse the
   JS on the client side — maybe there's an internal API that exposes the
   raw data.

3. **Scripted Decision Node + Page Node interaction**: When a Scripted
   Decision Node precedes a Page Node, do the Scripted Decision Node's
   callbacks merge with the Page Node's callbacks in the response, or do
   they appear in separate response steps?

4. **`stage` field utility**: The `stage` field already provides a node
   name (e.g., `DataStore1`, `WebAuthn1`). Could this be used as a
   lightweight alternative to HATEOAS — the client uses `stage` to determine
   the UI layout, without needing `_links`?

---

## 12. References

- PingAM 8 vendor docs, §Scripted Decision node API (lines 290011–290495)
- PingAM 8 vendor docs, §callbacksBuilder (lines 291795–291900)
- PingAM 8 vendor docs, `metadataCallback(Object outputValue)` (line 291814)
- PingAM 8 vendor docs, `withStage(String stage)` (lines 290280, 290359)
- PingAccess 9.0 vendor docs, §Groovy in PingAccess (lines 14938–15002)
- PingAccess 9.0 vendor docs, §Groovy Script Examples (lines 16096–16155)
- PingAccess SDK guide, §ConfigurationType.GROOVY (line 476)
- PingGateway SDK guide, §22 Script Extensibility (ScriptableFilter)
- Current transforms: `deployments/platform/specs/am-auth-response-v2.yaml`
- Current profile: `deployments/platform/profiles/platform-am.yaml`
- PingAM authentication API research: `docs/research/pingam-authentication-api.md`
- Platform transform pipeline: `docs/research/message-xform-platform-transforms.md`
