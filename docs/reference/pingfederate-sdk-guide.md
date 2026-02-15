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
| 1 | [SDK Layout and Build Pipeline](#1-sdk-layout-and-build-pipeline) | Directory layout, Ant targets, build properties, output paths | `SDK`, `Ant`, `build`, `deploy` |
| 2 | [Core Plugin Model](#2-core-plugin-model) | `ConfigurablePlugin`, describable plugins, descriptor contracts | `plugin`, `configurable`, `describable`, `lifecycle` |
| 3 | [Configuration API](#3-configuration-api) | Reading admin-entered values: fields, tables, typed accessors | `Configuration`, `Field`, `Table`, `FieldList` |
| 4 | [Descriptor and Admin UI](#4-descriptor-and-admin-ui) | Building admin UI: field descriptors, validators, tables, actions | `descriptor`, `GUI`, `FieldDescriptor`, `validation` |
| 5 | [Packaging and Discovery](#5-packaging-and-discovery) | PF-INF markers, manual packaging, deploy path | `PF-INF`, `packaging`, `deploy`, `JAR` |
| 6 | [Logging](#6-logging) | Logger patterns, sample realities | `logging`, `commons-logging`, `Log4j` |
| 7 | [IdP Adapter SPI](#7-idp-adapter-spi) | `IdpAuthenticationAdapterV2` full runtime contract | `IdP`, `adapter`, `lookupAuthN`, `logout` |
| 8 | [SP Adapter SPI](#8-sp-adapter-spi) | `SpAuthenticationAdapter` methods and flow semantics | `SP`, `adapter`, `createAuthN`, `SsoContext` |
| 9 | [Authentication Selector SPI](#9-authentication-selector-spi) | `selectContext`/`callback` runtime behavior | `selector`, `callback`, `cookie`, `routing` |
| 10 | [STS Token SPI](#10-sts-token-spi) | `TokenProcessor` and `TokenGenerator` contracts | `STS`, `token`, `WS-Trust`, `BinarySecurityToken` |
| 11 | [Custom Data Source SPI](#11-custom-data-source-spi) | `testConnection`, `getAvailableFields`, `retrieveValues` | `data-source`, `driver`, `filter`, `JDBC` |
| 12 | [Password Credential Validator SPI](#12-password-credential-validator-spi) | Validation, reset/change/recovery interfaces | `PCV`, `password`, `reset`, `change` |
| 13 | [Notification Publisher SPI](#13-notification-publisher-spi) | `publishNotification` contract and response mapping | `notification`, `publisher`, `HTTP`, `event` |
| 14 | [Authentication API-Capable Plugins](#14-authentication-api-capable-plugins) | `AuthnApiPlugin`, states/actions/models/errors, runtime handling | `API`, `AuthnApiPlugin`, `state`, `action` |
| 15 | [Session State and Transaction State](#15-session-state-and-transaction-state) | `SessionStateSupport`, `TransactionalStateSupport`, cleanup rules | `session`, `transaction`, `state`, `cleanup` |
| 16 | [Error Localization](#16-error-localization) | `AuthnErrorDetail`, `userMessage`, language packs | `error`, `localization`, `i18n`, `language-pack` |
| 17 | [Identity Store Provisioner SPI](#17-identity-store-provisioner-spi) | User/group CRUD, filtering, pagination, sort, deletion semantics | `provisioner`, `SCIM`, `CRUD`, `identity-store` |
| 18 | [Additional SDK Extension SPIs](#18-additional-sdk-extension-spis) | OOB/CIBA, Secret Manager, DCR, Access Grant, Client Storage | `CIBA`, `OOB`, `secret`, `DCR`, `grant` |
| 19 | [Template Rendering](#19-template-rendering) | `TemplateRendererUtil`, HTML templates, language bundles | `template`, `HTML`, `rendering`, `velocity` |
| 20 | [Thread Safety and Concurrency](#20-thread-safety-and-concurrency) | Init-time vs per-request state, server guarantees | `thread`, `concurrency`, `singleton` |
| 21 | [Deployment and Classloading](#21-deployment-and-classloading) | Plugin JAR isolation, deploy directory, classpath model | `deploy`, `classloader`, `classpath`, `JAR` |
| 22 | [Full Sample Inventory](#22-full-sample-inventory) | Every sample directory with concrete implementation learnings | `samples`, `examples`, `inventory` |
| 23 | [Cross-Sample Reusable Patterns](#23-cross-sample-reusable-patterns) | 10 practical patterns not obvious from interface docs | `patterns`, `dual-path`, `security`, `cleanup`, `HandlerRegistry`, `service-points` |
| 24 | [Runtime Service Accessors and Utilities](#24-runtime-service-accessors-and-utilities) | Service Accessor pattern, LoggingUtil, SessionManager, AccessTokenIssuer, ConfigCache | `accessor`, `runtime`, `ServiceFactory`, `audit`, `session`, `token` |
| 25 | [Adapter Lifecycle Interfaces, Constants, and Enumerations](#25-adapter-lifecycle-interfaces-constants-and-enumerations) | TransactionAware/SessionAware adapters, DynamicClient, FIPS, all enums | `lifecycle`, `transaction`, `session-aware`, `constants`, `enums`, `DynamicClient` |
| 26 | [Advanced Implementation Patterns](#26-advanced-implementation-patterns) | Production-hardened patterns: dual-path params, state management, authN flow control, CAPTCHA/risk integration, multi-state API plugins, backoff, CIBA/OOB, structured logging | `patterns`, `state`, `configuration`, `API`, `lifecycle`, `resilience`, `best-practice` |
| 27 | [Hardening Checklist](#27-hardening-checklist) | Production readiness deltas from sample code | `production`, `hardening`, `security`, `cluster` |
| 28 | [SDK-Relevant Release Deltas](#28-sdk-relevant-release-deltas) | Important SDK-related additions surfaced in docs | `release`, `delta`, `SRI`, `SessionManager` |
| 29 | [Build-Deploy Quick Start](#29-build-deploy-quick-start) | End-to-end plugin creation flow | `quickstart`, `tutorial`, `create`, `deploy` |
| 30 | [Source Inventory](#30-source-inventory) | Files reviewed for this guide | `sources`, `inventory`, `traceability` |

---

## 1. SDK Layout and Build Pipeline

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

## 2. Core Plugin Model

> **Tags:** `plugin`, `configurable`, `describable`, `lifecycle`, `ConfigurablePlugin`, `DescribablePlugin`

Official model distinguishes two core behaviors:

1. **Configurable** plugin behavior
2. **Describable** plugin behavior

### 2.1 ConfigurablePlugin Interface

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

### 2.2 DescribablePlugin Interface

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

### 2.3 PluginDescriptor

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

---

## 3. Configuration API

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

## 4. Descriptor and Admin UI

> **Tags:** `descriptor`, `GUI`, `FieldDescriptor`, `validation`, `ActionDescriptor`, `TableDescriptor`

The descriptor API tells PingFederate how to render the admin configuration
screen for your plugin. The primary container is `GuiConfigDescriptor`
(or its adapter subclass `AdapterConfigurationGuiDescriptor`).

### 4.1 GuiConfigDescriptor

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

### 4.2 FieldDescriptor Hierarchy

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

### 4.3 FieldDescriptor Base API

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

### 4.4 Common Field Constructors

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

### 4.5 TableDescriptor

```java
// org.sourceid.saml20.adapter.gui.TableDescriptor

TableDescriptor(String name, String description);
void addRowField(FieldDescriptor field);     // add column to table
void addValidator(RowValidator validator);   // row-level validation
```

### 4.6 ActionDescriptor

```java
// org.sourceid.saml20.adapter.gui.ActionDescriptor

// Standard action \u2014 link shown on admin UI
ActionDescriptor(String name, String description, ActionDescriptor.Action action);

// Download action \u2014 clicking triggers a file download instead of a page refresh
ActionDescriptor(String name, String description,
    String downloadContentType, String downloadFilename,
    ActionDescriptor.Action action);

// Add parameter fields \u2014 shown as inputs before the action fires
void addParameter(FieldDescriptor fieldDescriptor);
List<FieldDescriptor> getParameters();

// ActionDescriptor.Action (inner interface)
String actionInvoked(Configuration configuration);  // return string displayed to admin

// Overload with action parameters (default method)
default String actionInvoked(Configuration configuration,
    SimpleFieldList actionParameters);   // called when parameters are defined
```

> \u26a0\ufe0f **Parameters vs Configuration:** `actionInvoked(Configuration, SimpleFieldList)`
> receives both the current plugin configuration *and* the values entered
> into the action's parameter fields. Use `addParameter()` when you need
> admin input before executing the action (e.g., entering a test username).

### 4.7 Validation Interfaces

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

**Built-in validators** (in `org.sourceid.saml20.adapter.gui.validation.impl`):

- `RequiredFieldValidator` — field must not be empty
- `IntegerValidator` — field must be an integer
- `IntegerValidator(int min, int max)` — bounded integer

### 4.8 Complete Descriptor Example (from `SpAuthnAdapterExample`)

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

## 5. Packaging and Discovery

> **Tags:** `PF-INF`, `packaging`, `deploy`, `JAR`, `classloading`

### 5.1 Ant-based packaging (recommended)

The Ant build path automatically creates required discovery metadata and JAR layout.

**High-level flow:**

1. Set `target-plugin.name` in `build.local.properties`.
2. Place third-party JARs in `plugin-src/<plugin>/lib` if needed.
3. Run `ant deploy-plugin` or `clean-plugin` → `jar-plugin` → `deploy-plugin`.
4. Restart PingFederate.

> ⚠️ **Multi-project limitation:** You can develop source code for multiple
> projects simultaneously, but you can build and deploy **only one at a time**.
> Change `target-plugin.name` as needed.

### 5.2 Manual packaging rules

Manual builds must produce proper `PF-INF` markers at JAR root.

**Required classpath roots for manual compile:**

- `<pf_install>/pingfederate/server/default/lib`
- `<pf_install>/pingfederate/lib`
- `<pf_install>/pingfederate/sdk/lib`
- `<pf_install>/pingfederate/sdk/plugin-src/<subproject>/lib`

### PF-INF Marker Mapping

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

**Manual deployment destination:**

- Copy plugin JAR and dependent third-party JARs to
  `<pf_install>/pingfederate/server/default/deploy`
- Copy optional runtime config assets (templates, language packs) to
  `<pf_install>/pingfederate/server/default/conf`

---

## 6. Logging

> **Tags:** `logging`, `commons-logging`, `Log4j`, `System.out`

Official SDK chapter includes a dedicated logging note:

- Use normal Java logging patterns
- Samples commonly use Apache Commons Logging (`LogFactory`) or Log4j wrappers

**Sample reality:**

| Sample | Logger |
|--------|--------|
| `sp-adapter-example` | `commons-logging` (`LogFactory`) — structured usage |
| `idp-adapter-example` | `System.out` (demonstration only) |
| Most others | `commons-logging` |

> ⚠️ **Production rule:** Replace all `System.out` usage with a consistent
> logger. Use `commons-logging` or SLF4J (PingFederate ships logging
> infrastructure). Never use `System.out` in production plugins.

---

## 7. IdP Adapter SPI

> **Tags:** `IdP`, `adapter`, `lookupAuthN`, `logout`, `AuthnAdapterResponse`, `inParameters`

### Primary SPI

```java
// com.pingidentity.sdk.IdpAuthenticationAdapterV2
//   extends IdpAuthenticationAdapter
//   extends ConfigurableAuthnAdapter  (-> ConfigurablePlugin + getAdapterDescriptor)
```

### Core Methods

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

### 7.1 `lookupAuthN` Execution Semantics

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

**AuthnAdapterResponse status values:**

| Status | Meaning |
|--------|---------|
| `AUTHN_STATUS.SUCCESS` | Authentication complete — attributes in `attributeMap` |
| `AUTHN_STATUS.FAILURE` | Authentication denied |
| `AUTHN_STATUS.IN_PROGRESS` | Adapter wrote response, awaiting next invocation |

### 7.2 `inParameters` Map Keys

**Complete list from `IdpAuthenticationAdapterV2` fields (Javadoc):**

| Constant | Key | Description |
|----------|-----|-------------|
| `IN_PARAMETER_NAME_RESUME_PATH` | Resume path | URL to return to after async response |
| `IN_PARAMETER_NAME_PARTNER_ENTITYID` | Partner entity ID | SP entity ID (SP-initiated SSO) |
| `IN_PARAMETER_NAME_CHAINED_ATTRIBUTES` | Chained attributes | Merged attrs from previous auth sources |
| `IN_PARAMETER_NAME_ADAPTER_ACTION` | Adapter action | `PASSWORD_RESET`, `CHANGE_PASSWORD`, `EXTERNAL_CONSENT` |
| `IN_PARAMETER_NAME_AUTHN_POLICY` | Auth policy | Reauthentication policy context |
| `IN_PARAMETER_NAME_OAUTH_SCOPE` | OAuth scopes | Requested OAuth scopes |
| `IN_PARAMETER_NAME_OAUTH_SCOPE_DESCRIPTIONS` | Scope descriptions | Human-readable scope descriptions |
| `IN_PARAMETER_NAME_DEFAULT_SCOPE` | Default scope | Default scope description |
| `IN_PARAMETER_NAME_OAUTH_CLIENT_ID` | Client ID | OAuth client_id |
| `IN_PARAMETER_NAME_OAUTH_CLIENT_NAME` | Client name | OAuth client display name |
| `IN_PARAMETER_NAME_OAUTH_AUTHORIZATION_DETAILS` | Auth details | OAuth authorization details |
| `IN_PARAMETER_NAME_OAUTH_AUTHORIZATION_DETAIL_DESCRIPTIONS` | Detail descriptions | Human-readable detail descriptions |
| `IN_PARAMETER_NAME_INSTANCE_ID` | Instance ID | Adapter instance identifier |
| `IN_PARAMETER_NAME_SERVER_BASE_URL` | Server base URL | PF server base URL |
| `IN_PARAMETER_NAME_CURRENT_SERVER_BASE_URL` | Current server URL | Whitelisted domain from request |
| `IN_PARAMETER_NAME_APPLICATION_NAME` | App name | Application display name |
| `IN_PARAMETER_NAME_APPLICATION_ICON_URL` | App icon URL | Application icon/logo URL |
| `IN_PARAMETER_NAME_DEVICE_SHARING_TYPE` | Device sharing | `SHARED` or `PRIVATE` device indicator |
| `IN_PARAMETER_NAME_REQUEST_ID` | Request ID | Request identifier attribute |
| `IN_PARAMETER_NAME_SRI` | SRI | Session Reference ID (`pi.sri` value) |

### 7.3 Adapter Style Patterns from Samples

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

### 7.4 Session and Reauthentication Rules

**Official guidance:**

- Prefer PingFederate-managed authentication sessions over internal session tracking
- If maintaining custom state, use:
  - `SessionStateSupport` for global/session state
  - `TransactionalStateSupport` for transaction-scoped state
- **Always** remove transactional attributes before returning from `lookupAuthN()`
- For password reset/change flows, respect reauthentication policy in
  `IN_PARAMETER_NAME_AUTHN_POLICY` — do not reuse stale session state

### 7.5 Logout (`logoutAuthN`) Behavior

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

### Primary SPI

```java
// org.sourceid.saml20.adapter.sp.authn.SpAuthenticationAdapter
//   extends ConfigurableAuthnAdapter
```

### Core Methods

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

### Runtime Semantics

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

### Primary SPI

```java
// com.pingidentity.sdk.AuthenticationSelector
//   extends Plugin (-> ConfigurablePlugin + DescribablePlugin)
```

### Core Methods

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

### Runtime Semantics

- `selectContext` chooses authentication source (adapter, IdP connection, or
  context mapping)
- **Async pattern:** Committed response in `selectContext` pauses flow; browser
  must return to `resumePath`
- `callback` runs after selected auth source completes

> ⚠️ **Callback restriction (from official docs):** Do **not** write response
> body in `callback()`. Cookie writes are supported.

### `extraParameters` Keys

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

### Result Types

```java
// AuthenticationSelectorContext.ResultType
ADAPTER_ID    // return adapter instance ID
IDP_CONN_ID   // return IdP connection ID
CONTEXT       // return context string for context mapping
```

**`authentication-selector-example` behavior:**

- Prompts for email/domain, extracts domain, sets persistent cookie
- Supports API and non-API execution paths
- If no input exists, returns `null` after rendering response (still in progress)
- Defaults cookie name to `pf-authn-selector-<configuration-id>` when not configured
- Uses `AuthenticationSelectorDescriptor.setSupportsExtendedResults(true)`
- Sets `ResultType.CONTEXT` and returns domain string for context mapping

---

## 10. STS Token SPI

> **Tags:** `STS`, `token`, `WS-Trust`, `BinarySecurityToken`, `TokenProcessor`, `TokenGenerator`

### 10.1 Token Processor

```java
// org.sourceid.wstrust.plugin.process.TokenProcessor<T extends SecurityToken>

TokenContext processToken(T token);
```

**`token-processor-example` behavior:**

- Implements `TokenProcessor<BinarySecurityToken>`
- Decodes binary token payload as UTF-8 subject string
- Returns `TokenContext` with subject attributes map containing `subject`

### 10.2 Token Generator

```java
// org.sourceid.wstrust.plugin.generate.TokenGenerator

SecurityToken generateToken(TokenContext attributeContext);
```

**`token-generator-example` behavior:**

- Reads `subject` from `TokenContext`
- Builds `BinarySecurityToken` with token type `urn:sample:token`
- Encodes subject bytes to Base64 payload

---

## 11. Custom Data Source SPI

> **Tags:** `data-source`, `driver`, `filter`, `JDBC`, `CustomDataSourceDriver`

### Primary SPI

```java
// com.pingidentity.sources.CustomDataSourceDriver
//   extends ConfigurablePlugin

boolean testConnection();
List<String> getAvailableFields();
Map<String, Object> retrieveValues(
    Collection<String> attributeNamesToFill,
    SimpleFieldList filterConfiguration);
SourceDescriptor getSourceDescriptor();
void configure(Configuration configuration);
```

### Runtime Semantics

| Method | Contract |
|--------|----------|
| `testConnection()` | Gates admin progress — `false` blocks continuation |
| `getAvailableFields()` | Must return **at least one** field |
| `retrieveValues()` | Return map keyed by requested attribute names; return **empty map** when no match |

> ⚠️ **Filter substitution rule (from official docs):** Runtime attributes in
> admin-defined filters use `${attributeName}` syntax.

**`custom-data-store-example` behavior:**

- Uses properties-file directory as storage
- Filter key: `SamplePropertiesDataStore Username`
- Descriptor includes both configuration fields and filter fields
- Reads `<username>.properties` and maps requested fields
- Missing properties return empty string values

---

## 12. Password Credential Validator SPI

> **Tags:** `PCV`, `password`, `reset`, `change`, `recovery`, `PasswordCredentialValidator`

### Primary SPI

```java
// com.pingidentity.sdk.password.PasswordCredentialValidator

AttributeMap processPasswordCredential(String username, String password)
throws PasswordValidationException;
```

### Return Semantics

| Result | Meaning |
|--------|---------|
| Non-empty `AttributeMap` | Valid credential — map contains principal attributes |
| `null` or empty map | Invalid credential |
| `PasswordValidationException` | System/backend failure |

### Optional Interfaces

| Interface | Purpose |
|-----------|---------|
| `ChangeablePasswordCredential` | `changePassword()`, `isPasswordChangeable()`, `isChangePasswordEmailNotifiable()`, `isPendingPasswordExpiryNotifiable()` |
| `ResettablePasswordCredential` | `resetPassword()`, `isPasswordResettable()` |
| `RecoverableUsername` | `findUser()`, `findUsersByMail()`, attribute-name getters for recovery metadata |
| `AccountUnlockablePasswordCredential` | Account unlock for "Trouble Logging In?" flows |
| `ChallengeablePasswordCredential` | MFA challenge: `challengeCredential()` returns `PasswordChallengeResult`; throws `PasswordCredentialChallengeException` |
| `AttributeRetrievablePasswordCredential` | `retrieveAttributes(username)` — look up user attributes without validating a password |

**`password-credential-validator-example` behavior:**

- Stores a single credential pair in configuration for demonstration
- Supports password policy check on reset/change (min length, recoverable errors)
- Implements all optional interfaces

> ⚠️ **Sample caveat (explicitly documented in code):** Non-admin password
> updates are **not persisted/replicated** for cluster usage.

---

## 13. Notification Publisher SPI

> **Tags:** `notification`, `publisher`, `HTTP`, `event`, `PublishResult`

### Primary SPI

```java
// com.pingidentity.sdk.notification.NotificationPublisherPlugin

// Primary method — string-only data map
PublishResult publishNotification(
    String eventType,
    Map<String, String> data,
    Map<String, String> configuration);

// Object data variant — called when descriptor signals support (since 11.3)
default PublishResult publishNotificationWithObjectData(
    String eventType,
    Map<String, Object> data,    // <-- Object values, not String
    Map<String, String> configuration);

// Whether to include user attributes in notification payload (since 11.3.1)
default boolean acceptsUserAttributes();  // default: false
```

### Runtime Semantics

- Called when configured events trigger notifications
- Plugin returns `PublishResult` with notification status
- PF checks `NotificationSenderPluginDescriptor.getSupportsObjectData()` to
  decide whether to call `publishNotification()` or `publishNotificationWithObjectData()`
- If `acceptsUserAttributes()` returns `true`, PF includes authentication
  attributes in the data map for certain end-user flows

**`notification-publisher-example` behavior:**

- Descriptor type: `Sample HTTP Notification Publisher`
- Config field: `POST Endpoint`
- Builds JSON payload containing `eventType` and `data`
- Sends HTTP POST via Apache HTTP client
- Sets status `SUCCESS` on HTTP 200, otherwise `FAILURE`

---

## 14. Authentication API-Capable Plugins

> **Tags:** `API`, `AuthnApiPlugin`, `state`, `action`, `model`, `JSON`, `REST`

The Authentication API is PingFederate's REST interface that mirrors the
browser-based adapter/selector flow. Adapters/selectors can support both
browser (HTML) and API (JSON) request paths simultaneously.

### 14.1 Marker Interface

```java
// com.pingidentity.sdk.api.authn.AuthnApiPlugin

// Implemented by adapters/selectors that support the Authentication API.
// Key method:
AuthnApiSpec getApiSpec();
```

> ⚠️ **Non-interactive adapters:** If `getApiSpec()` returns `null`, the adapter
> is treated as non-interactive (no user input needed). The `interactive` flag
> on `AuthnApiSpec` controls whether PF expects user interaction.

### 14.2 AuthnApiSpec Structure

The spec defines the contract between your plugin and the Authentication API:

- **States** — Named conditions the adapter can be in (e.g. `USERNAME_PASSWORD_REQUIRED`)
- **Actions** — Operations the API client can invoke (e.g. `submitUserCredentials`)
- **Models** — Java POJOs describing the JSON body structure for states and actions
- **Errors** — Typed error specifications with user messages

### 14.3 States, Actions, and Models

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

### 14.4 Runtime Flow (Official docs)

For each RequestShift
1. Check if the incoming request is an API request: `apiSupport.isApiRequest(req)`
2. Check if a known action is being submitted: `ActionSpec.SUBMIT.isRequested(req)`
3. On API request, deserialize the model: `apiSupport.deserializeAsModel(req, Model.class)`
4. Perform validation (built-in from `@Schema` annotations, plus custom)
5. On validation error, throw `AuthnErrorException`
6. If no action is requested, render the response for the current state

### 14.5 AuthnApiSupport Singleton

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

### 14.6 Checking for Actions (Dual-path pattern)

```java
// From official docs — preferred approach
private boolean isSubmitRequest(HttpServletRequest req) {
    if (apiSupport.isApiRequest(req)) {
        return ActionSpec.SUBMIT_USER_ATTRIBUTES.isRequested(req);
    }
    return StringUtils.isNotBlank(req.getParameter("pf.submit"));
}
```

### 14.7 Extracting Models (Dual-path pattern)

```java
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

### 14.8 Handling Invalid Action IDs

```java
// Best practice from official docs
if (apiSupport.getActionId(req) != null) {
    // Unknown action — return error
    throw new AuthnErrorException(CommonErrorSpec.INVALID_ACTION_ID.makeInstance());
}
```

### 14.9 Sending API Responses

```java
// Build state response
UserAttributesRequired model = new UserAttributesRequired();
model.setAttributeNames(new ArrayList<>(extendedAttr));
AuthnState<UserAttributesRequired> authnState =
    apiSupport.makeAuthnState(req, StateSpec.USER_ATTRIBUTES_REQUIRED, model);
// Optionally: authnState.removeAction(...) to hide inapplicable actions
apiSupport.writeAuthnStateResponse(req, resp, authnState);
```

### 14.10 Error Handling

```java
try {
    // ... processing ...
} catch (AuthnErrorException e) {
    apiSupport.writeErrorResponse(req, resp, e.getValidationError());
    authnAdapterResponse.setAuthnStatus(AUTHN_STATUS.IN_PROGRESS);
    return authnAdapterResponse;
}
```

### 14.11 Authentication Statuses

| Status | When to return |
|--------|---------------|
| `AUTHN_STATUS.SUCCESS` | Authentication complete (API and non-API) |
| `AUTHN_STATUS.FAILURE` | Authentication denied (API and non-API) |
| `AUTHN_STATUS.IN_PROGRESS` | Response written, awaiting next invocation |

### 14.12 Content-Type Action Dispatch

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

### 14.13 Built-in Status Strings (CommonStatus)

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

### 14.14 Built-in Action IDs (CommonActionId)

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

### 14.15 Built-in Error Specs (CommonErrorSpec)

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

### 14.16 Built-in Error Detail Specs (CommonErrorDetailSpec)

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

## 15. Session State and Transaction State

> **Tags:** `session`, `transaction`, `state`, `cleanup`, `SessionStateSupport`, `TransactionalStateSupport`

### 15.1 State Support Interfaces

```java
// org.sourceid.saml20.adapter.state

// Session-scoped (HTTP session-like, survives across transactions)
SessionStateSupport            // getAttribute/setAttribute with HttpServletRequest
ApplicationSessionStateSupport // full application-session lifecycle

// Transaction-scoped (single SSO/SLO transaction, auto-cleared)
TransactionalStateSupport      // getAttribute/setAttribute, per-transaction

// Key-value persistence
KeyValueStateSupport           // HashMap-like key/value store

// Cleanup
SessionStateCleanable          // interface for session cleanup callbacks
```

### 15.2 SessionStateSupport

```java
// org.sourceid.saml20.adapter.state.SessionStateSupport

Object getAttribute(String name, HttpServletRequest req, HttpServletResponse resp);
Object removeAttribute(String name, HttpServletRequest req, HttpServletResponse resp);

void setAttribute(String name, Object value,
    HttpServletRequest req, HttpServletResponse resp, boolean usedAsLoginCtx);
```

> ⚠️ **The `usedAsLoginCtx` parameter:** When `true`, PF marks this adapter's
> session state as part of the "login context" — the session survives across
> protocol transactions and is available for re-authentication decisions. Set to
> `false` for temporary state that should be scoped to one authentication.

### 15.3 ApplicationSessionStateSupport

`ApplicationSessionStateSupport` extends `SessionStateSupport` with built-in
**inactivity timeout** management. It tracks `lastActivity` and `startActivity`
timestamps and provides automatic session invalidation:

```java
// Constructor — activityKey should be unique per adapter instance
ApplicationSessionStateSupport appState =
    new ApplicationSessionStateSupport(activityKey);

// Records activity timestamp (debounced — see below)
appState.recordActivityForSession(inactivityTimeout, req, resp);

// Check for timeout (returns true and cleans up if inactive > timeout)
boolean timedOut = appState.isInactiveFor(inactivityTimeout, req, resp);
```

**Performance insight:** Activity recording has a **30-second write debounce**
built in. If the last recorded activity is less than 30 seconds ago, the write
is skipped to avoid excessive session store traffic. This means adapters should
not rely on sub-30-second inactivity precision.

### 15.4 TransactionalStateSupport

```java
// org.sourceid.saml20.adapter.state.TransactionalStateSupport

// Constructor — resumePath is the adapter's callback endpoint path
TransactionalStateSupport(String resumePath);

Object getAttribute(String name, HttpServletRequest req, HttpServletResponse resp);
void   setAttribute(String name, Object value, HttpServletRequest req, HttpServletResponse resp);
Object removeAttribute(String name, HttpServletRequest req, HttpServletResponse resp);

// Transaction isolation
String  getTransactionalContextId();      // returns current context ID (null if none)
boolean hasTransactionalContextId();      // true if in an active transaction
```

> ⚠️ **Isolation mechanism:** `TransactionalStateSupport` internally appends
> the transactional context ID to attribute names using a delimiter. This
> ensures that concurrent SSO transactions to the same adapter don't share
> state. Always use the provided methods rather than raw session attributes.

### 15.5 Official Guidance

- **Prefer PingFederate-managed authentication sessions** over internal session
  tracking where possible
- Session state management for Authentication API adapters is **identical** to
  regular adapters — same `SessionStateSupport` / `TransactionalStateSupport`
- It should **not** be necessary to store more state attributes just to support
  the Authentication API
- **Always** clean up transactional state attributes before returning final
  status from `lookupAuthN()`
- PingFederate-managed authentication sessions mean many adapters no longer
  need to provide their own internal session tracking

### 15.6 Sample Usage (from samples)

**`authentication-selector-example`:**
- Uses `SessionStateSupport` to store persistent cookie value
- Attribute key: `DOMAIN_ATTR_NAME`

**`external-consent-page-example`:**
- Uses `SessionStateSupport` to store CSRF token and authorization-detail
  identifier map
- Removes all session state in cleanup path

---

## 16. Error Localization

> **Tags:** `error`, `localization`, `i18n`, `language-pack`, `AuthnErrorDetail`

### 16.1 Error Model

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

### 16.2 User-Facing Messages

- `userMessage` field of `AuthnErrorDetail` is the **only** case where an API
  response includes user-facing text
- Localize `userMessage` using language packs and templates
- Define errors up front using `AuthnErrorSpec` / `AuthnErrorDetailSpec`, then
  construct instances on demand

### 16.3 Language Pack Support

- Place language pack properties files in
  `<pf_install>/server/default/conf/language-packs/`
- Reference via `TemplateRendererUtil` for HTML rendering
- For API responses, inject localized messages into `AuthnErrorDetail.userMessage`

---

## 17. Identity Store Provisioner SPI

> **Tags:** `provisioner`, `SCIM`, `CRUD`, `identity-store`, `filtering`, `pagination`

### 17.1 Interface Hierarchy

```
IdentityStoreUserProvisioner   (deprecated — users only)
  └── IdentityStoreProvisioner     (users + groups)
       └── IdentityStoreProvisionerWithFiltering (users + groups + filtering/pagination)
```

> ⚠️ **Use `IdentityStoreProvisionerWithFiltering`** for any new implementation.
> The other two are deprecated or lack full feature support.

### 17.2 User CRUD Methods

```java
// Create
UserResponseContext createUser(CreateUserRequestContext ctx)
    throws IdentityStoreException;

// Read
UserResponseContext readUser(ReadUserRequestContext ctx)
    throws IdentityStoreException;

// Update
UserResponseContext updateUser(UpdateUserRequestContext ctx)
    throws IdentityStoreException;

// Delete
void deleteUser(DeleteUserRequestContext ctx)
    throws IdentityStoreException;
```

### 17.3 Group CRUD Methods

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

### 17.4 Deletion Semantics (Official docs)

> ⚠️ **Critical rule:** After deleting a user, the plugin:
>
> - **MUST** return `NotFoundException` for all `readUser()`, `updateUser()`,
>   and `deleteUser()` operations on the deleted ID
> - **MUST NOT** consider the deleted user in conflict calculation (i.e.,
>   `createUser()` with the same ID should **not** throw `ConflictException`)
> - The plugin may choose **not** to permanently delete the resource — soft
>   delete is allowed if these contracts hold

### 17.5 Filtering Interface (IdentityStoreProvisionerWithFiltering)

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

## 18. Additional SDK Extension SPIs

> **Tags:** `CIBA`, `OOB`, `secret`, `DCR`, `grant`, `client-storage`, `captcha`, `authorization-details`

### 18.1 CIBA / Out-of-Band Auth Plugin

```java
// com.pingidentity.sdk.oobauth.OOBAuthPlugin

// Initiate an out-of-band authentication request
OOBAuthTransactionContext initiate(OOBAuthRequestContext requestContext,
    Map<String, Object> inParameters)
    throws UnknownUserException, UserAuthBindingMessageException,
           OOBAuthGeneralException;

// Poll the status of an in-progress OOB transaction
OOBAuthResultContext check(String transactionIdentifier,
    Map<String, Object> inParameters) throws OOBAuthGeneralException;

// Called when transaction is complete — clean up state
void finished(String transactionIdentifier)
    throws OOBAuthGeneralException;
```

> ⚠️ **Lifecycle:** PF calls `initiate()` once, then polls `check()` repeatedly
> until the status indicates completion, then calls `finished()` for cleanup.
> `finished()` is best-effort — implementations must have their own resource
> bounds (e.g., expiration sweeping).

**`ciba-auth-plugin-example` (`SampleEmailAuthPlugin`) behavior:**

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

> ⚠️ **`KeyValueStateSupport` vs `SessionStateSupport`:** The CIBA example uses
> `KeyValueStateSupport` because the approval and polling requests come from
> *different* HTTP sessions (email link vs. polling client). Use
> `KeyValueStateSupport` when state must be shared across unrelated requests.

### 18.2 Secret Manager

```java
// com.pingidentity.sdk.secretmanager.SecretManager

// Retrieve secret value and optional attributes
SecretInfo getSecretInfo(String secretId, Map<String, Object> inParameters)
    throws SecretManagerException;

// Descriptor: use SecretManagerDescriptor (extends PluginDescriptor)
// Reference format: OBF:MGR:{secretManagerId}:{secretId}
```

**`secret-manager-example` (`SampleSecretManager`) behavior:**

- **Environment variable lookup:** Resolves `secretId` to environment variable
  name, returns `System.getenv(secretId)` wrapped in `SecretInfo`
- **ActionDescriptor with parameters:** Demonstrates creating admin UI actions
  that accept user input:
  ```java
  // Action with parameters — fields are passed to actionInvoked()
  ActionDescriptor generateAction = new ActionDescriptor(
      "Generate Secret Reference", "Generate the secret reference...",
      new SecretReferenceAction());
  TextFieldDescriptor envVarField = new TextFieldDescriptor(
      "Environment Variable", "Enter an environment variable...");
  envVarField.addValidator(new RequiredFieldValidator());
  generateAction.addParameter(envVarField);  // <-- action parameters
  guiDescriptor.addAction(generateAction);
  ```
- **ActionDescriptor.Action 2-arg overload:** 
  ```java
  // When actionInvoked(Configuration, SimpleFieldList) is overridden,
  // the single-arg version is never called
  @Override
  public String actionInvoked(Configuration configuration,
      SimpleFieldList actionParameters) {
      String envVar = actionParameters.getFieldValue("Environment Variable");
      return SECRET_REFERENCE_PREFIX + configuration.getId() + ":" + envVar;
  }
  ```
- **Secret reference utilities:** `SecretReferenceUtil.getSecretManagerId(ref)`
  and `SecretReferenceUtil.getSecretId(ref)` parse the `OBF:MGR:` format
- **Descriptor:** Uses `SecretManagerDescriptor` (not `PluginDescriptor`) with
  `VersionUtil.getVersion()` for versioned registration

### 18.3 Dynamic Client Registration Plugin

```java
// com.pingidentity.sdk.oauth20.registration.DynamicClientRegistrationPlugin

// All methods are `default` — override only the ones you need

default void processPlugin(HttpServletRequest request,
    HttpServletResponse response, DynamicClient dynamicClient,
    Map<String, Object> inParameters)
    throws ClientRegistrationException;  // since 9.0

default void processPluginUpdate(HttpServletRequest request,
    HttpServletResponse response, DynamicClient dynamicClient,
    DynamicClient existingDynamicClient, Map<String, Object> inParameters)
    throws ClientRegistrationException;  // since 10.1

default void processPluginGet(HttpServletRequest request,
    HttpServletResponse response, DynamicClient existingDynamicClient,
    Map<String, Object> inParameters)
    throws ClientRegistrationException;  // since 10.1

default void processPluginDelete(HttpServletRequest request,
    HttpServletResponse response, DynamicClient existingDynamicClient,
    Map<String, Object> inParameters)
    throws ClientRegistrationException;  // since 10.1
```

> ⚠️ **All methods are `default`:** The interface has no abstract methods.
> This allows you to override only the lifecycle hooks relevant to your
> validation logic.

**`dynamic-client-registration-example` (`SoftwareStatementValidatorPlugin`) behavior:**

- **JWT software statement validation:** Parses `software_statement` JWT from
  DCR request, verifies signature against configured JWKS or JWKS URI
- **Key resolver creation:** Supports both inline `JsonWebKeySet` and HTTPS-based
  `HttpsJwksVerificationKeyResolver` with trusted CA certificates from
  `TrustedCAAccessor.getAllTrustAnchors()`
- **Algorithm constraints:** Restricts to RS256/RS384/RS512 and ES256/ES384/ES512
- **Claim-to-client mapping:** Maps JWT claims to `DynamicClient` fields via
  `DynamicClientFields` enum (redirect_uris, grant_types, scope, CIBA fields,
  JARM fields, etc.)
- **Extended metadata:** Non-standard claims mapped via
  `dynamicClient.addClientMetadataValues(claimName, values)`
- **Update prevention:** `processPluginUpdate()` rejects requests that include
  `software_statement` (only allowed on initial registration)
- **Cross-field validation (lambda):** Uses `guiDescriptor.addValidator(config -> {...})`
  to enforce XOR constraint (exactly one of JWKS URL or inline JWKS required)
- **Descriptor:** Uses `DynamicClientRegistrationPluginDescriptor` with version
- **Error model:** Throws `ClientRegistrationException` with HTTP status,
  `ErrorCode` enum (e.g., `invalid_software_statement`, `invalid_payload`,
  `internal_error`), and description string

### 18.4 Access Grant Manager

```java
// com.pingidentity.sdk.accessgrant.AccessGrantManager

// Persist
void saveGrant(AccessGrant grant, AccessGrantAttributesHolder attributes)
    throws AccessGrantManagementException;

// Retrieval — abstract methods (must implement)
AccessGrant getByGuid(String guid) throws AccessGrantManagementException;
AccessGrant getByRefreshToken(String refreshToken)
    throws AccessGrantManagementException;
Collection<AccessGrant> getByClientId(String clientId)
    throws AccessGrantManagementException;
Collection<AccessGrant> getByUserKey(String userKey)
    throws AccessGrantManagementException;
AccessGrant getByUserKeyScopeClientIdGrantTypeContext(
    String userKey, Scope scope, String clientId,
    String grantType, String context) throws AccessGrantManagementException;

// Retrieval — default methods (override for optimized implementations)
default AccessGrant getByAccessGrantCriteria(AccessGrantCriteria criteria)
    throws AccessGrantManagementException;  // since 8.1
default Collection<AccessGrant> getByUserKeyClientIdGrantType(
    String userKey, String clientId, String grantType)
    throws AccessGrantManagementException;  // since 12.0

// Attributes
AccessGrantAttributesHolder getGrantAttributes(String grantGuid)
    throws AccessGrantManagementException;
void updateGrantAttributes(String grantGuid, AccessGrantAttributesHolder attrs)
    throws AccessGrantManagementException;

// Revocation
void revokeGrant(String guid) throws AccessGrantManagementException;
void revokeGrant(String userKey, String guid)
    throws AccessGrantManagementException;
default void deleteGrant(String guid)
    throws AccessGrantManagementException;  // since 12.0

// Maintenance
void deleteExpiredGrants();
void updateRefreshToken(AccessGrant grant)
    throws AccessGrantManagementException;
default void updateExpiry(AccessGrant grant)
    throws AccessGrantManagementException;  // since 12.0
boolean isDataSourceInUse(String datasourceId);
```

> ⚠️ **`revokeGrant` vs `deleteGrant`:** `revokeGrant()` deletes the grant AND
> may take further action (e.g., notifying token introspection services).
> `deleteGrant()` (since 12.0) is a pure storage delete with no side effects.
> Override `deleteGrant()` for cases where you want silent removal.

**`access-grant-example` (`SampleAccessGrant`) behavior:**

- Full in-memory grant storage (`HashMap<String, AccessGrantWithAttributes>`)
  keyed by GUID
- **Refresh-token lookup:** Hashes incoming token via `TokenUtil.digestToken()`
  before comparing against stored `getHashedRefreshTokenValue()`
- **Criteria-based retrieval:** Matches on userKey + scope + clientId +
  grantType + contextualQualifier + authorizationDetails (JSON equality)
- **Refresh token rotation:** `updateRefreshToken()` creates new `AccessGrant`
  with new hash, preserving all other fields and updating timestamp
- **Expiry update:** `updateExpiry()` creates new `AccessGrant` with new expires
  value and updated timestamp
- **Registration:** Configured via `service-points.conf` entry:
  `access.grant.manager=com.pingidentity.accessgrant.SampleAccessGrant`

> ⚠️ **No configuration UI:** `AccessGrantManager` has no `configure()` method.
> It is registered as a service-point implementation, not as an admin-configured
> plugin.

### 18.5 Client Storage Manager

```java
// com.pingidentity.sdk.oauth20.ClientStorageManagerV2 / ClientStorageManagerBase

// CRUD operations
ClientData getClient(String clientId) throws ClientStorageManagementException;
Collection<ClientData> getClients() throws ClientStorageManagementException;
void addClient(ClientData client) throws ClientStorageManagementException;
void updateClient(ClientData client) throws ClientStorageManagementException;
void deleteClient(String clientId) throws ClientStorageManagementException;

// Optional optimized search (override for performance)
Collection<ClientData> search(SearchCriteria searchCriteria)
    throws ClientStorageManagementException;

// Sort field constants (from ClientStorageManagerV2)
String CLIENT_ID = "clientId";
String CLIENT_NAME = "clientName";
String LAST_MODIFIED_DATE = "lastModifiedDate";
String CREATION_DATE = "creationDate";
```

**`client-storage-example` (`SampleClientStorage`) behavior:**

- Extends `ClientStorageManagerBase` (abstract base class with default `search()`
  implementation that calls `getClients()` and filters in memory)
- In-memory `HashMap<String, StoredClientData>` with inner struct holding
  `clientData` (serialized JSON), `clientName`, `clientLastModified`,
  `clientCreationTime`
- **Search with pagination and sorting:** Overrides `search()` for illustrative
  purposes; uses `SearchCriteria.getQuery()`, `getStartIndex()`,
  `getItemsRequested()`, `getOrderBy()` with custom `ClientComparator`
- **Registration:** Configured via two `service-points.conf` entries:
  ```
  client.storage.manager=com.pingidentity.clientstorage.SampleClientStorage
  client.manager=org.sourceid.oauth20.domain.ClientManagerGenericImpl
  ```

> ⚠️ **Admin-only method:** `search()` is only invoked by the admin console for
> UI pagination. Engine nodes at runtime use `getClient()` directly.

### 18.6 Authorization Detail Processor

```java
// com.pingidentity.sdk.authorizationdetails.AuthorizationDetailProcessor extends Plugin

AuthorizationDetailValidationResult validate(AuthorizationDetail, AuthorizationDetailContext, Map<String, Object>);
AuthorizationDetail enrich(AuthorizationDetail, AuthorizationDetailContext, Map<String, Object>);
String getUserConsentDescription(AuthorizationDetail, AuthorizationDetailContext, Map<String, Object>);
boolean isEqualOrSubset(AuthorizationDetail, AuthorizationDetail, AuthorizationDetailContext, Map<String, Object>);
```

**Official use case:** Custom processing of RFC 9396 authorization details types.
See [Section 25.14](#2514-authorizationdetailprocessor-spi) for full signatures.

### 18.7 Captcha/Risk Provider

```java
// com.pingidentity.sdk.captchaprovider.CaptchaProvider extends Plugin

String              getJavaScriptFileName();
Map<String, Object> getCaptchaAttributes(CaptchaContext context);
CaptchaResult       validateCaptcha(CaptchaContext context);
```

**Official use case:** Integration with custom CAPTCHA or risk-assessment services
during authentication flows. See [Section 25.13](#2513-captchaprovider-spi) for
full API including action constants and post-authentication callback.

---

## 19. Template Rendering

> **Tags:** `template`, `HTML`, `rendering`, `velocity`, `TemplateRendererUtil`

### 19.1 TemplateRendererUtil

```java
// com.pingidentity.sdk.template.TemplateRendererUtil

// Render HTML template to response
void render(HttpServletRequest req, HttpServletResponse resp,
    String templateName, Map<String, Object> params)
throws TemplateRendererUtilException;
```

### 19.2 Template Asset Locations

- Place custom HTML templates in
  `<pf_install>/server/default/conf/template/`
- Templates use PingFederate's Velocity-based rendering engine
- Language pack files go in
  `<pf_install>/server/default/conf/language-packs/`

### 19.3 Sample Usage (from `template-render-adapter-example`)

```java
Map<String, Object> params = new HashMap<>();
params.put("username", savedUsername);
params.put("action", req.getRequestURI());
params.put("extendedAttr", extendedAttrNames);
params.put("message", message);

TemplateRendererUtil.render(req, resp, "adapter.form.html", params);
```

---

## 20. Thread Safety and Concurrency

> **Tags:** `thread`, `concurrency`, `singleton`, `configure`

### 20.1 Official Guarantees (from Javadoc)

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

### 20.2 Practical Implications

| Phase | Thread Safety | Recommendation |
|-------|---------------|----------------|
| `configure()` | Single-threaded | Safe to initialize mutable state |
| `getPluginDescriptor()` | May be called multiple times | Return cached instance |
| `lookupAuthN()` / `selectContext()` | Multi-threaded | Use only immutable state from `configure()` |
| `logoutAuthN()` | Multi-threaded | Same as above |

> ⚠️ **Rule:** Store all configuration-derived values as **final or effectively
> immutable** fields during `configure()`. Do **not** share mutable state across
> requests unless properly synchronized.

### 20.3 SDK-Internal Thread Safety Patterns

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
- **`ApplicationSessionStateSupport`** uses a **30-second write debounce**
  (see §15.3) to reduce session store contention under high throughput

---

## 21. Deployment and Classloading

> **Tags:** `deploy`, `classloader`, `classpath`, `JAR`, `isolation`

### 21.1 Deployment Directory

All plugin JARs deploy to:
```
<pf_install>/pingfederate/server/default/deploy/
```

### 21.2 Plugin Discovery

- PingFederate scans the deploy directory at startup
- Discovers plugins via `PF-INF/` marker files inside JARs
- Each marker file lists FQCN of plugin implementations
- **Restart required** after deploying new/updated plugins

### 21.3 Third-Party Dependencies

- Place dependency JARs in your plugin project's `lib/` subdirectory
- The `deploy-plugin` Ant target copies them to the deploy directory alongside
  your plugin JAR
- Dependencies are on the server classpath — **no plugin-level classloader
  isolation** like OSGi

> ⚠️ **Dependency conflicts:** Since all plugins share the server classpath,
> conflicting library versions between plugins may cause runtime errors. Use
> shading/relocation for third-party libraries that conflict with PingFederate's
> own dependencies.

### 21.4 Configuration Assets

Optional runtime assets (templates, language packs, static resources) deploy to:
```
<pf_install>/pingfederate/server/default/conf/
```

---

## 22. Full Sample Inventory

> **Tags:** `samples`, `examples`, `inventory`

All samples are in `sdk/plugin-src/`:

| # | Directory | Plugin Type | Primary Class | SPI |
|---|-----------|-------------|---------------|-----|
| 1 | `idp-adapter-example` | IdP Adapter | `SampleSubnetAdapter` | `IdpAuthenticationAdapterV2` |
| 2 | `sp-adapter-example` | SP Adapter | `SpAuthnAdapterExample` | `SpAuthenticationAdapter` |
| 3 | `template-render-adapter-example` | IdP Adapter | `TemplateRenderAdapter` | `IdpAuthenticationAdapterV2` + `AuthnApiPlugin` |
| 4 | `authentication-selector-example` | Auth Selector | `SampleAuthenticationSelector` | `AuthenticationSelector` + `AuthnApiPlugin` |
| 5 | `custom-data-store-example` | Data Source | `SamplePropertiesDataStoreDriver` | `CustomDataSourceDriver` |
| 6 | `password-credential-validator-example` | PCV | `SamplePasswordCredentialValidator` | `PasswordCredentialValidator` + optional interfaces |
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

---

## 23. Cross-Sample Reusable Patterns

> **Tags:** `patterns`, `dual-path`, `security`, `cleanup`, `descriptor-caching`

### Pattern 1: Descriptor Caching

**All samples** cache the descriptor instance as a final field:

```java
private AuthnAdapterDescriptor descriptor = initDescriptor();

@Override
public AuthnAdapterDescriptor getAdapterDescriptor() {
    return descriptor;
}
```

### Pattern 2: Dual-Path Rendering (Browser + API)

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

### Pattern 3: CSRF Token Management

**Used by:** `external-consent-page-example`

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

### Pattern 4: Chained Attribute Consumption

**Used by:** `idp-adapter-example` (SampleSubnetAdapter)

```java
Map<String, Object> chainedAttrs = (Map<String, Object>)
    inParameters.get(IN_PARAMETER_NAME_CHAINED_ATTRIBUTES);
if (chainedAttrs != null) {
    // Use upstream identity to enrich/gate the current flow
}
```

### Pattern 5: Configuration-Time Side Effects

**Used by:** `password-credential-validator-example`

```java
@Override
public void configure(Configuration configuration) {
    username = configuration.getFieldValue("Username");
    password = configuration.getFieldValue("Password");
    // Parse policy constraints from config at init time
    minPasswordLength = configuration.getIntFieldValue("Minimum Password Length");
}
```

### Pattern 6: Handler Registration for Custom URL Endpoints

**Used by:** `ciba-auth-plugin-example`

Plugins that need to handle HTTP requests at custom URL paths (e.g., approval
pages, webhook callbacks) register servlet handlers during `configure()`:

```java
@Override
public void configure(Configuration configuration) {
    // ... standard config ...
    HandlerRegistry.registerHandler("/oob-auth/review",
        new OOBRequestForApprovalHandler());
    HandlerRegistry.registerHandler("/oob-auth/approve",
        new ApproveHandler());
}
```

> ⚠️ Handler paths are registered globally. Choose unique, plugin-scoped
> paths to avoid collisions with other plugins.

### Pattern 7: OAuth Context Consumption via `inParameters`

**Used by:** `external-consent-page-example`, `idp-adapter-example`

```java
// In lookupAuthN(), extract OAuth authorization context
String clientId = (String)
    inParameters.get(IN_PARAMETER_NAME_USERID_OAS_CLIENT_ID);
String[] requestedScopes = (String[])
    inParameters.get(IN_PARAMETER_NAME_USERID_OAS_REQUESTED_SCOPES);
String[] requestedConsentScopes = (String[])
    inParameters.get(IN_PARAMETER_NAME_USERID_OAS_REQUESTED_CONSENT_SCOPES);
Map<String, AuthorizationDetail> authzDetails = (Map<String, AuthorizationDetail>)
    inParameters.get(IN_PARAMETER_NAME_AUTHORIZATION_DETAILS);
Boolean isExtConsentAdapter = (Boolean)
    inParameters.get(ADAPTER_INFO_EXTERNAL_CONSENT_ADAPTER);
```

### Pattern 8: Service-Point Registration (Non-Admin Plugins)

**Used by:** `access-grant-example`, `client-storage-example`

Plugins that replace core PF services (not admin-configured plugins) register
via `<pf_install>/pingfederate/server/default/conf/service-points.conf`:

```properties
# Replace the default access grant manager
access.grant.manager=com.pingidentity.accessgrant.SampleAccessGrant

# Replace the default client storage manager (requires companion entry)
client.storage.manager=com.pingidentity.clientstorage.SampleClientStorage
client.manager=org.sourceid.oauth20.domain.ClientManagerGenericImpl
```

> ⚠️ These plugins have **no admin UI** — they are configured entirely via
> `service-points.conf`. They implement `configure()` without
> `PluginDescriptor`. Server restart required after changes.

### Pattern 9: ActionDescriptor with Typed Parameters

**Used by:** `secret-manager-example`, `ciba-auth-plugin-example`

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

### Pattern 10: Optional Interface Composition

**Used by:** `password-credential-validator-example`

Extend core SPI functionality by implementing optional marker interfaces that
PF discovers dynamically:

```java
// Base requirement
public class SamplePCV implements PasswordCredentialValidator,
    // Optional extensions — PF detects these at runtime
    ChangeablePasswordCredential,        // password change
    ResettablePasswordCredential,        // password reset
    RecoverableUsername,                  // username recovery
    AccountUnlockablePasswordCredential  // account unlock
{
    @Override
    public boolean isPasswordChangeable() { return true; }
    // PF only calls changePassword() if this returns true

    @Override
    public boolean isPasswordResettable() { return true; }
    // PF only calls resetPassword() if this returns true
}
```

> ⚠️ **Discoverable at runtime:** PF uses `instanceof` checks on your PCV
> instance. Implementing the interface is sufficient — no registration needed.

---

## 24. Runtime Service Accessors and Utilities

> **Tags:** `accessor`, `runtime`, `ServiceFactory`, `audit`, `session`, `token`, `crypto`

PingFederate exposes its internal subsystems to SDK plugin code through a
**Service Accessor** pattern. Each accessor class uses `ServiceFactory` internally
to obtain the implementation — plugin code simply calls `newInstance()` or static
methods.

### 24.1 Service Accessor Classes

All accessors live in `com.pingidentity.access` and follow the same pattern:

```java
// Every Accessor follows this pattern:
SomeAccessor accessor = SomeAccessor.newInstance();
accessor.getSomething();
```

| Accessor Class | Purpose | Key Methods |
|---------------|---------|-------------|
| `AccessGrantManagerAccessor` | OAuth access grant CRUD operations | `getAccessGrantManager()` |
| `AuthorizationDetailProcessorAccessor` | Rich Authorization Requests (RAR) comparison | `isEqualOrSubset(requested, approved, ctx)` |
| `BaseUrlAccessor` | Server base URL for constructing callback/redirect URIs | `getCurrentBaseUrl()`, `getResumeUrl(resumePath)` |
| `CaptchaProviderAccessor` | Look up configured CAPTCHA/risk providers by ID | `getCaptchaProvider(captchaProviderId)` |
| `ClientAccessor` | OAuth client lookup by entity ID | `getSpConnectionExtendedPropertyValues(entityId)` |
| `ClusterAccessor` | Cluster topology and node identity | `getNodeIndex()`, `getNodeTags()` |
| `ConnectionAccessor` | SP connection extended properties lookup | `getSpConnectionExtendedPropertyValues(entityId)` |
| `DataSourceAccessor` | JDBC/LDAP data source lookup and custom driver retrieval | `getLdapInfo(id)`, `getConnection(jndiName)`, `getCustomDataSourceDriver(id)` |
| `ExtendedPropertyAccessor` | Custom extended property definitions | `getExtendedPropertyDefinitions()` |
| `IdentityStoreProvisionerAccessor` | Identity store provisioner lookup | `getProvisioner(id)` |
| `JCEAccessor` | Java Cryptography Extension — encryption/decryption operations | AES encrypt/decrypt via `SecretKeySpec` |
| `JwksEndpointKeyAccessor` | JWKS endpoint key management — signing and encryption keys | `getCurrentRsaKey(alg)`, `getCurrentEcKey(curve)`, `getKeyById(kid)`, `getSigningJsonWebKeySet()` |
| `KerberosRealmAccessor` | Kerberos realm configuration lookup | `getKerberosRealm(id)`, `getKerberosRealmByName(name)` |
| `KeyAccessor` | Cryptographic key material (signing, SSL, encryption) | `getClientSslKeypair(alias)`, `getDsigKeypair(alias)`, `getEncryptionCertificate(alias)` |
| `NotificationPublisherAccessor` | Notification publisher plugin lookup | `getNotificationPublisher(id)` |
| `PasswordCredentialValidatorAccessor` | PCV instance lookup by ID | `getPasswordCredentialValidator(id)` |
| `PingOneEnvironmentAccessor` | PingOne connection environment credentials | `getEnvironmentId()`, `getAccessToken()`, `getManagementEndpoint()` |
| `SecretManagerAccessor` | Secret Manager instance resolution and secret retrieval | `getSecretInfo(secretReference, inParameters)` |
| `TrustedCAAccessor` | Trusted Certificate Authority trust anchors | `getAllTrustAnchors()` |
| `ClientSideAuthenticatorAccessor`† | Client-side authenticator lookup (package: `com.pingidentity.sdk.clientauth`) | `getClientSideAuthenticator(id)` |

> ⚠️ **Runtime-only:** Accessor classes work only inside a running PingFederate
> server. Calling `newInstance()` in a test harness or standalone JAR will fail
> because the `ServiceFactory` cannot resolve the backing service implementation.

### 24.2 LoggingUtil — Structured Audit Logging

```java
// com.pingidentity.sdk.logging.LoggingUtil
// Static methods for writing to PingFederate's audit log.

// Constants
String SUCCESS = "success";
String FAILURE = "failure";

// Write an audit entry
static void log(String msg);               // sets description if blank, updates response time

// Set contextual fields (call before log())
static void setStatus(String status);       // "success" or "failure"
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

// Lifecycle
static void init();
static void cleanup();

// Utility
static String getEvent();
static String calculateHashForLogging(String value);  // SHA-1 → Base64URL
```

**Usage pattern:**

```java
LoggingUtil.setStatus(LoggingUtil.SUCCESS);
LoggingUtil.setUserName(username);
LoggingUtil.setEvent("ADAPTER_AUTHN");
LoggingUtil.log("User authenticated successfully");
```

### 24.3 SessionManager — Session Revocation API

```java
// com.pingidentity.sdk.session.SessionManager

SessionManager manager = new SessionManager();

// Revoke all sessions for a user
manager.revokeAllSessionsFor(String uniqueUserKey);

// Revoke other sessions (keep current session alive)
manager.revokeOtherSessionsFor(String uniqueUserKey,
    HttpServletRequest req, HttpServletResponse resp);

// Revoke a specific session by its Session Reference Identifier (SRI)
manager.revoke(String sri);   // throws IllegalArgumentException if null/empty
```

> ℹ️ The SRI (Session Reference Identifier) is a value surfaced in adapter
> attributes since PF 11.3 (see Section 28: SDK-Relevant Release Deltas).

### 24.4 AccessTokenIssuer — Programmatic Token Issuance

```java
// com.pingidentity.sdk.oauth20.AccessTokenIssuer
// Issue OAuth 2.0 access tokens from within adapter/plugin code.

// Basic (uses default ATM)
static String issueToken(Map<String, Object> attributes, String scopeString,
    String clientId);

// With explicit Access Token Manager ID
static String issueToken(Map<String, Object> attributes, String scopeString,
    String clientId, String accessTokenManagerId);

// Full form returning IssuedAccessToken with metadata
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

### 24.5 ConfigCache — Thread-Safe Configuration Caching

```java
// com.pingidentity.sdk.util.ConfigCache<K, V>
// Thread-safe cache using ConcurrentHashMap with functional initialization.

ConfigCache<KeyType, ValType> cache = new ConfigCache<>(key -> expensiveInit(key));
ValType value = cache.get(key);

// Explicit invalidation
cache.invalidate(key);
cache.invalidateAll();
```

### 24.6 SecretReferenceUtil — Secret Reference Parsing

```java
// com.pingidentity.sdk.secretmanager.SecretReferenceUtil
// Format: OBF:MGR:{secretManagerId}:{secretId}

static boolean isSecretReference(String value);         // starts with "OBF:MGR:"
static String  getSecretManagerId(String reference);    // extracts manager ID
static String  getSecretId(String reference);           // extracts secret ID
static boolean validateSecretManagerId(String reference); // validates manager exists
```

### 24.7 TemplateRendererUtil

```java
// com.pingidentity.sdk.template.TemplateRendererUtil
// Render Velocity-based HTML templates from within plugin code.

static void render(HttpServletRequest req, HttpServletResponse resp,
    String templateFile, Map<String, Object> params)
    throws TemplateRendererUtilException;
```

---

## 25. Adapter Lifecycle Interfaces, Constants, and Enumerations

> **Tags:** `lifecycle`, `transaction`, `session-aware`, `constants`, `enums`, `DynamicClient`, `FIPS`

### 25.1 TransactionAwareAuthenticationAdapter

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

### 25.2 SessionAwareAuthenticationAdapter

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

### 25.3 PostRegistrationSessionAwareAdapter

For adapters that need to perform post-registration authentication.

```java
// com.pingidentity.sdk.PostRegistrationSessionAwareAdapter

Map<String, Object> authenticateUser(String userId, String adapterId,
    Map<String, Object> parameters);
```

### 25.4 AuthnAdapterResponse

Return type for IDP adapter `lookupAuthN()` callbacks.

```java
// com.pingidentity.sdk.AuthnAdapterResponse

Map<String, Object> getAttributeMap();
AUTHN_STATUS        getAuthnStatus();
int                 getErrorCode();
String              getUsername();
String              getErrorMessage();

// Status enum
enum AUTHN_STATUS {
    SUCCESS,              // authentication complete, attributes populated
    IN_PROGRESS,          // adapter needs more interaction (sent redirect/form)
    FAILURE,              // authentication failed
    ACTION,               // adapter is performing an action (e.g., template rendering)
    INTERACTION_REQUIRED  // similar to IN_PROGRESS, explicit interaction needed
}
```

### 25.5 DynamicClient Interface (Full Field Set)

The `DynamicClient` interface passed to `DynamicClientRegistrationPlugin`
methods exposes the complete RFC 7591/7592 field set:

```java
// com.pingidentity.sdk.oauth20.registration.DynamicClient

// Identity
String getClientId();
String getName();                     void setName(String);
String getSoftwareStatement();

// Authentication
String getClientAuthenticationType(); Status setClientAuthenticationType(String);
String getSecret();                   void setSecret(String);
void   generateSecret(int length);
String getClientCertIssuerDn();       void setClientCertIssuerDn(String);
String getClientCertSubjectDn();      void setClientCertSubjectDn(String);
String getTokenEndpointAuthSigningAlgorithm();
void   setTokenEndpointAuthSigningAlgorithm(String);

// Key material
String getJwksUrl();                  void setJwksUrl(String);
String getJwks();                     void setJwks(String);

// Redirect/grant/scope
List<String> getRedirectUris();       void setRedirectUris(List<String>);
Set<String>  getGrantTypes();         void setGrantTypes(Set<String>);
List<String> getScopes();             void setScopes(List<String>);
List<String> getRestrictedResponseTypes(); void setRestrictedResponseTypes(List<String>);

// Rich Authorization Requests (RAR)
List<String> getAllowedAuthorizationDetailsTypes();
void         setAllowedAuthorizationDetailsTypes(List<String>);

// ID Token signing/encryption
String getIdTokenSigningAlgorithm();               void setIdTokenSigningAlgorithm(String);
String getIdTokenEncryptionAlgorithm();            void setIdTokenEncryptionAlgorithm(String);
String getIdTokenContentEncryptionAlgorithm();     void setIdTokenContentEncryptionAlgorithm(String);

// Introspection JWT
String getIntrospectionSigningAlgorithm();             void setIntrospectionSigningAlgorithm(String);
String getIntrospectionEncryptionAlgorithm();          void setIntrospectionEncryptionAlgorithm(String);
String getIntrospectionContentEncryptionAlgorithm();   void setIntrospectionContentEncryptionAlgorithm(String);

// JARM (JWT Authorization Response Mode)
String getAuthorizationResponseSigningAlgorithm();             void setAuthorizationResponseSigningAlgorithm(String);
String getAuthorizationResponseEncryptionAlgorithm();          void setAuthorizationResponseEncryptionAlgorithm(String);
String getAuthorizationResponseContentEncryptionAlgorithm();   void setAuthorizationResponseContentEncryptionAlgorithm(String);

// UserInfo
String getUserInfoResponseSigningAlgorithm();             void setUserInfoResponseSigningAlgorithm(String);
String getUserInfoResponseEncryptionAlgorithm();          void setUserInfoResponseEncryptionAlgorithm(String);
String getUserInfoResponseContentEncryptionAlgorithm();   void setUserInfoResponseContentEncryptionAlgorithm(String);

// CIBA
String  getCibaDeliveryMode();                            void setCibaDeliveryMode(String);
String  getCibaNotificationEndpoint();                    void setCibaNotificationEndpoint(String);
boolean isCibaSupportUserCode();                          void setCibaSupportUserCode(boolean);
String  getCibaRequestObjectSigningAlgorithm();           void setCibaRequestObjectSigningAlgorithm(String);

// PAR / DPoP
boolean isRequirePushedAuthorizationRequests();    void setRequirePushedAuthorizationRequests(boolean);
boolean isRequireSignedRequests();
String  getRequestObjectSigningAlgorithm();        void setRequestObjectSigningAlgorithm(String);
boolean isRequireDpop();                           void setRequireDpop(boolean);

// Pairwise
boolean isPairwiseUserType();                      void setPairwiseUserType(boolean);
String  getSectorIdentifierUri();                  void setSectorIdentifierUri(String);

// Logout
String       getBackChannelLogoutUri();            void setBackChannelLogoutUri(String);
String       getFrontChannelLogoutUri();           void setFrontChannelLogoutUri(String);
List<String> getPostLogoutRedirectUris();          void setPostLogoutRedirectUris(List<String>);

// Display
String getLogoUrl();                               void setLogoUrl(String);
void   setDescription(String);

// Custom metadata (extensible key-value)
Set<String>  getClientMetadataKeys();
List<String> getClientMetadataValues(String key);
Status       addClientMetadataValues(String key, List<String> values);

// Status enum (returned by setters that validate)
enum Status { SUCCESS, FAILURE, INVALID_KEY, MULTI_VALUE_NOT_ALLOWED }
```

### 25.6 DynamicClientFields Enum

Maps RFC 7591/7592 field names to their wire-format strings.

```java
// com.pingidentity.sdk.oauth20.registration.DynamicClientFields

CLIENT_ID("client_id"),  CLIENT_NAME("client_name"),  CLIENT_SECRET("client_secret"),
REDIRECT_URIS("redirect_uris"),  GRANT_TYPES("grant_types"),  SCOPE("scope"),
TOKEN_ENDPOINT_AUTH_METHOD("token_endpoint_auth_method"),
TOKEN_ENDPOINT_AUTH_SIGNING_ALG("token_endpoint_auth_signing_alg"),
TLS_CLIENT_AUTH_SUBJECT_DN("tls_client_auth_subject_dn"),
ID_TOKEN_SIGNED_RESPONSE_ALG, ID_TOKEN_ENCRYPTED_RESPONSE_ALG, ID_TOKEN_ENCRYPTED_RESPONSE_ENC,
JWKS_URI, JWKS, LOGO_URI, RESPONSE_TYPES, SOFTWARE_STATEMENT,
REQUEST_OBJECT_SIGNING_ALG, AUTHORIZATION_DETAILS_TYPES,
BACKCHANNEL_TOKEN_DELIVERY_MODE, BACKCHANNEL_CLIENT_NOTIFICATION_ENDPOINT,
BACKCHANNEL_AUTHENTICATION_REQUEST_SIGNING_ALG, BACKCHANNEL_USER_CODE_PARAMETER,
SUBJECT_TYPE, SECTOR_IDENTIFIER_URI,
INTROSPECTION_SIGNED_RESPONSE_ALG, INTROSPECTION_ENCRYPTED_RESPONSE_ALG,
INTROSPECTION_ENCRYPTED_RESPONSE_ENC,
AUTHORIZATION_SIGNED_RESPONSE_ALG, AUTHORIZATION_ENCRYPTED_RESPONSE_ALG,
AUTHORIZATION_ENCRYPTED_RESPONSE_ENC,
REQUIRE_PUSHED_AUTHORIZATION_REQUESTS, DPOP_BOUND_ACCESS_TOKENS,
BACKCHANNEL_LOGOUT_URI, FRONTCHANNEL_LOGOUT_URI, POST_LOGOUT_REDIRECT_URIS,
CLIENT_DESCRIPTION,
USERINFO_SIGNED_RESPONSE_ALG, USERINFO_ENCRYPTED_RESPONSE_ALG,
USERINFO_ENCRYPTED_RESPONSE_ENC

String getName();   // returns the wire-format string, e.g. "client_id"
```

### 25.7 ClientRegistrationException

```java
// com.pingidentity.sdk.oauth20.registration.ClientRegistrationException

ClientRegistrationException(Response.Status httpStatus, ErrorCode error, String description);

enum ErrorCode {
    resource_not_found,
    unauthorized,
    invalid_access_token,
    invalid_payload,
    internal_error,
    invalid_client_metadata,
    invalid_software_statement,
    unapproved_software_statement,
    invalid_redirect_uri
}
```

### 25.8 ClientAuthType Enum

Maps RFC 7591 `token_endpoint_auth_method` values to PF domain types.

```java
// com.pingidentity.sdk.oauth20.registration.ClientAuthType

none                → NONE
client_secret_basic → SECRET
client_secret_post  → SECRET
tls_client_auth     → CLIENT_CERT
private_key_jwt     → PRIVATE_KEY_JWT
client_secret_jwt   → CLIENT_SECRET_JWT
```

### 25.9 NotificationEventType Enum (Complete)

All 33 notification event types available in PF 13.0:

```java
// com.pingidentity.sdk.notification.NotificationEventType

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

### 25.10 OOBAuthResultContext.Status

```java
enum Status { SUCCESS, IN_PROGRESS, FAILURE }
```

### 25.11 Plugin Metadata

```java
// com.pingidentity.sdk.PluginMetadataKeys — keys for Plugin.getPluginMetadata()
EARLY_CREATE_AND_CONFIGURE            // plugin is configured early in PF startup
PING_ONE_SERVICE_ASSOCIATION          // association with PingOne services
FIPS_STATUS                           // plugin FIPS compliance
TRY_LOOKUP_AUTHN                      // try lookupAuthN even if no prior session
SUPPORTS_CLIENT_SIDE_AUTHENTICATION   // supports client-side auth (e.g., WebAuthn)
CLIENT_SIDE_AUTHENTICATION_CLASS      // class for client-side auth

// com.pingidentity.sdk.PluginFipsStatus
enum PluginFipsStatus {
    NOT_ASSESSED("Not Assessed"),
    COMPLIANT("Compliant"),
    NOT_COMPLIANT("Not Compliant");
}

// com.pingidentity.sdk.PluginServiceAssociation — PingOne service association
// Built-in service display names:
String DIRECTORY_SERVICE_DISPLAY_NAME = "Directory Service";
String RISK_SERVICE_DISPLAY_NAME      = "Risk Management Service";
String PROTECT_SERVICE_DISPLAY_NAME   = "Protect Service";
String MFA_SERVICE_DISPLAY_NAME       = "MFA Service";
String VERIFY_SERVICE_DISPLAY_NAME    = "Verify Service";
```

### 25.12 AccountUnlockablePasswordCredential

Optional interface for PCVs that support account locking/unlocking.

```java
// com.pingidentity.sdk.account.AccountUnlockablePasswordCredential

boolean unlockAccount(String username);     // returns true on success
boolean isAccountLocked(String username);   // check lock status
boolean isAccountUnlockable();              // whether this PCV supports unlock at all
```

### 25.13 CaptchaProvider SPI

```java
// com.pingidentity.sdk.captchaprovider.CaptchaProvider extends Plugin

// Action constants — define when CAPTCHA should be shown
String IDENTIFY_ACTION          = "identify";
String LOGIN_ACTION             = "login";
String USERNAME_RECOVERY_ACTION = "usernameRecovery";
String PASSWORD_RESET_ACTION    = "passwordReset";
String PASSWORD_CHANGE_ACTION   = "passwordChange";
String REGISTRATION_ACTION      = "registration";

// Required methods
String                getJavaScriptFileName();
Map<String, Object>   getCaptchaAttributes(CaptchaContext context);
CaptchaResult         validateCaptcha(CaptchaContext context);

// Optional callback (default method)
default void postAuthenticationCallback(AuthenticationStatus status, CaptchaContext context);
```

### 25.14 AuthorizationDetailProcessor SPI

For implementing Rich Authorization Requests (RFC 9396) processing.

```java
// com.pingidentity.sdk.authorizationdetails.AuthorizationDetailProcessor extends Plugin

AuthorizationDetailValidationResult validate(
    AuthorizationDetail detail, AuthorizationDetailContext ctx, Map<String, Object> inParams);

AuthorizationDetail enrich(
    AuthorizationDetail detail, AuthorizationDetailContext ctx, Map<String, Object> inParams);

String getUserConsentDescription(
    AuthorizationDetail detail, AuthorizationDetailContext ctx, Map<String, Object> inParams);

boolean isEqualOrSubset(AuthorizationDetail detail1, AuthorizationDetail detail2,
    AuthorizationDetailContext ctx, Map<String, Object> inParams);
```

### 25.15 SessionStorageManager SPI

External session storage for PF-managed authentication sessions.

```java
// org.sourceid.saml20.service.session.data.SessionStorageManager

void createSessionGroup(SessionGroupData data);
void updateSessionGroup(SessionGroupData data);
void deleteSessionGroups(Collection<String> sessionRefs);
void deleteSessionGroupsByGroupIds(Collection<String> groupIds);

Collection<SessionGroupAndSessionsData> getSessionGroupsAndSessions(Collection<String> refs);
Collection<SessionGroupAndSessionsData> getSessionGroupsAndSessionsByGroupIds(Collection<String> ids);
Collection<SessionGroupData>            getSessionGroupsByUniqueUserId(String userId);

void    saveAuthnSessions(Collection<AuthnSessionData> sessions);
void    deleteAuthnSessions(String sessionGroupRef, Collection<String> sessionIds);
void    addUniqueUserId(String sessionGroupRef, String uniqueUserId);
boolean supportsBatchCleanup();
void    deleteExpiredSessionGroups();
boolean isDataSourceInUse(String dataSourceId);
```

---

## 26. Advanced Implementation Patterns

> **Tags:** `patterns`, `state`, `configuration`, `API`, `lifecycle`, `resilience`, `best-practice`

This section distills production-hardened implementation patterns for PingFederate
SDK plugin development. These patterns represent the canonical way to solve
common plugin development problems, covering configuration, state management,
authentication flow control, external service integration, and error handling.

### 26.1 ParamMapping: Type-Safe Dual-Path Parameter Extraction

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

### 26.2 Session Key Namespacing Strategy

Use a consistent namespacing pattern for session/transaction state keys to
prevent cross-instance collisions:

```java
// Per-adapter-instance keys (most precise, used when "Per Adapter" session state)
this.SESSION_KEY = getClass().getSimpleName() + ":" + config.getId() + ":" + keyName;

// Global keys (used when "Globally" session state)
this.SESSION_KEY = getClass().getSimpleName() + ":" + keyName;

// Examples:
"MyIdpAdapter:myInstanceId:SESSION"
"MyIdpAdapter:myInstanceId:username"
"MyIdpAdapter:myInstanceId:first-activity"
"MyIdpAdapter:myInstanceId:last-activity"

// Login context key (never global)
this.SESSION_KEY_LOGIN_CONTEXT =
    getClass().getSimpleName() + ":" + config.getId() + ":loginContext";
```

> ⚠️ **Important:** Always namespace session keys with `getClass().getSimpleName()`
> and the adapter instance ID. Failure to do this causes silent state collision
> when multiple instances of the same adapter type are configured.

### 26.3 Descriptor Metadata Flags

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

### 26.4 PluginApiSpec Construction

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

### 26.5 AUTHN_STATUS.INTERACTION_REQUIRED Pattern

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

### 26.6 AuthnPolicy Introspection

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
```

### 26.7 ConfigurationValidator Pattern

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

### 26.8 HandlerRegistry for Sub-Flows

Adapters can dynamically register HTTP request handlers for sub-flows
such as password reset, change password, and account recovery:

```java
// During configure():
private void registerPasswordResetHandlers() {
    HandlerRegistry.registerHandler("/pwdreset/Identify",
        new IdentifyServlet());
    HandlerRegistry.registerHandler("/pwdreset/SecurityCode",
        new SecurityCodeServlet());
    HandlerRegistry.registerHandler("/pwdreset/Success",
        new SuccessServlet());
    HandlerRegistry.registerHandler("/pwdreset/Resume",
        new ResumeServlet());
    HandlerRegistry.registerHandler("/pwdreset/Error",
        new ErrorServlet());
    HandlerRegistry.registerHandler("/pwdreset/Reset",
        new ResetServlet());
    HandlerRegistry.registerHandler("/pwdreset/Unlock",
        new AccountUnlockServlet());
}
```

These handlers are registered against PF's internal `HandlerRegistry` using
sub-paths that become accessible under the adapter's configured path.

### 26.9 PasswordCredentialValidatorAccessor Chain

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

### 26.10 Composite Adapter Chaining Mechanics

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

// Multi-value attribute for tracking which adapters succeeded:
List<String> loggedInAdapters = new ArrayList<>(
    existingValue.getValuesAsCollection());
loggedInAdapters.add(currentAdapterId);
attributes.put("logged-in-adapters", new AttributeValue(loggedInAdapters));
```

### 26.11 AttributeValue Multi-Value Construction

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

### 26.12 Selector Callback Pattern

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

### 26.13 CAPTCHA Integration Pattern

CAPTCHA providers use a dual-path validation approach to support both
browser form submissions and API requests:

```java
// In CaptchaProvider implementation:
protected String getCaptchaResponse(CaptchaContext captchaContext) {
    HttpServletRequest request = captchaContext.getRequest();
    AuthnApiSupport apiSupport = AuthnApiSupport.getDefault();

    // API path: deserialize from JSON body
    if (apiSupport.isApiRequest(request) && request.getMethod().equals("POST")) {
        Map requestBody = apiSupport.deserializeAsMap(request);
        Object captchaResponse = requestBody.get("captchaResponse");
        if (captchaResponse instanceof String) {
            return (String) captchaResponse;
        }
        return null;
    }

    // Browser path: standard form parameter
    return request.getParameter("g-recaptcha-response");
}
```

Adapters integrate CAPTCHA via the `CaptchaProviderAccessor`:

```java
// Post-authentication callback to report success/failure to risk providers
private void captchaPostAuthenticationCallback(HttpServletRequest req,
    HttpServletResponse resp, Map<String, Object> inParameters,
    AuthnAdapterResponse authnAdapterResponse) {

    CaptchaProvider captchaProvider = getCaptchaProvider();
    if (captchaProvider != null) {
        CaptchaProvider.AuthenticationStatus status =
            authnAdapterResponse.getAuthnStatus() == AUTHN_STATUS.SUCCESS
                ? CaptchaProvider.AuthenticationStatus.SUCCESS
                : CaptchaProvider.AuthenticationStatus.FAILURE;
        CaptchaContext context = new CaptchaContext.Builder()
            .setRequest(req).setResponse(resp)
            .setAction(CaptchaProvider.AUTHN_AUTHENTICATE_ACTION)
            .setInParameters(inParameters).build();
        captchaProvider.postAuthenticationCallback(status, context);
    }
}
```

### 26.14 REST Data Source: JSON Pointer Attribute Mapping

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

### 26.15 Bearer Access Token Management Plugin

The `BearerAccessTokenManagementPlugin` SPI allows custom access token formats:

```java
// com.pingidentity.sdk.oauth20.BearerAccessTokenManagementPlugin extends Plugin

// Validate an incoming token string → AccessToken (or null if invalid)
AccessToken validateAccessToken(String tokenValue);

// Issue a new access token → IssuedAccessToken with value, type, and expiry
IssuedAccessToken issueAccessToken(
    Map<String, AttributeValue> attributes, Scope scope,
    String clientId, String accessGrantGuid);

// Overload with sequence number for token chaining
IssuedAccessToken issueAccessToken(
    Map<String, AttributeValue> attributes, Scope scope,
    String clientId, String accessGrantGuid, int tokenManagerSequenceNumber);
```

**Optional revocation support** — implement `AccessTokenRevocable`:

```java
// com.pingidentity.sdk.oauth20.AccessTokenRevocable
void revokeAllAccessTokens(String accessGrantGuid);
void revokeAllAccessTokensByClient(String clientId);
boolean revokeAccessToken(String tokenValue);
AccessToken getAccessToken(String tokenValue);
```

The returned `IssuedAccessToken` carries:
- `getTokenValue()` — the opaque or JWT string sent to the client
- `getTokenType()` — always `"Bearer"` (constant `TOKEN_TYPE`)
- `getExpiresAt()` — absolute epoch milliseconds

### 26.16 Notification Publisher Pattern

The `NotificationPublisherPlugin` SPI enables event-driven notifications
to external services:

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
    // e.g., AWS SNS, Azure Event Grid, custom webhook
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

### 26.17 Common inParameters Keys

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
| `IN_PARAMETER_NAME_ADAPTER_ACTION` | `...adapter.action` | `String` | Adapter action (see `ADAPTER_ACTION_*`) |

**Adapter action constants:**

| Constant | Value | Purpose |
|----------|-------|---------|
| `ADAPTER_ACTION_EXTERNAL_CONSENT` | `...action.external.consent` | External consent flow |
| `ADAPTER_ACTION_PASSWORD_RESET` | `...pf.passwordreset` | Password reset sub-flow |
| `ADAPTER_ACTION_CHANGE_PASSWORD` | `...pf.change.password` | Change password sub-flow |

> 💡 **Best Practice:** Always reference these via `IdpAuthenticationAdapterV2.IN_PARAMETER_NAME_*`
> constants rather than hardcoding the string values. This protects against key changes in future SDK versions.

### 26.18 LockingService: Account Lockout Integration

Adapters can integrate with PF's account locking infrastructure:

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
    // Account is locked — return failure
    if (failAuthenticationOnAccountLockout) {
        return AUTHN_STATUS.FAILURE;
    }
}
```

### 26.19 Cookie Management Pattern

Adapters that manage persistent cookies (e.g., "Remember My Username",
"This is My Device") should use instance-scoped cookie names:

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

### 26.20 Reflective Accessor Pattern for Backward Compatibility

Plugins deployed across multiple PF versions may need to call internal
server classes that do not exist in all versions. The recommended pattern
wraps the target class via pure reflection with fail-fast constructor
validation:

```java
public class ReflectiveServiceAccessor {
    private final Object targetInstance;
    private final Method getEnvironmentId;
    private final Method getAccessToken;

    public ReflectiveServiceAccessor(String configValue) {
        // All lookups in constructor — fail-fast if class missing
        Class<?> clazz = Class.forName(
            "com.pingidentity.access.SomeServerClass");
        Constructor<?> ctor = clazz.getConstructor(String.class);
        this.targetInstance = ctor.newInstance(configValue);
        this.getEnvironmentId  = clazz.getMethod("getEnvironmentId");
        this.getAccessToken    = clazz.getMethod("getAccessToken");
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

A companion `ReflectionUtils.getFieldValue(Class, String, T defaultValue)` utility
provides safe fallback when optional SDK fields are absent:

```java
public static <T> T getFieldValue(Class<?> clazz, String fieldName, T defaultValue) {
    try {
        Object fieldObj = clazz.getField(fieldName).get(null);
        if (defaultValue.getClass().isInstance(fieldObj)) return (T) fieldObj;
    } catch (NoSuchFieldException | IllegalAccessException e) { /* use default */ }
    return defaultValue;
}
```

**When to use:** Any time your plugin needs to call internal PF server classes that
may not exist in older versions, or when accessing optional SDK extensions like
`PluginServiceAssociation` or `PluginMetadataKeys`.

### 26.21 State Support Factory + Typed Wrapper Pattern

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
        // DO NOT remove nonce — consumed already
    }
}
```

Key insight: the factory is typically assigned as a method reference in production
(`AdapterStateSupport::new`) and injected via constructor in tests.

For adapters with many state keys, add typed accessor methods with sensible defaults:

```java
public class StateSupport {
    private final TransactionalStateSupport tss;

    public String getTransactionId(HttpServletRequest req, HttpServletResponse resp) {
        return getStringAttribute("transactionId", req, resp);
    }
    public boolean getFirstLookUp(HttpServletRequest req, HttpServletResponse resp) {
        Object v = getAttribute("firstLookUp", req, resp);
        return v == null ? true : (Boolean) v;  // default=true on first call
    }
    public void cleanup(HttpServletRequest req, HttpServletResponse resp) {
        // Exhaustive cleanup of all state keys
        removeAttribute("savedError", req, resp);
        removeAttribute("transactionId", req, resp);
        removeAttribute("userId", req, resp);
        removeAttribute("qrCode", req, resp);
        // ... all remaining keys
    }
}
```

### 26.22 TransactionAwareAuthenticationAdapter: Feedback Loop Pattern

`TransactionAwareAuthenticationAdapter` enables post-authentication feedback to
external services (e.g., risk engines, fraud detection). Store correlation IDs
during `lookupAuthN`, then retrieve and clean them up in the transaction callbacks:

```java
public class RiskAwareAdapter
    implements IdpAuthenticationAdapterV2, AuthnApiPlugin,
               TransactionAwareAuthenticationAdapter {

    // Called by PF engine after successful authentication
    public void onTransactionComplete(HttpServletRequest request,
            HttpServletResponse response, Map<String, Object> authnIds,
            AttributeMap policyResult, Map<String, Object> inParameters) {
        sendFeedback(request, response, CompletionStatus.SUCCESS, inParameters);
    }

    // Called by PF engine on authentication failure
    public void onTransactionFailure(HttpServletRequest request,
            HttpServletResponse response, Map<String, Object> authnIds,
            Map<String, Object> inParameters) {
        sendFeedback(request, response, CompletionStatus.FAILED, inParameters);
    }

    private void sendFeedback(..., CompletionStatus status, ...) {
        // Retrieve correlation ID from session
        String evalId = sessionWrapper
            .getEvaluationId(instanceId, request, response);
        // Clean up immediately — one-shot feedback
        sessionWrapper.removeEvaluationId(instanceId, request, response);
        if (StringUtils.isNotBlank(evalId)) {
            externalService.submitFeedback(evalId, trackingId,
                transactionId, status);
        }
    }
}
```

**Pattern:** The 5-arg overload with `inParameters` is preferred over the 4-arg
version. Always clean up correlation state in both success and failure paths.

### 26.23 CaptchaProvider SPI: Dual-Role Implementation

A single JAR can ship both a standalone IdP adapter and a `CaptchaProvider`
that embeds into other adapters. The `CaptchaProvider` SPI has three
lifecycle methods:

```java
public class RiskProvider
    implements CaptchaProvider, TransactionAwareAuthenticationAdapter {

    // 1. Return JavaScript file name for client-side data collection
    public String getJavaScriptFileName() { return "signals.js"; }

    // 2. Return custom attributes for the template engine
    public Map<String, Object> getCaptchaAttributes(CaptchaContext ctx) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("collectGeoLocation", isGeoLocationAllowed());
        attrs.put("collectDeviceTrustAttributes", enableDeviceTrust);
        return attrs;
    }

    // 3. Validate — store signals in transactional state, optionally evaluate risk
    public CaptchaResult validateCaptcha(CaptchaContext ctx) {
        HttpServletRequest request = ctx.getRequest();
        String resumeURL = (String) ctx.getInParameters()
            .get("com.pingidentity.adapter.input.parameter.resume.path");

        // Persist signals for later retrieval by the companion adapter
        transactionalWrapper.setSignals(resumeURL,
            extractSignals(request, authnApiSupport),
            request, response);

        if (enableRiskEvaluation) {
            AuthnAdapterResponse resp = evaluateRisk(...);
            if (resp.getAuthnStatus() == FAILURE) {
                return CaptchaResult.createInvalidResult(attributeMap);
            }
            transactionalWrapper.setAuthnResponse(resumeURL, resp, request, response);
        }

        if (followRecommendedAction && isBlockAction(recommendedAction)) {
            return CaptchaResult.createInvalidResult(attributeMap);
        }
        return CaptchaResult.createValidResult(attributeMap);
    }
}
```

**Key insight:** The Provider stores its results in transactional state keyed by
`resumeURL`. The companion IdP adapter checks for these results at the start of
`lookupAuthN`, enabling the provider-then-adapter pipeline.

### 26.24 Multi-State Authentication API Plugin

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

### 26.25 Custom AuthnErrorDetail Extension

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

### 26.26 CoreContract Enum: Type-Safe Attribute Contract

Define attribute contracts via an enum rather than raw strings. The enum can
additionally map attribute names to JSON Pointer paths for API response extraction:

```java
public enum CoreContract {
    SUBJECT("subject"),
    TRANSACTION_STATUS("transactionStatus"),
    FIRST_NAME("firstName"),
    BIRTH_DATE("birthDate"),
    // ... additional contract attributes
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

### 26.27 Exponential Backoff for External API Calls

Plugins that call external APIs should implement thread-safe exponential backoff
with jitter for resilient retries:

```java
public class ExponentialBackOffCalculator {
    private static final double BASE_DELAY_MS = 1000.0;
    private static final long MAX_DELAY_MS = 7_200_000L;  // 2 hours
    private int retryAttemptNumber = 0;
    private boolean backOffEnforced = false;

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

    public synchronized void enableBackOffEnforcement()  { backOffEnforced = true;  retryAttemptNumber = 0; }
    public synchronized void resetBackOffEnforcement()   { backOffEnforced = false; retryAttemptNumber = 0; }
}
```

**Usage in data source drivers and credential validators:**

```java
// In configure():
this.maxRetriesAttempt = 5;
this.retryRequest = true;
this.errorCodeForRetries = Arrays.asList("429", "500", "503");

// In retrieveValues() or processPasswordCredential():
if (errorCodeForRetries.contains(String.valueOf(statusCode))) {
    if (backoffCalculator.shouldRetry() && attempt < maxRetriesAttempt) {
        Thread.sleep(backoffCalculator.calculateRetryDelay());
        // retry...
    }
}
```

### 26.28 OOBAuthPlugin SPI (CIBA Out-of-Band Authentication)

The `OOBAuthPlugin` interface enables CIBA (Client-Initiated Backchannel
Authentication) flows. A single JAR can ship both an IdP adapter (for browser
flows) and an OOB plugin (for CIBA), sharing configuration and API clients:

```java
public class MfaCibaAuthenticator implements OOBAuthPlugin {

    // Start an OOB authentication request — called by CIBA endpoint
    public OOBAuthTransactionContext beginTransaction(OOBAuthRequestContext ctx)
            throws OOBAuthGeneralException, UnknownUserException,
                   UserAuthBindingMessageException {
        // Initiate push notification to user's device
        // Return transaction context for polling
    }

    // Check if authentication is complete
    public OOBAuthResultContext lookupTransaction(OOBAuthTransactionContext ctx)
            throws OOBAuthGeneralException {
        // Poll external MFA service for flow status
        // Return result: SUCCESS, PENDING, FAILED, EXPIRED
    }

    // Cancellation
    public void cancelTransaction(OOBAuthTransactionContext ctx)
            throws OOBAuthGeneralException {
        // Cancel the pending authentication flow
    }
}
```

**Key observations:**
- Same JAR ships both the IdP adapter (for browser flows) and OOB plugin (for CIBA)
- Both share configuration classes, API clients, and token services
- The CIBA authenticator maintains its own state wrapper for key-value state

### 26.29 Structured LogEvent Enum Pattern

For plugins with many log points, define a `LogEvent` enum with product-specific
codes for structured logging and log aggregation:

```java
public enum CustomLogEvent implements LogEvent {
    USER_NOT_AUTHENTICATED(LogLevel.ERROR, "000", "User arrived without prior auth."),
    ACCESS_TOKEN_EXCEPTION(LogLevel.WARN,  "003", "Token acquisition failed: %s"),
    AUTH_BYPASSED(LogLevel.DEBUG,           "006", "Auth bypassed per configuration."),
    RATE_LIMIT_EXCEEDED(LogLevel.ERROR,     "007", "Rate limit exceeded for %s"),
    // ... additional events as needed
    ;

    private static final String PRODUCT_CODE = "MYPLUGIN";
    private final LogLevel level;
    private final String code;
    private final String message;

    @Override
    public String getCode() { return PRODUCT_CODE + "-" + code; }
}

// Usage:
LOGGER.log(CustomLogEvent.ACCESS_TOKEN_EXCEPTION, exception);
LOGGER.log((LogEvent) CustomLogEvent.AUTH_BYPASSED);
```

**Pattern benefits:**
- Structured log codes (`MYPLUGIN-003`) enable log aggregation and alerting
- `LogLevel` enum provides compile-time level enforcement
- Format-string messages (`"%s"` placeholders) avoid string concatenation overhead
- Each plugin uses a unique product code prefix for filtering

### 26.30 Built-in FieldValidator Implementations

The SDK ships with ready-to-use `FieldValidator` implementations in
`org.sourceid.saml20.adapter.gui.validation.impl`. Plugin writers can reference
these directly instead of writing custom validation:

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
| `TableColumnValuesUniqueValidator`† | `ConfigurationValidator` — enforces uniqueness across table rows |

†`TableColumnValuesUniqueValidator` implements `ConfigurationValidator`, not `FieldValidator`.

```java
// Usage in descriptor construction:
FieldDescriptor connectionUrl = new TextFieldDescriptor(
    "Connection URL", "Target server URL");
connectionUrl.addValidator(new RequiredFieldValidator());
connectionUrl.addValidator(new HttpsURLValidator());
```

### 26.31 AuthnPolicy: Registration and AuthnContext Inspection

The `AuthnPolicy` object passed via `inParameters` exposes additional state
beyond the commonly documented `allowUserInteraction()` and `reauthenticate()`:

```java
AuthnPolicy policy = (AuthnPolicy) inParameters.get(
    IdpAuthenticationAdapterV2.IN_PARAMETER_NAME_AUTHN_POLICY);

// Check if the SP requested user registration
if (policy.registrationRequested()) {
    // Redirect to registration flow instead of login
}

// Inspect requested authentication context classes
List<String> requestedContexts = policy.getRequestAuthnContexts();
// e.g. "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport"
// Use AuthnContextClassRef constants for comparison
```

> 💡 **Tip:** The `AuthnContextClassRef` class provides all standard SAML 2.0
> authentication context URIs as constants (e.g., `PASSWORD_PROTECTED_TRANSPORT`,
> `KERBEROS`, `X509`, `SMARTCARD_PKI`, `TLS_CLIENT`, `TIME_SYNC_TOKEN`).

### 26.32 HandlerRegistry Thread Safety

`HandlerRegistry` uses `synchronized` methods for both `registerHandler()` and
`getHandler()`. This guarantees safe concurrent handler registration during
multi-threaded adapter initialization:

```java
// HandlerRegistry is internally synchronized — safe to call from configure():
HandlerRegistry.registerHandler("/ext/my-adapter/callback", this::handleCallback);
HandlerRegistry.registerHandler("/ext/my-adapter/logout", logoutHandler);

// getHandler() returns a 404 handler if no match — never returns null
Handler handler = HandlerRegistry.getHandler(requestPath);
handler.handle(request, response);
```

### 26.33 ConfigCache: Thread-Safe Hot-Reloading Configuration

`ConfigCache<T>` wraps a `Supplier<T>` with server-managed cache invalidation.
When PF reloads configuration (admin console save, cluster replication), the
cache is automatically cleared and re-populated on next `get()`:

```java
// In your adapter class:
private ConfigCache<MyParsedConfig> configCache;

@Override
public void configure(Configuration configuration) {
    this.configCache = new ConfigCache<>(() -> {
        // Expensive config parsing — called once per configuration change
        return MyParsedConfig.parse(configuration);
    });
}

@Override
public AuthnAdapterResponse lookupAuthN(...) {
    MyParsedConfig cfg = configCache.get();  // Cached, thread-safe
    // ... use cfg
}
```

> ⚠️ **Lifecycle:** Create the `ConfigCache` in `configure()`, not in the
> constructor. The `ServiceFactory` backing it requires a running PF context.

### 26.34 SecretReferenceUtil: Secret Manager Integration Format

Secrets stored in PF's Secret Manager use the `OBF:MGR:` prefix format.
The `SecretReferenceUtil` utility class provides parsing and validation:

```java
String fieldValue = configuration.getFieldValue("apiKey");

if (SecretReferenceUtil.isSecretReference(fieldValue)) {
    // Format: "OBF:MGR:<secretManagerId>:<secretId>"
    String managerId = SecretReferenceUtil.getSecretManagerId(fieldValue);
    String secretId  = SecretReferenceUtil.getSecretId(fieldValue);

    // Retrieve the actual secret value at runtime:
    SecretInfo secretInfo = SecretManagerAccessor.getSecretInfo(
        fieldValue, inParameters);
    String actualValue = secretInfo.getValue();
} else {
    // Plain-text value (not recommended for production)
    String actualValue = fieldValue;
}

// In GUI descriptor — use SecretReferenceFieldValidator:
field.addValidator(new SecretReferenceFieldValidator());
```

### 26.35 LoggingUtil: Structured Audit Trail

`LoggingUtil` provides a thread-local audit logging API that integrates with
PF's security audit log. Adapters should call `init()` at the start of
authentication and `log()` at the end to produce audit-grade records:

```java
@Override
public AuthnAdapterResponse lookupAuthN(HttpServletRequest req, ...) {
    LoggingUtil.init();
    LoggingUtil.setEvent("MY_ADAPTER_AUTHN");
    LoggingUtil.setRemoteAddress(req.getRemoteAddr());
    LoggingUtil.setHost(req.getServerName());

    try {
        // ... authentication logic ...
        LoggingUtil.setUserName(username);
        LoggingUtil.setStatus(LoggingUtil.SUCCESS);
        LoggingUtil.log("Authentication successful for " + username);
    } catch (Exception e) {
        LoggingUtil.setStatus(LoggingUtil.FAILURE);
        LoggingUtil.setDescription("Authentication failed: " + e.getMessage());
        LoggingUtil.log("Authentication failed");
    } finally {
        LoggingUtil.cleanup();  // REQUIRED — releases thread-local state
    }
}
```

**Available LoggingUtil setters:**

| Method | Purpose |
|--------|---------|
| `setStatus(String)` | `SUCCESS` or `FAILURE` |
| `setUserName(String)` | Authenticated username |
| `setEvent(String)` | Event type identifier |
| `setDescription(String)` | Human-readable description |
| `setPartnerId(String)` | SP entity ID / partner |
| `setHost(String)` | Request hostname |
| `setRemoteAddress(String)` | Client IP address |
| `setProtocol(String)` | Protocol identifier |
| `setRole(String)` | Adapter role (IdP/SP) |
| `setAccessTokenJti(String)` | OAuth access token JTI |
| `setRequestJti(String)` | Request JTI |
| `setInMessageContext(String)` | Inbound message context |
| `setOutMessageContext(String)` | Outbound message context |
| `setRequestStartTime(Long)` | Request start timestamp |
| `updateResponseTime()` | Auto-calculated from start time |
| `calculateHashForLogging(String)` | SHA-1 + Base64URL hash for PII |

> ⚠️ **Critical:** Always call `LoggingUtil.cleanup()` in a `finally` block.
> Failure to do so leaks thread-local state and corrupts audit records on that
> thread's next request.

### 26.36 AUTHN_STATUS Enum and ErrorCode Semantics

The `AuthnAdapterResponse.AUTHN_STATUS` enum has five values:

| Status | Meaning | HTTP behavior |
|--------|---------|---------------|
| `SUCCESS` | Authentication complete — attributes populated | PF continues policy chain |
| `IN_PROGRESS` | Adapter rendered a form or redirect — request not yet complete | PF waits for callback |
| `FAILURE` | Authentication definitively failed | PF triggers failure handling |
| `ACTION` | Adapter requests a specific action (e.g., password change) | PF routes to action flow |
| `INTERACTION_REQUIRED` | Non-interactive lookup attempted but user interaction needed | PF falls through to next adapter |

The `errorCode` and `errorMessage` fields on `AuthnAdapterResponse` are only
meaningful when status is `FAILURE`:

```java
AuthnAdapterResponse response = new AuthnAdapterResponse();
response.setAuthnStatus(AUTHN_STATUS.FAILURE);
response.setErrorCode(1001);  // Custom error code for audit
response.setErrorMessage("Account locked after 5 failed attempts");
// errorCode appears in audit logs for operational alerting
```

---

## 27. Hardening Checklist

> **Tags:** `production`, `hardening`, `security`, `cluster`, `monitoring`

### Logging

- [ ] Replace all `System.out` / `System.err` with a consistent logger
- [ ] Use commons-logging or SLF4J (PF ships logging infrastructure)
- [ ] Never log credentials, tokens, or PII at INFO level

### Security

- [ ] Validate and sanitize all form/API input before processing
- [ ] Use `HashedTextFieldDescriptor` for sensitive config values (passwords, keys)
- [ ] Use `SecretReferenceFieldDescriptor` + Secret Manager for externalized secrets
- [ ] Implement CSRF protection for HTML form submissions
- [ ] Set `HttpOnly`, `Secure`, `SameSite` on any custom cookies

### Clustering

- [ ] Ensure `configure()` state is purely derived from `Configuration` (no local-only state)
- [ ] Use PingFederate Session Revocation Service (SRI) for cluster-wide logout
- [ ] Do not rely on node-local in-memory storage for cross-request state
- [ ] Use `SessionStateSupport` for session data — PF handles replication

### Error Handling

- [ ] Handle `AuthnAdapterException`, `AuthnErrorException`, and `IOException`
- [ ] Return proper `AUTHN_STATUS` values even on error paths
- [ ] Use typed `AuthnErrorSpec` / `AuthnErrorDetailSpec` for API errors

### Configuration

- [ ] Use `RequiredFieldValidator` for mandatory fields
- [ ] Use `IntegerValidator(min, max)` for bounded numeric fields
- [ ] Add `ConfigurationValidator` for cross-field constraints
- [ ] Set `setDefaultForLegacyConfig()` when adding new fields to existing plugins

### Thread Safety

- [ ] All mutable state initialized in `configure()` only
- [ ] No shared mutable state across request-processing methods
- [ ] Use `ConcurrentHashMap` or similar if custom caching is needed

---

## 28. SDK-Relevant Release Deltas

> **Tags:** `release`, `delta`, `SRI`, `SessionManager`, `GuiConfigDescriptorBuilder`

| Version | SDK Change |
|---------|------------|
| 6.4 | `IdpAuthenticationAdapterV2` introduced (replaced older version) |
| 6.11 | `GuiConfigDescriptorBuilder` introduced; `getGuiConfigDescriptor()` deprecated |
| 7.3 | `AuthenticationSelector` introduced (replaced `AdapterSelector`) |
| 10.2 | `PluginDescriptor.getMetadata()` / `setMetadata()` added |
| 12.0+ | Session Revocation Service (SRI) available; `IN_PARAMETER_NAME_SRI` added |
| 13.0 | Authorization details support (`AuthorizationDetailProcessor`); `SecretManager` SPI; `CaptchaProvider` SPI |

---

## 29. Build-Deploy Quick Start

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

## 30. Source Inventory

> **Tags:** `sources`, `inventory`, `traceability`

### Official Documentation

| Source | Location |
|--------|----------|
| SDK Developer's Guide chapter | `docs/reference/vendor/pingfederate_server-13.0.txt` (lines ~82690–84430) |
| Authentication API chapter | `docs/reference/vendor/pingfederate_server-13.0.txt` (lines ~84430+) |

### SDK Javadoc Packages Consulted

| Package | Coverage |
|---------|----------|
| `com.pingidentity.sdk` | Core interfaces: `ConfigurablePlugin`, `DescribablePlugin`, `PluginDescriptor`, `GuiConfigDescriptor`, `GuiConfigDescriptorBuilder`, `AuthenticationSelector`, `IdpAuthenticationAdapterV2` |
| `com.pingidentity.sdk.api.authn` | Authentication API: `AuthnApiPlugin`, `AuthnApiSupport`, `AuthnApiSpec`, `AuthnState`, `ActionSpec`, `AuthnStateSpec` |
| `com.pingidentity.sdk.accessgrant` | Access grant management |
| `com.pingidentity.sdk.captchaprovider` | CAPTCHA/risk provider |
| `com.pingidentity.sdk.clientauth` | Client authentication |
| `com.pingidentity.sdk.key` | Key management |
| `com.pingidentity.sdk.locale` | Locale support |
| `com.pingidentity.sdk.logging` | Logging utilities |
| `com.pingidentity.sdk.notification` | Notification publisher |
| `com.pingidentity.sdk.oauth20` | Token management, registration |
| `com.pingidentity.sdk.oobauth` | CIBA / OOB authentication |
| `com.pingidentity.sdk.password` | Password credential validation |
| `com.pingidentity.sdk.provision` | Identity store provisioning (+ `exception`, `groups`, `users` sub-packages) |
| `com.pingidentity.sdk.secretmanager` | Secret manager |
| `com.pingidentity.sdk.session` | `SessionManager` utility |
| `com.pingidentity.sdk.template` | `TemplateRendererUtil` |
| `com.pingidentity.sdk.util` | SDK utilities |
| `org.sourceid.saml20.adapter` | Adapter base: `AuthnAdapterDescriptor`, `ConfigurableAuthnAdapter` |
| `org.sourceid.saml20.adapter.attribute` | `AttributeValue`, `AttributeMap` |
| `org.sourceid.saml20.adapter.conf` | `Configuration`, `Field`, `FieldList`, `Table`, `Row` |
| `org.sourceid.saml20.adapter.gui` | Full descriptor hierarchy (30+ classes) |
| `org.sourceid.saml20.adapter.gui.validation` | `FieldValidator`, `RowValidator`, `ConfigurationValidator` + impls |
| `org.sourceid.saml20.adapter.gui.event` | UI event handlers |
| `org.sourceid.saml20.adapter.idp` / `.sp` | Role-specific adapter interfaces |
| `org.sourceid.saml20.adapter.state` | `SessionStateSupport`, `TransactionalStateSupport`, `KeyValueStateSupport`, `ApplicationSessionStateSupport` |
| `org.sourceid.saml20.service.session.data` | `SessionStorageManager`, `SessionGroupData`, `AuthnSessionData` |
| `org.sourceid.websso.servlet.adapter` | `Handler`, `HandlerRegistry` |
| `org.sourceid.wstrust.plugin` | STS token processor/generator |
| `com.pingidentity.access` | Service Accessor classes (`BaseUrlAccessor`, `ClusterAccessor`, `DataSourceAccessor`, etc.) |
| `com.pingidentity.sdk.account` | `AccountUnlockablePasswordCredential` |

### Sample Source Files Reviewed

All 16 sample directories in `sdk/plugin-src/` — Java source files, build
configurations, and deployment descriptors.

---

*Guide generated from PingFederate 13.0.1 SDK Javadoc, official documentation,
and bundled sample projects. Last updated: 2026-02-16.*
