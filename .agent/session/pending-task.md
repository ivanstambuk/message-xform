# Pending Task

**Focus**: Device Binding E2E — Complete
**Status**: ✅ All 3 scenarios pass (binding, signing, cleanup, self-test)
**Next Step**: No immediate follow-up — move to next feature/task

## Context Notes
- K8s cluster scaled to 0 (all pods stopped)
- DeviceBindingService enabled at realm level (persists in PD config store)
- `boundDevices` LDAP schema applied to PD (persists)
- `postponeDeviceProfileStorage=true` set on DeviceBindingNode (persists in AM config)
- `applicationIds: ["com.example.test"]` matches `iss` claim in helper
- Research doc: `docs/research/device-binding-jws-format.md`

## SDD Gaps
- None — this work is infrastructure/deployment, not a core engine feature
