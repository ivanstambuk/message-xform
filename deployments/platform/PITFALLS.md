# deployments/platform — Known Pitfalls

Hard-won lessons from deploying and configuring PingAM, PingDirectory, and
PingAccess on Docker and Kubernetes.

---

## 1. Node IDs MUST Be UUIDs

**Severity: CRITICAL**

PingAM 8 requires UUID-format node IDs (e.g., `cd24c56f-f967-4345-a0c3-3ae9f80a9c61`).
Human-readable IDs like `db-usr-01` cause `"No Configuration found"` at journey
invocation time, even though nodes and trees import successfully with 200 responses.

This is **not validated at import time** — the tree appears in the tree listing as
`enabled: true`, but AM's auth framework cannot resolve the non-UUID node references
when actually walking the tree.

**Rule**: Always use `uuid.uuid4()` (Python) or `uuidgen` (bash) for node IDs.

## 2. `curl -sf` Silently Hides API Failures

**Severity: CRITICAL**

Using `curl -sf` in AM REST API scripts makes all error responses invisible:
- `-s` = silent (good — suppresses progress meter)
- `-f` = fail silently on HTTP errors (BAD — suppresses error body)

AM returns useful JSON error bodies on 4xx/5xx errors. With `-f`, you get an
empty string instead. This caused a multi-hour debugging session where node
imports appeared successful but silently failed.

**Rule**: Always use `curl -s` (never `-sf`) and check the response body.

## 3. orgConfig Is a Trap Door

**Severity: CRITICAL**

