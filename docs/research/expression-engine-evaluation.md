# Expression Engine Evaluation for message-xform

> Researched: 2026-02-07
> Context: Choosing the right transformation engine for gateway-inline JSON→JSON mapping.

---

## Requirements

What message-xform needs from an expression/transformation engine:

| Req | Description | Priority |
|-----|-------------|----------|
| R1 | **Structural JSON reshaping** — rename, move, flatten, nest fields | Must |
| R2 | **Default values** — fill in missing fields | Must |
| R3 | **Remove fields** — strip sensitive/internal data | Must |
| R4 | **Conditional logic** — "if status=200, use this mapping" | Must |
| R5 | **Pure Java** — no native binaries, no GraalVM, no JS engine | Must |
| R6 | **Jackson-native** — works with `JsonNode` (our data model) | Must |
| R7 | **Readable spec syntax** — a human can read and write transforms | Must |
| R8 | **Fast** — <5ms per transform, hot-path in gateway | Must |
| R9 | **Thread-safe** — single compiled spec, many threads | Must |
| R10 | **No loops needed** — simple mapping, no imperative logic | Nice |
| R11 | **Bidirectional mappings** — derive reverse from forward | Nice |
| R12 | **Pluggable** — swap engines without changing spec format | Nice |

---

## Candidates

### 1. JSLT (Schibsted)

**What it is:** A complete query and transformation language for JSON, inspired by
jq, XPath, and XQuery. Java-native, built on Jackson.

**Syntax example — rename & restructure:**
```jslt
{
  "userName": .user.name,
  "userEmail": .user.email,
  "isActive": .user.status == "active",
  "metadata": {
    "source": "pingam",
    "processedAt": now()
  },
  // Copy everything else unchanged
  * : .
}
```

**Syntax example — conditional + defaults:**
```jslt
{
  "type": if (.callbacks) "challenge" else "simple",
  "authId": .authId,
  "fields": [for (.callbacks[0].output)
    {
      "name": .name,
      "value": .value,
      "sensitive": .type == "PasswordCallback"
    }
  ]
}
```

**Pros:**
- ✅ **Very readable** — looks like a JSON template, instantly understandable
- ✅ **Pure Java**, depends only on Jackson — perfect fit
- ✅ **Battle-tested** — 9 billion transforms/day at Schibsted since 2018
- ✅ **Compiled expressions** — parse once, apply many times (thread-safe)
- ✅ **Rich function library** — `now()`, `contains()`, `size()`, `round()`, etc.
- ✅ **Extension functions** — plug in custom Java functions
- ✅ **jq-like syntax** — familiar to many developers
- ✅ **Object matching** (`* : .`) — copy unspecified fields through (passthrough)
- ✅ **Variables** (`let`) — intermediate computations without mutation
- ✅ **Function declarations** (`def`) — reusable logic within a transform

**Cons:**
- ⚠️ Last release: 0.1.14 (Nov 2022) — stable but no recent activity
- ⚠️ No formal spec/standard — it's Schibsted's custom language
- ⚠️ No built-in bidirectional support
- ⚠️ Has `for` loops (we don't need them, but they're there — not harmful)
- ⚠️ **Absent fields produce no node** — when an input field is missing, JSLT *omits*
  the output key entirely rather than producing a `null` node. Tests must use
  `assertFalse(result.has("field"))` instead of `assertTrue(result.get("field").isNull())`.
  Discovered during bidirectional round-trip testing (T-001-16/17).

**Assessment: ⭐⭐⭐⭐⭐ STRONG RECOMMEND**

The syntax is exactly what you'd want for declaring transforms. It reads like
"here's what the output looks like, with expressions for each field." The Jackson
integration is native — it works directly with `JsonNode`, no conversion needed.

---

### 2. JSONata (via Dashjoin `jsonata-java`)

**What it is:** A lightweight query and transformation language for JSON, inspired
by XPath 3.1 semantics. Originally JavaScript; Java ports exist.

**Syntax example — rename & restructure:**
```jsonata
{
  "userName": user.name,
  "userEmail": user.email,
  "isActive": user.status = "active",
  "metadata": {
    "source": "pingam"
  }
}
```

**Syntax example — conditional + array mapping:**
```jsonata
{
  "type": callbacks ? "challenge" : "simple",
  "authId": authId,
  "fields": callbacks[0].output.{
    "name": name,
    "value": value,
    "sensitive": type = "PasswordCallback"
  }
}
```

