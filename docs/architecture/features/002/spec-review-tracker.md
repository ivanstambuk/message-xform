# Feature 002 Spec Review — Tracker

| Field | Value |
|-------|-------|
| Created | 2026-02-11 |
| Reviewer | Agent (deep dive analysis) |
| Target | `docs/architecture/features/002/spec.md` |
| Cross-ref | `docs/architecture/features/002/pingaccess-sdk-guide.md` |
| Status | 🔨 In Progress (B1–B4 done) |

> This document tracks all findings from the spec deep-dive analysis and
> organizes them into implementation batches. Each batch is a logical unit of
> work that can be applied and committed independently.

---

## Summary

| Category | Count | Critical | Medium | Low |
|----------|-------|----------|--------|-----|
| 🔴 Bugs & Inconsistencies | 7 | 3 | 3 | 1 |
| 🟡 Gaps | 5 | 2 | 3 | 0 |
| 🔐 Security & Reliability | 4 | 0 | 3 | 1 |
| 💡 Improvements | 6 | 0 | 2 | 4 |
| **Total** | **22** | **5** | **11** | **6** |

---

## Batch Overview

| Batch | Theme | Items | Severity | Status |
|-------|-------|-------|----------|--------|
| **B1** | SDK API corrections (won't compile) | BUG-1, GAP-1, BUG-5 | 🔴 Critical | ✅ Done |
| **B2** | Lifecycle & control flow (runtime crashes) | GAP-4, IMP-6, BUG-6, BUG-4 | 🔴 Critical | ✅ Done |
| **B3** | Scenario matrix corrections | BUG-2, BUG-3, IMP-4 | 🟡 Medium | ✅ Done |
| **B4** | Body handling & headers | GAP-2, GAP-5, IMP-1 | 🟡 Medium | ✅ Done |
| **B5** | Configuration, reload & reliability | GAP-3, SEC-2, SEC-3, SEC-4 | 🟡 Medium | ⬜ Not started |
| **B6** | Documentation, trust model & polish | SEC-1, BUG-7, IMP-2, IMP-3, IMP-5 | 💡 Low | ⬜ Not started |

---

## Batch 1 — SDK API Corrections (won't compile)

> **Theme:** Fix references to SDK methods that don't exist in the Java API.
> These would cause immediate compilation failures during implementation.

### BUG-1: Wrong SDK method name — `getAllHeaderFields()` is Groovy-only

| Field | Value |
|-------|-------|
| ID | BUG-1 |
| Severity | 🔴 Critical |
| Status | ✅ Done |
| Affected lines | spec.md L155, L177, L631 |
| Root cause | `getAllHeaderFields()` exists only in PingAccess's Groovy wrapper (returns `GroovyHeaderField`). The Java SDK provides `getHeaderFields()` → `List<HeaderField>` and `asMap()` → `Map<String, String[]>`. |
| Cross-ref | SDK guide §5 (L439, L444) confirms: `getHeaderFields()` and `asMap()` |
| Evidence | `grep` of SDK guide finds zero hits for `getAllHeaderFields`; PA 9.0 reference doc shows it only in Groovy script context |

**Fix:**

Replace all 3 occurrences:

1. **L155** (wrapRequest mapping):
   - Old: `exchange.getRequest().getHeaders().getAllHeaderFields()` → single-value map
   - New: `exchange.getRequest().getHeaders().asMap()` → flatten `Map<String, String[]>` to single-value map (first value per name, lowercase keys)

2. **L177** (wrapResponse mapping):
   - Old: `exchange.getResponse().getHeaders().getAllHeaderFields()` → same normalization
   - New: `exchange.getResponse().getHeaders().asMap()` → same normalization

3. **L631** (FR-002-13 TransformContext):
   - Old: `exchange.getRequest().getHeaders().getAllHeaderFields()` → single-value map
   - New: `exchange.getRequest().getHeaders().asMap()` → single-value map (first value per name, lowercase keys)

Also update L632 (headersAll source) to reference `asMap()` consistently.

**Normalization pattern** (add as a note after the mapping tables):
```
Headers.asMap() returns Map<String, String[]>.
→ Single-value: iterate entries, take values[0], lowercase key → Map<String, String>
→ Multi-value:  iterate entries, Arrays.asList(values), lowercase key → Map<String, List<String>>
```

---

### GAP-1: No `body.read()` call before `getContent()`

| Field | Value |
|-------|-------|
| ID | GAP-1 |
| Severity | 🔴 Critical |
| Status | ✅ Done |
| Affected lines | spec.md L133–134, L154, L176 (wrapRequest/wrapResponse body source) |
| Root cause | Constraint #5 (L796–801) says `body.read()` is required before `getContent()`, but the mapping tables and FR-002-01 text only show `getContent()` |
| Cross-ref | SDK guide §4 (L378–408): Body is stateful; `getContent()` throws `IllegalStateException` if `isInMemory()` is false |

**Fix:**

1. **FR-002-01, JSON Parse Failure Strategy (L126–148):** Insert a pre-read step before the parse attempt:

   Replace the current step 1:
   > 1. Attempt to parse `Body.getContent()` as JSON via `ObjectMapper.readTree()`.

   With:
   > 1. **Pre-read the body into memory:** If `!body.isRead()`, call `body.read()`.
   >    On `AccessException` (body exceeds PA's configured maximum) or `IOException`:
   >    return `NullNode` as the body and log a warning with exception details.
   > 2. Attempt to parse `body.getContent()` as JSON via `ObjectMapper.readTree()`.

   Renumber subsequent steps (current step 2 → 3, current step 3 → 4).

2. **wrapRequest mapping table (L154):** Update body source:
   - Old: `exchange.getRequest().getBody().getContent()` → parse as `JsonNode`
   - New: `exchange.getRequest().getBody()` → pre-read via `body.read()` if `!body.isRead()` → `body.getContent()` → parse as `JsonNode` (or `NullNode` on read failure / non-JSON)

3. **wrapResponse mapping table (L176):** Same update for response body.

---

### BUG-5: `getContentType()` returns `MediaType` (nullable) — needs null guard

| Field | Value |
|-------|-------|
| ID | BUG-5 |
| Severity | 🟡 Medium |
| Status | ✅ Done |
| Affected lines | spec.md L158, L180 |
| Root cause | `Headers.getContentType()` returns `MediaType` (not `String`), which can be `null` when Content-Type is absent. Calling `.toString()` on null NPEs. |
| Cross-ref | SDK guide §5 (L471): getter returns `MediaType` |

**Fix:**

1. **L158** (wrapRequest contentType):
   - Old: `exchange.getRequest().getHeaders().getContentType()` → `MediaType.toString()`
   - New: `headers.getContentType()` → `MediaType ct`; `contentType = (ct != null) ? ct.toString() : null`

2. **L180** (wrapResponse contentType):
   - Same null-guard pattern.

3. Add a brief note after the mapping tables:
   > **Null Content-Type:** `Headers.getContentType()` returns `null` (not an empty
   > `MediaType`) when the Content-Type header is absent. The adapter MUST null-check
   > before calling `toString()`.

---

### B1 Commit Plan

- [x] Apply BUG-1 fixes (3 lines + normalization note)
- [x] Apply GAP-1 fixes (pre-read step + mapping table updates)
- [x] Apply BUG-5 fixes (null-guard on 2 lines + note)
- [x] Commit: `fix(spec-002): correct SDK API refs in mapping tables` (`0861a24`)

---

## Batch 2 — Lifecycle & Control Flow (runtime crashes)

> **Theme:** Fix incorrect control flow assumptions that would cause runtime
> issues (overwritten error responses, wrong TransformContext lifecycle).

### GAP-4: `handleResponse()` called even on `Outcome.RETURN` — no DENY guard

| Field | Value |
|-------|-------|
| ID | GAP-4 |
| Severity | 🔴 Critical |
| Status | ✅ Done |
| Affected lines | spec.md FR-002-02 (L245–282) |
| Root cause | SDK guide §12 (L1726): "`RETURN` does NOT skip response interceptors — it triggers them in reverse order." If `handleRequest()` returns `RETURN` (DENY mode), `handleResponse()` is still called and would overwrite the error response. |
| Cross-ref | SDK guide §1 (L87–89): `handleResponse` contract |

**Fix:**

1. **FR-002-02, `handleRequest` DENY path (L258–259):** Add a step to set a "denied" marker:
   > - `ERROR` with DENY → build error response via `ResponseBuilder`, set on exchange,
   >   **set `ExchangeProperty TRANSFORM_DENIED = true`**, return `Outcome.RETURN`.

2. **FR-002-02, `handleResponse` (L260–267):** Add a DENY-aware guard at the top:
   > 2. `handleResponse(Exchange)` → `CompletionStage<Void>`:
   >    - **Guard:** If `exchange.isPropertyTrue(TRANSFORM_DENIED)`, skip all
   >      processing and return completed stage immediately. This prevents
   >      overwriting the DENY error response set during `handleRequest()`.
   >    - [existing steps: wrap, transform, dispatch, return]

3. **Add the ExchangeProperty declaration** near FR-002-07 (L462–476):
   ```java
   private static final ExchangeProperty<Boolean> TRANSFORM_DENIED =
       ExchangeProperty.create("io.messagexform", "transformDenied", Boolean.class);
   ```

4. **Add scenario S-002-29** (or renumber — see B3):
   > `handleRequest()` DENY → `handleResponse()` skips processing → client receives original DENY error body.

---

### IMP-6: `TransformContext` is an immutable record — cannot reuse with updated status

| Field | Value |
|-------|-------|
| ID | IMP-6 |
| Severity | 🔴 Critical (incorrect lifecycle description) |
| Status | ✅ Done |
| Affected lines | spec.md FR-002-13 (L650–653) |
| Root cause | `TransformContext` is a Java record (immutable). The spec says "The same `TransformContext` is reused for both request and response phases (with `status` updated for the response phase)." This is impossible — records are immutable. |
| Cross-ref | `core/src/main/java/.../TransformContext.java` — confirmed `public record TransformContext(...)` |

**Fix:**

Replace L650–653:

Old:
> **Lifecycle note:** `buildTransformContext()` is called **once per request** at
> the start of `handleRequest()`. The same `TransformContext` is reused for both
> request and response phases of the same exchange (with `status` updated for the
> response phase). This avoids re-parsing cookies/query params in `handleResponse()`.

New:
> **Lifecycle note:** `TransformContext` is an immutable record. Two instances are
> built per exchange:
>
> 1. **Request phase:** `buildTransformContext(exchange, null)` — `status` is null
>    (no response yet).
> 2. **Response phase:** `buildTransformContext(exchange, exchange.getResponse().getStatusCode())`
>    — `status` is the response status code.
>
> To avoid re-parsing cookies and query params in the response phase, the adapter
> MAY cache the parsed `cookies` and `queryParams` maps as method-local variables
> in `handleRequest()` and pass them to `handleResponse()` via an `ExchangeProperty`.
> Alternatively, re-parsing is acceptable (cost is negligible for typical request sizes).

Update the `buildTransformContext` method signature in the FR-002-13 table (L629):
- Old: `buildTransformContext(Exchange)`
- New: `buildTransformContext(Exchange, Integer status)` — status is null for request phase, response status code for response phase.

---

### BUG-6: `handleResponse()` must override base class default no-op

| Field | Value |
|-------|-------|
| ID | BUG-6 |
| Severity | 🟡 Low |
| Status | ✅ Done |
| Affected lines | spec.md FR-002-02 (L260) |
| Root cause | `AsyncRuleInterceptorBase` provides a default no-op `handleResponse()`. The spec doesn't explicitly state that the adapter must override this default. |
| Cross-ref | SDK guide §1 (L102): `CompletionStage<Void> handleResponse(Exchange exchange); // default no-op` |

**Fix:**

Add a note to FR-002-02 after the `handleResponse` description (L267):

> **Override note:** `AsyncRuleInterceptorBase` provides a default no-op
> implementation of `handleResponse()`. The adapter MUST override this to
> implement response-phase transformations. Without the override, response
> transforms are silently skipped.

---

### BUG-4: `applyChanges` direction strategy — SPI tension

| Field | Value |
|-------|-------|
| ID | BUG-4 |
| Severity | 🟡 Medium |
| Status | ✅ Done |
| Affected lines | spec.md L186–219 |
| Root cause | `GatewayAdapter<R>.applyChanges(Message, R)` has no direction parameter. The spec proposes a direction-aware overload but the analysis is incomplete — it says "default to RESPONSE" for the SPI method, but `StandaloneAdapter` writes all fields regardless of direction. |

**Fix:**

Rewrite the "applyChanges Direction Strategy" section (L186–219) for clarity:

1. Keep **option 2** (two internal helpers) as the **chosen** approach:
   ```
   // Internal helpers (not part of GatewayAdapter SPI):
   void applyRequestChanges(Message transformed, Exchange exchange)
   void applyResponseChanges(Message transformed, Exchange exchange)
   ```

2. The public SPI method `applyChanges(Message, Exchange)` is **not called directly**
   by `MessageTransformRule`. Instead, `MessageTransformRule` calls the direction-
   specific internal helper directly (it knows the direction from the lifecycle phase).

3. The public `applyChanges()` SPI method delegates to `applyResponseChanges()` as a
   reasonable default for general-purpose callers that don't distinguish direction.
   Add note: _"The `StandaloneAdapter` does not need direction awareness because
   Javalin's `Context` API naturally separates request and response writes."_

4. Remove option 1 (Direction enum parameter) — no longer presented as an alternative.
   It was the weaker option and clutters the spec.

---

### B2 Commit Plan

- [x] Apply GAP-4 fixes (DENY guard + ExchangeProperty)
- [x] Apply IMP-6 fixes (TransformContext lifecycle rewrite)
- [x] Apply BUG-6 fix (override note)
- [x] Apply BUG-4 fix (applyChanges direction rewrite)
- [x] Commit: `fix(spec-002): lifecycle & control flow corrections` (`09d18a1`)

---

## Batch 3 — Scenario Matrix Corrections

> **Theme:** Fix incorrect `$session` paths in scenarios and add missing
> pipeline interaction scenario.

### BUG-2: S-002-25 — wrong `$session` path for OAuth fields

| Field | Value |
|-------|-------|
| ID | BUG-2 |
| Severity | 🟡 Medium |
| Status | ✅ Done |
| Affected lines | spec.md L708 |
| Root cause | `$session` uses a flat namespace. OAuth fields are at `$session.clientId`, not `$session.oauth.clientId`. |

**Fix:**

Old (L708):
> `$session.oauth.clientId` and `$session.oauth.scopes` → resolves from `OAuthTokenMetadata`

New:
> `$session.clientId` and `$session.scopes` → resolves from `OAuthTokenMetadata` (flat `$session` namespace, see FR-002-06 merge layers)

---

### BUG-3: S-002-26 — wrong `$session` path for session state

| Field | Value |
|-------|-------|
| ID | BUG-3 |
| Severity | 🟡 Medium |
| Status | ✅ Done |
| Affected lines | spec.md L709 |
| Root cause | Session state attributes are spread flat into `$session` (layer 4). Path should be `$session.authzCache`, not `$session.sessionState.authzCache`. |

**Fix:**

Old (L709):
> `$session.sessionState.authzCache` → resolves from `SessionStateSupport`

New:
> `$session.authzCache` → resolves from `SessionStateSupport` (flat `$session` namespace, layer 4 merge)

---

### IMP-4: Missing pipeline interaction scenario

| Field | Value |
|-------|-------|
| ID | IMP-4 |
| Severity | 🟡 Medium |
| Status | ✅ Done |
| Affected lines | spec.md scenario matrix (L682–709) |
| Root cause | No scenario covers interaction with prior rules that modify the exchange (e.g., URI rewrite by an upstream ParameterRule). |

**Fix:**

Add two new scenarios after S-002-26:

> | S-002-27 | **Prior rule URI rewrite:** Upstream ParameterRule rewrites `/old/path` to `/new/path` → MessageTransformRule matches spec on `/new/path` (validates URI choice per FR-002-01 note). |
> | S-002-28 | **DENY + handleResponse interaction:** `handleRequest()` returns `Outcome.RETURN` with DENY error body → `handleResponse()` is called (SDK contract) → adapter skips response processing (DENY guard, GAP-4 fix) → client receives original DENY error. |

---

### B3 Commit Plan

- [x] Apply BUG-2 fix (S-002-25 path)
- [x] Apply BUG-3 fix (S-002-26 path)
- [x] Apply IMP-4 fix (add S-002-27, S-002-28)
- [x] Commit: `fix(spec-002): scenario matrix corrections` (`fefa78e`)

---

## Batch 4 — Body Handling & Headers

> **Theme:** Specify charset for body write-back, protect Content-Length from
> header diff, handle Transfer-Encoding.

### GAP-2: No charset specification for body serialization in `applyChanges()`

| Field | Value |
|-------|-------|
| ID | GAP-2 |
| Severity | 🟡 Medium |
| Status | ✅ Done |
| Affected lines | spec.md FR-002-01 applyChanges (L211–218) |
| Root cause | The spec says `setBodyContent(bytes)` but doesn't specify how to serialize `JsonNode` to `byte[]`, or what charset to use. |

**Fix:**

Add a **Body Serialization** note after the applyChanges phase table (after L218):

> **Body serialization:** The adapter serializes the transformed `JsonNode` to
> bytes via `objectMapper.writeValueAsBytes(transformedMessage.body())`. Jackson
> defaults to UTF-8 encoding. After calling `setBodyContent(bytes)` (which auto-
> updates `Content-Length`), the adapter MUST set the Content-Type header to
> `application/json` if the body was successfully transformed. If the original
> Content-Type had a charset parameter, it is replaced by the Jackson output charset
> (always UTF-8).
>
> For `NullNode` bodies (passthrough or non-JSON), the adapter does NOT rewrite
> Content-Type — the original value is preserved.

---

### GAP-5: `Content-Length` must be excluded from header diff

| Field | Value |
|-------|-------|
| ID | GAP-5 |
| Severity | 🟡 Medium |
| Status | ✅ Done |
| Affected lines | spec.md L221–243 (Header Application Strategy) |
| Root cause | `setBodyContent(bytes)` auto-updates Content-Length. If the diff-based header strategy then applies a transformed Content-Length from the `Message`, it could overwrite the correct auto-set value. |

**Fix:**

Add a note to the Header Application Strategy section (after L243):

> **Protected headers:** The diff-based strategy MUST exclude the following
> headers from modification:
>
> - `content-length` — managed automatically by `setBodyContent()`. Allowing the
>   transform spec to override it would cause body/length mismatch.
> - `transfer-encoding` — see IMP-1 note below.
>
> These headers are removed from both the "original" and "transformed" sets before
> computing the diff.

---

### IMP-1: Handle `Transfer-Encoding: chunked` after `setBodyContent()`

| Field | Value |
|-------|-------|
| ID | IMP-1 |
| Severity | 💡 Low |
| Status | ✅ Done |
| Affected lines | spec.md FR-002-01 applyChanges section |
| Root cause | When writing a transformed body via `setBodyContent(bytes)`, the body is no longer chunked. If the original response had `Transfer-Encoding: chunked`, it must be removed. |

**Fix:**

Add to the "Protected headers" note from GAP-5:

> After `setBodyContent()`, the adapter MUST remove `Transfer-Encoding` from the
> response headers if present. `setBodyContent()` sets a complete byte array with
> a known length — chunked encoding is no longer applicable. PingAccess may handle
> this automatically, but the adapter ensures correctness defensively.

---

### B4 Commit Plan

- [x] Apply GAP-2 fix (body serialization note)
- [x] Apply GAP-5 fix (protected headers note)
- [x] Apply IMP-1 fix (Transfer-Encoding handling)
- [ ] Commit: `fix(spec-002): body serialization charset, protected headers, Transfer-Encoding`

---

## Batch 5 — Configuration, Reload & Reliability

> **Theme:** Fill gaps around hot-reload lifecycle, input validation, and
> body size limits.

### GAP-3: No scenarios for `reloadIntervalSec > 0`

| Field | Value |
|-------|-------|
| ID | GAP-3 |
| Severity | 🟡 Medium |
| Status | ⬜ Not started |
| Affected lines | spec.md FR-002-04 (L326–333), scenario matrix |

**Fix:**

1. **FR-002-04 (L326–333):** Add reload failure behavior:

   > **Reload failure semantics:** If `TransformEngine.reload()` throws during a
   > scheduled reload (e.g., malformed YAML), the adapter logs a warning and
   > retains the previous valid `TransformRegistry`. The failed reload does NOT
   > disrupt in-flight requests — `TransformEngine`'s `AtomicReference`-based
   > swap ensures that the old registry remains active until a successful reload
   > replaces it (NFR-001-05).

2. Add reload scenarios to the matrix:

   > | S-002-29 | **Spec hot-reload (success):** `reloadIntervalSec=30` → spec YAML modified on disk → next poll reloads specs → new spec matches next request. |
   > | S-002-30 | **Spec hot-reload (failure):** `reloadIntervalSec=30` → malformed spec written to disk → reload fails with warning log → previous specs remain active → existing transforms unaffected. |

   _(Note: if B3 already added S-002-27..28, these become S-002-29..30.)_

---

### SEC-2: `specsDir` path traversal — validation note

| Field | Value |
|-------|-------|
| ID | SEC-2 |
| Severity | 🔐 Low |
| Status | ⬜ Not started |
| Affected lines | spec.md FR-002-04 (L317) |

**Fix:**

Add a validation note after the configuration table in FR-002-04:

> **Security note:** The `specsDir` and `profilesDir` fields accept arbitrary
> file system paths. While PingAccess admin access is a privileged context,
> the adapter SHOULD validate these paths in `configure()` as defense-in-depth:
> - Reject paths containing `..` segments.
> - Log the resolved absolute path at INFO level for audit trail.
> - Verify the directory exists and is readable.

---

### SEC-3: `ScheduledExecutorService` must use daemon thread

| Field | Value |
|-------|-------|
| ID | SEC-3 |
| Severity | 🔐 Medium |
| Status | ⬜ Not started |
| Affected lines | spec.md FR-002-04 (L329–333) |

**Fix:**

Expand the hot-reload implementation detail (L329–333):

Old:
> the adapter starts a `ScheduledExecutorService` (single daemon thread)

New:
> the adapter starts a `ScheduledExecutorService` with a named daemon thread:
>
> ```java
> Executors.newSingleThreadScheduledExecutor(r -> {
>     Thread t = new Thread(r, "mxform-spec-reload");
>     t.setDaemon(true);
>     return t;
> });
> ```
>
> The daemon thread ensures PingAccess shutdown is not blocked by the reload
> scheduler. The thread name aids diagnostics in thread dumps.

---

### SEC-4: No input body size limit — `body.read()` can OOM

| Field | Value |
|-------|-------|
| ID | SEC-4 |
| Severity | 🔐 Medium |
| Status | ⬜ Not started |
| Affected lines | spec.md Constraint #7 (L806–809) |

**Fix:**

Expand Constraint #7:

Old (L806–809):
> 7. **Body.isInMemory():** `body.isInMemory()` can be `false` for large
>    bodies (streamed from backend). `body.getContent()` reads the entire body
>    into memory. The adapter does not impose additional limits beyond the
>    core engine's `maxOutputSizeBytes` (NFR-001-07).

New:
> 7. **Body size and memory:** `body.isInMemory()` can be `false` for large
>    bodies (streamed from backend). The adapter calls `body.read()` to load
>    the body into memory before `getContent()`.
>
>    **Input body limit:** PingAccess enforces a configurable maximum body size
>    at the engine level (`body.read()` throws `AccessException` if exceeded).
>    The adapter does NOT impose an additional limit beyond PingAccess's built-in
>    enforcement. On `AccessException` from `body.read()`, the adapter falls back
>    to `NullNode` (per FR-002-01 body read failure strategy).
>
>    **Output body limit:** The core engine's `maxOutputSizeBytes` (NFR-001-07)
>    limits the transformed output size.
>
>    **Recommendation:** Administrators SHOULD configure PingAccess's engine-level
>    body size limit to prevent excessive memory consumption. The default PA limit
>    applies to all rules, not just this adapter.

---

### B5 Commit Plan

- [ ] Apply GAP-3 fixes (reload failure semantics + 2 scenarios)
- [ ] Apply SEC-2 fix (path validation note)
- [ ] Apply SEC-3 fix (daemon thread executor detail)
- [ ] Apply SEC-4 fix (body size constraint rewrite)
- [ ] Commit: `fix(spec-002): hot-reload lifecycle, path validation, body size limits`

---

## Batch 6 — Documentation, Trust Model & Polish

> **Theme:** Improve documentation quality, clarify design decisions, add
> missing schemas.

### SEC-1: Full identity data in `$session` — document trust model

| Field | Value |
|-------|-------|
| ID | SEC-1 |
| Severity | 🔐 Medium |
| Status | ⬜ Not started |
| Affected lines | spec.md FR-002-06 (L372–458) |

**Fix:**

Add a **Trust Model** note after the collision handling section (after L448):

> **Trust model:** The `$session` variable exposes the full identity context
> (subject, claims, OAuth metadata, session state) to JSLT expressions. This is
> by design — the adapter acts as a **trusted bridge** between PingAccess's
> identity subsystem and the transform engine.
>
> **Implications:**
> - Transform spec authors are trusted actors. A malicious spec could leak
>   sensitive identity data (e.g., `$session.tokenId`) into the response body.
> - The core engine's `sensitive` list (ADR-0019) can redact specific output
>   paths, but cannot prevent JSLT from **reading** session data.
> - In production, restrict write access to `specsDir` to authorized operators.
>
> **Future consideration:** A `SessionContextFilter` that selectively exposes
> only whitelisted `$session` fields could be added as a follow-up feature if
> multi-tenant spec authoring becomes a requirement.

---

### BUG-7: `ExchangeProperty` type — `Map<String, Object>` vs typed

| Field | Value |
|-------|-------|
| ID | BUG-7 |
| Severity | 💡 Low |
| Status | ⬜ Not started |
| Affected lines | spec.md FR-002-07 (L468–469) |

**Fix:**

Replace the `Map<String, Object>` type with a proper note:

Old (L468–469):
```java
private static final ExchangeProperty<Map<String, Object>> TRANSFORM_RESULT =
    ExchangeProperty.create("io.messagexform", "transformResult", Map.class);
```

New:
```java
private static final ExchangeProperty<TransformResultSummary> TRANSFORM_RESULT =
    ExchangeProperty.create("io.messagexform", "transformResult",
                            TransformResultSummary.class);
```

> **`TransformResultSummary` is an adapter-local record** (not the core
> `TransformResult`) to avoid Jackson relocation issues at the ExchangeProperty
> boundary. It contains only primitive/String fields that are safe to share
> across classloaders. See IMP-5 for the field schema.

---

### IMP-5: Document `ExchangeProperty` map key schema

| Field | Value |
|-------|-------|
| ID | IMP-5 |
| Severity | 🟡 Medium |
| Status | ⬜ Not started |
| Affected lines | spec.md FR-002-07 (L461–476) |

**Fix:**

Add a schema definition after the ExchangeProperty declaration:

> **`TransformResultSummary` schema:**
>
> | Field | Type | Description |
> |-------|------|-------------|
> | `specId` | `String` | Matched spec ID (null if no match) |
> | `specVersion` | `String` | Matched spec version (null if no match) |
> | `direction` | `String` | `"REQUEST"` or `"RESPONSE"` |
> | `durationMs` | `long` | Transform duration in milliseconds |
> | `outcome` | `String` | `"SUCCESS"`, `"PASSTHROUGH"`, or `"ERROR"` |
> | `errorType` | `String` | Error type code (null if no error) |
> | `errorMessage` | `String` | Human-readable error message (null if no error) |
>
> **Usage by downstream rules:**
> ```java
> exchange.getProperty(TRANSFORM_RESULT).ifPresent(summary -> {
>     LOG.info("Transform: spec={}, outcome={}, duration={}ms",
>              summary.specId(), summary.outcome(), summary.durationMs());
> });
> ```

---

### IMP-2: Explicitly justify async vs sync choice

| Field | Value |
|-------|-------|
| ID | IMP-2 |
| Severity | 💡 Low |
| Status | ⬜ Not started |
| Affected lines | spec.md FR-002-02 (L245–248) |

**Fix:**

Add a design-decision note after the FR-002-02 requirement statement:

> **Async vs Sync justification:** The SDK Javadoc recommends using
> `AsyncRuleInterceptor` only when the rule requires `HttpClient`. This adapter
> does not currently use `HttpClient`, making `RuleInterceptor` (sync) technically
> sufficient. However, `AsyncRuleInterceptorBase` is chosen because:
>
> 1. Future features (e.g., external schema validation, remote spec fetching)
>    may require HTTP callouts.
> 2. The async lifecycle (`CompletionStage<Outcome>`) composes cleanly with the
>    core engine's `TransformResult` type.
> 3. The vendor's own plugins (e.g., `PingAuthorizePolicyDecisionAccessControl`)
>    use `AsyncRuleInterceptorBase`, confirming it is the production-grade base.
>
> **Trade-off:** Slightly more complex lifecycle vs future extensibility.

---

### IMP-3: Log request path in `wrapResponse()` for tracing

| Field | Value |
|-------|-------|
| ID | IMP-3 |
| Severity | 💡 Low |
| Status | ⬜ Not started |
| Affected lines | spec.md FR-002-01 wrapResponse (L172–184) |

**Fix:**

Add a logging note after the wrapResponse mapping table:

> **Logging:** `wrapResponse()` SHOULD log the request path at DEBUG level:
> `LOG.debug("wrapResponse: {} {} → status={}", requestMethod, requestPath, statusCode)`.
> This aids correlation between request and response transforms in PingAccess
> server logs, especially when multiple applications/rules process the same exchange.

---

### B6 Commit Plan

- [ ] Apply SEC-1 fix (trust model note)
- [ ] Apply BUG-7 fix (ExchangeProperty type)
- [ ] Apply IMP-5 fix (ExchangeProperty schema)
- [ ] Apply IMP-2 fix (async justification)
- [ ] Apply IMP-3 fix (logging note)
- [ ] Commit: `fix(spec-002): trust model, ExchangeProperty schema, async justification, logging`

---

## Progress Tracker

| Batch | Items | Status | Commit |
|-------|-------|--------|--------|
| **B1** SDK API corrections | BUG-1, GAP-1, BUG-5 | ✅ Done | `0861a24` |
| **B2** Lifecycle & control flow | GAP-4, IMP-6, BUG-6, BUG-4 | ✅ Done | `09d18a1` |
| **B3** Scenario matrix | BUG-2, BUG-3, IMP-4 | ✅ Done | `fefa78e` |
| **B4** Body handling & headers | GAP-2, GAP-5, IMP-1 | ✅ Done | pending commit |
| **B5** Config, reload & reliability | GAP-3, SEC-2, SEC-3, SEC-4 | ⬜ Not started | — |
| **B6** Documentation & polish | SEC-1, BUG-7, IMP-2, IMP-3, IMP-5 | ⬜ Not started | — |

**Total: 22 items across 6 batches.**
