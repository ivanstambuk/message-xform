# ADR-0024 – Error Type Catalogue

Date: 2026-02-08 | Status: Accepted

## Context

The Feature 001 spec defines the engine's failure *behaviours* — structured logging,
error response generation (ADR-0022), abort-on-failure for pipeline chains — but
does not enumerate the *programmatic* exception types that cross the engine→adapter
boundary.

Currently only two exception types appear in the Java interfaces (FR-001-02):
- `ExpressionCompileException` (thrown by `ExpressionEngine.compile()`)
- `ExpressionEvalException` (thrown by `CompiledExpression.evaluate()`)

However, the spec describes at least five additional failure causes that lack named
types:
- Invalid YAML syntax in a spec file
- Invalid JSON Schema in a spec's `input.schema` or `output.schema`
- Missing or unresolvable spec version references in a profile
- Ambiguous match resolution between profile entries
- Invalid RFC 9535 path syntax in the `sensitive` block
- Evaluation-time budget exceeded (time or output size)
- Evaluation-time schema validation failure (strict mode)

Adapter implementors need a stable, complete set of exception types to write correct
error handling code. Without a defined hierarchy, each adapter invents its own catch
blocks, guesses which exceptions are recoverable, and risks misclassifying failures.
In PingAccess specifically, the adapter must map load-time exceptions to `Outcome.DENY`
at startup and evaluation-time exceptions to error response generation.

### Options Considered

- **Option A – Spec-level Error Catalogue** (chosen)
  - Add a normative Error Catalogue section to `spec.md` enumerating every exception
    type, its abstract parent, when it occurs (load/evaluation), and the expected
    adapter response.
  - Pros: definitive adapter contract, spec-driven, enables scenarios to assert on
    specific types, forces precision about failure modes.
  - Cons: adds maintenance burden (new failure modes require updating the catalogue).

- **Option B – SPI boundary types only** (rejected)
  - Keep only the two existing exception types (`ExpressionCompileException`,
    `ExpressionEvalException`) as the public contract. All other errors are wrapped.
  - Pros: simpler spec surface.
  - Cons: adapters cannot distinguish failure causes — all load-time failures are
    opaque. Operationally misleading exception names.

- **Option C – Defer to implementation** (rejected)
  - Don't define error types in the spec; let them emerge from implementation.
  - Pros: zero spec burden.
  - Cons: violates Principle 1 (Specifications Lead Execution). Multiple adapters
    would handle the same errors inconsistently.

Related ADRs:
- ADR-0022 – Error Response on Transform Failure (error response mechanism)
- ADR-0001 – Mandatory JSON Schema (schema validation errors)
- ADR-0012 – Pipeline Chaining Semantics (chain abort semantics)
- ADR-0019 – Sensitive Field Marking (sensitive path syntax errors)

## Decision

We adopt **Option A – Spec-level Error Catalogue**.

The spec defines a **two-tier exception hierarchy** that separates load-time
configuration errors from per-request evaluation errors:

### Tier 1: Load-Time Exceptions (`TransformLoadException`)

All load-time exceptions inherit from `TransformLoadException`. These are thrown
during spec/profile loading (`TransformEngine.loadSpec()`, `loadProfile()`). They
indicate **configuration problems** that must be resolved before traffic can flow.

| Exception | Cause | Example |
|-----------|-------|---------|
| `SpecParseException` | Invalid YAML syntax in a spec file | Missing colon, bad indentation, non-UTF-8 encoding |
| `ExpressionCompileException` | Expression syntax error in any engine | JSLT: `if (.x "bad"` — missing closing paren |
| `SchemaValidationException` | JSON Schema block itself is invalid (2020-12) | `type: not-a-type`, `required: 42` instead of array |
| `ProfileResolveException` | Profile references a missing spec, unknown version, or has ambiguous match | `spec: callback-prettify@3.0.0` when only `@1.0.0` is loaded |
| `SensitivePathSyntaxError` | Invalid RFC 9535 path in `sensitive` list | `callbacks[*].input` (missing `$` prefix) |

