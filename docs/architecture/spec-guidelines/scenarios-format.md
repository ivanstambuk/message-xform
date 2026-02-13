# Scenarios Format Standard

Status: Active | Last updated: 2026-02-13

This document defines the standard formats for scenario files across all features.
All scenarios are **machine-parseable YAML** inside markdown code blocks — no
free-form BDD prose.

**Template:** Copy `docs/architecture/spec-guidelines/scenarios-template.md` when
creating a new feature's `scenarios.md`.

## 1. Shared Header Metadata

Every `scenarios.md` file MUST begin with this preamble:

```markdown
# Feature NNN – [Feature Name]: Scenarios

| Field | Value |
|-------|-------|
| Status | Draft / Active |
| Last updated | YYYY-MM-DD |
| Linked spec | `docs/architecture/features/NNN/spec.md` |

> Guardrail: Each scenario is a **testable contract** expressed as structured
> YAML. Scenarios serve as integration test fixtures — they can be loaded or
> parsed directly by test infrastructure.
```

## 2. Scenario Schemas

There are two schemas. The choice depends on what the scenario tests:

| Schema | Use when | Examples |
|--------|----------|---------|
| **Data Transform** | Testing expression input → output | Feature 001 (JSLT transforms) |
| **Behavioral Contract** | Testing system behavior, lifecycle, wiring | Feature 002 (adapter integration) |

Both schemas share the same header fields. They differ in the body.

### 2.1 Shared Header Fields (mandatory)

```yaml
scenario: S-NNN-XX           # Unique ID
name: kebab-case-name         # Machine-friendly name
description: >                # What this scenario tests (1-3 sentences)
  Human-readable description.
tags: [tag1, tag2, ...]       # Categorization tags
refs: [FR-NNN-XX, ADR-XXXX]  # Traceability to spec requirements and ADRs
```

### 2.2 Data Transform Schema

For scenarios that test `input → expression → expected_output`:

```yaml
scenario: S-001-XX
name: example-data-transform
description: >
  What this scenario tests.
tags: [category, ...]
refs: [FR-001-XX]

transform:
  lang: jslt
  expr: |
    { "field": .input_field }

input:
  input_field: "value"

expected_output:
  field: "value"
```

Variants:
- **Bidirectional:** Add `forward` and `reverse` sub-keys under `transform`
- **Error expected:** Replace `expected_output` with `expected_error`
- **Dynamic values:** Use comments to indicate approximate matching

### 2.3 Behavioral Contract Schema

For scenarios that test system behavior (lifecycle, error handling, wiring,
threading, configuration):

```yaml
scenario: S-002-XX
name: example-behavioral
description: >
  What this scenario tests.
tags: [category, ...]
refs: [FR-002-XX, ADR-XXXX]

setup:
  config:                     # Plugin/adapter configuration
    key: value
  exchange:                   # Simulated exchange state
    request:
      method: POST
      uri: /api/endpoint
      contentType: application/json
      body: '{"key": "value"}'
      headers:
        X-Custom: "value"
    response:                 # Only for response-direction scenarios
      statusCode: 200
      body: '{"result": "ok"}'
    identity:                 # Optional: PingAccess identity context
      subject: "bjensen"
      attributes:
        email: "bjensen@example.com"
  specs:                      # Transform specs loaded for this scenario
    - id: spec-id
      matches: "POST /api/endpoint"
      direction: request
      expr: '...'

trigger: handleRequest | handleResponse | configure | reload

assertions:
  - description: what we check
    expect: expression or value
```

#### Setup Section

The `setup` block defines preconditions. All sub-keys are optional — include
only what the scenario needs:

- `config` — adapter plugin configuration fields
- `exchange` — the simulated PingAccess Exchange state
- `specs` — transform specs loaded before the trigger
- `profile` — active profile (if profile matching is being tested)
- `state` — any pre-existing state (ExchangeProperties, prior rule effects)

#### Trigger Section

A single string identifying what action fires:

| Trigger | Meaning |
|---------|---------|
| `handleRequest` | `MessageTransformRule.handleRequest(exchange)` |
| `handleResponse` | `MessageTransformRule.handleResponse(exchange)` |
| `configure` | `MessageTransformRule.configure(config)` |
| `reload` | Scheduled spec reload fires |
| `deploy` | JAR deployed to PingAccess |

#### Assertions Section

A list of key-value checks. Each assertion has:
- `description` — human-readable label (what we're checking)
- `expect` — the expected state/value

Assertions should be precise enough to map 1:1 to a JUnit assertion, but
expressed in domain language rather than Java syntax.

## 3. Rules

1. **No BDD prose.** Scenarios MUST use one of the two YAML schemas above.
   Free-form Given/When/Then paragraphs are not permitted.
2. **One scenario per heading.** Each `## S-NNN-XX:` section contains exactly
   one YAML code block. For large files (>20 scenarios), scenarios MAY be grouped
   under `## Category N: Name` headings with scenarios at `### S-NNN-XX:` level.
3. **Notes and rationale** may appear as markdown blockquotes (`> ...`) after
   the YAML block, but must be brief (1-3 lines).
4. **Horizontal rules** (`---`) separate scenarios for readability.
5. **Refs are mandatory.** Every scenario must trace to at least one FR, NFR,
   or ADR.
6. **Tags are mandatory.** Use consistent tag vocabulary within a feature.
7. **Coverage matrix.** Each `scenarios.md` SHOULD end with a coverage matrix
   mapping scenario IDs to FR/NFR IDs (addresses audit finding F-005).
