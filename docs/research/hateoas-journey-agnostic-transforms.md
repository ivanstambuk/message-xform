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

## Problem Statement

The current platform transforms (`am-auth-response-v2.yaml`,
`am-webauthn-response.yaml`) contain **journey-specific logic** inside the
gateway layer:

| Spec | Journey-specific knowledge embedded |
|------|------------------------------------|
| `am-auth-response-v2` | Knows about `NameCallback` → "username", `PasswordCallback` → "password", specific `cb_type()` / `cb_name()` mapping |
| `am-webauthn-response` | Knows about `TextOutputCallback` containing `navigator.credentials`, regex extraction of `challenge`, `rpId`, `userVerification` from embedded JavaScript |
| Profile `match.when` | Body-predicate routing knows WebAuthn callbacks have `navigator.credentials` in `TextOutputCallback` |

If a new journey is created (e.g., Device Binding, KBA, Social Login, MFA OTP),
the gateway transforms need updating to handle the new callback types. The
transforms are tightly coupled to the journey shape.

**Goal**: Explore architectures where the gateway transform layer is **fully
journey-agnostic** — it transforms any PingAM callback response into a clean
API surface with HATEOAS `_links` without knowing which journey is active.

---

## HATEOAS/HAL: What Would It Look Like?

### Current Clean Response (journey-specific)

```json
{
  "fields": [
    { "name": "username", "type": "text", "prompt": "User Name" },
    { "name": "password", "type": "password", "prompt": "Password" }
  ]
}
```

### HAL Response with Actions (journey-agnostic)

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

## The Core Challenge

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

---

## Approach A: AM-Side Enrichment via Scripted Decision Node (RECOMMENDED)

### How It Works

Add a Scripted Decision Node at strategic points in the journey tree that
injects a `MetadataCallback` with HATEOAS-style `_links` and `_actions`
into the callback response. The gateway layer then performs only **generic
structural mapping** — no journey knowledge needed.

### PingAM Scripted Decision Node Capabilities

From the vendor docs (PingAM 8, §Scripted Decision node API):

| Binding | Purpose | Relevance |
|---------|---------|-----------|
| `callbacksBuilder` | Create callbacks programmatically | ✅ Can emit `metadataCallback(data)` |
| `nodeState` | Read/write shared/transient state | ✅ Can read what journey stage we're in |
| `action.goTo()` | Set outcome + chain methods | ✅ `withStage(stage)` sets stage name |
| `requestHeaders` | Access HTTP request headers | ✅ Can condition on request path |
| `idRepository` | Access user profile | ✅ Can check user capabilities |

**Key API**: `callbacksBuilder.metadataCallback(data)` — injects arbitrary
JSON as a `MetadataCallback` into the response. This is the mechanism.

### Example Scripted Decision Node Script

```javascript
// Journey: Login with optional passkey
// Place this node right before the Page Node that collects credentials

var username = nodeState.get("username");
var hasPasskey = false;

if (username) {
  // Check if user has registered passkeys
  try {
    var identity = idRepository.getIdentity(username);
    var devices = identity.getAttributeValues("boundDevices");
    hasPasskey = devices && devices.length > 0;
  } catch (e) {
    // User not found yet — no passkey info available
  }
}

// Build HATEOAS metadata
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

// Inject as MetadataCallback
callbacksBuilder.metadataCallback(metadata);

// Continue the journey (this node is a pass-through)
action.goTo("true");
```

### What AM Returns

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

### What the Gateway Transform Does (Journey-Agnostic)

