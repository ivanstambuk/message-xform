# Feature 001 – PingAM Callback Response Prettification

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Linked plan | `docs/architecture/features/001/plan.md` |
| Linked tasks | `docs/architecture/features/001/tasks.md` |
| Roadmap entry | #1 – PingAM Callback Response Prettification |

> Guardrail: This specification is the single normative source of truth for the feature.
> Track questions in `docs/architecture/open-questions.md`, encode resolved answers
> here, and use ADRs under `docs/decisions/` for architectural clarifications.

## Overview

PingAM (formerly ForgeRock AM) exposes its authentication REST API via the
`/json/realms/root/authenticate` endpoint. This endpoint uses a **callback-based
protocol**: responses contain an opaque `authId` and a `callbacks[]` array with
machine-readable callback descriptors (e.g., `NameCallback`, `PasswordCallback`).
While functional, this protocol is **hostile to frontend developers** and API
consumers who expect clean, self-describing JSON payloads.

This feature defines a **bidirectional response transformation** that sits between
PingAM and the API consumer, converting PingAM's callback JSON into a clean,
developer-friendly format on the response path, and converting the consumer's
clean submission back into PingAM's callback format on the request path.

**Affected modules:** core (transformation engine), adapter (gateway-specific wrappers).

## Goals

- G-001-01 – Transform PingAM callback responses into clean, self-describing JSON
  that frontend developers can consume without understanding PingAM internals.
- G-001-02 – Transform clean client requests back into PingAM's callback format
  so PingAM receives validly-structured callback submissions.
- G-001-03 – Preserve the `authId` token transparently across the transformation
  boundary (it is opaque and must be round-tripped).
- G-001-04 – Handle multi-step authentication flows (multiple callback stages)
  without losing state.
- G-001-05 – Remain gateway-agnostic in the core transformation logic; adapters
  bind to specific gateway extension points.

## Non-Goals

- N-001-01 – This feature does NOT implement authentication logic; it only
  transforms the wire format. PingAM remains the identity provider.
- N-001-02 – Session token management (cookie promotion, token caching) is
  deferred to a future feature.
- N-001-03 – Error response transformation is in scope only for well-formed PingAM
  error responses; HTTP-level errors (502, 503) pass through unmodified.
- N-001-04 – Custom callback types (e.g., `PingOneProtectEvaluationCallback`,
  `DeviceProfileCallback`) are deferred; initial scope covers the "standard"
  callbacks listed below.

## Functional Requirements

### FR-001-01: Response Body Transformation (PingAM → Client)

**Requirement:** When PingAM responds with a callback JSON body, the transformer
MUST convert it into a clean JSON structure that:
- Removes the `callbacks[]` wrapper and `IDToken*` naming
- Exposes each callback as a named field with its prompt as context
- Preserves the `authId` in a dedicated top-level field
- Includes a `stage` field identifying the current authentication step
- Includes a `type` field indicating the response type (`challenge`, `success`, `failure`)

**PingAM response (input):**
```json
{
    "authId": "eyJ0eXAiOi...",
    "template": "",
    "stage": "DataStore1",
    "header": "Sign in",
    "callbacks": [
        {
            "type": "NameCallback",
            "output": [
                { "name": "prompt", "value": "User Name:" }
            ],
            "input": [
                { "name": "IDToken1", "value": "" }
            ]
        },
        {
            "type": "PasswordCallback",
            "output": [
                { "name": "prompt", "value": "Password:" }
            ],
            "input": [
                { "name": "IDToken2", "value": "" }
            ]
        }
    ]
}
```

**Prettified response (output):**
```json
{
    "authId": "eyJ0eXAiOi...",
    "type": "challenge",
    "stage": "DataStore1",
    "header": "Sign in",
    "fields": [
        {
            "name": "username",
            "type": "text",
            "label": "User Name:",
            "value": "",
            "required": true
        },
        {
            "name": "password",
            "type": "password",
            "label": "Password:",
            "value": "",
            "required": true
        }
    ]
}
```

| Aspect | Detail |
|--------|--------|
| Success path | PingAM callback JSON → clean JSON with `fields[]` array |
| Validation path | If response body is not valid callback JSON, pass through unmodified |
| Failure path | Malformed JSON → log warning, pass through raw body |
| Source | PingAM 8 REST API, `docs/research/pingam-authentication-api.md` |

