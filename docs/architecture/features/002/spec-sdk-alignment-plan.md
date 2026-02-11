# Spec ↔ SDK Guide Alignment Plan

> **Purpose:** Detailed implementation plan for aligning `spec.md` with the
> enriched `pingaccess-sdk-guide.md`. Each batch groups logically related gaps.
> Delete this file when all batches are complete.

| Field | Value |
|-------|-------|
| Created | 2026-02-11 |
| Target file | `docs/architecture/features/002/spec.md` |
| Reference file | `docs/architecture/features/002/pingaccess-sdk-guide.md` |
| Total gaps | 14 (5 worth updating, 5 nice-to-have, 4 no-action) |
| Actionable gaps | 10 (grouped into 5 batches) |

---

## Live Tracker

| Batch | Title | Gaps | Status |
|-------|-------|------|--------|
| 1 | Plugin Lifecycle & Injection | Gap 1, Gap 2 | ✅ Complete |
| 2 | Configuration & UI Enrichment | Gap 4, Gap 5, Gap 6, Gap 9 | ✅ Complete |
| 3 | Teardown & Resource Cleanup | Gap 14 | ✅ Complete |
| 4 | Test Strategy Enrichment | Gap 7, Gap 8 | ⬜ Not started |
| 5 | Behavioral Notes & Edge Cases | Gap 10, Gap 13 | ⬜ Not started |

**Skipped gaps (no action needed):**
- Gap 3 (`ConfigurationType` full enum) — spec only needs types we use
- Gap 6 (`ConfigurationModelAccessor` constraint) — our adapter doesn't use
  dynamic dropdowns; moved to Batch 2 as a minor note instead
- Gap 11 (`Body.parseFormParams()`) — adapter treats non-JSON as `NullNode`
- Gap 12 (`URISyntaxException`) — spec already handles correctly

---

## Batch 1 — Plugin Lifecycle & Injection

**Gaps covered:** Gap 1 (7-step lifecycle), Gap 2 (injection closed list)

**Target sections:** FR-002-05 (Plugin Lifecycle), FR-002-02 (AsyncRuleInterceptor Plugin)

### Gap 1: Enrich FR-002-05 with Official 7-Step Lifecycle

**Problem:** The spec has a simplified 5-step lifecycle that misses critical
subtleties documented in SDK guide §1 (lines 139–175).

**What to add to FR-002-05:**

Replace the current 5-step lifecycle (lines 478–489) with the official 7-step
sequence. Key additions:

1. **Step 2 — Spring initialization order is undefined:** Add a note that
   the order in which the `RuleInterceptor` and `PluginConfiguration` beans
   are processed by Spring is **not defined**. The adapter must NOT assume
   its config is available before `configure()` is called.

2. **Step 4 — JSON must include all fields:** Add a warning:
   > ⚠️ The JSON plugin configuration **must contain a JSON member for each
   > field**, regardless of implied value. Failure to include a field — even
   > if it has a Java default — can lead to errors.

   This affects the admin API contract — when creating/updating the rule via
   the PA admin REST API, all config fields must be present in the JSON payload.

3. **Step 6 — Validation before `configure()`:** Clarify that as of PA 5.0+,
   Bean Validation (`@NotNull`, `@Size`, etc.) runs **before** `configure()`
   is called. The adapter should NOT duplicate constraint checks in
   `configure()` that are already covered by annotations.

**Exact edit location in spec.md:**

```
FR-002-05: Plugin Lifecycle (lines 474–496)

Replace the current numbered list (lines 479–489):
  1. Construction → 2. Dependency injection → 3. Configuration →
  4. Runtime → 5. Teardown

With the official 7-step sequence:
  1. Annotation interrogation → 2. Spring initialization (order undefined) →
  3. Name assignment → 4. JSON → Configuration mapping (all-fields gotcha) →
  5. configure() call → 6. Bean Validation (pre-configure since PA 5.0) →
  7. Available for requests

Keep the existing Success/Failure aspects table and status unchanged.
```

**SDK guide references:** §1 lines 139–175 (lifecycle), lines 161–163
(JSON field requirement), lines 167–170 (validation order).

### Gap 2: Document Injection Closed List

**Problem:** The spec mentions `@Inject` in FR-002-05 but doesn't document
the definitive closed list of injectable classes or the Spring avoidance rule.

**What to add to FR-002-05 or FR-002-02:**

