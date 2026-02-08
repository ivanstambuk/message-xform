# ADR-0027 – URL Rewriting: Expression Context and Method Override

Date: 2026-02-08 | Status: Accepted

## Context

Feature 001 is being extended to support URL rewriting (FR-001-12). The transform
DSL already supports body transformation (`transform.expr`), header operations
(`headers` block), and status code overrides (`status` block). URL rewriting adds
a `url` block that allows spec authors to rewrite the request path, modify query
parameters, and override the HTTP method — all using JSLT expressions with access
to the full message context.

The primary use case is **de-polymorphizing** orchestration endpoints: converting
a generic `POST /dispatch` (where the operation is determined by a body field like
`action`) into specific REST-style URLs like `DELETE /api/users/123`.

Two design decisions needed resolution before the spec could be written:

1. **Q-028: Expression evaluation context** — should `url.path.expr` evaluate
   against the original (pre-transform) body or the transformed (post-transform)
   body?
2. **Q-029: Method override scope** — should HTTP method override (`url.method`)
   be included in the initial URL rewriting capability or deferred?

### Options Considered — Q-028 (Expression Context)

- **Option A – Original body** (chosen)
  - `url.path.expr` evaluates against the original request body, before the body
    transform has run. Routing fields (e.g., `action`, `resourceId`) are still
    present and available for URL construction.
  - Pros: primary use case works naturally; authors can strip routing fields from
    the body while still using them in the URL.
  - Cons: inconsistent with `headers.add.expr` and `status.when`, which evaluate
    against the transformed body (FR-001-10, FR-001-11).

- **Option B – Transformed body** (rejected)
  - Consistent with headers/status evaluation context.
  - Rejected because routing fields are typically stripped by the body transform,
    making them unavailable for URL construction — the primary use case breaks.

- **Option C – Configurable via `source: original | transformed`** (rejected)
  - Maximum flexibility but adds configuration surface for a case that rarely needs
    the transformed body. YAGNI.

### Options Considered — Q-029 (Method Override)

- **Option A – Include in v1** (chosen)
  - Ship `url.method` alongside `url.path` and `url.query`. Uses the same
    `set`/`when` pattern as `status` (FR-001-11).
  - Pros: completes the de-polymorphization story; trivial implementation cost
    (same pattern already exists); avoids revisiting the `url` block later.
  - Cons: slightly larger Phase 6 scope; adapter SPI must support method mutation.

- **Option B – Defer** (rejected)
  - Ship `url.path` and `url.query` only.
  - Rejected because de-polymorphization without method override is incomplete
    (backend receives DELETE-intent actions on POST), and the implementation cost
    of deferral (future rework of Message, url block, and adapters) exceeds the
    cost of including it now.

Related ADRs:
- ADR-0002 – Header Transforms (headers block pattern)
- ADR-0003 – Status Code Transforms (status `set`/`when` pattern)
- ADR-0013 – Copy-on-Wrap Message Adapter (mutability model)

## Decision

### Q-028: URL path expressions evaluate against the original (pre-transform) body.

This is a documented exception to the convention used by `headers.add.expr` and
`status.when` (which evaluate against the transformed body). The rationale is:

- **URL rewrite routes the input** (determines where the request goes). The routing
  decision depends on the raw request — fields like `action`, `realm`, `resourceId`.
- **Headers/status enrich the output** (decorate the transformed message). These
  naturally operate on the post-transform state.

The processing order is:

```
1. Engine reads metadata → binds $headers, $headers_all, $status,
   $requestPath, $requestMethod, $queryParams, $cookies
2. JSLT body expression evaluates (transform.expr) → transformed body
3. URL rewrite applied (against ORIGINAL body):
     a. path.expr evaluated → new path (string)
     b. query.remove → strip matching params (glob patterns)
     c. query.add (static) → set params
     d. query.add (dynamic) → evaluate expr against original body
     e. method.when predicate → if true, method.set applied
4. Header operations applied (against TRANSFORMED body):
     a. remove → rename → add (static) → add (dynamic)
5. Status predicate evaluated (against TRANSFORMED body) → status set
```

### Q-029: HTTP method override is included in v1.

`url.method` uses the same `set`/`when` pattern as `status`:

```yaml
url:
  method:
    set: "DELETE"
    when: '.action == "delete"'     # predicate against original body
```

Validation: `method.set` must be a valid HTTP method (`GET`, `POST`, `PUT`,
`DELETE`, `PATCH`, `HEAD`, `OPTIONS`) — validated at load time.

### Message Interface Update

`Message` gains URL mutation methods:

```java
// Added to Message interface (FR-001-04)
void setRequestPath(String path);
void setRequestMethod(String method);
```

### URL Encoding

The engine MUST percent-encode the result of `url.path.expr` per RFC 3986 §3.3.
If the expression returns a path containing reserved characters (e.g., spaces,
`?`, `#`), they are encoded. Path separators (`/`) are preserved as-is because
the expression is expected to construct a valid path structure.

### Direction Restriction

URL rewriting is only meaningful for **request transforms**:
- `direction: request` + `url` block → applied.
- `direction: response` + `url` block → ignored (warning logged at load time).

## Consequences

Positive:
- Completes the HTTP message transformation picture: body, headers, status, **and URL**.
- De-polymorphization use case is fully supported end-to-end.
- Original-body evaluation context makes the common case (strip routing fields + route)
  work without workarounds.
- Method override uses the proven `set`/`when` pattern — zero new concepts.

Negative / trade-offs:
- Two evaluation contexts in the pipeline (original body for URL, transformed body
  for headers/status). Mitigated by clear documentation and the intuitive rationale
  (route the input, enrich the output).
- Adapter SPI must support path and method mutation. All major gateways (PingAccess,
  PingGateway, Kong, standalone) support this natively.

Follow-ups:
- spec.md: add FR-001-12 (URL Rewriting).
- spec.md: update Message interface (FR-001-04) with `setRequestPath`, `setRequestMethod`.
- spec.md: update processing order.
- spec.md: update DO-001-02 (TransformSpec) with `url` field.
- spec.md: update Spec DSL section.
- plan.md: add URL rewriting increment to Phase 6.
- tasks.md: add URL rewriting tasks to Phase 6.
- scenarios.md: add URL rewriting scenarios.
- terminology.md: add URL rewriting terms.
- knowledge-map.md: add ADR-0027.
- llms.txt: add ADR-0027.
- open-questions.md: remove Q-028, Q-029.

Validating scenarios:
- S-001-38a: URL path rewrite — de-polymorphize dispatch (original-body context)
- S-001-38b: URL query parameter add/remove
- S-001-38c: HTTP method override with conditional predicate
- S-001-38d: path.expr returns null → error
- S-001-38e: invalid HTTP method → rejected at load time
- S-001-38f: url block on response transform → ignored with warning
- S-001-38g: URL-to-body extraction — reverse direction

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-04, FR-001-10, FR-001-11)
- PingAccess URL Rewrite Rules: `docs.pingidentity.com/pingaccess/9.0/.../pa_adding_rewrite_url_rules.html`
- Q-028: URL path expression evaluation context (resolved by this ADR)
- Q-029: HTTP method override scope (resolved by this ADR)
