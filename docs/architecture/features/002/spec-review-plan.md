# Feature 002 Spec Review — Fix Plan

> **Purpose:** Track and execute fixes for all 20 issues from the 2026-02-11 critical review.
> **Lifecycle:** Delete this file after all fixes are committed.

---

## Live Tracker

| # | Issue | Severity | Batch | Status |
|---|-------|----------|-------|--------|
| 1 | Lifecycle step ordering contradiction | 🔴 Critical | 1 | ✅ Done |
| 2 | `TransformResult.errorResponse()` returns `JsonNode`, not `String` | 🔴 Critical | 1 | ✅ Done |
| 3 | `javax.inject` vs `jakarta.inject` ambiguity | 🔴 Critical | 1 | ✅ Done |
| 4 | Jackson boundary conversion missing in `buildSessionContext()` | 🟠 Significant | 1 | ✅ Done |
| 5 | `SessionStateSupport.getAttributes()` boundary issue | 🟠 Significant | 1 | ✅ Done |
| 6 | `applyChanges` direction default is dubious | 🟠 Significant | 2 | ✅ Done |
| 7 | No `Content-Encoding` / compressed body handling | 🟠 Significant | 2 | ✅ Done |
| 8 | Missing error response body for `handleResponse` DENY mode | 🟠 Significant | 2 | ✅ Done |
| 9 | Two `ObjectMapper` instances not documented | 🟡 Moderate | 2 | ✅ Done |
| 10 | `reloadIntervalSec` TEXT type — input validation gap | 🟡 Moderate | 3 | ✅ Done |
| 11 | `ExchangeProperty` equality semantics trap | 🟡 Moderate | 3 | ✅ Done |
| 12 | `wrapRequest` deep copy body clarification | 🟡 Moderate | 3 | ✅ Done |
| 13 | Header diff strategy missing capitalization note | 🟡 Moderate | 3 | ✅ Done |
| 14 | `TransformResultSummary` field sourcing gap | 🟡 Moderate | 3 | ✅ Done |
| 15 | Cosmetic: triple blank lines around L957–959 | 🔵 Minor | 4 | ✅ Done |
| 16 | Missing scenario: concurrent reload + request race | 🔵 Minor | 4 | ✅ Done |
| 17 | Missing scenario: non-JSON response body | 🔵 Minor | 4 | ✅ Done |
| 18 | `jakarta.validation-api` version mismatch risk | 🔵 Minor | 4 | ✅ Done |
| 19 | No metrics/counters for transform operations | 🔵 Minor | 5 | ⬜ Not started |
| 20 | Ping Maven repo URL — HTTP vs HTTPS | 🔵 Minor | 5 | ⬜ Not started |

**Progress: 18 / 20 complete**

---

## Batch 1 — Critical Fixes & Jackson Boundary Bugs (Issues 1–5)

**Theme:** Fix all 🔴 Critical issues + the most dangerous 🟠 Jackson boundary bugs.
These are the issues that would cause runtime crashes (`ClassCastException`, `NoSuchMethodError`, compile-time break).

### Issue 1: Lifecycle Step Ordering Contradiction

**Location:** spec.md §5 Lifecycle (~L574–580)
**Problem:** Steps 5 (configure) and 6 (validation) are numbered in the wrong order. The text says "PA now validates **before** `configure()` is called" but lists validation as step 6 after configure (step 5). The sentence also contains garbled legacy text ("was called in the old model").
**Fix:**
- Swap steps 5 and 6: Bean Validation becomes step 5, `configure()` becomes step 6.
- Clean up the garbled sentence to clearly state: "Since PA 5.0+, Bean Validation runs **before** `configure()`, so constraint annotations on PluginConfiguration fields are guaranteed to be enforced before the plugin receives them via `configure()`."
- Add a design-decision note: "**Implication for our adapter:** `configure()` can safely assume that all `@NotNull`, `@Min`, `@Max` constraints have already been validated. No need to re-check them."

### Issue 2: `TransformResult.errorResponse()` Returns `JsonNode`, Not `String`

