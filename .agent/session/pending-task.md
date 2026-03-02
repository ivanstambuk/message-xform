# Pending Task

**Focus**: Device Binding — Phase 3.5: Debug AM JWS Validation  
**Status**: BLOCKED — AM returns 401 on DeviceBindingCallback submission
**Blocker**: JWS format mismatch between helper and AM's DeviceBindingNode

## What Works
- ✅ K8s cluster up, all 4 pods running (PD, AM, PA admin, PA engine)
- ✅ DeviceBindingService enabled (encryption=NONE)
- ✅ `boundDevices` LDAP schema applied to PD
- ✅ `DeviceBindingJourney` responds with `DeviceBindingCallback`
- ✅ JWS construction: RS512 signing, header, payload, signature all correct
- ✅ Self-test passes (JWS gen + RS512 verification)
- ✅ GraalJS interop fix in bind()/sign() — type-based lookup works

## The Blocker
AM's `DeviceBindingNode` returns `failure` outcome after receiving the JWS.
The 401 is the journey's failure path, not an HTTP error. Tried:
1. `publicKey` in JWS header (base64 X.509 DER) — 401
2. `publicKey` in JWS payload — 401
3. `jwk` in JWS header (standard JWK with n/e) — 401
4. No public key at all — 401
5. Even a dummy/invalid JWS — same 401

AM produces no debug output. The DeviceBindingNode silently falls to failure.

## Next Steps
1. Enable AM debug logging at MESSAGE level (needs Tomcat restart or AM console)
2. OR: Find the Ping SDK Android/iOS source for DeviceBindingCallback.sign()
   to determine the exact JWS format (header fields, payload claims)
3. OR: Use a real Ping SDK (JS or Android) to do one binding, then inspect
   the JWS format used by wireshark or request capture
4. After fixing the format: rerun `./run-e2e.sh --env k8s device-binding.feature`

## Infrastructure State
- K8s pods are running but will stop if k3s is stopped
- DeviceBindingService: enabled (will persist in PD config store)
- boundDevices schema: applied (will persist in PD)
- Port-forward not active (was killed)

## SDD Gaps
- None — this work is infrastructure/deployment, not a core engine feature