```yaml
# am-auth-response-generic.yaml — journey-agnostic transform spec
id: am-auth-response-generic
version: "2.0.0"
description: "Journey-agnostic AM callback transform with HATEOAS extraction"

transform:
  lang: jslt
  expr: |
    // Extract MetadataCallback(s) — HATEOAS data lives here
    let metadata_cbs = [for (.callbacks)
      .output[0].value
        if (.type == "MetadataCallback")]
    let metadata = if (size($metadata_cbs) > 0) $metadata_cbs[0] else null

    // Generic callback mapping — no journey knowledge needed
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

| Aspect | Rating | Notes |
|--------|--------|-------|
| Journey-agnosticism | ✅ High | Gateway knows callback types (structural) but not journey logic |
| Maintenance burden | ⚠️ Medium | Must add Scripted Decision Node to each journey |
| AM configuration | 🔧 Required | Script creation via AM Admin API or Amster |
| Gateway complexity | ✅ Low | Single generic transform spec replaces multiple journey-specific ones |
| HATEOAS richness | ✅ High | Full control — AM has all context to build accurate links |
| WebAuthn compat | ⚠️ Unclear | WebAuthn callbacks embed JS in TextOutputCallback — still needs special handling for the JS extraction, unless AM enriches the MetadataCallback with pre-extracted ceremony data |

---

## Approach B: Gateway-Side Generic Callback Mapping (No HATEOAS)

### How It Works

Replace the current journey-specific transforms with a single **generic**
transform that maps ALL PingAM callback types to a universal schema. No
HATEOAS links — just structural cleanup.

### Transform Spec

```yaml
# am-auth-generic.yaml — pure structural mapping, no journey knowledge
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

This is maximally generic — it passes through all callback data with only
a thin structural cleanup (lowercase type, consistent naming).

### Assessment

| Aspect | Rating | Notes |
|--------|--------|-------|
| Journey-agnosticism | ✅ Full | Zero journey knowledge in gateway |
| Maintenance burden | ✅ None | Never changes unless PingAM callback format changes |
| AM configuration | ✅ None | No AM changes needed |
| Gateway complexity | ✅ Minimal | Trivial transform spec |
| HATEOAS richness | ❌ None | Client must know the journey flow |
| WebAuthn compat | ❌ Problem | Raw TextOutputCallback with embedded JS still arrives — client must parse it |

**Verdict**: Solves the journey-agnosticism problem but **doesn't deliver
HATEOAS**. The client still needs journey flow knowledge. This is useful as
a first step — clean API surface without enrichment.

---

## Approach C: PingGateway ScriptableFilter (Groovy)

### How It Works

Instead of message-xform, use PingGateway's `ScriptableFilter` with a Groovy
script that intercepts AM responses and applies the same callback mapping.

### PingGateway Config

```json
{
  "type": "ScriptableFilter",
  "config": {
    "type": "application/x-groovy",
    "file": "AuthCallbackTransform.groovy",
    "args": {
      "amBaseUrl": "https://am.platform.local"
    }
  }
}
```

### Groovy Script

```groovy
// AuthCallbackTransform.groovy
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def slurper = new JsonSlurper()

return next.handle(context, request).thenOnResult { response ->
    if (response.status.code == 200 &&
        response.headers['content-type']?.firstValue?.contains('application/json')) {

        def body = slurper.parseText(response.entity.string)

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

            // Move authId to header
            response.headers.put('X-Auth-Session', body.authId)

            response.entity.json = transformed
        } else if (body.tokenId) {
            response.headers.put('Set-Cookie',
                "iPlanetDirectoryPro=${body.tokenId}; Path=/; HttpOnly; Secure; SameSite=Lax")
            response.entity.json = [authenticated: true, realm: body.realm]
        }
    }
}

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

### Assessment

| Aspect | Rating | Notes |
|--------|--------|-------|
| Journey-agnosticism | ✅ High | Same generic mapping as Approach B, with MetadataCallback extraction |
| Maintenance burden | ⚠️ Medium | Groovy script must be maintained separately from message-xform |
| AM configuration | Same as A/B | MetadataCallback enrichment still needs AM-side scripting |
| Gateway complexity | ⚠️ Medium | Groovy is powerful but harder to test than YAML+JSLT |
| PingGateway dependency | ❌ Locked | Only works with PingGateway, not PingAccess or standalone |
| HATEOAS richness | Same as A | Depends on what AM provides via MetadataCallback |

**Note on PingAccess**: PingAccess does **not** have a ScriptableFilter or
Groovy scripting capability. The PingAccess adapter uses the compiled Java
`MessageTransformRule` (our plugin). So this approach is PingGateway-only.

---

## Approach D: Hybrid — AM Enrichment + Generic Gateway Transform

### How It Works

Combine Approach A (AM-side) and Approach B (generic gateway mapping):

1. **AM side**: Scripted Decision Nodes inject `MetadataCallback` with
   `_links`, `_actions`, and any journey-specific enrichment (e.g.,
   pre-extracted WebAuthn ceremony data so the gateway doesn't need regex).

2. **Gateway side**: A single generic transform spec that:
   - Maps callback types to UI types (static, structural)
   - Extracts `MetadataCallback` and promotes `_links`/`_actions` to top level
   - Moves `authId` to `X-Auth-Session` header
   - Strips internal fields

3. **Works with both PingAccess (message-xform) and PingGateway (Groovy or
   message-xform standalone)** — the gateway layer is identical.

### Key Design: WebAuthn Pre-Extraction on AM Side

The biggest current source of journey-specific logic is the WebAuthn
`TextOutputCallback` regex parsing. If AM's Scripted Decision Node extracts
the ceremony data before emitting callbacks, the gateway never sees raw JS:

```javascript
// AM-side WebAuthn enrichment script (Scripted Decision Node)
// Placed before WebAuthn Registration/Authentication node in journey

