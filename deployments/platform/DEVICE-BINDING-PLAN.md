# Device Binding Implementation Plan

> **Status**: 🟡 In Progress — Phase 1
> **Created**: 2026-02-17
> **Last Updated**: 2026-02-17 21:35

## Overview

Implement PingAM's "Bound Device" feature for device-specific MFA using
asymmetric cryptography. This is distinct from WebAuthn/Passkeys — it uses
ForgeRock's proprietary `DeviceBindingCallback` and `DeviceSigningVerifierCallback`
with RS512-signed JWTs.

**Key insight**: Setting `authenticationType: NONE` allows headless E2E testing
with pure JDK crypto — no biometrics, no SDK, no hardware.

---

## Phase 1: Journey Definition

Define the authentication trees (journeys) for device binding and verification.

| # | Step | Status |
|---|------|--------|
| 1.1 | Create `DeviceBindingJourney.journey.json` (registration flow) | ✅ |
| 1.2 | Create `DeviceSigningJourney.journey.json` (verification flow) | ✅ |
| 1.3 | Create `setup-device-binding.sh` (service + import) | ✅ |
| 1.4 | Enable `DeviceBindingService` in realm config | ⬜ Needs running AM |
| 1.5 | Test import + smoke test via curl | ⬜ Needs running AM |

**Registration journey flow:**
```
Start → Username Collector → Password Collector → Data Store Decision
  → (true)  → Device Binding Node (authType=NONE) → Device Binding Storage → Success
  → (false) → Failure
```

**Verification journey flow:**
```
Start → Username Collector → Password Collector → Data Store Decision
  → (true)  → Device Signing Verifier (challenge=random) → Success / Failure
  → (false) → Failure
```

---

## Phase 2: E2E Crypto Helper

Build a headless JWS signer using pure JDK crypto (same pattern as `webauthn.js`).

| # | Step | Status |
|---|------|--------|
| 2.1 | Create `device-binding.js` helper with RSA key generation | ⬜ |
| 2.2 | Implement JWS builder (RS512 signing) | ⬜ |
| 2.3 | Implement `DeviceBindingCallback` parser | ⬜ |
| 2.4 | Implement `DeviceSigningVerifierCallback` parser | ⬜ |
| 2.5 | Implement device cleanup via REST API (`DELETE /devices/2fa/binding/{id}`) | ⬜ |
| 2.6 | Unit-test the helper in isolation | ⬜ |

**Key functions:**
- `generateKeyPair()` → `{ publicKey, privateKey, kid }`
- `buildBindingJws(challenge, userId, privateKey)` → JWS string
- `parseBindingCallback(callbacks)` → `{ challenge, userId, authenticationType }`
- `parseSigningCallback(callbacks)` → `{ challenge, userId }`
- `cleanupBoundDevices(amUrl, username, sessionToken)` → void

---

## Phase 3: E2E Feature Tests

End-to-end Karate tests for device binding and signing verification.

| # | Step | Status |
|---|------|--------|
| 3.1 | Create `device-binding.feature` with `@setup` provisioning | ⬜ |
| 3.2 | Scenario: Bind device with NONE auth type | ⬜ |
| 3.3 | Scenario: Verify signature from bound device | ⬜ |
| 3.4 | Scenario: Verify fails after device cleanup | ⬜ |
| 3.5 | Run full suite and verify pass | ⬜ |

---

## Phase 4: Transform Specs & Clean URLs

Create message-xform JSLT transforms and clean URL routing.

| # | Step | Status |
|---|------|--------|
| 4.1 | Create `am-device-binding-response.yaml` transform spec | ⬜ |
| 4.2 | Create `am-device-signing-response.yaml` transform spec | ⬜ |
| 4.3 | Create request-side URL rewrite spec | ⬜ |
| 4.4 | Update `platform-am.yaml` profile with new transforms | ⬜ |
| 4.5 | Configure PingAccess application for `/api/v1/auth/device-binding` | ⬜ |
| 4.6 | E2E test through transform pipeline | ⬜ |

---

## Phase 5: Documentation & Knowledge Capture

| # | Step | Status |
|---|------|--------|
| 5.1 | Update `llms.txt` with device binding journey references | ⬜ |
| 5.2 | Update PingAM operations guide with device binding section | ⬜ |
| 5.3 | Update `knowledge-map.md` | ⬜ |
| 5.4 | Commit all changes | ⬜ |

---

## Reference: Callback Structures (from PingAM 8 docs)

### DeviceBindingCallback (Registration)
```json
{
  "type": "DeviceBindingCallback",
  "output": [
    { "name": "userId",             "value": "id=bjensen,ou=user,dc=am,dc=example,dc=com" },
    { "name": "username",           "value": "bjensen" },
    { "name": "authenticationType", "value": "NONE" },
    { "name": "challenge",          "value": "6IBkTEPcMQ0xCghIclmDLost2ssGO5cPDs0AjUhmDTo=" },
    { "name": "title",              "value": "Authentication required" },
    { "name": "subtitle",           "value": "Cryptography device binding" },
    { "name": "description",        "value": "Please authenticate with biometrics to proceed" },
    { "name": "timeout",            "value": 60 }
  ],
  "input": [
    { "name": "IDToken1jws",         "value": "" },
    { "name": "IDToken1deviceName",  "value": "" },
    { "name": "IDToken1deviceId",    "value": "" },
    { "name": "IDToken1clientError", "value": "" }
  ]
}
```

**Client response**: Sign JWT `{"sub": userId, "challenge": challenge}` with RS512 → return JWS + deviceName + deviceId.

### DeviceSigningVerifierCallback (Verification)
```json
{
  "type": "DeviceSigningVerifierCallback",
  "output": [
    { "name": "userId",      "value": "" },
    { "name": "challenge",   "value": "Kc4dc14on98DYFzr5SoP2n3TC/JWAcAqTJMjCM+T27Y=" },
    { "name": "title",       "value": "Authentication required" },
    { "name": "subtitle",    "value": "Cryptography device binding" },
    { "name": "description", "value": "Please complete with biometric to proceed" },
    { "name": "timeout",     "value": 60 }
  ],
  "input": [
    { "name": "IDToken1jws",         "value": "" },
    { "name": "IDToken1clientError", "value": "" }
  ]
}
```

**Client response**: Sign JWT `{"sub": userId, "challenge": challenge}` with RS512 → return JWS only.

### Node Configuration (Amster/REST API)

| Node | Type ID | REST Path |
|------|---------|-----------|
| Device Binding | `DeviceBindingNode` | `/authenticationtrees/nodes/DeviceBindingNode` |
| Device Binding Storage | `DeviceBindingStorageNode` | `/authenticationtrees/nodes/DeviceBindingStorageNode` |
| Device Signing Verifier | `DeviceSigningVerifierNode` | `/authenticationtrees/nodes/DeviceSigningVerifierNode` |

### Device Management REST API

| Operation | Method | Endpoint |
|-----------|--------|----------|
| List | GET | `/json/realms/root/users/{user}/devices/2fa/binding?_queryFilter=true` |
| Delete | DELETE | `/json/realms/root/users/{user}/devices/2fa/binding/{device-id}` |
| Rename | PUT | `/json/realms/root/users/{user}/devices/2fa/binding/{device-id}` |

### DeviceBindingService (Realm Config)

| Property | Default | Notes |
|----------|---------|-------|
| `deviceBindingAttrName` | `boundDevices` | LDAP attribute storing bound device data |
| `deviceBindingSettingsEncryptionScheme` | `NONE` | Use `NONE` for dev/test |