Add a blockquote note after the lifecycle steps:

```markdown
> **Injection constraints (from SDK guide §1):**
> Only 3 classes are available for injection: `TemplateRenderer`,
> `ThirdPartyServiceModel`, and `HttpClient`. No other PA-internal classes
> can be injected. Use `javax.inject.Inject` — NOT Spring's `@Autowired` —
> to protect against PA internal changes. Note that
> `AsyncRuleInterceptorBase` already pre-wires `HttpClient` and
> `TemplateRenderer` via setter injection, so explicit `@Inject` is only
> needed if implementing the raw SPI interface.
```

**Exact edit location:** After the lifecycle steps in FR-002-05 (around
line 489), before the Aspect table.

**SDK guide references:** §1 lines 182–221 (injection section).

---

## Batch 2 — Configuration & UI Enrichment

**Gaps covered:** Gap 4 (@UIElement help), Gap 5 (enum auto-discovery),
Gap 6 (ConfigurationModelAccessor note), Gap 9 (ErrorHandlerUtil decision)

**Target sections:** FR-002-04 (Plugin Configuration), FR-002-11 (Error
Handling), Appendix D

### Gap 5: Use Java Enums for SELECT Fields (Primary)

**Problem:** FR-002-04 defines `errorMode` and `schemaValidation` as SELECT
fields with explicit `PASS_THROUGH`/`DENY` and `STRICT`/`LENIENT` options,
but doesn't specify whether they should use Java enums.

**What to change in FR-002-04:**

Add a design note after the configuration field table (line 421):

```markdown
> **Enum auto-discovery:** The `errorMode` and `schemaValidation` fields
> SHOULD be backed by Java enums. When a `SELECT` field's type is an `Enum`
> and no `@Option` annotations are provided, `ConfigurationBuilder.from()`
> auto-generates `ConfigurationOption` instances from the enum constants (SDK
> guide §7). This eliminates manual `@Option` annotations:
>
> ```java
> public enum ErrorMode {
>     PASS_THROUGH("Pass Through"),
>     DENY("Deny");
>     private final String label;
>     ErrorMode(String label) { this.label = label; }
>     @Override public String toString() { return label; }
> }
>
> public enum SchemaValidation {
>     STRICT("Strict"),
>     LENIENT("Lenient");
>     private final String label;
>     SchemaValidation(String label) { this.label = label; }
>     @Override public String toString() { return label; }
> }
> ```
```

Also update the configuration field table:
- `errorMode` type: change from `SELECT` to `SELECT (enum: ErrorMode)`
- `schemaValidation` type: change from `SELECT` to `SELECT (enum: SchemaValidation)`

**SDK guide references:** §7 lines 898–920 (enum auto-discovery).

### Gap 4: Add @Help Annotations to Config Fields

**Problem:** The SDK guide documents `@Help(content, title, url)` annotations
for inline help text in the admin UI. FR-002-04 doesn't specify help text.

**What to add to FR-002-04:**

After the enum auto-discovery note, add:

```markdown
> **Help text:** Each `@UIElement` field SHOULD include `@Help` annotations
> with inline documentation for administrators:
>
> | Field | Help Content |
> |-------|-------------|
> | `specsDir` | "Absolute path to the directory containing transform spec YAML files. Must not contain '..' segments." |
> | `profilesDir` | "Absolute path to the directory containing transform profile files. Leave empty to use specs without profiles." |
> | `activeProfile` | "Name of the profile to activate. Leave empty for no profile filtering." |
> | `errorMode` | "PASS_THROUGH: log errors and continue with original message. DENY: reject the request/response with an RFC 9457 error." |
> | `reloadIntervalSec` | "Interval in seconds for re-reading spec/profile files from disk. Set to 0 to disable (specs loaded only at startup)." |
> | `schemaValidation` | "STRICT: reject specs failing JSON Schema validation. LENIENT: log warnings but accept specs." |
```

**SDK guide references:** §7 lines 874–896 (@Help, @Option, @ParentField).

### Gap 9: Document ErrorHandlerUtil Decision

**Problem:** The SDK guide documents `ErrorHandlerUtil.getConfigurationFields()`
for appending standard PA error handler UI fields. The spec doesn't document
whether we use this or not.

**What to add:**

Add a design decision note to FR-002-11 (Error Handling), after the error mode
table (around line 749):

