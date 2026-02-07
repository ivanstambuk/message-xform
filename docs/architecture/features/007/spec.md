# Feature 007 – Kong Gateway Adapter

| Field | Value |
|-------|-------|
| Status | Not Started |
| Last updated | 2026-02-07 |
| Owners | Ivan |
| Roadmap entry | #7 – Kong Gateway Adapter |
| Depends on | Feature 001 (core engine), Feature 004 (standalone, if sidecar) |

## Overview

Gateway adapter for **Kong Gateway**. Kong's native extension model is Lua (OpenResty)
with Go plugin support. There is no direct Java integration path, so this adapter
requires a bridging strategy.

## Key Research

- Gateway Candidates: `docs/research/gateway-candidates.md` (evaluation complete)
- Kong transformer plugins: `docs/research/transformation-patterns.md` (patterns analyzed)

## Bridging Options

| Approach | Pros | Cons |
|----------|------|------|
| **Lua reimplementation** | Native performance, no bridge | Defeats shared Java core |
| **Sidecar proxy** | Reuses Java core as-is | Extra hop, added latency |
| **WASM** | In-process, portable | Java→WASM is immature (2026) |
| **Go plugin + JNI** | In-process | Fragile, complex deployment |

**Recommended:** Sidecar proxy (Feature 004) or Lua port for critical paths.

## Implementation Language

**TBD** — depends on bridging strategy (Lua, Go, or Java sidecar).

## Status

🔲 Spec not yet written. Bridging strategy TBD.
