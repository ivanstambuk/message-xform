# ADR-0021 – Query Params and Cookies in TransformContext

Date: 2026-02-07 | Status: Accepted

## Context

The `TransformContext` interface (FR-001-02) provides read-only context to expression
engines during evaluation. It currently exposes:

- `$headers` — HTTP headers as a JsonNode
- `$status` — HTTP status code (nullable per ADR-0020)
- `$requestPath` — request path string
- `$requestMethod` — HTTP method string

Real gateway APIs expose significantly more request metadata. PingAccess's `Exchange`
object provides `getQueryStringParams()`, `getPostParams()`, and `getCookies()`.
Kong exposes `kong.request.get_query()` and `kong.request.get_header("Cookie")`.
Apache APISIX, WSO2, and NGINX all expose query parameters and cookies.

Transform specs that need to branch on query parameters — a common pattern in
PingAM authentication flows (e.g., `?authIndexType=service&authIndexValue=Login`)
— cannot do so without these variables. Similarly, specs that need to read a
language preference or session identifier from cookies are currently blocked.

## Decision

Add two new read-only variables to `TransformContext`:

### `$queryParams`

A `JsonNode` object where keys are parameter names and values are the **first** value
(string). For multi-value parameters (e.g., `?tag=a&tag=b`), only the first value is
returned (consistent with `$headers` single-value behavior per FR-001-10).

```
GET /json/alpha/authenticate?authIndexType=service&authIndexValue=Login

$queryParams = {
  "authIndexType": "service",
  "authIndexValue": "Login"
}
```

JSLT usage: `$queryParams."authIndexType"` → `"service"`

### `$cookies`

A `JsonNode` object where keys are cookie names and values are cookie values (strings).
This exposes **request-side cookies only** (from the `Cookie:` HTTP header).

```
Cookie: session=eyJhbGciOi...; lang=en-US; theme=dark

$cookies = {
  "session": "eyJhbGciOi...",
  "lang": "en-US",
  "theme": "dark"
}
```

JSLT usage: `$cookies."lang"` → `"en-US"`

**Scope:** Request cookies only. Response-side `Set-Cookie` headers are structured
data (with `Path`, `HttpOnly`, `Secure`, `SameSite` attributes) and are handled via
header transforms (FR-001-10, ADR-0002), not context variables.

### Updated Java interface

```java
public interface TransformContext {
    JsonNode getHeaders();
    Integer getStatusCode();          // ADR-0020
    String getRequestPath();
    String getRequestMethod();
    JsonNode getQueryParams();        // NEW — ADR-0021
    JsonNode getCookies();            // NEW — ADR-0021
}
```

### Cross-language mapping

Both `$queryParams` and `$cookies` serialize naturally to JSON objects — no
language-specific concerns (consistent with ADR-0020 cross-language principle).

### Engine support matrix update

| Engine    | `$queryParams` | `$cookies` |
|-----------|:-:|:-:|
| `jslt`    | ✅ (bound as variable) | ✅ (bound as variable) |
| `jolt`    | ❌ (no variable support) | ❌ (no variable support) |
| `jq`      | ✅ (via `$ENV` or argument) | ✅ |
| `jsonata` | ✅ (via binding) | ✅ |

This is consistent with the existing `$headers`/`$status` engine support matrix
(ADR-0004): JOLT does not support context variables.

## Consequences

1. `TransformContext` gains two new methods: `getQueryParams()` and `getCookies()`.
   Both return `JsonNode` (may be an empty object `{}` if no params/cookies present,
   never `null`).
2. Expression engines that support context variables (JSLT, jq, JSONata) bind these
   as `$queryParams` and `$cookies`. JOLT ignores them (consistent with ADR-0004).
3. Adapters are responsible for parsing query params and cookies from the gateway's
   native request representation and providing them as `JsonNode` objects.
4. Security: Cookie values (e.g., session tokens) may be sensitive. Authors SHOULD
   use the `sensitive` list (ADR-0019) to mark cookie-derived fields if they appear
   in the transform output.
5. `$cookies` is request-side only. Response `Set-Cookie` manipulation remains a
   header transform concern (FR-001-10).

## Alternatives Considered

- **C) Synthetic headers**: Adapters inject `X-Query-Param-foo: bar` headers. Rejected
  — pollutes the header namespace and forces every adapter to implement the same
  workaround independently.
- **D) Leave as-is**: Blocks real-world auth flow transforms where query params are
  needed for branching. Rejected.