```markdown
> **PA-native error handler integration:** The adapter does NOT use
> `ErrorHandlerUtil.getConfigurationFields()` or `ErrorHandlerConfigurationImpl`.
> **Rationale:** Our error responses use RFC 9457 `application/problem+json`
> format (ADR-0022, ADR-0024), which is semantically different from PA's
> built-in HTML template-based error pages. Mixing both would confuse
> administrators — the PA error handler fields (template file, content type,
> status message) would be misleading when the adapter always produces JSON.
> If PA-native error templates are desired in the future, this can be added
> as a follow-up by appending `ErrorHandlerUtil.getConfigurationFields()` to
> `getConfigurationFields()` and adding a `@JsonUnwrapped @Valid` error
> handler config field (see SDK guide §7 for the pattern).
```

**SDK guide references:** §7 lines 1196–1221 (ErrorHandlerConfigurationImpl,
ErrorHandlerUtil).

### Gap 6: ConfigurationModelAccessor Usage Note (Minor)

**What to add:**

Brief note in Appendix D (line 1072–1075):

```markdown
> **Runtime constraint:** `ConfigurationModelAccessor` instances must only
> be used inside `configure()`, never during request processing (SDK guide §7).
```

**SDK guide references:** §7 lines 1011–1012.

---

## Batch 3 — Teardown & Resource Cleanup

**Gaps covered:** Gap 14 (`@PreDestroy` for `ScheduledExecutorService`)

**Target sections:** FR-002-04 (Plugin Configuration — reload section),
FR-002-05 (Plugin Lifecycle — teardown)

### Gap 14: ScheduledExecutorService Shutdown

**Problem:** FR-002-04 starts a `ScheduledExecutorService` daemon thread for
spec reload (lines 428–436) but does not document how it is shut down. The
SDK guide mentions `@PreDestroy` annotations for resource cleanup (§1, line
175). While the daemon thread flag prevents JVM hang, a clean shutdown is
still best practice.

**What to change in FR-002-04:**

After the `ScheduledExecutorService` creation code (line 436), add:

```markdown
**Shutdown:** The adapter SHOULD shut down the executor when the plugin is
decommissioned. Two options:

1. **`@PreDestroy` annotation** (preferred):
   ```java
   @PreDestroy
   void shutdown() {
       if (reloadExecutor != null) {
           reloadExecutor.shutdownNow();
       }
   }
   ```

2. **Daemon thread** (current fallback):
   The thread is already created with `t.setDaemon(true)`, ensuring PA
   shutdown is not blocked even without explicit cleanup. This is the
   minimum safety net.

Both strategies are applied: `@PreDestroy` for orderly shutdown, daemon
flag as a last-resort safety net.
```

**What to change in FR-002-05 (teardown):**

Update line 489 ("Teardown: No explicit destroy...") to:

```markdown
5. **Teardown:** No explicit destroy lifecycle for most plugins. Plugins
   are garbage-collected on PA restart. For plugins with background threads
   (e.g., spec reload scheduler), use `@PreDestroy` to release resources
   cleanly. The daemon thread flag provides a fallback safety net.
```

**SDK guide references:** §1 line 175 (`@PreDestroy` or finalizers).

---

## Batch 4 — Test Strategy Enrichment

**Gaps covered:** Gap 7 (ServiceFactory for tests), Gap 8 (SPI verification)

**Target section:** Test Strategy (lines 894–926)

### Gap 8: Add SPI Registration Verification Test

**Problem:** FR-002-08 describes the SPI file but doesn't include a test
verification pattern. SDK guide §11 (lines 1727–1750) documents the
`ServiceFactory.isValidImplName()` pattern.

**What to add to Test Strategy → Unit Tests section:**

After `MessageTransformConfigTest` (line 916), add a new test class entry:

```markdown
- **`SpiRegistrationTest`** — Verifies that the adapter plugin is correctly
  registered via `META-INF/services/`. Uses `ServiceFactory.isValidImplName()`:

  ```java
  @Test
  void pluginIsRegisteredViaSpi() {
      assertTrue(ServiceFactory.isValidImplName(
          AsyncRuleInterceptor.class,
          "io.messagexform.pingaccess.MessageTransformRule"
      ));
  }

  @Test
  void nonPluginClassIsNotRegistered() {
      assertFalse(ServiceFactory.isValidImplName(
          AsyncRuleInterceptor.class,
          "io.messagexform.pingaccess.PingAccessAdapter"
      ));
  }
  ```
```

