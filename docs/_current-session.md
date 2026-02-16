# Current Session State

**Date:** 2026-02-16
**Focus:** Platform deployment — PingAM + PingDirectory compatibility testing

## Key Discovery: PingAM 8.0.2 Runs on PingDirectory 11.0

Live testing proved the 3-container architecture works (PingAccess + PingAM +
PingDirectory). The initial hypothesis requiring PingDS was **wrong**. PingDirectory
can serve as the unified backend for all PingAM stores (config, CTS, policy, users).

### Required PingDirectory Tweaks

1. **Schema relaxation**: `single-structural-objectclass-behavior: accept`
   - PingAM writes entries without structural objectClasses
   - PingDirectory default is `reject`, PingDS silently accepts
   - Error: `"Object Class Violation: Entry ... does not include a structural object class"`

2. **ETag virtual attribute**: Mirror `ds-entry-checksum → etag`
   - PingAM CTS expects `etag` for optimistic concurrency control
   - PingDS has it built-in; PingDirectory does NOT
   - Error: `"CTS: Unable to retrieve the etag from the token"`
   - Fix: define `etag` attribute in schema, create mirror VA from `ds-entry-checksum`

### Gotchas Discovered

- **SSL cert trust**: PingAM JVM must trust PD's cert. AM runs as uid 11111 (forgerock),
  but `cacerts` is root-owned → must `docker exec -u 0` to import
- **FQDN validation**: PingAM rejects requests where Host header doesn't match
  configured `SERVER_URL`. Use correct hostname or `/etc/hosts` entry.
- **First request is slow**: After fresh config, AM lazy-initializes on first HTTP
  request. CTS/LDAP connections established on-demand, can take 30–60s.
- **`DATA_STORE=embedded` doesn't work**: Causes `NullPointerException` — AM 8.0
  requires an external directory.
- **PD default password**: The `pingidentity/pingdirectory` Docker image uses
  `2FederateM0re` as the default root password (not `Password1`).

### Diagnostic Techniques

- **PD access log**: `docker exec <pd> tail -f /opt/out/instance/logs/access`
  Shows every LDAP operation from AM with result codes
- **AM debug logs**: `docker exec <am> tail /home/forgerock/openam/var/debug/CoreSystem`
  Shows CTS errors, LDAP connection failures
- **AM install log**: `docker exec <am> cat /home/forgerock/openam/var/install.log`
  Shows schema loading progress during initial configurator run
- **AM stdout**: `docker logs --tail 20 <am>` — shows LDAP connection factory status

## Completed This Session

1. **Phase 1 — Verification**: PingDirectory as AM backend (PASS)
2. **Phase 2 — Docker Compose + TLS** (mostly done):
   - `.env.template` → updated for 3-container arch
   - `scripts/generate-keys.sh` → created
   - `docker-compose.yml` → rewritten for 3 containers
   - `config/server.xml` → Tomcat HTTPS
   - `config/pd-post-setup.dsconfig` → schema + etag tweaks
   - `config/etag-schema.ldif` → etag attribute definition
   - `.gitignore` → secrets, .env, data volumes

3. **Documentation**: 
   - `README.md` → updated architecture diagram
   - `PLAN.md` → revised D4→D5, added test evidence
   - `pingam-operations-guide.md` → **new Part III** with full PingDirectory
     compatibility guide (tweaks, parameters, SSL, monitoring, debugging)

## Next Steps

- Phase 2, Step 2.8: `docker compose up -d` smoke test
- Phase 3: `scripts/configure-am.sh` automation
- Phase 4: User creation + authentication journey
