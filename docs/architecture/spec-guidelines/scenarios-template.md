# Feature NNN – [Feature Name]: Scenarios

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | YYYY-MM-DD |
| Linked spec | `docs/architecture/features/NNN/spec.md` |
| Format | Data Transform ∣ Behavioral Contract (see `docs/architecture/spec-guidelines/scenarios-format.md`) |

> Guardrail: Each scenario is a **testable contract** expressed as structured
> YAML. Scenarios serve as integration test fixtures — they can be loaded or
> parsed directly by test infrastructure.

---

## Format

Each scenario follows this structure:

```yaml
scenario: S-NNN-XX
name: human-readable-name
description: >
  What this scenario tests (1-3 sentences).
tags: [tag1, tag2, ...]
refs: [FR-NNN-XX, ADR-XXXX]

# --- Schema-specific body goes here (see §2.2 or §2.3 of scenarios-format.md) ---
```

**Choose ONE schema per feature:**
- **Data Transform** — for features testing expression input → output (e.g., Feature 001)
- **Behavioral Contract** — for features testing system behavior/lifecycle (e.g., Feature 002)

See `docs/architecture/spec-guidelines/scenarios-format.md` for the full schema definitions.

---

<!-- ═══════════════════════════════════════════════════════════════
     SCENARIOS GO HERE
     
     Organization options:
     
     Option A — Flat (≤20 scenarios):
       ## S-NNN-01: Scenario Title
       ```yaml
       ...
       ```
       ---
       ## S-NNN-02: Next Scenario
       ...
     
     Option B — Categorized (>20 scenarios):
       ## Category 1: Category Name
       ### S-NNN-01: Scenario Title
       ```yaml
       ...
       ```
       ---
       ### S-NNN-02: Next Scenario
       ...
       ## Category 2: Next Category
       ...
     
     ═══════════════════════════════════════════════════════════════ -->

## Category 1: [Category Name]

[Optional 1-line description of what this category covers.]

### S-NNN-01: [Scenario Title]

```yaml
scenario: S-NNN-01
name: scenario-name-in-kebab-case
description: >
  Description of what this scenario tests.
tags: [category-tag, specific-tag]
refs: [FR-NNN-XX]

# --- Data Transform example ---
transform:
  lang: jslt
  expr: |
    { "output_field": .input_field }

input:
  input_field: "value"

expected_output:
  output_field: "value"

# --- OR Behavioral Contract example ---
# setup:
#   config:
#     key: value
#   exchange:
#     request:
#       method: POST
#       uri: /api/endpoint
#       body: '{"key": "value"}'
#
# trigger: handleRequest
#
# assertions:
#   - description: what we check
#     expect: expected.value == "result"
```

---

<!-- ═══════════════════════════════════════════════════════════════
     TRAILING SECTIONS (mandatory)
     
     These sections MUST appear AFTER all scenarios.
     No scenarios or categories may appear below these sections.
     ═══════════════════════════════════════════════════════════════ -->

## Scenario Index

| ID | Name | Category | Tags |
|----|------|----------|------|
| S-NNN-01 | scenario-name | Category Name | tag1, tag2 |

## Coverage Matrix

Mapping of scenario IDs to requirement references.

| Requirement | Scenarios |
|-------------|-----------|
| **FR-NNN-01** | S-NNN-01 |
| **NFR-NNN-01** | — |
