# ADR-0014 – Mapper Invocation Model: Transform-Level Sequential Directive

Date: 2026-02-07 | Status: Accepted

## Context

FR-001-08 (Reusable Mappers) specifies that transform specs MAY define reusable named
expressions under a `mappers` block, referenced via `mapperRef`. However, the spec did
not define **how** mappers are invoked — whether they are applied as declarative YAML
directives (sequenced at the spec level), as inline functions callable within JSLT
expressions, or both.

This matters because the invocation model determines:
- Engine-agnosticism (JOLT has no function concept, JSLT does)
- Implementation complexity (function injection vs. sequential evaluation)
- Consistency with ADR-0008 (single expression per direction, composition is declarative)

### Options Considered

- **Option A – Transform-level sequential directive only** (chosen)

  `mapperRef` is a YAML-level `apply` directive that sequences named mappers with the
  main `expr`. Mappers are NOT callable from within JSLT expressions. The engine runs
  steps in declaration order: each step's output feeds the next step's input.

  Example:
  ```yaml
  transform:
    lang: jslt
    expr: |
      {
        "type": if (.callbacks) "challenge" else "simple",
        "authId": .authId,
        "fields": [for (.callbacks)
          { "label": .output[0].value, "type": "text" }
        ]
      }
    apply:
      - mapperRef: strip-internal    # step 1: strip internal fields from input
      - expr                         # step 2: run the main transform expression
      - mapperRef: add-meta          # step 3: add metadata to output
  ```

  Evaluation flow:
  1. Engine takes input → feeds to `strip-internal` mapper → cleaned body
  2. Cleaned body → feeds to main `expr` → restructured output
  3. Restructured output → feeds to `add-meta` → final output with metadata

  Pros:
  - ✅ Engine-agnostic — works identically for JSLT, JOLT, jq, and any future engine
  - ✅ Consistent with ADR-0008's declarative composition philosophy
  - ✅ Simple implementation — mappers compile to `CompiledExpression`, engine calls them in sequence

  Cons:
  - ❌ Less flexible — cannot call a mapper conditionally within a JSLT expression
  - ❌ Introduces a mini-pipeline within a single spec (related to profile-level chaining, ADR-0012)

- **Option B – Both transform-level and inline JSLT function** (rejected)

  Mappers can be applied both as YAML-level directives AND as callable functions inside
  JSLT expressions (e.g., `strip-internal(.nested)`). The engine registers each mapper
  as a JSLT extension function at compile time.

  Example:
  ```yaml
  transform:
    lang: jslt
    expr: |
      let cleaned = strip-internal(.)
      {
        "type": if (cleaned.callbacks) "challenge" else "simple",
        "authId": cleaned.authId,
        "fields": [for (cleaned.callbacks)
          {
            "label": .output[0].value,
            "type": "text",
            "extra": if (.type == "PasswordCallback")
                       add-meta(.)
                     else
                       null
          }
        ]
      }
    apply:
      - expr
      - mapperRef: add-meta
  ```

  Pros:
  - ✅ Maximum flexibility — authors can call mappers conditionally or on sub-trees
  - ✅ Powerful composition within a single expression

  Cons:
  - ❌ Not engine-agnostic — JSLT supports custom functions, but JOLT/jq may not
  - ❌ More complex implementation — requires function injection into compilation context
  - ❌ Blurs the line between declarative and programmatic composition

- **Option C – Inline JSLT function only** (rejected)

  Mappers are exclusively callable as functions within `expr`. No YAML-level `apply`
  directives — composition is purely programmatic inside the expression.

  Example:
  ```yaml
  transform:
    lang: jslt
    expr: |
      let cleaned = strip-internal(.)
      let result = {
        "type": if (cleaned.callbacks) "challenge" else "simple",
        "authId": cleaned.authId,
        "fields": [for (cleaned.callbacks)
          { "label": .output[0].value, "type": "text" }
        ]
      }
      add-meta(result)
  ```

  Pros:
  - ✅ Most natural for JSLT — mirrors JSLT's built-in function model
  - ✅ Conditional and sub-tree invocation is natural

  Cons:
  - ❌ Completely engine-specific — only works for engines with custom function support
  - ❌ Violates engine-agnostic design principle (JOLT has no function concept)
  - ❌ Tightly couples the feature to JSLT, undermining the pluggable SPI design

### Comparison Matrix

| Aspect              | A: Directive only | B: Both         | C: Function only |
|---------------------|-------------------|-----------------|-------------------|
| Invocation          | `apply: [mapperRef: x]` in YAML | `apply` and `x(.)` in expr | `x(.)` in expr only |
| Conditional use     | ❌ Always applies | ✅ `if (...) x(.)` | ✅ `if (...) x(.)` |
| Sub-tree use        | ❌ Operates on full body | ✅ `x(.nested.field)` | ✅ `x(.nested.field)` |
| Works with JOLT     | ✅ Yes (YAML-level) | ⚠️ Only the `apply` part | ❌ No |
| Works with jq       | ✅ Yes (YAML-level) | ⚠️ Depends on function support | ⚠️ Depends |
| Complexity          | Low               | High            | Medium            |
| ADR-0008 alignment  | ✅ Declarative    | ⚠️ Mixed paradigm | ❌ Programmatic |

Related ADRs:
- ADR-0008 – Single Expression Per Direction (declarative composition principle)
- ADR-0010 – Pluggable Expression Engine SPI (engine-agnostic design)
- ADR-0012 – Pipeline Chaining Semantics (profile-level chaining)

## Decision

We adopt **Option A – Transform-level sequential directive only**.

Mappers are invoked exclusively via a declarative `apply` list in the spec YAML. The
`apply` list sequences named mappers and the main `expr` in declaration order. Each
step receives the previous step's output as input.

Concrete changes to the spec:
1. FR-001-08 gains the `apply` directive syntax.
2. When `apply` is absent, the transform behaves exactly as before (just `expr`).
3. Mappers compile to `CompiledExpression` objects — the engine runs them sequentially.
4. The `apply` list is spec-internal — it does NOT interact with profile-level chaining
   (ADR-0012). Profile chaining composes across specs; `apply` composes within a spec.

## Consequences

Positive:
- Engine-agnostic: mappers work identically across JSLT, JOLT, jq, and future engines.
- Consistent with the declarative philosophy established by ADR-0008.
- Low implementation complexity — no function injection needed in the ExpressionEngine SPI.

Negative / trade-offs:
- Cannot call mappers conditionally or on sub-trees. If conditional mapper application
  is needed, the author must split into multiple specs and use profile-level chaining.
- Introduces a mini-pipeline within a single spec, which is conceptually similar to
  (but more limited than) profile-level chaining.

Follow-ups:
- Update FR-001-08 in spec.md with the `apply` directive syntax.
- Update S-001-50 scenario to use the `apply` directive.
- Add a new scenario for the `apply` ordering semantics (pre + expr + post).
