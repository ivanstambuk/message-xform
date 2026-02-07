# ADR-0008 – Single Expression per Direction (No Spec-Level Pipelines)

Date: 2026-02-07 | Status: Accepted

## Context

The transform spec format (`spec.md` FR-001-01) currently defines one expression per
direction — a single `transform.expr` (unidirectional) or `forward.expr` /
`reverse.expr` (bidirectional). There is no `pipeline` or `steps` array.

The question arose whether the spec should support chaining multiple expressions in a
pipeline within a single spec file — e.g., `JOLT (structural shift) → JSLT (conditional
enrichment)` — or whether each spec should remain one expression per direction.

### Options Considered

- **Option A – Single expression per direction** (chosen)
  - Keep the current model: one `expr` per direction. Use the expression engine's
    native staged-computation features (JSLT `let`, jq pipe `|`, JSONata `$var :=`,
    DataWeave `var`) for multi-step logic. Use `mapperRef` (FR-001-08) for cross-spec
    reuse. If truly independent stages or mixed engines are needed, chain at the
    **profile level** (multiple specs bound in sequence to the same route).
  - Pros:
    - Simpler spec format — one expression, one compiled artifact, one evaluation.
    - Every supported engine natively supports staged computation (see analysis below).
    - Avoids intermediate `JsonNode` serialization overhead between pipeline stages.
    - Profile-level chaining already covers the mixed-engine case.
  - Cons:
    - Very complex transforms may produce long single expressions (mitigated by `let`
      bindings and `mapperRef`).
    - Mixed-engine pipelines are not possible within one spec file — but *are* possible
      via profile-level chaining with the same functional result.

- **Option B – Pipeline of expressions within a spec** (rejected)
  - Add an optional `pipeline` array to the `transform` block, where each step has its
    own `lang` and `expr`. Steps execute sequentially; output of step N becomes input
    of step N+1.
  - Pros:
    - Enables mixed-engine pipelines in a single spec file (JSLT → JOLT → JSLT).
    - Explicit stage boundaries aid readability for very complex chains.
  - Cons:
    - Adds complexity to the spec format and engine implementation.
    - Intermediate `JsonNode` serialization between stages adds latency.
    - Mixed-engine pipelines are a niche use case with no validated current demand.
    - Duplicates what profile-level chaining already provides — same result, different
      ergonomics.

Related ADRs:
- ADR-0004 – Engine Support Matrix (capability validation)

## Decision

We adopt **Option A – Single expression per direction**.

Each transform spec defines exactly one expression per direction. The spec format does
not support pipeline/chaining within a single spec. Multi-step logic is expressed using
each engine's native staged-computation features. Mixed-engine composition is achieved
at the profile level by binding multiple specs to the same route in sequence.

## Analysis: Engine-Native Staged Computation

A key factor in this decision is that **every supported expression engine** already has
native facilities for multi-step logic within a single expression. No engine is harmed
by the single-expression model:

| Engine      | Variables / staging   | Mechanism                                | Conditionals     | Fits single-expr? |
|-------------|:---------------------:|------------------------------------------|:----------------:|:-----------------:|
| **JSLT**    | ✅ `let x = …`        | `let` bindings, `def` functions          | ✅ `if/else`     | ✅ Perfect        |
| **JOLT**    | ❌ No variables       | Spec IS a pipeline (`[shift, default, remove]`) | ❌ None    | ⚠️ Already pipeline internally |
| **jq**      | ✅ `as $x`            | Pipe `\|` is the core primitive          | ✅ `if/then/else`| ✅ Perfect        |
| **JSONata** | ✅ `$x :=`            | Chaining via `;` and `~>` operator       | ✅ Ternary, `$match` | ✅ Perfect   |
| **DataWeave** | ✅ `var x = …`      | `var` declarations in header block       | ✅ `if/else`, pattern match | ✅ Perfect |

JOLT is the outlier — it has no variables or conditionals — but a JOLT spec is inherently
a pipeline of operations (shift → default → remove → sort). JOLT's internal pipeline
model is already sufficient for its use cases. When JOLT users need conditionals or header
awareness, they compose via profile-level chaining with a JSLT spec.

### Example: JSLT staged computation (single expression)

```yaml
transform:
  lang: jslt
  expr: |
    let cleaned = { * : . } - "_internal" - "_debug"
    $cleaned + { "_meta": { "transformedBy": "message-xform" } }
```

This is functionally identical to a hypothetical two-stage pipeline, but without
intermediate serialization overhead.

## Analysis: What Mixed-Engine Use Cases Exist?

The follow-up investigation identified four concrete mixed-engine scenarios. All are
achievable via profile-level chaining:

### Use Case 1: Legacy JOLT migration + conditional enrichment
An org has existing JOLT specs from Apache NiFi. They want to add conditional,
header-aware logic (which JOLT cannot do). **Solution:** Two specs bound in the profile —
JOLT spec (untouched) → JSLT spec (new conditional layer). The JOLT spec is reused as-is.

### Use Case 2: JOLT structural shift + body-to-header injection
JOLT excels at deep structural shifts but cannot reference `$headers` or `$status`
(per ADR-0004, engine support matrix). **Solution:** Profile chains JOLT (structural
heavy lifting) → JSLT (header-aware enrichment using `$headers`).

### Use Case 3: jq filtering + JSLT reshaping
jq has powerful array slicing (`[.items[] | select(.active)]`); JSLT has better
template-style output shaping. **Solution:** Two specs — jq (filter) → JSLT (reshape).

### Use Case 4: DataWeave migration bridge
Org migrating from MuleSoft has existing DataWeave scripts. **Solution:** Profile chains
DataWeave spec (existing logic) → JSLT spec (new enrichment). Existing scripts are
preserved.

### Common pattern

All four use cases involve **JOLT** or a **legacy engine** that lacks conditionals or
header awareness — and all are solved by profile-level chaining with identical functional
results. The only difference between Option A and Option B is packaging ergonomics
(two files + profile wiring vs. one self-contained file). This does not justify the
added spec format and engine complexity of Option B.

## Consequences

Positive:
- Spec format remains simple — one expression per direction, easy to read and validate.
- Engine implementation avoids pipeline dispatch, intermediate serialization, and
  cross-engine state passing.
- All engines' native features are leveraged (JSLT `let`, jq `|`, JSONata `~>`, etc.).
- Profile-level chaining provides a clear, documented escape hatch for mixed-engine
  composition.

Negative / trade-offs:
- Mixed-engine transforms require two separate spec files and profile-level wiring
  instead of a single self-contained file. This is marginally less ergonomic but
  functionally equivalent.
- Very long single expressions may benefit from external tooling (JSLT formatters,
  linters) for readability.

Follow-ups:
- Document profile-level chaining pattern in a "Cookbook" or usage guide when
  implementation begins.
- If demand for mixed-engine single-spec pipelines emerges from real usage, revisit
  this ADR — but only with validated use cases.
