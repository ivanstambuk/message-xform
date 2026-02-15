# PingFederate SDK Implementation Guide

> Single-source, implementation-focused guide for PingFederate 13.0 SDK development.
> This file is intentionally dense and self-contained.
> It consolidates the full SDK chapter from the official server documentation and concrete behavior from every bundled SDK sample project.

| Field | Value |
|---|---|
| Product | PingFederate Server |
| Version | 13.0 |
| SDK root | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/` |
| Official source text | `docs/reference/vendor/pingfederate_server-13.0.txt` |
| Official SDK chapter region | approx. lines `82690-84430` |
| Sample source root | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/` |

## Topic Index

| # | Section | Coverage |
|---|---|---|
| 1 | SDK Layout and Build Pipeline | Directory layout, Ant targets, build properties, output paths |
| 2 | Core Plugin Model | `ConfigurablePlugin`, describable plugins, descriptor contracts |
| 3 | Packaging and Discovery | Ant descriptor generation, PF-INF markers, manual packaging |
| 4 | Logging | Logging expectations and sample logging patterns |
| 5 | IdP Adapter SPI | `IdpAuthenticationAdapterV2` full runtime contract |
| 6 | SP Adapter SPI | `SpAuthenticationAdapter` methods and flow semantics |
| 7 | STS Token SPI | `TokenProcessor` and `TokenGenerator` contracts |
| 8 | Authentication Selector SPI | `selectContext`/`callback` runtime behavior |
| 9 | Custom Data Source SPI | `testConnection`, `getAvailableFields`, `retrieveValues` |
| 10 | Password Credential Validator SPI | Validation, reset/change/recovery interfaces |
| 11 | Notification Publisher SPI | `publishNotification` contract and response mapping |
| 12 | Authentication API-Capable Plugins | `AuthnApiPlugin`, states/actions/models/errors, runtime handling |
| 13 | Session State and Transaction State | `SessionStateSupport`, `TransactionalStateSupport`, cleanup rules |
| 14 | Error Localization | `AuthnErrorDetail`, `userMessage`, language packs |
| 15 | Identity Store Provisioner SPI | User/group CRUD, filtering, pagination, sort, deletion semantics |
| 16 | Additional SDK Extension SPIs in Samples | OOB/CIBA, Secret Manager, Dynamic Client Registration, Access Grant, Client Storage |
| 17 | Full Sample Inventory and Deep Notes | Every sample directory with concrete implementation learnings |
| 18 | Cross-Sample Reusable Patterns | Practical patterns not obvious from interface docs |
| 19 | Hardening Checklist | Production readiness deltas from sample code |
| 20 | SDK-Relevant Release Deltas | Important SDK-related additions surfaced in docs |
| 21 | Build-Deploy Quick Start | End-to-end plugin creation flow |
| 22 | Source Inventory | Files reviewed for this guide |

## 1. SDK Layout and Build Pipeline

SDK root:
`binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/`

Main directories and files:

- `plugin-src/`: custom plugin projects plus all bundled examples.
- `doc/`: SDK API docs.
- `lib/`: SDK compilation support libraries.
- `build.xml`: canonical Ant build/deploy workflow.
- `build.properties`: shared immutable defaults.
- `build.local.properties`: local overrides (`target-plugin.name`, `pingfederate.home`).

Official SDK chapter notes:

- Supported SDK JDK baseline is JDK 11.
- Recommended target order: `clean-plugin`, `jar-plugin`, `deploy-plugin`.

Build property values (from shipped files):

- Plugin source root: `${plugin-src.dir}` -> `plugin-src`
- Selected plugin folder: `${target-plugin.dir}` -> `plugin-src/${target-plugin.name}`
- Plugin JAR prefix: `pf.plugins.`
- Plugin JAR name: `pf.plugins.${target-plugin.name}.jar`
- Deploy dir: `${pingfederate.home}/server/default/deploy`
- Conf copy dir: `${pingfederate.home}/server/default/conf`

Key Ant targets:

- `clean-plugin`: remove `${target-plugin.build.dir}`
- `compile-plugin`: compile `java/` sources to `build/classes`
- `jar-plugin`: generate deployment descriptors + package plugin JAR
- `deploy-plugin`: copy plugin JAR plus `lib/*.jar` to deploy dir and copy optional `conf/`

What `jar-plugin` adds beyond raw compilation:

