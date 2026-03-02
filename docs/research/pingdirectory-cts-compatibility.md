# PingDirectory as CTS / Config Store for PingAM — Compatibility Analysis

> Research analysis: Can PingDirectory replace PingDS as the CTS, config, and
> policy store for PingAM? Official vendor stance vs. verified findings.

| Field        | Value                                              |
|--------------|----------------------------------------------------|
| Status       | Verified                                           |
| Last updated | 2026-03-02                                         |
| Verified on  | PingAM 8.0.2 + PingDirectory 11.0 (Feb 16, 2026)  |

---

## 1. Background

PingAM (formerly ForgeRock AM) uses an external directory server for four store
roles: **Configuration**, **CTS (Core Token Service)**, **Policy**, and
**Identity** (user profiles). The official documentation states that PingDS
(formerly ForgeRock DS) is The *only supported backend* for the first three.

This document analyses whether PingDirectory can replace PingDS in all four
roles, based on official vendor documentation, web research, codebase lineage
analysis, and verified live testing in our platform deployment.

---

## 2. Official Vendor Stance

### PingDS is the only supported CTS backend

The Ping Identity FAQ explicitly states:

> "Can I use any LDAP server for the CTS store? **No, DS is the only supported
> backend for the CTS store.**"
> — *FAQ: Core Token Service (CTS) and session high availability in PingAM
> (Article #000035133)*

The PingAM documentation for **Identity Stores** lists PingDirectory and Generic
LDAPv3 as supported types. The documentation for **Config Stores** and **CTS
Stores** exclusively references PingDS.

### Store compatibility matrix (official)

| Store Role         | PingDS (External) | PingDirectory      |
|--------------------|--------------------|--------------------|
| Identity Store     | ✅ Supported       | ✅ Supported       |
| CTS Store          | ✅ Required        | ❌ Not supported   |
| Config Store       | ✅ Required        | ❌ Not supported   |
| Policy Store       | ✅ Required        | ❌ Not supported   |

**Conclusion:** Per official documentation, our deployment is in an
unsupported-but-functional configuration.

---

## 3. Codebase Lineage — Are They Really "Different Products"?

A common claim is that PingDirectory (UnboundID heritage) and PingDS (OpenDJ
heritage) are "completely different codebases." **This is incorrect.**

### The actual lineage

```
Sun Microsystems
    └─ OpenDS (Java-based LDAP, mid-2000s)
         ├─ OpenDJ fork (Oracle → ForgeRock → Ping Identity 2023 merger)
         │    └─ ForgeRock DS → PingDS (ForgeRock side of the merger)
         │
         └─ UnboundID (acquired by Ping Identity 2016)
              └─ PingDirectory (Ping Identity side of the merger)
```

Both products trace back to the **same OpenDS codebase**. They diverged in the
late 2000s when Sun was split up, but they share:
- The same LDAP protocol foundation
- The same core schema handling
- The same `ds-entry-checksum` virtual attribute concept
- Compatible replication wire protocols (to a degree)

**Source:** ldap.com (maintained by Neil Wilson, PingDirectory lead architect),
nawilson.com, Ping Identity release notes (confirming "DS code base" origins).

### What is actually different

| Aspect              | PingDS (OpenDJ side)              | PingDirectory (UnboundID side)   |
|---------------------|-----------------------------------|----------------------------------|
| `etag` attribute    | Built-in natively                 | Not present — has `ds-entry-checksum` instead |
| Structural OC rule  | Permissive by default             | Strict by default (`reject`)     |
| Setup profiles      | PingDS-specific (`--rootSuffix`)  | PingDirectory-specific (`pd.profile`) |
| Docker image        | Not on Docker Hub                 | `pingidentity/pingdirectory`     |
| Admin tools         | `dsconfig`, `ldapmodify`          | `dsconfig`, `ldapmodify` (compatible CLI) |
| Default root DN     | `uid=admin`                       | `cn=administrator`              |
| Default password    | `Password1`                       | `2FederateM0re`                 |

The differences are cosmetic (naming, defaults) and one functional gap (`etag`),
which we bridge with a mirror virtual attribute.

---

## 4. What We Did — Two Tweaks

Our platform runs **PingDirectory 11.0 as the sole LDAP backend** for PingAM
8.0.2 — all four store roles (config, CTS, policy, identity) in one instance.

Two PingDirectory configuration changes are required:

### Tweak 1: Schema Relaxation

**Problem:** PingAM writes entries without structural objectClasses (e.g.,
`ou=iPlanetAMAuthConfiguration,...`). PingDirectory rejects these by default.

**Fix:**
```bash
dsconfig set-global-configuration-prop \
  --set single-structural-objectclass-behavior:accept
```

This is a **documented PingDirectory feature** — not a hack.

### Tweak 2: ETag Virtual Attribute

**Problem:** PingAM's CTS uses an `etag` attribute for optimistic concurrency.
PingDirectory doesn't have `etag`, but has the equivalent `ds-entry-checksum`.

**Fix (two sub-steps):**

1. Add `etag` attribute to schema (`etag-schema.ldif`)
2. Create mirror virtual attribute: `ds-entry-checksum → etag`

```bash
dsconfig create-virtual-attribute \
  --name "CTS ETag" \
  --type mirror \
  --set attribute-type:etag \
  --set source-attribute:ds-entry-checksum \
  --set base-dn:dc=example,dc=com
```

Both are **documented PingDirectory features** (mirror virtual attributes,
custom schema additions).

### Configuration files

| File | Purpose |
|------|---------|
| `deployments/platform/config/pd-post-setup.dsconfig` | Both dsconfig commands |
| `deployments/platform/config/etag-schema.ldif` | The `etag` attribute schema |

### Operational references (how-to details)

For step-by-step setup commands and gotchas:
- [PingAM Operations Guide §6](../operations/pingam-operations-guide.md#6-using-pingdirectory-as-am-backend) — full deep-dive
- [Platform Deployment Guide §4](../operations/platform-deployment-guide.md#4-compatibility-tweaks) — deployment walkthrough

---

## 5. Verified Test Evidence

Tested Feb 16, 2026 — PingAM 8.0.2 + PingDirectory 11.0.

| Test                                 | Result |
|--------------------------------------|--------|
| PingAM configurator → PingDirectory  | ✅ 98 schema files loaded (0 errors) |
| CTS token persistence                | ✅ 13 tokens in `ou=tokens,dc=example,dc=com` |
| Admin authentication (`amAdmin`)     | ✅ Valid `tokenId` + `iPlanetDirectoryPro` cookie |
| PD access log evidence               | ✅ 4000+ successful LDAP ops from AM |
| User authentication (`user.1`)       | ✅ Callback-based auth against `ldapService` tree |
| WebAuthn passkey registration + auth | ✅ Full FIDO2 ceremony (E2E tested) |
| WebAuthn usernameless (discoverable) | ✅ E2E tested with resident key |
| Device Binding callback              | ✅ Returns challenge, userId, authenticationType |
| Token reaper (session expiry)        | ✅ Tokens cleaned up on session logout |

All tests run on both Docker Compose and Kubernetes (k3s) topologies.

---

## 6. Risk Assessment

### Risks that are real

| Risk | Severity | Mitigation |
|------|----------|------------|
| **No vendor support** | Medium | If we file a Ping Support ticket about CTS issues, they will ask us to reproduce on PingDS. Acceptable — we are not in a Ping support contract for this deployment. |
| **Multi-datacenter CTS replication** | Low (for us) | Replication behavior between PD mirrors for high-churn CTS data is untested. Irrelevant — we run single-instance topologies. |
| **Future PingAM version** | Low | A future PingAM release could add PingDS-specific internal APIs that bypass LDAP. Monitor release notes during upgrades. |

### Risks that are overstated

| Claimed Risk | Reality |
|--------------|---------|
| "Token reaper failures" | PingAM's reaper uses standard LDAP delete ops. No PD-specific issue observed. |
| "Replication instabilities" | Only relevant for multi-DC/multi-replica. Single instance has zero replication. |
| "Incompatible indexing" | PingAM creates its own indexes during configurator setup. PD accepted all 98 schema files. |
| "Forcing schemas voids support" | We used documented PD features (mirror VA, custom schema), not internal hacks. |
| "Different codebases" | Both products share OpenDS lineage. The LDAP protocol layer is compatible. |

### Bottom line

| Use case                          | Recommendation |
|-----------------------------------|----------------|
| Dev / test / demo / E2E           | ✅ **Use PingDirectory** — eliminates PingDS, reduces container count, verified working |
| Pre-production (load testing)     | ⚠️ Test token reaper under realistic load before deciding |
| Production (high-scale, multi-DC) | ❌ Use PingDS — official support, replication guarantees, vendor reaper optimizations |

---

## 7. Why We Chose PingDirectory

1. **Fewer containers.** 3 (PD + AM + PA) instead of 4 (PD + DS + AM + PA).
   PingDirectory serves as both user store and AM system store.

2. **Docker Hub availability.** PingDirectory has an official
   `pingidentity/pingdirectory` Docker Hub image. PingDS does not — you must
   build from the distribution ZIP.

3. **Operational simplicity.** One directory server to manage, back up, and
   monitor.

4. **Feature parity for our scale.** At single-instance dev/test scale, the
   functional differences between PD and PingDS are negligible once the two
   tweaks are applied.

---

## 8. Summary

| Aspect | Conclusion |
|--------|------------|
| Official support | ❌ PingDirectory is NOT supported for CTS/Config/Policy |
| Technical compatibility | ✅ Works with two tweaks (schema relaxation + etag VA) |
| Codebase relationship | Same lineage (OpenDS → UnboundID / OpenDJ) |
| Our deployment | PingDirectory 11.0 as sole backend — verified working |
| Risk for dev/test | Low — no replication, no support contract dependency |
| Risk for production at scale | Medium — untested replication, no vendor support |
| Recommendation | Use for dev/test/demo; evaluate PingDS for high-scale production |
