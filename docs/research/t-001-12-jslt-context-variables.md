# T-001-12 Research: JSLT Context Variable Binding

_Date:_ 2026-02-08
_Status:_ Complete

## Research Question

How should TransformContext variables (`$headers`, `$headers_all`, `$status`,
`$queryParams`, `$cookies`) be made available inside JSLT expressions?

## Finding

JSLT has **first-class support for external variables** via:

```java
Expression jslt = Parser.compileString(expr);
Map<String, JsonNode> vars = Map.of("headers", headersNode, "status", statusNode);
JsonNode output = jslt.apply(vars, input);
```

Variables injected this way are accessible in expressions using the `$` prefix
(e.g. `$headers`, `$status`).

## Binding Strategy (Implemented)

Context variables are bound as **top-level JSLT external variables** using
`Expression.apply(Map<String, JsonNode>, JsonNode)`:

| Spec variable    | JSLT variable  | JsonNode type        |
|------------------|----------------|----------------------|
| `$headers`       | `$headers`     | ObjectNode (string → string) |
| `$headers_all`   | `$headers_all` | ObjectNode (string → array) |
| `$status`        | `$status`      | IntNode or NullNode  |
| `$queryParams`   | `$queryParams` | ObjectNode (string → string) |
| `$cookies`       | `$cookies`     | ObjectNode (string → string) |

## Key Behaviors Discovered

1. **Undeclared variable access is an error** — JSLT throws `JsltException`
   at evaluation time if a `$variable` is referenced but not in the injected map.
   This is good: it catches typos early.

2. **Variables are injected per-evaluation** — the `Expression` object is
   compiled once and shared; variables are passed fresh on each `apply()` call.
   This is thread-safe.

3. **No prefix stripping needed** — JSLT uses `$name` syntax natively, so
   `$headers` in JSLT matches the spec's `$headers` context variable name.

4. **Object key access works naturally** — `$headers."content-type"` or
   `$headers.host` works as expected for accessing individual header values.

5. **Field exclusion syntax** — to copy all fields except specific ones, use
   `{ * - secret : . }` (the minus in the matcher), **not** `{ * : . } - "secret"`
   (which is an invalid subtraction expression).

6. **`contains()` on null/missing fields throws** — calling `contains(.missingField, "value")`
   where `.missingField` is null or absent triggers a `JsltException` at evaluation
   time (not a false return). This matters for `status.when` predicates: a `when`
   predicate that calls `contains()` on a field that doesn't exist in the transformed
   body will fail, causing the engine to keep the original status code (S-001-38i).

## Implications

- No wrapper or adapter layer needed — the JSLT library's native variable
  injection mechanism maps directly to our TransformContext design.
- The implementation in `JsltExpressionEngine.buildVariables()` is minimal
  (~6 lines) and requires no special handling.
- Future engines (jq, JOLT) will need their own variable binding strategy,
  but the SPI's `TransformContext` parameter keeps this encapsulated.
