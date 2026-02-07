# Feature 006 – Apache APISIX Adapter

| Field | Value |
|-------|-------|
| Status | Not Started |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Roadmap entry | #6 – Apache APISIX Adapter |
| Depends on | Feature 001 (core engine) |

## Overview

Gateway adapter that integrates message-xform with **Apache APISIX** via its Java
Plugin Runner. APISIX communicates with the Java plugin over a Unix socket / RPC,
meaning the Java core can run unmodified.

## Key Research

- Gateway Candidates: `docs/research/gateway-candidates.md` (evaluation complete)
- APISIX Java Plugin Runner: Not yet researched

## Scope (to be elaborated)

- Research APISIX Java Plugin Runner protocol
- Implement `GatewayAdapter` SPI for APISIX plugin context
- Hot-reload integration (APISIX supports dynamic plugin reconfiguration)
- Deployment packaging (Plugin Runner jar + APISIX config)

## Implementation Language

**Java** — via APISIX Java Plugin Runner.

## Status

🔲 Spec not yet written. Research pending (APISIX Java Plugin Runner).
