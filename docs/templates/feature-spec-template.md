# Feature <NNN> – <Descriptive Name>

| Field | Value |
|-------|-------|
| Status | Draft | <!-- Specification status only (Draft/Ready/Deprecated), independent of implementation progress. -->
| Last updated | YYYY-MM-DD |
| Owners | <Name(s)> |
| Linked plan | `docs/architecture/features/<NNN>/plan.md` |
| Linked tasks | `docs/architecture/features/<NNN>/tasks.md` |
| Roadmap entry | #<workstream number> |

> Guardrail: This specification is the single normative source of truth for the feature. Track high- and medium-impact questions in `docs/architecture/open-questions.md`, encode resolved answers directly in the Requirements/NFR/Behaviour sections below, and use ADRs under `docs/decisions/` for architecturally significant clarifications.

## Overview
Summarise the problem, affected modules (core/adapter/standalone), and the user impact in 2–3 sentences.

## Goals
List the concrete outcomes this feature must deliver.

## Non-Goals
Call out adjacent topics out of scope.

## Functional Requirements
| ID | Requirement | Success path | Validation path | Failure path | Source |
|----|-------------|--------------|-----------------|--------------|--------|
| FR-<NNN>-01 | Describe the behaviour/constraint. | Required behaviour when inputs are valid. | Input validation logic, errors, or warnings. | How the system responds to faults. | Standards, specs, or owner directives. |

## Non-Functional Requirements
| ID | Requirement | Driver | Measurement | Dependencies | Source |
|----|-------------|--------|-------------|--------------|--------|
| NFR-<NNN>-01 | Describe the quality/performance/security constraint. | Why this constraint exists. | How success is verified. | Modules or tooling needed. | Normative reference. |

## Branch & Scenario Matrix
| Scenario ID | Description / Expected outcome |
|-------------|--------------------------------|
| S-<NNN>-01 | Describe behaviour |

## Test Strategy
Describe how each layer gains coverage.
- **Core:** …
- **Integration:** …
- **Adapter (PingAccess/PingGateway/…):** …
- **Standalone:** …
- **Docs/Contracts:** …

## Interface & Contract Catalogue

### Domain Objects
| ID | Description | Modules |
|----|-------------|---------|
| DO-<NNN>-01 | e.g., TransformRequest fields, validation rules | core, adapter |

### Configuration
| ID | Config key | Type | Description |
|----|-----------|------|-------------|
| CFG-<NNN>-01 | e.g., `transforms[].path` | string | Describe config entry. |

### Gateway Integration Points
| ID | Gateway | Hook | Description |
|----|---------|------|-------------|
| GW-<NNN>-01 | PingAccess | RuleInterceptor.handleResponse | Response body transformation. |

### Fixtures & Sample Data
| ID | Path | Purpose |
|----|------|---------|
| FX-<NNN>-01 | docs/test-vectors/...json | Describe fixture usage. |

## Spec DSL
```yaml
domain_objects:
  - id: DO-<NNN>-01
    name: TransformRequest
    fields:
      - name: inputBody
        type: byte[]
      - name: contentType
        type: string
gateway_hooks:
  - id: GW-<NNN>-01
    gateway: PingAccess
    hook: RuleInterceptor.handleResponse
fixtures:
  - id: FX-<NNN>-01
    path: docs/test-vectors/...json
```

## Appendix (Optional)
Include supporting notes, payload examples, or references.
