# Pending Task

**Focus**: Device Binding — Phase 2: E2E Crypto Helper
**Status**: Phase 1 complete, Phase 2 not started
**Next Step**: Create `device-binding.js` helper with RSA key generation and JWS signing

## Context Notes
- `DeviceBindingCallback` verified on K8s — returns challenge, userId, authenticationType=NONE
- Helper pattern should follow existing `webauthn.js` approach
- Key functions needed:
  - `generateKeyPair()` → `{ publicKey, privateKey, kid }`
  - `buildBindingJws(challenge, userId, privateKey)` → JWS string (RS512)
  - `parseBindingCallback(callbacks)` → `{ challenge, userId, authenticationType }`
  - `parseSigningCallback(callbacks)` → `{ challenge, userId }`
  - `cleanupBoundDevices(amUrl, username, sessionToken)` → void
- AM expects response: `IDToken1jws` (signed JWT), `IDToken1deviceName`, `IDToken1deviceId`
- K8s cluster is running, port-forward needed: `kubectl port-forward -n message-xform svc/pingam 28080:8080`
- boundDevices LDIF already applied to PD, DeviceBindingService already enabled

## SDD Gaps
- None — this work is infrastructure/deployment, not a core engine feature