- Runs SDK task `BuildSdkDeploymentDesc`
- Produces plugin discovery metadata in `PF-INF` so PingFederate can find plugin classes

## 2. Core Plugin Model

Official model distinguishes two core behaviors:

1. Configurable plugin behavior
2. Describable plugin behavior

### 2.1 Configurable behavior

Configurable plugins implement:

```java
void configure(Configuration configuration)
```

Runtime contract:

- PingFederate calls `configure()` with admin-saved configuration.
- Plugin should cache runtime values from `Configuration`.
- Server finishes creation/configuration before exposing instance to live traffic.

### 2.2 Describable behavior

Most plugin types provide descriptor metadata used to render admin UI.

Descriptor methods by family:

```java
PluginDescriptor getPluginDescriptor();
AuthnAdapterDescriptor getAdapterDescriptor();
SourceDescriptor getSourceDescriptor();
```

Important documented nuance:

- Adapter plugins and custom data source plugins are describable even though they do not directly implement `DescribablePlugin`.

Descriptor contents commonly include:

- Field descriptors
- Field validators
- Row/config validators
- Actions
- Attribute contracts
- Extended contract support flags

Descriptor stability rule:

- Return the same descriptor object instance on each call.

## 3. Packaging and Discovery

### 3.1 Ant-based packaging (recommended)

Ant build path automatically creates required discovery metadata and JAR layout.

High-level flow:

1. Set `target-plugin.name` in `build.local.properties`.
2. Place third-party JARs in `plugin-src/<plugin>/lib` if needed.
3. Run `ant deploy-plugin` or `clean-plugin -> jar-plugin -> deploy-plugin`.
4. Restart PingFederate.

### 3.2 Manual packaging rules

Manual builds must produce proper `PF-INF` markers at JAR root.

Required classpath roots for manual compile:

- `<pf_install>/pingfederate/server/default/lib`
- `<pf_install>/pingfederate/lib`
- `<pf_install>/pingfederate/sdk/lib`
- `<pf_install>/pingfederate/sdk/plugin-src/<subproject>/lib`

PF-INF marker mapping:

| Plugin type | PF-INF file |
|---|---|
| IdP Adapter | `idp-authn-adapters` |
| SP Adapter | `sp-authn-adapters` |
| Custom Data Source | `custom-drivers` |
| Token Processor | `token-processors` |
| Token Generator | `token-generators` |
| Authentication Selector | `authentication-selectors` |
| Password Credential Validator | `password-credential-validators` |
| Identity Store Provisioner | `identity-store-provisioners` |
| CIBA/OOB Auth Plugin | `oob-auth-plugins` |
| Notification Publisher | `notification-sender` |

Each marker file format:

- one fully-qualified implementation class per line
- class must implement the corresponding SPI

Manual deployment destination:

- copy plugin JAR and dependent third-party JARs to `<pf_install>/pingfederate/server/default/deploy`

## 4. Logging

Official SDK chapter includes a dedicated logging note:

- Use normal Java logging patterns (examples commonly use Apache Commons logging or Log4j wrappers).
- `sp-adapter-example` demonstrates structured logging usage.

Sample reality:

- Legacy samples use `commons-logging` (`LogFactory`), Log4j, and one sample uses `System.out` (`SampleSubnetAdapter`) as a demonstration only.
- Production plugins should use a consistent logger and avoid `System.out`.

## 5. IdP Adapter SPI

Primary SPI:

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

### 5.1 `lookupAuthN` execution semantics

Official behavior:

- Called for IdP-initiated SSO, SP-initiated SSO, OAuth transactions, and direct IdP-to-SP processing.
- Also used when adapter is in password reset/change policy.
- If response is committed, PingFederate pauses transaction and re-invokes adapter at resume path.
- Return value is ignored for committed responses.
- For a non-committed return:
  - `authnStatus` must be set.
  - On `SUCCESS`, `attributeMap` must contain user attributes.

Observed sample status usage:

- `AUTHN_STATUS.SUCCESS`
- `AUTHN_STATUS.FAILURE`
- `AUTHN_STATUS.IN_PROGRESS` when adapter writes response and awaits next invocation.

### 5.2 `inParameters` map keys used in shipped samples

Samples consume the following `IdpAuthenticationAdapterV2` keys:

