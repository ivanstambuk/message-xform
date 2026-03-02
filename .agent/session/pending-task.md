# Pending Task

**Focus**: Device Binding — Phase 3.5: Live K8s Validation
**Status**: Phase 3 written, waiting on live K8s cluster
**Next Step**: Start K8s cluster and run `./run-e2e.sh --env k8s device-binding.feature`

## Context Notes
- `device-binding.feature` created with 3 scenarios:
  1. Full binding registration + signing verification (Steps 1-9)
  2. Signing fails after device cleanup (negative test)
  3. Self-test — crypto helper validation (✅ passed offline)
- `karate-config.js` updated with `deviceBindingJourneyParams`, `deviceSigningJourneyParams`, `deviceBindingTestUser: user.6`
- K8s cluster was not running at test time — need to bring up the cluster and run:
  ```bash
  ./run-e2e.sh --env k8s device-binding.feature
  ```
- If scenarios 1+2 pass → mark Phase 3 as ✅, proceed to Phase 4

## After K8s Validation
- Phase 4: Transform Specs & Clean URLs
- Phase 5: Documentation & Knowledge Capture

## SDD Gaps
- None — this work is infrastructure/deployment, not a core engine feature