Adapters MUST surface load-time exceptions during startup or reload. The engine
MUST NOT silently accept broken configuration. Gateway-specific handling:
- PingAccess: `configure()` → catch `TransformLoadException` → `Outcome.DENY`
- PingGateway: `init()` → catch → fail filter startup
- Standalone: `main()` → catch → exit with non-zero status

### Tier 2: Evaluation-Time Exceptions (`TransformEvalException`)

All evaluation-time exceptions inherit from `TransformEvalException`. These are
thrown per-request during `TransformEngine.transform()`. The engine catches them
internally and produces a **configurable error response** (ADR-0022) — adapters
do NOT typically catch these directly unless they need custom error handling beyond
the engine's default.

| Exception | Cause | Example |
|-----------|-------|---------|
| `ExpressionEvalException` | Expression execution error | JSLT: undefined variable `$missingVar`, type error, null dereference |
| `EvalBudgetExceededException` | `max-eval-ms` or `max-output-bytes` exceeded | Expression takes 200ms against 50ms budget |
| `InputSchemaViolation` | Strict-mode input validation failure | Input missing `required` field, wrong type |

The engine's error response mechanism (ADR-0022) wraps these into RFC 9457 or
custom error responses. Adapters return the error response to the caller.

### Exception Hierarchy (Java)

```
TransformException (abstract — never thrown directly)
├── TransformLoadException (abstract — load-time parent)
│   ├── SpecParseException
│   ├── ExpressionCompileException        ← already in FR-001-02
│   ├── SchemaValidationException
│   ├── ProfileResolveException
│   └── SensitivePathSyntaxError
└── TransformEvalException (abstract — evaluation-time parent)
    ├── ExpressionEvalException           ← already in FR-001-02
    ├── EvalBudgetExceededException
    └── InputSchemaViolation
```

All exceptions carry:
- `specId` (String, nullable) — the spec that triggered the error, if known.
- `detail` (String) — human-readable error description.
- `phase` (enum: `LOAD`, `EVALUATION`) — derived from the tier.

`TransformLoadException` additionally carries:
- `source` (String, nullable) — file path or resource identifier of the failing
  spec/profile.

`TransformEvalException` additionally carries:
- `chainStep` (Integer, nullable) — the pipeline chain step index (e.g., `2` in
  a 3-step chain). Null when not in a chain.

### Error Response `type` URNs

The `error-response` mechanism (ADR-0022, FR-001-07) uses the exception type to
populate the RFC 9457 `type` field:

| Exception | URN `type` |
|-----------|-----------|
| `ExpressionEvalException` | `urn:message-xform:error:expression-eval-failed` |
| `EvalBudgetExceededException` | `urn:message-xform:error:eval-budget-exceeded` |
| `InputSchemaViolation` | `urn:message-xform:error:schema-validation-failed` |

The custom template's `{{error.type}}` placeholder resolves to the simple class
name (e.g., `ExpressionEvalException`).

## Consequences

Positive:
- Adapter implementors have a definitive, testable contract for error handling.
- Scenarios can assert on specific exception types (e.g., `expected_error.type:
  SpecParseException`).
- The spec is precise about every failure mode — no implementation guesswork.
- Gateway-specific adapter specs can document exact exception → outcome mappings.
- Error response `type` URNs are deterministic and machine-parseable.

Negative / trade-offs:
- The catalogue must be maintained as new failure modes are discovered. New
  exception types require a spec update.
- Eight named types is more surface area than the original two. However, each
  type is directly observable in production — the specificity aids diagnostics.

References:
- Feature 001 spec: `docs/architecture/features/001/spec.md` (FR-001-02, FR-001-07)
- ADR-0022: Error Response on Transform Failure
- Q-024: Error type hierarchy underspecified (resolved by this ADR)