- `IN_PARAMETER_NAME_RESUME_PATH`
- `IN_PARAMETER_NAME_PARTNER_ENTITYID`
- `IN_PARAMETER_NAME_CHAINED_ATTRIBUTES`
- `IN_PARAMETER_NAME_ADAPTER_ACTION`
- `IN_PARAMETER_NAME_OAUTH_SCOPE`
- `IN_PARAMETER_NAME_OAUTH_SCOPE_DESCRIPTIONS`
- `IN_PARAMETER_NAME_DEFAULT_SCOPE`
- `IN_PARAMETER_NAME_OAUTH_CLIENT_ID`
- `IN_PARAMETER_NAME_OAUTH_AUTHORIZATION_DETAIL_DESCRIPTIONS`
- `IN_PARAMETER_NAME_AUTHN_POLICY`

SDK release note addition relevant to adapters:

- `IN_PARAMETER_NAME_SRI` exposed in SDK (`pi.sri` value).

### 5.3 Adapter style patterns from samples

`template-render-adapter-example`:

- Interactive form + API-capable mode in same adapter.
- HTML path uses `TemplateRendererUtil` and language pack messages.
- API path uses `AuthnApiSupport` and JSON state/action flow.

`idp-adapter-example` (`SampleSubnetAdapter`):

- Non-interactive adapter validates client IPv4 subnet.
- Uses chained attributes to assign role (`GUEST` vs `CORP_USER`).
- Implements non-interactive API mode (`getApiSpec() == null`, `interactive(false)`).

`external-consent-page-example`:

- Enforces adapter action `ADAPTER_ACTION_EXTERNAL_CONSENT`.
- Displays OAuth scope and authorization detail approval page.
- Persists CSRF token and authorization-detail identifier map in `SessionStateSupport`.
- Supports returning approved scopes and authorization details in multiple accepted formats.
- Returns adapter info flag `ADAPTER_INFO_EXTERNAL_CONSENT_ADAPTER=true`.

### 5.4 Session and reauthentication rules

Official guidance:

- Prefer PingFederate-managed authentication sessions over internal session tracking.
- If maintaining custom state, use:
  - `SessionStateSupport` for global/session state
  - `TransactionalStateSupport` for transaction-scoped state
- Remove transaction-scoped attributes before returning from `lookupAuthN()`.
- For password reset/change flows, respect reauthentication policy in `IN_PARAMETER_NAME_AUTHN_POLICY` and do not reuse stale session state.

### 5.5 Logout (`logoutAuthN`) behavior

Official behavior:

- Called during IdP-initiated and SP-initiated SLO.
- Can run asynchronously using response + resume path.
- If adapter has internal session state, clear it here.
- If external auth system exists, redirect to external logout endpoint and return to `resumePath`.

Sample behaviors:

- `TemplateRenderAdapter` and `SampleSubnetAdapter`: return `true` (no-op logout).
- `ExternalConsentPageAdapter`: throws adapter exception (explicitly not supporting logout).

## 6. SP Adapter SPI

Primary SPI:

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

Runtime semantics:

- `createAuthN`: establish SP-side security context during SSO.
- `logoutAuthN`: terminate SP-side session during SLO.
- `lookupLocalUserId`: used for account linking when no link exists yet.

`sp-adapter-example` specifics:

- Uses `LocalIdPasswordLookup` against uploaded user/password properties file.
- Demonstrates returning serializable authn state from `createAuthN` and receiving it in `logoutAuthN`.
- Provides the richest descriptor/GUI validation patterns in the SDK samples.

## 7. STS Token SPI

### 7.1 Token Processor

SPI:

- `TokenProcessor<T extends SecurityToken>`

Core method:

```java
TokenContext processToken(T token)
```

`token-processor-example` behavior:

- Implements `TokenProcessor<BinarySecurityToken>`.
- Decodes binary token payload as UTF-8 subject string.
- Returns `TokenContext` with subject attributes map containing `subject`.

### 7.2 Token Generator

SPI:

- `TokenGenerator`

Core method:

```java
SecurityToken generateToken(TokenContext attributeContext)
```

`token-generator-example` behavior:

- Reads `subject` from `TokenContext`.
- Builds `BinarySecurityToken` with token type `urn:sample:token`.
- Encodes subject bytes to Base64 payload.

## 8. Authentication Selector SPI

Primary SPI:

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

Runtime semantics:

