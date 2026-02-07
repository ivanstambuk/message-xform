# API Gateway Transformation Patterns — Research Notes

> Researched: 2026-02-07
> Context: Understanding how existing gateways and libraries implement request/response
> body transformation, to inform message-xform's generic specification.

---

## Overview

Every major API gateway supports some form of request/response transformation. The
approaches fall into three categories:

1. **Declarative field-level operations** (Kong, Tyk) — add/remove/rename/replace individual fields
2. **Template-based transformation** (AWS API Gateway, Apigee) — VTL templates, XSL, scripting
3. **Spec-based structural transformation** (JOLT, JSONata) — JSON-to-JSON reshaping via a spec

message-xform sits in category 3, closest to JOLT — it defines structural transformations
declaratively, not individual field tweaks.

---

## Kong Gateway — Request/Response Transformer Plugins

### Architecture

Kong provides **four** transformation plugins:
- `request-transformer` / `request-transformer-advanced` (request path)
- `response-transformer` / `response-transformer-advanced` (response path)

The "Advanced" variants add regex support, templating, and array/nested object navigation.

### Transformation Model

Kong uses a **5-phase pipeline** applied in fixed order:
1. **Remove** — delete headers, query params, or body fields
2. **Rename** — rename headers, query params, or body fields
3. **Replace** — overwrite headers, query params, body fields, URI, or method
4. **Add** — add new headers, query params, or body fields (only if not present)
5. **Append** — append headers, query params, or body fields (always adds, even if present)

Each phase operates on three targets:
- **Headers** — HTTP headers
- **Query string** — URL query parameters
- **Body (JSON)** — JSON body fields (dot-notation for nested, `array[*]` for arrays)

### Templating

Kong supports **template expressions** using `$(...)` syntax:
- `$(headers.Authorization)` — reference a request header
- `$(query_params.user_id)` — reference a query parameter
- `$(uri_captures.id)` — reference a URI capture group
- `$(shared.variable)` — reference a shared variable

Advanced templates evaluate Lua expressions inside `$(...)`, supporting:
- Logical operators: `$(uri_captures["user-id"] or query_params["user"] or "unknown")`
- Lambda functions for complex transformations
- String methods

### Configuration (YAML)

```yaml
plugins:
  - name: request-transformer-advanced
    config:
      remove:
        headers: ["X-Internal-Header"]
        body: ["internal_field", "debug_info"]
      rename:
        headers: ["X-Old-Name:X-New-Name"]
      replace:
        uri: "/v2/$(uri_captures.resource)"
        body: ["status:active"]
      add:
        headers: ["X-Gateway:kong"]
        body: ["source:api-gateway"]
      append:
        headers: ["X-Request-ID:$(headers.X-Correlation-ID)"]
```

### Key Insight: Kong Does NOT Do Structural Transformation

Kong's transformer operates at the **field level** — it can add, remove, rename, and
replace individual fields. It **cannot**:
- Restructure a nested JSON object into a flat one (or vice versa)
- Map an array of objects into a differently-shaped array
- Apply conditional transformations based on field values
- Transform between fundamentally different JSON schemas

**This is exactly the gap message-xform fills.** Kong can tweak fields; message-xform
can reshape entire message structures.

---

## AWS API Gateway — Mapping Templates (VTL)

### Architecture

AWS uses **Velocity Template Language (VTL)** mapping templates for REST APIs. These
templates define how to transform the request/response body, with access to:
- `$input.body` — raw request body
- `$input.json('$.field')` — JSONPath extraction
- `$input.params('header-name')` — request parameters
- `$context` — API Gateway context (stage, identity, etc.)

### Transformation Model

VTL is a **full template language** — it can generate arbitrary output:

```velocity
#set($body = $input.path('$'))
{
    "userId": "$body.user_id",
    "fullName": "$body.first_name $body.last_name",
    "active": $body.is_active
}
```

### Configuration (API Gateway)

Mapping templates are defined per integration, per content type:
```json
{
  "requestTemplates": {
    "application/json": "#set($body = ...)\n{...}"
  },
  "responseTemplates": {
    "application/json": "#set($body = ...)\n{...}"
  }
}
```

### Key Insight: Powerful but Vendor-Locked

VTL mapping templates are **extremely powerful** — they can do arbitrary
transformations. But they are:
- Completely AWS-specific (no portability)
- Hard to test outside API Gateway
- Limited to 10MB payload
- VTL syntax is non-obvious for most developers

---

## Apigee — Policy-Based Transformation

### Architecture

Apigee uses a **policy pipeline** with specialized policies:
- `XMLToJSON` / `JSONToXML` — format conversion
- `ExtractVariables` — JSONPath/XPath extraction into variables
- `AssignMessage` — set/modify headers, body, variables
- `JavaCallout` / `JavaScript` / `Python` — custom scripting
- `XSLTransform` — XSLT-based XML transformation

### Transformation Model

Policies are attached to **flows** (PreFlow, PostFlow, ConditionalFlow) and can:
- Extract data from request/response using JSONPath or XPath
- Store extracted data in flow variables
- Construct new payloads using templates + variables
- Apply conditional logic based on variables