var callbacks_from_state = nodeState.get("pageCallbacks");
// ... or, this runs AFTER the WebAuthn node has produced its callbacks

// Instead of embedding JS in TextOutputCallback, the script adds a
// MetadataCallback with pre-extracted ceremony data:
var ceremonyData = {
  "_links": {
    "self": { "href": "/api/v1/auth/passkey" },
    "submit": {
      "href": "/api/v1/auth/passkey",
      "method": "POST",
      "title": "Submit passkey attestation"
    }
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

**⚠️ Limitation**: The Scripted Decision Node runs **before** or **after** other
nodes in the tree, not **inside** them. The WebAuthn node generates callbacks
internally — a Scripted Decision Node placed before it doesn't have the
WebAuthn ceremony data yet, and one placed after it would see the node's
outcome, not its callbacks.

**Possible workaround**: Use the Scripted Decision Node to directly emit
the WebAuthn callbacks instead of using the built-in WebAuthn node. This is
a full reimplementation of the WebAuthn ceremony in server-side JavaScript,
which is complex but gives full control over the callback format.

**Simpler alternative**: Accept that WebAuthn callbacks will still need
gateway-side special handling (regex extraction), but use MetadataCallback
for everything else. The WebAuthn transform spec becomes the only
journey-specific transform; all other journeys use the generic one.

---

## Comparison Matrix

| Criterion | A: AM Enrichment | B: Generic Gateway | C: PG Groovy | D: Hybrid |
|-----------|-----------------|-------------------|-------------|-----------|
| Journey-agnostic gateway | ✅ | ✅ | ✅ | ✅ |
| HATEOAS `_links` | ✅ Rich | ❌ None | ✅ If AM provides | ✅ Rich |
| AM configuration needed | 🔧 Scripts per journey | ❌ None | 🔧 Same as A | 🔧 Scripts per journey |
| PingAccess compatible | ✅ | ✅ | ❌ PG only | ✅ |
| PingGateway compatible | ✅ | ✅ | ✅ | ✅ |
| Standalone compatible | ✅ | ✅ | ❌ | ✅ |
| WebAuthn handling | ⚠️ Complex | ❌ Raw JS | ⚠️ Complex | ⚠️ Best-effort |
| Gateway maintenance | ✅ Low | ✅ Minimal | ⚠️ Medium | ✅ Low |
| AM maintenance | ⚠️ Per journey | ✅ None | ⚠️ Per journey | ⚠️ Per journey |
| Testing complexity | ⚠️ E2E required | ✅ Unit testable | ⚠️ E2E required | ⚠️ E2E required |

---

## Key Architectural Decision: Where Does Journey Knowledge Live?

```
┌─────────────────────────────────────────────────────────────────┐
│ Current Architecture                                            │
│                                                                  │
│  PingAM ── raw callbacks ──► Gateway ── journey-specific ──► Client
│                               transforms                         │
│                               (knowledge HERE)                   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Proposed Architecture (Approach A/D)                             │
│                                                                  │
│  PingAM ── enriched callbacks ──► Gateway ── generic ──► Client  │
│  (knowledge HERE via             structural                      │
│   Scripted Decision Nodes)       mapping only                    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Minimal Architecture (Approach B)                                │
│                                                                  │
│  PingAM ── raw callbacks ──► Gateway ── generic ──► Client       │
│                               structural             (knowledge  │
│                               mapping only            HERE)      │
└─────────────────────────────────────────────────────────────────┘
```

The fundamental trade-off:

- **Push knowledge to AM (A/D)**: AM already has all the journey context.
  Enriching callbacks at the source is the most architecturally sound approach.
  But it requires AM administrative changes per journey, and may be complex
  for WebAuthn-style nodes.

- **Push knowledge to client (B)**: The gateway becomes a thin structural
  translator. The client must understand callback types and know what journeys
  are available. Simplest gateway, but most complex client.

- **Current: knowledge in gateway**: Works but scales poorly — each journey
  needs gateway transform changes.

---

## Page Node and MetadataCallback — Can Page Nodes Enrich?

The user's intuition was correct: **Page Nodes cannot enrich callbacks** with
HATEOAS metadata. A Page Node (PingAM's UI grouping mechanism) groups
multiple callbacks into a single step for display purposes, but it only
controls **which callbacks appear together** — not the callback contents.

To add enrichment, you need a **Scripted Decision Node** placed appropriately
in the tree. The `callbacksBuilder.metadataCallback(data)` API is the only
built-in mechanism for injecting arbitrary JSON into the callback response.

### Alternative: Custom Node (Java Plugin)

Instead of a Scripted Decision Node (server-side JavaScript/Groovy), you
can write a **custom Java node** that implements `TreeNode` and uses the
AM node API. This is more complex to deploy (JAR in AM's classpath) but
provides:

- Full Java type safety
- Access to the same bindings (`TreeContext`, `SharedState`, etc.)
- Ability to emit any callback type including `MetadataCallback`
- Better testability (JUnit + mock TreeContext)

A custom "HATEOAS Enrichment Node" could be reusable across journeys:

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

## Recommendation

**For immediate use**: Approach B (generic gateway mapping) is the quickest
win. Replace the current journey-specific transforms with a single generic
spec that does structural callback mapping. No HATEOAS links but the gateway
becomes fully journey-agnostic. WebAuthn callbacks remain as raw
TextOutputCallbacks — clients must handle them.

**For full HATEOAS**: Approach D (hybrid) is the right long-term architecture.
AM-side Scripted Decision Nodes inject `MetadataCallback` with `_links` and
`_actions`; the gateway does generic structural mapping + MetadataCallback
extraction. This requires:

1. Per-journey Scripted Decision Node scripts in AM
2. A single generic message-xform transform spec (replaces 2 current specs)
3. A WebAuthn-specific transform that may still need JS regex extraction
   (unless AM pre-extracts ceremony data into MetadataCallback)

**For the PingGateway Groovy question**: Yes, PingGateway Groovy scripts can
do the same structural mapping as message-xform. The `ScriptableFilter` has
full access to request/response bodies and headers. However:

- Groovy scripts are harder to test (no JSLT unit test framework)
- Groovy is PingGateway-only — not portable to PingAccess or standalone
- message-xform provides a declarative, gateway-agnostic approach

The message-xform JSLT transform is **equivalent in capability** to a
PingGateway Groovy script for this use case, and is portable across all
gateway products.

---

## Open Questions for Follow-Up

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

## References

- PingAM 8 vendor docs, §Scripted Decision node API (lines 290011–290495)
- PingAM 8 vendor docs, §callbacksBuilder (lines 291795–291900)
- PingAM 8 vendor docs, `metadataCallback(Object outputValue)` (line 291814)
- PingAM 8 vendor docs, `withStage(String stage)` (lines 290280, 290359)
- Current transforms: `deployments/platform/specs/am-auth-response-v2.yaml`
- Current profile: `deployments/platform/profiles/platform-am.yaml`
- PingGateway SDK guide, §22 Script Extensibility (ScriptableFilter)
- PingAM authentication API research: `docs/research/pingam-authentication-api.md`
- Platform transform pipeline: `docs/research/message-xform-platform-transforms.md`