Setting `orgConfig` (the realm's default authentication service) to an invalid
name **breaks ALL authentication**, including `/json/authenticate` without
parameters. Recovery requires using the explicit
`authIndexType=service&authIndexValue=ldapService` path to get an admin token.

**Rule**: Never change `orgConfig` unless the target service exists and works.

## 4. PD `boundDevices` Schema Not Present by Default

**Severity: HIGH**

The `boundDevices` LDAP attribute (OID `1.3.6.1.4.1.36733.2.2.1.14`) does NOT
exist in PingDirectory by default. Without it, the `DeviceBindingService` fails
silently — any journey using `DeviceBindingNode` returns `"No Configuration found"`.

The LDIF is bundled inside the AM WAR:
```bash
# K8s:
kubectl exec -n $NS deploy/pingam -- cat \
  /usr/local/tomcat/webapps/am/WEB-INF/template/ldif/pingdirectory/pingdirectory_bounddevices.ldif \
  | kubectl exec -i -n $NS pingdirectory-0 -- ldapmodify -p 1636 --useSSL --trustAll \
      -D "cn=administrator" -w "2FederateM0re"

# Docker:
docker exec pingam cat \
  /usr/local/tomcat/webapps/am/WEB-INF/template/ldif/pingdirectory/pingdirectory_bounddevices.ldif \
  | docker exec -i pingdirectory ldapmodify -p 1636 --useSSL --trustAll \
      -D "cn=administrator" -w "2FederateM0re"
```

Other schema LDIFs that may need manual application:
- `pingdirectory_webauthndevices.ldif` — for WebAuthn
- `pingdirectory_oathdevices.ldif` — for OATH/TOTP
- `pingdirectory_pushdevices.ldif` — for push notification

## 5. `applicationIds` Cannot Be Empty on Device Nodes

**Severity: HIGH**

Both `DeviceBindingNode` and `DeviceSigningVerifierNode` require at least one
value in their `applicationIds` array. Passing `[]` returns:
`"Values for Application Ids is required."`

**Fix**: Use `["com.example.test"]` or a meaningful app bundle identifier.

## 6. `DeviceBindingStorageNode` Required After `DeviceBindingNode`

**Severity: MEDIUM**

`DeviceBindingNode` generates keys and performs the binding handshake but does
NOT persist the bound device to the user's LDAP entry.
`DeviceBindingStorageNode` must follow in the tree to actually write the
`boundDevices` attribute.

Correct flow: `DeviceBindingNode → (success) → DeviceBindingStorageNode → Success`

## 7. Node Import Order Matters

**Severity: HIGH**

The tree definition references nodes by UUID. If you import the tree before its
nodes exist, AM creates the tree but it's broken — invoking it returns
`"No Configuration found"` because the referenced nodes don't exist. AM does
NOT validate node references during tree creation.

**Rule**: Always import nodes first, then the tree.

## 8. K8s: Plugin JAR Deploy Mount Path

**Severity: CRITICAL**

The `ping-devops` container hooks do NOT copy `/opt/staging/deploy/` →
`/opt/out/instance/deploy/`. The emptyDir for JAR injection must mount
directly at `/opt/out/instance/deploy`. Mounting at staging causes the
plugin to exist on disk but never reach PingAccess's classpath.

## 9. K8s: PD FQDN Hostname Verification

**Severity: CRITICAL**

PD's self-signed cert CN matches the K8s headless service FQDN:
`pingdirectory-0.pingdirectory-cluster.message-xform.svc.cluster.local`

AM's LDAP SDK does SSL hostname verification. Using the short service
name (`pingdirectory`) causes `Client-Side Timeout` errors.

## 10. K8s: PA Admin Password Override

**Severity: HIGH**

`PA_ADMIN_PASSWORD_INITIAL` is a **seed** password used only during first boot.
The actual PA Admin API password is `PING_IDENTITY_PASSWORD` (set to `2Access`
in our config, not the global `2FederateM0re`).

## 11. K8s: Traefik Title-Cases HTTP/1.1 Response Headers

**Severity: HIGH**

Traefik normalizes HTTP/1.1 response header names to title-case:
`x-auth-session` → `X-Auth-Session`. Karate's bracket notation is
case-sensitive, so assertions fail.

**Fix**: `karate.configure('lowerCaseResponseHeaders', true)` in `karate-config.js`.

## 12. K8s: AM Host Header Must Include Port

**Severity: HIGH**

When accessing AM via `kubectl port-forward`, the `Host` header must include
the port matching AM's `boot.json` instance URL:
```
Host: pingam:8080   ← correct
Host: pingam        ← WRONG
```

## 13. Test Users Start at `user.1`

**Severity: LOW**

PingDirectory sample users are `user.1` through `user.10`, not zero-indexed.

## 14. GraalJS Java List Identity Comparison Fails

**Severity: HIGH**

Karate's GraalJS runtime wraps Java `List` elements differently on each access.
Using `===` to compare a previously retrieved element against `list[i]` always
returns `false`, because the GraalJS proxy wrappers are different objects.

```javascript
// BROKEN: idx is always -1
var cb = findCallback(callbacks, 'DeviceBindingCallback');
for (var i = 0; i < callbacks.length; i++) {
    if (callbacks[i] === cb) { idx = i; break; }  // never matches
}

// CORRECT: use type-based match
for (var i = 0; i < callbacks.length; i++) {
    if (callbacks[i].type === 'DeviceBindingCallback') { ... }
}
```

**Rule**: Never use identity (`===`) comparison on Java collection elements
in Karate GraalJS helpers. Use property-based matching instead.

## 15. DeviceBindingService Must Be Enabled Before Journeys

**Severity: HIGH**

The `DeviceBindingService` must be enabled at realm level BEFORE any journey
using `DeviceBindingNode` or `DeviceSigningVerifierNode` will work. Without it,
the nodes silently fail. Enable via REST:
```bash
curl -X PUT ".../am/json/realms/root/realm-config/services/deviceBindingService" \
  -d '{"deviceBindingAttrName":"boundDevices","deviceBindingSettingsEncryptionScheme":"NONE"}'
```

Note: the service is NOT automatically enabled when journeys are imported.
The `setup-device-binding.sh` script handles this, but it must be re-run
after any PD volume recreation.

## 16. DeviceBindingNode Fails Silently — No Debug Output

**Severity: MEDIUM**

When `DeviceBindingNode` cannot validate a JWS response, it falls to the
`failure` outcome without any server-side error logging. The HTTP response is
simply `401 Unauthorized` with `"Login failure"` — no detail about what
validation step failed (signature, claims, key format, etc.).

AM's `Authentication` debug file at `/home/forgerock/openam/var/debug/Authentication`
is empty by default. Debug logging must be explicitly enabled to diagnose
DeviceBindingNode failures.