### FR-001-02: Request Body Transformation (Client → PingAM)

**Requirement:** When a client submits the prettified format, the transformer MUST
convert it back into PingAM's expected callback submission format, mapping named
fields back to `IDToken*` entries in the `callbacks[]` array.

**Client request (input):**
```json
{
    "authId": "eyJ0eXAiOi...",
    "stage": "DataStore1",
    "fields": [
        { "name": "username", "value": "bjensen" },
        { "name": "password", "value": "Ch4ng31t" }
    ]
}
```

**PingAM request (output):**
```json
{
    "authId": "eyJ0eXAiOi...",
    "template": "",
    "stage": "DataStore1",
    "callbacks": [
        {
            "type": "NameCallback",
            "output": [
                { "name": "prompt", "value": "User Name:" }
            ],
            "input": [
                { "name": "IDToken1", "value": "bjensen" }
            ]
        },
        {
            "type": "PasswordCallback",
            "output": [
                { "name": "prompt", "value": "Password:" }
            ],
            "input": [
                { "name": "IDToken2", "value": "Ch4ng31t" }
            ]
        }
    ]
}
```

| Aspect | Detail |
|--------|--------|
| Success path | Clean JSON with `fields[]` → PingAM callback JSON |
| Validation path | Missing `authId` → reject with 400 and descriptive error |
| Failure path | Field name doesn't match known callback → log warning, attempt best-effort mapping |
| Source | PingAM 8 REST API, `docs/research/pingam-authentication-api.md` |

### FR-001-03: Success Response Transformation

**Requirement:** When PingAM returns a successful authentication (containing
`tokenId` and `successUrl`), the transformer MUST convert it into a clean
success response.

**PingAM success response (input):**
```json
{
    "tokenId": "AQIC5wM2LY4S...==",
    "successUrl": "/openam/console",
    "realm": "/"
}
```

**Prettified success response (output):**
```json
{
    "type": "success",
    "token": "AQIC5wM2LY4S...==",
    "successUrl": "/openam/console",
    "realm": "/"
}
```

| Aspect | Detail |
|--------|--------|
| Success path | `tokenId` presence detected → emit `type: "success"` response |
| Validation path | N/A |
| Failure path | N/A |
| Source | PingAM 8 REST API |

### FR-001-04: Error/Failure Response Transformation

**Requirement:** When PingAM returns an authentication failure, the transformer
MUST convert it into a clean error response.

**PingAM failure response (input):**
```json
{
    "code": 401,
    "reason": "Unauthorized",
    "message": "Authentication Failed"
}
```

**Prettified failure response (output):**
```json
{
    "type": "failure",
    "error": {
        "code": 401,
        "reason": "Unauthorized",
        "message": "Authentication Failed"
    }
}
```

| Aspect | Detail |
|--------|--------|
| Success path | Error JSON → wrapped in `type: "failure"` envelope |
| Validation path | N/A |
| Failure path | Unrecognized error format → pass through with `type: "failure"` wrapper |
| Source | PingAM 8 REST API |

### FR-001-05: Callback Type Mapping

**Requirement:** The transformer MUST map the following PingAM callback types to
clean field types. Unknown callback types MUST be passed through with
`type: "unknown"` and the raw callback data preserved.

| PingAM Callback Type | Prettified Field Type | Notes |
|----------------------|----------------------|-------|
| `NameCallback` | `text` | Single-line text input |
| `PasswordCallback` | `password` | Masked text input |
| `TextInputCallback` | `text` | Single-line text input |
| `TextOutputCallback` | `info` | Display-only text (messageType: 0=info, 1=warning, 2=error) |
| `ConfirmationCallback` | `choice` | Single/multiple choice selection |
| `ChoiceCallback` | `choice` | Dropdown or radio selection |
| `HiddenValueCallback` | `hidden` | Hidden field (e.g., CSRF tokens) |
| `BooleanAttributeInputCallback` | `boolean` | Checkbox/toggle |
| `NumberAttributeInputCallback` | `number` | Numeric input |
| `StringAttributeInputCallback` | `text` | String input with validation |
| `TermsAndConditionsCallback` | `terms` | Terms acceptance (includes terms text + version) |
| `KbaCreateCallback` | `kba` | Knowledge-based auth question setup |
| `SelectIdPCallback` | `idp-select` | Identity provider selection |
| `ValidatedCreatePasswordCallback` | `password` | Password with server-side validation policies |

