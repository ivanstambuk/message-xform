# Pending Task

**Focus**: Device Binding — Phase 3.5: Debug AM JWS Validation
**Status**: BLOCKED — AM returns 401 on DeviceBindingCallback JWS submission
**Next Step**: Determine the exact JWS format expected by AM's DeviceBindingNode

## Context Notes
- K8s cluster stopped, pods scaled to 0 (restart: `sudo systemctl start k3s`,
  then scale up with `kubectl scale statefulset/deployment --replicas=1`)
- DeviceBindingService enabled at realm level (persists in PD config store)
- `boundDevices` LDAP schema applied to PD (persists)
- User DN format: `uid=user.6,ou=People,dc=example,dc=com`
- AM uses `id=user.6,ou=user,dc=example,dc=com` as internal userId
- Self-test passes — crypto helper JWS gen + RS512 verification works
- GraalJS interop fix applied (type-based callback lookup)

## Blocker: AM JWS Format Unknown
Tried: publicKey in header, publicKey in payload, jwk in header, no key, dummy JWS.
All result in DeviceBindingNode → failure → 401.

Approaches to unblock:
1. Extract the bundled `boundDevices` LDIF from AM WAR (pitfall #4) — may contain
   clues about expected attribute format
2. Enable AM MESSAGE-level debug (needs Tomcat sys prop or AM console)
3. Find Ping SDK Android source for DeviceBindingCallback.sign() on GitHub
4. Use a real Ping SDK to do one binding, capture JWS via network trace

## SDD Gaps
- None — this work is infrastructure/deployment, not a core engine feature