- `selectContext` chooses authentication source (adapter, IdP connection, or context mapping).
- Committed response in `selectContext` pauses flow; browser must return to `resumePath`.
- `callback` runs after selected auth source completes.

Official callback restriction:

- Do not write response body in `callback`.
- Cookie writes are supported.

`authentication-selector-example` behavior:

- Selector prompts for email/domain, extracts domain, and sets persistent cookie.
- Supports API and non-API execution paths.
- If no input exists, returns `null` after rendering response (selector still in progress).
- Defaults cookie name to `pf-authn-selector-<configuration-id>` when not configured.
- Uses `AuthenticationSelectorDescriptor.setSupportsExtendedResults(true)`.

Result type usage:

- Example sets `ResultType.CONTEXT` and returns domain string for context mapping.
- Other legal result types: `ADAPTER_ID`, `IDP_CONN_ID`.

## 9. Custom Data Source SPI

Primary SPI:

- `com.pingidentity.sources.CustomDataSourceDriver`

Core methods:

```java
boolean testConnection();
List<String> getAvailableFields();
Map<String, Object> retrieveValues(Collection<String> attributeNamesToFill, SimpleFieldList filterConfiguration);
SourceDescriptor getSourceDescriptor();
void configure(Configuration configuration);
```

Runtime semantics:

- `testConnection()` gates admin progress; false blocks continuation.
- `getAvailableFields()` must return at least one field.
- `retrieveValues()` must return map keyed by requested attribute names; return empty map when no match.

`custom-data-store-example` behavior:

- Uses properties-file directory as storage.
- Filter key: `SamplePropertiesDataStore Username`.
- Descriptor includes both configuration fields and filter fields.
- Reads `<username>.properties` and maps requested fields.
- Missing properties return empty string values in sample implementation.

Official filter substitution rule:

- Runtime attributes in admin-defined filters use `${attributeName}` syntax.

## 10. Password Credential Validator SPI

Primary SPI:

- `com.pingidentity.sdk.password.PasswordCredentialValidator`

Core method:

```java
AttributeMap processPasswordCredential(String username, String password)
throws PasswordValidationException;
```

Semantics:

- Valid credential -> non-empty `AttributeMap` with principal attributes.
- Invalid credential -> null or empty map.
- System or backend failures -> `PasswordValidationException`.

Optional interfaces demonstrated by sample:

- `ChangeablePasswordCredential`
- `ResettablePasswordCredential`
- `RecoverableUsername`

`password-credential-validator-example` behavior:

- Stores a single credential pair in configuration for demonstration.
- Supports password policy check on reset/change (min length, recoverable errors).
- Implements:
  - `changePassword(...)`
  - `isPasswordChangeable()`
  - `isChangePasswordEmailNotifiable()`
  - `isPendingPasswordExpiryNotifiable()`
  - `findUser(...)`
  - `findUsersByMail(...)`
  - attribute-name getters for reset/recovery metadata
  - `isPasswordResettable()`
  - `resetPassword(...)`

Sample caveat explicitly documented in code:

- Non-admin password updates are not persisted/replicated for cluster usage.

## 11. Notification Publisher SPI

Primary SPI:

- `com.pingidentity.sdk.notification.NotificationPublisherPlugin`

Core method:

```java
PublishResult publishNotification(
    String eventType,
    Map<String, String> data,
    Map<String, String> configuration)
```

Runtime semantics:

- Called when configured events trigger notifications.
- Plugin returns `PublishResult` with notification status.

`notification-publisher-example` behavior:

- Descriptor type: `Sample HTTP Notification Publisher`.
- Config field: `POST Endpoint`.
- Builds JSON payload containing `eventType` and `data`.
- Sends HTTP POST via Apache HTTP client.
- Sets status `SUCCESS` on HTTP 200, otherwise `FAILURE`.

## 12. Authentication API-Capable Plugins

Primary SPI:

- `com.pingidentity.sdk.api.authn.AuthnApiPlugin`

Methods:

```java
PluginApiSpec getApiSpec();
AuthnApiPluginDescriptor getApiPluginDescriptor(); // optional override
```

### 12.1 Concept model

Authentication API transaction model:

- PingFederate assigns flow ID to each API-based auth transaction.
- Transaction always has a current state (`status`) with optional model fields.
- State response also advertises available actions.
- Action POST payload shape is determined by action model class.

### 12.2 Spec objects

Core spec types:

