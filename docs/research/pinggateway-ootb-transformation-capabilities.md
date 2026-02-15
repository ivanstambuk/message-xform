# PingGateway OOTB Capability Assessment for PingAM Payload Transformation

Date: 2026-02-15
Status: Complete

## Question

Can PingGateway do, out of the box, the same class of request/response transformations we do in `message-xform` for PingAM payload normalization (for example, reshaping payloads to an external API contract such as IBM MRA-style expectations), including field propagation to headers?

## Assumption

"IBM MR A" was interpreted as an external target API contract that requires:
- request payload reshaping
- response payload reshaping
- payload/context field propagation to headers
- request method/URI rewrites in some flows

## Executive Answer

Short answer: **yes, mostly, but script-driven**.

PingGateway can transform both request and response data, and can propagate values into headers, using built-in filters plus Groovy scriptable objects. However, there is no evidence of a single generic declarative JSON-to-JSON transform filter comparable to `message-xform`'s dedicated transformation engine; complex payload mapping is primarily implemented via scripts (or custom Java extensions).

## Capability Matrix

| Capability | OOTB in PingGateway | How | Notes |
|---|---|---|---|
| Request transformation | Yes | Filter chain modifies request representation; `HeaderFilter`, `AssignmentFilter`, `StaticRequestFilter`, `ScriptableFilter` | Request model includes query/form, cookies, headers, entity. |
| Response transformation | Yes | Response flow filters build response headers/entity; scriptable handlers/filters can construct or replace response | Not limited to request-only processing. |
| Header propagation (context/value -> header) | Yes | `HeaderFilter` with expressions; scripts set `request.headers` | Supports request and response header mutation. |
| Payload field extraction for flow logic | Yes | Expressions can read request entity form fields, e.g. `request.entity.form[...]` | Commonly used in OAuth/token exchange examples. |
| Full custom payload reshaping (general JSON mapping) | Partial (script/custom) | Groovy (`ScriptableFilter` / `ScriptableHandler`) or Java extension | No dedicated generic declarative mapper found in docs. |
| No-code parity with message-xform style contracts | No | N/A | Requires route/filter/script composition. |

## Evidence

1. Request + response pipeline semantics are explicitly documented:
- request representation includes parsed query/form params, cookies, headers, entity; request filters can modify it.
- response flow filters build response with headers + entity.
- Source: `docs/reference/vendor/pinggateway-2025.11.txt:966`, `docs/reference/vendor/pinggateway-2025.11.txt:972`.

2. Scriptable objects can access request/context and response promise callbacks:
- scripts can "Access responses returned in promise callback methods."
- Source: `docs/reference/vendor/pinggateway-2025.11.txt:5485`, `docs/reference/vendor/pinggateway-2025.11.txt:5490`.

3. Request header mutation is straightforward:
- `HeaderFilter` example adds `Username` and `Password` to REQUEST.
- Source: `docs/reference/vendor/pinggateway-2025.11.txt:5627`, `docs/reference/vendor/pinggateway-2025.11.txt:5629`, `docs/reference/vendor/pinggateway-2025.11.txt:5630`.

4. Response header mutation is also supported:
- `HeaderFilter` example with `"messageType": "RESPONSE"` adds `set-cookie`.
- Source: `docs/reference/vendor/pinggateway-2025.11.txt:15172`, `docs/reference/vendor/pinggateway-2025.11.txt:15174`, `docs/reference/vendor/pinggateway-2025.11.txt:15175`.

5. Request/response body shaping via scripts is possible:
- Scriptable handler creates response and sets `response.entity`.
- Scriptable filter examples show request mutation and async response callback model.
- Source: `docs/reference/vendor/pinggateway-2025.11.txt:5575`, `docs/reference/vendor/pinggateway-2025.11.txt:5583`, `docs/reference/vendor/pinggateway-2025.11.txt:5763`.

6. Field propagation patterns are demonstrated:
- `AssignmentFilter` copies context values into session fields, then `StaticRequestFilter` rewrites request method/URI/form.
- Source: `docs/reference/vendor/pinggateway-2025.11.txt:12423`, `docs/reference/vendor/pinggateway-2025.11.txt:12435`, `docs/reference/vendor/pinggateway-2025.11.txt:12460`.

7. Entity field access from inbound payload is available in expressions:
- Example reads `request.entity.form['subject_token'][0]`.
- Source: `docs/reference/vendor/pinggateway-2025.11.txt:18742`, `docs/reference/vendor/pinggateway-2025.11.txt:18768`.

8. For transformations not achievable with existing handlers/filters/expressions, PingGateway points to custom Java API extensions:
- Source: `docs/reference/vendor/pinggateway-2025.11.txt:6092`, `docs/reference/vendor/pinggateway-2025.11.txt:6094`.

## Practical Interpretation for message-xform

- If the requirement is "can PingGateway do this at all without building a new gateway product plugin," the answer is **yes**.
- If the requirement is "can it do this with a reusable, declarative transform-spec model like `message-xform` out of the box," the answer is **not directly**; you compose filters and/or write Groovy.
- So PingGateway is capable, but the implementation style is route/script-centric rather than spec-centric.

## Bottom Line

PingGateway is **not limited to request-only changes**. It can perform request and response transformations and propagate values to headers out of the box, primarily through built-in filters and Groovy scripts. For heavily standardized, reusable payload transformation contracts across many APIs, `message-xform` still provides a cleaner and more portable abstraction.
