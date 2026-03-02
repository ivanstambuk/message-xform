# Pending Task

**Focus**: Device Binding — Phase 3: E2E Feature Tests
**Status**: Phase 2 complete, Phase 3 not started
**Next Step**: Create `device-binding.feature` with binding + signing + cleanup scenarios

## Context Notes
- `device-binding.js` helper created with full crypto support (Phase 2 ✅)
- Helper provides: `bind()`, `sign()`, `parseBindingCallback()`, `parseSigningCallback()`, `selfTest()`
- RS512 JWS with `{sub, challenge, exp, iat, nbf}` payload — matches ForgeRock SDK format
- Supporting Karate features: `list-bound-devices.feature`, `delete-bound-device.feature`
- K8s cluster is running, DeviceBindingCallback verified
- Journey params needed in karate-config.js:
  - `deviceBindingJourneyParams: 'authIndexType=service&authIndexValue=DeviceBindingJourney'`
  - `deviceSigningJourneyParams: 'authIndexType=service&authIndexValue=DeviceSigningJourney'`
- Use dedicated test user (e.g., `user.6`) to avoid conflicts with passkey tests

## Phase 3 Steps
- 3.1: Create `device-binding.feature` with `@setup` provisioning
- 3.2: Scenario — Bind device with NONE auth type
- 3.3: Scenario — Verify signature from bound device
- 3.4: Scenario — Verify fails after device cleanup
- 3.5: Run full suite and verify pass

## SDD Gaps
- None — this work is infrastructure/deployment, not a core engine feature