- `AuthnStateSpec<TModel>`
- `AuthnActionSpec<TModel>`
- `AuthnErrorSpec`
- `AuthnErrorDetailSpec`

Builder usage shown in official text and samples:

```java
new AuthnStateSpec.Builder<...>().status(...).modelClass(...).action(...).build();
new AuthnActionSpec.Builder<...>().id(...).modelClass(...).error(...).errorDetail(...).build();
new AuthnErrorDetailSpec.Builder().code(...).message(...).parentCode(...).build();
```

### 12.3 Sample API specs

`template-render-adapter-example`:

- State ID: `USER_ATTRIBUTES_REQUIRED`
- Action ID: `submitUserAttributes`
- Error detail code: `INVALID_ATTRIBUTE_NAME`
- Includes `CommonActionSpec.CANCEL_AUTHENTICATION`
- Includes validation error mapping to `CommonErrorSpec.VALIDATION_ERROR`

`authentication-selector-example`:

- State ID: `EMAIL_OR_DOMAIN_REQUIRED`
- Action ID: `submitEmailOrDomain`
- Includes cancel action

### 12.4 Runtime handling pattern

Official pattern for `lookupAuthN()` or `selectContext()`:

1. Check whether request carries an expected action for current state.
2. If action exists, deserialize action model and process.
3. If action ID exists but does not match any valid action for state, return `INVALID_ACTION_ID`.
4. If no action requested, render current state response.

Common utility class:

- `AuthnApiSupport apiSupport = AuthnApiSupport.getDefault();`

Frequently used helper methods:

- `apiSupport.isApiRequest(req)`
- `apiSupport.getActionId(req)`
- `apiSupport.deserializeAsModel(req, Model.class)`
- `apiSupport.makeAuthnState(req, stateSpec, model)`
- `apiSupport.writeAuthnStateResponse(req, resp, state)`
- `apiSupport.writeErrorResponse(req, resp, authnError)`

Validation flow from official guidance:

- `deserializeAsModel()` enforces JSON shape and required fields (`@Schema(required=true)`).
- Additional domain validation should throw `AuthnErrorException` with detail objects.
- Catch `AuthnErrorException` and write API error response.

### 12.5 Non-interactive API-capable plugins

Official and sample pattern:

- implement `AuthnApiPlugin`
- return `null` from `getApiSpec()`
- return `new AuthnApiPluginDescriptor.Builder().interactive(false).build()`

Effect:

- PingFederate can execute plugin directly in API and front-channel paths without forced redirect/RESUME.

## 13. Session State and Transaction State

Official mechanisms:

- `SessionStateSupport`: user session-scoped attributes across transactions.
- `TransactionalStateSupport`: attributes scoped to current auth transaction.

Guidance:

- Use same state strategy for API and non-API requests.
- Do not introduce extra state just for API mode if existing state is sufficient.
- Remove transactional attributes when flow completes.

Sample usage highlights:

- `ExternalConsentPageAdapter`: CSRF token and authorization-detail identifier map via `SessionStateSupport`.
- `SampleEmailAuthPlugin`: OOB transaction data in `KeyValueStateSupport`, cleaned in `finished()`.

## 14. Error Localization

Official API error localization pattern:

- Use `AuthnErrorDetail.userMessage` for end-user-facing text.
- Define reusable error detail specs (`code`, `message`, `parentCode`).
- Build localized user message at runtime via language bundles.

Localization utilities used by samples:

- `LocaleUtil.getUserLocale(req)`
- `LanguagePackMessages("<bundle>", locale)`

Template samples load these bundles:

- `attribute-form-template.properties`
- `sample-authn-selector-email-template.properties`
- `external-consent-page.properties`
- `request-approval-page.properties`

## 15. Identity Store Provisioner SPI

Preferred interfaces:

- `IdentityStoreProvisionerWithFiltering`
- `IdentityStoreProvisioner`

Deprecated interface:

- `IdentityStoreUserProvisioner`

### 15.1 User operations

```java
UserResponseContext createUser(CreateUserRequestContext createRequestCtx)
UserResponseContext readUser(ReadUserRequestContext readRequestCtx)
UsersResponseContext readUsers(ReadUsersRequestContext readRequestCtx) // WithFiltering only
UserResponseContext updateUser(UpdateUserRequestContext updateRequestCtx)
void deleteUser(DeleteUserRequestContext deleteRequestCtx)
```

