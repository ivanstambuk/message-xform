# Feature 003 – PingGateway Adapter

| Field | Value |
|-------|-------|
| Status | Not Started |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Roadmap entry | #3 – PingGateway Adapter |
| Depends on | Feature 001 (core engine) |

## Overview

Gateway adapter that integrates message-xform with **PingGateway** (formerly ForgeRock
Identity Gateway / IG). PingGateway uses a Java/Groovy filter/handler chain.

## Key Research

- PingGateway reference: `docs/reference/pinggateway-2025.11.txt` (37K lines, not yet analyzed)
- Gateway Candidates: `docs/research/gateway-candidates.md` (evaluation complete)

## Scope (to be elaborated)

- Research PingGateway filter/handler extension model
- Implement `GatewayAdapter` SPI for PingGateway `Request`/`Response`
- Groovy scripted filter option vs compiled Java filter
- Deployment packaging

## Implementation Language

**Java / Groovy** — PingGateway supports both.

## Status

🔲 Spec not yet written. Research pending (PingGateway filter model).