**Location:** spec.md DENY mode code examples (~L283–284 and equivalent sections)
**Problem:** Code shows `result.errorResponse().getBytes(StandardCharsets.UTF_8)` but `errorResponse()` returns `JsonNode`, which has no `getBytes()` method.
**Fix:**
- Replace all instances of `result.errorResponse().getBytes(StandardCharsets.UTF_8)` with `objectMapper.writeValueAsBytes(result.errorResponse())`.
- Add a clarifying comment: `// errorResponse() returns JsonNode — serialize to bytes`.

### Issue 3: `javax.inject` vs `jakarta.inject` Ambiguity

**Location:** spec.md FR-002-10 dependency table (~L830) and injection guidance (~L598)
**Problem:** Spec text says "use `javax.inject.Inject`" but the dependency table lists `jakarta.inject:jakarta.inject-api:2.0.1`. These are different packages.
**Fix:**
- Research: Check the PA SDK 9.0.1 JAR to determine which inject API it uses.
- If PA SDK uses `javax.inject`: change the dependency to `javax.inject:javax.inject:1` and keep the text as-is.
- If PA SDK uses `jakarta.inject`: change the text to `jakarta.inject.Inject` and keep the dependency as-is.
- Add a design-decision note explaining the choice and the SDK evidence.

### Issue 4: Jackson Boundary Conversion Missing in `buildSessionContext()`

**Location:** spec.md §6 `buildSessionContext()` code example (~L640–643)  
**Problem:** `identity.getAttributes()` returns a PA-classloader `JsonNode`. Setting PA `JsonNode` values into a shaded `ObjectNode` via `session.set(key, value)` causes `ClassCastException` at runtime after Jackson relocation.
**Fix:**
- Add boundary conversion step before Layer 3 (OIDC claims):
  ```java
  // Boundary convert PA JsonNode → shaded JsonNode
  byte[] attrBytes = paObjectMapper.writeValueAsBytes(identity.getAttributes());
  JsonNode shadedAttributes = ourObjectMapper.readTree(attrBytes);
  ```
- Update the code to iterate `shadedAttributes` instead of raw `attributes`.

### Issue 5: `SessionStateSupport.getAttributes()` Boundary Issue

**Location:** spec.md §6 `buildSessionContext()` Layer 4 code (~L631)
**Problem:** Same as Issue 4 — `sss.getAttributes()` returns `Map<String, JsonNode>` with PA-classloader `JsonNode` values. Direct `session.set(key, value)` causes `ClassCastException`.
**Fix:**
- Add boundary conversion for each session state value:
  ```java
  sss.getAttributes().forEach((key, paValue) -> {
      byte[] valBytes = paObjectMapper.writeValueAsBytes(paValue);
      JsonNode shadedValue = ourObjectMapper.readTree(valBytes);
      session.set(key, shadedValue);
  });
  ```
- Add a design note after the code: "All PA-sourced `JsonNode` values must be boundary-converted before being set into the shaded ObjectNode. See FR-002-09 Constraint 8."

---

## Batch 2 — Significant Architecture Fixes (Issues 6–9)

**Theme:** Address remaining 🟠 Significant issues around adapter architecture, error handling, and missing constraints.

### Issue 6: `applyChanges` Direction Default Is Dubious

**Location:** spec.md applyChanges section (~L235–236)
**Problem:** The public SPI method defaults to response-only behavior, masking directional bugs.
**Fix:**
- Change `applyChanges(Message, Exchange)` to throw `UnsupportedOperationException("Use applyRequestChanges() or applyResponseChanges() directly")`.
- Document this as a conscious design decision: "The PA adapter overrides the GatewayAdapter SPI method to force callers to use direction-specific helpers, preventing accidental response-only application during request phase."

### Issue 7: No `Content-Encoding` / Compressed Body Handling

**Location:** spec.md — missing from body handling sections
**Problem:** No mention of gzip/deflate compressed bodies. Parsing compressed bytes as JSON would silently fail (NullNode fallback).
**Fix:**
- Add a new constraint (Constraint 10) to FR-002-09:
  > "**Constraint 10 — No compressed body support (v1).** The adapter does NOT decompress `Content-Encoding: gzip/deflate/br` bodies. If the backend sends a compressed body, JSON parsing will fail and the body falls back to `NullNode` (headers-only transform). PingAccess administrators SHOULD configure sites to disable response compression for endpoints using this rule, or use PA's built-in `OutboundContentRewriteInterceptor` upstream to handle decompression."
