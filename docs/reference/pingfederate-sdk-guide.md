# PingFederate SDK Implementation Guide

> Single-source reference for implementing PingFederate 13.0 plugins.
> Sourced from the official PingFederate Server documentation (SDK Developer's Guide section),
> the bundled SDK Javadocs, and the SDK sample projects shipped in the distribution.
> This guide is optimized for LLM context and implementation planning.

| Field | Value |
|-------|-------|
| Product | PingFederate Server |
| Version | 13.0 |
| SDK root | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/` |
| Javadocs | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/doc/index.html` |
| Official text source | `docs/reference/vendor/pingfederate_server-13.0.txt` |
| Related feature | `docs/architecture/features/003/` (future PingGateway), PingFederate research support |

---

## Topic Index

| # | Section | One-liner | Tags |
|---|---------|-----------|------|
| 1 | [SDK Layout](#1-sdk-layout) | Where source, docs, libs, and build scripts live | `layout`, `sdk`, `paths` |
| 2 | [Core Plugin Lifecycle](#2-core-plugin-lifecycle) | `configure()` + descriptor-driven runtime model | `lifecycle`, `plugin`, `configure` |
| 3 | [Build and Deployment](#3-build-and-deployment) | Ant build flow, manual packaging, deploy path | `ant`, `jar`, `deploy` |
| 4 | [Discovery Metadata (PF-INF)](#4-discovery-metadata-pf-inf) | Required plugin-type marker files for manual builds | `pf-inf`, `discovery` |
| 5 | [Primary Extension Points](#5-primary-extension-points) | IdP, SP, selector, STS, data source, PCV, provisioning | `interfaces`, `spi` |
| 6 | [Authentication API Plugins](#6-authentication-api-plugins) | `AuthnApiPlugin`, state/action models, support utils | `authn-api`, `api-capable` |
| 7 | [Session State Helpers](#7-session-state-helpers) | Session and transactional state handling | `session`, `state` |
| 8 | [SDK Sample Projects](#8-sdk-sample-projects) | What each bundled sample demonstrates | `examples`, `reference` |
| 9 | [Runtime Deploy Inventory](#9-runtime-deploy-inventory) | What is bundled in `server/default/deploy` | `runtime`, `bundled` |
| 10 | [Implementation Notes](#10-implementation-notes) | Practical constraints and planning guidance | `notes`, `planning` |

---

## 1. SDK Layout

SDK root:
`binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/`

Key contents:

- `plugin-src/`
- `doc/` (Javadocs)
- `lib/`
- `build.xml`
- `build.properties`
- `build.local.properties`

Official guide emphasis:

- `plugin-src` is the canonical place for custom plugin projects.
- `doc/index.html` is the full API reference.
- `build.xml` + `build.local.properties` is the supported build/deploy workflow.

Important compatibility note in official guide:

- The SDK Developer's Guide states: `PingFederate SDK only supports JDK 11` for the plugin build workflow.

---

## 2. Core Plugin Lifecycle

Most plugin types follow this pattern:

1. PingFederate creates plugin instance.
2. PingFederate injects admin-configured values via `configure(Configuration)`.
3. PingFederate retrieves descriptor metadata (`getPluginDescriptor()`, `getAdapterDescriptor()`, or `getSourceDescriptor()`).
4. Runtime invokes plugin entry methods for flow-specific behavior.

Shared interfaces (core contracts):

- `com.pingidentity.sdk.ConfigurablePlugin`
- `com.pingidentity.sdk.DescribablePlugin`

Descriptor role:

- Descriptor defines admin UI fields and metadata (type name, contract fields, optional version).
- Descriptor also controls runtime behavior expectations (for example, extended contract support).

---

## 3. Build and Deployment

### Ant-driven build (preferred)

From SDK guide and `sdk/build.xml`:

- `ant clean-plugin`
- `ant compile-plugin`
- `ant jar-plugin`
- `ant deploy-plugin`

`build.local.properties` controls target plugin:

```properties
# sdk/build.local.properties
target-plugin.name=<plugin-src subfolder>
pingfederate.home=../
```

Build outputs (from `build.properties`):

- Classes: `plugin-src/<plugin>/build/classes`
- JAR: `plugin-src/<plugin>/build/jar/pf.plugins.<plugin>.jar`

Deploy target:

- `<pf_install>/pingfederate/server/default/deploy`

### Manual build

Manual packaging is supported but requires explicit PF-INF descriptor files (next section).

---

## 4. Discovery Metadata (PF-INF)

For manual packaging, add `PF-INF/` at the JAR root and include plugin-type marker files.
Each file contains fully-qualified implementation class names, one per line.

| Plugin type | PF-INF file |
|-------------|-------------|
| IdP Adapter | `idp-authn-adapters` |
| SP Adapter | `sp-authn-adapters` |
| Custom Data Source | `custom-drivers` |
| Token Processor | `token-processors` |
| Token Generator | `token-generators` |
| Authentication Selector | `authentication-selectors` |
| Password Credential Validator | `password-credential-validators` |
| Identity Store Provisioner | `identity-store-provisioners` |
| CIBA Authenticator | `oob-auth-plugins` |
| Notification Publisher | `notification-sender` |

---

## 5. Primary Extension Points

### 5.1 IdP Adapter

Main interfaces:

- `com.pingidentity.sdk.IdpAuthenticationAdapterV2`
- `org.sourceid.saml20.adapter.idp.authn.IdpAuthenticationAdapter`

Core methods:

- `AuthnAdapterResponse lookupAuthN(HttpServletRequest, HttpServletResponse, Map<String,Object>)`
- `boolean logoutAuthN(Map, HttpServletRequest, HttpServletResponse, String)`
- `IdpAuthnAdapterDescriptor getAdapterDescriptor()`
- `void configure(Configuration)`

Key runtime behavior:

- Async browser flow is supported by committing `HttpServletResponse` and resuming on `resumePath`.
- `inParameters` includes rich context keys (OAuth client, scopes, policy, resume path, tracking IDs, etc.).

### 5.2 SP Adapter

Interface:

- `org.sourceid.saml20.adapter.sp.authn.SpAuthenticationAdapter`

Core methods:

- `Serializable createAuthN(SsoContext, HttpServletRequest, HttpServletResponse, String)`
- `boolean logoutAuthN(Serializable, HttpServletRequest, HttpServletResponse, String)`
- `String lookupLocalUserId(HttpServletRequest, HttpServletResponse, String, String)`

### 5.3 Authentication Selector

Interface:

- `com.pingidentity.sdk.AuthenticationSelector`

Core methods:

- `AuthenticationSelectorContext selectContext(HttpServletRequest, HttpServletResponse, Map<AuthenticationSourceKey,String>, Map<String,Object>, String)`
- `void callback(HttpServletRequest, HttpServletResponse, Map, AuthenticationSourceKey, AuthenticationSelectorContext)`

### 5.4 STS Token Translator

Processor:

- `org.sourceid.wstrust.plugin.process.TokenProcessor<T extends SecurityToken>`
- `TokenContext processToken(T token)`

Generator:

- `org.sourceid.wstrust.plugin.generate.TokenGenerator`
- `SecurityToken generateToken(TokenContext attributeContext)`

### 5.5 Custom Data Source

Interface:

- `com.pingidentity.sources.CustomDataSourceDriver`

Core methods:

- `SourceDescriptor getSourceDescriptor()`
- `void configure(Configuration)`
- `boolean testConnection()`
- `Map<String,Object> retrieveValues(Collection<String>, SimpleFieldList)`
- `List<String> getAvailableFields()`

### 5.6 Password Credential Validator

Interface:

- `com.pingidentity.sdk.password.PasswordCredentialValidator`

Core method:

- `AttributeMap processPasswordCredential(String username, String password)`

Optional capability interfaces (seen in sample):

- `ChangeablePasswordCredential`
- `ResettablePasswordCredential`
- `RecoverableUsername`

### 5.7 Identity Store Provisioner

Interfaces:

- `com.pingidentity.sdk.provision.IdentityStoreProvisioner`
- `com.pingidentity.sdk.provision.IdentityStoreProvisionerWithFiltering`

Core methods include:

- Users: `createUser`, `readUser`, `updateUser`, `deleteUser`
- Groups: `createGroup`, `readGroup`, `updateGroup`, `deleteGroup`
- Filtering/list APIs: `readUsers`, `readGroups` (with filtering interface)
- Capability: `isGroupProvisioningSupported()`

### 5.8 Notification Publisher

Interface:

- `com.pingidentity.sdk.notification.NotificationPublisherPlugin`

Core methods:

- `PublishResult publishNotification(String eventType, Map<String,String> data, Map<String,String> configuration)`
- Optional default variant: `publishNotificationWithObjectData(...)`

### 5.9 Secret Manager

Interface:

- `com.pingidentity.sdk.secretmanager.SecretManager`

Core method:

- `SecretInfo getSecretInfo(String secretId, Map<String,Object> inParameters)`

---

## 6. Authentication API Plugins

To make adapters/selectors API-capable:

- Implement `com.pingidentity.sdk.api.authn.AuthnApiPlugin`
- Provide `PluginApiSpec getApiSpec()`
- Optionally override `AuthnApiPluginDescriptor getApiPluginDescriptor()`

Utility class:

- `com.pingidentity.sdk.api.authn.util.AuthnApiSupport`

Key helper methods:

- `isApiRequest(HttpServletRequest)`
- `deserializeAsModel(HttpServletRequest, Class<T>)`
- `makeAuthnState(HttpServletRequest, AuthnStateSpec<T>, T)`
- `writeAuthnStateResponse(HttpServletRequest, HttpServletResponse, AuthnState<T>)`
- `writeErrorResponse(HttpServletRequest, HttpServletResponse, AuthnError)`
- `getActionId(HttpServletRequest)`

Pattern from shipped API-capable samples:

1. Detect action.
2. Deserialize action model.
3. Validate action payload.
4. Return state or error via `AuthnApiSupport`.

---

## 7. Session State Helpers

Core state classes:

- `org.sourceid.saml20.adapter.state.SessionStateSupport`
- `org.sourceid.saml20.adapter.state.TransactionalStateSupport`
- `org.sourceid.saml20.adapter.state.ApplicationSessionStateSupport`

Guidance from SDK docs:

- Use session helpers rather than direct servlet session usage for plugin state.
- Transaction-scoped state should be cleaned up when flow step completes.

---

## 8. SDK Sample Projects

Bundled sample projects under:
`binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/`

| Folder | Demonstrates |
|--------|--------------|
| `idp-adapter-example` | Basic non-interactive IdP adapter (`SampleSubnetAdapter`) |
| `template-render-adapter-example` | Interactive IdP adapter + Authn API support |
| `sp-adapter-example` | SP adapter lifecycle methods |
| `authentication-selector-example` | Selector with form/API behavior |
| `token-processor-example` | STS token processor |
| `token-generator-example` | STS token generator |
| `custom-data-store-example` | Custom data source connector |
| `password-credential-validator-example` | PCV + optional password flows |
| `identity-store-provisioner-example` | SCIM-style user/group provisioning |
| `notification-publisher-example` | Outbound notification publishing |
| `secret-manager-example` | Secret manager + admin actions |
| `ciba-auth-plugin-example` | OOB/CIBA plugin flow |
| `dynamic-client-registration-example` | Software statement validator plugin |
| `client-storage-example` | Custom OAuth client storage manager |
| `access-grant-example` | Access grant manager implementation |
| `external-consent-page-example` | External consent adapter flow |

---

## 9. Runtime Deploy Inventory

In the extracted PingFederate 13.0.1 runtime, plugin/app artifacts are in:
`binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/server/default/deploy/`

Notable shipped artifacts include:

- `pf-runtime.war`
- `pf-ws.war`
- `pf-scim.war`
- `provisioner-runtime.war`
- `pf-jwt-token-translator.jar`
- `pf-pingid-idp-adapter-2.17.0.jar`
- `pf-pingone-mfa-adapter-3.1.jar`
- `pf-pingone-pcv-3.1.jar`
- `x509-certificate-adapter-1.3.2.jar`
- `opentoken-adapter-2.9.1.jar`

Use this directory as the deploy target for custom plugin JARs and dependent third-party JARs.

---

## 10. Implementation Notes

1. Packaging convention from SDK tooling is `pf.plugins.<plugin-name>.jar`.
2. Ant tooling auto-generates deployment descriptor metadata used for plugin discovery.
3. API-capable plugins should provide deterministic state/action definitions and typed models.
4. The SDK examples are broad enough to bootstrap nearly every major extension type without starting from scratch.
5. For distributed/HA-ready implementations, do not copy in-memory sample storage patterns (`HashMap`-based sample stores).

---

## References

- `docs/reference/vendor/pingfederate_server-13.0.txt` (SDK Developer's Guide section around pp. 1721-1761)
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/doc/index.html`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/build.xml`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/build.properties`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/*`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/server/default/deploy/*`
