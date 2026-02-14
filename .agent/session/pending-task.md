# Pending Task

**Focus**: E2E Test Expansion — Phase 9 (Hot-Reload) or Phase 8b (Web Session)
**Status**: Phase 8a complete (62/62 passing). Ready for next phase.
**Next Step**: Choose between Phase 9 (Hot-Reload E2E, lower risk) or Phase 8b
(Web Session OIDC for L4 session state, higher complexity).

## Context Notes
- Phase 8a is fully operational with mock-oauth2-server + JWKS ATV approach
- The `PingFederate` token provider type must be kept (not `Common`) — PA 9.0
  OAS endpoint is non-functional without real PingFederate
- PA silently ignores unknown API fields — always verify field names against
  PA API docs, not intuition
- PA internal log (`pingaccess.log`) can be very large — use `docker exec grep`
  inside the container, never load into shell variables
- clientId and scopes from $session require introspection (not JWKS ATV) —
  these are best-effort in current E2E tests
- User raised the question of whether configuring mock-oauth2-server to mimic
  PingFederate responses could populate missing fields via "Common" token
  provider type + introspection — this is a viable path but would require
  fixing the Docker networking (PA→mock-oauth2-server issuer URL resolution)

## SDD Gaps (if any)
- None — all checks passed in retro audit
