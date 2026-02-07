# Feature 008 – NGINX Adapter

| Field | Value |
|-------|-------|
| Status | Not Started |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Roadmap entry | #8 – NGINX Adapter |
| Depends on | Feature 001 (core engine), Feature 004 (standalone, if sidecar) |

## Overview

Gateway adapter for **NGINX**. Like Kong, NGINX has no native Java support.
The pragmatic default is a **sidecar pattern** — run message-xform in standalone
mode (Feature 004), with NGINX proxying through it.

## Key Research

- Gateway Candidates: `docs/research/gateway-candidates.md` (4 approaches documented)

## Integration Options

| Approach | Pros | Cons |
|----------|------|------|
| **njs (JavaScript)** | Lightweight, built-in | Limited API, no Jackson |
| **OpenResty (Lua)** | Mature, same as Kong | Same Lua reimplementation issue |
| **C module** | Maximum performance | Highest dev cost, maintenance burden |
| **Sidecar proxy** | Reuses Java core as-is | Extra hop, added latency |

**Recommended:** Sidecar proxy (Feature 004) is the pragmatic default.

## Implementation Language

**Java (sidecar)** or **njs/Lua** for native integration.

## Status

🔲 Spec not yet written. Approach TBD.