- Add a follow-up note: "Compressed body support may be added in a future version."

### Issue 8: Missing Error Response Body for `handleResponse` DENY Mode

**Location:** spec.md handleResponse DENY path (~L385, L853)
**Problem:** Unclear who produces the error body when DENY happens during response phase — is it `TransformResult.errorResponse()` or an adapter-constructed body?
**Fix:**
- Add explicit documentation:
  > "In `handleResponse()` DENY mode, the error body comes from `TransformResult.errorResponse()` — the same as request-phase DENY. The adapter serializes it via `objectMapper.writeValueAsBytes()`, sets it as the response body via `exchange.getResponse().setBodyContent()`, overwrites the status, and sets Content-Type to `application/problem+json`."
  > "If `wrapResponse()` itself fails (e.g., IOException during `body.read()`), the adapter constructs its own RFC 9457 error body with `type: urn:messagexform:error:adapter:wrap-failure` and status 502."

### Issue 9: Two `ObjectMapper` Instances Not Documented

**Location:** spec.md — missing from FR-002-09 or FR-002-01
**Problem:** Jackson relocation implies two ObjectMapper instances, but spec doesn't explain how the PA-native one is obtained.
**Fix:**
- Add a design note to FR-002-09 after Constraint 8:
  > "**Dual ObjectMapper pattern:** The adapter maintains two ObjectMapper instances:
  > 1. `shadedMapper` — our relocated Jackson (created in `configure()`). Used for all adapter-internal JSON operations.
  > 2. `paMapper` — obtained from PA's classloader via `new com.fasterxml.jackson.databind.ObjectMapper()` (unshaded, resolves to PA's Jackson). Used **solely** for boundary conversion in `buildSessionContext()`.
  >
  > Since Jackson is relocated in the shadow JAR, `com.fasterxml.jackson.databind.ObjectMapper` at compile time becomes `io.messagexform.shaded.jackson.databind.ObjectMapper`. The PA-native ObjectMapper must be instantiated reflectively or obtained via an unshaded utility class compiled separately."
- Add an alternative: "If reflective instantiation is too fragile, convert at the byte-array boundary using PA's `Body.getContent()` / `Response.setBodyContent()` which use `byte[]` (no Jackson types cross the boundary)."

---

## Batch 3 — Moderate Precision Fixes (Issues 10–14)

**Theme:** Validation constraints, documentation precision, and field sourcing gaps.

### Issue 10: `reloadIntervalSec` Input Validation Gap

**Location:** spec.md config field definition (~L428)
**Fix:**
- Add `@Min(0) @Max(86400)` to the `reloadIntervalSec` field definition.
- Change field type from `TEXT` to `TEXT` with `Integer` Java type annotation.
- Update `@Help` content: "Interval in seconds between automatic config reload checks. 0 = disabled. Maximum 86400 (24 hours)."
- Add a validation note: "Non-numeric input causes a Jakarta Bean Validation error, surfaced in the PA admin UI."

### Issue 11: `ExchangeProperty` Equality Semantics Trap

**Location:** spec.md ExchangeProperty definitions (~L728–743)
**Fix:**
- Add a caution note after the property definitions:
  > "⚠️ **Namespace uniqueness:** Per SDK §2, `ExchangeProperty` equality is based on namespace + identifier only (type is ignored). All adapter-defined properties use namespace `io.messagexform` with unique identifiers (`transformDenied`, `transformResult`). Plugin authors extending this adapter MUST NOT reuse these identifiers with different types."

### Issue 12: `wrapRequest` Deep Copy Body Clarification

**Location:** spec.md wrapRequest body parsing (~L116)
**Fix:**
- Add a clarifying sentence:
  > "Parsing the body via `objectMapper.readTree(body.getContent())` inherently produces a new, independent `JsonNode` tree — satisfying the ADR-0013 deep-copy requirement without an explicit `deepCopy()` call."

### Issue 13: Header Diff Strategy Missing Capitalization Note

