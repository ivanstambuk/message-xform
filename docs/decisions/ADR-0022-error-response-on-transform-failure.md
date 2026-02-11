# ADR-0022 – Error Response on Transform Failure (No Passthrough)

Date: 2026-02-07 | Status: Accepted

## Context

The original spec (FR-001-07) defined two failure modes for transform evaluation:
- `strict` mode: abort the transformation and **pass the original message through**
  unchanged.
- `lenient` mode: "use partial output where possible" (vaguely defined).

Both options assumed that **passthrough** — forwarding the untransformed message —
was a reasonable failure recovery. This assumption is wrong.

When a transform profile matches a request, the engine has committed to transforming
that message. The downstream service expects the **transformed** schema, not the
original. Passing the original through will cause the downstream service to fail
anyway — with a confusing, delayed error instead of a clear, immediate one.

Additionally, `lenient` mode's "partial output" was never precisely defined (Q-025)
and posed a production safety risk: sending a half-evaluated JSON tree downstream
could corrupt traffic silently.

### Options Considered

- **Option A – Error response on failure** (chosen)
  - When a matched transform fails (expression error, schema validation failure,
    evaluation timeout, output size exceeded), the engine produces a **configurable
    error response** returned to the caller. No passthrough of the original message.
  - The error response format defaults to RFC 9457 (Problem Details for HTTP APIs)
    but is configurable per deployment (organizations may use internal error formats).
  - `error-mode` config (CFG-001-03) is removed entirely — there is no lenient/strict
    distinction for expression failures.
  - `schema-validation-mode` (CFG-001-08) is retained — it controls whether schemas
    are checked at evaluation time. When strict and validation fails, the same error
    response mechanism applies.
  - Pros: production-safe, clear failure signal, configurable error format.
  - Cons: removes "best-effort" option entirely; operators cannot get partial output.

- **Option B – Passthrough on failure** (rejected, was the status quo)
  - On transform failure, pass the original message through unchanged.
  - Pros: simple, no-op recovery.
  - Cons: downstream service receives a message in the wrong schema and fails anyway.
    The failure is delayed and confusing rather than immediate and clear. The operator
    does not know the transform failed unless they check logs.

- **Option C – Lenient "partial output" mode** (rejected)
  - Pass whatever the expression produced (even if null or incomplete).
  - Pros: maximum flexibility.
  - Cons: dangerous — malformed JSON reaches downstream services. "Partial output"
    was never properly defined. Production safety risk.

Related ADRs:
- ADR-0001 – Mandatory JSON Schema (schema validation)
- ADR-0012 – Pipeline Chaining Semantics (chain abort semantics)
- ADR-0013 – Copy-on-Wrap Message Adapter (rollback semantics)

## Decision

We adopt **Option A – Error response on failure**.

When a transform profile matches a request and the transformation fails at any stage
(expression compilation, expression evaluation, evaluation timeout, output size
exceeded, schema validation in strict mode), the engine MUST:

1. Log a structured error entry (spec id, engine id, error detail, chain step if
   applicable).
2. Produce a **configurable error response** containing: error type, human-readable
   title, status code, detail message, and request instance.
3. Signal the adapter to return this error response to the caller instead of
   forwarding to/from the downstream service.

**Error response format** is configurable:
- Default: **RFC 9457 Problem Details** (`application/problem+json`).
- Custom: operators may define a custom error template for organizations with
  internal error standards.

**Configuration:**
```yaml
# Engine-level error response config
error-response:
  format: rfc9457                # "rfc9457" (default) or "custom"
  status: 502                    # default HTTP status for transform failures
  custom-template: |             # only used when format: custom
    {
      "errorCode": "TRANSFORM_FAILED",
      "message": "{{error.detail}}",
      "specId": "{{error.specId}}"
    }
```

**Example RFC 9457 error response:**
```json
{
  "type": "urn:message-xform:error:transform-failed",
  "title": "Transform Failed",
  "status": 502,
  "detail": "JSLT evaluation error in spec 'callback-prettify@1.0.0': undefined variable '$missingVar' at line 3",
  "instance": "/json/alpha/authenticate"
}
```

**`error-mode` (CFG-001-03) is removed.** There is no lenient/strict distinction
for transform failures — failures always produce error responses.

**`schema-validation-mode` (CFG-001-08) is retained** with the same semantics:
- `strict`: validate input/output schemas at evaluation time. Failure → error response.
- `lenient`: skip evaluation-time schema checks (production default for performance).
  Schemas are still validated at load time.

**Passthrough (FR-001-06) is narrowed:** passthrough only applies when **no profile
matches** the request. When a profile matches but the body is not valid JSON (and
cannot be parsed), the engine returns an error response.

**Pipeline chaining update (ADR-0012):** abort-on-failure now means the chain aborts
and returns an error response — not passthrough of the original message.

**Copy-on-wrap update (ADR-0013):** on failure, the adapter discards the Message
copy AND returns the error response to the caller. The native message is not
forwarded.

## Consequences

Positive:
- Clear failure signal: clients always know when a transform failed.
- Production-safe: malformed or untransformed messages never reach downstream services.
- Configurable error format accommodates both RFC 9457 and internal org standards.
- Simplifies the spec: removes the vague `error-mode: lenient` concept and the
  confusing "partial output" language.

Negative / trade-offs:
- No "best-effort" mode: operators who want some output even on failure cannot get it.
  This is an acceptable trade-off — "some output" was never properly defined anyway.
- Adds complexity: the engine now needs error response generation, template rendering,
  and error-response config parsing.
- Adapter responsibility increases: adapters must handle the error response signal
  from the engine and return an appropriate HTTP error to the caller.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-07, CFG-001-03)
- RFC 9457: Problem Details for HTTP APIs
- Q-025: Lenient mode underspecified (resolved by this ADR)
