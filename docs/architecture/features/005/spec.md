# Feature 005 – WSO2 API Manager Adapter

| Field | Value |
|-------|-------|
| Status | Not Started |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Roadmap entry | #5 – WSO2 API Manager Adapter |
| Depends on | Feature 001 (core engine) |

## Overview

Gateway adapter that integrates message-xform with **WSO2 API Manager**. WSO2 is
Java-native and genuinely open source (Apache 2.0), making it a natural fit for
direct core library integration.

## Key Research

- Gateway Candidates: `docs/research/gateway-candidates.md` (evaluation complete)
- WSO2 extension model: Not yet researched

## Scope (to be elaborated)

- Research WSO2 API Manager extension/mediation model
- Implement `GatewayAdapter` SPI for WSO2 message context
- Synapse mediator or custom handler approach
- Deployment packaging

## Implementation Language

**Java** — WSO2 is entirely Java-based.

## Status

🔲 Spec not yet written. Research pending (WSO2 extension model).
