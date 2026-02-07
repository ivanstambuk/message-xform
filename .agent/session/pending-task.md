# Pending Task

**Focus**: PingAM + PingAccess research — COMPLETE
**Status**: All initial research documented and committed.

## Research Completed This Session

| Document | Lines | Content |
|----------|-------|---------|
| `docs/research/pingam-authentication-api.md` | 296 | `/json/authenticate` endpoint, callback mechanism, session tokens, all callback types |
| `docs/research/pingaccess-plugin-api.md` | 345 | Add-on SDK SPIs, Exchange object model, `setBodyContent()`, plugin lifecycle, adapter architecture |
| `docs/research/gateway-candidates.md` | 220 | 10 gateway candidates evaluated across 3 tiers, prioritized adapter order |

## Reference Docs Available (gitignored, local only)

| Document | Size | Lines |
|----------|------|-------|
| `docs/reference/pingam-8.txt` | 14 MB | 353,818 |
| `docs/reference/pingaccess-9.0.txt` | 2.1 MB | 49,536 |
| `docs/reference/pinggateway-2025.11.txt` | 1.5 MB | 37,081 |

## PLAN.md Updates This Session

- Added PingGateway research backlog item
- Added NGINX adapter research with 4 approaches
- Added gateway candidate evaluation tiers
- Added SDD principle: specs are gateway-agnostic, implementations are gateway-specific

## AGENTS.md Updates This Session

- Added Rule 7: Research Must Be Persisted — all research written to `docs/research/`, never chat-only

## Next Steps

- [ ] Research PingGateway filter/handler API (docs already downloaded)
- [ ] Adopt SDD — set up spec pipeline, write first feature spec for PingAM callback transformation
- [ ] Bootstrap Java project (Maven multi-module: core, pingaccess, standalone)
- [ ] Design the transformation configuration model (YAML-based)
