# Current Session — Device Binding Phase 2+3

## Focus
Device Binding — Phase 2 (E2E Crypto Helper) + Phase 3 (Feature Tests).

## Status
✅ **Phase 2 Complete** — `device-binding.js` helper created and committed.
✅ **Phase 3 Written** — `device-binding.feature` created with 3 scenarios.
🚧 **Phase 3.5 Blocked** — AM's DeviceBindingNode rejects JWS (401).

## Summary of Work Done

### Phase 2: E2E Crypto Helper ✅
- Created `device-binding.js` — pure JDK RSA 2048 + RS512 JWS signing.
- Functions: `generateKeyPair()`, `buildJws()`, `bind()`, `sign()`, parsers.
- Created `list-bound-devices.feature` and `delete-bound-device.feature` helpers.
- Self-test verifies JWS structure + RS512 signature correctness.

### Phase 3: E2E Feature Tests ✅ (written, blocked on live validation)
- Created `device-binding.feature` with 3 scenarios.
- Updated `karate-config.js` with journey params and `deviceBindingTestUser`.
- Started K8s cluster, enabled DeviceBindingService, applied boundDevices schema.
- Fixed GraalJS Java List interop bug (type-based callback lookup).
- Self-test scenario passes; live AM validation returns 401.

### Debugging Work
- Tried 5 public key placement strategies: header `publicKey`, payload `publicKey`,
  header `jwk` (standard JWK), no key, dummy JWS — all return 401.
- AM DeviceBindingNode fails silently — no debug output.
- Documented 3 new pitfalls in PITFALLS.md (GraalJS interop, service prerequisite, silent failure).

## Blocker
AM's `DeviceBindingNode` returns `failure` outcome for all JWS submissions.
Need to determine the exact JWS format via SDK source or AM debug logging.

## Handover
- K8s cluster stopped, pods scaled to 0.
- DeviceBindingService enabled (persists in PD config store).
- boundDevices schema applied (persists in PD).
- Next: determine JWS format, fix helper, rerun tests.
