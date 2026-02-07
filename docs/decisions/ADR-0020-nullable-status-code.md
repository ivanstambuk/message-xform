# ADR-0020 – Nullable Status Code in TransformContext

Date: 2026-02-07 | Status: Accepted

## Context

The Feature 001 spec contained a contradiction between two representations of the
HTTP status code for request transforms:

1. **Java interface** (`TransformContext.getStatusCode()`) declared `int` returning
   `-1` as a sentinel for "no status available" (request transforms).
2. **ADR-0017** and the JSLT variable contract declared that `$status` is `null`
   for request transforms.

Java `int` cannot be `null`. The `-1` sentinel forces every expression engine adapter
to know about a magic value and map it before binding to the expression language.

Additionally, message-xform aims to support non-Java implementations (Lua, Go, C)
via sidecar/bridge patterns or native reimplementations. The `TransformContext`
contract must be expressible cross-language. Every language has a native "absent"
representation (`null`, `nil`, `None`, `*int`), but `-1` is a Java-specific sentinel
that leaks internal concerns into every other implementation. Over JSON (sidecar
protocol), `null` is native; `-1` would appear as an invalid HTTP status code.

## Decision

Change `TransformContext.getStatusCode()` from `int` (returning `-1`) to `Integer`
(returning `null`).

The spec-level contract is:

> `$status` is `null` when no HTTP status exists (request transforms per ADR-0017).
> For response transforms, `$status` is the integer HTTP status code (100–599).

### Java interface (updated)

```java
public interface TransformContext {
    JsonNode getHeaders();
    /** HTTP status code, or null for request transforms (ADR-0017). */
    Integer getStatusCode();
    String getRequestPath();
    String getRequestMethod();
}
```

### Cross-language mapping

| Language | "No status" | "Has status" |
|----------|-------------|--------------|
| Java     | `Integer` → `null` | `Integer` → `200` |
| JSON     | `null` | `200` |
| Lua      | `nil` | `200` |
| Go       | `*int` → `nil` | `*int` → `&200` |
| Python   | `None` | `200` |
| C        | `struct { bool has_status; int code; }` | `{ true, 200 }` |

## Consequences

1. The Java `TransformContext.getStatusCode()` return type changes from `int` to
   `Integer`. The JSLT adapter binds `null` directly — no sentinel mapping needed.
2. ADR-0017 and the Java interface are now consistent. The `-1` sentinel is eliminated.
3. DO-001-07 (`TransformContext` domain object) retains `type: int` in the conceptual
   model but the note clarifies `null for request transforms`. The Java implementation
   uses `Integer` to represent this.
4. Expression engine adapters no longer need sentinel-aware logic. They receive `null`
   or a valid status code — nothing else.
5. Minor autoboxing cost for `Integer` vs `int` is negligible (one object per request,
   not a hot-loop allocation).
6. Cross-language implementations follow the spec-level contract (`null` when absent)
   using their language's native "absent" type.

## Alternatives Considered

- **B) Keep `int` with `-1` sentinel**: Forces every adapter to know about the magic
  value. `-1` is not a valid HTTP status code and would appear as such in JSON
  serialization (sidecar protocol). Rejected.
- **C) Add `boolean hasStatus()` alongside `int getStatusCode()`**: Two methods for
  one concept. Callers must remember the check-first pattern. Doesn't help expression
  engine binding — JSLT still needs `null`. Rejected.