## Non-Functional Requirements

| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-001-01 | The transformation MUST be stateless per-request — no server-side session or cache is required. | Horizontal scalability; gateway deployments must not require shared state. | Unit tests confirm no mutable static state or cross-request side effects. | None. | Architecture. |
| NFR-001-02 | The core transformation library MUST have zero gateway-specific dependencies. | Gateway-agnostic core enables adapter reuse. | Maven dependency analysis confirms no PingAccess/PingGateway imports in `core`. | Module structure. | `PLAN.md`, SDD principle. |
| NFR-001-03 | Transformation latency MUST be < 5ms for a standard callback response (< 10 callbacks). | Gateway rules are in the critical path; excessive latency is unacceptable. | Microbenchmark with JMH. | Core only. | Performance. |
| NFR-001-04 | Unknown/unrecognized callback types MUST NOT cause transformation failure; they MUST pass through gracefully. | PingAM versions may introduce new callback types. | Test with synthetic unknown callback types. | Core. | Robustness. |
| NFR-001-05 | The `authId` token MUST be treated as opaque binary data — never parsed, decoded, logged, or cached. | `authId` is a signed JWT; tampering or logging it is a security risk. | Code review + static analysis rule. | Core. | Security. |

## Branch & Scenario Matrix

| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-001-01 | Simple username/password callback → clean `fields[]` response |
| S-001-02 | Multi-step flow: first callback returns challenge, second returns success |
| S-001-03 | Client submits clean fields → transformed to PingAM callback format |
| S-001-04 | PingAM returns success with `tokenId` → clean success response |
| S-001-05 | PingAM returns failure → clean error response |
| S-001-06 | Response contains unknown callback type → passed through as `type: "unknown"` |
| S-001-07 | Response is not callback JSON (e.g., HTML error page) → passed through unmodified |
| S-001-08 | ChoiceCallback with multiple options → clean `choice` field with options array |
| S-001-09 | TextOutputCallback (info/warning/error) → clean `info` display field |
| S-001-10 | TermsAndConditionsCallback → clean `terms` field with terms text + version |
| S-001-11 | Mixed callback types in single response → all mapped correctly |
| S-001-12 | Empty callbacks array → clean response with empty `fields[]` |

## Test Strategy

- **Core (unit):** JUnit 5 tests for each callback type mapping, each response type
  (challenge/success/failure), and edge cases (unknown callbacks, malformed JSON, empty
  arrays). Use parameterized tests with vectors from `docs/test-vectors/`.
- **Core (integration):** Round-trip tests: PingAM response → prettify → client
  submission → un-prettify → verify matches expected PingAM request.
- **Adapter:** Per-gateway adapter tests with mock `Exchange`/filter objects that verify
  body replacement via `setBodyContent()` or equivalent.
- **Standalone:** HTTP-level integration tests with a mock PingAM backend.

## Interface & Contract Catalogue

### Domain Objects

| ID | Description | Modules |
|----|-------------|---------|
| DO-001-01 | `CallbackResponse` — parsed PingAM callback response (authId, stage, callbacks[]) | core |
| DO-001-02 | `PrettifiedResponse` — clean response (authId, type, stage, header, fields[]) | core |
| DO-001-03 | `PrettifiedField` — single field descriptor (name, type, label, value, required, options) | core |
| DO-001-04 | `ClientSubmission` — client's clean request (authId, stage, fields[]) | core |
| DO-001-05 | `CallbackSubmission` — PingAM-formatted request (authId, callbacks[]) | core |

### Configuration

| ID | Config key | Type | Description |
|----|-----------|------|-------------|
| CFG-001-01 | `transforms[].path` | string | URL path pattern to apply this transform to (e.g., `/json/*/authenticate`) |
| CFG-001-02 | `transforms[].direction` | enum | `request`, `response`, or `both` |
| CFG-001-03 | `transforms[].type` | string | Transform type identifier (e.g., `pingam-callback-prettify`) |

### Gateway Integration Points

| ID | Gateway | Hook | Description |
|----|---------|------|-------------|
| GW-001-01 | PingAccess | `RuleInterceptor.handleResponse(Exchange)` | Read response body, prettify, replace via `setBodyContent()` |
| GW-001-02 | PingAccess | `RuleInterceptor.handleRequest(Exchange)` | Read request body, un-prettify, replace via `setBodyContent()` |
| GW-001-03 | Standalone | HTTP reverse proxy filter | Same transformation, wired as an HTTP filter in embedded proxy |