### 15.2 Group operations

```java
boolean isGroupProvisioningSupported()
GroupResponseContext createGroup(CreateGroupRequestContext createRequestCtx)
GroupResponseContext readGroup(ReadGroupRequestContext readRequestCtx)
GroupsResponseContext readGroups(ReadGroupsRequestContext readRequestCtx) // WithFiltering only
GroupResponseContext updateGroup(UpdateGroupRequestContext updateRequestCtx)
void deleteGroup(DeleteGroupRequestContext deleteRequestCtx)
```

Error contract:

- Throw `IdentityStoreException` subclasses for operational errors.
- If using soft-delete semantics, deleted IDs must behave as not found for read/update/delete.
- Deleted resources must not incorrectly trigger conflict checks.

`identity-store-provisioner-example` deep behavior:

- In-memory user/group caches (`ConcurrentHashMap`).
- Admin radio option controls delete behavior:
  - disable user
  - permanently delete user
- Implements SCIM-style list/query hooks:
  - filter string logging
  - sort mapping via `getSCIMTargetToSourceAttributeMapping()`
  - pagination (`startIndex`, `count`)
- Demonstrates conflict logic on username uniqueness with soft-delete mode awareness.
- Group contract includes member handling via `MemberAttribute`.

## 16. Additional SDK Extension SPIs in Samples

These SPIs are present in shipped sample code and are highly relevant when extending PingFederate beyond the core SDK chapter list.

### 16.1 OOB/CIBA Authentication Plugin (`OOBAuthPlugin`)

Sample class:

- `com.pingidentity.oob.SampleEmailAuthPlugin`

Core methods used:

```java
OOBAuthTransactionContext initiate(OOBAuthRequestContext requestContext, Map<String, Object> inParameters)
OOBAuthResultContext check(String transactionIdentifier, Map<String, Object> inParameters)
void finished(String transactionIdentifier)
```

Sample flow:

- Registers custom handlers for review/approve/deny endpoints.
- Sends email containing approval URL.
- Tracks transaction in `KeyValueStateSupport`.
- Supports approved scopes and approved authorization details.
- Optional status-change callback flag in configuration.

Handler paths used:

- `/oob-auth/review`
- `/oob-auth/approve`
- `/oob-auth/deny`

### 16.2 Secret Manager (`SecretManager`)

Sample class:

- `com.pingidentity.secretmanager.SampleSecretManager`

Core method:

```java
SecretInfo getSecretInfo(String secretId, Map<String, Object> inParameters)
```

Sample behavior:

- Reads secret from environment variable named by `secretId`.
- Returns `SecretInfo(secret, attributes)`.
- Provides admin actions:
  - generate secret reference
  - verify secret reference
- Uses reference format:
  - `OBF:MGR:{secretManagerId}:{secretId}`

### 16.3 Dynamic Client Registration Plugin

Sample class:

- `com.pingidentity.clientregistration.SoftwareStatementValidatorPlugin`

Core methods:

```java
void processPlugin(HttpServletRequest request, HttpServletResponse response, DynamicClient dynamicClient, Map<String, Object> inParameters)
void processPluginUpdate(HttpServletRequest request, HttpServletResponse response, DynamicClient dynamicClient, DynamicClient existingDynamicClient, Map<String, Object> inParameters)
```

Sample behavior:

- Validates `software_statement` JWT.
- Enforces configured issuer.
- Supports verification from either:
  - JWKS URI
  - JWKS JSON text
- Rejects invalid signatures, malformed JWTs, and software_statement on update.
- Maps standard OAuth/OIDC/DCR claims into `DynamicClient`.
- Processes supported extended metadata claims.

Configuration validation logic is strict:

- exactly one of `JWKS URL` or `JWKS` must be provided
- both set or both blank are rejected

### 16.4 Access Grant Manager

Sample class:

- `com.pingidentity.accessgrant.SampleAccessGrant`

SPI:

- `AccessGrantManager`

Sample behavior:

- In-memory grant storage keyed by GUID.
- Implements full grant lookup/update/revoke API.
- Supports criteria-based retrieval including authorization details equality.
- Refresh-token lookup hashes incoming token before comparison.

### 16.5 Client Storage Manager

Sample class:

- `com.pingidentity.clientstorage.SampleClientStorage`

SPI base:

