# Pending Task

**Focus**: Phase 8 complete — all platform E2E + transform specs done
**Status**: Phase 8 fully ✅. Documentation fully synced.
**Next Step**: Identify next phase of work (see suggestions below)

## Context Notes
- Profile is now v4.0.0 with both request-side URL rewriting and response-side body transforms
- PA has two applications: `/am` (direct AM proxy) and `/api` (clean URL surface)
- `configure-pa-api.sh` must be run after `configure-pa-plugin.sh` when setting up a fresh stack
- The clean URL specs have NOT been E2E tested yet — they are config-only artifacts
  that need a running platform stack to verify

## Suggested Next Steps

1. **E2E test the clean URLs** — Update existing E2E tests (or add new scenarios)
   to exercise `/api/v1/auth/login`, `/api/v1/auth/passkey`, and
   `/api/v1/auth/passkey/usernameless` instead of the raw AM paths

2. **Phase 9 wrap-up** — All Phase 9 steps are already ✅ Done, but the
   platform deployment guide should get a new subsection for the /api app setup
   in the "Setup procedure" section (currently only documents configure-pa.sh
   and configure-pa-plugin.sh steps)

3. **New feature work** — With the platform fully operational, consider:
   - Feature 003 (PingGateway adapter)
   - Feature 004 (standalone proxy enhancements)
   - Any backlog items from the main PLAN.md

## SDD Gaps
- None identified — retro audit passed clean