### Fixtures & Sample Data

| ID | Path | Purpose |
|----|------|---------|
| FX-001-01 | `docs/test-vectors/pingam-callback-simple.json` | Username/password callback response (input) |
| FX-001-02 | `docs/test-vectors/pingam-callback-prettified.json` | Expected prettified output for FX-001-01 |
| FX-001-03 | `docs/test-vectors/pingam-success.json` | PingAM success response with tokenId |
| FX-001-04 | `docs/test-vectors/pingam-failure.json` | PingAM authentication failure response |
| FX-001-05 | `docs/test-vectors/pingam-callback-complex.json` | Multi-callback response (choice, terms, hidden) |

## Spec DSL

```yaml
domain_objects:
  - id: DO-001-01
    name: CallbackResponse
    fields:
      - name: authId
        type: string
        constraints: "opaque, never parsed"
      - name: stage
        type: string
      - name: callbacks
        type: array<Callback>
  - id: DO-001-02
    name: PrettifiedResponse
    fields:
      - name: authId
        type: string
      - name: type
        type: enum[challenge, success, failure]
      - name: stage
        type: string
      - name: header
        type: string
      - name: fields
        type: array<PrettifiedField>
  - id: DO-001-03
    name: PrettifiedField
    fields:
      - name: name
        type: string
      - name: type
        type: enum[text, password, choice, hidden, boolean, number, info, terms, kba, idp-select, unknown]
      - name: label
        type: string
      - name: value
        type: any
      - name: required
        type: boolean
      - name: options
        type: array<string>
        constraints: "only for choice/idp-select types"

gateway_hooks:
  - id: GW-001-01
    gateway: PingAccess
    hook: RuleInterceptor.handleResponse
    body_access: exchange.getResponse().getBody().getContent()
    body_replace: exchange.getResponse().setBodyContent(byte[])
  - id: GW-001-02
    gateway: PingAccess
    hook: RuleInterceptor.handleRequest
    body_access: exchange.getRequest().getBody().getContent()
    body_replace: exchange.getRequest().setBodyContent(byte[])

fixtures:
  - id: FX-001-01
    path: docs/test-vectors/pingam-callback-simple.json
  - id: FX-001-02
    path: docs/test-vectors/pingam-callback-prettified.json
```

## Appendix

### A. Callback Type Reference

The full list of PingAM callback types is documented in
`docs/research/pingam-authentication-api.md`. The initial scope (FR-001-05) covers
the 14 most common types. Additional callback types can be added incrementally
without changing the core transformation contract — they just need a new entry in
the callback type mapping table.

### B. Multi-Step Flow Sequence

```
Client              message-xform            PingAM
  │                      │                      │
  │ POST /auth/login     │                      │
  │ (no body)            │                      │
  │─────────────────────►│                      │
  │                      │ POST /json/.../auth  │
  │                      │─────────────────────►│
  │                      │                      │
  │                      │◄─────────────────────│
  │                      │ {authId, callbacks}   │
  │                      │                      │
  │◄─────────────────────│ FR-001-01            │
  │ {authId, type:       │ (prettify response)  │
  │  "challenge",        │                      │
  │  fields: [...]}      │                      │
  │                      │                      │
  │ POST /auth/login     │                      │
  │ {authId, fields:     │                      │
  │  [{name:"username",  │                      │
  │    value:"bjensen"}, │                      │
  │   {name:"password",  │                      │
  │    value:"Ch4ng31t"}]│                      │
  │─────────────────────►│                      │
  │                      │ FR-001-02            │
  │                      │ (un-prettify request) │
  │                      │ POST /json/.../auth  │
  │                      │ {authId, callbacks}   │
  │                      │─────────────────────►│
  │                      │                      │
  │                      │◄─────────────────────│
  │                      │ {tokenId, successUrl} │
  │                      │                      │
  │◄─────────────────────│ FR-001-03            │
  │ {type: "success",    │ (prettify success)   │
  │  token: "AQIC5w.."} │                      │
```

### C. Research References

- PingAM Authentication API: `docs/research/pingam-authentication-api.md`
- PingAccess Plugin API: `docs/research/pingaccess-plugin-api.md`
- Gateway Candidates: `docs/research/gateway-candidates.md`