### Key Insight: Policy Composition

Apigee's strength is **composing** multiple simple policies into a pipeline:
1. ExtractVariables → pull `userId` from request
2. AssignMessage → construct backend request with different shape
3. ExtractVariables → pull fields from backend response
4. AssignMessage → construct clean client response

This composition pattern is relevant to message-xform.

---

## JOLT — JSON-to-JSON Transformation Library

### Architecture

JOLT is a **Java library** (open source, Apache 2.0) that provides:
- Declarative, spec-based JSON-to-JSON transformations
- A chainable pipeline of operations
- No code required — transformations are defined in JSON specs

### Transformation Operations

JOLT provides 5 core operations:
1. **Shift** — Move/rename fields, restructure hierarchy (most powerful)
2. **Default** — Add default values for missing fields
3. **Remove** — Delete fields
4. **Modify** — Apply functions to values (string ops, math, etc.)
5. **Cardinality** — Convert between single values and arrays

### Spec Format (JSON)

```json
[
  {
    "operation": "shift",
    "spec": {
      "callbacks": {
        "*": {
          "input": {
            "*": {
              "value": "fields[&3].value"
            }
          },
          "output": {
            "*": {
              "value": "fields[&3].label"
            }
          },
          "type": "fields[&1].originalType"
        }
      },
      "authId": "authId",
      "stage": "stage"
    }
  },
  {
    "operation": "default",
    "spec": {
      "type": "challenge"
    }
  }
]
```

### Key Insight: Spec-Driven, Gateway-Agnostic, Java-Native

JOLT is the **closest existing solution** to what message-xform aims to be:
- Specs are declarative and gateway-agnostic
- The library is Java-native (same ecosystem as PingAccess/PingGateway)
- Transformations are composable (pipeline of operations)

**However**, JOLT has limitations:
- Spec syntax is notoriously hard to read and write
- No built-in support for conditional transformations based on values
- No bidirectional transformation (can't auto-derive the reverse)
- Limited type awareness (JSON types only, no concept of "this is a password field")
- No support for headers or non-body transformation
- No configuration model for binding specs to URL patterns

---

## JSONata — Expression Language for JSON

### Architecture

JSONata is an **expression language** for querying and transforming JSON, inspired by
XPath 3.1. Available in JavaScript (primary), Java (community port), and other languages.

### Transformation Model

```jsonata
{
    "authId": authId,
    "type": tokenId ? "success" : (callbacks ? "challenge" : "failure"),
    "stage": stage,
    "fields": callbacks.{
        "name": type = "NameCallback" ? "username" : type = "PasswordCallback" ? "password" : input[0].name,
        "type": type = "PasswordCallback" ? "password" : "text",
        "label": output[0].value,
        "value": input[0].value
    }
}
```

### Key Insight: Expressive but Language-Specific

JSONata is more expressive than JOLT for value-based transformations (conditional
logic, string operations), but:
- Primary implementation is JavaScript (not Java)
- Java port exists but is community-maintained
- Expression syntax has a learning curve
- Same limitations as JOLT for bidirectional, headers, and URL binding

---

## JMESPath — Query Language for JSON

### Architecture

JMESPath is a **query language** (not a transformation language). It selects and
projects data from JSON but has limited restructuring capability.

### Key Insight: Wrong Tool for Structural Transformation

JMESPath is designed for **extraction** (select fields from a response), not
**restructuring** (reshape one JSON schema into another). It's useful for
AWS API Gateway simple parameter mapping but not for the kind of deep
transformation message-xform does.

---

## Synthesis: What message-xform Should Be

### The Gap

| Capability | Kong | AWS VTL | Apigee | JOLT | JSONata | **message-xform** |
|-----------|------|---------|--------|------|---------|-------------------|
| Field add/remove/rename | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Structural reshaping | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Conditional logic | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ |
| Bidirectional (auto-reverse) | ❌ | ❌ | ❌ | ❌ | ❌ | **✅** |
| Gateway-agnostic spec | ❌ | ❌ | ❌ | ✅ | ✅ | **✅** |
| URL/path binding | ✅ | ✅ | ✅ | ❌ | ❌ | **✅** |
| Header transformation | ✅ | ✅ | ✅ | ❌ | ❌ | **✅** |
| YAML config | ✅ | ❌ | ❌ | ❌ | ❌ | **✅** |
| Java-native | ❌ | ❌ | ✅ | ✅ | ❌ | **✅** |

### Design Principles for message-xform (derived from research)

1. **Spec-based like JOLT** — transformations defined declaratively in YAML/JSON, not code
2. **Pipeline model like Kong** — composable operations applied in order
3. **Conditional logic like JSONata** — support value-based branching
4. **Bidirectional** — define the forward transform; derive (or separately define) the reverse
5. **Multi-target** — operate on body (JSON), headers, status code, and URI
6. **Gateway-agnostic core** — no dependency on any gateway SDK
7. **URL-bound** — specs bind to URL patterns (like Kong's per-route plugins)
8. **Human-readable config** — YAML, not the JSON spec format JOLT uses

---

*Status: COMPLETE — Transformation landscape fully researched*
