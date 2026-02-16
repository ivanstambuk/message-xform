# Current Session State

**Date:** 2026-02-16
**Focus:** Platform deployment — Phases 1–6 (PD verification through WebAuthn journey)

## Summary

Massive buildout day: stood up the full 3-container platform from scratch and
advanced through 6 of 9 phases. Major discovery: PingAM 8.0.2 runs directly
on PingDirectory 11.0 (3-container arch), eliminating the need for PingDS.

## Phases Completed

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | PingDirectory as AM backend verification | ✅ Done |
| 2 | Docker Compose skeleton + TLS | ✅ Done |
| 3 | PingAM initial configuration script | ✅ Done |
| 4 | Username/Password journey + test users | ✅ Done |
| 5 | PingAccess reverse proxy integration | ✅ Done |
| 6 | WebAuthn / Passkey journeys | 🔄 Partial (6.1, 6.4 done; 6.5 next) |

## Key Decisions Made

- **D1**: PingDirectory for ALL AM stores (not PingDS)
- **D5**: 3-container architecture (PA + AM + PD)
- **D6**: REST API import over frodo CLI (frodo v3 has parsing issues)
- **D7**: Callback auth everywhere (ZPL disabled, AM 8.0 default)

## Critical Gotchas Documented

1. **Host header**: AM rejects requests without matching Host header → use
   `-H "Host: am.platform.local:18080"` + `http://127.0.0.1:18080`
2. **curl -sf**: Silently swallows AM error responses → always use `-s` only
3. **orgConfig trap**: Setting to invalid name breaks ALL auth → recovery
   via `authIndexType=service&authIndexValue=ldapService`
4. **Node import order**: Nodes must exist before tree import (AM doesn't validate)

## Next Steps

1. **Phase 6.5**: Test passkey registration/authentication (needs browser or openauth-sim)
2. **Phase 7**: Message-xform plugin wiring for PingAccess
3. **Phase 8**: E2E smoke tests (Karate)