- `ClientStorageManagerBase` (`ClientStorageManagerV2` semantics)

Sample behavior:

- In-memory client storage with CRUD.
- Implements search with query filtering on ID/name.
- Supports sort by ID/name/last-modified/creation-time.
- Supports pagination via `SearchCriteria` start index and item count.

## 17. Full Sample Inventory and Deep Notes

Sample set under `sdk/plugin-src/` (Java files, templates, language packs):

| Sample | Primary SPI(s) | Key implementation notes |
|---|---|---|
| `idp-adapter-example` | `IdpAuthenticationAdapterV2`, `AuthnApiPlugin` | IPv4 subnet auth, chained-attribute role elevation, non-interactive API descriptor (`interactive=false`) |
| `template-render-adapter-example` | `IdpAuthenticationAdapterV2`, `AuthnApiPlugin` | Unified browser/API flow, explicit state/action/error specs, model deserialization + validation pipeline |
| `external-consent-page-example` | `IdpAuthenticationAdapterV2` | OAuth external consent flow, CSRF token enforcement, authorization-details ID masking |
| `sp-adapter-example` | `SpAuthenticationAdapter` | Full admin GUI control surface: field, row, config validators; action descriptor usage |
| `authentication-selector-example` | `AuthenticationSelector`, `AuthnApiPlugin` | Domain-based source selection, persistent cookie optimization, API state for selector |
| `custom-data-store-example` | `CustomDataSourceDriver` | Descriptor with filter fields, runtime property-file lookup, connection testing pattern |
| `password-credential-validator-example` | `PasswordCredentialValidator` + optional interfaces | Auth + password change/reset + username recovery in one implementation |
| `identity-store-provisioner-example` | `IdentityStoreProvisionerWithFiltering` | SCIM-style read/list/filter/sort/page, soft delete vs hard delete behavior |
| `token-processor-example` | `TokenProcessor<BinarySecurityToken>` | Token decode to subject attribute context |
| `token-generator-example` | `TokenGenerator` | Subject attribute context to token generation |
| `notification-publisher-example` | `NotificationPublisherPlugin` | HTTP POST transport with JSON payload + status mapping |
| `ciba-auth-plugin-example` | `OOBAuthPlugin` | Full out-of-band approval lifecycle with callback-capable status changes |
| `secret-manager-example` | `SecretManager` | Secret lookup from env, plus actionable admin utilities |
| `dynamic-client-registration-example` | `DynamicClientRegistrationPlugin` | Software statement verification and claim-to-client mapping |
| `access-grant-example` | `AccessGrantManager` | End-to-end grant persistence interface sample |
| `client-storage-example` | `ClientStorageManagerBase` | Client CRUD + search/sort/page administrative support |

Notable non-code sample assets:

- Template HTML files under each sample `conf/template/` directory.
- Language bundles under `conf/language-packs/`.
- These are first-class runtime dependencies for interactive templates and localized messages.

## 18. Cross-Sample Reusable Patterns

### 18.1 Dual-path handlers (API + browser)

`TemplateRenderAdapter` and `SampleAuthenticationSelector` both use one runtime path that branches by request type:

- API request: action ID + JSON model + `AuthnApiSupport` response
- Browser request: form parameters + template rendering

This keeps behavior parity between channels.

### 18.2 Strict action matching

Good pattern used by API-capable samples:

- if an action ID is present but not valid for current state, return invalid-action error instead of silently ignoring it.

### 18.3 Descriptor-centric admin UX

`SpAuthnAdapterExample` shows full descriptor stack:

- field-level validation
- row-level cross-field validation in tables
- global configuration validator
- action links with dynamic results

### 18.4 State cleanup discipline

`SampleEmailAuthPlugin.finished()` and official adapter guidance align on one rule:

- always remove transaction-scoped state once terminal status is reached.

### 18.5 Security details demonstrated in examples

- CSRF token round-trip validation on consent pages.
- Escaping request parameters before rendering (`Utils.escapeForHtml`).
- Authorization-detail value shielding by random identifier mapping.

## 19. Hardening Checklist

Concrete hardening deltas from sample code to production:

