# Current Session — Device Binding E2E (Complete)

## Focus
Device Binding E2E tests — debugging 401 errors, JWS format research, server-side debugging.

## Status
✅ **Complete** — all 3 E2E scenarios pass.

## Summary

### ForgeRock SDK Analysis
- Cloned `github.com/ForgeRock/forgerock-android-sdk` (MIT license)
- Documented exact JWS format in `docs/research/device-binding-jws-format.md`
- JWK header needs `use`/`alg`, payload needs `iss`/`platform`/`android-version`

### Server-Side Debugging
- Root cause: `DeviceBindingNode.postponeDeviceProfileStorage` was `false`
  but `DeviceBindingStorageNode` downstream requires transient state
- Fixed via AM REST API (set to `true`)
- `iss` claim must match `applicationIds` in node config

### Commits
- `c0cc0c4` JWS format fix (SDK source analysis)
- `f5f4c42` E2E tests pass, issuer + assertion fixes
- `b80348c` Research doc with root cause
- `e578c04` Clean up research doc to target-state format

## Infrastructure State
- K8s cluster: scaled to 0
- AM config changes: persisted (postponeDeviceProfileStorage, applicationIds)
- PD schema: `boundDevices` applied (persists)
