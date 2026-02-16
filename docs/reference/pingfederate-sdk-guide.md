# PingFederate SDK Implementation Guide

> Single-source reference for implementing PingFederate 13.0 SDK plugins.  
> Sourced from the official SDK Javadoc, bundled sample projects, and the  
> PingFederate 13.0 documentation chapter "SDK Developer's Guide".  
> This document is optimized for LLM context — each section is self-contained.  
> **Goal:** This guide should be sufficient without consulting the Javadoc directly.  
> **Completeness:** Incorporates all content from the official PingFederate 13.0  
> SDK documentation chapter. No external documentation dependency required.

| Field | Value |
|-------|-------|
| Product | PingFederate Server |
| Version | 13.0 (13.0.1 distribution) |
| Java | JDK 11 (SDK baseline) |
| SDK root | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/` |
| Official source text | `docs/reference/vendor/pingfederate_server-13.0.txt` |
| Official SDK chapter region | approx. lines 82690–84430 |
| Sample source root | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/plugin-src/` |
| SDK Javadoc | `binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/doc/` |

---

## Topic Index

| # | Section | One-liner | Tags |
|---|---------|-----------|------|
| | **Part I — Getting Started & Foundation** | | |
| 1 | [Quick Start: Build-Deploy in 5 Minutes](#1-quick-start-build-deploy-in-5-minutes) | End-to-end plugin creation flow | `quickstart`, `tutorial`, `create`, `deploy` |
| 2 | [SDK Layout and Build Pipeline](#2-sdk-layout-and-build-pipeline) | Directory layout, Ant targets, build properties | `SDK`, `Ant`, `build`, `deploy` |
| 3 | [Core Plugin Model and Lifecycle](#3-core-plugin-model-and-lifecycle) | `ConfigurablePlugin`, descriptor contracts, lifecycle adapters | `plugin`, `configurable`, `lifecycle`, `transaction-aware` |
| 4 | [Configuration API](#4-configuration-api) | Reading admin-entered values: fields, tables, typed accessors | `Configuration`, `Field`, `Table`, `FieldList` |
| 5 | [Descriptor and Admin UI](#5-descriptor-and-admin-ui) | Building admin UI: descriptors, validators, tables, actions, metadata flags | `descriptor`, `GUI`, `FieldDescriptor`, `validation`, `metadata` |
| 6 | [Packaging, Deployment & Classloading](#6-packaging-deployment--classloading) | PF-INF markers, manual packaging, deploy path, classpath isolation | `PF-INF`, `packaging`, `deploy`, `JAR`, `classloader` |
| | **Part II — SPI Reference** | | |
| 7 | [IdP Adapter SPI](#7-idp-adapter-spi) | `IdpAuthenticationAdapterV2` full runtime contract, inParameters, AuthnPolicy | `IdP`, `adapter`, `lookupAuthN`, `inParameters`, `AuthnPolicy` |
| 8 | [SP Adapter SPI](#8-sp-adapter-spi) | `SpAuthenticationAdapter` methods and flow semantics | `SP`, `adapter`, `createAuthN`, `SsoContext` |
| 9 | [Authentication Selector SPI](#9-authentication-selector-spi) | `selectContext`/`callback` runtime behavior | `selector`, `callback`, `cookie`, `routing` |
| 10 | [Authentication API (JSON/REST)](#10-authentication-api-jsonrest) | `AuthnApiPlugin`, states/actions/models/errors, dual-path patterns | `API`, `AuthnApiPlugin`, `state`, `action`, `ParamMapping` |
| 11 | [Password Credential Validator SPI](#11-password-credential-validator-spi) | Validation, reset/change/recovery, PCV chaining | `PCV`, `password`, `reset`, `change`, `chain` |
| 12 | [Identity Store Provisioner SPI](#12-identity-store-provisioner-spi) | User/group CRUD, filtering, pagination, sort, deletion | `provisioner`, `SCIM`, `CRUD`, `identity-store` |
| 13 | [Notification Publisher SPI](#13-notification-publisher-spi) | `publishNotification` contract, event types, publisher pattern | `notification`, `publisher`, `HTTP`, `event` |
| 14 | [Custom Data Source SPI](#14-custom-data-source-spi) | `testConnection`, `retrieveValues`, JSON Pointer mapping | `data-source`, `driver`, `filter`, `JDBC`, `JSON-Pointer` |
| 15 | [STS Token SPI](#15-sts-token-spi) | `TokenProcessor` and `TokenGenerator` contracts | `STS`, `token`, `WS-Trust`, `BinarySecurityToken` |
| 16 | [CIBA / Out-of-Band Auth Plugin](#16-ciba--out-of-band-auth-plugin) | `OOBAuthPlugin` full lifecycle, CIBA integration | `CIBA`, `OOB`, `push`, `polling`, `KeyValueStateSupport` |
| 17 | [Additional Extension SPIs](#17-additional-extension-spis) | Secret Manager, DCR, Access Grant, Client Storage, Auth Details, CAPTCHA, Bearer ATM | `secret`, `DCR`, `grant`, `client-storage`, `captcha`, `authorization-details` |
| | **Part III — Cross-Cutting Concerns** | | |
| 18 | [Session State and Transaction State](#18-session-state-and-transaction-state) | `SessionStateSupport`, `TransactionalStateSupport`, key namespacing, state factory | `session`, `transaction`, `state`, `cleanup`, `namespacing` |
| 19 | [Thread Safety and Concurrency](#19-thread-safety-and-concurrency) | Init-time vs per-request state, server guarantees, HandlerRegistry, ConfigCache | `thread`, `concurrency`, `singleton`, `ConfigCache` |
| 20 | [Logging, Audit & Observability](#20-logging-audit--observability) | Logger patterns, LoggingUtil, structured LogEvent enum | `logging`, `audit`, `LoggingUtil`, `LogEvent` |
| 21 | [Template Rendering & Error Localization](#21-template-rendering--error-localization) | `TemplateRendererUtil`, HTML templates, `AuthnErrorDetail`, language packs | `template`, `HTML`, `rendering`, `error`, `i18n` |
| 22 | [Security Patterns](#22-security-patterns) | CSRF, cookies, secret references, OAuth context, reflective compat | `CSRF`, `cookies`, `secret`, `OAuth`, `reflection` |
| | **Part IV — Implementation Patterns** | | |
| 23 | [Core SDK Patterns](#23-core-sdk-patterns) | Descriptor caching, dual-path rendering, composite chaining, attribute contracts | `patterns`, `dual-path`, `chaining`, `contract`, `feedback-loop` |
| 24 | [Resilience & External Integration](#24-resilience--external-integration) | Exponential backoff, account lockout, handler registration, service-points | `resilience`, `backoff`, `locking`, `handler`, `service-point` |
| | **Part V — Reference** | | |
| 25 | [Runtime Service Accessors and Utilities](#25-runtime-service-accessors-and-utilities) | Service Accessor pattern, AccessTokenIssuer, SessionManager | `accessor`, `runtime`, `ServiceFactory`, `token`, `session` |
| 26 | [Enumerations, Constants & Data Types](#26-enumerations-constants--data-types) | AuthnAdapterResponse, AUTHN_STATUS, DynamicClient, NotificationEventType, FIPS | `enums`, `constants`, `AUTHN_STATUS`, `DynamicClient`, `FIPS` |
| 27 | [Hardening Checklist, Release Deltas & Sources](#27-hardening-checklist-release-deltas--sources) | Production checklist, version history, sample inventory, source traceability | `hardening`, `release`, `samples`, `sources` |

---

# Part I — Getting Started & Foundation

## 1. Quick Start: Build-Deploy in 5 Minutes

> **Tags:** `quickstart`, `tutorial`, `create`, `deploy`

### Prerequisites

- **JDK 11** installed and on PATH
- **Apache Ant** installed and on PATH
- PingFederate 13.0.x extracted

### Step-by-Step

1. **Create project directory:**
   ```bash
   mkdir -p <pf>/pingfederate/sdk/plugin-src/my-plugin/java/com/example/myplugin
   ```

2. **Write plugin implementation** (e.g., an IdP adapter implementing
   `IdpAuthenticationAdapterV2`)

3. **Add third-party libraries** (if any):
   ```bash
   mkdir -p <pf>/pingfederate/sdk/plugin-src/my-plugin/lib
   cp my-dependency.jar <pf>/pingfederate/sdk/plugin-src/my-plugin/lib/
   ```

4. **Configure build target:**
   ```properties
   # build.local.properties
   target-plugin.name=my-plugin
   ```

5. **Build and deploy:**
   ```bash
   cd <pf>/pingfederate/sdk
   ant clean-plugin
   ant jar-plugin
   ant deploy-plugin
   ```

6. **Restart PingFederate** — plugin appears in admin console.

### Output Artifacts

| Artifact | Location |
|----------|----------|
| Compiled classes | `plugin-src/my-plugin/build/classes/` |
| Plugin JAR | `plugin-src/my-plugin/build/jar/pf.plugins.my-plugin.jar` |
| Deployed JAR | `server/default/deploy/pf.plugins.my-plugin.jar` |
| Deployed deps | `server/default/deploy/*.jar` (from `lib/`) |

---

## 2. SDK Layout and Build Pipeline

> **Tags:** `SDK`, `Ant`, `build`, `deploy`, `JDK`

SDK root:
`binaries/pingfederate/dist/pingfederate-13.0.1/pingfederate/sdk/`

### Directory Structure

```
sdk/
├── plugin-src/           # Custom plugin projects + all bundled examples
├── doc/                  # SDK Javadoc (open index.html)
├── lib/                  # SDK compilation support libraries
│   ├── servlet-api.jar
│   └── tasks.jar         # BuildSdkDeploymentDesc Ant task
├── build.xml             # Canonical Ant build/deploy workflow
├── build.properties      # Shared immutable defaults (DO NOT MODIFY)
└── build.local.properties # Local overrides (target-plugin.name, pingfederate.home)
```

> ⚠️ **JDK baseline:** The PingFederate SDK only supports **JDK 11**. Unlike
> PingAccess (which supports 17/21), PF is strictly JDK 11.

### Build Properties (from shipped files)

```properties
# build.properties (DO NOT MODIFY — use build.local.properties for overrides)
plugin-src.dir=plugin-src
target-plugin.dir=${plugin-src.dir}/${target-plugin.name}
target-plugin.build.dir=${target-plugin.dir}/build
# Plugin JAR naming
# Output: pf.plugins.${target-plugin.name}.jar
# Deploy: ${pingfederate.home}/server/default/deploy
# Conf:   ${pingfederate.home}/server/default/conf
```

### Ant Targets

| Target | Description |
|--------|-------------|
| `clean-plugin` | Remove `${target-plugin.build.dir}` |
| `compile-plugin` | Compile `java/` sources to `build/classes` |
| `jar-plugin` | Generate deployment descriptors + package plugin JAR |
| `deploy-plugin` | Copy plugin JAR plus `lib/*.jar` to deploy dir and copy optional `conf/` |

> **Recommended order:** `clean-plugin` → `jar-plugin` → `deploy-plugin`

### What `jar-plugin` adds beyond raw compilation

- Runs SDK Ant task `BuildSdkDeploymentDesc`
- Produces plugin discovery metadata in `PF-INF/` so PingFederate can find plugin classes
- The compiled class files and deployment descriptor are placed in
  `sdk/plugin-src/<subproject>/build/classes`
- The output JAR (`pf.plugins.<subproject>.jar`) is placed in
  `sdk/plugin-src/<subproject>/build/jar`

> ⚠️ **Always use `build.xml`** for packaging. Manual builds must replicate the
> `PF-INF` marker generation or PingFederate will not discover your plugin.

---

## 3. Core Plugin Model and Lifecycle

> **Tags:** `plugin`, `configurable`, `describable`, `lifecycle`, `ConfigurablePlugin`, `DescribablePlugin`, `TransactionAware`, `SessionAware`

Official model distinguishes two core behaviors:

1. **Configurable** plugin behavior
2. **Describable** plugin behavior

### 3.1 ConfigurablePlugin Interface

```java
// com.pingidentity.sdk.ConfigurablePlugin
// All Known Sub-interfaces:
//   IdpAuthenticationAdapterV2, SpAuthenticationAdapter, AuthenticationSelector,
//   CustomDataSourceDriver, PasswordCredentialValidator, TokenProcessor<T>,
//   TokenGenerator, NotificationPublisherPlugin, OOBAuthPlugin, SecretManager,
//   DynamicClientRegistrationPlugin, CaptchaProvider, IdentityStoreProvisioner, ...

void configure(Configuration configuration);
```

**Runtime contract (from Javadoc):**

- PingFederate calls `configure()` with admin-saved `Configuration` object
- Plugin should cache runtime values extracted from `Configuration`
- **Thread safety guaranteed:** The server does NOT allow access to your plugin
  instance until after creation and configuration is completed
- All concurrency issues are handled by the server — no synchronization needed
  in `configure()`
- Each time the server creates a new instance of your plugin, this method is
  invoked with the proper configuration

> ⚠️ **Configuration is a snapshot.** The `Configuration` object passed to
> `configure()` reflects the admin-saved state. If the admin changes settings,
> PingFederate creates a **new plugin instance** and calls `configure()` on it —
> the old instance is not updated.

### 3.2 DescribablePlugin Interface

```java
// com.pingidentity.sdk.DescribablePlugin
// extends ConfigurablePlugin

PluginDescriptor getPluginDescriptor();
```

Most plugin types provide descriptor metadata used to render admin UI.

**Descriptor methods by family:**

| Plugin Family | Descriptor Method | Return Type |
|---------------|-------------------|-------------|
| Generic plugins | `getPluginDescriptor()` | `PluginDescriptor` |
| Adapter plugins (IdP/SP) | `getAdapterDescriptor()` | `AuthnAdapterDescriptor` |
| Custom data sources | `getSourceDescriptor()` | `SourceDescriptor` |

> ⚠️ **Special cases:** Adapter plugins and custom data source plugins are
> describable even though they do **not** directly implement `DescribablePlugin`.
> They still return a descriptor (via `getAdapterDescriptor()` /
> `getSourceDescriptor()`) and are still describable.

### 3.3 PluginDescriptor

```java
// com.pingidentity.sdk.PluginDescriptor
// Direct subclasses: AuthnAdapterDescriptor, AuthenticationSelectorDescriptor,
//   SourceDescriptor, TokenPluginDescriptor, NotificationSenderPluginDescriptor,
//   SecretManagerDescriptor, IdentityStoreProvisionerDescriptor, ...

// Constructors
PluginDescriptor(String type, ConfigurablePlugin plugin, GuiConfigDescriptor guiDesc);
PluginDescriptor(String type, ConfigurablePlugin plugin, GuiConfigDescriptor guiDesc, String version);
PluginDescriptor(String type, ConfigurablePlugin plugin, GuiConfigDescriptorBuilder builder);
PluginDescriptor(String type, ConfigurablePlugin plugin, GuiConfigDescriptorBuilder builder, String version);

// Key methods
String getType();                          // plugin type name (shown in admin UI dropdowns)
String getPluginClassName();               // FQCN of the implementing plugin
GuiConfigDescriptor getGuiConfigDescriptor();        // @Deprecated since 6.11
GuiConfigDescriptorBuilder getGuiConfigDescriptorBuilder(); // preferred since 6.11
Set<String> getAttributeContractSet();     // attribute contract
boolean isSupportsExtendedContract();      // allows additional attributes beyond contract
void setSupportsExtendedContract(boolean); // setter for extended contract
String getVersion();                       // plugin version string
Map<String, Object> getMetadata();         // additional metadata (since 10.2)
void setMetadata(Map<String, Object>);     // set metadata (keys from PluginMetadataKeys)
```

**Descriptor stability rule:**

- Return the **same descriptor object instance** on each call to
  `getPluginDescriptor()` / `getAdapterDescriptor()`. Cache it in a field.

### 3.4 TransactionAwareAuthenticationAdapter

IdP adapters can implement this interface to receive lifecycle callbacks
after authentication completes or fails.

```java
// com.pingidentity.sdk.TransactionAwareAuthenticationAdapter

// Constants — keys available in the 'parameters' map
String PARAMETER_NAME_TRACKING_ID        = "com.pingidentity.adapter.input.parameter.tracking.id";
String PARAMETER_NAME_REQUEST_ID         = "com.pingidentity.adapter.input.parameter.request.id";
String PARAMETER_NAME_TRANSACTION_ID     = "com.pingidentity.adapter.input.parameter.transaction.id";
String PARAMETER_NAME_PARTNER_ENTITYID   = "com.pingidentity.adapter.input.parameter.partner.entityid";
String PARAMETER_NAME_OAUTH_CLIENT_ID    = "com.pingidentity.adapter.input.parameter.oauth.client.id";
String PARAMETER_NAME_SP_ADAPTER_ID      = "com.pingidentity.adapter.input.parameter.sp.adapter.id";
String PARAMETER_NAME_CHAINED_ATTRIBUTES = "com.pingidentity.adapter.input.parameter.chained.attributes";

// Whether to defer authentication session registration
boolean isDeferAuthenticationSessionRegistration();

// Called on successful authentication completion
void onTransactionComplete(HttpServletRequest req, HttpServletResponse resp,
    Map<String, Object> authnIdentifiersMap,
    AttributeMap policyResultMap,
    Map<String, Object> parameters);   // all above constants available here

// Called on authentication failure
void onTransactionFailure(HttpServletRequest req, HttpServletResponse resp,
    Map<String, Object> authnIdentifiersMap,
    Map<String, Object> parameters);
```

> ℹ️ **Chained attributes:** The `CHAINED_ATTRIBUTES` parameter contains attributes
> from upstream adapters in a chained authentication policy. This enables
> downstream adapters to react to upstream results.

### 3.5 SessionAwareAuthenticationAdapter

Allows adapters to participate in SSO session reuse decisions.

```java
// com.pingidentity.sdk.SessionAwareAuthenticationAdapter

// Return true to reuse an existing authentication session instead of re-authenticating
boolean checkUseAuthenticationSession(HttpServletRequest req,
    HttpServletResponse resp,
    Map<String, Object> parameters,
    AuthenticationSession existingSession);
```

**`AuthenticationSession` fields:**

```java
// com.pingidentity.sdk.AuthenticationSession
AuthenticationSourceKey getAuthnSourceKey();
Map<String, Object>     getAttributeMap();      // immutable
long                    getCreationTimeMillis();
```

### 3.6 PostRegistrationSessionAwareAdapter

For adapters that need to perform post-registration authentication.

```java
// com.pingidentity.sdk.PostRegistrationSessionAwareAdapter

Map<String, Object> authenticateUser(String userId, String adapterId,
    Map<String, Object> parameters);
```

---

## 4. Configuration API

> **Tags:** `Configuration`, `Field`, `Table`, `FieldList`, `Row`, `typed-accessors`

The `Configuration` class is the runtime container for admin-entered values.
It extends `FieldList` and adds table support. This is the object passed to
`configure(Configuration)`.

### Class Hierarchy

```
FieldList (abstract)
├── Configuration    — top-level config container (fields + tables + advanced fields)
├── Row              — a single row in a table
└── SimpleFieldList  — used for custom data source filter fields
```

### FieldList (base class — inherited by Configuration, Row, SimpleFieldList)

```java
// org.sourceid.saml20.adapter.conf.FieldList

// Typed field accessors
String  getFieldValue(String name);              // raw string value
int     getIntFieldValue(String name);           // parse as int
double  getDoubleFieldValue(String name);        // parse as double
boolean getBooleanFieldValue(String name);       // parse as boolean
boolean getBooleanFieldValue(String name, boolean defaultValue);
byte[]  getFileFieldValueAsByteArray(String name); // uploaded file content
String  getFileFiledValueAsString(String name);    // uploaded file as string (note: typo in SDK)
Field   getField(String name);                   // get raw Field object
boolean isEmpty();                               // whether fieldsMap is empty
void    addField(Field field);                   // add a Field
```

> ⚠️ **SDK typo:** The method `getFileFiledValueAsString()` has a typo in the
> official SDK (`Filed` instead of `Field`). This is the actual method name you
> must call.

### Configuration (extends FieldList)

```java
// org.sourceid.saml20.adapter.conf.Configuration

// Table access
Table       getTable(String tableName);    // get table by name
List<Table> getTables();                   // all tables

// Advanced fields
FieldList   getAdvancedFields();           // 'advanced' configuration section

// Instance metadata
String      getId();                       // plugin instance ID
void        setId(String id);

// Attribute contract
Set<String> getAdditionalAttrNames();      // additional attributes configured in GUI
Set<String> getMaskedAttrNames();          // attributes marked as masked in logs
Set<String> getMultiValuedAttributes();    // attributes with array values
```

### Field

```java
// org.sourceid.saml20.adapter.conf.Field

String  getName();                  // field name
String  getValue();                 // raw string value
boolean getValueAsBoolean();        // parsed as boolean
int     getValueAsInt();            // parsed as int
double  getValueAsDouble();         // parsed as double
byte[]  getFileValueAsByteArray();  // file upload content
String  getFileValueAsString();     // file upload as string
```

### Table and Row

```java
// org.sourceid.saml20.adapter.conf.Table
String    getName();
List<Row> getRows();         // all rows in the table

// org.sourceid.saml20.adapter.conf.Row (extends FieldList)
// Inherits all FieldList methods — each row is a FieldList
```

### Usage Pattern (from `SpAuthnAdapterExample`)

```java
@Override
public void configure(Configuration configuration) {
    // Scalar field
    showHeaderAndFooter = configuration.getBooleanFieldValue("Checkbox");

    // File upload field
    byte[] fileBytes = configuration.getFileFieldValueAsByteArray("Users properties file");
    users = new Properties();
    users.load(new ByteArrayInputStream(fileBytes));

    // Table iteration
    Table table = configuration.getTable("A Table of Values");
    for (Row row : table.getRows()) {
        int colOne = row.getIntFieldValue("Column One");
        int colTwo = row.getIntFieldValue("Column Two");
        boolean checked = row.getBooleanFieldValue("Checkbox column");
    }
}
```

---

## 5. Descriptor and Admin UI

> **Tags:** `descriptor`, `GUI`, `FieldDescriptor`, `validation`, `ActionDescriptor`, `TableDescriptor`, `metadata`

The descriptor API tells PingFederate how to render the admin configuration
screen for your plugin. The primary container is `GuiConfigDescriptor`
(or its adapter subclass `AdapterConfigurationGuiDescriptor`).

### 5.1 GuiConfigDescriptor

```java
// com.pingidentity.sdk.GuiConfigDescriptor
// Direct subclass: AdapterConfigurationGuiDescriptor

// Construction
GuiConfigDescriptor();
GuiConfigDescriptor(String description);    // description shown at top of config page

// Adding UI elements
void addField(FieldDescriptor fieldDescriptor);          // standard field
void addAdvancedField(FieldDescriptor fieldDescriptor);  // 'advanced' section
void addTable(TableDescriptor table);                    // table of rows
void addAction(ActionDescriptor action);                 // clickable action link
void addSummaryDescriptor(ReadOnlyDescriptor readOnly);  // read-only display

// Validation
void addValidator(ConfigurationValidator validator);      // whole-config validation
void addListener(ConfigurationListener listener);         // config change events
void addPreRenderCallback(PreRenderCallback callback);    // pre-render hooks

// Accessors
List<FieldDescriptor> getFields();
List<FieldDescriptor> getAdvancedFields();
List<TableDescriptor> getTables();
List<ActionDescriptor> getActions();
```

### 5.2 FieldDescriptor Hierarchy

```
FieldDescriptor (abstract)
├── AbstractTextFieldDescriptor (abstract)
│   ├── TextFieldDescriptor        — text input (optional encryption)
│   │   └── SecretReferenceFieldDescriptor — secret reference input
│   └── HashedTextFieldDescriptor  — password/hashed field
├── AbstractSelectionFieldDescriptor (abstract)
│   ├── RadioGroupFieldDescriptor  — radio button group
│   └── SelectFieldDescriptor      — dropdown select
├── FilterableSelectionFieldDescriptor (abstract)
│   ├── AuthnSourceSelectionFieldDescriptor — auth source picker
│   ├── ConnectionSelectionFieldDescriptor  — connection picker
│   ├── OAuthClientSelectionFieldDescriptor — OAuth client picker
│   ├── OAuthScopeSelectionFieldDescriptor  — scope picker
│   └── PingOneEnvironmentFieldDescriptor   — PingOne env picker
├── CheckBoxFieldDescriptor         — checkbox
├── TextAreaFieldDescriptor         — multi-line text area
├── UploadFileFieldDescriptor       — file upload
├── LdapDatastoreFieldDescriptor    — LDAP datastore dropdown
├── JdbcDatastoreFieldDescriptor    — JDBC datastore dropdown
├── CustomSourceFieldDescriptor     — custom source dropdown
├── DsigKeypairFieldDescriptor      — signing keypair dropdown
├── EncryptionCertificateFieldDescriptor — encryption cert dropdown
├── PasswordCredentialValidatorFieldDescriptor — PCV dropdown
├── TrustedCAFieldDescriptor        — trusted CA dropdown
├── NotificationSenderFieldDescriptor — publisher dropdown
├── PolicyContractFieldDescriptor   — policy contract picker
├── LdapAuthenticationErrorFieldDescriptor — LDAP error code dropdown
├── LinkDescriptor                  — clickable link (read-only)
└── ReadOnlyDescriptor (abstract)   — read-only display
    └── ExtendedPropertiesFileDescriptor
```

### 5.3 FieldDescriptor Base API

```java
// org.sourceid.saml20.adapter.gui.FieldDescriptor (abstract)

String  getName();                          // unique name per table/config page
String  getDescription();                   // description tooltip
String  getLabel();                         // display label (overrides name if set)
void    setLabel(String label);             // rename field without breaking existing config
String  getDefaultValue();                  // default value for new instances
void    setDefaultValue(String value);
String  getDefaultForLegacyConfig();        // default for pre-existing instances
void    setDefaultForLegacyConfig(String);  // backward compat for newly added fields
boolean isHidden();                         // hidden in UI
void    setHidden(boolean hidden);
void    addValidator(FieldValidator validator);
void    addValidator(FieldValidator validator, boolean skipIfEmptyValue);
List<FieldDescriptor.FieldValidationWrapper> getValidationChain();
```

### 5.4 Common Field Constructors

```java
// TextFieldDescriptor
TextFieldDescriptor(String name, String description);
TextFieldDescriptor(String name, String description, boolean encrypted);

// CheckBoxFieldDescriptor
CheckBoxFieldDescriptor(String name, String description);
void setDefaultValue(boolean checked);

// RadioGroupFieldDescriptor
RadioGroupFieldDescriptor(String name, String description, String[] options);

// SelectFieldDescriptor
SelectFieldDescriptor(String name, String description,
    List<AbstractSelectionFieldDescriptor.OptionValue> options);
// OptionValue: new OptionValue(String displayName, String value)
static final OptionValue SELECT_ONE;  // placeholder "Select One" option

// TextAreaFieldDescriptor
TextAreaFieldDescriptor(String name, String description, int rows, int columns);

// UploadFileFieldDescriptor
UploadFileFieldDescriptor(String name, String description);

// HashedTextFieldDescriptor (password field)
HashedTextFieldDescriptor(String name, String description);
```

### 5.5 TableDescriptor

```java
// org.sourceid.saml20.adapter.gui.TableDescriptor

TableDescriptor(String name, String description);
void addRowField(FieldDescriptor field);     // add column to table
void addValidator(RowValidator validator);   // row-level validation
```

### 5.6 ActionDescriptor

```java
// org.sourceid.saml20.adapter.gui.ActionDescriptor

// Standard action — link shown on admin UI
ActionDescriptor(String name, String description, ActionDescriptor.Action action);

// Download action — clicking triggers a file download instead of a page refresh
ActionDescriptor(String name, String description,
    String downloadContentType, String downloadFilename,
    ActionDescriptor.Action action);

// Add parameter fields — shown as inputs before the action fires
void addParameter(FieldDescriptor fieldDescriptor);
List<FieldDescriptor> getParameters();

// ActionDescriptor.Action (inner interface)
String actionInvoked(Configuration configuration);  // return string displayed to admin

// Overload with action parameters (default method)
default String actionInvoked(Configuration configuration,
    SimpleFieldList actionParameters);   // called when parameters are defined
```

> ⚠️ **Parameters vs Configuration:** `actionInvoked(Configuration, SimpleFieldList)`
> receives both the current plugin configuration *and* the values entered
> into the action's parameter fields. Use `addParameter()` when you need
> admin input before executing the action (e.g., entering a test username).

**ActionDescriptor with Typed Parameters** (from `secret-manager-example`, `ciba-auth-plugin-example`):

Admin UI actions can accept user input via typed parameter fields:

```java
ActionDescriptor action = new ActionDescriptor(
    "Generate Secret Reference", "Generates a reference...",
    new ActionHandler());
TextFieldDescriptor param = new TextFieldDescriptor(
    "Variable Name", "Enter the name...");
param.addValidator(new RequiredFieldValidator());
action.addParameter(param);  // User fills this in before invoking action
guiDescriptor.addAction(action);

// Handler receives the typed parameter list
@Override
public String actionInvoked(Configuration config,
    SimpleFieldList actionParameters) {
    String value = actionParameters.getFieldValue("Variable Name");
    return "Result: " + value;
}
```

### 5.7 Validation Interfaces

```java
// Field-level
// org.sourceid.saml20.adapter.gui.validation.FieldValidator
void validate(Field field) throws ValidationException;

// Row-level (cross-field in a table row)
// org.sourceid.saml20.adapter.gui.validation.RowValidator
void validate(FieldList fieldsInRow) throws ValidationException;

// Config-level (cross-field/table global)
// org.sourceid.saml20.adapter.gui.validation.ConfigurationValidator
void validate(Configuration configuration) throws ValidationException;
```

### 5.8 Built-in FieldValidator Implementations

The SDK ships with ready-to-use `FieldValidator` implementations in
`org.sourceid.saml20.adapter.gui.validation.impl`:

| Validator | Purpose |
|-----------|---------|
| `RequiredFieldValidator` | Rejects blank/null values |
| `IntegerValidator` | Validates integer strings |
| `IntegerValidator(int min, int max)` | Bounded integer |
| `LongValidator` | Validates long integer strings |
| `DoubleValidator` | Validates double-precision floating point |
| `FloatValidator` | Validates single-precision floating point |
| `StringLengthValidator` | Enforces min/max string length |
| `RegExValidator` | Validates against a regular expression |
| `EmailValidator` | Validates email address format |
| `HostnameValidator` | Validates hostname format |
| `URLValidator` | Validates URL format (any scheme) |
| `HttpURLValidator` | Validates HTTP URL format |
| `HttpsURLValidator` | Validates HTTPS URL format |
| `JwksValidator` | Validates JWKS JSON structure |
| `SecretReferenceFieldValidator` | Validates `OBF:MGR:` secret reference format |
| `PingOneEnvironmentValidator` | Validates PingOne environment reference |
| `TableColumnValuesUniqueValidator`† | `ConfigurationValidator` — enforces uniqueness across table rows |

†`TableColumnValuesUniqueValidator` implements `ConfigurationValidator`, not `FieldValidator`.

```java
// Usage in descriptor construction:
FieldDescriptor connectionUrl = new TextFieldDescriptor(
    "Connection URL", "Target server URL");
connectionUrl.addValidator(new RequiredFieldValidator());
connectionUrl.addValidator(new HttpsURLValidator());
```

### 5.9 ConfigurationValidator Pattern

Plugins can implement thorough configuration validation using both
`FieldValidator` (per-field) and `ConfigurationValidator` (cross-field):

```java
// Inline anonymous FieldValidator for CIDR notation
networkCIDR.addValidator(new FieldValidator() {
    public void validate(Field field) throws ValidationException {
        try {
            new CIDRUtils(field.getValue());
        } catch (IllegalArgumentException | UnknownHostException ex) {
            throw new ValidationException(
                "Invalid network range. Please verify CIDR notation.");
        }
    }
});

// Cross-field ConfigurationValidator for table uniqueness
guiConfigDescriptor.addValidator(new ConfigurationValidator() {
    public void validate(Configuration configuration) throws ValidationException {
        List<String> errors = new ArrayList<>();

        // Check at least one row exists
        Table table = configuration.getTable("Authentication Sources");
        if (table.getRows().isEmpty()) {
            throw new ValidationException("Please add at least one source.");
        }

        // Check for duplicate values
        Set<String> seen = new HashSet<>();
        for (Row row : table.getRows()) {
            String value = row.getFieldValue("Network Range (CIDR notation)");
            if (!seen.add(value)) {
                errors.add("Duplicate network: " + value);
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);  // multi-error constructor
        }
    }
});
```

> 💡 **Best practice:** `ValidationException` accepts a `List<String>` in its
> constructor, allowing multiple errors to be reported at once rather than
> failing on the first error.

### 5.10 Descriptor Metadata Flags

Plugins can set metadata flags on their descriptors to control PingFederate's
runtime behavior:

```java
// Adapter descriptor metadata
Map<String, Boolean> metadata = new HashMap<>();
metadata.put("EarlyCreateAndConfigure", true);   // created at startup, not lazily
metadata.put("FipsStatus", PluginFipsStatus.COMPLIANT);  // FIPS 140-2 compliance flag
metadata.put("TryLookupAuthn", true);            // enables try-lookup phase
result.setMetadata(metadata);

// Non-interactive selector
authnSelectorDescriptor.setSupportsExtendedResults(false);
authnSelectorDescriptor.setMetadata(
    Collections.singletonMap("FipsStatus", PluginFipsStatus.COMPLIANT));
```

**Key metadata keys (from `PluginMetadataKeys`):**

| Key | Effect |
|-----|--------|
| `EarlyCreateAndConfigure` | Plugin is instantiated at PF startup instead of lazily |
| `FipsStatus` | Declares FIPS compliance level (`COMPLIANT`, `NOT_COMPLIANT`, `NOT_APPLICABLE`) |
| `TryLookupAuthn` | Enables `inParameters` to include `com.pingidentity.adapter.input.parameter.try.lookup.authn` |

### 5.11 Complete Descriptor Example (from `SpAuthnAdapterExample`)

```java
private AuthnAdapterDescriptor initDescriptor() {
    AdapterConfigurationGuiDescriptor gui =
        new AdapterConfigurationGuiDescriptor("Main description for admin page");

    // File upload with custom validation
    UploadFileFieldDescriptor fileField =
        new UploadFileFieldDescriptor("Users properties file", "A properties file.");
    fileField.addValidator(new RequiredFieldValidator());
    fileField.addValidator(field -> {
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(field.getFileValueAsByteArray()));
        if (p.isEmpty()) throw new ValidationException("File must contain data");
    });
    gui.addField(fileField);

    // Text with default
    TextFieldDescriptor text = new TextFieldDescriptor("Text", "Description");
    text.addValidator(new RequiredFieldValidator());
    text.setDefaultValue("default value");
    gui.addField(text);

    // Bounded number (skip validation if empty)
    TextFieldDescriptor number = new TextFieldDescriptor("Number", "2 to 47");
    number.addValidator(new IntegerValidator(2, 47), true);
    gui.addField(number);

    // Checkbox with default checked
    CheckBoxFieldDescriptor checkbox = new CheckBoxFieldDescriptor("Checkbox", "Desc");
    checkbox.setDefaultValue(true);
    gui.addField(checkbox);

    // Radio group
    gui.addField(new RadioGroupFieldDescriptor("Radio", "", new String[]{"One","Two","Three"}));

    // Select dropdown with display/value separation
    List<AbstractSelectionFieldDescriptor.OptionValue> opts = new ArrayList<>();
    opts.add(SelectFieldDescriptor.SELECT_ONE);
    opts.add(new AbstractSelectionFieldDescriptor.OptionValue("First", "1"));
    opts.add(new AbstractSelectionFieldDescriptor.OptionValue("Second", "2"));
    gui.addField(new SelectFieldDescriptor("Select", "", opts));

    // Encrypted password field
    gui.addField(new TextFieldDescriptor("Password", "Encrypted field", true));

    // Advanced text area (hidden by default)
    gui.addAdvancedField(new TextAreaFieldDescriptor("Text Area", "Advanced", 3, 50));

    // Table with row validation
    TableDescriptor table = new TableDescriptor("Values", "Table description");
    TextFieldDescriptor col1 = new TextFieldDescriptor("Col One", "Desc");
    col1.addValidator(new RequiredFieldValidator());
    col1.addValidator(new IntegerValidator());
    table.addRowField(col1);
    TextFieldDescriptor col2 = new TextFieldDescriptor("Col Two", "Desc");
    col2.addValidator(new RequiredFieldValidator());
    col2.addValidator(new IntegerValidator(0, 200));
    table.addRowField(col2);
    table.addRowField(new CheckBoxFieldDescriptor("Check", ""));
    table.addValidator(row -> {
        if (row.getBooleanFieldValue("Check")) {
            if (row.getIntFieldValue("Col One") + row.getIntFieldValue("Col Two") > 100)
                throw new ValidationException("Sum cannot exceed 100 when checked");
        }
    });
    gui.addTable(table);

    // Action link
    gui.addAction(new ActionDescriptor("Show File", "Display content",
        config -> config.getFileFiledValueAsString("Users properties file")));

    // Global config validator
    gui.addValidator(config -> {
        if (!config.getBooleanFieldValue("Checkbox")) {
            if (config.getTable("Values").getRows().size() < 2)
                throw new ValidationException("Need ≥2 rows when checkbox unchecked");
        }
    });

    // Build descriptor with attribute contract
    Set<String> contract = new HashSet<>();
    contract.add("Attribute One");
    contract.add("Attribute Two");
    return new AuthnAdapterDescriptor(this, "My Adapter", contract, true, gui);
}
```

---

## 6. Packaging, Deployment & Classloading

> **Tags:** `PF-INF`, `packaging`, `deploy`, `JAR`, `classloading`, `isolation`

### 6.1 Ant-based Packaging (recommended)

The Ant build path automatically creates required discovery metadata and JAR layout.

**High-level flow:**

1. Set `target-plugin.name` in `build.local.properties`.
2. Place third-party JARs in `plugin-src/<plugin>/lib` if needed.
3. Run `ant deploy-plugin` or `clean-plugin` → `jar-plugin` → `deploy-plugin`.
4. Restart PingFederate.

> ⚠️ **Multi-project limitation:** You can develop source code for multiple
> projects simultaneously, but you can build and deploy **only one at a time**.
> Change `target-plugin.name` as needed.

### 6.2 Manual Packaging Rules

Manual builds must produce proper `PF-INF` markers at JAR root.

**Required classpath roots for manual compile:**

- `<pf_install>/pingfederate/server/default/lib`
- `<pf_install>/pingfederate/lib`
- `<pf_install>/pingfederate/sdk/lib`
- `<pf_install>/pingfederate/sdk/plugin-src/<subproject>/lib`

### 6.3 PF-INF Marker Mapping

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
| CIBA/OOB Auth Plugin | `oob-auth-plugins` |
| Notification Publisher | `notification-sender` |

**Each marker file format:**

- One fully-qualified implementation class per line
- Class must implement the corresponding SPI

### 6.4 Deployment Directory

All plugin JARs deploy to:
```
<pf_install>/pingfederate/server/default/deploy/
```

- PingFederate scans the deploy directory at startup
- Discovers plugins via `PF-INF/` marker files inside JARs
- Each marker file lists FQCN of plugin implementations
- **Restart required** after deploying new/updated plugins

### 6.5 Third-Party Dependencies

- Place dependency JARs in your plugin project's `lib/` subdirectory
- The `deploy-plugin` Ant target copies them to the deploy directory alongside
  your plugin JAR
- Dependencies are on the server classpath — **no plugin-level classloader
  isolation** like OSGi

> ⚠️ **Dependency conflicts:** Since all plugins share the server classpath,
> conflicting library versions between plugins may cause runtime errors. Use
> shading/relocation for third-party libraries that conflict with PingFederate's
> own dependencies.

### 6.6 Configuration Assets

Optional runtime assets (templates, language packs, static resources) deploy to:
```
<pf_install>/pingfederate/server/default/conf/
```

**Manual deployment destination:**

- Copy plugin JAR and dependent third-party JARs to
  `<pf_install>/pingfederate/server/default/deploy`
- Copy optional runtime config assets (templates, language packs) to
  `<pf_install>/pingfederate/server/default/conf`

---

# Part II — SPI Reference

## 7. IdP Adapter SPI

> **Tags:** `IdP`, `adapter`, `lookupAuthN`, `logout`, `AuthnAdapterResponse`, `inParameters`, `AuthnPolicy`

### 7.1 Primary SPI

```java
// com.pingidentity.sdk.IdpAuthenticationAdapterV2
//   extends IdpAuthenticationAdapter
//   extends ConfigurableAuthnAdapter  (-> ConfigurablePlugin + getAdapterDescriptor)
```

### 7.2 Core Methods

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

### 7.3 `lookupAuthN` Execution Semantics

**Official behavior (from Javadoc + vendor docs):**

- Called for IdP-initiated SSO, SP-initiated SSO, OAuth transactions, and
  direct IdP-to-SP processing
- Also used when adapter is in password reset/change policy
- **Async pattern:** If response is committed (`resp.isCommitted()`),
  PingFederate pauses the transaction and re-invokes the adapter at `resumePath`.
  Return value is ignored for committed responses.
- For a non-committed return:
  - `authnStatus` must be set on the `AuthnAdapterResponse`
  - On `SUCCESS`, `attributeMap` must contain user attributes matching the
    attribute contract

### 7.4 AuthnAdapterResponse and AUTHN_STATUS

```java
// com.pingidentity.sdk.AuthnAdapterResponse

Map<String, Object> getAttributeMap();
AUTHN_STATUS        getAuthnStatus();
int                 getErrorCode();
String              getUsername();
String              getErrorMessage();
```

**AUTHN_STATUS enum — complete semantics:**

| Status | Meaning | HTTP Behavior |
|--------|---------|---------------|
| `SUCCESS` | Authentication complete — attributes populated | PF continues policy chain |
| `IN_PROGRESS` | Adapter rendered a form or redirect — request not yet complete | PF waits for callback |
| `FAILURE` | Authentication definitively failed | PF triggers failure handling |
| `ACTION` | Adapter requests a specific action (e.g., password change) | PF routes to action flow |
| `INTERACTION_REQUIRED` | Non-interactive lookup attempted but user interaction needed | PF falls through to next adapter |

**Error fields** — only meaningful when status is `FAILURE`:

```java
AuthnAdapterResponse response = new AuthnAdapterResponse();
response.setAuthnStatus(AUTHN_STATUS.FAILURE);
response.setErrorCode(1001);  // Custom error code for audit
response.setErrorMessage("Account locked after 5 failed attempts");
// errorCode appears in audit logs for operational alerting
```

### 7.5 `INTERACTION_REQUIRED` Pattern

Adapters that support try-lookup can use `INTERACTION_REQUIRED` to signal that
silent authentication is not possible and user interaction is needed:

```java
// When try-lookup-authn is true AND re-authentication is required,
// return INTERACTION_REQUIRED to signal PF to proceed with the full flow
if (Boolean.TRUE.equals(inParameters.get(
        "com.pingidentity.adapter.input.parameter.try.lookup.authn"))
    && authnPolicy.allowUserInteraction()) {

    if (authnPolicy.reauthenticate()) {
        authnAdapterResponse.setAuthnStatus(
            AuthnAdapterResponse.AUTHN_STATUS.INTERACTION_REQUIRED);
        return authnAdapterResponse;
    }

    // If not re-auth but try-lookup, disable user interaction for initial check
    authnPolicy = new AuthnPolicy(false, authnPolicy.reauthenticate(),
        authnPolicy.getRequestAuthnContexts());
    inParameters.put("com.pingidentity.adapter.input.parameter.authn.policy",
        authnPolicy);
}
```

### 7.6 AuthnPolicy Introspection

Adapters can inspect `AuthnPolicy` from `inParameters` to adjust behavior:

```java
AuthnPolicy authnPolicy = (AuthnPolicy) inParameters.get(
    "com.pingidentity.adapter.input.parameter.authn.policy");

// Key methods:
authnPolicy.allowUserInteraction();    // false = API-only or silent check
authnPolicy.reauthenticate();          // true = force re-authentication
authnPolicy.registrationRequested();   // true = prompt=create from OIDC

// Detecting OIDC prompt=create for registration redirect:
if (authnPolicy != null && authnPolicy.allowUserInteraction()
    && authnPolicy.registrationRequested()) {
    return "identity.registration";      // redirect to registration flow
}

// Inspect requested authentication context classes
List<String> requestedContexts = authnPolicy.getRequestAuthnContexts();
// e.g. "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport"
// Use AuthnContextClassRef constants for comparison
```

> 💡 **Tip:** The `AuthnContextClassRef` class provides all standard SAML 2.0
> authentication context URIs as constants (e.g., `PASSWORD_PROTECTED_TRANSPORT`,
> `KERBEROS`, `X509`, `SMARTCARD_PKI`, `TLS_CLIENT`, `TIME_SYNC_TOKEN`).

### 7.7 Common `inParameters` Keys

These `inParameters` keys are available at runtime in `lookupAuthN()`.
Use the SDK constants from `IdpAuthenticationAdapterV2` and `TransactionAwareAuthenticationAdapter`
instead of hardcoding raw strings:

| Constant | Key String | Type | Notes |
|----------|-----------|------|-------|
| `IN_PARAMETER_NAME_AUTHN_POLICY` | `...authn.policy` | `AuthnPolicy` | Always present — controls interaction, re-auth, registration |
| `IN_PARAMETER_NAME_RESUME_PATH` | `...resume.path` | `String` | Resume URL for `TransactionalStateSupport` |
| `IN_PARAMETER_NAME_INSTANCE_ID` | `...instanceid` | `String` | Adapter instance ID |
| `IN_PARAMETER_TRY_LOOKUP_AUTHN` | `...try.lookup.authn` | `Boolean` | True during try-lookup phase (requires `TryLookupAuthn` metadata) |
| `IN_PARAMETER_NAME_CHAINED_ATTRIBUTES` | `...chained.attributes` | `Map` | Attributes from previous adapter in a Composite chain |
| `IN_PARAMETER_NAME_PARTNER_ENTITYID` | `...partner.entityid` | `String` | SP entity ID or connection ID |
| `IN_PARAMETER_NAME_USERID` | `...userid` | `String` | Pre-populated user ID (if known) |
| `IN_PARAMETER_NAME_USERID_AUTHENTICATED` | `...userid.authenticated` | `String` | Previously authenticated user ID |
| `IN_PARAMETER_NAME_SERVER_BASE_URL` | `...server.base.url` | `String` | Configured server base URL |
| `IN_PARAMETER_NAME_CURRENT_SERVER_BASE_URL` | `...current.server.base.url` | `String` | Actual request base URL |
| `IN_PARAMETER_NAME_TRACKING_ID` | `...tracking.id` | `String` | Transaction tracking ID for audit correlation |
| `IN_PARAMETER_NAME_REQUEST_ID` | `...request.id` | `String` | Unique request identifier |
| `IN_PARAMETER_NAME_TRANSACTION_ID` | `...transaction.id` | `String` | Transaction identifier |
| `IN_PARAMETER_NAME_OAUTH_CLIENT_ID` | `...oauth.client.id` | `String` | OAuth client ID (if OIDC/OAuth flow) |
| `IN_PARAMETER_NAME_OAUTH_CLIENT_NAME` | `...oauth.client.name` | `String` | OAuth client display name |
| `IN_PARAMETER_NAME_OAUTH_SCOPE` | `...oauth.scope` | `String` | Requested OAuth scopes |
| `IN_PARAMETER_NAME_OAUTH_SCOPE_DESCRIPTIONS` | `...oauth.scope.descriptions` | `String` | Scope descriptions |
| `IN_PARAMETER_NAME_DEFAULT_SCOPE` | `...oauth.scope.default.description` | `String` | Default scope description |
| `IN_PARAMETER_NAME_OAUTH_AUTHORIZATION_DETAILS` | `...oauth.authorization.details` | `String` | RAR authorization details (RFC 9396) |
| `IN_PARAMETER_NAME_OAUTH_AUTHORIZATION_DETAIL_DESCRIPTIONS` | `...oauth.authorization.detail.descriptions` | `String` | RAR detail descriptions |
| `IN_PARAMETER_NAME_APPLICATION_NAME` | `...application.name` | `String` | Application display name |
| `IN_PARAMETER_NAME_APPLICATION_ICON_URL` | `...application.icon.url` | `String` | Application icon URL |
| `IN_PARAMETER_NAME_SP_ADAPTER_ID` | `...sp.adapter.id` | `String` | SP adapter instance ID |
| `IN_PARAMETER_NAME_SIGNED_REQUEST_CLAIMS` | `...signed.request.claims` | `String` | Claims from a signed OIDC request object |
| `IN_PARAMETER_NAME_TRACKED_HTTP_REQUEST_PARAMS` | `...tracked.http.request.params` | `String` | HTTP params tracked from initial request |
| `IN_PARAMETER_NAME_DEVICE_SHARING_TYPE` | `...device.sharing.type` | `String` | Device sharing setting for session policy |
| `IN_PARAMETER_OIDC_UI_LOCALES` | `...oidc.ui.locales` | `String` | OIDC `ui_locales` parameter |
| `IN_PARAMETER_NAME_SRI` | `...sri` | `String` | Session Reference Identifier |
| `IN_PARAMETER_NAME_ADAPTER_ACTION` | `...adapter.action` | `String` | Adapter action (see below) |

**Adapter action constants:**

| Constant | Value | Purpose |
|----------|-------|---------|
| `ADAPTER_ACTION_EXTERNAL_CONSENT` | `...action.external.consent` | External consent flow |
| `ADAPTER_ACTION_PASSWORD_RESET` | `...pf.passwordreset` | Password reset sub-flow |
| `ADAPTER_ACTION_CHANGE_PASSWORD` | `...pf.change.password` | Change password sub-flow |

> 💡 **Best Practice:** Always reference these via `IdpAuthenticationAdapterV2.IN_PARAMETER_NAME_*`
> constants rather than hardcoding the string values. This protects against key changes in future SDK versions.

### 7.8 Adapter Style Patterns from Samples

**`template-render-adapter-example` (TemplateRenderAdapter):**

- Interactive form + API-capable mode in same adapter
- HTML path uses `TemplateRendererUtil` and language pack messages
- API path uses `AuthnApiSupport` and JSON state/action flow
- Demonstrates complete dual-path (browser + API) pattern

**`idp-adapter-example` (SampleSubnetAdapter):**

- Non-interactive adapter validates client IPv4 subnet
- Uses chained attributes to assign role (`GUEST` vs `CORP_USER`)
- Implements non-interactive API mode (`getApiSpec() == null`, `interactive(false)`)

**`external-consent-page-example` (ExternalConsentPageAdapter):**

- Enforces adapter action `ADAPTER_ACTION_EXTERNAL_CONSENT`
- Displays OAuth scope and authorization detail approval page
- Persists CSRF token and authorization-detail identifier map in `SessionStateSupport`
- Returns `ADAPTER_INFO_EXTERNAL_CONSENT_ADAPTER=true`

### 7.9 Session and Reauthentication Rules

**Official guidance:**

- Prefer PingFederate-managed authentication sessions over internal session tracking
- If maintaining custom state, use:
  - `SessionStateSupport` for global/session state
  - `TransactionalStateSupport` for transaction-scoped state
- **Always** remove transactional attributes before returning from `lookupAuthN()`
- For password reset/change flows, respect reauthentication policy in
  `IN_PARAMETER_NAME_AUTHN_POLICY` — do not reuse stale session state

### 7.10 Logout (`logoutAuthN`) Behavior

**Official behavior:**

- Called during IdP-initiated and SP-initiated SLO
- Can run asynchronously using response + resume path pattern
- If adapter has internal session state, clear it here
- If external auth system exists, redirect to external logout endpoint and
  return to `resumePath`

**Sample behaviors:**

| Sample | Logout Implementation |
|--------|----------------------|
| `TemplateRenderAdapter` | Returns `true` (no-op logout) |
| `SampleSubnetAdapter` | Returns `true` (no-op logout) |
| `ExternalConsentPageAdapter` | Throws `AuthnAdapterException` (not supported) |

---

## 8. SP Adapter SPI

> **Tags:** `SP`, `adapter`, `createAuthN`, `SsoContext`, `logoutAuthN`, `lookupLocalUserId`

### 8.1 Primary SPI

```java
// org.sourceid.saml20.adapter.sp.authn.SpAuthenticationAdapter
//   extends ConfigurableAuthnAdapter
```

### 8.2 Core Methods

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

### 8.3 Runtime Semantics

| Method | Purpose |
|--------|---------|
| `createAuthN` | Establish SP-side security context during SSO. Returns `Serializable` state. |
| `logoutAuthN` | Terminate SP-side session during SLO. Receives `authnBean` from `createAuthN`. |
| `lookupLocalUserId` | Account linking when no link exists yet. |

**`sp-adapter-example` specifics:**

- Uses `LocalIdPasswordLookup` against uploaded user/password properties file
- Demonstrates returning serializable authn state from `createAuthN` and
  receiving it in `logoutAuthN`
- Provides the **richest** descriptor/GUI validation patterns in the SDK samples:
  field-level, row-level, and config-level validators; action descriptors;
  tables; advanced fields; file upload

---

## 9. Authentication Selector SPI

> **Tags:** `selector`, `callback`, `cookie`, `routing`, `AuthenticationSelectorContext`

### 9.1 Primary SPI

```java
// com.pingidentity.sdk.AuthenticationSelector
//   extends Plugin (-> ConfigurablePlugin + DescribablePlugin)
```

### 9.2 Core Methods

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

### 9.3 Runtime Semantics

- `selectContext` chooses authentication source (adapter, IdP connection, or
  context mapping)
- **Async pattern:** Committed response in `selectContext` pauses flow; browser
  must return to `resumePath`
- `callback` runs after selected auth source completes

> ⚠️ **Callback restriction (from official docs):** Do **not** write response
> body in `callback()`. Cookie writes are supported.

### 9.4 `extraParameters` Keys

```java
EXTRA_PARAMETER_NAME_ENTITY_ID               // SP entity ID
EXTRA_PARAM_NAME_CHAINED_ATTRIBUTES          // merged attributes from previous sources
EXTRA_PARAM_NAME_CLIENT_ID                   // OAuth client_id
EXTRA_PARAM_NAME_SCOPE                       // OAuth scope
EXTRA_PARAM_NAME_AUTHORIZATION_DETAILS       // OAuth authorization_details
EXTRA_PARAMETER_NAME_AUTHN_POLICY            // AuthnPolicy
EXTRA_PARAMETER_NAME_INSTANCE_ID             // selector instance ID
EXTRA_PARAM_NAME_TRACKED_HTTP_REQUEST_PARAMS // tracked HTTP request parameters
AUTHN_REQ_DOC_PARAM_NAME                     // SP-initiated AuthnRequest document
```

### 9.5 Result Types

```java
// AuthenticationSelectorContext.ResultType
ADAPTER_ID    // return adapter instance ID
IDP_CONN_ID   // return IdP connection ID
CONTEXT       // return context string for context mapping
```

### 9.6 Selector Callback Pattern

Selector implementations can inject values into the returned attribute map
via the `callback()` method:

```java
// Selector callback — adds result attribute
public void callback(HttpServletRequest req, HttpServletResponse res,
    Map authnIdentifiers, AuthenticationSourceKey authnSourceKey,
    AuthenticationSelectorContext context) {

    if (StringUtils.isNotBlank(this.attributeName)
        && !authnIdentifiers.containsKey(this.attributeName)) {

        // Match existing attribute types (AttributeValue vs String)
        boolean anyAttributeValues = false;
        for (Object value : authnIdentifiers.values()) {
            anyAttributeValues |= value instanceof AttributeValue;
        }

        // Use AttributeValue if other attributes are using it
        Object attr = anyAttributeValues
            ? new AttributeValue(context.getResult())
            : context.getResult();
        authnIdentifiers.put(this.attributeName, attr);
    }
}
```

### 9.7 Sample Behavior

**`authentication-selector-example` behavior:**

- Prompts for email/domain, extracts domain, sets persistent cookie
- Supports API and non-API execution paths
- If no input exists, returns `null` after rendering response (still in progress)
- Defaults cookie name to `pf-authn-selector-<configuration-id>` when not configured
- Uses `AuthenticationSelectorDescriptor.setSupportsExtendedResults(true)`
- Sets `ResultType.CONTEXT` and returns domain string for context mapping

---

## 10. Authentication API (JSON/REST)

> **Tags:** `API`, `AuthnApiPlugin`, `state`, `action`, `model`, `JSON`, `REST`, `ParamMapping`

The Authentication API is PingFederate's REST interface that mirrors the
browser-based adapter/selector flow. Adapters/selectors can support both
browser (HTML) and API (JSON) request paths simultaneously.

### 10.1 Marker Interface

```java
// com.pingidentity.sdk.api.authn.AuthnApiPlugin

// Implemented by adapters/selectors that support the Authentication API.
// Key method:
AuthnApiSpec getApiSpec();
```

> ⚠️ **Non-interactive adapters:** If `getApiSpec()` returns `null`, the adapter
> is treated as non-interactive (no user input needed). The `interactive` flag
> on `AuthnApiSpec` controls whether PF expects user interaction.

### 10.2 AuthnApiSpec Structure

The spec defines the contract between your plugin and the Authentication API:

- **States** — Named conditions the adapter can be in (e.g. `USERNAME_PASSWORD_REQUIRED`)
- **Actions** — Operations the API client can invoke (e.g. `submitUserCredentials`)
- **Models** — Java POJOs describing the JSON body structure for states and actions
- **Errors** — Typed error specifications with user messages

### 10.3 States, Actions, and Models

```java
// State specification
AuthnStateSpec<M> stateSpec = new AuthnStateSpec.Builder<M>()
    .status("USERNAME_PASSWORD_REQUIRED")
    .modelClass(UsernamePasswordRequired.class)
    .build();

// Action specification
ActionSpec actionSpec = new ActionSpec.Builder()
    .id("submitUserCredentials")
    .description("Submit username and password")
    .modelClass(SubmitUserCredentials.class)
    .build();

// AuthnApiSpec (returned from getApiSpec())
AuthnApiSpec apiSpec = new AuthnApiSpec.Builder()
    .interactive(true)   // adapter requires user interaction
    .addState(stateSpec)
    .addAction(actionSpec)
    .build();
```

### 10.4 PluginApiSpec Construction

Adapters construct their API spec by referencing pre-built `CommonStateSpec`
constants and passing them as a list:

```java
public PluginApiSpec getApiSpec() {
    List<AuthnStateSpec> states = Arrays.asList(
        CommonStateSpec.USERNAME_PASSWORD_REQUIRED,
        CommonStateSpec.CHALLENGE_RESPONSE_REQUIRED,
        CommonStateSpec.MUST_CHANGE_PASSWORD,
        CommonStateSpec.CHANGE_PASSWORD_EXTERNAL,
        CommonStateSpec.NEW_PASSWORD_RECOMMENDED,
        CommonStateSpec.NEW_PASSWORD_REQUIRED,
        CommonStateSpec.SUCCESSFUL_PASSWORD_CHANGE,
        CommonStateSpec.ACCOUNT_RECOVERY_USERNAME_REQUIRED,
        CommonStateSpec.ACCOUNT_RECOVERY_OTL_VERIFICATION_REQUIRED,
        CommonStateSpec.RECOVERY_CODE_REQUIRED,
        CommonStateSpec.PASSWORD_RESET_REQUIRED,
        CommonStateSpec.SUCCESSFUL_PASSWORD_RESET,
        CommonStateSpec.USERNAME_RECOVERY_EMAIL_REQUIRED,
        CommonStateSpec.USERNAME_RECOVERY_EMAIL_SENT,
        CommonStateSpec.SUCCESSFUL_ACCOUNT_UNLOCK,
        CommonStateSpec.CURRENT_CREDENTIALS_REQUIRED
    );
    return new PluginApiSpec(states);
}
```

For non-interactive selectors (no user input), return `null` from `getApiSpec()`
and override `getApiPluginDescriptor()`:

```java
// Non-interactive selector
public PluginApiSpec getApiSpec() {
    return null;
}

public AuthnApiPluginDescriptor getApiPluginDescriptor() {
    return new AuthnApiPluginDescriptor.Builder()
        .interactive(false)
        .build();
}
```

### 10.5 Runtime Flow (Official docs)

For each request:
1. Check if the incoming request is an API request: `apiSupport.isApiRequest(req)`
2. Check if a known action is being submitted: `ActionSpec.SUBMIT.isRequested(req)`
3. On API request, deserialize the model: `apiSupport.deserializeAsModel(req, Model.class)`
4. Perform validation (built-in from `@Schema` annotations, plus custom)
5. On validation error, throw `AuthnErrorException`
6. If no action is requested, render the response for the current state

### 10.6 AuthnApiSupport Singleton

```java
// com.pingidentity.sdk.api.authn.AuthnApiSupport

// Get the singleton
AuthnApiSupport apiSupport = AuthnApiSupport.getDefault();

// Key methods
boolean isApiRequest(HttpServletRequest req);
String getActionId(HttpServletRequest req);
<M> M deserializeAsModel(HttpServletRequest req, Class<M> modelClass)
    throws IOException, AuthnErrorException;
<M> AuthnState<M> makeAuthnState(HttpServletRequest req,
    AuthnStateSpec<M> stateSpec, M model);
void writeAuthnStateResponse(HttpServletRequest req, HttpServletResponse resp,
    AuthnState<?> authnState) throws IOException;
void writeErrorResponse(HttpServletRequest req, HttpServletResponse resp,
    AuthnError error);
```

### 10.7 ParamMapping: Type-Safe Dual-Path Parameter Extraction

`ParamMapping<M, T>` objects declare how a single value maps between form
parameters and API model fields:

```java
// com.pingidentity.sdk.api.authn.util.ParamMapping

// Each mapping binds a form param name → model getter → type converter
private static final ParamMapping<CheckUsernamePassword, String> USERNAME_MAPPING =
    new ParamMapping<>("pf.username",          // HTML form param name
        CheckUsernamePassword.class,           // API model class
        CheckUsernamePassword::getUsername,     // model getter
        Function.identity());                  // form-value → type converter

private static final ParamMapping<CheckUsernamePassword, Boolean> REMEMBER_MAPPING =
    new ParamMapping<>("pf.rememberUsername",
        CheckUsernamePassword.class,
        CheckUsernamePassword::getRememberMyUsername,
        "on"::equalsIgnoreCase);               // checkbox "on" → Boolean
```

This allows extraction to work uniformly regardless of whether the request
came from an HTML form or an API JSON body. The converter function handles
type coercion (e.g., checkbox `"on"` → `Boolean`).

### 10.8 Dual-Path Action Dispatch

```java
// From official docs — preferred approach
private boolean isSubmitRequest(HttpServletRequest req) {
    if (apiSupport.isApiRequest(req)) {
        return ActionSpec.SUBMIT_USER_ATTRIBUTES.isRequested(req);
    }
    return StringUtils.isNotBlank(req.getParameter("pf.submit"));
}

// Extracting models
private SubmitUserAttributes getSubmitted(HttpServletRequest req)
    throws AuthnErrorException, AuthnAdapterException {
    if (apiSupport.isApiRequest(req)) {
        return apiSupport.deserializeAsModel(req, SubmitUserAttributes.class);
    }
    // Build model from form parameters
    SubmitUserAttributes result = new SubmitUserAttributes();
    result.setUsername(req.getParameter("username"));
    return result;
}
```

### 10.9 Content-Type Action Dispatch

The Authentication API uses **vendor media types** to dispatch actions:

```
Content-Type: application/vnd.pingidentity.{actionId}+json
```

For example, `submitUserCredentials` becomes:
```
Content-Type: application/vnd.pingidentity.submitUserCredentials+json
```

`AuthnApiSupport` handles parsing: `toActionId(contentType)` extracts the action
ID, and `toContentType(actionId)` builds the full media type string.

### 10.10 Error Handling

```java
// Invalid action handling (best practice)
if (apiSupport.getActionId(req) != null) {
    throw new AuthnErrorException(CommonErrorSpec.INVALID_ACTION_ID.makeInstance());
}

// Sending API responses
UserAttributesRequired model = new UserAttributesRequired();
model.setAttributeNames(new ArrayList<>(extendedAttr));
AuthnState<UserAttributesRequired> authnState =
    apiSupport.makeAuthnState(req, StateSpec.USER_ATTRIBUTES_REQUIRED, model);
apiSupport.writeAuthnStateResponse(req, resp, authnState);

// Error response handling
try {
    // ... processing ...
} catch (AuthnErrorException e) {
    apiSupport.writeErrorResponse(req, resp, e.getValidationError());
    authnAdapterResponse.setAuthnStatus(AUTHN_STATUS.IN_PROGRESS);
    return authnAdapterResponse;
}
```

### 10.11 Custom AuthnErrorDetail Extension

Extend `AuthnErrorDetail` to include plugin-specific diagnostic fields while
maintaining SDK compatibility:

```java
public class CustomAuthnErrorDetail extends AuthnErrorDetail
    implements Serializable {

    private final String providerId;

    public CustomAuthnErrorDetail(Builder builder) {
        this.setCode(builder.code);
        this.setMessage(builder.message);
        this.setUserMessage(builder.userMessage);
        this.providerId = builder.providerId;
    }

    public static class Builder {
        private String code, message, userMessage, providerId;

        public Builder setCode(String code)     { this.code = code; return this; }
        public Builder setMessage(String msg)   { this.message = msg; return this; }
        public Builder setUserMessage(String m) { this.userMessage = m; return this; }
        public Builder setProviderId(String id) { this.providerId = id; return this; }

        public CustomAuthnErrorDetail build() {
            return new CustomAuthnErrorDetail(this);
        }
    }
}
```

**Usage:** Passed as detail objects in `AuthnError` responses, providing the API
consumer with plugin-specific diagnostic information beyond the standard fields.

### 10.12 Multi-State Authentication API Plugin

Complex adapters can define 20+ custom `AuthnStateSpec` constants covering
an entire workflow lifecycle (e.g., MFA device pairing, identity verification):

```java
public class StateSpec {
    // Setup / selection
    public static final AuthnStateSpec<SetupRequired>
        SETUP_REQUIRED = new AuthnStateSpec.Builder()
            .status("SETUP_REQUIRED")
            .description("User must set up a device.")
            .modelClass(Void.class)           // no model data needed
            .action(ActionSpec.SETUP)
            .action(ActionSpec.SKIP)
            .action(CommonActionSpec.CANCEL_AUTHENTICATION)
            .build();

    // Per-channel activation targets
    public static final AuthnStateSpec<...>
        EMAIL_TARGET_REQUIRED = ...;   // submit email
        SMS_TARGET_REQUIRED   = ...;   // submit phone
        TOTP_ACTIVATION_REQUIRED = ...;  // enter TOTP code

    // Polling states
    public static final AuthnStateSpec<...>
        PUSH_ACTIVATION_REQUIRED = ...;    // poll for push acceptance
        WEBAUTHN_ACTIVATION_REQUIRED = ...;  // WebAuthn biometrics

    // Template overrides using CommonStateSpec
    public static final AuthnStateSpec<CustomCompleted>
        COMPLETED = new AuthnStateSpec.Builder()
            .template(CommonStateSpec.MFA_COMPLETED, CustomCompleted.class)
            .build();
    public static final AuthnStateSpec<CustomFailed>
        FAILED = new AuthnStateSpec.Builder()
            .template(CommonStateSpec.MFA_FAILED, CustomFailed.class)
            .build();
}
```

**Patterns:**
- `.template(CommonStateSpec.XXX, CustomModel.class)` — inherit a common state's
  status string and description, but override the model class
- `.modelClass(Void.class)` — valid for states that carry no model payload
- Each state declares exactly which actions are available, forming a state machine graph
- Actions like `RESEND_OTP`, `CANCEL_PAIRING` can be reused across states

### 10.13 Built-in Status Strings (CommonStatus)

```java
// com.pingidentity.sdk.api.authn.common.CommonStatus

USERNAME_PASSWORD_REQUIRED, AUTHENTICATION_REQUIRED, COMPLETED, RESUME, FAILED,
CHANGE_PASSWORD_EXTERNAL,

// Password management
NEW_PASSWORD_REQUIRED, NEW_PASSWORD_RECOMMENDED, CURRENT_CREDENTIALS_REQUIRED,

// Account recovery
ACCOUNT_RECOVERY_USERNAME_REQUIRED, PASSWORD_RESET_REQUIRED,
CHALLENGE_RESPONSE_REQUIRED, RECOVERY_CODE_REQUIRED,
USERNAME_RECOVERY_EMAIL_REQUIRED, USERNAME_RECOVERY_EMAIL_SENT,
SUCCESSFUL_PASSWORD_CHANGE, SUCCESSFUL_PASSWORD_RESET, SUCCESSFUL_ACCOUNT_UNLOCK,
CANCELED,

// Registration
REGISTRATION_REQUIRED, EMAIL_VERIFICATION_REQUIRED, ONE_TIME_LINK_VERIFICATION_REQUIRED,

// Multi-factor
DEVICE_SELECTION_REQUIRED, OTP_REQUIRED, OTP_VERIFIED,
PUSH_CONFIRMATION_WAITING, PUSH_CONFIRMATION_TIMED_OUT, PUSH_CONFIRMATION_REJECTED,
MOBILE_PAIRING_REQUIRED, MFA_COMPLETED, MFA_FAILED,

// Device profiling
DEVICE_PROFILE_REQUIRED, DEVICE_PROFILE_SESSION_ID_REQUIRED,

// OAuth Device Authorization
OAUTH_DEVICE_USER_CODE_REQUIRED, OAUTH_DEVICE_USER_CODE_CONFIRMATION_REQUIRED,
OAUTH_DEVICE_COMPLETED,

// Identifier-first
IDENTIFIER_REQUIRED,

// Misc
ACCOUNT_LINKING_FAILED, EXTERNAL_AUTHENTICATION_REQUIRED, EXTERNAL_AUTHENTICATION_FAILED,
MULTI_FACTOR_AUTHENTICATION_STATE
```

### 10.14 Built-in Action IDs (CommonActionId)

```java
// com.pingidentity.sdk.api.authn.common.CommonActionId

checkUsernamePassword, checkChallengeResponse, checkNewPassword,
continueAuthentication, initiateAccountRecovery, cancelAccountRecovery,
checkAccountRecoveryUsername, checkRecoveryCode, checkPasswordReset,
continueAccountRecovery, recoverUsername, checkUsernameRecoveryEmail,
cancelUsernameRecovery, submitIdentifier, clearIdentifier,
cancelIdentifierSubmission, initiateRegistration, registerUser,
selectDevice, checkOtp, resendOtp, authenticate, poll,
submitDeviceProfile, checkCurrentCredentials, submitDeviceProfileSessionId,
sendEmailVerificationOtl, skipOtp, submitUserCode, confirmUserCode,
useClientSideAuthenticator
```

### 10.15 Built-in Error Specs (CommonErrorSpec)

```java
// com.pingidentity.sdk.api.authn.common.CommonErrorSpec

VALIDATION_ERROR     (400, "VALIDATION_ERROR",     "The request was not valid.")
INVALID_REQUEST      (400, "INVALID_REQUEST",      "The incoming request is not valid for the ...")
FLOW_NOT_FOUND       (404, "FLOW_NOT_FOUND",       "No matching flow found for the flowId.")
UNEXPECTED_ERROR     (500, "UNEXPECTED_ERROR",      "An unexpected error occurred ...")
INVALID_ACTION_ID    (400, "INVALID_ACTION_ID",     "The provided action ID is not valid ...")
REGISTRATION_FAILED  (400, "REGISTRATION_FAILED",   "An error occurred while attempting to ...")
REQUEST_FAILED       (400, "REQUEST_FAILED",         "The request could not be completed. ...")

// Usage:
AuthnError error = CommonErrorSpec.VALIDATION_ERROR.makeInstanceBuilder()
    .detail(CommonErrorDetailSpec.CREDENTIAL_VALIDATION_FAILED.makeInstance())
    .build();
throw new AuthnErrorException(error);
```

### 10.16 Built-in Error Detail Specs (CommonErrorDetailSpec)

```java
// com.pingidentity.sdk.api.authn.common.CommonErrorDetailSpec

// Credential/field validation (parent: VALIDATION_ERROR)
CREDENTIAL_VALIDATION_FAILED     "authn.api.invalid.credentials"
INVALID_CHALLENGE_RESPONSE       "authn.api.invalid.challenge.response"
FIELD_REQUIRED                   "authn.api.missing.required.field"
UNEXPECTED_ACTION_MODEL          "authn.api.unexpected.action.model"
INVALID_FIELD_FORMAT             "authn.api.invalid.field.format"
UNRECOGNIZED_FIELD_NAME          "authn.api.unrecognized.field"
INVALID_OTP                      "authn.api.invalid.otp"
INVALID_DEVICE                   (no user message key)
INVALID_FIELD_VALUE              (no user message key)
INVALID_MOBILE_PAYLOAD           (no user message key)
CAPTCHA_ERROR                    (no user message key)
INVALID_USER_CODE                "authn.api.oauth.device.user.code.invalid"

// Content-type (parent: INVALID_REQUEST)
INVALID_CONTENT_TYPE             (no user message key)

// Account/password (parent: VALIDATION_ERROR)
PASSWORD_CHANGE_ERROR            (no user message key)
ACCOUNT_RECOVERY_ERROR           (no user message key)
REGISTRATION_FAILED              (parent: REGISTRATION_FAILED)

// Rate limiting (parent: REQUEST_FAILED)
OTP_RESEND_LIMIT                 "authn.api.otp.resend.limit"
OTP_ATTEMPTS_LIMIT               "authn.api.otp.attempts.limit"
OTP_EXPIRED                      "authn.api.otp.expired"
TOTP_ATTEMPTS_LIMIT              "authn.api.totp.attempts.limit"
DEVICE_LOCKED                    "authn.api.device.locked"
DEVICE_ROOTED                    "authn.api.device.rooted"
PUSH_FAILED                      "authn.api.push.failed"
USER_CODE_LOCKED                 "authn.api.oauth.device.user.code.locked"
USER_CODE_EXPIRED                "authn.api.oauth.device.user.code.expired"
```

> ℹ️ **User message keys:** The `userMessageKey` values map to entries in
> `authn-api-messages` resource bundles. PF resolves these to localized strings
> using `AuthnApiSupport.getLocalizedMessage()`.

---

## 11. Password Credential Validator SPI

> **Tags:** `PCV`, `password`, `reset`, `change`, `chain`, `unlock`, `recovery`

### 11.1 Primary SPI

```java
// com.pingidentity.sdk.password.PasswordCredentialValidator
//   extends ConfigurablePlugin + DescribablePlugin

AttributeMap processPasswordCredential(String username, String password)
    throws PasswordValidationException;
```

### 11.2 Optional Extension Interfaces

PCV implementations can additionally implement these marker interfaces
to enable extended password management flows. PingFederate discovers them
at runtime via `instanceof` checks:

| Interface | Gate Method | Capability |
|-----------|------------|------------|
| `ChangeablePasswordCredential` | `isPasswordChangeable()` | Password change |
| `ResettablePasswordCredential` | `isPasswordResettable()` | Password reset |
| `RecoverableUsername` | (interface presence) | Username recovery |
| `AccountUnlockablePasswordCredential` | `isAccountUnlockable()` | Account unlock |

```java
public class SamplePCV implements PasswordCredentialValidator,
    ChangeablePasswordCredential,
    ResettablePasswordCredential,
    RecoverableUsername,
    AccountUnlockablePasswordCredential {

    @Override
    public boolean isPasswordChangeable() { return true; }
    // PF only calls changePassword() if this returns true

    @Override
    public boolean isPasswordResettable() { return true; }
    // PF only calls resetPassword() if this returns true

    @Override
    public boolean isAccountUnlockable() { return true; }
    // PF only calls unlockAccount() if this returns true
}
```

> ⚠️ **Discoverable at runtime:** PF uses `instanceof` checks on your PCV
> instance. Implementing the interface is sufficient — no registration needed.

### 11.3 PCV Chaining Pattern

Adapters can iterate over configured PCV instances for credential validation:

```java
// Store PCV IDs from configuration
for (Row row : configuration.getTable("Credential Validators").getRows()) {
    this.pwdCrdVal.add(row.getFieldValue("Password Credential Validator Instance"));
}

// At authentication time, iterate PCVs until one validates
AttributeMap authnIds = null;
for (String pcvId : this.pwdCrdVal) {
    try {
        PasswordCredentialValidator credentialValidator =
            new PasswordCredentialValidatorAccessor()
                .getPasswordCredentialValidator(pcvId);
        authnIds = credentialValidator.processPasswordCredential(username, password);
        if (!Util.isEmpty(authnIds)) {
            break;  // first successful PCV wins
        }
    } catch (Exception e) {
        log.warn(e.getMessage());  // log and try next PCV
    }
}
```

### 11.4 Account Lockout Integration

PCVs can integrate with PF's account locking infrastructure:

```java
// com.pingidentity.sdk.account.AccountUnlockablePasswordCredential
boolean unlockAccount(String username);     // returns true on success
boolean isAccountLocked(String username);   // check lock status
boolean isAccountUnlockable();              // whether this PCV supports unlock
```

---

## 12. Identity Store Provisioner SPI

> **Tags:** `provisioner`, `SCIM`, `CRUD`, `identity-store`, `filtering`, `pagination`

### 12.1 Interface Hierarchy

```
IdentityStoreUserProvisioner   (deprecated — users only)
  └── IdentityStoreProvisioner     (users + groups)
       └── IdentityStoreProvisionerWithFiltering (users + groups + filtering/pagination)
```

> ⚠️ **Use `IdentityStoreProvisionerWithFiltering`** for any new implementation.
> The other two are deprecated or lack full feature support.

### 12.2 User CRUD Methods

```java
UserResponseContext createUser(CreateUserRequestContext ctx)
    throws IdentityStoreException;

UserResponseContext readUser(ReadUserRequestContext ctx)
    throws IdentityStoreException;

UserResponseContext updateUser(UpdateUserRequestContext ctx)
    throws IdentityStoreException;

void deleteUser(DeleteUserRequestContext ctx)
    throws IdentityStoreException;
```

### 12.3 Group CRUD Methods

```java
// isGroupProvisioningSupported() must return true; otherwise throw NotImplementedException

GroupResponseContext createGroup(CreateGroupRequestContext ctx)
    throws IdentityStoreException;

GroupResponseContext readGroup(ReadGroupRequestContext ctx)
    throws IdentityStoreException;

GroupResponseContext updateGroup(UpdateGroupRequestContext ctx)
    throws IdentityStoreException;

void deleteGroup(DeleteGroupRequestContext ctx)
    throws IdentityStoreException;
```

### 12.4 Deletion Semantics (Official docs)

> ⚠️ **Critical rule:** After deleting a user, the plugin:
>
> - **MUST** return `NotFoundException` for all `readUser()`, `updateUser()`,
>   and `deleteUser()` operations on the deleted ID
> - **MUST NOT** consider the deleted user in conflict calculation (i.e.,
>   `createUser()` with the same ID should **not** throw `ConflictException`)
> - The plugin may choose **not** to permanently delete the resource — soft
>   delete is allowed if these contracts hold

### 12.5 Filtering Interface (IdentityStoreProvisionerWithFiltering)

Additional methods for list/search with pagination, filtering, and sorting:

```java
UserResponseContextList listUsers(ListUserRequestContext ctx)
    throws IdentityStoreException;

GroupResponseContextList listGroups(ListGroupRequestContext ctx)
    throws IdentityStoreException;
```

**`identity-store-provisioner-example` behavior:**

- In-memory `ConcurrentHashMap` storage
- Supports full CRUD + filtering + soft delete
- Group membership stored as attribute lists
- Filter matching via `IdentityStoreProvisionerUtil` helper class

---

## 13. Notification Publisher SPI

> **Tags:** `notification`, `publisher`, `HTTP`, `event`, `NotificationEventType`

### 13.1 Primary SPI

```java
// com.pingidentity.sdk.notification.NotificationPublisherPlugin
//   extends Plugin (-> ConfigurablePlugin + DescribablePlugin)

PublishResult publishNotification(String eventType,
    Map<String, String> data, Map<String, String> config);
```

### 13.2 NotificationEventType Enum (Complete)

All 33 notification event types in PF 13.0:

```java
// Password/account
PASSWORD_RESET, PASSWORD_CHANGED, USERNAME_RECOVERY, ACCOUNT_UNLOCKED, ACCOUNT_DISABLED,
PASSWORD_RESET_FAILED, PASSWORD_RESET_ONE_TIME_LINK, PASSWORD_RESET_ONE_TIME_CODE

// Admin
ADMIN_PASSWORD_CHANGED, ADMIN_EMAIL_CHANGED, ADMIN_ACCOUNT_CHANGE_NOTIFICATION_OFF

// Ownership verification
OWNERSHIP_VERIFICATION_ONE_TIME_LINK, OWNERSHIP_VERIFICATION_ONE_TIME_PASSCODE

// Certificate lifecycle
CERTIFICATE_EVENT_EXPIRED, CERTIFICATE_EVENT_INITIAL_WARN,
CERTIFICATE_EVENT_FINAL_WARN, CERTIFICATE_EVENT_ACTIVATED, CERTIFICATE_EVENT_CREATED

// License management
SERVER_LICENSING_EVENT_WARNING, SERVER_LICENSING_EVENT_GROUP_WARNING,
SERVER_LICENSING_EVENT_EXPIRED, SERVER_LICENSING_EVENT_GROUP_EXPIRED,
SERVER_LICENSING_EVENT_SHUTDOWN, SERVER_LICENSING_EVENT_GROUP_SHUTDOWN

// SAML metadata
SAML_METADATA_UPDATE_EVENT_FAILED,
SAML_METADATA_UPDATE_EVENT_ENTITY_ID_NOT_FOUND,
SAML_METADATA_UPDATE_EVENT_UPDATED

// Operational
THREAD_POOL_EXHAUSTION, BULKHEAD_WARNING, BULKHEAD_FULL,
AUDIT_LOGGING_FAILURE, AUDIT_LOGGING_RECOVERY
```

### 13.3 Publisher Implementation Pattern

```java
public PublishResult publishNotification(String eventType,
    Map<String, String> data, Map<String, String> config) {

    // 1. Filter config to allowed keys only (security)
    config.entrySet().removeIf(entry ->
        !allowedConfigKeys.contains(entry.getKey()));

    // 2. Build JSON payload with data + config
    Map<String, Map<String, Object>> payload = new HashMap<>();
    payload.put("data", dataCopy);
    payload.put("configuration", config);

    // 3. Check payload size against configured max
    String json = objectMapper.writeValueAsString(payload);
    if (json.getBytes(StandardCharsets.UTF_8).length > maxPayloadSize) {
        // truncate or error
    }

    // 4. Publish to external notification service
    PublishResponse response = notificationClient.publish(
        PublishRequest.builder()
            .destination(topicOrEndpoint)
            .message(json)
            .build());

    // 5. Return result with status
    sendResult.setStatus(NOTIFICATION_STATUS.SUCCESS);
    return sendResult;
}
```

---

## 14. Custom Data Source SPI

> **Tags:** `data-source`, `driver`, `filter`, `JDBC`, `SourceDescriptor`, `JSON-Pointer`

### 14.1 Primary SPI

```java
// com.pingidentity.sdk.ds.CustomDataSourceDriver
//   extends ConfigurablePlugin

boolean testConnection();
Map<String, Object> retrieveValues(Collection<String> attributeNamesRequested,
    SimpleFieldList filterConfiguration);

// Descriptor method (not via DescribablePlugin)
SourceDescriptor getSourceDescriptor();
```

### 14.2 `SourceDescriptor`

Custom data sources provide a `SourceDescriptor` (subclass of `PluginDescriptor`)
that describes both the admin GUI configuration and the source's attribute
contract.

### 14.3 REST Data Source: JSON Pointer Attribute Mapping

The REST Data Source driver maps JSON responses to PF attributes using
RFC 6901 JSON Pointer syntax:

```java
// Configuration: local attribute name → JSON Pointer path
// "email"    → "/contact/email"
// "fullName" → "/name/full"
// "roles"    → "/permissions/0/role"

// At runtime, the driver:
// 1. Makes HTTP request to configured base URL + resource path
// 2. Parses JSON response
// 3. Evaluates each JSON Pointer against the response
// 4. Returns Map<String, Object> of attribute name → resolved value
```

The resource path and body support PF expression substitution:
```
/users?uid=${username}
{"login":"${username}","department":"${departmentNumber}"}
```

### 14.4 Sample Behavior

**`custom-data-store-example` (`SamplePropertiesDataStoreDriver`):**

- Loads `.properties` file as data source
- `testConnection()` validates file can be read
- `retrieveValues()` uses filter to look up key, returns matching values

---

## 15. STS Token SPI

> **Tags:** `STS`, `token`, `WS-Trust`, `BinarySecurityToken`, `TokenProcessor`, `TokenGenerator`

### 15.1 Token Processor

```java
// org.sourceid.wstrust.plugin.TokenProcessor<T extends BinarySecurityToken>

T unmarshallToken(String token) throws WSTrustException;
AttributeMap processToken(T token) throws WSTrustException;
```

### 15.2 Token Generator

```java
// org.sourceid.wstrust.plugin.TokenGenerator

String generateToken(Map<String, Object> inputAttributes)
    throws WSTrustException;
```

### 15.3 Sample Behaviors

**`token-processor-example` (`SampleTokenProcessor`):**

- Parses `BinarySecurityToken` from WS-Trust `RequestSecurityToken`
- Validates token structure and extracts subject attributes
- Returns `AttributeMap` with subject identity claims

**`token-generator-example` (`SampleTokenGenerator`):**

- Accepts `inputAttributes` from policy and trust context
- Generates custom token string
- Returns token value for embedding in WS-Trust response

---

## 16. CIBA / Out-of-Band Auth Plugin

> **Tags:** `CIBA`, `OOB`, `push`, `polling`, `KeyValueStateSupport`, `HandlerRegistry`

### 16.1 Primary SPI

```java
// com.pingidentity.sdk.oobauth.OOBAuthPlugin

OOBAuthTransactionContext initiate(OOBAuthRequestContext requestContext,
    Map<String, Object> inParameters)
    throws UnknownUserException, UserAuthBindingMessageException,
           OOBAuthGeneralException;

OOBAuthResultContext check(String transactionIdentifier,
    Map<String, Object> inParameters) throws OOBAuthGeneralException;

void finished(String transactionIdentifier)
    throws OOBAuthGeneralException;
```

### 16.2 Lifecycle

> ⚠️ **Lifecycle:** PF calls `initiate()` once, then polls `check()` repeatedly
> until the status indicates completion, then calls `finished()` for cleanup.
> `finished()` is best-effort — implementations must have their own resource
> bounds (e.g., expiration sweeping).

### 16.3 Result Status

```java
// OOBAuthResultContext.Status
enum Status { SUCCESS, IN_PROGRESS, FAILURE }
```

### 16.4 `KeyValueStateSupport` vs `SessionStateSupport`

> ⚠️ The CIBA example uses `KeyValueStateSupport` because the approval and
> polling requests come from *different* HTTP sessions (email link vs. polling
> client). Use `KeyValueStateSupport` when state must be shared across
> unrelated requests.

### 16.5 Sample Behavior

**`ciba-auth-plugin-example` (`SampleEmailAuthPlugin`):**

- **Email-based approval flow:** Sends email to user with unique approval link
- **Handler registration:** Uses `HandlerRegistry.registerHandler()` during
  `configure()` to register servlet handlers at custom URL paths:
  ```java
  HandlerRegistry.registerHandler("/oob-auth/review", new OOBRequestForApprovalHandler());
  HandlerRegistry.registerHandler("/oob-auth/approve", new ApproveHandler());
  HandlerRegistry.registerHandler("/oob-auth/deny", new DenyHandler());
  ```
- **Cross-request state:** Uses `KeyValueStateSupport` (not `SessionStateSupport`)
  to store approval state keyed by random transaction ID
- **Status change callback:** Supports `OOBAuthStatusChangeReceiver` for
  push-based status notification (`context.setStatusChangeCallbackCapable(true)`)
- **Base URL access:** Uses `BaseUrlAccessor.getCurrentBaseUrl()` to construct
  callback URLs dynamically
- **Authorization details:** Full support for `AuthorizationDetail` consent
  with identifier mapping (random IDs assigned to protect details from exposure)
- **i18n:** Uses `LanguagePackMessages` + `LocaleUtil.getUserLocale(req)` for
  both email content and approval page rendering
- **Complex descriptor:** Demonstrates `SelectFieldDescriptor` with `OptionValue`s,
  `addAdvancedField()` for collapsible sections, `ActionDescriptor` for
  "Test Email Connectivity" action, `EmailValidator`, `HostnameValidator`,
  `IntegerValidator(0, 65535)` validators
- **Template rendering:** Uses `TemplateRendererUtil.render()` for approval and
  response pages

---

## 17. Additional Extension SPIs

> **Tags:** `secret`, `DCR`, `grant`, `client-storage`, `captcha`, `authorization-details`, `bearer-ATM`, `session-storage`

### 17.1 Secret Manager

```java
// com.pingidentity.sdk.secretmanager.SecretManager

SecretInfo getSecretInfo(String secretId, Map<String, Object> inParameters)
    throws SecretManagerException;

// Descriptor: use SecretManagerDescriptor (extends PluginDescriptor)
// Reference format: OBF:MGR:{secretManagerId}:{secretId}
```

**`secret-manager-example` (`SampleSecretManager`) behavior:**

- Environment variable lookup: Resolves `secretId` to `System.getenv(secretId)`
- ActionDescriptor with parameters for generating secret references
- Uses `SecretManagerDescriptor` with `VersionUtil.getVersion()`

### 17.2 Dynamic Client Registration Plugin

```java
// com.pingidentity.sdk.oauth20.registration.DynamicClientRegistrationPlugin

// All methods are `default` — override only the ones you need
default void processPlugin(HttpServletRequest request,
    HttpServletResponse response, DynamicClient dynamicClient,
    Map<String, Object> inParameters) throws ClientRegistrationException;

default void processPluginUpdate(HttpServletRequest request,
    HttpServletResponse response, DynamicClient dynamicClient,
    DynamicClient existingDynamicClient, Map<String, Object> inParameters)
    throws ClientRegistrationException;

default void processPluginGet(...) throws ClientRegistrationException;
default void processPluginDelete(...) throws ClientRegistrationException;
```

> ⚠️ **All methods are `default`:** The interface has no abstract methods.
> This allows you to override only the lifecycle hooks relevant to your
> validation logic.

**`dynamic-client-registration-example` behavior:**

- JWT software statement validation with JWKS/JWKS URI signature verification
- Algorithm constraints: RS256/384/512 and ES256/384/512
- Claim-to-client mapping via `DynamicClientFields` enum
- Cross-field validation (XOR on JWKS URL vs inline JWKS)
- Error model: `ClientRegistrationException` with HTTP status and `ErrorCode` enum

### 17.3 Access Grant Manager

```java
// com.pingidentity.sdk.accessgrant.AccessGrantManager

void saveGrant(AccessGrant grant, AccessGrantAttributesHolder attributes)
    throws AccessGrantManagementException;
AccessGrant getByGuid(String guid) throws AccessGrantManagementException;
AccessGrant getByRefreshToken(String refreshToken)
    throws AccessGrantManagementException;
Collection<AccessGrant> getByClientId(String clientId)
    throws AccessGrantManagementException;
Collection<AccessGrant> getByUserKey(String userKey)
    throws AccessGrantManagementException;
void revokeGrant(String guid) throws AccessGrantManagementException;
default void deleteGrant(String guid)
    throws AccessGrantManagementException;  // since 12.0
void deleteExpiredGrants();
```

> ⚠️ **`revokeGrant` vs `deleteGrant`:** `revokeGrant()` deletes the grant AND
> may take further action (e.g., notifying token introspection services).
> `deleteGrant()` (since 12.0) is a pure storage delete with no side effects.

> ⚠️ **No configuration UI:** `AccessGrantManager` has no `configure()` method.
> It is registered as a service-point implementation via `service-points.conf`,
> not as an admin-configured plugin.

### 17.4 Client Storage Manager

```java
// com.pingidentity.sdk.oauth20.ClientStorageManagerV2 / ClientStorageManagerBase

ClientData getClient(String clientId) throws ClientStorageManagementException;
Collection<ClientData> getClients() throws ClientStorageManagementException;
void addClient(ClientData client) throws ClientStorageManagementException;
void updateClient(ClientData client) throws ClientStorageManagementException;
void deleteClient(String clientId) throws ClientStorageManagementException;
Collection<ClientData> search(SearchCriteria searchCriteria)
    throws ClientStorageManagementException;
```

**Registration:** Configured via `service-points.conf`:
```properties
client.storage.manager=com.pingidentity.clientstorage.SampleClientStorage
client.manager=org.sourceid.oauth20.domain.ClientManagerGenericImpl
```

> ⚠️ **Admin-only method:** `search()` is only invoked by the admin console for
> UI pagination. Engine nodes at runtime use `getClient()` directly.

### 17.5 Authorization Detail Processor

```java
// com.pingidentity.sdk.authorizationdetails.AuthorizationDetailProcessor extends Plugin

AuthorizationDetailValidationResult validate(
    AuthorizationDetail detail, AuthorizationDetailContext ctx,
    Map<String, Object> inParams);
AuthorizationDetail enrich(
    AuthorizationDetail detail, AuthorizationDetailContext ctx,
    Map<String, Object> inParams);
String getUserConsentDescription(
    AuthorizationDetail detail, AuthorizationDetailContext ctx,
    Map<String, Object> inParams);
boolean isEqualOrSubset(AuthorizationDetail detail1, AuthorizationDetail detail2,
    AuthorizationDetailContext ctx, Map<String, Object> inParams);
```

**Use case:** Custom processing of RFC 9396 authorization details types.

### 17.6 Captcha/Risk Provider

```java
// com.pingidentity.sdk.captchaprovider.CaptchaProvider extends Plugin

String              getJavaScriptFileName();
Map<String, Object> getCaptchaAttributes(CaptchaContext context);
CaptchaResult       validateCaptcha(CaptchaContext context);
default void        postAuthenticationCallback(AuthenticationStatus status,
                        CaptchaContext context);
```

**Action constants:**

| Constant | Value |
|----------|-------|
| `IDENTIFY_ACTION` | `"identify"` |
| `LOGIN_ACTION` | `"login"` |
| `USERNAME_RECOVERY_ACTION` | `"usernameRecovery"` |
| `PASSWORD_RESET_ACTION` | `"passwordReset"` |
| `PASSWORD_CHANGE_ACTION` | `"passwordChange"` |
| `REGISTRATION_ACTION` | `"registration"` |

### 17.7 Bearer Access Token Management Plugin

```java
// com.pingidentity.sdk.oauth20.BearerAccessTokenManagementPlugin extends Plugin

AccessToken validateAccessToken(String tokenValue);
IssuedAccessToken issueAccessToken(
    Map<String, AttributeValue> attributes, Scope scope,
    String clientId, String accessGrantGuid);
```

**Optional revocation support** — implement `AccessTokenRevocable`:

```java
void revokeAllAccessTokens(String accessGrantGuid);
void revokeAllAccessTokensByClient(String clientId);
boolean revokeAccessToken(String tokenValue);
AccessToken getAccessToken(String tokenValue);
```

### 17.8 Session Storage Manager

```java
// org.sourceid.saml20.service.session.data.SessionStorageManager

void createSessionGroup(SessionGroupData data);
void updateSessionGroup(SessionGroupData data);
void deleteSessionGroups(Collection<String> sessionRefs);
void deleteSessionGroupsByGroupIds(Collection<String> groupIds);
Collection<SessionGroupAndSessionsData> getSessionGroupsAndSessions(
    Collection<String> refs);
void saveAuthnSessions(Collection<AuthnSessionData> sessions);
void deleteAuthnSessions(String sessionGroupRef, Collection<String> sessionIds);
boolean supportsBatchCleanup();
void deleteExpiredSessionGroups();
```

---

# Part III — Cross-Cutting Concerns

## 18. Session State and Transaction State

> **Tags:** `session`, `transaction`, `state`, `cleanup`, `namespacing`, `KeyValueStateSupport`

### 18.1 State Support Classes

PingFederate provides three state management mechanisms for plugins that must
persist data across HTTP requests:

| Class | Scope | Use When |
|-------|-------|----------|
| `SessionStateSupport` | User session (global or per-adapter) | State that persists across SSO transactions |
| `TransactionalStateSupport` | Single authentication transaction | State needed only during one auth flow |
| `KeyValueStateSupport` | Cross-session, keyed by arbitrary ID | State shared across unrelated sessions (e.g., CIBA) |
| `ApplicationSessionStateSupport` | Application-level session data | Application session attributes (30s write debounce) |

### 18.2 Core API Pattern

```java
// All state support classes follow this pattern:
void setAttribute(String key, Object value,
    HttpServletRequest req, HttpServletResponse resp);
Object getAttribute(String key,
    HttpServletRequest req, HttpServletResponse resp);
void removeAttribute(String key,
    HttpServletRequest req, HttpServletResponse resp);
```

### 18.3 Session Key Namespacing Strategy

Use a consistent namespacing pattern for session/transaction state keys to
prevent cross-instance collisions:

```java
// Per-adapter-instance keys (most precise)
this.SESSION_KEY = getClass().getSimpleName() + ":" + config.getId() + ":" + keyName;

// Global keys (when "Globally" session state scope)
this.SESSION_KEY = getClass().getSimpleName() + ":" + keyName;

// Examples:
"MyIdpAdapter:myInstanceId:SESSION"
"MyIdpAdapter:myInstanceId:username"
"MyIdpAdapter:myInstanceId:first-activity"
```

> ⚠️ **Important:** Always namespace session keys with `getClass().getSimpleName()`
> and the adapter instance ID. Failure to do this causes silent state collision
> when multiple instances of the same adapter type are configured.

### 18.4 TransactionalStateSupport: Cleanup Rules

- **Always** remove transactional attributes before returning `SUCCESS` from `lookupAuthN()`
- Transactional state is scoped to a resume path — it is automatically cleaned up
  when the transaction completes, but explicit cleanup prevents stale state on retry

### 18.5 State Support Factory + Typed Wrapper Pattern

For complex adapters with many state keys, wrap `TransactionalStateSupport`
in a testable, typed facade:

```java
// Factory interface — allows mocking in unit tests
public interface StateSupportFactory {
    AdapterStateSupport newInstance(
        HttpServletRequest req, HttpServletResponse resp, String resumePath);
}

// Concrete wrapper — fully-qualified key names prevent collisions
public class AdapterStateSupport {
    private static final String RESUME_URL =
        MyAdapter.class.getName() + ".resumeUrl";
    private static final String NONCE =
        MyAdapter.class.getName() + ".nonce";
    private final TransactionalStateSupport tss;

    public String setNonce() {
        String nonce = UUID.randomUUID().toString();
        tss.setAttribute(NONCE, nonce, req, resp);
        return nonce;
    }
    public String getAndRemoveNonce() {
        String stored = (String) tss.getAttribute(NONCE, req, resp);
        tss.removeAttribute(NONCE, req, resp);      // consume-once
        return stored;
    }
    public void cleanup() {
        removeAttribute(RESUME_URL);
        removeAttribute(CONTINUE_TOKEN);
    }
}
```

Key insight: the factory is typically assigned as a method reference in production
(`AdapterStateSupport::new`) and injected via constructor in tests.

### 18.6 ApplicationSessionStateSupport Write Debounce

`ApplicationSessionStateSupport` uses a **30-second write debounce** to reduce
session store contention under high throughput. This means `setAttribute()` calls
may not be immediately persisted to the session store.

---

## 19. Thread Safety and Concurrency

> **Tags:** `thread`, `concurrency`, `singleton`, `configure`, `ConfigCache`

### 19.1 Official Guarantees (from Javadoc)

1. **Instance creation:** PingFederate creates a new plugin instance and calls
   `configure()` before allowing any request access. No concurrent access during
   initialization.

2. **Configuration reload:** When admin changes settings, PingFederate creates
   a **new instance** and calls `configure()` on it. The old instance is retired
   — it is never mutated in-place.

3. **Request processing:** Multiple threads may call `lookupAuthN()`,
   `selectContext()`, etc. on the same instance concurrently. Your plugin must be
   thread-safe for request processing.

4. **Concurrency model:** "All concurrency issues are handled in the server so
   you don't need to worry about them" — this refers specifically to the
   create/configure lifecycle, **not** to per-request processing.

### 19.2 Practical Implications

| Phase | Thread Safety | Recommendation |
|-------|---------------|----------------|
| `configure()` | Single-threaded | Safe to initialize mutable state |
| `getPluginDescriptor()` | May be called multiple times | Return cached instance |
| `lookupAuthN()` / `selectContext()` | Multi-threaded | Use only immutable state from `configure()` |
| `logoutAuthN()` | Multi-threaded | Same as above |

> ⚠️ **Rule:** Store all configuration-derived values as **final or effectively
> immutable** fields during `configure()`. Do **not** share mutable state across
> requests unless properly synchronized.

### 19.3 SDK-Internal Thread Safety Patterns

The SDK itself follows consistent thread safety patterns that plugin
writers should be aware of:

- **`AccessToken.expiresAt`** is declared `volatile` — safe for cross-thread
  expiry checks without synchronization
- **`AccessToken.getAttributes()`** returns `Collections.unmodifiableMap()` —
  callers cannot accidentally corrupt the attribute map
- **`HandlerRegistry`** uses `synchronized` on both `registerHandler()` and
  `getHandler()` — safe for concurrent registration during startup
- **`ServiceFactory`** caches resolved implementations in a `synchronized`
  block with a shared `SERVICE_CACHE` — SPI lookups are thread-safe but the
  first invocation pays a reflection cost
- **`ConfigCache`** delegates to the server's proxied `ConfigHolder` — cache
  invalidation is managed by the server, not the plugin

### 19.4 ConfigCache: Thread-Safe Hot-Reloading Configuration

```java
// com.pingidentity.sdk.util.ConfigCache<K, V>
// Thread-safe cache using ConcurrentHashMap with functional initialization.

ConfigCache<KeyType, ValType> cache = new ConfigCache<>(key -> expensiveInit(key));
ValType value = cache.get(key);

// Explicit invalidation
cache.invalidate(key);
cache.invalidateAll();
```

> ⚠️ **Lifecycle:** Create the `ConfigCache` in `configure()`, not in the
> constructor. The `ServiceFactory` backing it requires a running PF context.

---

## 20. Logging, Audit & Observability

> **Tags:** `logging`, `audit`, `LoggingUtil`, `LogEvent`, `structured-logging`

### 20.1 Standard Logging

- Use commons-logging or SLF4J (PF ships logging infrastructure)
- Never log credentials, tokens, or PII at INFO level
- Replace all `System.out` / `System.err` with a consistent logger

### 20.2 LoggingUtil — Structured Audit Logging

```java
// com.pingidentity.sdk.logging.LoggingUtil
// Thread-local audit logging API — integrates with PF's security audit log.

static void init();                           // begin audit context
static void log(String msg);                  // write audit entry
static void cleanup();                        // REQUIRED — releases thread-local state

// Contextual fields (call before log())
static void setStatus(String status);         // "success" or "failure"
static void setUserName(String username);
static void setPartnerId(String partnerId);
static void setEvent(String event);
static void setDescription(String description);
static void setHost(String host);
static void setRemoteAddress(String remoteAddress);
static void setProtocol(String protocol);
static void setRole(String role);
static void setAccessTokenJti(String jti);
static void setRequestJti(String jti);
static void setRequestStartTime(Long startTime);
static void updateResponseTime();
static String calculateHashForLogging(String value);  // SHA-1 → Base64URL
```

**Usage pattern:**

```java
@Override
public AuthnAdapterResponse lookupAuthN(HttpServletRequest req, ...) {
    LoggingUtil.init();
    LoggingUtil.setEvent("MY_ADAPTER_AUTHN");
    LoggingUtil.setRemoteAddress(req.getRemoteAddr());

    try {
        LoggingUtil.setUserName(username);
        LoggingUtil.setStatus(LoggingUtil.SUCCESS);
        LoggingUtil.log("Authentication successful for " + username);
    } catch (Exception e) {
        LoggingUtil.setStatus(LoggingUtil.FAILURE);
        LoggingUtil.log("Authentication failed");
    } finally {
        LoggingUtil.cleanup();  // REQUIRED — releases thread-local state
    }
}
```

> ⚠️ **Critical:** Always call `LoggingUtil.cleanup()` in a `finally` block.
> Failure to do so leaks thread-local state and corrupts audit records on that
> thread's next request.

### 20.3 Structured LogEvent Enum Pattern

For plugins with many log points, define a `LogEvent` enum with product-specific
codes for structured logging and log aggregation:

```java
public enum CustomLogEvent implements LogEvent {
    USER_NOT_AUTHENTICATED(LogLevel.ERROR, "000", "User arrived without prior auth."),
    ACCESS_TOKEN_EXCEPTION(LogLevel.WARN,  "003", "Token acquisition failed: %s"),
    AUTH_BYPASSED(LogLevel.DEBUG,           "006", "Auth bypassed per configuration."),
    RATE_LIMIT_EXCEEDED(LogLevel.ERROR,     "007", "Rate limit exceeded for %s");

    private static final String PRODUCT_CODE = "MYPLUGIN";
    private final LogLevel level;
    private final String code;
    private final String message;

    @Override
    public String getCode() { return PRODUCT_CODE + "-" + code; }
}

// Usage:
LOGGER.log(CustomLogEvent.ACCESS_TOKEN_EXCEPTION, exception);
```

**Pattern benefits:**
- Structured log codes (`MYPLUGIN-003`) enable log aggregation and alerting
- `LogLevel` enum provides compile-time level enforcement
- Format-string messages avoid string concatenation overhead
- Each plugin uses a unique product code prefix for filtering

---

## 21. Template Rendering & Error Localization

> **Tags:** `template`, `HTML`, `rendering`, `error`, `i18n`, `language-pack`, `AuthnErrorDetail`

### 21.1 TemplateRendererUtil

```java
// com.pingidentity.sdk.template.TemplateRendererUtil

static void render(HttpServletRequest req, HttpServletResponse resp,
    String templateFile, Map<String, Object> params)
    throws TemplateRendererUtilException;
```

### 21.2 Template Asset Locations

- HTML templates: `<pf_install>/server/default/conf/template/`
- Language packs: `<pf_install>/server/default/conf/language-packs/`
- Templates use PingFederate's Velocity-based rendering engine

### 21.3 Sample Usage

```java
Map<String, Object> params = new HashMap<>();
params.put("username", savedUsername);
params.put("action", req.getRequestURI());
params.put("extendedAttr", extendedAttrNames);
params.put("message", message);

TemplateRendererUtil.render(req, resp, "adapter.form.html", params);
```

### 21.4 Error Model

```java
// Error specification (declared once)
AuthnErrorDetailSpec INVALID_OTP = new AuthnErrorDetailSpec.Builder()
    .code("INVALID_OTP")
    .message("An invalid or expired OTP code was provided.")
    .userMessage("The code you entered is invalid or expired.")
    .build();

// Error instance (created on demand)
AuthnErrorDetail detail = INVALID_OTP.makeInstanceBuilder()
    .message("An invalid or expired OTP code was provided.")
    .build();

// Compose into AuthnError
AuthnError authnError = CommonErrorSpec.VALIDATION_ERROR.makeInstance();
authnError.setDetails(List.of(detail));
throw new AuthnErrorException(authnError);
```

### 21.5 User-Facing Messages

- `userMessage` field of `AuthnErrorDetail` is the **only** case where an API
  response includes user-facing text
- Localize `userMessage` using language packs and templates
- Define errors up front using `AuthnErrorSpec` / `AuthnErrorDetailSpec`, then
  construct instances on demand

### 21.6 Language Pack Support

- Place language pack properties files in
  `<pf_install>/server/default/conf/language-packs/`
- Reference via `TemplateRendererUtil` for HTML rendering
- For API responses, inject localized messages into `AuthnErrorDetail.userMessage`
- User message keys in `CommonErrorDetailSpec` map to entries in
  `authn-api-messages` resource bundles
- PF resolves these to localized strings via `AuthnApiSupport.getLocalizedMessage()`

---

## 22. Security Patterns

> **Tags:** `CSRF`, `cookies`, `secret`, `OAuth`, `reflection`, `validation`

### 22.1 CSRF Token Management

```java
// Store in session state
String csrfToken = UUID.randomUUID().toString();
sessionState.setAttribute("csrfToken", csrfToken, req, resp);

// Verify on submission
String submitted = req.getParameter("csrf_token");
String stored = (String) sessionState.getAttribute("csrfToken", req, resp);
if (!stored.equals(submitted)) {
    throw new AuthnAdapterException("CSRF validation failed");
}
```

### 22.2 Cookie Management Pattern

Adapters that manage persistent cookies should use instance-scoped cookie names:

```java
// Cookie name includes adapter ID to prevent cross-instance conflicts
this.cookieName = getAdapterCookieName("remember", config.getId());

// Cookie values are Base64-encoded for safety
private static String encodeCookieValue(String val) {
    return "B64:" + Base64.getEncoder()
        .encodeToString(val.getBytes(StandardCharsets.UTF_8));
}
private static String decodeCookieValue(String val) {
    if (val.startsWith("B64:")) {
        return new String(Base64.getDecoder().decode(val.substring(4)),
            StandardCharsets.UTF_8);
    }
    return val;  // legacy unencoded fallback
}
```

### 22.3 Secret Reference Integration

```java
String fieldValue = configuration.getFieldValue("apiKey");

if (SecretReferenceUtil.isSecretReference(fieldValue)) {
    // Format: "OBF:MGR:<secretManagerId>:<secretId>"
    SecretInfo secretInfo = SecretManagerAccessor.getSecretInfo(
        fieldValue, inParameters);
    String actualValue = secretInfo.getValue();
} else {
    String actualValue = fieldValue;  // plain-text (not recommended)
}

// In GUI descriptor — validate secret reference format:
field.addValidator(new SecretReferenceFieldValidator());
```

### 22.4 Reflective Accessor Pattern for Backward Compatibility

Plugins deployed across multiple PF versions may need to call internal
server classes that do not exist in all versions:

```java
public class ReflectiveServiceAccessor {
    private final Object targetInstance;
    private final Method getAccessToken;

    public ReflectiveServiceAccessor(String configValue) {
        // All lookups in constructor — fail-fast if class missing
        Class<?> clazz = Class.forName("com.pingidentity.access.SomeServerClass");
        Constructor<?> ctor = clazz.getConstructor(String.class);
        this.targetInstance = ctor.newInstance(configValue);
        this.getAccessToken = clazz.getMethod("getAccessToken");
    }

    public String getAccessToken() throws Throwable {
        try {
            return (String) getAccessToken.invoke(targetInstance);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();  // unwrap to real cause
        }
    }
}
```

A companion utility provides safe fallback when optional SDK fields are absent:

```java
public static <T> T getFieldValue(Class<?> clazz, String fieldName, T defaultValue) {
    try {
        Object fieldObj = clazz.getField(fieldName).get(null);
        if (defaultValue.getClass().isInstance(fieldObj)) return (T) fieldObj;
    } catch (NoSuchFieldException | IllegalAccessException e) { /* use default */ }
    return defaultValue;
}
```

### 22.5 Input Validation Best Practices

- Use `HashedTextFieldDescriptor` for sensitive config values (passwords, keys)
- Use `SecretReferenceFieldDescriptor` + Secret Manager for externalized secrets
- Validate and sanitize all form/API input before processing
- Set `HttpOnly`, `Secure`, `SameSite` on any custom cookies

---

# Part IV — Implementation Patterns

## 23. Core SDK Patterns

> **Tags:** `patterns`, `dual-path`, `chaining`, `contract`, `descriptor-caching`, `feedback-loop`

### 23.1 Descriptor Caching

**All samples** cache the descriptor instance as a final field:

```java
private AuthnAdapterDescriptor descriptor = initDescriptor();

@Override
public AuthnAdapterDescriptor getAdapterDescriptor() {
    return descriptor;
}
```

**Rule:** Return the **same descriptor object instance** on each call to
`getPluginDescriptor()` / `getAdapterDescriptor()`. Cache it in a field.

### 23.2 Dual-Path Rendering (Browser + API)

**Used by:** `template-render-adapter-example`, `authentication-selector-example`

```java
if (apiSupport.isApiRequest(req)) {
    // JSON path — deserialize model, validate, return state
    apiSupport.writeAuthnStateResponse(req, resp, authnState);
} else {
    // HTML path — render Velocity template
    templateRendererUtil.render(req, resp, "form.html", params);
}
```

### 23.3 Chained Attribute Consumption

**Used by:** `idp-adapter-example` (SampleSubnetAdapter)

```java
Map<String, Object> chainedAttrs = (Map<String, Object>)
    inParameters.get(IN_PARAMETER_NAME_CHAINED_ATTRIBUTES);
if (chainedAttrs != null) {
    // Use upstream identity to enrich/gate the current flow
}
```

### 23.4 Composite Adapter Chaining Mechanics

Composite adapters chain multiple sub-adapters using a state machine
stored in `TransactionalStateSupport`:

```java
// Adapter chaining with Required/Sufficient policy
// Each sub-adapter maps to a policy:
enum Policy { Required, Sufficient }

// On each invocation:
// 1. Load LoginState (current adapter index) from transactional state
// 2. Delegate to current sub-adapter
// 3. On SUCCESS:
//    a. If policy is Sufficient OR last adapter → return SUCCESS with merged attrs
//    b. Otherwise → increment index, return IN_PROGRESS, redirect to resume path
// 4. On FAILURE:
//    a. If policy is Required → return FAILURE (entire chain fails)
//    b. Otherwise → increment index, try next adapter

// Attribute merging:
allAttributesState.attributes.put(currentAdapterId,
    adapterResponse.getAttributeMap());

// Chained attributes passed to next adapter via inParameters:
inParameters.put("com.pingidentity.adapter.input.parameter.chained.attributes",
    allAttributesState.attributes);
```

### 23.5 AttributeValue Multi-Value Construction

`AttributeValue` supports multi-value attributes for tracking composite results:

```java
// Single value
new AttributeValue("myValue");

// Multi-value from List<String>
List<String> values = Arrays.asList("adapter1", "adapter2", "adapter3");
new AttributeValue(values);

// Reading multi-value
Collection<String> all = attributeValue.getValuesAsCollection();

// Single value read (returns first value)
String single = attributeValue.getValue();
```

### 23.6 CoreContract Enum: Type-Safe Attribute Contract

Define attribute contracts via an enum rather than raw strings:

```java
public enum CoreContract {
    SUBJECT("subject"),
    TRANSACTION_STATUS("transactionStatus"),
    FIRST_NAME("firstName"),
    BIRTH_DATE("birthDate"),
    NATIONAL_ID_NUMBER("nationalIdNumber");

    private static final String DATA_PREFIX = "/_embedded/data/0/";
    private final String value;

    // Map enum → JSON Pointer for API response extraction
    public String getPointer(String verificationType) {
        return DATA_PREFIX + value;
    }
}

// In getAdapterDescriptor() — generates contract from enum:
private Set<String> getContract() {
    return Arrays.stream(CoreContract.values())
        .map(CoreContract::toString)
        .collect(Collectors.toSet());
}
```

This pattern provides compile-time safety for attribute names and centralizes
the mapping between contract attributes and external API response structures.

### 23.7 Configuration-Time Side Effects

```java
@Override
public void configure(Configuration configuration) {
    username = configuration.getFieldValue("Username");
    password = configuration.getFieldValue("Password");
    // Parse policy constraints from config at init time
    minPasswordLength = configuration.getIntFieldValue("Minimum Password Length");
}
```

### 23.8 ConfigurationValidator Pattern

Plugins can implement thorough configuration validation using both
`FieldValidator` (per-field) and `ConfigurationValidator` (cross-field):

```java
// Cross-field ConfigurationValidator for table uniqueness
guiConfigDescriptor.addValidator(config -> {
    List<String> errors = new ArrayList<>();

    Table table = config.getTable("Authentication Sources");
    if (table.getRows().isEmpty()) {
        throw new ValidationException("Please add at least one source.");
    }

    Set<String> seen = new HashSet<>();
    for (Row row : table.getRows()) {
        String value = row.getFieldValue("Network Range (CIDR notation)");
        if (!seen.add(value)) {
            errors.add("Duplicate network: " + value);
        }
    }

    if (!errors.isEmpty()) {
        throw new ValidationException(errors);  // multi-error constructor
    }
});
```

> 💡 **Best practice:** `ValidationException` accepts a `List<String>` in its
> constructor, allowing multiple errors to be reported at once rather than
> failing on the first error.

### 23.9 Descriptor Metadata Flags

Plugins can set metadata flags on their descriptors to control PingFederate's
runtime behavior:

```java
Map<String, Boolean> metadata = new HashMap<>();
metadata.put("EarlyCreateAndConfigure", true);   // created at startup, not lazily
metadata.put("FipsStatus", PluginFipsStatus.COMPLIANT);
metadata.put("TryLookupAuthn", true);            // enables try-lookup phase
result.setMetadata(metadata);
```

| Key | Effect |
|-----|--------|
| `EarlyCreateAndConfigure` | Plugin is instantiated at PF startup instead of lazily |
| `FipsStatus` | Declares FIPS compliance level |
| `TryLookupAuthn` | Enables `inParameters` to include `try.lookup.authn` |

### 23.10 Optional Interface Composition

Extend core SPI functionality by implementing optional marker interfaces that
PF discovers dynamically:

```java
public class SamplePCV implements PasswordCredentialValidator,
    ChangeablePasswordCredential,        // password change
    ResettablePasswordCredential,        // password reset
    RecoverableUsername,                  // username recovery
    AccountUnlockablePasswordCredential  // account unlock
{
    // PF uses instanceof checks — implementing is sufficient
}
```

### 23.11 ActionDescriptor with Typed Parameters

Admin UI actions can accept user input via typed parameter fields:

```java
ActionDescriptor action = new ActionDescriptor(
    "Generate Secret Reference", "Generates a reference...",
    new ActionHandler());
TextFieldDescriptor param = new TextFieldDescriptor(
    "Variable Name", "Enter the name...");
param.addValidator(new RequiredFieldValidator());
action.addParameter(param);  // User fills this in before invoking action
guiDescriptor.addAction(action);

// Handler receives the typed parameter list
@Override
public String actionInvoked(Configuration config,
    SimpleFieldList actionParameters) {
    String value = actionParameters.getFieldValue("Variable Name");
    return "Result: " + value;
}
```

### 23.12 CAPTCHA Integration Pattern

CAPTCHA providers use a dual-path validation approach:

```java
// In CaptchaProvider implementation:
protected String getCaptchaResponse(CaptchaContext captchaContext) {
    HttpServletRequest request = captchaContext.getRequest();
    AuthnApiSupport apiSupport = AuthnApiSupport.getDefault();

    // API path: deserialize from JSON body
    if (apiSupport.isApiRequest(request) && request.getMethod().equals("POST")) {
        Map requestBody = apiSupport.deserializeAsMap(request);
        Object captchaResponse = requestBody.get("captchaResponse");
        if (captchaResponse instanceof String) return (String) captchaResponse;
        return null;
    }

    // Browser path: standard form parameter
    return request.getParameter("g-recaptcha-response");
}
```

### 23.13 TransactionAwareAuthenticationAdapter: Feedback Loop Pattern

Store correlation IDs during `lookupAuthN`, then retrieve and clean them up
in the transaction callbacks:

```java
public class RiskAwareAdapter
    implements IdpAuthenticationAdapterV2, AuthnApiPlugin,
               TransactionAwareAuthenticationAdapter {

    public void onTransactionComplete(HttpServletRequest request,
            HttpServletResponse response, Map<String, Object> authnIds,
            AttributeMap policyResult, Map<String, Object> inParameters) {
        sendFeedback(request, response, CompletionStatus.SUCCESS, inParameters);
    }

    public void onTransactionFailure(HttpServletRequest request,
            HttpServletResponse response, Map<String, Object> authnIds,
            Map<String, Object> inParameters) {
        sendFeedback(request, response, CompletionStatus.FAILED, inParameters);
    }

    private void sendFeedback(..., CompletionStatus status, ...) {
        String evalId = sessionWrapper
            .getEvaluationId(instanceId, request, response);
        sessionWrapper.removeEvaluationId(instanceId, request, response);
        if (StringUtils.isNotBlank(evalId)) {
            externalService.submitFeedback(evalId, trackingId,
                transactionId, status);
        }
    }
}
```

**Pattern:** Always clean up correlation state in both success and failure paths.

### 23.14 Service-Point Registration (Non-Admin Plugins)

Plugins that replace core PF services register via
`<pf_install>/pingfederate/server/default/conf/service-points.conf`:

```properties
# Replace the default access grant manager
access.grant.manager=com.pingidentity.accessgrant.SampleAccessGrant

# Replace the default client storage manager (requires companion entry)
client.storage.manager=com.pingidentity.clientstorage.SampleClientStorage
client.manager=org.sourceid.oauth20.domain.ClientManagerGenericImpl
```

> ⚠️ These plugins have **no admin UI** — they are configured entirely via
> `service-points.conf`. Server restart required after changes.

---

## 24. Resilience & External Integration

> **Tags:** `resilience`, `backoff`, `locking`, `handler`, `service-point`

### 24.1 Exponential Backoff for External API Calls

Plugins that call external APIs should implement thread-safe exponential backoff
with jitter:

```java
public class ExponentialBackOffCalculator {
    private static final double BASE_DELAY_MS = 1000.0;
    private static final long MAX_DELAY_MS = 7_200_000L;  // 2 hours
    private int retryAttemptNumber = 0;

    public synchronized long calculateRetryDelay() {
        SecureRandom rng = SecureRandom.getInstance("SHA1PRNG");
        long delay = (long)(Math.pow(2, retryAttemptNumber) * BASE_DELAY_MS
                      + rng.nextInt(1001));   // jitter 0-1000ms
        retryAttemptNumber++;
        return delay;
    }

    public boolean shouldRetry() {
        long projected = (long)(Math.pow(2, retryAttemptNumber) * BASE_DELAY_MS);
        return projected <= MAX_DELAY_MS;
    }

    public synchronized void resetBackOffEnforcement() {
        retryAttemptNumber = 0;
    }
}
```

**Usage in data source drivers and credential validators:**

```java
// In configure():
this.maxRetriesAttempt = 5;
this.errorCodeForRetries = Arrays.asList("429", "500", "503");

// At request time:
if (errorCodeForRetries.contains(String.valueOf(statusCode))) {
    if (backoffCalculator.shouldRetry() && attempt < maxRetriesAttempt) {
        Thread.sleep(backoffCalculator.calculateRetryDelay());
        // retry...
    }
}
```

### 24.2 LockingService: Account Lockout Integration

```java
// During configure():
this.accountLockingService = LockingServiceManager.getLockingService();

// During authentication:
LockingService.IsLockedRequest isLockedRequest =
    new LockingService.IsLockedRequest.Builder()
        .username(username)
        .failedLoginType(LockingService.FailedLoginType.PASSWORD)
        .build();

if (accountLockingService.isLocked(isLockedRequest)) {
    if (failAuthenticationOnAccountLockout) {
        return AUTHN_STATUS.FAILURE;
    }
}
```

### 24.3 Handler Registration for Custom URL Endpoints

Plugins that need to handle HTTP requests at custom URL paths register
servlet handlers during `configure()`:

```java
@Override
public void configure(Configuration configuration) {
    HandlerRegistry.registerHandler("/oob-auth/review",
        new OOBRequestForApprovalHandler());
    HandlerRegistry.registerHandler("/oob-auth/approve",
        new ApproveHandler());
}
```

> ⚠️ Handler paths are registered globally. Choose unique, plugin-scoped
> paths to avoid collisions with other plugins.

### 24.4 OAuth Context Consumption via `inParameters`

```java
// In lookupAuthN(), extract OAuth authorization context
String clientId = (String)
    inParameters.get(IN_PARAMETER_NAME_USERID_OAS_CLIENT_ID);
String[] requestedScopes = (String[])
    inParameters.get(IN_PARAMETER_NAME_USERID_OAS_REQUESTED_SCOPES);
Map<String, AuthorizationDetail> authzDetails = (Map<String, AuthorizationDetail>)
    inParameters.get(IN_PARAMETER_NAME_AUTHORIZATION_DETAILS);
```

---

# Part V — Reference

## 25. Runtime Service Accessors and Utilities

> **Tags:** `accessor`, `runtime`, `ServiceFactory`, `token`, `session`, `crypto`

PingFederate exposes its internal subsystems to SDK plugin code through a
**Service Accessor** pattern. Each accessor class uses `ServiceFactory` internally
to obtain the implementation — plugin code simply calls `newInstance()` or static
methods.

### 25.1 Service Accessor Classes

All accessors live in `com.pingidentity.access` and follow the same pattern:

```java
SomeAccessor accessor = SomeAccessor.newInstance();
accessor.getSomething();
```

| Accessor Class | Purpose | Key Methods |
|---------------|---------|-------------|
| `AccessGrantManagerAccessor` | OAuth access grant CRUD | `getAccessGrantManager()` |
| `AuthorizationDetailProcessorAccessor` | RAR comparison | `isEqualOrSubset(requested, approved, ctx)` |
| `BaseUrlAccessor` | Server base URL for callback/redirect URIs | `getCurrentBaseUrl()`, `getResumeUrl(resumePath)` |
| `CaptchaProviderAccessor` | CAPTCHA/risk provider lookup by ID | `getCaptchaProvider(captchaProviderId)` |
| `ClientAccessor` | OAuth client lookup | `getSpConnectionExtendedPropertyValues(entityId)` |
| `ClusterAccessor` | Cluster topology and node identity | `getNodeIndex()`, `getNodeTags()` |
| `ConnectionAccessor` | SP connection extended properties | `getSpConnectionExtendedPropertyValues(entityId)` |
| `DataSourceAccessor` | JDBC/LDAP data source lookup | `getLdapInfo(id)`, `getConnection(jndiName)`, `getCustomDataSourceDriver(id)` |
| `ExtendedPropertyAccessor` | Custom extended property definitions | `getExtendedPropertyDefinitions()` |
| `IdentityStoreProvisionerAccessor` | Identity store provisioner lookup | `getProvisioner(id)` |
| `JCEAccessor` | Encryption/decryption operations | AES via `SecretKeySpec` |
| `JwksEndpointKeyAccessor` | JWKS endpoint key management | `getCurrentRsaKey(alg)`, `getCurrentEcKey(curve)`, `getKeyById(kid)` |
| `KerberosRealmAccessor` | Kerberos realm configuration | `getKerberosRealm(id)`, `getKerberosRealmByName(name)` |
| `KeyAccessor` | Cryptographic key material | `getClientSslKeypair(alias)`, `getDsigKeypair(alias)` |
| `NotificationPublisherAccessor` | Notification publisher lookup | `getNotificationPublisher(id)` |
| `PasswordCredentialValidatorAccessor` | PCV instance lookup by ID | `getPasswordCredentialValidator(id)` |
| `PingOneEnvironmentAccessor` | PingOne connection credentials | `getEnvironmentId()`, `getAccessToken()` |
| `SecretManagerAccessor` | Secret retrieval | `getSecretInfo(secretReference, inParameters)` |
| `TrustedCAAccessor` | Trust anchors | `getAllTrustAnchors()` |
| `ClientSideAuthenticatorAccessor` | Client-side auth lookup | `getClientSideAuthenticator(id)` |

> ⚠️ **Runtime-only:** Accessor classes work only inside a running PingFederate
> server. Calling `newInstance()` in a test harness will fail because the
> `ServiceFactory` cannot resolve the backing service implementation.

### 25.2 SessionManager — Session Revocation API

```java
// com.pingidentity.sdk.session.SessionManager

SessionManager manager = new SessionManager();

manager.revokeAllSessionsFor(String uniqueUserKey);
manager.revokeOtherSessionsFor(String uniqueUserKey,
    HttpServletRequest req, HttpServletResponse resp);
manager.revoke(String sri);   // by Session Reference Identifier
```

> ℹ️ The SRI (Session Reference Identifier) is surfaced in adapter attributes
> since PF 11.3.

### 25.3 AccessTokenIssuer — Programmatic Token Issuance

```java
// com.pingidentity.sdk.oauth20.AccessTokenIssuer

static String issueToken(Map<String, Object> attributes, String scopeString,
    String clientId);

static String issueToken(Map<String, Object> attributes, String scopeString,
    String clientId, String accessTokenManagerId);

static IssuedAccessToken issue(Map<String, Object> attributes, String scopeString,
    String clientId, String accessTokenManagerId,
    AuthorizationDetails authorizationDetails);
```

**`IssuedAccessToken` fields:**

```java
String getTokenValue();   // the actual token string
String getTokenType();    // e.g., "Bearer"
Long   getExpiresAt();    // epoch millis
```

### 25.4 SecretReferenceUtil — Secret Reference Parsing

```java
// com.pingidentity.sdk.secretmanager.SecretReferenceUtil
// Format: OBF:MGR:{secretManagerId}:{secretId}

static boolean isSecretReference(String value);
static String  getSecretManagerId(String reference);
static String  getSecretId(String reference);
static boolean validateSecretManagerId(String reference);
```

### 25.5 Built-in FieldValidator Implementations

The SDK ships with ready-to-use `FieldValidator` implementations in
`org.sourceid.saml20.adapter.gui.validation.impl`:

| Validator | Purpose |
|-----------|---------|
| `RequiredFieldValidator` | Rejects blank/null values |
| `IntegerValidator` | Validates integer strings |
| `LongValidator` | Validates long integer strings |
| `DoubleValidator` | Validates double-precision floating point |
| `FloatValidator` | Validates single-precision floating point |
| `StringLengthValidator` | Enforces min/max string length |
| `RegExValidator` | Validates against a regular expression |
| `EmailValidator` | Validates email address format |
| `HostnameValidator` | Validates hostname format |
| `URLValidator` | Validates URL format (any scheme) |
| `HttpURLValidator` | Validates HTTP URL format |
| `HttpsURLValidator` | Validates HTTPS URL format |
| `JwksValidator` | Validates JWKS JSON structure |
| `SecretReferenceFieldValidator` | Validates `OBF:MGR:` secret reference format |
| `PingOneEnvironmentValidator` | Validates PingOne environment reference |
| `TableColumnValuesUniqueValidator`† | Enforces uniqueness across table rows |

†`TableColumnValuesUniqueValidator` implements `ConfigurationValidator`, not `FieldValidator`.

```java
// Usage:
FieldDescriptor connectionUrl = new TextFieldDescriptor(
    "Connection URL", "Target server URL");
connectionUrl.addValidator(new RequiredFieldValidator());
connectionUrl.addValidator(new HttpsURLValidator());
```

---

## 26. Enumerations, Constants & Data Types

> **Tags:** `enums`, `constants`, `AUTHN_STATUS`, `DynamicClient`, `FIPS`, `ClientAuthType`

### 26.1 AUTHN_STATUS Enum

```java
enum AUTHN_STATUS {
    SUCCESS,              // authentication complete, attributes populated
    IN_PROGRESS,          // adapter needs more interaction (sent redirect/form)
    FAILURE,              // authentication failed
    ACTION,               // adapter performing an action (e.g., template rendering)
    INTERACTION_REQUIRED  // non-interactive lookup attempted but interaction needed
}
```

### 26.2 AuthnAdapterResponse

```java
Map<String, Object> getAttributeMap();
AUTHN_STATUS        getAuthnStatus();
int                 getErrorCode();
String              getUsername();
String              getErrorMessage();
```

### 26.3 TransactionAwareAuthenticationAdapter Constants

```java
String PARAMETER_NAME_TRACKING_ID        = "com.pingidentity.adapter.input.parameter.tracking.id";
String PARAMETER_NAME_REQUEST_ID         = "com.pingidentity.adapter.input.parameter.request.id";
String PARAMETER_NAME_TRANSACTION_ID     = "com.pingidentity.adapter.input.parameter.transaction.id";
String PARAMETER_NAME_PARTNER_ENTITYID   = "com.pingidentity.adapter.input.parameter.partner.entityid";
String PARAMETER_NAME_OAUTH_CLIENT_ID    = "com.pingidentity.adapter.input.parameter.oauth.client.id";
String PARAMETER_NAME_SP_ADAPTER_ID      = "com.pingidentity.adapter.input.parameter.sp.adapter.id";
String PARAMETER_NAME_CHAINED_ATTRIBUTES = "com.pingidentity.adapter.input.parameter.chained.attributes";
```

### 26.4 SessionAwareAuthenticationAdapter

```java
boolean checkUseAuthenticationSession(HttpServletRequest req,
    HttpServletResponse resp, Map<String, Object> parameters,
    AuthenticationSession existingSession);
```

**`AuthenticationSession` fields:**

```java
AuthenticationSourceKey getAuthnSourceKey();
Map<String, Object>     getAttributeMap();      // immutable
long                    getCreationTimeMillis();
```

### 26.5 PostRegistrationSessionAwareAdapter

```java
Map<String, Object> authenticateUser(String userId, String adapterId,
    Map<String, Object> parameters);
```

### 26.6 DynamicClient Interface (Full Field Set)

The `DynamicClient` interface exposes the complete RFC 7591/7592 field set:

```java
// Identity
String getClientId();
String getName();                     void setName(String);
String getSoftwareStatement();

// Authentication
String getClientAuthenticationType(); Status setClientAuthenticationType(String);
String getSecret();                   void setSecret(String);
void   generateSecret(int length);

// Key material
String getJwksUrl();                  void setJwksUrl(String);
String getJwks();                     void setJwks(String);

// Redirect/grant/scope
List<String> getRedirectUris();       void setRedirectUris(List<String>);
Set<String>  getGrantTypes();         void setGrantTypes(Set<String>);
List<String> getScopes();             void setScopes(List<String>);

// Rich Authorization Requests (RAR)
List<String> getAllowedAuthorizationDetailsTypes();
void         setAllowedAuthorizationDetailsTypes(List<String>);

// ID Token, Introspection, JARM, UserInfo signing/encryption
// ... (extensive getter/setter pairs for all JWT algorithm configurations)

// CIBA
String  getCibaDeliveryMode();                void setCibaDeliveryMode(String);
String  getCibaNotificationEndpoint();        void setCibaNotificationEndpoint(String);
boolean isCibaSupportUserCode();              void setCibaSupportUserCode(boolean);

// PAR / DPoP
boolean isRequirePushedAuthorizationRequests();  void setRequirePushedAuthorizationRequests(boolean);
boolean isRequireDpop();                         void setRequireDpop(boolean);

// Logout
String       getBackChannelLogoutUri();        void setBackChannelLogoutUri(String);
String       getFrontChannelLogoutUri();       void setFrontChannelLogoutUri(String);
List<String> getPostLogoutRedirectUris();      void setPostLogoutRedirectUris(List<String>);

// Custom metadata (extensible key-value)
Set<String>  getClientMetadataKeys();
List<String> getClientMetadataValues(String key);
Status       addClientMetadataValues(String key, List<String> values);

// Status enum
enum Status { SUCCESS, FAILURE, INVALID_KEY, MULTI_VALUE_NOT_ALLOWED }
```

### 26.7 DynamicClientFields Enum

Maps RFC 7591/7592 field names to wire-format strings:

```java
CLIENT_ID("client_id"), CLIENT_NAME("client_name"), CLIENT_SECRET("client_secret"),
REDIRECT_URIS("redirect_uris"), GRANT_TYPES("grant_types"), SCOPE("scope"),
TOKEN_ENDPOINT_AUTH_METHOD("token_endpoint_auth_method"),
JWKS_URI, JWKS, LOGO_URI, RESPONSE_TYPES, SOFTWARE_STATEMENT,
BACKCHANNEL_TOKEN_DELIVERY_MODE, BACKCHANNEL_CLIENT_NOTIFICATION_ENDPOINT,
REQUIRE_PUSHED_AUTHORIZATION_REQUESTS, DPOP_BOUND_ACCESS_TOKENS,
// ... (complete set of ~30 fields)

String getName();   // returns the wire-format string
```

### 26.8 ClientRegistrationException

```java
ClientRegistrationException(Response.Status httpStatus, ErrorCode error, String description);

enum ErrorCode {
    resource_not_found, unauthorized, invalid_access_token, invalid_payload,
    internal_error, invalid_client_metadata, invalid_software_statement,
    unapproved_software_statement, invalid_redirect_uri
}
```

### 26.9 ClientAuthType Enum

Maps RFC 7591 `token_endpoint_auth_method` values to PF domain types:

```java
none                → NONE
client_secret_basic → SECRET
client_secret_post  → SECRET
tls_client_auth     → CLIENT_CERT
private_key_jwt     → PRIVATE_KEY_JWT
client_secret_jwt   → CLIENT_SECRET_JWT
```

### 26.10 Plugin Metadata

```java
// com.pingidentity.sdk.PluginMetadataKeys
EARLY_CREATE_AND_CONFIGURE            // plugin configured early at startup
PING_ONE_SERVICE_ASSOCIATION          // PingOne services association
FIPS_STATUS                           // plugin FIPS compliance
TRY_LOOKUP_AUTHN                      // try lookupAuthN even if no prior session
SUPPORTS_CLIENT_SIDE_AUTHENTICATION   // client-side auth (e.g., WebAuthn)

// com.pingidentity.sdk.PluginFipsStatus
enum PluginFipsStatus {
    NOT_ASSESSED("Not Assessed"),
    COMPLIANT("Compliant"),
    NOT_COMPLIANT("Not Compliant");
}

// com.pingidentity.sdk.PluginServiceAssociation — built-in display names
String DIRECTORY_SERVICE_DISPLAY_NAME = "Directory Service";
String RISK_SERVICE_DISPLAY_NAME      = "Risk Management Service";
String PROTECT_SERVICE_DISPLAY_NAME   = "Protect Service";
String MFA_SERVICE_DISPLAY_NAME       = "MFA Service";
String VERIFY_SERVICE_DISPLAY_NAME    = "Verify Service";
```

---

## 27. Hardening Checklist, Release Deltas & Sources

> **Tags:** `hardening`, `release`, `samples`, `sources`, `production`

### 27.1 Hardening Checklist

#### Logging

- [ ] Replace all `System.out` / `System.err` with a consistent logger
- [ ] Use commons-logging or SLF4J (PF ships logging infrastructure)
- [ ] Never log credentials, tokens, or PII at INFO level

#### Security

- [ ] Validate and sanitize all form/API input before processing
- [ ] Use `HashedTextFieldDescriptor` for sensitive config values
- [ ] Use `SecretReferenceFieldDescriptor` + Secret Manager for externalized secrets
- [ ] Implement CSRF protection for HTML form submissions
- [ ] Set `HttpOnly`, `Secure`, `SameSite` on any custom cookies

#### Clustering

- [ ] Ensure `configure()` state is purely derived from `Configuration`
- [ ] Use PingFederate Session Revocation Service (SRI) for cluster-wide logout
- [ ] Do not rely on node-local in-memory storage for cross-request state
- [ ] Use `SessionStateSupport` for session data — PF handles replication

#### Error Handling

- [ ] Handle `AuthnAdapterException`, `AuthnErrorException`, and `IOException`
- [ ] Return proper `AUTHN_STATUS` values even on error paths
- [ ] Use typed `AuthnErrorSpec` / `AuthnErrorDetailSpec` for API errors

#### Configuration

- [ ] Use `RequiredFieldValidator` for mandatory fields
- [ ] Use `IntegerValidator(min, max)` for bounded numeric fields
- [ ] Add `ConfigurationValidator` for cross-field constraints
- [ ] Set `setDefaultForLegacyConfig()` when adding new fields to existing plugins

#### Thread Safety

- [ ] All mutable state initialized in `configure()` only
- [ ] No shared mutable state across request-processing methods
- [ ] Use `ConcurrentHashMap` or similar if custom caching is needed

### 27.2 SDK-Relevant Release Deltas

| Version | SDK Change |
|---------|------------|
| 6.4 | `IdpAuthenticationAdapterV2` introduced (replaced older version) |
| 6.11 | `GuiConfigDescriptorBuilder` introduced; `getGuiConfigDescriptor()` deprecated |
| 7.3 | `AuthenticationSelector` introduced (replaced `AdapterSelector`) |
| 10.2 | `PluginDescriptor.getMetadata()` / `setMetadata()` added |
| 12.0+ | Session Revocation Service (SRI); `IN_PARAMETER_NAME_SRI` added |
| 13.0 | `AuthorizationDetailProcessor`; `SecretManager` SPI; `CaptchaProvider` SPI |

### 27.3 Full Sample Inventory

All samples are in `sdk/plugin-src/`:

| # | Directory | Plugin Type | Primary Class | SPI |
|---|-----------|-------------|---------------|-----|
| 1 | `idp-adapter-example` | IdP Adapter | `SampleSubnetAdapter` | `IdpAuthenticationAdapterV2` |
| 2 | `sp-adapter-example` | SP Adapter | `SpAuthnAdapterExample` | `SpAuthenticationAdapter` |
| 3 | `template-render-adapter-example` | IdP Adapter | `TemplateRenderAdapter` | `IdpAuthenticationAdapterV2` + `AuthnApiPlugin` |
| 4 | `authentication-selector-example` | Auth Selector | `SampleAuthenticationSelector` | `AuthenticationSelector` + `AuthnApiPlugin` |
| 5 | `custom-data-store-example` | Data Source | `SamplePropertiesDataStoreDriver` | `CustomDataSourceDriver` |
| 6 | `password-credential-validator-example` | PCV | `SamplePasswordCredentialValidator` | `PasswordCredentialValidator` + optional |
| 7 | `token-processor-example` | Token Processor | `SampleTokenProcessor` | `TokenProcessor<BinarySecurityToken>` |
| 8 | `token-generator-example` | Token Generator | `SampleTokenGenerator` | `TokenGenerator` |
| 9 | `notification-publisher-example` | Notification | `SampleNotificationPublisher` | `NotificationPublisherPlugin` |
| 10 | `ciba-auth-plugin-example` | CIBA/OOB Auth | `SampleOOBAuth` | `OOBAuthPlugin` |
| 11 | `secret-manager-example` | Secret Manager | `SampleSecretManager` | `SecretManager` |
| 12 | `dynamic-client-registration-example` | DCR | `SampleDynamicClientRegistration` | `DynamicClientRegistrationPlugin` |
| 13 | `access-grant-example` | Access Grant Mgr | `SampleAccessGrant` | `AccessGrantManager` |
| 14 | `client-storage-example` | Client Storage | `SampleClientStorage` | Client storage SPI |
| 15 | `identity-store-provisioner-example` | Provisioner | `SampleIdentityStoreProvisioner` | `IdentityStoreProvisionerWithFiltering` |
| 16 | `external-consent-page-example` | IdP Adapter | `ExternalConsentPageAdapter` | `IdpAuthenticationAdapterV2` |

### 27.4 Source Inventory

#### Official Documentation

| Source | Location |
|--------|----------|
| SDK Developer's Guide chapter | `docs/reference/vendor/pingfederate_server-13.0.txt` (lines ~82690–84430) |
| Authentication API chapter | `docs/reference/vendor/pingfederate_server-13.0.txt` (lines ~84430+) |

#### SDK Javadoc Packages Consulted

| Package | Coverage |
|---------|----------|
| `com.pingidentity.sdk` | Core interfaces: `ConfigurablePlugin`, `DescribablePlugin`, `PluginDescriptor`, `GuiConfigDescriptor` |
| `com.pingidentity.sdk.api.authn` | Authentication API: `AuthnApiPlugin`, `AuthnApiSupport`, `AuthnApiSpec`, `AuthnState`, `ActionSpec` |
| `com.pingidentity.sdk.accessgrant` | Access grant management |
| `com.pingidentity.sdk.captchaprovider` | CAPTCHA/risk provider |
| `com.pingidentity.sdk.logging` | Logging utilities |
| `com.pingidentity.sdk.notification` | Notification publisher |
| `com.pingidentity.sdk.oauth20` | Token management, registration |
| `com.pingidentity.sdk.oobauth` | CIBA / OOB authentication |
| `com.pingidentity.sdk.password` | Password credential validation |
| `com.pingidentity.sdk.provision` | Identity store provisioning |
| `com.pingidentity.sdk.secretmanager` | Secret manager |
| `com.pingidentity.sdk.session` | `SessionManager` utility |
| `com.pingidentity.sdk.template` | `TemplateRendererUtil` |
| `com.pingidentity.sdk.util` | SDK utilities |
| `org.sourceid.saml20.adapter` | Adapter base, attribute, conf, gui packages |
| `org.sourceid.saml20.adapter.state` | State support classes |
| `org.sourceid.saml20.service.session.data` | Session storage manager |
| `org.sourceid.websso.servlet.adapter` | `Handler`, `HandlerRegistry` |
| `org.sourceid.wstrust.plugin` | STS token processor/generator |
| `com.pingidentity.access` | Service Accessor classes |
| `com.pingidentity.sdk.account` | `AccountUnlockablePasswordCredential` |

#### Sample Source Files Reviewed

All 16 sample directories in `sdk/plugin-src/` — Java source files, build
configurations, and deployment descriptors.

---

*Guide generated from PingFederate 13.0.1 SDK Javadoc, official documentation,
and bundled sample projects. Last updated: 2026-02-16.*
