# PingFederate SDK Implementation Guide

> Single-source reference for implementing PingFederate 13.0 plugins.
> Sourced from the official PingFederate Server documentation (SDK Developer's Guide section),
> SDK Javadocs, and all SDK sample projects bundled in the distribution.
> This document is optimized for LLM context and implementation planning.
> **Goal:** self-contained guidance for day-to-day plugin development.
> **Completeness:** includes the entire official SDK section plus sample-derived implementation notes.

| Field | Value |
|-------|-------|
| Product | PingFederate Server |
| Version | 13.0 |
| SDK root | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/` |
| Javadocs | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/doc/index.html` |
| Official source text | `docs/reference/vendor/pingfederate_server-13.0.txt` |
| Official SDK range in source text | approx. lines `82582-84395` (SDK Developer's Guide section) |

---

## Topic Index

| # | Section | One-liner | Tags |
|---|---------|-----------|------|
| 1 | [SDK Scope and Extension Surface](#1-sdk-scope-and-extension-surface) | What the SDK is for and what extension points it exposes | `scope`, `overview`, `spi` |
| 2 | [SDK Directory Layout](#2-sdk-directory-layout) | Where source, docs, libs, and build files live | `layout`, `paths` |
| 3 | [Plugin Lifecycle and Shared Interfaces](#3-plugin-lifecycle-and-shared-interfaces) | `configure()` + descriptor-driven model used by most plugin types | `lifecycle`, `configure`, `descriptor` |
| 4 | [Descriptor and Admin UI Model](#4-descriptor-and-admin-ui-model) | Field descriptors, validators, actions, and table config patterns | `admin-ui`, `descriptor`, `validation` |
| 5 | [Build and Deploy with Ant](#5-build-and-deploy-with-ant) | Supported build pipeline and outputs | `ant`, `build`, `deploy` |
| 6 | [Manual Build and PF-INF Metadata](#6-manual-build-and-pf-inf-metadata) | Required discovery marker files for manual packaging | `pf-inf`, `jar`, `discovery` |
| 7 | [IdP Adapter SPI](#7-idp-adapter-spi) | Core flow contract for `IdpAuthenticationAdapterV2` | `idp`, `adapter`, `sso` |
| 8 | [SP Adapter SPI](#8-sp-adapter-spi) | Session creation, logout, and account linking methods | `sp`, `adapter`, `linking` |
| 9 | [STS Token Processor and Generator SPI](#9-sts-token-processor-and-generator-spi) | WS-Trust token translation contracts | `sts`, `token`, `wstrust` |
| 10 | [Authentication Selector SPI](#10-authentication-selector-spi) | Source selection and callback behavior | `selector`, `routing`, `callback` |
| 11 | [Custom Data Source Connector SPI](#11-custom-data-source-connector-spi) | Connection tests, fields, and runtime retrieval | `datasource`, `attribute`, `lookup` |
| 12 | [Password Credential Validator SPI](#12-password-credential-validator-spi) | Username/password validation plus optional reset/change contracts | `pcv`, `password`, `validation` |
| 13 | [Notification Publisher SPI](#13-notification-publisher-spi) | Publishing event notifications from PingFederate | `notification`, `publisher` |
| 14 | [Authentication API-Capable Plugins](#14-authentication-api-capable-plugins) | `AuthnApiPlugin`, states/actions/models, runtime handling | `authn-api`, `state-machine`, `json` |
| 15 | [Session State Management](#15-session-state-management) | Session helpers for global and transaction-scoped state | `session`, `state`, `security` |
| 16 | [Identity Store Provisioner SPI](#16-identity-store-provisioner-spi) | SCIM-oriented provisioning/deprovisioning contracts | `provisioning`, `scim`, `users`, `groups` |
| 17 | [SDK Sample Inventory](#17-sdk-sample-inventory) | What each bundled sample demonstrates | `samples`, `reference` |
| 18 | [Sample-Derived Patterns Not Explicit in Official Text](#18-sample-derived-patterns-not-explicit-in-official-text) | Practical behavior observed in code examples | `patterns`, `implementation` |
| 19 | [Production Hardening Checklist](#19-production-hardening-checklist) | What to change before shipping | `hardening`, `ops`, `ha` |
| 20 | [Quick Start Recipe](#20-quick-start-recipe) | Minimal end-to-end plugin flow | `quickstart`, `workflow` |
| 21 | [References](#21-references) | Source files used to build this guide | `sources` |

---

## 1. SDK Scope and Extension Surface

The PingFederate SDK is for custom plugin implementations that integrate PingFederate with external identity, data, and messaging systems.

Officially documented extension categories:

- Authentication adapters (IdP and SP)
- Authentication selectors
- WS-Trust token translators (token processors and token generators)
- Custom data source drivers
- Password credential validators
- Identity store provisioners
- Notification publishers

Important official upgrade warning:

- Custom components can change behavior after PingFederate upgrades; retest all customizations in non-critical upgraded environments.

---

## 2. SDK Directory Layout

SDK root:
`binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/`

Key files and directories:

- `plugin-src/` - custom plugin projects and bundled examples.
- `doc/` - SDK Javadocs (`index.html`).
- `lib/` - SDK compile-time libraries.
- `build.xml` - supported Ant build/deploy script.
- `build.properties` - shared build defaults and output paths.
- `build.local.properties` - local override for `target-plugin.name` and `pingfederate.home`.

Build script defaults (from SDK files):

- JAR name pattern: `pf.plugins.<target-plugin.name>.jar`
- Deploy directory: `<pf_install>/pingfederate/server/default/deploy`
- Build output directory: `plugin-src/<plugin>/build/`

Official note:

- SDK guide states plugin build workflow supports JDK 11.

---

## 3. Plugin Lifecycle and Shared Interfaces

### 3.1 Core plugin types

The official SDK flow is centered around two interface families:

1. Configurable plugins
2. Describable plugins

### 3.2 Configurable plugins

All configurable plugins implement:

```java
void configure(Configuration configuration)
```

Behavior:

- PingFederate calls `configure()` with admin-saved configuration.
- Plugin should cache required runtime values in instance fields.
- The instance becomes active only after configuration is complete.

### 3.3 Describable plugins

Plugins that need admin UI metadata return descriptors.

Descriptor methods by plugin family:

- `PluginDescriptor getPluginDescriptor()`
- `AuthnAdapterDescriptor getAdapterDescriptor()`
- `SourceDescriptor getSourceDescriptor()`

Special case from official docs:

- Adapters and custom data sources are describable but do not implement `DescribablePlugin` directly; they still expose descriptor methods.

### 3.4 Descriptor stability rule

Examples repeatedly document this requirement:

- Return the same descriptor instance across calls.

---

## 4. Descriptor and Admin UI Model

Descriptors define admin UI schema and runtime metadata.

Common building blocks used in samples:

- Field types: `TextFieldDescriptor`, `CheckBoxFieldDescriptor`, `SelectFieldDescriptor`, `RadioGroupFieldDescriptor`, `TextAreaFieldDescriptor`, `UploadFileFieldDescriptor`, `TableDescriptor`
- Validators: `RequiredFieldValidator`, `IntegerValidator`, `EmailValidator`, `HostnameValidator`, custom `FieldValidator`, `RowValidator`, `ConfigurationValidator`
- Actions: `ActionDescriptor` (including parameterized actions)

Sample-level behaviors worth reusing:

- `sp-adapter-example` shows mixed field types, table validation, and config-level cross-field validation.
- `secret-manager-example` shows action parameters and action invocation overload with `SimpleFieldList`.
- `ciba-auth-plugin-example` shows a "Test" action used for connectivity checks.

---

## 5. Build and Deploy with Ant

Official supported flow:

1. Set `target-plugin.name` in `sdk/build.local.properties`.
2. Optionally place third-party dependencies in `sdk/plugin-src/<plugin>/lib`.
3. Run Ant targets.

Main targets:

- `ant clean-plugin`
- `ant compile-plugin`
- `ant jar-plugin`
- `ant deploy-plugin`

Recommended order:

- `clean-plugin` -> `jar-plugin` -> `deploy-plugin`

What `jar-plugin` does:

- Compiles classes
- Invokes SDK task `BuildSdkDeploymentDesc` to generate deployment metadata
- Builds plugin JAR in `plugin-src/<plugin>/build/jar/`

What `deploy-plugin` does:

- Copies plugin JAR + `lib/*.jar` dependencies into deploy directory
- Copies optional `conf/` contents into PingFederate conf directory

Server restart:

- Required after deploy for plugin discovery/activation.

---

## 6. Manual Build and PF-INF Metadata

Manual build is supported but requires explicit discovery metadata.

Classpaths required to compile manually:

- `<pf_install>/pingfederate/server/default/lib`
- `<pf_install>/pingfederate/lib`
- `<pf_install>/pingfederate/sdk/lib`
- `<pf_install>/pingfederate/sdk/plugin-src/<subproject>/lib`

### 6.1 PF-INF directory requirements

- JAR must include `PF-INF/` at JAR root.
- Each plugin type needs a marker file listing fully qualified class names, one per line.

| Plugin type | PF-INF marker file |
|-------------|--------------------|
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

### 6.2 Manual deployment target

- Copy plugin JAR and dependency JARs to `<pf_install>/pingfederate/server/default/deploy`.

---

## 7. IdP Adapter SPI

### 7.1 Primary interface

Implement:

- `com.pingidentity.sdk.IdpAuthenticationAdapterV2`

Core methods:

```java
AuthnAdapterResponse lookupAuthN(
    HttpServletRequest req,
    HttpServletResponse resp,
    Map<String, Object> inParameters)
throws AuthnAdapterException, IOException;

boolean logoutAuthN(
    Map authnIdentifiers,
    HttpServletRequest req,
    HttpServletResponse resp,
    String resumePath)
throws AuthnAdapterException, IOException;
```

### 7.2 lookupAuthN behavior

Official behavior:

- Called for IdP-initiated SSO, SP-initiated SSO, OAuth transactions, and direct IdP-to-SP adapter processing.
- If response is committed, PingFederate pauses flow and resumes by reinvoking `lookupAuthN()` at resume path.
- Return value is ignored for committed responses.
- For non-committed completion:
  - set `authnStatus`
  - when `SUCCESS`, provide user attributes in `attributeMap`

Common styles:

- Interactive form adapter (template rendering)
- Redirect-to-external-auth-system adapter

### 7.3 inParameters map usage

`inParameters` contains runtime keys documented in `IdpAuthenticationAdapterV2`.
Observed sample usage includes:

- `IN_PARAMETER_NAME_RESUME_PATH`
- `IN_PARAMETER_NAME_PARTNER_ENTITYID`
- `IN_PARAMETER_NAME_CHAINED_ATTRIBUTES`
- `IN_PARAMETER_NAME_AUTHN_POLICY`
- `IN_PARAMETER_NAME_ADAPTER_ACTION`
- `IN_PARAMETER_NAME_OAUTH_SCOPE`
- `IN_PARAMETER_NAME_OAUTH_SCOPE_DESCRIPTIONS`
- `IN_PARAMETER_NAME_DEFAULT_SCOPE`
- `IN_PARAMETER_NAME_OAUTH_CLIENT_ID`
- `IN_PARAMETER_NAME_OAUTH_AUTHORIZATION_DETAIL_DESCRIPTIONS`

### 7.4 Session and logout guidance

Official guidance:

- Prefer PingFederate-managed authentication session over custom session tracking.
- If storing session attributes, use SDK state helpers (`SessionStateSupport`, `TransactionalStateSupport`).
- Remove transaction-scoped state before returning from `lookupAuthN()` to reduce heap usage and avoid security issues.
- In logout, clear internal session state and optionally redirect to external logout endpoint, then return to `resumePath`.

### 7.5 Password reset/change policy considerations

Official behavior:

- In those flows, `IN_PARAMETER_NAME_USERID` is the username entered at flow start.
- Reauthentication policy is conveyed via `IN_PARAMETER_NAME_AUTHN_POLICY`.
- Adapter should ignore previously cached auth state when reauthenticate is required.

---

## 8. SP Adapter SPI

### 8.1 Primary interface

Implement:

- `org.sourceid.saml20.adapter.sp.authn.SpAuthenticationAdapter`

Core methods:

```java
Serializable createAuthN(
    SsoContext ssoContext,
    HttpServletRequest req,
    HttpServletResponse resp,
    String resumePath)
throws AuthnAdapterException, IOException;

boolean logoutAuthN(
    Serializable authnBean,
    HttpServletRequest req,
    HttpServletResponse resp,
    String resumePath)
throws AuthnAdapterException, IOException;

String lookupLocalUserId(
    HttpServletRequest req,
    HttpServletResponse resp,
    String partnerIdpEntityId,
    String resumePath)
throws AuthnAdapterException, IOException;
```

### 8.2 Responsibilities

- `createAuthN`: establish SP-side security context for SSO request.
- `logoutAuthN`: terminate SP-side session for SLO.
- `lookupLocalUserId`: support account linking when no link exists yet.

### 8.3 Account linking flow notes

Official and sample guidance:

- Use `resumePath` for asynchronous local authentication.
- Return local user ID to allow PingFederate to establish persistent link.

---

## 9. STS Token Processor and Generator SPI

### 9.1 Token processor

Implement:

- `org.sourceid.wstrust.plugin.process.TokenProcessor<T extends SecurityToken>`

Core method:

```java
TokenContext processToken(T token)
```

Notes:

- `T` can be `BinarySecurityToken` for custom Base64-transported token formats.
- Processor returns subject/context attributes used downstream.

### 9.2 Token generator

Implement:

- `org.sourceid.wstrust.plugin.generate.TokenGenerator`

Core method:

```java
SecurityToken generateToken(TokenContext attributeContext)
```

Notes:

- Generator consumes subject data from `TokenContext` and emits a token.
- `BinarySecurityToken` is available for custom token formats.

---

## 10. Authentication Selector SPI

### 10.1 Primary interface

Implement:

- `com.pingidentity.sdk.AuthenticationSelector`

Core methods:

```java
AuthenticationSelectorContext selectContext(
    HttpServletRequest req,
    HttpServletResponse resp,
    Map<AuthenticationSourceKey, String> mappedAuthnSourcesNames,
    Map<String, Object> extraParameters,
    String resumePath);

void callback(
    HttpServletRequest req,
    HttpServletResponse resp,
    Map authnIdentifiers,
    AuthenticationSourceKey authenticationSourceKey,
    AuthenticationSelectorContext authnSelectorContext);
```

### 10.2 Behavior rules

- `selectContext()` chooses adapter or IdP connection target.
- If selector commits response, flow pauses and resumes on `resumePath`.
- `callback()` runs after selected source authentication.
- Official warning: writing response content in `callback()` is not supported; cookie writes are supported.

### 10.3 Result types

Selector context can choose by:

- context value (`ResultType.CONTEXT`)
- concrete adapter ID
- concrete IdP connection ID

---

## 11. Custom Data Source Connector SPI

### 11.1 Primary interface

Implement:

- `com.pingidentity.sources.CustomDataSourceDriver`

Core methods:

```java
boolean testConnection();
List<String> getAvailableFields();
Map<String,Object> retrieveValues(Collection<String> attributeNamesToFill, SimpleFieldList filterConfiguration);
SourceDescriptor getSourceDescriptor();
void configure(Configuration configuration);
```

### 11.2 Runtime contract

- `testConnection()` must return `true` only when connectivity is valid; false blocks admin progression.
- `getAvailableFields()` must return at least one field.
- `retrieveValues()` returns map keyed by requested attribute names.
- Use `CustomDataSourceDriverDescriptor` + `FilterFieldDataDescriptor` for runtime filter fields in admin UI.

Official filter note:

- Runtime attribute references in admin filters must use `${attributeName}` syntax.

---

## 12. Password Credential Validator SPI

### 12.1 Primary interface

Implement:

- `com.pingidentity.sdk.password.PasswordCredentialValidator`

Core method:

```java
AttributeMap processPasswordCredential(String username, String password)
throws PasswordValidationException;
```

Semantics:

- valid credentials -> non-empty `AttributeMap` (must include principal)
- invalid credentials -> `null` or empty map
- system/validation service failure -> throw `PasswordValidationException`

### 12.2 Optional capability interfaces

Common optional interfaces in samples:

- `ChangeablePasswordCredential`
- `ResettablePasswordCredential`
- `RecoverableUsername`

Sample behaviors include:

- change password policy checks returning recoverable auth exceptions
- user lookup for reset with required attributes (`mail`, `givenName`, etc.)
- username recovery by email

---

## 13. Notification Publisher SPI

### 13.1 Primary interface

Implement:

- `com.pingidentity.sdk.notification.NotificationPublisherPlugin`

Core method:

```java
PublishResult publishNotification(
    String eventType,
    Map<String, String> data,
    Map<String, String> configuration)
```

Runtime behavior:

- PingFederate invokes this method when configured events occur.
- Return `PublishResult` status (`SUCCESS`/`FAILURE`).

Sample implementation pattern:

- Serialize event payload to JSON
- POST to configured endpoint
- map HTTP result to `PublishResult.NOTIFICATION_STATUS`

---

## 14. Authentication API-Capable Plugins

### 14.1 Core concept

API-capable adapters/selectors support JSON API interaction instead of browser template rendering when invoked through authentication API endpoints.

### 14.2 Required interface

Implement:

- `com.pingidentity.sdk.api.authn.AuthnApiPlugin`

Methods:

- `PluginApiSpec getApiSpec()`
- `AuthnApiPluginDescriptor getApiPluginDescriptor()` (optional override)

### 14.3 API model vocabulary

- **State**: current transaction step (`status`) plus model payload.
- **Action**: allowed operation from current state.
- **Model**: typed payload for states/actions.
- **Errors**: top-level and detail error specs.

Relevant spec classes:

- `AuthnStateSpec`
- `AuthnActionSpec`
- `AuthnErrorSpec`
- `AuthnErrorDetailSpec`

### 14.4 API Explorer integration

The returned API spec powers API Explorer documentation and interactive testing (`/pf-ws/authn/explorer`, if enabled in admin settings).

### 14.5 Model annotation guidance

Official recommendation:

- Use `@Schema` on model getters with descriptions and required flags.

### 14.6 Runtime flow pattern (official)

In `lookupAuthN()` or `selectContext()`:

1. Check expected actions for current state.
2. If action requested, deserialize model and process it.
3. If unknown action ID requested, return `INVALID_ACTION_ID`.
4. If no action requested, return/render current state.

### 14.7 AuthnApiSupport utility

Common helper class:

- `AuthnApiSupport apiSupport = AuthnApiSupport.getDefault();`

Frequently used methods in samples/docs:

- `isApiRequest(req)`
- `getActionId(req)`
- `deserializeAsModel(req, ModelClass.class)`
- `makeAuthnState(req, stateSpec, model)`
- `writeAuthnStateResponse(req, resp, state)`
- `writeErrorResponse(req, resp, authnError)`

### 14.8 Validation and error handling

Official behavior:

- `deserializeAsModel()` validates shape/required fields and can throw `AuthnErrorException`.
- Plugin-specific validation should throw `AuthnErrorException` with `AuthnError` payload.
- Catch `AuthnErrorException` and write API error response.

### 14.9 Non-interactive plugin mode

For plugins that never prompt users:

- implement `AuthnApiPlugin`
- return `null` from `getApiSpec()`
- return descriptor with `interactive(false)` via `AuthnApiPluginDescriptor.Builder`

This avoids forced RESUME redirects during API executions.

### 14.10 Error localization

Official guidance and samples:

- Use `AuthnErrorDetail.userMessage` for end-user-facing error text.
- Localize with `LocaleUtil` + `LanguagePackMessages`.

---

## 15. Session State Management

Official guidance:

- Use SDK state helpers instead of direct servlet session usage.

Primary helpers:

- `SessionStateSupport` (global/session-scoped)
- `TransactionalStateSupport` (current transaction-scoped)

Sample usage patterns:

- `ExternalConsentPageAdapter` stores CSRF token and temporary authorization detail mappings via `SessionStateSupport`.
- `ciba-auth-plugin-example` stores OOB transaction state with `KeyValueStateSupport` and removes it in `finished()`.

Cleanup rule:

- Remove transaction state once flow completes to avoid leaks and stale security state.

---

## 16. Identity Store Provisioner SPI

### 16.1 Preferred interfaces

Implement one of:

- `IdentityStoreProvisionerWithFiltering` (supports list/query/filter)
- `IdentityStoreProvisioner` (no list/query/filter)

Deprecated:

- `IdentityStoreUserProvisioner` (users only)

### 16.2 Core user methods

```java
UserResponseContext createUser(CreateUserRequestContext ctx)
UserResponseContext readUser(ReadUserRequestContext ctx)
UserResponseContext updateUser(UpdateUserRequestContext ctx)
void deleteUser(DeleteUserRequestContext ctx)
```

With filtering interface additionally:

```java
UsersResponseContext readUsers(ReadUsersRequestContext ctx)
```

### 16.3 Core group methods

```java
boolean isGroupProvisioningSupported()
GroupResponseContext createGroup(CreateGroupRequestContext ctx)
GroupResponseContext readGroup(ReadGroupRequestContext ctx)
GroupResponseContext updateGroup(UpdateGroupRequestContext ctx)
void deleteGroup(DeleteGroupRequestContext ctx)
```

With filtering interface additionally:

```java
GroupsResponseContext readGroups(ReadGroupsRequestContext ctx)
```

### 16.4 Error semantics

- Throw `IdentityStoreException` subclasses for runtime/provisioning errors.
- If soft-delete model is used, deleted resources must behave as not found for read/update/delete.
- Conflict checks should ignore previously deleted resources where applicable.

### 16.5 Sample behavior highlights

`identity-store-provisioner-example` demonstrates:

- configurable delete-vs-disable user behavior
- SCIM paging/sort hooks (`startIndex`, `count`, `sortBy`, `sortOrder`)
- conflict handling on username uniqueness
- optional group provisioning support path

---

## 17. SDK Sample Inventory

Path:
`binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/`

| Sample folder | Primary extension point | Key capability shown |
|---------------|-------------------------|----------------------|
| `idp-adapter-example` | IdP adapter | Non-interactive API-capable adapter (`interactive=false`) |
| `template-render-adapter-example` | IdP adapter | Interactive form + Authn API states/actions/errors |
| `external-consent-page-example` | IdP adapter | External OAuth consent, scope/authorization-details approval, CSRF |
| `sp-adapter-example` | SP adapter | SP session creation/logout/account-link plus rich GUI descriptors |
| `authentication-selector-example` | Selector | Cookie-based context selection + API-capable selector |
| `token-processor-example` | STS processor | `BinarySecurityToken` decode -> subject context |
| `token-generator-example` | STS generator | Subject context -> encoded `BinarySecurityToken` |
| `custom-data-store-example` | Data source | Filter fields, properties-file lookup, connection test |
| `password-credential-validator-example` | PCV | Validate + change/reset/recovery optional interfaces |
| `identity-store-provisioner-example` | Provisioner | User/group CRUD with filtering/sorting/pagination hooks |
| `notification-publisher-example` | Notification | HTTP POST publisher implementation |
| `secret-manager-example` | Secret manager | Secret reference generation/verification admin actions |
| `ciba-auth-plugin-example` | OOB/CIBA | OOB initiate/check/finished with review/approve/deny handlers |
| `dynamic-client-registration-example` | Dynamic client registration | Software statement JWT validation and metadata mapping |
| `client-storage-example` | OAuth client storage | CRUD/search/sort pagination for client data |
| `access-grant-example` | Access grant manager | Access grant persistence/revocation and criteria lookup |

---

## 18. Sample-Derived Patterns Not Explicit in Official Text

These are practical patterns visible in shipped examples and useful for implementation quality.

### 18.1 API-capable non-interactive plugins

`idp-adapter-example` demonstrates the recommended non-interactive API pattern:

- `getApiSpec()` returns `null`
- `getApiPluginDescriptor()` returns `interactive(false)`

This avoids unnecessary RESUME responses for API clients.

### 18.2 Template rendering and i18n

Interactive samples consistently pair:

- `TemplateRendererUtil.render(...)`
- `LocaleUtil.getUserLocale(req)`
- `LanguagePackMessages("<bundle>", locale)`

and provide language pack files under `conf/language-packs/`.

### 18.3 Callback discipline for selectors

`authentication-selector-example` follows official guidance:

- `callback()` sets cookies only
- no body writes in callback path

### 18.4 Session-safe consent handling

`external-consent-page-example` adds concrete security pattern:

- generate/store CSRF token in session state
- verify token on approval POST
- map authorization details to short random IDs before rendering page

### 18.5 OOB/CIBA transaction lifecycle

`ciba-auth-plugin-example` demonstrates full lifecycle contract:

- `initiate(...)` allocates transaction state, sends notification
- `check(...)` returns `IN_PROGRESS`/`SUCCESS`/`FAILURE`
- `finished(...)` removes transaction state

### 18.6 Admin action patterns

`secret-manager-example` and `ciba-auth-plugin-example` show using `ActionDescriptor` to:

- run connectivity checks
- generate and validate secret references
- accept action parameters via `SimpleFieldList`

### 18.7 Rich GUI validation

`sp-adapter-example` demonstrates three useful validation scopes:

- field-level (`FieldValidator`)
- row-level (`RowValidator` for table rows)
- whole-config (`ConfigurationValidator`)

### 18.8 HA caveat in in-memory samples

`access-grant-example`, `client-storage-example`, and `identity-store-provisioner-example` are intentionally in-memory.

Implication:

- do not reuse these storage approaches for HA/DR production deployments.
- back persistent plugin state with external durable store.

---

## 19. Production Hardening Checklist

Before production use:

1. Replace in-memory state stores with durable shared storage.
2. Add structured logging (avoid `System.out` usage in samples).
3. Add strict input validation for all request-derived fields.
4. Ensure CSRF and replay protections for interactive handlers.
5. Enforce bounded retries/timeouts for network dependencies.
6. Validate compatibility across upgrades in dedicated regression suites.
7. Document and test restart/redeploy behavior (state cleanup, idempotency).
8. Validate plugin behavior in cluster mode (session replication, callback paths).

---

## 20. Quick Start Recipe

For a new plugin in SDK workspace:

1. Create `sdk/plugin-src/<plugin-name>/java/...` package structure.
2. Implement target SPI and descriptor method(s).
3. Add GUI fields/validators/actions in descriptor.
4. Implement `configure(Configuration)` and runtime methods.
5. Set `target-plugin.name=<plugin-name>` in `sdk/build.local.properties`.
6. Run:

```bash
cd binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk
ant clean-plugin
ant jar-plugin
ant deploy-plugin
```

7. Restart PingFederate.
8. Configure plugin instance in admin console and validate flow end-to-end.

---

## 21. References

Primary official source text:

- `docs/reference/vendor/pingfederate_server-13.0.txt` (SDK Developer's Guide section; lines approx. `82582-84395`)

SDK build/config and API docs:

- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/build.xml`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/build.properties`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/build.local.properties`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/doc/index.html`

Sample sources (all reviewed for this guide):

- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/idp-adapter-example/java/com/pingidentity/adapter/idp/SampleSubnetAdapter.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/template-render-adapter-example/java/com/pingidentity/adapter/idp/TemplateRenderAdapter.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/authentication-selector-example/java/com/pingidentity/authentication/selector/SampleAuthenticationSelector.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/sp-adapter-example/java/com/pingidentity/adapter/sp/SpAuthnAdapterExample.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/token-processor-example/java/com/pingidentity/processor/SampleTokenProcessor.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/token-generator-example/java/com/pingidentity/generator/SampleTokenGenerator.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/custom-data-store-example/java/com/pingidentity/customdatastore/SamplePropertiesDataStore.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/password-credential-validator-example/java/com/pingidentity/password/credential/validator/SamplePasswordCredentialValidator.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/identity-store-provisioner-example/java/com/pingidentity/identitystoreprovisioners/sample/SampleIdentityStoreProvisioner.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/notification-publisher-example/java/com/pingidentity/notification/publisher/HttpNotificationPublisher.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/secret-manager-example/java/com/pingidentity/secretmanager/SampleSecretManager.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/ciba-auth-plugin-example/java/com/pingidentity/oob/SampleEmailAuthPlugin.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/external-consent-page-example/java/com/pingidentity/adapter/idp/ExternalConsentPageAdapter.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/dynamic-client-registration-example/java/com/pingidentity/clientregistration/SoftwareStatementValidatorPlugin.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/client-storage-example/java/com/pingidentity/clientstorage/SampleClientStorage.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/access-grant-example/java/com/pingidentity/accessgrant/SampleAccessGrant.java`