**Location:** spec.md header diff section (~L266–284)
**Fix:**
- Add a sentence: "The original header name snapshot is normalized to lowercase before diff comparison, matching the `Message.headers()` key normalization. This ensures case-insensitive diff correctness regardless of PA's original header casing."

### Issue 14: `TransformResultSummary` Field Sourcing Gap

**Location:** spec.md TransformResultSummary section (~L728–743)
**Fix:**
- Document field sourcing:
  > "The `specId` and `specVersion` fields are populated from the `TransformSpec` that was matched during profile resolution. The adapter obtains these from the `TransformRegistry` API (which returns the matched spec metadata alongside the result). If the core engine's `TransformResult` does not expose spec identification, the adapter tracks the matched spec ID from the profile resolution step and attaches it to the summary."
- If this reveals a core API gap, add an open question to `open-questions.md`.

---

## Batch 4 — Scenarios & Dependency Fixes (Issues 15–18)

**Theme:** Missing test scenarios, cosmetic cleanup, dependency version verification.

### Issue 15: Cosmetic — Triple Blank Lines

**Location:** spec.md ~L957–959
**Fix:** Remove extra blank lines, leaving a single blank line between sections.

### Issue 16: Missing Scenario — Concurrent Reload + Request Race

**Location:** spec.md scenarios section
**Fix:**
- Add scenario:
  > "**S-002-31:** Concurrent reload during active transform — reload swaps `AtomicReference<TransformRegistry>` while a transform is in flight → in-flight transform completes using its snapshot of the old registry (Java reference semantics guarantee this) → next request uses the new registry. Outcome: no data corruption, no locking, no request failure. Test: trigger reload in a background thread while a slow transform is executing."

### Issue 17: Missing Scenario — Non-JSON Response Body

**Location:** spec.md scenarios section
**Fix:**
- Add scenario:
  > "**S-002-32:** Backend returns `text/html` response body — `wrapResponse()` attempts JSON parse, fails, falls back to `NullNode` body → response-direction transforms can still operate on headers and status code → body passthrough unmodified. Outcome: PASSTHROUGH for body transforms, SUCCESS for header-only transforms."

### Issue 18: `jakarta.validation-api` Version Mismatch Risk

**Location:** spec.md FR-002-10 dependency table (~L829)
**Fix:**
- Add a note: "Verify that PA 9.0.1 ships Jakarta Validation 3.0+ (compatible with Hibernate Validator 7.0.5). If PA ships Validation 2.0 (javax.validation), the dependency should be `javax.validation:validation-api:2.0.1.Final` instead. Check `<PA_HOME>/lib/` for the actual validation-api JAR version."
- If PA ships the older `javax.validation`, update the dependency entry.

---

## Batch 5 — Follow-up Notes & Polish (Issues 19–20)

**Theme:** Non-functional enhancements and infrastructure notes.

### Issue 19: No Metrics/Counters for Transform Operations

**Location:** spec.md — new section or FR-002-12
**Fix:**
- Add a "Future Enhancements" or "Non-Goals (v1)" note:
  > "**Metrics & observability (v1 non-goal):** The v1 adapter does not expose JMX MBeans, Micrometer metrics, or OpenTelemetry spans for transform operations. PingAccess's built-in audit logging captures rule execution events at the PA level. Per-transform metrics (success/error/passthrough counters, latency histograms) are deferred to a future version. The `TransformResultSummary` ExchangeProperty provides per-request observability for downstream rules."

### Issue 20: Ping Maven Repo URL — HTTP vs HTTPS

**Location:** spec.md FR-002-10 (~L834)
**Fix:**
- Update the repository URL to match the SDK documentation: `http://maven.pingidentity.com/release/` (HTTP, not HTTPS).
- Add a note: "The Ping Identity Maven repository uses HTTP (not HTTPS) per the official SDK documentation. If your organization requires HTTPS-only repositories, mirror the SDK artifacts to an internal repository manager."

---

## Execution Protocol

1. **Before each batch:** Read the current spec to get fresh line numbers.
2. **During batch:** Apply all fixes, updating the tracker after each issue.
3. **After each batch:** Commit with message `docs(spec-002): batch N — <summary>`.
4. **After all batches:** Delete this plan file and commit: `chore: remove completed spec review plan`.
