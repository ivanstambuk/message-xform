# Pending Task

**Focus**: Device Binding — Phase 3.5: Validate Fixed JWS Format
**Status**: READY — JWS format fixed per SDK source analysis
**Next Step**: Start K8s cluster and run E2E tests

## Context Notes
- K8s cluster stopped, pods scaled to 0 (restart: `sudo systemctl start k3s`,
  then scale up with `kubectl scale statefulset/deployment --replicas=1`)
- DeviceBindingService enabled at realm level (persists in PD config store)
- `boundDevices` LDAP schema applied to PD (persists)
- User DN format: `uid=user.6,ou=People,dc=example,dc=com`
- AM uses `id=user.6,ou=user,dc=example,dc=com` as internal userId
- Self-test passes — crypto helper JWS gen + RS512 verification works
- GraalJS interop fix applied (type-based callback lookup)

## Fix Applied (based on SDK source analysis)
Cloned `github.com/ForgeRock/forgerock-android-sdk` and analyzed the `sign()`
method in `DeviceBindAuthenticators.kt` (lines 77–113).

Three categories of missing fields identified and fixed in `device-binding.js`:
1. **JWK header**: added `"use":"sig"` and `"alg":"RS512"` (AM stores these)
2. **Payload claims**: added `"iss"` (issuer), `"platform":"android"`,
   `"android-version":34`
3. **Self-test**: updated to verify all new claims

See `docs/research/device-binding-jws-format.md` for full analysis.

## SDD Gaps
- None — this work is infrastructure/deployment, not a core engine feature