Also add a new planned source file to the Source Files table (line 956):

```markdown
| SRC-002-07 | `adapter-pingaccess/src/test/java/io/messagexform/pingaccess/SpiRegistrationTest.java` | SPI registration verification |
```

**SDK guide references:** §11 lines 1727–1750 (SPI registration test pattern).

### Gap 7: Note ServiceFactory for Test Infrastructure

**Problem:** Test Strategy mentions mock `Exchange` objects but doesn't
reference `ServiceFactory` for creating real SDK objects.

**What to add to Test Strategy:**

After the existing mock-pattern blockquote (line 919), add:

```markdown
> **SDK test infrastructure:** When Mockito mocks are insufficient (e.g.,
> integration-level tests that need real `Body`, `Headers`, or
> `ResponseBuilder` instances), use `ServiceFactory`:
>
> | Factory | Method | Creates |
> |---------|--------|---------|
> | `ServiceFactory.bodyFactory()` | `createBody(byte[])`, `createEmptyBody()` | Real `Body` instances |
> | `ServiceFactory.headersFactory()` | `createHeaders()`, `createHeaders(List<HeaderField>)` | Real `Headers` instances |
> | `ResponseBuilder` static factories | `newInstance(HttpStatus)`, `ok()`, etc. | Real `Response` instances |
>
> **Note:** Using `ServiceFactory` requires the PA SDK on the test classpath
> (already available as `compileOnly` → add as `testCompileOnly` if needed).
> For most unit tests, Mockito mocks are preferred. `ServiceFactory` is for
> integration tests that exercise real SDK object behavior.
```

**SDK guide references:** §12 lines 1960–1991 (ServiceFactory, BodyFactory,
HeadersFactory).

---

## Batch 5 — Behavioral Notes & Edge Cases

**Gaps covered:** Gap 10 (TemplateRenderer non-usage), Gap 13 (PA-specific
status codes)

**Target sections:** FR-002-02 (AsyncRuleInterceptor Plugin), Constraints

### Gap 10: Document TemplateRenderer Non-Usage Decision

**Problem:** FR-002-02 mentions `getTemplateRenderer()` in the context of
`getErrorHandlingCallback()` but doesn't explicitly state the adapter does
NOT use `TemplateRenderer` for error rendering.

**What to add:**

In FR-002-02, after the `getErrorHandlingCallback()` item (around line 353),
add a note:

```markdown
> **TemplateRenderer usage:** The adapter does NOT use `TemplateRenderer`
> for error responses. Error bodies are generated as RFC 9457 JSON
> (`application/problem+json`) directly, without PA's Velocity template
> engine. `TemplateRenderer` is only referenced indirectly via
> `RuleInterceptorErrorHandlingCallback` (which handles unhandled exceptions
> — not transform failures).
```

**SDK guide references:** §12 lines 1993–2009 (TemplateRenderer).

### Gap 13: Document PA-Specific Non-Standard Status Codes

**Problem:** The SDK documents two internal PA status codes: `HttpStatus.ALLOWED`
(277) and `HttpStatus.REQUEST_BODY_REQUIRED` (477). The spec doesn't mention
these.

**What to add to Constraints section:**

After Constraint 8 (line 1005), add Constraint 9:

```markdown
9. **PA-specific status codes:** PingAccess defines two non-standard HTTP
   status codes: `HttpStatus.ALLOWED` (277) and `HttpStatus.REQUEST_BODY_REQUIRED`
   (477). These are PA-internal codes used by the engine — the adapter MUST NOT
   map to or from these codes. If the backend returns a non-standard status code,
   the adapter passes it through unchanged. Transform specs targeting status codes
   (ADR-0003) should only use standard HTTP status codes (100–599).
```

**SDK guide references:** §12 lines 1918–1923 (PA-specific status codes).

---

## Implementation Checklist

For each batch, the implementer should:

1. **Read** the referenced SDK guide sections to confirm context.
2. **Apply edits** to `spec.md` using the exact locations and content described.
3. **Verify** that no existing spec content is contradicted by the additions.
4. **Update the tracker** at the top of this file (change ⬜ → ✅).
5. **Commit** with message format: `docs(spec-002): batch N — <title>`.

When all 5 batches show ✅, **delete this file** and commit:
`chore: remove completed spec-SDK alignment plan`.