1. Replace all in-memory state stores (`HashMap`, in-process caches) with durable shared storage.
2. Remove `System.out` logging and normalize structured logger usage.
3. Enforce timeout, retry, and circuit behavior for all network calls.
4. Validate all user-supplied inputs beyond basic sample checks.
5. Ensure cluster-safe replication behavior for password reset/change state.
6. Ensure secret manager integrations rely on secure secret backends rather than plain env vars where possible.
7. Validate all template parameters for XSS-safety before rendering.
8. Test API and browser paths for identical policy outcomes.
9. Add restart and failover tests for session and transaction state recovery semantics.
10. Ensure plugin startup/lazy-init settings are tested with your extension set.

## 20. SDK-Relevant Release Deltas

SDK-facing deltas surfaced in the vendor text:

- `IN_PARAMETER_NAME_SRI` added to SDK inputs (`pi.sri` exposure).
- `SessionManager` added to SDK for adapter-driven session revocation use cases.
- Startup toggle for plugin creation/initialization introduced (`ConfigurePluginsOnStartup`).
- `AccessGrantManagerAccessor` exposed in SDK API surface.

Operational implication:

- Plugin lifecycle timing can differ when startup plugin initialization is disabled; plugin first-use latency should be tested in environments using this setting.

## 21. Build-Deploy Quick Start

Minimal plugin workflow:

1. Create plugin project under `sdk/plugin-src/<plugin-name>/`.
2. Add Java sources under `java/` with package structure.
3. Add optional third-party dependencies under `<plugin>/lib/`.
4. Add optional runtime config assets under `<plugin>/conf/`.
5. Set `target-plugin.name=<plugin-name>` in `sdk/build.local.properties`.
6. Run:

```bash
cd binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk
ant clean-plugin
ant jar-plugin
ant deploy-plugin
```

7. Restart PingFederate.
8. Configure plugin instance in admin console.
9. Validate both happy-path and failure-path behavior in runtime flows.

## 22. Source Inventory

Primary official text:

- `docs/reference/vendor/pingfederate_server-13.0.txt`

SDK build and layout files:

- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/build.xml`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/build.properties`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/build.local.properties`

Bundled sample sources reviewed:

- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/idp-adapter-example/java/com/pingidentity/adapter/idp/SampleSubnetAdapter.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/template-render-adapter-example/java/com/pingidentity/adapter/idp/TemplateRenderAdapter.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/template-render-adapter-example/java/com/pingidentity/adapter/idp/api/StateSpec.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/template-render-adapter-example/java/com/pingidentity/adapter/idp/api/ActionSpec.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/template-render-adapter-example/java/com/pingidentity/adapter/idp/api/ErrorDetailSpec.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/template-render-adapter-example/java/com/pingidentity/adapter/idp/api/SubmitUserAttributes.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/template-render-adapter-example/java/com/pingidentity/adapter/idp/api/UserAttributesRequired.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/external-consent-page-example/java/com/pingidentity/adapter/idp/ExternalConsentPageAdapter.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/sp-adapter-example/java/com/pingidentity/adapter/sp/SpAuthnAdapterExample.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/authentication-selector-example/java/com/pingidentity/authentication/selector/SampleAuthenticationSelector.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/authentication-selector-example/java/com/pingidentity/authentication/selector/api/StateSpec.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/authentication-selector-example/java/com/pingidentity/authentication/selector/api/ActionSpec.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/authentication-selector-example/java/com/pingidentity/authentication/selector/api/SubmitEmailOrDomain.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/custom-data-store-example/java/com/pingidentity/customdatastore/SamplePropertiesDataStore.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/password-credential-validator-example/java/com/pingidentity/password/credential/validator/SamplePasswordCredentialValidator.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/notification-publisher-example/java/com/pingidentity/notification/publisher/HttpNotificationPublisher.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/token-processor-example/java/com/pingidentity/processor/SampleTokenProcessor.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/token-generator-example/java/com/pingidentity/generator/SampleTokenGenerator.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/identity-store-provisioner-example/java/com/pingidentity/identitystoreprovisioners/sample/SampleIdentityStoreProvisioner.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/ciba-auth-plugin-example/java/com/pingidentity/oob/SampleEmailAuthPlugin.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/secret-manager-example/java/com/pingidentity/secretmanager/SampleSecretManager.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/dynamic-client-registration-example/java/com/pingidentity/clientregistration/SoftwareStatementValidatorPlugin.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/access-grant-example/java/com/pingidentity/accessgrant/SampleAccessGrant.java`
- `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/client-storage-example/java/com/pingidentity/clientstorage/SampleClientStorage.java`