**Pros:**
- ✅ **Very readable** — similar to JSLT in clarity
- ✅ **Formal specification** — well-documented standard
- ✅ **Rich built-in functions** — string, numeric, array, object manipulation
- ✅ **Dashjoin port is actively maintained** — v0.9.9, released Oct 2025
- ✅ **Zero external dependencies** (Dashjoin version)
- ✅ **Used by IBM z/OS Connect** (IBM's JSONata4Java port)

**Cons:**
- ⚠️ **Not Jackson-native** — Dashjoin works with its own JSON model, needs
  conversion to/from Jackson `JsonNode` (performance hit)
- ⚠️ **Two competing Java ports** — IBM's JSONata4Java vs Dashjoin's jsonata-java,
  neither is definitively "the" Java implementation
- ⚠️ **JavaScript heritage** — some builtins assume JS semantics (type coercion)
- ⚠️ The Dashjoin port claims "zero deps" but that means it's NOT Jackson-based;
  we'd need a Jackson adapter layer
- ⚠️ No built-in bidirectional support

**Assessment: ⭐⭐⭐⭐ GOOD — but Jackson integration friction**

JSONata syntax is roughly as readable as JSLT. The deal-breaker vs JSLT is
Jackson compatibility: JSLT works directly with `JsonNode`, JSONata ports don't.
In a gateway-inline context where we already have Jackson everywhere, adding a
conversion layer adds latency and complexity.

---

### 3. JOLT (Bazaarvoice)

**What it is:** A Java library for declarative JSON-to-JSON structural
transformation using JSON specs (not a language, but a spec format).

**Syntax example — rename & restructure:**
```json
[
  {
    "operation": "shift",
    "spec": {
      "user": {
        "name": "userName",
        "email": "userEmail",
        "status": "statusRaw"
      }
    }
  },
  {
    "operation": "default",
    "spec": {
      "metadata": {
        "source": "pingam"
      }
    }
  }
]
```

**Syntax example — conditional (using `shift` pattern matching):**
```json
[
  {
    "operation": "shift",
    "spec": {
      "callbacks": {
        "0": {
          "output": {
            "*": {
              "name": "fields[&1].name",
              "value": "fields[&1].value",
              "type": {
                "PasswordCallback": { "#true": "fields[&3].sensitive" }
              }
            }
          }
        }
      }
    }
  }
]
```

**Pros:**
- ✅ **Pure Java**, Jackson-native — direct `JsonNode` in/out
- ✅ **Battle-tested** — widely used in enterprise (Apache NiFi, Spring, etc.)
- ✅ **Built-in operations pipeline** — shift, default, remove, sort, cardinality
- ✅ **Active project** — maintained, good documentation
- ✅ **Compiled specs** — thread-safe Chainr objects
- ✅ **No expression language** — just structural mapping (simpler to reason about)

**Cons:**
- ❌ **Syntax is objectively ugly** — the `&1`, `#true`, tree-walking notation is
  hard to read and hard to write. The user explicitly called this out.
- ❌ **No conditional logic** — pattern matching is clumsy, no `if/else`
- ❌ **No functions** — can't do string manipulation, date formatting, etc.
- ❌ **Debug nightmare** — when a JOLT spec doesn't work, it fails silently
- ❌ **No value transformation** — only structural reshaping; can't compute new values
- ⚠️ Using custom Java for value transforms defeats the purpose of declarative specs

**Assessment: ⭐⭐ NOT RECOMMENDED as primary engine**

JOLT's structural operations (shift, default, remove) are the right *concepts*,
but its syntax is hostile to humans. For our use case — readable, maintainable
transformation specs — JOLT fails the readability requirement.

JOLT is worth keeping as an **optional pluggable engine** for users who already
know it or have existing JOLT specs, but it should NOT be the default.

---

### 4. jq (via `jackson-jq`)

**What it is:** A lightweight command-line JSON processor, ported to Java via
`jackson-jq` (pure Java reimplementation on top of Jackson).

**Syntax example — rename & restructure:**
```jq
{
  userName: .user.name,
  userEmail: .user.email,
  isActive: (.user.status == "active"),
  metadata: {
    source: "pingam"
  }
}
```

**Syntax example — conditional + array mapping:**
```jq
{
  type: (if .callbacks then "challenge" else "simple" end),
  authId: .authId,
  fields: [.callbacks[0].output[] | {
    name: .name,
    value: .value,
    sensitive: (.type == "PasswordCallback")
  }]
}
```

**Pros:**
- ✅ **Readable** — familiar to anyone who uses `jq` on the command line
- ✅ **Jackson-native** (`jackson-jq`) — direct `JsonNode` integration
- ✅ **Powerful** — full expression language with pipes, filters, conditionals
- ✅ **Widely known** — jq is a standard tool for JSON manipulation

**Cons:**
- ⚠️ `jackson-jq` v1.0 still in preview (develop/1.x branch)
- ⚠️ Not 100% jq compatible — some features missing or different
- ⚠️ Pipe syntax (`.[] | ...`) can get cryptic for complex transforms
- ⚠️ Error messages are often poor in the Java port
- ⚠️ Less battle-tested than native jq
- ⚠️ No compilation/precompilation story as clear as JSLT's

**Assessment: ⭐⭐⭐ DECENT — but maturity concerns**

jq syntax is well-known and readable for simple cases. However, `jackson-jq` is
still immature compared to JSLT's production track record (9B transforms/day).
Worth supporting as a pluggable engine for users who prefer jq syntax.

---

### 5. JMESPath (via `jmespath-java`)

**What it is:** A query language for JSON (like a minimal XPath for JSON).
Designed for data extraction, not structural transformation.

**Syntax example — simple projection:**
```jmespath
{userName: user.name, userEmail: user.email}
```

**Pros:**
- ✅ Formal spec with compliance test suite
- ✅ Fully compliant Java implementation
- ✅ Very simple for extraction/projection

**Cons:**
- ❌ **Not a transformation language** — can only extract/project, not reshape
- ❌ **No conditional logic**, no functions, no defaults
- ❌ **No value computation** — can't create new values from existing ones
- ❌ **No passthrough** — can't say "copy unmentioned fields"

**Assessment: ⭐ NOT FIT — query language, not transformation**

JMESPath is designed for AWS CLI's `--query` flag. It can project fields out of a
JSON tree, but it cannot restructure, add defaults, conditionally map, or compute
derived values. It's a query language, not a transformation language. Not suitable
for message-xform.

---

### 6. DataWeave (MuleSoft)

A powerful data transformation language from MuleSoft/Salesforce.

**Syntax example:**
```dataweave
%dw 2.0
output application/json
---
{
  userName: payload.user.name,
  userEmail: payload.user.email,
  isActive: payload.user.status == "active"
}
```

**Pros:**
- ✅ Very powerful — supports XML, CSV, JSON, etc.

**Cons:**
- ❌ **Proprietary** — part of MuleSoft's commercial platform
- ❌ **No open-source Java runtime** — can't be embedded as a library
- ❌ **MuleSoft-specific** — only runs inside Mule runtime (or MuleSoft Anypoint)
- ❌ **Heavy** — designed for ESB, not gateway-inline transforms

**Assessment: ❌ NOT VIABLE — proprietary, no embeddable runtime**

DataWeave is powerful but closed. Some transformation engines specify it as an engine
id, but actually running it requires MuleSoft's runtime. Not viable for message-xform.

---

## Head-to-Head Comparison

The same transformation expressed in each viable engine:

**Task:** Transform PingAM callback response into a simpler structure.

### Input
```json
{
  "authId": "abc-123",
  "callbacks": [{
    "type": "NameCallback",
    "output": [{"name": "prompt", "value": "User Name:"}],
    "input": [{"name": "IDToken1", "value": ""}]
  }],
  "stage": "UsernamePassword"
}
```

### Desired Output
```json
{
  "sessionRef": "abc-123",
  "step": "UsernamePassword",
  "fields": [{
    "label": "User Name:",
    "type": "text",
    "fieldId": "IDToken1"
  }]
}
```

### JSLT
```jslt
{
  "sessionRef": .authId,
  "step": .stage,
  "fields": [for (.callbacks[0].output)
    {
      "label": .value,
      "type": if (.name == "prompt") "text" else "password",
      "fieldId": /* need to correlate with input array */
        .name
    }
  ]
}
```
**Readability: ⭐⭐⭐⭐⭐** — Instantly clear what the output looks like.

### JOLT
```json
[{
  "operation": "shift",
  "spec": {
    "authId": "sessionRef",
    "stage": "step",
    "callbacks": {
      "0": {
        "output": {
          "*": {
            "value": "fields[&1].label",
            "name": "fields[&1].type"
          }
        }
      }
    }
  }
}]
```
**Readability: ⭐⭐** — What does `[&1]` mean? What does `*` match? Requires
deep JOLT knowledge.

### jq (jackson-jq)
```jq
{
  sessionRef: .authId,
  step: .stage,
  fields: [.callbacks[0].output[] | {
    label: .value,
    type: (if .name == "prompt" then "text" else "password" end),
    fieldId: .name
  }]
}
```
**Readability: ⭐⭐⭐⭐** — Good, but pipe syntax is less natural than JSLT
for object construction.

### JSONata
```jsonata
{
  "sessionRef": authId,
  "step": stage,
  "fields": callbacks[0].output.{
    "label": value,
    "type": name = "prompt" ? "text" : "password",
    "fieldId": name
  }
}
```
**Readability: ⭐⭐⭐⭐** — Clean, but needs dot-access context awareness.

---

## Recommendation

### Primary Engine: **JSLT**

| Criterion | Assessment |
|-----------|------------|
| Readability | ⭐⭐⭐⭐⭐ Best of all candidates |
| Jackson integration | ⭐⭐⭐⭐⭐ Native `JsonNode` in/out |
| Performance | ⭐⭐⭐⭐⭐ 9B transforms/day at Schibsted |
| Thread-safety | ⭐⭐⭐⭐⭐ Compiled `Expression` is immutable |
| Java-native | ⭐⭐⭐⭐⭐ Pure Java, Jackson-only dependency |
| Maturity | ⭐⭐⭐⭐ Production since 2018, stable API |
| Extensibility | ⭐⭐⭐⭐ Custom Java functions |
| For our use case | ⭐⭐⭐⭐⭐ JSON→JSON mapping is its sweet spot |

JSLT gives us:
1. **Template-style syntax** — the transform spec looks like the desired output
2. **Zero impedance mismatch** — works directly with Jackson `JsonNode`
3. **Compiled expressions** — parse once at startup, apply per-request at <1ms
4. **Conditional logic** — `if/else` without needing a separate expression engine
5. **Passthrough** — `* : .` copies unmentioned fields (critical for open-world)
6. **Extension points** — plug in custom Java functions as needed

### Pluggable Engine Architecture

Even though JSLT is the recommended primary engine, message-xform SHOULD adopt a
pluggable Expression Engine SPI (ADR-0010):

```java
public interface TransformEngine {
    String id();  // "jslt", "jolt", "jsonata", "jq"
    JsonNode transform(String expression, JsonNode input);
}
```

This allows:
- **JSLT as default** for new specs
- **JOLT as plugin** for users with existing JOLT specs
- **jq as plugin** for users who prefer jq syntax
- **JSONata as plugin** for future integration

In the transform spec:
```yaml
id: callback-prettify
version: "1.0.0"
transform:
  lang: jslt     # engine selector (default: jslt)
  expr: |
    {
      "sessionRef": .authId,
      "step": .stage,
      "fields": [for (.callbacks[0].output)
        {
          "label": .value,
          "type": if (.name == "prompt") "text" else "password"
        }
      ]
    }
```

### Key Architectural Decision

> **message-xform uses JSLT as its primary expression engine because:**
> 1. It's the only candidate that is simultaneously readable, Jackson-native,
>    battle-tested, and specifically designed for JSON-to-JSON transformation.
> 2. Its template-style syntax maps perfectly to our declarative spec model.
> 3. It has zero dependencies beyond Jackson (which we already use).
> 4. Its compiled `Expression` objects are immutable and thread-safe — ideal
>    for gateway-inline use where a single spec serves thousands of requests.

### Known Limitations

#### JSLT Evaluation Budget (T-001-25)

JSLT compiled `Expression.apply()` runs synchronously and **cannot be
interrupted mid-evaluation**. There is no timeout or cancellation mechanism
in the JSLT API. Consequences:

- **Time budget** (`max-eval-ms`) is enforced **post-evaluation** by
  measuring wall-clock time around the `apply()` call. If the expression
  takes longer than the budget, the result is discarded and an
  `EvalBudgetExceededException` is thrown — but the thread was still blocked
  for the full evaluation duration.
- **Output size budget** (`max-output-bytes`) is the **primary guard**
  against runaway expressions. After evaluation, the output is serialised
  and its byte count is compared against the budget.
- For true preemptive timeout, a separate executor thread with
  `Future.get(timeout)` would be needed, adding context-switch overhead.
  This is considered a Phase 7+ optimisation if real-world workloads
  demonstrate a need.

#### JSLT ArithmeticException Leakage (T-001-31)

JSLT's `Expression.apply()` catches `JsltException` internally, but
**not `java.lang.ArithmeticException`**. This means `1/0` in a JSLT
expression throws a raw `ArithmeticException` that escapes the JSLT
runtime, rather than being wrapped in a `JsltException`.

- The `JsltExpressionEngine.evaluate()` method catches `JsltException`
  but not `ArithmeticException` — this is a known gap.
- For chain abort testing, **strict schema validation** is a more
  reliable mechanism to trigger controlled failures than arithmetic
  errors.
- A future hardening task could add a catch-all `RuntimeException`
  handler in `JsltExpressionEngine.evaluate()`, but this risks masking
  genuine bugs. Recommend targeting specific exception types instead.

---

*Status: COMPLETE — Engine evaluation done. JSLT recommended.*

