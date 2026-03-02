# Device Binding JWS Format — ForgeRock SDK Analysis

> **Status**: ✅ Complete
> **Date**: 2026-03-02
> **Source**: ForgeRock Android SDK (MIT license) — cloned from
>   `github.com/ForgeRock/forgerock-android-sdk` @ `develop` branch

## Context

PingAM's `DeviceBindingNode` returns HTTP 401 when the JWS format doesn't
match what it expects. AM fails silently — no debug output. This research
reverse-engineers the exact JWS format from the official SDK source.

## Key Source Files

All paths relative to `forgerock-android-sdk/forgerock-auth/src/main/java/
org/forgerock/android/auth/`:

| File | Purpose |
|------|---------|
| `devicebind/DeviceBindAuthenticators.kt` | **The gold mine**: `sign()` method (lines 77–113) builds the complete JWS |
| `callback/DeviceBindingCallback.kt` | `execute()` method (lines 207–273) orchestrates binding flow |
| `callback/DeviceSigningVerifierCallback.kt` | `authenticate()` method (lines 175–209) orchestrates signing flow |
| `devicebind/None.kt` | `authenticationType=NONE` — pure RSA, no biometrics |
| `devicebind/RSASASignatureSigner.kt` | Nimbus JOSE JWS signing wrapper |

## Device Binding JWS (Registration)

### JWS Header

Built by `DeviceBindAuthenticators.kt` lines 85–94 using Nimbus JOSE:

```kotlin
val jwk = RSAKey.Builder(keyPair.publicKey)
    .keyUse(KeyUse.SIGNATURE)   // "use": "sig"
    .keyID(kid)                 // "kid": "<uuid>"
    .algorithm(parse("RS512"))  // "alg": "RS512"
    .build()

JWSHeader.Builder(parse("RS512"))
    .keyID(kid)
    .jwk(jwk)
    .build()
```

**Resulting header:**
```json
{
  "alg": "RS512",
  "kid": "<uuid>",
  "jwk": {
    "kty": "RSA",
    "kid": "<uuid>",
    "use": "sig",
    "alg": "RS512",
    "n": "<base64url modulus>",
    "e": "<base64url exponent>"
  }
}
```

**Critical detail**: The JWK embedded in the header includes `"use": "sig"`
and `"alg": "RS512"` — not just `kty`/`n`/`e`. AM's server-side validator
uses this JWK to extract and store the public key for future verification.

### JWS Payload

Built by `DeviceBindAuthenticators.kt` lines 95–102:

```kotlin
JWTClaimsSet.Builder()
    .subject(userId)                    // "sub"
    .issuer(context.packageName)        // "iss" — Android app package name
    .expirationTime(expiration)         // "exp"
    .issueTime(getIssueTime())          // "iat"
    .notBeforeTime(getNotBeforeTime())  // "nbf"
    .claim("platform", "android")       // "platform"
    .claim("android-version", Build.VERSION.SDK_INT)  // "android-version"
    .claim("challenge", challenge)      // "challenge"
    .build()
```

**Resulting payload:**
```json
{
  "sub": "id=user.1,ou=user,dc=example,dc=com",
  "iss": "com.example.myapp",
  "exp": 1709400000,
  "iat": 1709399940,
  "nbf": 1709399940,
  "platform": "android",
  "android-version": 34,
  "challenge": "6IBkTEPcMQ0xCghIclmDLost2ssGO5cPDs0AjUhmDTo="
}
```

### Callback Input Fields (set by client)

From `DeviceBindingCallback.kt` lines 137–163:

| Input Position | Field | Value |
|----------------|-------|-------|
| 0 | `IDToken<N>jws` | Compact JWS string |
| 1 | `IDToken<N>deviceName` | Human-readable device name |
| 2 | `IDToken<N>deviceId` | Device identifier (UUID-based) |
| 3 | `IDToken<N>clientError` | Error string (empty on success) |

### Signing Flow

From `DeviceBindingCallback.execute()` lines 207–273:

1. `deviceAuthenticator.initialize(userId, prompt)` — sets up crypto key
2. `deviceAuthenticator.generateKeys(context, attestation)` — creates RSA 2048 key pair
3. `deviceAuthenticator.authenticate(context)` — returns `Success(privateKey)` for NONE type
4. Generate random `kid` (UUID)
5. Persist `UserKey(keyAlias, userId, userName, kid, authType)` locally
6. Call `deviceAuthenticator.sign(context, keyPair, signature, kid, userId, challenge, expiration, attestation)`
7. `setJws(jws)` and `setDeviceId(deviceId)`

**For `authenticationType=NONE`** (our E2E scenario):
- No biometric prompt
- `authenticate()` returns `Success(privateKey)` immediately
- `sign()` uses `RSASSASigner(keyPair.privateKey)` directly (not CryptoObject)

## Device Signing Verifier JWS (Verification)

### JWS Header (simpler — no JWK)

Built by `DeviceBindAuthenticators.kt` lines 144–147 (second `sign()` overload):

```kotlin
JWSHeader.Builder(parse("RS512"))
    .keyID(userKey.kid)
    .build()
```

**Resulting header:**
```json
{
  "alg": "RS512",
  "kid": "<uuid>"
}
```

**No JWK** in the signing verifier header — AM already has the public key
stored from the binding step.

### JWS Payload (signing verification)

```kotlin
JWTClaimsSet.Builder()
    .subject(userKey.userId)            // "sub"
    .issuer(context.packageName)        // "iss"
    .claim("challenge", challenge)      // "challenge"
    .issueTime(getIssueTime())          // "iat"
    .notBeforeTime(getNotBeforeTime())  // "nbf"
    .expirationTime(expiration)         // "exp"
    .build()
// + customClaims.forEach { claimsSet.claim(key, value) }
```

**Note**: The signing verifier payload does NOT include `platform` or
`android-version` claims (unlike the binding payload).

## What Was Missing in Our Helper

| Field | Binding | Signing | Our Helper (before) | Impact |
|-------|---------|---------|---------------------|--------|
| `jwk.use` | `"sig"` | — | **Missing** | AM may reject JWK without `use` |
| `jwk.alg` | `"RS512"` | — | **Missing** | AM may reject JWK without `alg` |
| `iss` | ✅ (package name) | ✅ | **Missing** | **Likely the blocker** — AM validates `iss` |
| `platform` | `"android"` | ❌ | **Missing** | AM may expect platform claim |
| `android-version` | SDK_INT (e.g. 34) | ❌ | **Missing** | AM may expect version claim |

## Stored Device Record Format

From `successDeviceBinding.json` test fixture, AM stores bound devices with:

```json
{
  "_id": "<uuid>",
  "deviceId": "<hash>",
  "deviceName": "Pixel 7 Pro",
  "uuid": "<uuid>",
  "key": {
    "kty": "RSA",
    "kid": "<uuid>",
    "use": "sig",
    "alg": "RS512",
    "n": "<modulus>",
    "e": "AQAB"
  }
}
```

The `key` object is the JWK extracted from the binding JWS header. This
confirms that `use` and `alg` fields are preserved and expected.

## Registered JWT Claim Names (validation)

From `DeviceBindAuthenticators.kt` lines 200–208, the SDK defines
registered (reserved) claim names that cannot be used as custom claims:

```kotlin
val registeredKeys = listOf(
    JWTClaimNames.SUBJECT,           // "sub"
    JWTClaimNames.EXPIRATION_TIME,   // "exp"
    JWTClaimNames.ISSUED_AT,         // "iat"
    JWTClaimNames.NOT_BEFORE,        // "nbf"
    JWTClaimNames.ISSUER,            // "iss"
    CHALLENGE                         // "challenge"
)
```

## Fix Applied

Updated `device-binding.js` to include all missing fields:
- JWK header: added `"use":"sig"` and `"alg":"RS512"`
- Payload: added `"iss":"io.messagexform.e2e"`, `"platform":"android"`,
  `"android-version":34`
- Self-test: updated to verify new claims

## Next Steps

1. Start K8s cluster and run E2E tests with the fixed JWS format
2. If still failing: enable AM MESSAGE-level debug to see server-side
   validation errors
